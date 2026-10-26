package com.oms.client.agent

import org.junit.Assert.*
import org.junit.Test

class GuiSessionGuardTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsafe action was accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun reusedNodeNumberCannotTargetANewObservation() {
        val guard = GuiSessionGuard()
        val first = guard.observe("window:app")
        val second = guard.observe("window:app")
        rejected { guard.check(100, 200, "window:app", guard.revision, first, true) }
        guard.check(100, 200, "window:app", guard.revision, second, true)
        rejected { guard.check(100, 200, "window:app", guard.revision, null, true) }
    }

    @Test fun confirmationCannotAuthorizeAChangedWindowOrContent() {
        val guard = GuiSessionGuard()
        val id = guard.observe("window:app")
        val revisionAtPrompt = guard.revision
        rejected { guard.check(100, 200, "other:app", revisionAtPrompt, id, true) }
        guard.invalidate()
        rejected { guard.check(100, 200, "window:app", revisionAtPrompt, id, true) }
        // Coordinates without an observation still cannot outlive a content change while waiting.
        rejected { guard.check(100, 200, "window:app", revisionAtPrompt, null, false) }
    }

    @Test fun stopSurvivesAnotherRequestAndObservationUntilLocalResume() {
        val guard = GuiSessionGuard()
        guard.stop()
        val stoppedId = guard.observe("window:app")
        rejected { guard.check(100, 200, "window:app", guard.revision, stoppedId, true) }
        rejected { guard.check(100, 200, "window:app", guard.revision, null, false) }
        guard.resumeOnPhone()
        rejected { guard.check(100, 200, "window:app", guard.revision, stoppedId, true) }
        val fresh = guard.observe("window:app")
        guard.check(100, 200, "window:app", guard.revision, fresh, true)
    }

    @Test fun restoredStopLatchDeniesEvenCoordinateActions() {
        val guard = GuiSessionGuard(initiallyStopped = true)
        rejected { guard.check(100, 200, "window:app", guard.revision, null, false) }
    }

    @Test fun expiredConfirmationCannotExecute() {
        val guard = GuiSessionGuard()
        val id = guard.observe("window:app")
        rejected { guard.check(200, 200, "window:app", guard.revision, id, true) }
        rejected { guard.check(201, 200, "window:app", guard.revision, null, false) }
    }

    @Test fun overlayWindowEventDoesNotInvalidateUnchangedApplication() {
        val guard = GuiSessionGuard()
        val id = guard.observe("window:app")
        val revision = guard.revision
        guard.invalidateIfWindowChanged("window:app")
        guard.check(100, 200, "window:app", revision, id, true)
        guard.invalidateIfWindowChanged("other:app")
        rejected { guard.check(100, 200, "window:app", revision, id, true) }
    }

    @Test fun remoteDeadlineCannotExtendSafetyWaitPastSixtySeconds() {
        assertEquals(60_100L, GuiSessionGuard.boundedDeadline(100, Long.MAX_VALUE))
        assertEquals(500L, GuiSessionGuard.boundedDeadline(100, 500))
        for (expired in listOf(0L, 99L, 100L)) {
            try { GuiSessionGuard.boundedDeadline(100, expired); fail("Expired deadline accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @org.junit.Test(expected = IllegalStateException::class)
    fun revokedConnectionCannotExecuteAfterConfirmation() {
        val guard = GuiSessionGuard()
        val observation = guard.observe("app")
        guard.check(1, 100, "app", guard.revision, observation, true, sessionAuthorized = false)
    }
}
