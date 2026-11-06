package com.oms.client.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class GuardedMirrorActionTest {
    private class Fixture {
        val guard = GuiSessionGuard()
        val mutex = Mutex()
        var now = 100L
        var authorized = true
        var overlayAvailable = true
        var active: Job? = null
        var shown = 0
        var hidden = 0
        var rootPackages = listOf("com.oms.client")
        var passwordFocused = false
        val gate = GuardedMirrorAction(guard, mutex) { now }

        suspend fun run(action: suspend () -> Unit) = gate.run(
            1000L, { authorized }, { active = it },
            { shown++; overlayAvailable }, { hidden++ },
            {
                if (SensitiveActionPolicy.isBlockedWindowSet("com.oms.client", "MainActivity",
                        rootPackages, passwordFocused, allowSelfPackage = true)) {
                    throw SecurityException("Blocked window")
                }
            }, action
        )
    }

    @Test fun splitScreenAndPasswordWindowBlockBeforeInjectionEvenWhenSelfIsAllowed() = runBlocking {
        for (password in listOf(false, true)) {
            val f = Fixture().apply {
                rootPackages = if (password) listOf("com.oms.client")
                    else listOf("com.oms.client", "com.eg.android.AlipayGphone")
                passwordFocused = password
            }
            var injected = false
            try { f.run { injected = true }; fail("Unsafe window accepted") }
            catch (_: SecurityException) { }
            assertFalse(injected)
            assertNull(f.active)
            assertFalse(f.mutex.isLocked)
            assertEquals(1, f.hidden)
        }
    }

    @Test fun overlayFailurePreventsInjectionAndReleasesAction() = runBlocking {
        val f = Fixture().apply { overlayAvailable = false }
        var injected = false
        try { f.run { injected = true }; fail("Missing stop control accepted") }
        catch (_: IllegalStateException) { }
        assertFalse(injected)
        assertNull(f.active)
        assertFalse(f.mutex.isLocked)
    }

    @Test fun queuedInputUsesTheWindowsPresentWhenItActuallyExecutes() = runBlocking {
        supervisorScope {
            val f = Fixture()
            f.mutex.lock()
            var injected = false
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.run { injected = true } }
            f.rootPackages = listOf("com.oms.client", "com.eg.android.AlipayGphone")
            f.mutex.unlock()
            try { pending.await(); fail("Queued input ignored a newly protected window") }
            catch (_: SecurityException) { }
            assertFalse(injected)
            assertNull(f.active)
        }
    }

    @Test fun touchCancellationLatchesStopAndPreservesStoppedPillUntilLocalResume() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        var afterGesture = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            f.run {
                entered.complete(Unit)
                awaitCancellation()
                @Suppress("UNREACHABLE_CODE")
                afterGesture = true
            }
        }
        entered.await()
        assertNotNull(f.active)
        assertEquals(1, f.shown)
        // Same steps as the service's phone-local setStopped callback.
        f.guard.stop()
        f.active!!.cancel()
        pending.join()
        assertFalse(afterGesture)
        assertTrue(f.guard.stopped)
        assertNull(f.active)
        assertEquals(0, f.hidden)
        assertFalse(f.mutex.isLocked)
        try { f.run { fail("Stopped mirror injected") }; fail("Stop was bypassed") }
        catch (_: IllegalStateException) { }
        f.guard.resumeOnPhone()
        var freshInjected = false
        f.run { freshInjected = true }
        assertTrue(freshInjected)
    }

    @Test fun queuedInputCannotSurviveStopResumeButCanSurviveScrolling() = runBlocking {
        supervisorScope {
            for (stopResume in listOf(false, true)) {
                val f = Fixture()
                f.mutex.lock()
                var injected = false
                val pending = async(start = CoroutineStart.UNDISPATCHED) { f.run { injected = true } }
                f.guard.invalidate("scroll frame")
                if (stopResume) { f.guard.stop(); f.guard.resumeOnPhone() }
                f.mutex.unlock()
                if (stopResume) {
                    try { pending.await(); fail("Old input survived stop/resume") }
                    catch (_: IllegalStateException) { }
                } else pending.await()
                assertEquals(!stopResume, injected)
                assertNull(f.active)
            }
        }
    }

    @Test fun revokedSessionOrDeadlineDuringGestureCannotReportSuccess() = runBlocking {
        for (expired in listOf(false, true)) {
            val f = Fixture()
            try {
                f.run { if (expired) f.now = 1000L else f.authorized = false }
                fail("Invalid action completed successfully")
            } catch (_: IllegalStateException) { }
            assertNull(f.active)
            assertEquals(1, f.hidden)
            assertFalse(f.mutex.isLocked)
        }
    }
}
