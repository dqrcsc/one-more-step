package com.oms.client.agent

import java.util.UUID

/** Pure state rules; accessed only from the service's main dispatcher. */
internal class GuiSessionGuard(initiallyStopped: Boolean = false) {
    var stopped = initiallyStopped
        private set
    var revision: Long = 0
        private set
    private var observationId: String? = null
    private var observedWindow: String? = null

    private var invalidationReason: String = "observation replaced"

    fun invalidate(reason: String = "observation replaced") {
        invalidationReason = reason
        revision++
        observationId = null
        observedWindow = null
    }

    fun invalidateIfWindowChanged(window: String) {
        if (observedWindow != null && observedWindow != window) invalidate()
    }

    fun observe(window: String): String {
        invalidate()
        observedWindow = window
        return UUID.randomUUID().toString().also { observationId = it }
    }

    fun stop() { stopped = true; invalidate() }
    fun resumeOnPhone() { stopped = false; invalidate() }

    fun check(
        now: Long,
        deadline: Long,
        window: String,
        expectedRevision: Long,
        suppliedObservation: String?,
        requiresObservation: Boolean,
        sessionAuthorized: Boolean = true
    ) {
        checkAuthorization(now, deadline, sessionAuthorized)
        check(revision == expectedRevision) { "ERR_STALE_OBSERVATION: Screen changed ($invalidationReason); observe again" }
        if (requiresObservation || !suppliedObservation.isNullOrBlank()) {
            check(!suppliedObservation.isNullOrBlank() && suppliedObservation == observationId && window == observedWindow) {
                "ERR_STALE_OBSERVATION: Missing or outdated observationId; observe again"
            }
        }
    }

    /** Recheck after a gesture without treating its own window updates as stale input. */
    fun checkAuthorization(now: Long, deadline: Long, sessionAuthorized: Boolean) {
        check(sessionAuthorized) { "ERR_SESSION_REVOKED: Originating connection is no longer authorized" }
        check(!stopped) { "ERR_USER_INTERRUPTED: Resume GUI access on the phone first" }
        check(now < deadline) { "ERR_GUI_DEADLINE: Action expired" }
    }

    companion object {
        fun boundedDeadline(now: Long, requested: Long): Long {
            require(requested > now) { "ERR_GUI_DEADLINE: Missing or expired deadlineMs" }
            return minOf(requested, now + 60_000L)
        }
    }
}
