package com.oms.client.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class GuardedRemoteActionTest {
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Unsafe action was allowed") }
        catch (_: IllegalStateException) { }
    }

    @Test fun failedUnlockStopsBeforeOpeningDialer() = runBlocking {
        val gate = GuardedRemoteAction(GuiSessionGuard(), Mutex())
        var dialerOpened = false
        rejected {
            gate.run(null, { true }) {
                requireUnlocked { false }
                effect { dialerOpened = true }
            }
        }
        assertFalse(dialerOpened)
    }

    @Test fun revokedSessionAfterPausePreventsNextSideEffect() = runBlocking {
        supervisorScope {
            var authorized = true
            var dialerOpened = false
            val gate = GuardedRemoteAction(GuiSessionGuard(), Mutex())
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run(null, { authorized }) {
                    pause(20)
                    effect { dialerOpened = true }
                }
            }
            authorized = false
            rejected { pending.await() }
            assertFalse(dialerOpened)
        }
    }

    @Test fun queuedActionExpiresWithoutAnySideEffect() = runBlocking {
        supervisorScope {
            val mutex = Mutex(locked = true)
            var now = 100L
            var executed = false
            val gate = GuardedRemoteAction(GuiSessionGuard(), mutex) { now }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run(200L, { true }) { effect { executed = true } }
            }
            now = 200L
            mutex.unlock()
            rejected { pending.await() }
            assertFalse(executed)
            assertFalse(mutex.isLocked)
        }
    }

    @Test fun stopAndResumeDoesNotResurrectQueuedCall() = runBlocking {
        supervisorScope {
            val mutex = Mutex(locked = true)
            val guard = GuiSessionGuard()
            var executed = false
            val gate = GuardedRemoteAction(guard, mutex)
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run(null, { true }) { effect { executed = true } }
            }
            guard.stop()
            guard.resumeOnPhone()
            mutex.unlock()
            rejected { pending.await() }
            assertFalse(executed)
        }
    }

    @Test fun cancelledUnlockReleasesMutexAndCannotStartCall() = runBlocking {
        val mutex = Mutex()
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Boolean>()
        var active: Job? = null
        var called = false
        val gate = GuardedRemoteAction(GuiSessionGuard(), mutex)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(null, { true }, { active = it }) {
                requireUnlocked { entered.complete(Unit); unblock.await() }
                effect { called = true }
            }
        }
        entered.await()
        assertNotNull(active)
        pending.cancelAndJoin()
        unblock.complete(true)
        assertFalse(called)
        assertFalse(mutex.isLocked)
        assertNull(active)
        gate.run(null, { true }) { effect { called = true } }
        assertTrue(called)
    }

    @Test fun explicitAndDefaultDeadlinesCannotExtendPastTwentyFiveSeconds() = runBlocking {
        for (deadline in listOf<Long?>(null, Long.MAX_VALUE)) {
            var now = 100L
            var called = false
            val gate = GuardedRemoteAction(GuiSessionGuard(), Mutex()) { now }
            rejected {
                gate.run(deadline, { true }) {
                    now = 25_100L
                    effect { called = true }
                }
            }
            assertFalse(called)
        }
    }

    @Test fun successfulUnlockDoesNotNeedAnotherMutexAcquisition() = runBlocking {
        val gate = GuardedRemoteAction(GuiSessionGuard(), Mutex())
        val actions = mutableListOf<String>()
        gate.run(null, { true }) {
            requireUnlocked { effect { actions.add("unlock"); true } }
            effect { actions.add("open_dialer") }
        }
        assertEquals(listOf("unlock", "open_dialer"), actions)
    }

    @Test fun stopDuringUnlockPauseBlocksAnyFollowingInput() = runBlocking {
        supervisorScope {
            val guard = GuiSessionGuard()
            var gestures = 0
            val gate = GuardedRemoteAction(guard, Mutex())
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run(null, { true }) {
                    pause(20)
                    effect { gestures++ }
                }
            }
            guard.stop()
            rejected { pending.await() }
            assertEquals(0, gestures)
        }
    }
}
