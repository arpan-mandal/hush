package com.vertos.promoblocker

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import android.telecom.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import java.time.Duration

// Robolectric 4.14's stock screening shadow does not inherit ShadowService, causing
// framework Service.onDestroy() to cast-fail. Keep lifecycle support and capture Telecom.
@Implements(CallScreeningService::class)
class LifecycleCompatibleScreeningShadow : ShadowService() {
    var lastResponse: CallResponse? = null
    var lastDetails: Call.Details? = null
    @Implementation
    fun respondToCall(details: Call.Details, response: CallResponse) {
        lastDetails = details
        lastResponse = response
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [LifecycleCompatibleScreeningShadow::class])
class PromoCallScreeningServiceTest {
    class FakeRuntime : ScreeningRuntime {
        override var snapshot = ScreeningSnapshot()
        var waitForReady = false
        val ready = CompletableDeferred<Unit>()
        val records = mutableListOf<Pair<String?, String>>()
        var onRecord: () -> Unit = {}
        override suspend fun awaitReady() { if (waitForReady) ready.await() }
        override fun record(number: String?, reason: String) { onRecord(); records.add(number to reason) }
    }
    class CapturingService : PromoCallScreeningService() {
        val fake = FakeRuntime()
        val responses = mutableListOf<Pair<Call.Details, CallResponse>>()
        override val runtime: ScreeningRuntime get() = fake
        override val evaluationDispatcher = UnconfinedTestDispatcher()
        override fun dispatchResponse(details: Call.Details, response: CallResponse) { responses.add(details to response) }
    }
    class TelecomBoundaryService : PromoCallScreeningService() {
        override val runtime = FakeRuntime()
        override val evaluationDispatcher = UnconfinedTestDispatcher()
    }
    private lateinit var service: CapturingService
    @Before fun setup() { service = Robolectric.buildService(CapturingService::class.java).create().get() }
    @After fun cleanup() { service.onDestroy(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
    private fun details(number: String? = "+919876543210", direction: Int = Call.Details.DIRECTION_INCOMING, verification: Int = Connection.VERIFICATION_STATUS_NOT_VERIFIED): Call.Details {
        val details = mock(Call.Details::class.java)
        `when`(details.callDirection).thenReturn(direction)
        `when`(details.handle).thenReturn(number?.let { Uri.fromParts("tel", it, null) })
        if (Build.VERSION.SDK_INT >= 30) `when`(details.callerNumberVerificationStatus).thenReturn(verification)
        return details
    }
    private fun response() = service.responses.single().second
    @Test fun rejectionIsPassedToActualTelecomMethod() {
        val actual = Robolectric.buildService(TelecomBoundaryService::class.java).create().get()
        val call = details("+911408823412")
        try {
            actual.onScreenCall(call)
            val shadow = Shadow.extract<LifecycleCompatibleScreeningShadow>(actual)
            assertSame(call, shadow.lastDetails)
            val response = checkNotNull(shadow.lastResponse)
            assertTrue(response.disallowCall)
            assertTrue(response.rejectCall)
            assertTrue(response.skipCallLog)
            assertTrue(response.skipNotification)
        } finally { actual.onDestroy() }
    }
    @Test fun promotionalCallHasAllSilentRejectionFlags() {
        service.onScreenCall(details("+911408823412"))
        assertTrue(response().disallowCall)
        assertTrue(response().rejectCall)
        assertTrue(response().skipCallLog)
        assertTrue(response().skipNotification)
    }
    @Test fun failedCarrierVerificationRejectsImmediately() {
        service.onScreenCall(details(verification = Connection.VERIFICATION_STATUS_FAILED))
        assertTrue(response().disallowCall)
        assertEquals(NumberEvaluator.SPOOFED, service.fake.records.single().second)
    }
    @Test fun cleanNumberPasses() {
        service.onScreenCall(details())
        assertFalse(response().disallowCall)
        assertFalse(response().rejectCall)
        assertFalse(response().skipCallLog)
        assertFalse(response().skipNotification)
        assertTrue(service.fake.records.isEmpty())
    }
    @Test fun outgoingCallsBypassRulesAndVerification() {
        val call = details("+911408823412", Call.Details.DIRECTION_OUTGOING, Connection.VERIFICATION_STATUS_FAILED)
        service.onScreenCall(call)
        assertFalse(response().disallowCall)
        verify(call, never()).getHandle()
        verify(call, never()).getCallerNumberVerificationStatus()
    }
    @Test fun nullHandlePassesByDefault() { service.onScreenCall(details(null)); assertFalse(response().disallowCall) }
    @Test fun nullHandleBlocksWhenConfigured() {
        service.fake.snapshot = ScreeningSnapshot(ScreeningSettings(blockUnknown = true))
        service.onScreenCall(details(null))
        assertTrue(response().disallowCall)
    }
    @Test fun killSwitchAllowsSpoofedPromotionalCall() {
        service.fake.snapshot = ScreeningSnapshot(ScreeningSettings(enabled = false))
        service.onScreenCall(details("+911408823412", verification = Connection.VERIFICATION_STATUS_FAILED))
        assertFalse(response().disallowCall)
        assertTrue(service.fake.records.isEmpty())
    }
    @Test fun spoofedToggleIsRespected() {
        service.fake.snapshot = ScreeningSnapshot(ScreeningSettings(blockSpoofed = false))
        service.onScreenCall(details(verification = Connection.VERIFICATION_STATUS_FAILED))
        assertFalse(response().disallowCall)
    }
    @Test @Config(sdk = [29]) fun androidTenNeverReadsVerificationStatus() {
        val call = details(verification = Connection.VERIFICATION_STATUS_FAILED)
        service.onScreenCall(call)
        // Executing this callback on the API 29 runtime proves the API 30 getter is guarded.
        assertFalse(response().disallowCall)
    }
    @Test fun responsePrecedesAuditLogging() {
        service.fake.onRecord = { assertEquals(1, service.responses.size) }
        service.onScreenCall(details("1401234567"))
        assertEquals("1401234567", service.fake.records.single().first)
    }
    @Test fun coldStartWatchdogAllowsOnceAndDoesNotLog() {
        service.fake.waitForReady = true
        service.onScreenCall(details("1401234567"))
        assertTrue(service.responses.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertFalse(response().disallowCall)
        service.fake.ready.complete(Unit)
        assertEquals(1, service.responses.size)
        assertTrue(service.fake.records.isEmpty())
    }
    @Test fun serviceDestructionStillAllowsPendingCall() {
        service.fake.waitForReady = true
        service.onScreenCall(details("1401234567"))
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertFalse(response().disallowCall)
    }
    @Test fun successfulScreeningCancelsWatchdog() {
        service.onScreenCall(details("1401234567"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, service.responses.size)
    }
    @Test fun nonTelephoneHandlesAreTreatedAsUnknown() {
        val call = details()
        `when`(call.handle).thenReturn(Uri.parse("sip:1401234567@example.com"))
        service.onScreenCall(call)
        assertFalse(response().disallowCall)
    }
}
