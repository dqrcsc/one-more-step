package com.oms.client.agent

/** Attribute content/state events to the application windows used by an observation/action. */
internal class GuiWindowTracker {
    private var applications: Map<Int, String> = emptyMap()
    private var identity = ""

    fun track(windows: Map<Int, String>) {
        applications = windows.toMap()
        identity = identityOf(windows)
    }

    fun belongsToApplication(windowId: Int, packageName: String?): Boolean {
        if (windowId >= 0) return windowId in applications
        // Missing window IDs cannot prove an event is external. Retain conservative
        // invalidation for the tracked package, and for events with no attribution.
        return applications.isNotEmpty() && (packageName == null || packageName in applications.values)
    }

    fun windowsChanged(windows: Map<Int, String>): Boolean =
        applications.isNotEmpty() && identityOf(windows) != identity

    companion object {
        fun identityOf(windows: Map<Int, String>): String =
            windows.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value}" }
    }
}
