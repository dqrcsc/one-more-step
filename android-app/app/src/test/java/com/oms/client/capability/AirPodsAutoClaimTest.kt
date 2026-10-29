package com.oms.client.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AirPodsAutoClaimTest {
    @Test fun releaseAcknowledgementPrecedesConnection() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(AirPodsAutoClaim.run({ true }, {
            events += "released"
            { true }
        }, { valid -> events += "connect"; valid() }))
        assertEquals(listOf("released", "connect"), events)
    }

    @Test fun failureOrTimeoutNeverConnects() = runBlocking {
        try {
            AirPodsAutoClaim.run({ true }, { error("release failed") }, {
                fail("Must not connect without ACK"); true
            })
            fail("Release failure must propagate")
        } catch (_: IllegalStateException) { }
    }

    @Test fun manualOverrideOrPlaybackStopWhileWaitingCancelsConnection() = runBlocking {
        var wanted = true
        assertFalse(AirPodsAutoClaim.run({ wanted }, {
            wanted = false
            { true }
        }, { fail("Superseded claim must not connect"); true }))
    }

    @Test fun sessionRevokedBeforeOrDuringConnectionIsRejected() = runBlocking {
        assertFalse(AirPodsAutoClaim.run({ true }, { { false } }, {
            fail("Revoked session must not connect"); true
        }))
        var sessionValid = true
        assertFalse(AirPodsAutoClaim.run({ true }, { { sessionValid } }, { valid ->
            assertTrue(valid())
            sessionValid = false
            valid()
        }))
    }

    @Test fun cancelledReleaseNeverConnects() = runBlocking {
        try {
            AirPodsAutoClaim.run({ true }, { throw CancellationException() }, {
                fail("Cancelled claim must not connect"); true
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
