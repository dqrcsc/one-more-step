package com.oms.client.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.oms.client.OmsApplication
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Module 05: Core Android Accessibility GUI Automation Service & Safety Guardrails
 */
class OmsAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OmsAccessibilityService? = null
            private set

        private val BLOCKED_FINANCIAL_PACKAGES = setOf(
            "com.eg.android.AlipayGphone", // Alipay
            "com.unionpay",                // UnionPay
            "com.icbc",
            "com.chinamworld.main",
            "cmb.pb",
            "com.android.packageinstaller",
            "com.miui.packageinstaller"
        )

        private val SENSITIVE_ACTION_KEYWORDS = listOf(
            "提交", "发送", "发布", "删除", "付款", "确认转账", "立即支付", "确认删除"
        )
    }

    @Volatile
    var currentPackageName: String = ""
        private set

    @Volatile
    var currentActivityName: String = ""
        private set

    @Volatile
    private var emergencyStopped = false

    private var safetyOverlay: SafetyStopOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        safetyOverlay = SafetyStopOverlay(this) {
            emergencyStopped = true
            OmsApplication.instance?.transportClient?.sendEmergencyStopEvent("floating_pill_clicked")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackageName = it }
            event.className?.toString()?.let { currentActivityName = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        safetyOverlay?.hideActivePill()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun isBlockedFinancialOrPasswordWindow(root: AccessibilityNodeInfo?): Boolean {
        if (BLOCKED_FINANCIAL_PACKAGES.contains(currentPackageName)) return true
        val actLower = currentActivityName.lowercase()
        if (actLower.contains("pay") || actLower.contains("password") || actLower.contains("wallet")) {
            return true
        }
        if (root != null) {
            // Check if focused node is a password field
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused?.isPassword == true) return true
        }
        return false
    }

    /**
     * Collect all active application window roots (handling Xiaomi HyperOS multi-window / personalassistant overlays)
     */
    private fun collectApplicationRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val allWindows = windows
            if (!allWindows.isNullOrEmpty()) {
                val appWindows = allWindows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { w ->
                        val r = Rect()
                        w.getBoundsInScreen(r)
                        r.width() * r.height()
                    }
                for (w in appWindows) {
                    val rNode = w.root ?: continue
                    val pkg = rNode.packageName?.toString() ?: ""
                    if (pkg != "com.miui.personalassistant" || appWindows.size == 1) {
                        roots.add(rNode)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && roots.none { it == activeRoot }) {
            roots.add(activeRoot)
        }
        roots.firstOrNull()?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let {
            currentPackageName = it
        }
        return roots
    }

    suspend fun observeScreen(
        includeScreenshot: Boolean,
        somAnnotation: Boolean,
        onSendBinaryStream: (fileName: String, mime: String, bytes: ByteArray) -> String?
    ): JSONObject {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val roots = collectApplicationRoots()
        val primaryRoot = roots.firstOrNull()

        if (isBlockedFinancialOrPasswordWindow(primaryRoot)) {
            return JSONObject().apply {
                put("packageName", currentPackageName)
                put("activityName", currentActivityName)
                put("isSecureWindow", true)
                put("screenWidth", screenW)
                put("screenHeight", screenH)
                put("nodes", JSONArray())
            }
        }

        val extractedNodes = SemanticTreeExtractor.extractCompactTreeFromRoots(roots, screenW, screenH)
        val nodesArr = JSONArray()
        for (n in extractedNodes) {
            nodesArr.put(n.toJson())
        }

        var screenshotStreamId: String? = null
        if (includeScreenshot) {
            val webpBytes = ScreenCapturer.captureWebpBytes(this, extractedNodes, somAnnotation)
            if (webpBytes != null) {
                screenshotStreamId = onSendBinaryStream("gui_screen.webp", "image/webp", webpBytes)
            }
        }

        return JSONObject().apply {
            put("packageName", currentPackageName)
            put("activityName", currentActivityName)
            put("isSecureWindow", false)
            put("screenWidth", screenW)
            put("screenHeight", screenH)
            put("nodes", nodesArr)
            if (screenshotStreamId != null) {
                put("screenshotStreamId", screenshotStreamId)
            }
        }
    }

    suspend fun executeGuiAction(params: JSONObject): JSONObject {
        emergencyStopped = false
        safetyOverlay?.showActivePill()

        val root = rootInActiveWindow
        if (isBlockedFinancialOrPasswordWindow(root)) {
            safetyOverlay?.hideActivePill()
            throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Payment or password window is hardware-isolated")
        }

        val action = params.optString("action", "click").lowercase()
        val targetNodeId = params.optString("targetNodeId", "")
        val text = params.optString("text", "")
        val requireConfirm = params.optBoolean("requireUserConfirm", false)

        val targetNode = if (targetNodeId.isNotEmpty()) {
            SemanticTreeExtractor.getNodeById(targetNodeId)
        } else {
            null
        }

        // Guardrail #3 & #4: Check if target node label contains sensitive keywords ("提交", "发送", "删除", etc.)
        val targetLabel = (targetNode?.text?.toString() ?: targetNode?.contentDescription?.toString() ?: "").trim()
        val hitsSensitiveKeyword = SENSITIVE_ACTION_KEYWORDS.any { kw -> targetLabel.contains(kw) }

        if (requireConfirm || (action == "click" && hitsSensitiveKeyword)) {
            val confirmed = safetyOverlay?.awaitUserConfirmForSensitiveAction(
                if (targetLabel.isNotEmpty()) "点击「$targetLabel」" else "执行 $action"
            ) ?: false
            if (!confirmed || emergencyStopped) {
                safetyOverlay?.hideActivePill()
                throw IllegalStateException("ERR_USER_INTERRUPTED: User declined sensitive GUI action")
            }
        }

        if (emergencyStopped) {
            safetyOverlay?.hideActivePill()
            throw IllegalStateException("ERR_USER_INTERRUPTED: Emergency stop triggered")
        }

        var ok = false
        when (action) {
            "click", "long_click" -> {
                if (targetNode != null) {
                    val semAction = if (action == "long_click") {
                        AccessibilityNodeInfo.ACTION_LONG_CLICK
                    } else {
                        AccessibilityNodeInfo.ACTION_CLICK
                    }
                    ok = targetNode.performAction(semAction)
                    if (!ok) {
                        val b = Rect()
                        targetNode.getBoundsInScreen(b)
                        ok = injectTapGesture(
                            b.centerX() + Random.nextInt(-2, 3),
                            b.centerY() + Random.nextInt(-2, 3),
                            if (action == "long_click") 600L else 75L
                        )
                    }
                } else if (params.has("x") && params.has("y")) {
                    ok = injectTapGesture(
                        params.getInt("x"),
                        params.getInt("y"),
                        if (action == "long_click") 600L else 75L
                    )
                }
            }
            "input_text" -> {
                if (targetNode != null) {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            "scroll_down", "scroll_up" -> {
                val dm = resources.displayMetrics
                val cx = dm.widthPixels / 2
                val startY = if (action == "scroll_down") (dm.heightPixels * 0.75).toInt() else (dm.heightPixels * 0.28).toInt()
                val endY = if (action == "scroll_down") (dm.heightPixels * 0.25).toInt() else (dm.heightPixels * 0.75).toInt()
                ok = injectBezierSwipeGesture(cx, startY, cx, endY, 320L)
            }
            "swipe" -> {
                val x1 = params.optInt("x", 540)
                val y1 = params.optInt("y", 1600)
                val x2 = params.optInt("x2", 540)
                val y2 = params.optInt("y2", 500)
                ok = injectBezierSwipeGesture(x1, y1, x2, y2, 300L)
            }
            "back" -> ok = performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> ok = performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> ok = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "lock_screen", "lock" -> ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "launch_app" -> {
                val res = com.oms.client.capability.AppLauncherHelper.launchApp(this, text)
                ok = res.ok
            }
            "open_url" -> {
                val (opened, _) = com.oms.client.capability.AppLauncherHelper.openUrl(this, text)
                ok = opened
            }
        }

        // Wait 400ms for UI transition to settle and return post-action compact tree
        delay(400)
        val dm = resources.displayMetrics
        val postTree = SemanticTreeExtractor.extractCompactTreeFromRoots(
            collectApplicationRoots(),
            dm.widthPixels,
            dm.heightPixels
        )
        val postArr = JSONArray()
        for (n in postTree) postArr.put(n.toJson())

        safetyOverlay?.hideActivePill()

        return JSONObject().apply {
            put("ok", ok)
            put("packageName", currentPackageName)
            put("postActionTree", postArr)
        }
    }

    fun abortGuiSession() {
        emergencyStopped = true
        safetyOverlay?.hideActivePill()
    }

    fun performSystemGlobalAction(actionName: String): Boolean {
        return when (actionName.lowercase()) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock_screen", "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            else -> false
        }
    }

    private fun injectTapGesture(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun injectBezierSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            val ctrlX = (x1 + x2) / 2f + Random.nextInt(-25, 26)
            val ctrlY = (y1 + y2) / 2f
            quadTo(ctrlX, ctrlY, x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
