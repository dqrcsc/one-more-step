package com.oms.client.capability

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An automatic connection can only use the session that acknowledged the release. */
internal object AirPodsAutoClaim {
    suspend fun run(
        stillWanted: () -> Boolean,
        release: suspend () -> (() -> Boolean),
        connect: suspend (sessionStillValid: () -> Boolean) -> Boolean
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!stillWanted()) return false
        val sessionStillValid = release()
        currentCoroutineContext().ensureActive()
        if (!stillWanted() || !sessionStillValid()) return false
        return connect { stillWanted() && sessionStillValid() }
    }
}
