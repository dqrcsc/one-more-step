package com.oms.client.agent

import android.app.Notification
import com.oms.client.otp.OtpExtractor
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
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
            val webpBytes = ScreenCapturer.captureWebpBytes(this@OmsAccessibilityService, extractedNodes, somAnnotation)
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
