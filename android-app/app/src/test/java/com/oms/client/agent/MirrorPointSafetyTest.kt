package com.oms.client.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class MirrorPointSafetyTest {
    private class Fixture {
        val guard = GuiSessionGuard()
        val safety = MirrorPointSafety()
        var now = 100L
        var authorized = true
        var targetAvailable = true
        var injected = false
        var snapshot = MirrorPointSafety.Snapshot(mapOf(1 to "com.example"), 1,
            "node-1", listOf("bounds=1,1,100,100"), "删除", false, false)
        suspend fun run(timeout: Long = 60_000L, approve: suspend () -> Boolean = { true }) {
            GuardedMirrorAction(guard, Mutex()) { now }.run(
                65_100L, { authorized }, {}, { true }, {}, {}, maxDurationMs = 65_000L
            ) { deadline ->
                val stopEpoch = guard.stopEpoch
                safety.check("click", {
                    guard.checkMirrorQueued(now, deadline, stopEpoch, authorized)
                }, { snapshot }, { targetAvailable }, { approve() }, timeout)
                injected = true
            }
        }
    }
    private suspend fun rejected(f: Fixture, change: () -> Unit) {
        try { f.run { change(); true }; fail("Stale approval injected") }
        catch (_: IllegalStateException) { }
        catch (_: SecurityException) { }
        assertFalse(f.injected)
    }
    @Test fun approvedStableTargetSucceedsAfterMoreThanFiveSeconds() = runBlocking {
        val f = Fixture()
        f.run { f.now += 10_000; true }
        assertTrue(f.injected)
    }
    @Test fun approvalOverlayWindowEventsDoNotInvalidateApplicationTarget() = runBlocking {
        val f = Fixture()
        f.run {
            f.safety.onWindowsChanged(f.snapshot.applicationWindows)
            f.safety.onWindowStateChanged(99, "com.oms.client")
            true
        }
        assertTrue(f.injected)
    }
    @Test fun changedApplicationWindowIsRejected() = runBlocking {
        val f = Fixture()
        rejected(f) { f.snapshot = f.snapshot.copy(applicationWindows = mapOf(2 to "com.example")) }
    }
    @Test fun changingWindowsAndReturningCannotReviveApproval() = runBlocking {
        val f = Fixture()
        rejected(f) {
            f.safety.onWindowsChanged(mapOf(2 to "com.other"))
            f.safety.onWindowsChanged(f.snapshot.applicationWindows)
        }
    }
    @Test fun sameWindowActivityTransitionIsRejected() = runBlocking {
        val f = Fixture()
        rejected(f) { f.safety.onWindowStateChanged(1, "com.example") }
    }
    @Test fun switchingActiveSplitScreenWindowIsRejected() = runBlocking {
        val f = Fixture()
        f.snapshot = f.snapshot.copy(applicationWindows = mapOf(1 to "com.example", 2 to "com.other"))
        rejected(f) { f.snapshot = f.snapshot.copy(activeWindowId = 2) }
    }
    @Test fun initiallyProtectedTargetDoesNotEvenPrompt() = runBlocking {
        val f = Fixture()
        f.snapshot = f.snapshot.copy(password = true)
        try { f.run { fail("Protected target prompted"); true }; fail("Protected input injected") }
        catch (_: SecurityException) { }
        assertFalse(f.injected)
    }
    @Test fun ordinaryTargetDoesNotPrompt() = runBlocking {
        val f = Fixture()
        f.snapshot = f.snapshot.copy(label = "下一页")
        f.run { fail("Ordinary target prompted"); true }
        assertTrue(f.injected)
    }
    @Test fun targetReplacedMovedRelabeledOrUnavailableIsRejected() = runBlocking {
        for (change in 0..3) {
            val f = Fixture()
            rejected(f) {
                when (change) {
                    0 -> f.snapshot = f.snapshot.copy(targetIdentity = "node-2")
                    1 -> f.snapshot = f.snapshot.copy(targetAttributes = listOf("bounds=2,2,101,101"))
                    2 -> f.snapshot = f.snapshot.copy(label = "确认删除全部")
                    3 -> f.targetAvailable = false
                }
            }
        }
    }
    @Test fun newlyProtectedFinancialWindowOrPasswordIsRejected() = runBlocking {
        for (password in listOf(false, true)) {
            val f = Fixture()
            rejected(f) { f.snapshot = f.snapshot.copy(password = password, blockedWindow = !password) }
        }
    }
    @Test fun disconnectedOrStoppedOrResumedSessionCannotInject() = runBlocking {
        for (change in 0..2) {
            val f = Fixture()
            rejected(f) {
                when (change) {
                    0 -> f.authorized = false
                    1 -> f.guard.stop()
                    2 -> { f.guard.stop(); f.guard.resumeOnPhone() }
                }
            }
        }
    }
    @Test fun explicitDeclineDoesNotInject() = runBlocking {
        val f = Fixture()
        try { f.run { false }; fail("Declined input injected") }
        catch (_: IllegalStateException) { }
        assertFalse(f.injected)
    }
    @Test fun expiredDeadlineDoesNotInject() = runBlocking {
        val f = Fixture()
        rejected(f) { f.now = 65_100L }
    }
    @Test fun confirmationTimeoutCancelsPromptAndDoesNotInject() = runBlocking {
        val f = Fixture()
        var cancelled = false
        try {
            f.run(timeout = 10L) { try { awaitCancellation() } finally { cancelled = true } }
            fail("Expired confirmation injected")
        } catch (_: IllegalStateException) { }
        assertTrue(cancelled)
        assertFalse(f.injected)
    }
    @Test fun callerCancellationCancelsPromptAndDoesNotInject() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            f.run { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(f.injected)
    }
}
