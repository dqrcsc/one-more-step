package com.oms.client.agent

import org.junit.Assert.*
import org.junit.Test

class GuiWindowTrackerTest {
    @Test fun overlayAndSystemEventsDoNotInvalidateObservedApplication() {
        val tracker = GuiWindowTracker()
        tracker.track(mapOf(7 to "com.android.settings"))
        assertFalse(tracker.belongsToApplication(9, "com.oms.client"))
        assertFalse(tracker.belongsToApplication(3, "com.android.systemui"))
        assertFalse(tracker.belongsToApplication(9, "com.android.settings"))
        assertTrue(tracker.belongsToApplication(7, "com.android.settings"))
    }
    @Test fun ownApplicationContentIsNotMistakenForAnOverlay() {
        val tracker = GuiWindowTracker()
        tracker.track(mapOf(7 to "com.oms.client"))
        assertTrue(tracker.belongsToApplication(7, "com.oms.client"))
        assertFalse(tracker.belongsToApplication(9, "com.oms.client"))
    }
    @Test fun unknownAttributionRemainsConservative() {
        val tracker = GuiWindowTracker()
        tracker.track(mapOf(7 to "app"))
        assertTrue(tracker.belongsToApplication(-1, "app"))
        assertTrue(tracker.belongsToApplication(-1, null))
        assertFalse(tracker.belongsToApplication(-1, "other"))
    }
    @Test fun windowOrPackageChangesRemainInvalidatingAndOrderDoesNotMatter() {
        val tracker = GuiWindowTracker()
        tracker.track(mapOf(7 to "app", 8 to "dialog"))
        assertFalse(tracker.windowsChanged(linkedMapOf(8 to "dialog", 7 to "app")))
        assertTrue(tracker.windowsChanged(mapOf(7 to "other", 8 to "dialog")))
        assertTrue(tracker.windowsChanged(mapOf(7 to "app")))
        assertTrue(tracker.windowsChanged(mapOf(7 to "app", 8 to "dialog", 9 to "new")))
    }
}
