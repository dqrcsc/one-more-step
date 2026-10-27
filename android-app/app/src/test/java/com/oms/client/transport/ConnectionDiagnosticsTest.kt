package com.oms.client.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException

class ConnectionDiagnosticsTest {
    @Test fun retriesKeepFailureUntilRecoveryAndFallbackCannotOverwriteLan() {
        val d = ConnectionDiagnostics(null)
        d.record(ConnectionDiagnostics.Event.MAC_UNREACHABLE)
        d.record(ConnectionDiagnostics.Event.CONNECTING)
        d.record(ConnectionDiagnostics.Event.SECURITY_REJECTED, "BT_RFCOMM")
        assertEquals(ConnectionDiagnostics.Event.MAC_UNREACHABLE, d.reason())
        d.record(ConnectionDiagnostics.Event.CONNECTED)
        assertEquals(ConnectionDiagnostics.Event.CONNECTED, d.reason())
        d.record(ConnectionDiagnostics.Event.CONNECTION_LOST, affectState = false)
        assertEquals(ConnectionDiagnostics.Event.CONNECTED, d.reason())
    }
    @Test fun missingWifiIsClearedWhenWifiReturnsButSecurityFailureIsPreserved() {
        val d = ConnectionDiagnostics(null)
        d.record(ConnectionDiagnostics.Event.WIFI_UNAVAILABLE)
        d.record(ConnectionDiagnostics.Event.WIFI_AVAILABLE)
        assertEquals(ConnectionDiagnostics.Event.WIFI_AVAILABLE, d.reason())
        d.record(ConnectionDiagnostics.Event.SECURITY_REJECTED)
        d.record(ConnectionDiagnostics.Event.WIFI_AVAILABLE)
        assertEquals(ConnectionDiagnostics.Event.SECURITY_REJECTED, d.reason())
    }
    @Test fun persistedHistoryIsBoundedAndDoesNotRestoreOldOnlineState() {
        val f = File.createTempFile("oms-diagnostics", ".log")
        var time = 100000L
        val d = ConnectionDiagnostics(f) { time }
        repeat(240) { time += 31000; d.record(ConnectionDiagnostics.Event.CONNECTED, attempt = it.toLong()) }
        assertEquals(200, f.readLines().size)
        val restored = ConnectionDiagnostics(f) { time + 31000 }
        assertEquals(ConnectionDiagnostics.Event.STARTED, restored.reason())
        assertTrue(restored.exportText().contains("#239 CONNECTED"))
        assertEquals(200, f.readLines().size)
    }
    @Test fun repeatedEventsAreCoalescedAndExceptionMessagesNeverExported() {
        val d = ConnectionDiagnostics(null) { 100000 }
        repeat(20) { d.record(ConnectionDiagnostics.networkFailure(SocketTimeoutException("token=SECRET private body"))) }
        val exported = d.exportText()
        assertEquals(1, Regex("CONNECTION_TIMEOUT").findAll(exported).count())
        assertFalse(exported.contains("SECRET"))
        assertFalse(exported.contains("private body"))
    }
    @Test fun unreadableJournalDoesNotBreakRecording() {
        val directory = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val d = ConnectionDiagnostics(directory)
        d.record(ConnectionDiagnostics.Event.CONNECTED)
        assertEquals(ConnectionDiagnostics.Event.CONNECTED, d.reason())
        assertFalse(d.persistenceAvailable)
    }
}
