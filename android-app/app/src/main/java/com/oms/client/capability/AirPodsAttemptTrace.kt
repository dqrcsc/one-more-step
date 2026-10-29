package com.oms.client.capability

internal class AirPodsAttemptTrace(private val now: () -> Long) {
    private val started = now()
    private val events = mutableListOf<Map<String, Any>>()
    @Synchronized fun record(stage: String, fields: Map<String, Any> = emptyMap()) {
        if (events.size == 64) events.removeAt(1)
        events.add(fields + mapOf("stage" to stage, "elapsedMs" to now() - started))
    }
    @Synchronized fun snapshot(): Map<String, Any> = mapOf("elapsedMs" to now() - started, "events" to events.toList())
}
