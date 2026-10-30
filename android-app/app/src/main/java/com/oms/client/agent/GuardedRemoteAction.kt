package com.oms.client.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Shared gate for multi-step phone actions; the service invokes it on the main dispatcher. */
internal class GuardedRemoteAction(
    private val guard: GuiSessionGuard,
    private val mutex: Mutex,
    private val maxDurationMs: Long = 25_000L,
    private val now: () -> Long = System::currentTimeMillis
) {
    class Steps internal constructor(
        private val guard: GuiSessionGuard,
        private val deadline: Long,
        private val sessionValid: () -> Boolean,
        private val now: () -> Long
    ) {
        suspend fun check() {
            currentCoroutineContext().ensureActive()
            guard.checkAuthorization(now(), deadline, sessionValid())
        }

        suspend fun <T> effect(action: suspend () -> T): T {
            check()
            val result = action()
            check()
            return result
        }

        suspend fun pause(ms: Long) = effect { delay(ms) }

        suspend fun requireUnlocked(unlock: suspend () -> Boolean) {
            kotlin.check(effect(unlock)) { "ERR_UNLOCK_FAILED: Unlock was not confirmed; call was not started" }
        }
    }

    suspend fun <T> run(
        requestedDeadline: Long?,
        sessionValid: () -> Boolean,
        activeJob: (Job?) -> Unit = {},
        action: suspend Steps.() -> T
    ): T {
        val started = now()
        val deadline = minOf(started + maxDurationMs,
            GuiSessionGuard.boundedDeadline(started, requestedDeadline ?: (started + maxDurationMs), maxDurationMs))
        val queuedRevision = guard.revision
        val steps = Steps(guard, deadline, sessionValid, now)
        steps.check()
        return withTimeout((deadline - started).coerceAtLeast(1L)) {
            mutex.withLock {
                guard.check(now(), deadline, "", queuedRevision, null, false, sessionValid())
                activeJob(currentCoroutineContext()[Job])
                try {
                    val result = steps.action()
                    steps.check()
                    result
                } finally {
                    activeJob(null)
                }
            }
        }
    }
}
