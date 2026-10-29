package com.oms.client.capability

import org.junit.Assert.*
import org.junit.Test

class AirPodsAttemptTraceTest {
    @Test fun boundedTraceRetainsStartAndRecentFailureEvidence() {
        var time = 10L
        val trace = AirPodsAttemptTrace { time }
        trace.record("start")
        repeat(100) { time++; trace.record("attempt", mapOf("returnCode" to it)) }
        trace.record("finished", mapOf("ok" to false))
        val snapshot = trace.snapshot()
        val events = snapshot["events"] as List<*>
        assertEquals(64, events.size)
        assertEquals("start", (events.first() as Map<*,*>)["stage"])
        assertEquals("finished", (events.last() as Map<*,*>)["stage"])
        assertEquals(100L, snapshot["elapsedMs"])
    }
    @Test fun returnedSnapshotDoesNotChangeWhenLaterCallbacksArrive() {
        val trace = AirPodsAttemptTrace { 0L }
        trace.record("expired")
        val snapshot = trace.snapshot()
        trace.record("late_callback")
        assertEquals(1, (snapshot["events"] as List<*>).size)
        assertEquals(2, (trace.snapshot()["events"] as List<*>).size)
    }
}
