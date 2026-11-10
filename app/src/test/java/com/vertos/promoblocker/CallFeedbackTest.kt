package com.vertos.promoblocker

import android.app.Application
import android.telephony.TelephonyManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallFeedbackTest {
    @Test fun promptsOnlyAfterAnAnsweredAllowedCallEnds() {
        val tracker = CallFeedbackTracker()
        val number = "+919876543210"
        tracker.allowed("+91 (98765) 43210", tracker.beginCall())
        assertNull(tracker.stateChanged(TelephonyManager.EXTRA_STATE_RINGING))
        assertNull(tracker.stateChanged(TelephonyManager.EXTRA_STATE_IDLE))
        tracker.allowed(number, tracker.beginCall())
        assertNull(tracker.stateChanged(TelephonyManager.EXTRA_STATE_RINGING))
        assertNull(tracker.stateChanged(TelephonyManager.EXTRA_STATE_OFFHOOK))
        assertEquals(number, tracker.stateChanged(TelephonyManager.EXTRA_STATE_IDLE))
        assertNull(tracker.stateChanged(TelephonyManager.EXTRA_STATE_IDLE))
    }

    @Test fun overlappingCallsAreNotReported() {
        val tracker = CallFeedbackTracker()
        tracker.allowed("9876543210", tracker.beginCall())
        tracker.stateChanged(TelephonyManager.EXTRA_STATE_RINGING)
        tracker.allowed("9876543211", tracker.beginCall())
        tracker.stateChanged(TelephonyManager.EXTRA_STATE_OFFHOOK)
        assertNull(tracker.stateChanged(TelephonyManager.EXTRA_STATE_IDLE))
    }

    @Test fun feedbackRuleBlocksOnlyTheReportedNumber() {
        val rule = CompiledRule("feedback", exactFeedbackRule("+919876543210"), normalizePhoneNumber = true)
        val snapshot = ScreeningSnapshot(rules = listOf(rule))
        assertEquals("feedback", NumberEvaluator.evaluate("+91 98765-43210", false, snapshot))
        assertNull(NumberEvaluator.evaluate("+919876543211", false, snapshot))
        assertNull(NumberEvaluator.evaluate("919876543210", false, snapshot))
    }
}
