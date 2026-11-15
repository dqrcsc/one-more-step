package com.oms.client.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Revalidates the actual hit target after phone-local approval; overlay events are excluded. */
internal class MirrorPointSafety {
    data class Snapshot(
        val applicationWindows: Map<Int, String>,
        val activeWindowId: Int?,
        val targetIdentity: Any?,
        val targetAttributes: List<String>,
        val label: String,
        val password: Boolean,
        val blockedWindow: Boolean
    )

    private var watchedWindows: GuiWindowTracker? = null
    private var windowChanged = false

    fun onWindowsChanged(windows: Map<Int, String>) {
        if (watchedWindows?.windowsChanged(windows) == true) windowChanged = true
    }

    fun onWindowStateChanged(windowId: Int, packageName: String?) {
        if (watchedWindows?.belongsToApplication(windowId, packageName) == true) windowChanged = true
    }

    suspend fun check(
        action: String,
        checkAuthorization: () -> Unit,
        readSnapshot: () -> Snapshot,
        refreshOriginalTarget: () -> Boolean,
        confirm: suspend (String) -> Boolean,
        confirmationTimeoutMs: Long = 60_000L
    ) {
        checkAuthorization()
        val original = readSnapshot()
        checkProtected(original)
        if (!SensitiveActionPolicy.requiresUserConfirmation(action, false, original.label)) return
        check(original.targetIdentity != null) { "ERR_STALE_OBSERVATION: Sensitive target unavailable" }
        watchedWindows = GuiWindowTracker().apply { track(original.applicationWindows) }
        try {
            val approved = withTimeoutOrNull(confirmationTimeoutMs) { confirm(original.label) } == true
            currentCoroutineContext().ensureActive()
            checkAuthorization()
            check(approved) { "ERR_USER_INTERRUPTED: Sensitive mirror approval declined or expired" }
            val originalTargetAvailable = refreshOriginalTarget()
            val current = readSnapshot()
            checkProtected(current)
            check(!windowChanged && original.applicationWindows == current.applicationWindows &&
                original.activeWindowId == current.activeWindowId) {
                "ERR_STALE_OBSERVATION: Application window changed during mirror approval"
            }
            check(originalTargetAvailable && original.targetIdentity == current.targetIdentity &&
                original.targetAttributes == current.targetAttributes && original.label == current.label) {
                "ERR_STALE_OBSERVATION: Mirror target changed during approval"
            }
            currentCoroutineContext().ensureActive()
            checkAuthorization()
        } finally {
            watchedWindows = null
        }
    }

    private fun checkProtected(snapshot: Snapshot) {
        if (snapshot.blockedWindow || snapshot.password) {
            throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Payment/password/self-package safety policy")
        }
    }
}
