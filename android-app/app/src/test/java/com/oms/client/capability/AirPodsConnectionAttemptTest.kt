package com.oms.client.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AirPodsConnectionAttemptTest {
    private class Fixture {
        var time = 0L
        var valid = true
        var connectedAt = Long.MAX_VALUE
        var activeAt = Long.MAX_VALUE
        var requests = 0
        var activations = 0
        suspend fun run(budget: Long = 8000L, pause: suspend (Long) -> Unit = { time += it }) =
            AirPodsConnectionAttempt.run(budget, { time }, { valid },
                { time >= connectedAt }, { time >= activeAt },
                { requests++; true }, { activations++ }, pause)
    }

    @Test fun alreadyConnectedAndActiveNeedsNoMutation() = runBlocking {
        val f = Fixture().apply { connectedAt = 0; activeAt = 0 }
        assertTrue(f.run()); assertEquals(0, f.requests); assertEquals(0, f.activations)
    }
    @Test fun connectionPastOldRpcTimeoutCanCompleteWithinBudget() = runBlocking {
        val f = Fixture().apply { connectedAt = 5000; activeAt = 5500 }
        assertTrue(f.run()); assertEquals(5500L, f.time); assertTrue(f.activations > 0)
    }
    @Test fun profileConnectionWithoutActiveRouteIsNotSuccess() = runBlocking {
        val f = Fixture().apply { connectedAt = 0 }
        assertFalse(f.run()); assertEquals(8000L, f.time); assertEquals(0, f.requests)
    }
    @Test fun supersededOrRevokedRequestCannotActivateLateConnection() = runBlocking {
        val f = Fixture().apply { connectedAt = 100; activeAt = 100 }
        assertFalse(f.run { f.time += it; f.valid = false })
        assertEquals(1, f.requests); assertEquals(0, f.activations)
    }
    @Test fun timeoutStopsRequestsAndRejectsRouteAtDeadline() = runBlocking {
        val f = Fixture().apply { connectedAt = 8000; activeAt = 8000 }
        assertFalse(f.run()); assertEquals(1, f.requests); assertEquals(0, f.activations)
    }
    @Test fun expiredRequestDoesNotConnectOrActivate() = runBlocking {
        val f = Fixture()
        assertFalse(f.run(0)); assertEquals(0, f.requests); assertEquals(0, f.activations)
    }
    @Test fun acceptedRequestIsNotResubmittedWhileWaitingForSlowConnection() = runBlocking {
        val f = Fixture().apply { connectedAt = 9000; activeAt = 9200 }
        assertTrue(f.run(12000)); assertEquals(1, f.requests)
    }
    @Test fun alreadyConnectingDoesNotEnqueueAnotherRequest() = runBlocking {
        var elapsed = 0L
        assertTrue(AirPodsConnectionAttempt.run(12000, { elapsed }, { true },
            { elapsed >= 7000 }, { elapsed >= 7000 },
            { fail("Existing connection must finish first"); false }, {}, { elapsed += it }, { true }))
    }
    @Test fun rejectedRequestMayRetryButNeverPastDeadline() = runBlocking {
        var time = 0L
        var requests = 0
        assertFalse(AirPodsConnectionAttempt.run(3000, { time }, { true }, { false }, { false },
            { requests++; false }, {}, { time += it }))
        assertEquals(3, requests)
    }
    @Test fun callerCancellationPropagatesAndStopsFurtherEffects() = runBlocking {
        val f = Fixture()
        try {
            f.run { throw CancellationException("session closed") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, f.requests); assertEquals(0, f.activations)
    }
}
