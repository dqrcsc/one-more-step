package com.oms.client.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAnswerDetectorTest {
    @Test
    fun dialingAndRingingNodesDoNotCountAsAnswered() {
        val dialingSnapshots = listOf(
            CallAnswerDetector.NodeSnapshot(text = "10086", contentDescription = "", viewIdResourceName = "com.android.incallui:id/name"),
            CallAnswerDetector.NodeSnapshot(text = "正在呼叫…", contentDescription = "", viewIdResourceName = "com.android.incallui:id/callStateLabel")
        )
        assertEquals(CallAnswerDetector.State.DIALING_OR_RINGING, CallAnswerDetector.inspect(dialingSnapshots).state)
        assertNull(CallAnswerDetector.inspect(dialingSnapshots).matchedTimer)

        val ringingSnapshots = listOf(
            CallAnswerDetector.NodeSnapshot(text = "对方响铃", contentDescription = "", viewIdResourceName = "com.miui.incallui:id/call_status")
        )
        assertEquals(CallAnswerDetector.State.DIALING_OR_RINGING, CallAnswerDetector.inspect(ringingSnapshots).state)
    }

    @Test
    fun callDurationTimerMarksCallAsAnswered() {
        val connectedSnapshots = listOf(
            CallAnswerDetector.NodeSnapshot(text = "10086", contentDescription = "", viewIdResourceName = "com.miui.incallui:id/name"),
            CallAnswerDetector.NodeSnapshot(text = "00:01", contentDescription = "", viewIdResourceName = "com.miui.incallui:id/elapsedTime")
        )
        val inspection = CallAnswerDetector.inspect(connectedSnapshots)
        assertEquals(CallAnswerDetector.State.ANSWERED, inspection.state)
        assertEquals("00:01", inspection.matchedTimer)

        val hyperOsSnapshots = listOf(
            CallAnswerDetector.NodeSnapshot(text = "SIM卡2 · 7秒", contentDescription = "", viewIdResourceName = ""),
            CallAnswerDetector.NodeSnapshot(text = "7秒", contentDescription = "", viewIdResourceName = "")
        )
        val hyperOsInspection = CallAnswerDetector.inspect(hyperOsSnapshots)
        assertEquals(CallAnswerDetector.State.ANSWERED, hyperOsInspection.state)
        assertEquals("7秒", hyperOsInspection.matchedTimer)
    }

    @Test
    fun awaitCallAnsweredDetectsTransitionFromDialingToConnectedTimer() = runBlocking {
        var pollCount = 0
        var virtualWaited = 0L
        val result = CallAnswerDetector.awaitCallAnswered(
            maxWaitMs = 5_000L,
            pollIntervalMs = 250L,
            isInCall = { true },
            observeInCallNodes = {
                pollCount += 1
                if (pollCount < 3) {
                    listOf(CallAnswerDetector.NodeSnapshot(text = "正在拨号", contentDescription = "", viewIdResourceName = ""))
                } else {
                    listOf(CallAnswerDetector.NodeSnapshot(text = "00:00", contentDescription = "", viewIdResourceName = "com.android.incallui:id/elapsedTime"))
                }
            },
            checkAuthorization = {},
            pause = { virtualWaited += it }
        )
        assertTrue(result.answered)
        assertEquals("call_answered", result.reason)
        assertEquals("00:00", result.matchedTimer)
        assertEquals(500L, virtualWaited)
    }

    @Test
    fun awaitCallAnsweredStopsImmediatelyWhenRemoteRejectsOrEndsBeforeAnswer() = runBlocking {
        var inCall = true
        var polls = 0
        val result = CallAnswerDetector.awaitCallAnswered(
            maxWaitMs = 5_000L,
            pollIntervalMs = 250L,
            isInCall = { inCall },
            observeInCallNodes = {
                polls += 1
                if (polls == 2) {
                    inCall = false
                }
                listOf(CallAnswerDetector.NodeSnapshot(text = "正在呼叫", contentDescription = "", viewIdResourceName = ""))
            },
            checkAuthorization = {},
            pause = {}
        )
        assertFalse(result.answered)
        assertEquals("call_ended_before_answer", result.reason)
    }
}
