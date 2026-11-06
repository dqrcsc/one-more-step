package com.oms.client.agent

import org.junit.Assert.*
import org.junit.Test

class AiCallInputPolicyTest {
    private val pkg = "com.xiaomi.aiasst.service"
    private val valid = AiCallInputPolicy.NodeState(pkg, 7, true, true, true, false)
    private val allowed = setOf(pkg, "com.android.incallui")
    private val windows = mapOf(7 to pkg, 8 to "com.android.incallui", 9 to "com.tencent.mm")
    private val target = AiCallInputPolicy.Target(pkg, 7)

    @Test fun unrelatedSplitScreenInputCannotBeSelectedBeforeAiCallInput() {
        val candidates = listOf(valid.copy(packageName = "com.tencent.mm", windowId = 9), valid)
        val selected = candidates.firstNotNullOfOrNull { AiCallInputPolicy.select(it, allowed, windows) }
        assertEquals(target, selected)
        assertNull(AiCallInputPolicy.select(valid.copy(packageName = "com.tencent.mm"), allowed, windows))
        assertNull(AiCallInputPolicy.select(valid.copy(windowId = 9), allowed, windows))
    }

    @Test fun passwordHiddenDisabledAndNonEditableNodesAreRejected() {
        for (node in listOf(valid.copy(password = true), valid.copy(visible = false),
                valid.copy(enabled = false), valid.copy(editable = false))) {
            assertNull(AiCallInputPolicy.select(node, allowed, windows))
        }
    }

    @Test fun changedPackageOrWindowIsRejectedEvenWhenBothPackagesAreAllowed() {
        for (node in listOf(valid.copy(packageName = "com.android.incallui", windowId = 8),
                valid.copy(windowId = 10))) {
            assertFalse(AiCallInputPolicy.write(target, { node }, { windows + (10 to pkg) },
                { fail("Must not focus a replacement window"); true },
                { fail("Must not write to a replacement window"); true }))
        }
    }

    @Test fun refreshFailureCannotFocusOrWrite() {
        assertFalse(AiCallInputPolicy.write(target, { null }, { windows },
            { fail("Stale node cannot be focused"); true }, { fail("Stale node cannot be written"); true }))
    }

    @Test fun focusChangingTargetSafetyStopsBeforeWriting() {
        for (changed in listOf(valid.copy(password = true), valid.copy(visible = false),
                valid.copy(enabled = false), valid.copy(editable = false),
                valid.copy(windowId = 8, packageName = "com.android.incallui"))) {
            var current = valid
            var written = false
            assertFalse(AiCallInputPolicy.write(target, { current }, { windows },
                { current = changed; true }, { written = true; true }))
            assertFalse(written)
        }
    }

    @Test fun removedWindowOrFailedFocusCannotWrite() {
        var currentWindows = windows
        assertFalse(AiCallInputPolicy.write(target, { valid }, { currentWindows },
            { currentWindows = emptyMap(); true }, { fail("Removed window cannot be written"); true }))
        assertFalse(AiCallInputPolicy.write(target, { valid }, { windows },
            { false }, { fail("Failed focus cannot write"); true }))
    }

    @Test fun validTargetIsRefreshedBeforeAndAfterFocus() {
        val events = mutableListOf<String>()
        assertTrue(AiCallInputPolicy.write(target,
            { events += "refresh"; valid }, { windows },
            { events += "focus"; true }, { events += "write"; true }))
        assertEquals(listOf("refresh", "focus", "refresh", "write"), events)
    }
}
