package com.vertos.promoblocker

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

open class PromoCallScreeningService : CallScreeningService() {
    protected open val evaluationDispatcher: CoroutineDispatcher get() = Dispatchers.Default
    private val scope by lazy { CoroutineScope(SupervisorJob() + evaluationDispatcher) }
    private val handler = Handler(Looper.getMainLooper())
    protected open val runtime: ScreeningRuntime
        get() = (application as PromoApplication).repository
    // A small seam lets tests capture the exact response sent to Telecom, whose method is final.
    protected open fun dispatchResponse(details: Call.Details, response: CallResponse) {
        respondToCall(details, response)
    }
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            dispatchResponse(callDetails, ALLOW)
            return
        }
        val responded = AtomicBoolean(false)
        val fallback = Runnable {
            if (responded.compareAndSet(false, true)) dispatchResponse(callDetails, ALLOW)
        }
        // Fail open when a cold start or worker saturation exceeds the local target budget.
        handler.postDelayed(fallback, 40)
        scope.launch {
            val number = callDetails.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
            val failed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
            val reason = withTimeoutOrNull(30) {
                runtime.awaitReady()
                NumberEvaluator.evaluate(number, failed, runtime.snapshot)
            }
            if (responded.compareAndSet(false, true)) {
                handler.removeCallbacks(fallback)
                dispatchResponse(callDetails, if (reason == null) ALLOW else BLOCK)
                if (reason != null) runtime.record(number, reason)
            }
        }
    }
    override fun onDestroy() {
        // Pending watchdogs remain queued so every accepted callback receives a response.
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private val ALLOW = CallResponse.Builder().build()
        private val BLOCK = CallResponse.Builder().setDisallowCall(true).setRejectCall(true)
            .setSkipCallLog(true).setSkipNotification(true).build()
    }
}
