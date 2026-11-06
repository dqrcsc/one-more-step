package com.oms.client.agent

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlin.coroutines.resume

/** A late gesture callback cannot stop a newer action or undo a phone-local resume. */
internal object GestureCancellation {
    fun complete(
        continuation: CancellableContinuation<Boolean>,
        originatingAction: Job?,
        activeAction: Job?,
        originatingStopEpoch: Long,
        currentStopEpoch: Long,
        stop: () -> Unit
    ) {
        if (!continuation.isActive) return
        if (originatingAction != null && activeAction === originatingAction &&
            currentStopEpoch == originatingStopEpoch) stop()
        if (continuation.isActive) continuation.resume(false)
    }
}
