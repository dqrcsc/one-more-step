package com.oms.client.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Mirror input tolerates scrolling updates, but never a changed stop epoch. Main dispatcher only. */
internal class GuardedMirrorAction(
    private val guard: GuiSessionGuard,
    private val mutex: Mutex,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun <T> run(
        requestedDeadline: Long?,
        sessionValid: () -> Boolean,
        activeJob: (Job?) -> Unit,
        showStopControl: () -> Boolean,
        hideStopControl: () -> Unit,
        checkWindow: () -> Unit,
        action: suspend () -> T
    ): T {
        val started = now()
        val deadline = GuiSessionGuard.boundedDeadline(started, requestedDeadline ?: (started + 5000L), 5000L)
        val queuedStopEpoch = guard.stopEpoch
        guard.checkAuthorization(started, deadline, sessionValid())
        return withTimeout((deadline - started).coerceAtLeast(1L)) {
            mutex.withLock {
                guard.checkMirrorQueued(now(), deadline, queuedStopEpoch, sessionValid())
                activeJob(currentCoroutineContext()[Job])
                try {
                    check(showStopControl()) { "ERR_SAFETY_OVERLAY_UNAVAILABLE: Enable phone overlay permission" }
                    checkWindow()
                    currentCoroutineContext().ensureActive()
                    guard.checkMirrorQueued(now(), deadline, queuedStopEpoch, sessionValid())
                    val result = action()
                    currentCoroutineContext().ensureActive()
                    guard.checkMirrorQueued(now(), deadline, queuedStopEpoch, sessionValid())
                    result
                } finally {
                    activeJob(null)
                    // setStopped owns the stopped/resume pill; cleanup must leave it visible.
                    if (!guard.stopped) hideStopControl()
                }
            }
        }
    }
}
