package com.oms.client.agent

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import com.oms.client.capability.AppLauncherHelper
import com.oms.client.otp.OtpExtractor
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.oms.client.OmsApplication
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.abs
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

    private val guiMutex = Mutex()
    private val windowTracker = GuiWindowTracker()
    private lateinit var guard: GuiSessionGuard
    private var activeAction: Job? = null
    private var safetyOverlay: SafetyStopOverlay? = null
    private val stopPreferences by lazy { getSharedPreferences("gui_safety", MODE_PRIVATE) }

    private fun setStopped(reason: String) {
        guard.stop()
        stopPreferences.edit().putBoolean("stopped", true).apply()
        activeAction?.cancel(CancellationException("ERR_USER_INTERRUPTED: $reason"))
        safetyOverlay?.cancelConfirmation()
        safetyOverlay?.showStoppedPill()
        OmsApplication.instance?.transportClient?.sendEmergencyStopEvent(reason)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        guard = GuiSessionGuard(stopPreferences.getBoolean("stopped", false))
        safetyOverlay = SafetyStopOverlay(this,
            onEmergencyStop = { setStopped("floating_pill_clicked") },
            onLocalResume = {
                // Only this phone-local button clears the durable stop latch.
                guard.resumeOnPhone()
                stopPreferences.edit().putBoolean("stopped", false).apply()
                safetyOverlay?.hideActivePill()
            }
        )
        if (guard.stopped) safetyOverlay?.showStoppedPill()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::guard.isInitialized) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            try {
                val notif = event.parcelableData as? Notification
                if (notif != null) {
                    val extras = notif.extras
                    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    val body = bigText ?: text ?: event.text.joinToString(" ")
                    if (body.isNotBlank()) {
                        OtpExtractor.onCandidateTextReceived(body, title, pkg)
                    }
                } else {
                    val eventTxt = event.text.joinToString(" ").trim()
                    if (eventTxt.isNotEmpty()) {
                        OtpExtractor.onCandidateTextReceived(eventTxt, null, pkg)
                    }
                }
            } catch (_: Throwable) {
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            // Confirmation itself requires a physical touch. No action runs while it is
            // pending; application changes during that wait invalidate the observation.
            if (activeAction != null && safetyOverlay?.isAwaitingConfirmation != true) {
                setStopped("touch_interaction")
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (windowTracker.windowsChanged(applicationWindows(collectApplicationRoots()))) {
                guard.invalidate("application windows changed")
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.windowId in applicationWindows(collectApplicationRoots())) {
                event.packageName?.toString()?.let { currentPackageName = it }
                event.className?.toString()?.takeUnless {
                    it.startsWith("android.widget.") || it.startsWith("android.view.") || it.startsWith("android.inputmethodservice.")
                }?.let { currentActivityName = it }
            }
            if (!windowTracker.belongsToApplication(event.windowId, event.packageName?.toString())) return
            guard.invalidate("event=${event.eventType}, window=${event.windowId}, package=${event.packageName}, class=${event.className}")
        }
    }

    override fun onInterrupt() {
        if (::guard.isInitialized) setStopped("accessibility_interrupted")
    }

    override fun onDestroy() {
        activeAction?.cancel()
        safetyOverlay?.cancelConfirmation()
        safetyOverlay?.hideActivePill()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun applicationWindows(roots: List<AccessibilityNodeInfo>): Map<Int, String> =
        roots.associate { it.windowId to it.packageName.toString() }

    private fun windowIdentity(roots: List<AccessibilityNodeInfo> = collectApplicationRoots()): String =
        GuiWindowTracker.identityOf(applicationWindows(roots))

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
        var hasWindowInventory = false
        var applicationWindowIds: Set<Int> = emptySet()
        try {
            val allWindows = windows
            if (!allWindows.isNullOrEmpty()) {
                hasWindowInventory = true
                applicationWindowIds = allWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { it.id }.toSet()
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
        if (activeRoot != null && (!hasWindowInventory || activeRoot.windowId in applicationWindowIds)
            && roots.none { it.windowId == activeRoot.windowId }) {
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
        compactForBle: Boolean = false,
        onSendBinaryStream: (fileName: String, mime: String, bytes: ByteArray) -> String?
    ): JSONObject = withContext(Dispatchers.Main.immediate) { guiMutex.withLock {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val roots = collectApplicationRoots()
        val primaryRoot = roots.firstOrNull()
        windowTracker.track(applicationWindows(roots))
        val observationId = guard.observe(windowIdentity(roots))

        if (isBlockedFinancialOrPasswordWindow(primaryRoot)) {
            SemanticTreeExtractor.extractCompactTreeFromRoots(emptyList(), screenW, screenH)
            return@withLock JSONObject().apply {
                put("observationId", observationId)
                put("securityPolicy", "payment_or_password_heuristic")
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

        val snapshotRevision = guard.revision
        var screenshotStreamId: String? = null
        if (includeScreenshot) {
            val webpBytes = ScreenCapturer.captureWebpBytes(
                this@OmsAccessibilityService,
                extractedNodes,
                somAnnotation,
                compactForBle = compactForBle
            )
            if (webpBytes != null) {
                screenshotStreamId = onSendBinaryStream("gui_screen.webp", "image/webp", webpBytes)
            }
        }

        check(guard.revision == snapshotRevision) { "ERR_STALE_OBSERVATION: Screen changed during capture; observe again" }
        JSONObject().apply {
            put("observationId", observationId)
            put("securityPolicy", "heuristic_only_not_flag_secure_detection")
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
    } }

    suspend fun executeGuiAction(params: JSONObject, sessionStillValid: () -> Boolean): JSONObject {
        val deadline = GuiSessionGuard.boundedDeadline(System.currentTimeMillis(), params.optLong("deadlineMs", 0L))
        return withTimeout((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) {
            withContext(Dispatchers.Main.immediate) {
                val queuedRevision = guard.revision
                val requiresQueuedRevisionConsistency =
                    params.optString("observationId", "").isNotBlank() ||
                        params.optString("targetNodeId", "").isNotBlank() ||
                        (params.has("x") && params.has("y"))
                guiMutex.withLock {
                check(!guard.stopped) { "ERR_USER_INTERRUPTED: GUI emergency stop is active" }
                if (requiresQueuedRevisionConsistency) {
                    check(guard.revision == queuedRevision) { "ERR_STALE_OBSERVATION: Screen or safety state changed while queued" }
                }
                activeAction = currentCoroutineContext()[Job]
                try { executeLocked(params, deadline, sessionStillValid) }
                finally {
                    activeAction = null
                    safetyOverlay?.cancelConfirmation()
                    if (!guard.stopped) safetyOverlay?.hideActivePill()
                }
            } }
        }
    }

    private suspend fun executeLocked(params: JSONObject, deadline: Long, sessionStillValid: () -> Boolean): JSONObject {
        val action = params.optString("action", "click").lowercase()
        val isSafeExitAction = action in setOf("home", "lock_screen", "lock")
        val isGlobalNavigationAction = action in setOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "lock")
        val initialRoots = collectApplicationRoots()
        windowTracker.track(applicationWindows(initialRoots))
        val initialWindow = windowIdentity(initialRoots)
        val initialRevision = guard.revision
        val targetNodeId = params.optString("targetNodeId", "")
        val observationId = params.optString("observationId", "").takeIf { it.isNotBlank() }
        suspend fun checkReady() {
            currentCoroutineContext().ensureActive()
            val currentWindow = windowIdentity()
            check(currentWindow == initialWindow) { "ERR_STALE_OBSERVATION: Window or package changed" }
            guard.check(System.currentTimeMillis(), deadline, currentWindow, initialRevision,
                observationId, targetNodeId.isNotBlank(), sessionStillValid())
            if (!isSafeExitAction && isBlockedFinancialOrPasswordWindow(rootInActiveWindow)) {
                throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Payment/password safety policy")
            }
        }
        checkReady()
        val pillShown = safetyOverlay?.showActivePill() == true
        if (!isGlobalNavigationAction) {
            check(pillShown) { "ERR_SAFETY_OVERLAY_UNAVAILABLE: Enable phone overlay permission" }
        }

        val root = rootInActiveWindow
        if (!isSafeExitAction && isBlockedFinancialOrPasswordWindow(root)) {
            safetyOverlay?.hideActivePill()
            throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Payment/password safety policy")
        }

        val text = params.optString("text", "")
        val requireConfirm = params.optBoolean("requireUserConfirm", false)

        val targetNode = if (targetNodeId.isNotEmpty()) {
            SemanticTreeExtractor.getNodeById(targetNodeId)
        } else {
            null
        }

        if (targetNodeId.isNotBlank() && (targetNode == null || !targetNode.refresh())) {
            throw IllegalStateException("ERR_STALE_OBSERVATION: Target node unavailable; observe again")
        }

        // Guardrail #3 & #4: Check if target node label contains sensitive keywords ("提交", "发送", "删除", etc.)
        val targetLabel = (targetNode?.text?.toString() ?: targetNode?.contentDescription?.toString() ?: "").trim()
        val hitsSensitiveKeyword = SENSITIVE_ACTION_KEYWORDS.any { kw -> targetLabel.contains(kw) }

        if (requireConfirm || (action in setOf("click", "long_click") && hitsSensitiveKeyword)) {
            val confirmed = safetyOverlay?.awaitUserConfirmForSensitiveAction(
                if (targetLabel.isNotEmpty()) "点击「$targetLabel」" else "执行 $action"
            ) ?: false
            if (!confirmed) {
                safetyOverlay?.hideActivePill()
                throw IllegalStateException("ERR_USER_INTERRUPTED: User declined sensitive GUI action")
            }
        }

        checkReady()
        if (targetNode != null && !targetNode.refresh()) {
            throw IllegalStateException("ERR_STALE_OBSERVATION: Target expired while waiting; observe again")
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
                        checkReady()
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
            "quick_settings" -> ok = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
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

        // Action execution is complete; clear activeAction before the 400ms post-observation
        // settle window so a user touching the screen after the action does not falsely latch stop.
        activeAction = null

        // Wait 400ms for UI transition to settle and return post-action compact tree
        delay(400)
        val dm = resources.displayMetrics
        val postRoots = collectApplicationRoots()
        windowTracker.track(applicationWindows(postRoots))
        val postObservationId = guard.observe(windowIdentity(postRoots))
        val postTree = SemanticTreeExtractor.extractCompactTreeFromRoots(
            if (isBlockedFinancialOrPasswordWindow(postRoots.firstOrNull())) emptyList() else postRoots,
            dm.widthPixels,
            dm.heightPixels
        )
        val postArr = JSONArray()
        for (n in postTree) postArr.put(n.toJson())

        safetyOverlay?.hideActivePill()

        return JSONObject().apply {
            put("ok", ok)
            put("observationId", postObservationId)
            put("packageName", currentPackageName)
            put("postActionTree", postArr)
        }
    }

    fun abortGuiSession() {
        val stop = { if (::guard.isInitialized) setStopped("remote_abort") }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else Handler(Looper.getMainLooper()).post(stop)
    }

    suspend fun performSystemGlobalAction(actionName: String, deadlineMs: Long, sessionStillValid: () -> Boolean): Boolean {
        val result = executeGuiAction(JSONObject().put("action", actionName)
            .put("deadlineMs", deadlineMs), sessionStillValid)
        return result.optBoolean("ok")
    }

    private data class PatternGridResolution(
        val centers: List<PointF>,
        val bounds: Rect,
        val source: String
    )

    private fun parsePatternCells(raw: String): List<Int> {
        val result = mutableListOf<Int>()
        for (ch in raw) {
            if (ch in '1'..'9') {
                val cell = ch - '1' // 0..8
                if (result.lastOrNull() != cell) {
                    result.add(cell)
                }
            }
        }
        return result
    }

    private fun collectAllWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val allWindows = windows
            if (!allWindows.isNullOrEmpty()) {
                for (w in allWindows) {
                    val rNode = w.root ?: continue
                    if (roots.none { it.windowId == rNode.windowId }) {
                        roots.add(rNode)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val active = rootInActiveWindow
        if (active != null && roots.none { it.windowId == active.windowId }) {
            roots.add(active)
        }
        return roots
    }

    private fun isLockPatternViewCandidate(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val cls = node.className?.toString()?.lowercase() ?: ""
        // Exclude full-screen root containers like keyguard_pattern_view or parent viewgroups
        if (viewId.endsWith("keyguard_pattern_view") || viewId.contains("container") || viewId.contains("root")) {
            return false
        }
        val isPatternIdOrCls = viewId.endsWith("lockpatternview") ||
            viewId.endsWith("pattern_view") ||
            viewId.endsWith("patternview") ||
            viewId.endsWith("lock_pattern") ||
            cls.endsWith("lockpatternview") ||
            cls.endsWith("patternview") ||
            cls.contains("lockpattern")

        if (isPatternIdOrCls) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val isNearSquare = kotlin.math.abs(r.width() - r.height()) <= (r.width() * 0.18).toInt()
            val validWidth = r.width() in (screenW * 0.40).toInt()..(screenW * 0.95).toInt()
            val validHeight = r.height() in (screenW * 0.40).toInt()..(screenH * 0.60).toInt()
            if (isNearSquare && validWidth && validHeight) {
                return true
            }
        }
        return false
    }

    private fun gridFromRect(rect: Rect, source: String): PatternGridResolution {
        val side = minOf(rect.width(), rect.height()).toFloat().coerceAtLeast(180f)
        val startX = rect.exactCenterX() - side / 2f
        val startY = rect.exactCenterY() - side / 2f
        val step = side / 3f
        val centers = (0 until 9).map { idx ->
            val r = idx / 3
            val c = idx % 3
            PointF(startX + (c + 0.5f) * step, startY + (r + 0.5f) * step)
        }
        val normalizedRect = Rect(
            startX.toInt(),
            startY.toInt(),
            (startX + side).toInt(),
            (startY + side).toInt()
        )
        return PatternGridResolution(centers, normalizedRect, source)
    }

    private fun resolvePatternGridCenters(
        screenW: Int,
        screenH: Int,
        customBoundsStr: String
    ): PatternGridResolution? {
        // Tier 0: Explicit custom bounds ("left,top,right,bottom" as pixels or 0..1 ratios)
        if (customBoundsStr.isNotBlank()) {
            val parts = customBoundsStr.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (parts.size == 4) {
                val isRatio = parts.all { it in 0f..1f }
                val l = if (isRatio) (parts[0] * screenW).toInt() else parts[0].toInt()
                val t = if (isRatio) (parts[1] * screenH).toInt() else parts[1].toInt()
                val r = if (isRatio) (parts[2] * screenW).toInt() else parts[2].toInt()
                val b = if (isRatio) (parts[3] * screenH).toInt() else parts[3].toInt()
                if (r > l + 100 && b > t + 100) {
                    return gridFromRect(Rect(l, t, r, b), "custom_bounds")
                }
            }
        }

        val roots = collectAllWindowRoots()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.addLast(it) }

        var explicitPatternNodeRect: Rect? = null
        var squareSystemCandidateRect: Rect? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val pkg = node.packageName?.toString() ?: ""
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Tier 1: LockPatternView with 9 virtual child nodes (ExploreByTouchHelper)
            if (node.childCount == 9 && isLockPatternViewCandidate(node, screenW, screenH)) {
                val childCenters = mutableListOf<PointF>()
                val unionRect = Rect()
                for (i in 0 until 9) {
                    val ch = node.getChild(i) ?: continue
                    val cr = Rect()
                    ch.getBoundsInScreen(cr)
                    if (cr.width() > 0 && cr.height() > 0 && cr.centerY() > screenH * 0.25) {
                        childCenters.add(PointF(cr.exactCenterX(), cr.exactCenterY()))
                        unionRect.union(cr)
                    }
                }
                val isUnionNearSquare = abs(unionRect.width() - unionRect.height()) <= (unionRect.width() * 0.20).toInt()
                if (childCenters.size == 9 && unionRect.width() in (screenW * 0.40).toInt()..(screenW * 0.95).toInt() && isUnionNearSquare) {
                    // Sort into 3 rows by Y, then each row of 3 by X
                    val sortedByY = childCenters.sortedBy { it.y }
                    val ordered = mutableListOf<PointF>()
                    for (rowIdx in 0 until 3) {
                        val rowPoints = sortedByY.subList(rowIdx * 3, rowIdx * 3 + 3).sortedBy { it.x }
                        ordered.addAll(rowPoints)
                    }
                    return PatternGridResolution(ordered, unionRect, "accessibility_children_9")
                }
            }

            // Tier 2: Explicit LockPatternView container node
            if (explicitPatternNodeRect == null && isLockPatternViewCandidate(node, screenW, screenH)) {
                explicitPatternNodeRect = Rect(rect)
            }

            // Heuristic square view inside systemui bouncer region
            if (squareSystemCandidateRect == null &&
                pkg == "com.android.systemui" &&
                rect.width() in (screenW * 0.50).toInt()..(screenW * 0.94).toInt() &&
                abs(rect.width() - rect.height()) <= (rect.width() * 0.14).toInt() &&
                rect.centerY() in (screenH * 0.40).toInt()..(screenH * 0.86).toInt()
            ) {
                squareSystemCandidateRect = Rect(rect)
            }

            val count = node.childCount
            for (i in 0 until count) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }

        if (explicitPatternNodeRect != null) {
            return gridFromRect(explicitPatternNodeRect, "accessibility_node")
        }
        if (squareSystemCandidateRect != null) {
            return gridFromRect(squareSystemCandidateRect, "accessibility_square_heuristic")
        }
        return fallbackHyperOsPatternGrid(screenW, screenH)
    }

    private fun fallbackHyperOsPatternGrid(screenW: Int, screenH: Int): PatternGridResolution {
        // Xiaomi HyperOS / MIUI measured 3x3 LockPatternView layout (e.g. 175,1516,1045,2386 on 1220x2712)
        val side = (screenW * 0.713f).toInt()
        val left = (screenW - side) / 2
        val top = (screenH * 0.559f).toInt()
        return gridFromRect(Rect(left, top, left + side, top + side), "fallback_hyperos_geometry")
    }

    private suspend fun injectPatternGesture(points: List<PointF>): Boolean {
        if (points.size < 2) return false
        val path = Path()
        val first = points.first()
        path.moveTo(first.x, first.y)
        // Micro-anchor at initial dot so ACTION_DOWN + initial move definitely hit dot 0
        path.lineTo(first.x + 1.5f, first.y + 1.5f)
        for (i in 1 until points.size) {
            val pt = points[i]
            path.lineTo(pt.x, pt.y)
            // Micro-dwell at each vertex so Android's Path sampler always emits samples inside each dot's hit radius
            path.lineTo(pt.x + 1.5f, pt.y)
        }
        val durationMs = (points.size * 150L).coerceIn(450L, 1400L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture)
    }

    private fun findPinDigitNodeCenter(digit: Char, screenW: Int, screenH: Int): PointF? {
        val targetStr = digit.toString()
        val roots = collectAllWindowRoots()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val txt = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            if (txt == targetStr || desc == targetStr || desc.startsWith("$targetStr ") || viewId.endsWith("key$targetStr")) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 20 && r.height() > 20 && r.centerY() > screenH * 0.30) {
                    return PointF(r.exactCenterX(), r.exactCenterY())
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    suspend fun performScreenUnlock(params: JSONObject, sessionStillValid: () -> Boolean): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            if (pm.isInteractive && !km.isKeyguardLocked) {
                return@withContext JSONObject().apply {
                    put("ok", true)
                    put("action", "unlock_screen")
                    put("unlocked", true)
                    put("alreadyUnlocked", true)
                    put("screenLocked", false)
                    put("screenInteractive", true)
                    put("method", "already_unlocked")
                }
            }

            // 1. Wake up screen if off
            if (!pm.isInteractive) {
                AppLauncherHelper.wakeScreenIfNeeded(this@OmsAccessibilityService)
                var waited = 0
                while (!pm.isInteractive && waited < 700) {
                    delay(70)
                    waited += 70
                }
                delay(220)
            }

            if (!km.isKeyguardLocked) {
                return@withContext JSONObject().apply {
                    put("ok", true)
                    put("action", "unlock_screen")
                    put("unlocked", true)
                    put("screenLocked", false)
                    put("screenInteractive", pm.isInteractive)
                    put("method", "wake_only")
                }
            }

            val rawPattern = params.optString("pattern", "").trim()
            val rawPin = params.optString("pin", "").trim()
            val customBounds = params.optString("bounds", "").trim()

            // 2. Check if pattern bouncer is already visible; if not, swipe up to reveal bouncer
            var detectedGrid = resolvePatternGridCenters(screenW, screenH, customBounds)
            val isBouncerAlreadyShowing = detectedGrid != null &&
                detectedGrid.source in setOf("accessibility_children_9", "accessibility_node", "accessibility_square_heuristic")
            var swipedUp = false
            if (!isBouncerAlreadyShowing) {
                check(sessionStillValid()) { "Session closed" }
                injectBezierSwipeGesture(
                    screenW / 2,
                    (screenH * 0.78).toInt(),
                    screenW / 2,
                    (screenH * 0.24).toInt(),
                    240L
                )
                swipedUp = true
                delay(460)
            }

            if (!km.isKeyguardLocked) {
                return@withContext JSONObject().apply {
                    put("ok", true)
                    put("action", "unlock_screen")
                    put("unlocked", true)
                    put("screenLocked", false)
                    put("screenInteractive", pm.isInteractive)
                    put("method", "swipe_only")
                }
            }

            // 3. Pattern unlock execution
            if (rawPattern.isNotEmpty()) {
                val cells = parsePatternCells(rawPattern)
                require(cells.size >= 4) {
                    "图案密码至少需要 4 个点（1~9 对应九宫格左上到右下，如 '14789'）"
                }
                val grid = resolvePatternGridCenters(screenW, screenH, customBounds)
                    ?: fallbackHyperOsPatternGrid(screenW, screenH)
                val pathPoints = cells.map { idx -> grid.centers[idx] }
                check(sessionStillValid()) { "Session closed" }
                val gestureDispatched = injectPatternGesture(pathPoints)

                var waitUnlockMs = 0
                while (km.isKeyguardLocked && waitUnlockMs < 1200) {
                    delay(100)
                    waitUnlockMs += 100
                }
                val unlocked = !km.isKeyguardLocked
                return@withContext JSONObject().apply {
                    put("ok", unlocked)
                    put("action", "unlock_screen")
                    put("unlocked", unlocked)
                    put("gestureDispatched", gestureDispatched)
                    put("swipedUp", swipedUp)
                    put("method", "pattern")
                    put("patternCells", cells.map { it + 1 }.joinToString("-"))
                    put("gridSource", grid.source)
                    put("gridBounds", JSONObject().apply {
                        put("left", grid.bounds.left)
                        put("top", grid.bounds.top)
                        put("right", grid.bounds.right)
                        put("bottom", grid.bounds.bottom)
                    })
                    put("screenLocked", km.isKeyguardLocked)
                    put("screenInteractive", pm.isInteractive)
                    if (!unlocked) {
                        put("error", "已绘制图案 (${cells.map { it + 1 }.joinToString("-")}) 但手机仍处于锁屏状态，请确认图案顺序或九宫格坐标区域")
                    }
                }
            }

            // 4. Optional PIN unlock execution
            if (rawPin.isNotEmpty()) {
                for (ch in rawPin) {
                    if (!ch.isDigit()) continue
                    check(sessionStillValid()) { "Session closed" }
                    val pt = findPinDigitNodeCenter(ch, screenW, screenH)
                    if (pt != null) {
                        injectTapGesture(pt.x.toInt(), pt.y.toInt(), 65L)
                        delay(110)
                    }
                }
                var waitUnlockMs = 0
                while (km.isKeyguardLocked && waitUnlockMs < 1100) {
                    delay(100)
                    waitUnlockMs += 100
                }
                val unlocked = !km.isKeyguardLocked
                return@withContext JSONObject().apply {
                    put("ok", unlocked)
                    put("action", "unlock_screen")
                    put("unlocked", unlocked)
                    put("method", "pin")
                    put("screenLocked", km.isKeyguardLocked)
                    put("screenInteractive", pm.isInteractive)
                }
            }

            // 5. Neither pattern nor PIN provided -> woke screen and swiped up to bouncer
            JSONObject().apply {
                put("ok", !km.isKeyguardLocked)
                put("action", "unlock_screen")
                put("unlocked", !km.isKeyguardLocked)
                put("method", "swipe_bouncer")
                put("screenLocked", km.isKeyguardLocked)
                put("screenInteractive", pm.isInteractive)
                put("hint", "已点亮屏幕并上滑打开图案解锁界面；请传入 --pattern <1~9序列> 完成图案解锁")
            }
        }

    suspend fun performPhoneCall(params: JSONObject, sessionStillValid: () -> Boolean): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            val rawNumber = params.optString("number", params.optString("phoneNumber", params.optString("text", ""))).trim()
            val cleanNumber = rawNumber.filter { it.isDigit() || it in setOf('+', '*', '#', ',') }
            require(cleanNumber.isNotEmpty()) { "请提供要拨打的电话号码" }

            val dialOnly = params.optBoolean("dialOnly", false)
            val enableSpeaker = params.optBoolean("speaker", false)
            val simPref = params.optString("simSlot", params.optString("sim", "1")).trim()

            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) {
                performScreenUnlock(params, sessionStillValid)
                delay(350)
            }

            val (opened, errMsg) = AppLauncherHelper.openDialerWithNumber(this@OmsAccessibilityService, cleanNumber)
            if (!opened) {
                return@withContext JSONObject().apply {
                    put("ok", false)
                    put("action", "call_phone")
                    put("number", cleanNumber)
                    put("error", errMsg ?: "无法唤起系统拨号盘")
                }
            }

            if (dialOnly) {
                delay(350)
                return@withContext JSONObject().apply {
                    put("ok", true)
                    put("action", "call_phone")
                    put("number", cleanNumber)
                    put("dialedOnly", true)
                    put("callPlaced", false)
                }
            }

            delay(520)
            check(sessionStillValid()) { "Session closed" }

            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val roots = collectAllWindowRoots()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            roots.forEach { queue.addLast(it) }

            data class CallBtnCandidate(val node: AccessibilityNodeInfo, val label: String, val rect: Rect)
            val candidates = mutableListOf<CallBtnCandidate>()

            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val cls = n.className?.toString() ?: ""
                val lbl = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                val r = Rect()
                n.getBoundsInScreen(r)
                if (n.isVisibleToUser && n.isClickable &&
                    (cls.endsWith("Button") || cls.endsWith("ImageButton") || lbl.contains("电信") || lbl.contains("联通") || lbl.contains("移动") || lbl.contains("拨打") || lbl.contains("呼叫")) &&
                    !lbl.contains("菜单") && !lbl.contains("关闭") && !lbl.contains("删除") &&
                    r.centerY() in (screenH * 0.74).toInt()..(screenH * 0.93).toInt() &&
                    r.centerX() in (screenW * 0.18).toInt()..(screenW * 0.82).toInt()
                ) {
                    candidates.add(CallBtnCandidate(n, lbl, r))
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
            }

            val sorted = candidates.sortedBy { it.rect.centerX() }
            val chosen = when {
                sorted.isEmpty() -> null
                simPref.isNotEmpty() && sorted.any { it.label.contains(simPref, ignoreCase = true) } ->
                    sorted.first { it.label.contains(simPref, ignoreCase = true) }
                simPref == "2" && sorted.size >= 2 -> sorted[1]
                else -> sorted.first()
            }

            var clicked = false
            var simLabel = chosen?.label ?: "默认SIM卡"
            if (chosen != null) {
                clicked = chosen.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    clicked = injectTapGesture(chosen.rect.centerX(), chosen.rect.centerY(), 75L)
                }
            } else {
                // Fallback tap on Xiaomi dual-SIM button 1 or button 2 coordinates
                val targetX = if (simPref == "2") (screenW * 0.64).toInt() else (screenW * 0.36).toInt()
                val targetY = (screenH * 0.864).toInt()
                clicked = injectTapGesture(targetX, targetY, 75L)
                simLabel = if (simPref == "2") "SIM卡2" else "SIM卡1"
            }

            var speakerEnabled = false
            if (clicked && enableSpeaker) {
                delay(1800)
                val callRoots = collectAllWindowRoots()
                val q2 = ArrayDeque<AccessibilityNodeInfo>()
                callRoots.forEach { q2.addLast(it) }
                while (q2.isNotEmpty()) {
                    val n = q2.removeFirst()
                    val lbl = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                    if (n.isVisibleToUser && (lbl.contains("免提") || lbl.contains("扬声器") || lbl.contains("Speaker", ignoreCase = true))) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        speakerEnabled = n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            injectTapGesture(r.centerX(), r.centerY(), 75L)
                        break
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { q2.addLast(it) }
                    }
                }
            }

            delay(400)
            JSONObject().apply {
                put("ok", clicked)
                put("action", "call_phone")
                put("number", cleanNumber)
                put("simUsed", simLabel)
                put("callPlaced", clicked)
                put("speakerEnabled", speakerEnabled)
            }
        }

    suspend fun performHangupCall(sessionStillValid: () -> Boolean): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            check(sessionStillValid()) { "Session closed" }
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val roots = collectAllWindowRoots()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            roots.forEach { queue.addLast(it) }

            var hungUp = false
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val lbl = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                val vid = n.viewIdResourceName?.lowercase() ?: ""
                if (n.isVisibleToUser && (lbl.contains("挂断") || lbl.contains("结束通话") || vid.contains("endbutton") || vid.contains("hangup") || vid.contains("end_call"))) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    hungUp = n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        injectTapGesture(r.centerX(), r.centerY(), 75L)
                    if (hungUp) break
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
            }

            if (!hungUp) {
                hungUp = injectTapGesture(screenW / 2, (screenH * 0.865).toInt(), 75L)
            }
            delay(300)
            JSONObject().apply {
                put("ok", hungUp)
                put("action", "hangup_call")
                put("hungUp", hungUp)
            }
        }

    private suspend fun injectTapGesture(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture)
    }

    private suspend fun injectBezierSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            val ctrlX = (x1 + x2) / 2f + Random.nextInt(-25, 26)
            val ctrlY = (y1 + y2) / 2f
            quadTo(ctrlX, ctrlY, x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture)
    }
    private suspend fun dispatchAndAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

}
