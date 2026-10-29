package com.oms.client.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HangupCallVerifierTest {
    @Test fun idleOrUnknownCallStateNeverClicksAnything() = runBlocking {
        for (state in listOf(false, null)) {
            var clicks = 0
            val result = HangupCallVerifier.execute({ state }, { clicks++; true }, {})
            assertFalse(result.hungUp)
            assertFalse(result.requested)
            assertEquals(0, clicks)
        }
    }

    @Test fun missingTargetFailsWithoutPollingOrFallback() = runBlocking {
        val result = HangupCallVerifier.execute({ true }, { false }, { fail("No target was clicked") })
        assertFalse(result.hungUp)
        assertEquals("hangup_target_unavailable", result.reason)
    }

    @Test fun uiClickAloneDoesNotProveHangup() = runBlocking {
        val result = HangupCallVerifier.execute({ true }, { true }, {})
        assertTrue(result.requested)
        assertFalse(result.hungUp)
        assertEquals("hangup_not_confirmed", result.reason)
    }

    @Test fun confirmedTransitionToIdleReportsSuccess() = runBlocking {
        var active = true
        val result = HangupCallVerifier.execute({ active }, { true }, { active = false })
        assertTrue(result.hungUp)
        assertTrue(result.requested)
        assertEquals("call_ended", result.reason)
    }

    @Test fun losingPermissionDuringConfirmationDoesNotReportSuccess() = runBlocking {
        var active: Boolean? = true
        val result = HangupCallVerifier.execute({ active }, { true }, { active = null })
        assertFalse(result.hungUp)
        assertTrue(result.requested)
        assertEquals("call_state_unavailable", result.reason)
    }
}
