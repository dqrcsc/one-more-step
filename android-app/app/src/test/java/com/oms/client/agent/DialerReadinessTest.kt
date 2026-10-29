package com.oms.client.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DialerReadinessTest {
    @Test fun warmDialerDoesNotNeedAnArtificialDelay() = runBlocking {
        assertTrue(DialerReadiness.awaitVerifiedNumber(
            { false }, { true }, {}, { fail("Already ready; must not wait") }
        ))
    }

    @Test fun coldDialerMayRenderAfterTheOldFiveHundredTwentyMillisecondWait() = runBlocking {
        var elapsed = 0L
        var checks = 0
        assertTrue(DialerReadiness.awaitVerifiedNumber(
            { false }, { elapsed >= 1200L }, { checks++ }, { elapsed += it }
        ))
        assertEquals(1200L, elapsed)
        assertEquals(13, checks)
    }

    @Test fun missingOrWrongNumberNeverBecomesSuccessAndWaitIsBounded() = runBlocking {
        var elapsed = 0L
        var observations = 0
        assertFalse(DialerReadiness.awaitVerifiedNumber(
            { false }, { observations++; false }, {}, { elapsed += it }
        ))
        assertEquals(3000L, elapsed)
        assertEquals(31, observations)
    }

    @Test fun phoneRelockingWhileWaitingStopsBeforeReadingTheNextNumber() = runBlocking {
        var locked = false
        var observations = 0
        assertFalse(DialerReadiness.awaitVerifiedNumber(
            { locked }, { observations++; false }, {}, { locked = true }
        ))
        assertEquals(1, observations)
    }

    @Test fun revokedAuthorizationOrExpiredDeadlineCannotTurnLateNumberIntoSuccess() = runBlocking {
        for (reason in listOf("ERR_SESSION_REVOKED", "ERR_GUI_DEADLINE", "ERR_USER_INTERRUPTED")) {
            var ready = false
            var observations = 0
            try {
                DialerReadiness.awaitVerifiedNumber(
                    { false }, { observations++; ready },
                    { check(!ready) { reason } }, { ready = true }
                )
                fail("A revoked operation was accepted")
            } catch (e: IllegalStateException) { assertEquals(reason, e.message) }
            assertEquals(1, observations)
        }
    }

    @Test fun cancellationWhileWaitingPropagatesWithoutFurtherObservation() = runBlocking {
        var observations = 0
        try {
            DialerReadiness.awaitVerifiedNumber(
                { false }, { observations++; false }, {}, { throw CancellationException("stop") }
            )
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, observations)
    }
}
