package com.oms.client.capability

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded profile requests: no blocking RFCOMM socket and no detached callback work. */
internal object AirPodsConnectionAttempt {
    suspend fun run(
        budgetMs: Long,
        now: () -> Long,
        stillValid: () -> Boolean,
        connected: () -> Boolean,
        active: () -> Boolean,
        connect: () -> Boolean,
        activate: () -> Unit,
        pause: suspend (Long) -> Unit,
        connecting: () -> Boolean = { false }
    ): Boolean {
        val deadline = now() + budgetMs
        var nextRequest = now()
        var connectionSubmitted = false
        suspend fun valid(): Boolean {
            currentCoroutineContext().ensureActive()
            return stillValid() && now() < deadline
        }
        while (valid()) {
            val hasProfile = connected()
            if (!valid()) return false
            if (hasProfile && active()) return valid()
            if (now() >= nextRequest) {
                if (!valid()) return false
                if (hasProfile) {
                    activate()
                } else if (!connectionSubmitted && !connecting()) {
                    // An accepted asynchronous request owns the rest of this attempt.
                    // Re-submitting every second can enqueue redundant OS connection work.
                    connectionSubmitted = connect()
                }
                nextRequest = now() + 1000L
            }
            if (!valid()) return false
            pause(minOf(100L, deadline - now()).coerceAtLeast(1L))
        }
        return false
    }
}
