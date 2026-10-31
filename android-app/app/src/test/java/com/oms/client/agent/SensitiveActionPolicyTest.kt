package com.oms.client.agent

import android.graphics.Rect
import com.oms.client.share.ShareInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SensitiveActionPolicyTest {

    private fun rectOf(l: Int, t: Int, r: Int, b: Int): Rect = Rect().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    @Test
    fun coordinateAndNodeMutationsRequireObservationId() {
        assertTrue(SensitiveActionPolicy.requiresObservationId("click", targetNodeId = "#1", hasCoordinates = false))
        assertTrue(SensitiveActionPolicy.requiresObservationId("click", targetNodeId = "", hasCoordinates = true))
        assertTrue(SensitiveActionPolicy.requiresObservationId("long_click", targetNodeId = "", hasCoordinates = true))
        assertTrue(SensitiveActionPolicy.requiresObservationId("set_text", targetNodeId = "#3", hasCoordinates = false))
        assertFalse(SensitiveActionPolicy.requiresObservationId("back", targetNodeId = "", hasCoordinates = false))
        assertFalse(SensitiveActionPolicy.requiresObservationId("home", targetNodeId = "", hasCoordinates = false))
        assertFalse(SensitiveActionPolicy.requiresObservationId("wait", targetNodeId = "", hasCoordinates = false))
    }

    @Test
    fun sensitiveKeywordsDetectDestructiveAndFinancialLabels() {
        assertTrue(SensitiveActionPolicy.hitsSensitiveKeyword("立即支付 ¥128.00"))
        assertTrue(SensitiveActionPolicy.hitsSensitiveKeyword("确认删除对话"))
        assertTrue(SensitiveActionPolicy.hitsSensitiveKeyword("提交订单"))
        assertTrue(SensitiveActionPolicy.hitsSensitiveKeyword("Confirm Transfer"))
        assertTrue(SensitiveActionPolicy.hitsSensitiveKeyword("Allow access"))
        assertFalse(SensitiveActionPolicy.hitsSensitiveKeyword("查看详情"))
        assertFalse(SensitiveActionPolicy.hitsSensitiveKeyword("返回首页"))
    }

    @Test
    fun blockedPackageAndWindowPolicyPreventsSelfControlAndPrivilegeEscalation() {
        assertTrue(SensitiveActionPolicy.isBlockedPackage("com.oms.client"))
        assertTrue(SensitiveActionPolicy.isBlockedPackage("com.eg.android.AlipayGphone"))
        assertTrue(SensitiveActionPolicy.isBlockedPackage("com.google.android.permissioncontroller"))
        assertTrue(SensitiveActionPolicy.isBlockedPackage("com.miui.securitycenter"))
        assertFalse(SensitiveActionPolicy.isBlockedPackage("com.tencent.mm"))

        assertTrue(SensitiveActionPolicy.isBlockedActivity("com.android.settings.Settings\$AccessibilitySettingsActivity"))
        assertTrue(SensitiveActionPolicy.isBlockedActivity("com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI"))
        assertFalse(SensitiveActionPolicy.isBlockedActivity("com.android.settings.bluetooth.BluetoothSettings"))

        // Multi-window check blocks if any visible application window belongs to a blocked package
        assertTrue(
            SensitiveActionPolicy.isBlockedWindowSet(
                currentPackageName = "com.tencent.mm",
                currentActivityName = "com.tencent.mm.ui.LauncherUI",
                rootPackages = listOf("com.tencent.mm", "com.oms.client"),
                hasFocusedPasswordInput = false
            )
        )
    }

    @Test
    fun findSmallestNodeAtResolvesInnerSensitiveTargetForCoordinateClicks() {
        val outerCard = ExtractedSemanticNode(
            id = "#1",
            role = "Container",
            label = "订单卡片",
            clickable = true,
            editable = false,
            scrollable = false,
            checkable = false,
            checked = false,
            selected = false,
            bounds = rectOf(0, 0, 1080, 800)
        )
        val payButton = ExtractedSemanticNode(
            id = "#2",
            role = "Button",
            label = "立即支付 · ¥99.00",
            clickable = true,
            editable = false,
            scrollable = false,
            checkable = false,
            checked = false,
            selected = false,
            bounds = rectOf(700, 620, 1020, 740)
        )
        val nodes = listOf(outerCard, payButton)

        val hitPay = SemanticTreeExtractor.findSmallestNodeAt(850, 680, nodes)
        assertNotNull(hitPay)
        assertEquals("#2", hitPay?.id)
        assertTrue(SensitiveActionPolicy.requiresUserConfirmation("click", false, hitPay?.label.orEmpty()))

        val hitCardOnly = SemanticTreeExtractor.findSmallestNodeAt(200, 200, nodes)
        assertEquals("#1", hitCardOnly?.id)

        val miss = SemanticTreeExtractor.findSmallestNodeAt(500, 1200, nodes)
        assertNull(miss)
    }

    @Test
    fun firstNonBlankHandlesStringBuildersFromAccessibilityTree() {
        val sb = StringBuilder("  子树聚合标签  ")
        assertEquals("子树聚合标签", SemanticTreeExtractor.firstNonBlank(null, "", sb, "备用"))
    }

    @Test
    fun shareInputPolicyWhitelistsTagsAndBlocksPrivateOrSensitiveUris() {
        assertEquals("pkb_vault", ShareInputPolicy.resolveDirectShareTag("shortcut_pkb_vault"))
        assertEquals("ui_bug", ShareInputPolicy.resolveDirectShareTag("shortcut_ui_bug"))
        assertEquals("default_inbox", ShareInputPolicy.resolveDirectShareTag("sms_otp"))
        assertEquals("voice_idea", ShareInputPolicy.resolveVoiceIdeaTag("sms_otp"))
        assertEquals("pkb_vault", ShareInputPolicy.resolveVoiceIdeaTag("pkb_vault"))

        // External share stream must be content:// and not sms/contacts/telephony
        assertFalse(ShareInputPolicy.isSafeExternalStreamParts("file", null, "/data/data/com.oms.client/shared_prefs/oms_prefs.xml"))
        assertFalse(ShareInputPolicy.isSafeExternalStreamParts("content", "sms", "/inbox"))
        assertFalse(ShareInputPolicy.isSafeExternalStreamParts("content", "com.android.contacts", "/contacts/1"))
        assertTrue(ShareInputPolicy.isSafeExternalStreamParts("content", "media", "/external/images/media/42"))

        // Internal outbox attachment reader allows only files inside filesDir/cacheDir or safe content://
        val tempRoot = Files.createTempDirectory("oms-share-test").toFile()
        val filesDir = tempRoot.resolve("files").apply { mkdirs() }
        val cacheDir = tempRoot.resolve("cache").apply { mkdirs() }
        val prefsDir = tempRoot.resolve("shared_prefs").apply { mkdirs() }
        val safeAttachment = filesDir.resolve("mobile_123_file.png").apply { writeText("ok") }
        val secretPrefs = prefsDir.resolve("oms_prefs.xml").apply { writeText("secret") }

        assertTrue(
            ShareInputPolicy.isSafeInternalOrContentParts(
                "file",
                null,
                safeAttachment.absolutePath,
                listOf(filesDir, cacheDir)
            )
        )
        assertFalse(
            ShareInputPolicy.isSafeInternalOrContentParts(
                "file",
                null,
                secretPrefs.absolutePath,
                listOf(filesDir, cacheDir)
            )
        )
    }
}
