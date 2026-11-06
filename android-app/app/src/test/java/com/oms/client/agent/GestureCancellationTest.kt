package com.oms.client.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GestureCancellationTest {
    @Test fun currentGestureCancellationStopsItsActionAndBlocksFollowingWork() = runBlocking {
        val guard = GuiSessionGuard()
        var continuation: CancellableContinuation<Boolean>? = null
        var after = false
        val action = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Boolean> { continuation = it }
            after = true
        }
        GestureCancellation.complete(continuation!!, action, action, 0, guard.stopEpoch) {
            guard.stop(); action.cancel()
        }
        action.join()
        assertTrue(guard.stopped)
        assertFalse(after)
    }

    @Test fun lateCallbackAfterActionCancellationDoesNotStopNewAction() = runBlocking {
        var continuation: CancellableContinuation<Boolean>? = null
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Boolean> { continuation = it }
        }
        old.cancelAndJoin()
        val fresh = Job()
        GestureCancellation.complete(continuation!!, old, fresh, 0, 0) {
            fail("Late callback stopped a newer action")
        }
        assertTrue(fresh.isActive)
        fresh.cancel()
    }

    @Test fun oldCallbackAfterLocalResumeOrReplacementCompletesWithoutRelatchingStop() = runBlocking {
        for (changedEpoch in listOf(false, true)) {
            var continuation: CancellableContinuation<Boolean>? = null
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Boolean> { continuation = it }
            }
            val fresh = Job()
            GestureCancellation.complete(continuation!!, old, if (changedEpoch) old else fresh,
                0, if (changedEpoch) 2 else 0) { fail("Old callback relatched stop") }
            assertFalse(old.await())
            assertTrue(fresh.isActive)
            fresh.cancel()
        }
    }
}
