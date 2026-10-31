package com.oms.client.agent

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import com.oms.client.capability.AppLauncherHelper
import com.oms.client.capability.NotificationExecutor
import com.oms.client.otp.OtpExtractor
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.telecom.TelecomManager
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
    }

    fun isEmergencyStopped(): Boolean = ::guard.isInitialized && guard.stopped

    fun resumeFromPhoneUi() {
        if (!::guard.isInitialized) return
        guard.resumeOnPhone()
        stopPreferences.edit().putBoolean("stopped", false).apply()
        safetyOverlay?.hideActivePill()
    }

    @Volatile
    var currentPackageName: String = ""
        private set

    @Volatile
    var currentActivityName: String = ""
        private set

    @Volatile
    var onMirrorUiEvent: (() -> Unit)? = null

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
            onMirrorUiEvent?.invoke()
            if (windowTracker.windowsChanged(applicationWindows(collectApplicationRoots()))) {
                guard.invalidate("application windows changed")
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onMirrorUiEvent?.invoke()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.windowId in applicationWindows(collectApplicationRoots())) {
                event.packageName?.toString()?.let { currentPackageName = it }
                event.className?.toString()?.takeUnless {
                    it.startsWith("android.widget.") || it.startsWith("android.view.") || it.startsWith("android.inputmethodservice.")
                }?.let { currentActivityName = it }
            }
            if (!windowTracker.belongsToApplication(event.windowId, event.packageName?.toString())) return
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                guard.invalidate("event=${event.eventType}, window=${event.windowId}, package=${event.packageName}, class=${event.className}")
            }
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

    private fun isBlockedFinancialOrPasswordWindow(
        root: AccessibilityNodeInfo?,
        allRoots: List<AccessibilityNodeInfo> = emptyList()
    ): Boolean {
        val candidateRoots = if (allRoots.isNotEmpty()) allRoots else listOfNotNull(root)
        val hasFocusedPassword = candidateRoots.any { r ->
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true
        }
        return SensitiveActionPolicy.isBlockedWindowSet(
            currentPackageName = currentPackageName,
            currentActivityName = currentActivityName,
            rootPackages = candidateRoots.map { it.packageName?.toString() },
            hasFocusedPasswordInput = hasFocusedPassword
        )
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

        if (isBlockedFinancialOrPasswordWindow(primaryRoot, roots)) {
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
        val hasCoordinates = params.has("x") && params.has("y")
        val requiresObservationId = SensitiveActionPolicy.requiresObservationId(action, targetNodeId, hasCoordinates)
        val observationId = params.optString("observationId", "").takeIf { it.isNotBlank() }
        suspend fun checkReady() {
            currentCoroutineContext().ensureActive()
            val currentRoots = collectApplicationRoots()
            val currentWindow = windowIdentity(currentRoots)
            check(currentWindow == initialWindow) { "ERR_STALE_OBSERVATION: Window or package changed" }
            guard.check(System.currentTimeMillis(), deadline, currentWindow, initialRevision,
                observationId, requiresObservationId, sessionStillValid())
            if (!isSafeExitAction && isBlockedFinancialOrPasswordWindow(rootInActiveWindow, currentRoots)) {
                throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Payment/password safety policy")
            }
        }
        checkReady()
        val pillShown = safetyOverlay?.showActivePill() == true
        if (!isGlobalNavigationAction) {
            check(pillShown) { "ERR_SAFETY_OVERLAY_UNAVAILABLE: Enable phone overlay permission" }
        }

        val root = rootInActiveWindow
        if (!isSafeExitAction && isBlockedFinancialOrPasswordWindow(root, initialRoots)) {
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
        if (targetNode?.isPassword == true) {
            safetyOverlay?.hideActivePill()
            throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Password field interaction is blocked")
        }

        val hitNodeForCoords = if (targetNode == null && hasCoordinates && action in setOf("click", "long_click")) {
            SemanticTreeExtractor.findSmallestNodeAt(params.optInt("x"), params.optInt("y"))
        } else {
            null
        }

        // Guardrail #3 & #4: Check aggregated node label or coordinate hit-tested node label for sensitive keywords
        val targetLabel = when {
            targetNode != null -> SemanticTreeExtractor.extractNodeLabel(targetNode)
            hitNodeForCoords != null -> hitNodeForCoords.label.trim()
            else -> ""
        }

        if (SensitiveActionPolicy.requiresUserConfirmation(action, requireConfirm, targetLabel)) {
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
            if (node.packageName?.toString() == "com.android.systemui" && node.isVisibleToUser && node.isEnabled &&
                (txt == targetStr || desc == targetStr || desc.startsWith("$targetStr ") || viewId.endsWith("key$targetStr"))) {
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

    private suspend fun <T> withGuardedPhoneAction(
        params: JSONObject,
        sessionStillValid: () -> Boolean,
        maxDurationMs: Long = 25_000L,
        action: suspend GuardedRemoteAction.Steps.() -> T
    ): T = withContext(Dispatchers.Main.immediate) {
        GuardedRemoteAction(guard, guiMutex, maxDurationMs = maxDurationMs).run(
            requestedDeadline = if (params.has("deadlineMs")) params.optLong("deadlineMs") else null,
            sessionValid = sessionStillValid,
            activeJob = { activeAction = it },
            action = {
                safetyOverlay?.showActivePill()
                try {
                    action()
                } finally {
                    if (!guard.stopped) safetyOverlay?.hideActivePill()
                }
            }
        )
    }

    suspend fun performScreenUnlock(params: JSONObject, sessionStillValid: () -> Boolean): JSONObject =
        withGuardedPhoneAction(params, sessionStillValid) { performScreenUnlockLocked(params, this) }

    private suspend fun performScreenUnlockLocked(
        params: JSONObject,
        steps: GuardedRemoteAction.Steps
    ): JSONObject {
            steps.check()
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            fun requireKeyguardInputSurface() {
                check(km.isKeyguardLocked) { "ERR_UNLOCK_STATE_CHANGED: Keyguard is no longer locked" }
                check(rootInActiveWindow?.packageName?.toString() == "com.android.systemui") {
                    "ERR_KEYGUARD_SURFACE_UNAVAILABLE: No active system keyguard surface"
                }
            }

            if (pm.isInteractive && !km.isKeyguardLocked) {
                return JSONObject().apply {
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
                steps.effect { AppLauncherHelper.wakeScreenIfNeeded(this@OmsAccessibilityService) }
                var waited = 0
                while (!pm.isInteractive && waited < 700) {
                    steps.pause(70)
                    waited += 70
                }
                steps.pause(220)
            }

            if (!km.isKeyguardLocked) {
                return JSONObject().apply {
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
            if (km.isKeyguardLocked && rootInActiveWindow?.packageName?.toString() == "com.mfashiongallery.emag") {
                steps.effect { performGlobalAction(GLOBAL_ACTION_BACK) }
                steps.pause(350)
            }
            var detectedGrid = resolvePatternGridCenters(screenW, screenH, customBounds)
            val isBouncerAlreadyShowing = detectedGrid != null &&
                detectedGrid.source in setOf("accessibility_children_9", "accessibility_node", "accessibility_square_heuristic")
            var swipedUp = false
            if (!isBouncerAlreadyShowing) {
                steps.check()
                requireKeyguardInputSurface()
                steps.effect { injectBezierSwipeGesture(
                    screenW / 2,
                    (screenH * 0.78).toInt(),
                    screenW / 2,
                    (screenH * 0.24).toInt(),
                    240L
                ) }
                swipedUp = true
                steps.pause(460)
            }

            if (!km.isKeyguardLocked) {
                return JSONObject().apply {
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
                requireKeyguardInputSurface()
                val cells = parsePatternCells(rawPattern)
                require(cells.size >= 4) {
                    "图案密码至少需要 4 个点（1~9 对应九宫格左上到右下，如 '14789'）"
                }
                val grid = resolvePatternGridCenters(screenW, screenH, customBounds)
                    ?: fallbackHyperOsPatternGrid(screenW, screenH)
                val pathPoints = cells.map { idx -> grid.centers[idx] }
                steps.check()
                val gestureDispatched = steps.effect { injectPatternGesture(pathPoints) }

                var waitUnlockMs = 0
                while (km.isKeyguardLocked && waitUnlockMs < 1200) {
                    steps.pause(100)
                    waitUnlockMs += 100
                }
                val unlocked = !km.isKeyguardLocked
                return JSONObject().apply {
                    put("ok", unlocked)
                    put("action", "unlock_screen")
                    put("unlocked", unlocked)
                    put("gestureDispatched", gestureDispatched)
                    put("swipedUp", swipedUp)
                    put("method", "pattern")
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
                        put("error", "已尝试图案解锁但手机仍处于锁屏状态，请在手机上确认解锁方式或九宫格坐标区域")
                    }
                }
            }

            // 4. Optional PIN unlock execution
            if (rawPin.isNotEmpty()) {
                require(rawPin.all { it in '0'..'9' }) { "PIN must contain decimal digits only" }
                for (ch in rawPin) {
                    steps.check()
                    requireKeyguardInputSurface()
                    val pt = findPinDigitNodeCenter(ch, screenW, screenH)
                        ?: throw IllegalStateException("ERR_PIN_TARGET_UNAVAILABLE: No verified keyguard digit target")
                    check(steps.effect { injectTapGesture(pt.x.toInt(), pt.y.toInt(), 65L) }) {
                        "ERR_PIN_INPUT_FAILED: Keyguard input was not accepted"
                    }
                    steps.pause(110)
                }
                var waitUnlockMs = 0
                while (km.isKeyguardLocked && waitUnlockMs < 1100) {
                    steps.pause(100)
                    waitUnlockMs += 100
                }
                val unlocked = !km.isKeyguardLocked
                return JSONObject().apply {
                    put("ok", unlocked)
                    put("action", "unlock_screen")
                    put("unlocked", unlocked)
                    put("method", "pin")
                    put("screenLocked", km.isKeyguardLocked)
                    put("screenInteractive", pm.isInteractive)
                }
            }

            // 5. Neither pattern nor PIN provided -> woke screen and swiped up to bouncer
            return JSONObject().apply {
                put("ok", !km.isKeyguardLocked)
                put("action", "unlock_screen")
                put("unlocked", !km.isKeyguardLocked)
                put("method", "swipe_bouncer")
                put("screenLocked", km.isKeyguardLocked)
                put("screenInteractive", pm.isInteractive)
                put("hint", "已点亮屏幕并上滑打开图案解锁界面；请传入 --pattern <1~9序列> 完成图案解锁")
            }
        }

    private fun findClickableCallControl(
        roots: List<AccessibilityNodeInfo>,
        allowedPackages: Set<String>,
        predicate: (label: String, viewId: String) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.filter { it.packageName?.toString() in allowedPackages }
            .forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val pkg = n.packageName?.toString() ?: ""
            if (pkg in allowedPackages && n.isVisibleToUser && n.isEnabled) {
                val lbl = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                val viewId = n.viewIdResourceName ?: ""
                if (predicate(lbl, viewId)) {
                    if (n.isClickable) return n
                    // On Xiaomi HyperOS com.android.incallui, outer Switch ("免提" / "挂断电话") has
                    // clickable=false while its direct child ImageButton/ImageView has clickable=true and label=""
                    val childQueue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { childQueue.addLast(it to 1) }
                    }
                    while (childQueue.isNotEmpty()) {
                        val (child, d) = childQueue.removeFirst()
                        if (child.packageName?.toString() in allowedPackages &&
                            child.isVisibleToUser && child.isEnabled && child.isClickable
                        ) {
                            return child
                        }
                        if (d < 2) {
                            for (j in 0 until child.childCount) {
                                child.getChild(j)?.let { childQueue.addLast(it to (d + 1)) }
                            }
                        }
                    }
                    var parent = n.parent
                    var depth = 0
                    while (parent != null && depth < 3) {
                        if (parent.packageName?.toString() in allowedPackages &&
                            parent.isVisibleToUser && parent.isEnabled && parent.isClickable
                        ) {
                            return parent
                        }
                        parent = parent.parent
                        depth += 1
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun findSpeakerSwitchCheckedState(
        roots: List<AccessibilityNodeInfo>,
        allowedPackages: Set<String>
    ): Boolean? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.filter { it.packageName?.toString() in allowedPackages }
            .forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val pkg = n.packageName?.toString() ?: ""
            if (pkg in allowedPackages && n.isVisibleToUser) {
                val lbl = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                val viewId = n.viewIdResourceName ?: ""
                if (DialerTargetPolicy.isSpeakerControl(lbl, viewId) && n.isCheckable) {
                    return n.isChecked
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private suspend fun ensureSpeakerphoneEnabled(
        steps: GuardedRemoteAction.Steps,
        callPackages: Set<String>,
        isInCall: () -> Boolean?
    ): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        fun isSpeakerActive(): Boolean {
            val uiChecked = findSpeakerSwitchCheckedState(collectAllWindowRoots(), callPackages)
            if (uiChecked != null) return uiChecked
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (audio?.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return true
                }
            }
            @Suppress("DEPRECATION")
            return audio?.isSpeakerphoneOn == true
        }
        if (isSpeakerActive()) return true
        if (isInCall() != true) return false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val speakerDev = audio?.availableCommunicationDevices?.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speakerDev != null) {
                    audio.setCommunicationDevice(speakerDev)
                }
            } catch (_: Throwable) {
            }
        }
        if (isSpeakerActive()) return true

        val speakerNode = findClickableCallControl(
            roots = collectAllWindowRoots(),
            allowedPackages = callPackages,
            predicate = DialerTargetPolicy::isSpeakerControl
        )
        speakerNode?.let { node ->
            steps.effect {
                if (isInCall() == true && node.refresh() && node.isVisibleToUser && node.isEnabled &&
                    node.packageName?.toString() in callPackages && node.isClickable
                ) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        val b = Rect()
                        node.getBoundsInScreen(b)
                        injectTapGesture(b.centerX(), b.centerY(), 75L)
                    } else {
                        true
                    }
                } else {
                    false
                }
            }
            steps.pause(250L)
            if (!isSpeakerActive() && isInCall() == true) {
                // If a Bluetooth audio-routing popup sheet opened, click the explicit "扬声器 / 免提" item
                val popupItem = findClickableCallControl(
                    roots = collectAllWindowRoots(),
                    allowedPackages = callPackages,
                    predicate = { lbl, _ ->
                        val t = lbl.trim().lowercase()
                        t in setOf("扬声器", "免提", "外放", "speaker", "speakerphone")
                    }
                )
                popupItem?.let { item ->
                    steps.effect {
                        if (isInCall() == true && item.refresh() && item.isVisibleToUser &&
                            item.isEnabled && item.isClickable
                        ) {
                            item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                    steps.pause(200L)
                }
            }
        }
        if (isSpeakerActive()) return true
        try {
            @Suppress("DEPRECATION")
            audio?.isSpeakerphoneOn = true
        } catch (_: Throwable) {
        }
        return isSpeakerActive()
    }

    private suspend fun executeVerifiedHangup(
        trustedPackages: Set<String>,
        isInCall: () -> Boolean?,
        checkAuth: suspend () -> Unit,
        pause: suspend (Long) -> Unit
    ): HangupCallVerifier.Result {
        checkAuth()
        return HangupCallVerifier.execute(
            isInCall = isInCall,
            clickVerifiedTarget = {
                AppLauncherHelper.wakeScreenIfNeeded(this@OmsAccessibilityService)
                val target = findClickableCallControl(
                    roots = collectAllWindowRoots(),
                    allowedPackages = trustedPackages,
                    predicate = DialerTargetPolicy::isHangupControl
                )
                target?.let {
                    !guard.stopped && it.refresh() && it.isVisibleToUser && it.isEnabled && it.isClickable &&
                        it.packageName?.toString() in trustedPackages && isInCall() == true &&
                        it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } ?: false
            },
            awaitStateUpdate = {
                pause(150L)
                checkAuth()
            }
        )
    }

    suspend fun performPhoneCall(params: JSONObject, sessionStillValid: () -> Boolean): JSONObject {
        val dialOnly = params.optBoolean("dialOnly", false)
        val sayText = params.optString("sayText", params.optString("say", "")).trim()
        val aiCallText = params.optString("aiCallText", params.optString("aiCall", "")).trim()
        val waitForAnswerOnly = params.optBoolean("waitForAnswer", false)
        val audioBase64 = params.optString("audioBase64", "").takeIf { it.isNotBlank() }
        val hasInCallSpeech = !dialOnly && (sayText.isNotEmpty() || audioBase64 != null || aiCallText.isNotEmpty() || waitForAnswerOnly)
        return withGuardedPhoneAction(
            params = params,
            sessionStillValid = sessionStillValid,
            maxDurationMs = if (hasInCallSpeech) 90_000L else 25_000L
        ) {
            val steps = this
            val rawNumber = params.optString("number", params.optString("phoneNumber", params.optString("text", ""))).trim()
            val cleanNumber = rawNumber.filter { it.isDigit() || it in setOf('+', '*', '#', ',') }
            require(cleanNumber.isNotEmpty()) { "请提供要拨打的电话号码" }
            val enableSpeaker = if (params.has("speaker")) {
                params.optBoolean("speaker", false)
            } else {
                !dialOnly && (sayText.isNotEmpty() || audioBase64 != null || waitForAnswerOnly)
            }
            val simPref = params.optString("simSlot", params.optString("sim", "")).trim()
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val inCallUiPkgs = setOf("com.android.incallui", "com.miui.incallui")
            fun isInCall(): Boolean? {
                try {
                    val tc = telecom?.isInCall
                    if (tc != null) return tc
                } catch (_: SecurityException) {
                }
                val mode = audio?.mode
                if (mode == android.media.AudioManager.MODE_IN_CALL ||
                    mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                    mode == android.media.AudioManager.MODE_RINGTONE
                ) {
                    return true
                }
                return collectAllWindowRoots().any { it.packageName?.toString() in inCallUiPkgs }
            }
            fun failed(reason: String, requested: Boolean = false) = JSONObject().apply {
                put("ok", false)
                put("action", "call_phone")
                put("callPlaced", false)
                put("callRequested", requested)
                put("reason", reason)
            }

            steps.requireUnlocked {
                if (!km.isKeyguardLocked) true
                else performScreenUnlockLocked(params, steps).optBoolean("unlocked", false) && !km.isKeyguardLocked
            }
            if (!dialOnly && isInCall() != false) {
                return@withGuardedPhoneAction failed("call_state_unavailable_or_already_active")
            }

            val dialerIntent = android.content.Intent(android.content.Intent.ACTION_DIAL,
                android.net.Uri.fromParts("tel", cleanNumber, null))
            val dialerPackages = setOfNotNull(
                try { telecom?.defaultDialerPackage } catch (_: SecurityException) { null },
                dialerIntent.resolveActivity(packageManager)?.packageName
            ).filterNot { it in setOf("android", "com.android.intentresolver") }.toSet()
            if (dialerPackages.isEmpty()) return@withGuardedPhoneAction failed("dialer_unavailable")

            val (opened, _) = steps.effect {
                check(!km.isKeyguardLocked) { "ERR_UNLOCK_FAILED: Phone locked before opening dialer" }
                AppLauncherHelper.openDialerWithNumber(this@OmsAccessibilityService, cleanNumber)
            }
            if (!opened) return@withGuardedPhoneAction failed("dialer_open_failed")

            fun dialerNodes(): List<AccessibilityNodeInfo> {
                val result = mutableListOf<AccessibilityNodeInfo>()
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                collectApplicationRoots().filter { it.packageName?.toString() in dialerPackages }
                    .forEach { queue.addLast(it) }
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    if (n.packageName?.toString() in dialerPackages && n.isVisibleToUser) result.add(n)
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
                }
                return result
            }
            fun hasRequestedNumber(nodes: List<AccessibilityNodeInfo>): Boolean = nodes.any { node ->
                listOf(node.text, node.contentDescription).any { value ->
                    value?.toString()?.filter { it.isDigit() || it in setOf('+', '*', '#', ',') } == cleanNumber
                }
            }
            var nodes = emptyList<AccessibilityNodeInfo>()
            val numberReady = DialerReadiness.awaitVerifiedNumber(
                isLocked = { km.isKeyguardLocked },
                numberMatches = {
                    nodes = dialerNodes()
                    hasRequestedNumber(nodes)
                },
                checkAuthorization = { steps.check() },
                pause = { steps.pause(it) }
            )
            if (!numberReady) {
                return@withGuardedPhoneAction failed("dialer_number_not_verified")
            }
            if (dialOnly) return@withGuardedPhoneAction JSONObject().apply {
                put("ok", true)
                put("action", "call_phone")
                put("number", cleanNumber)
                put("dialedOnly", true)
                put("callPlaced", false)
            }

            fun label(node: AccessibilityNodeInfo) =
                SemanticTreeExtractor.firstNonBlank(node.text, node.contentDescription)
            fun isCallButton(node: AccessibilityNodeInfo): Boolean =
                node.packageName?.toString() in dialerPackages && node.isVisibleToUser &&
                    node.isEnabled && node.isClickable &&
                    DialerTargetPolicy.isCallControl(label(node), node.viewIdResourceName ?: "")
            fun matchesSim(node: AccessibilityNodeInfo): Boolean =
                DialerTargetPolicy.matchesSim(label(node), node.viewIdResourceName ?: "", simPref)
            val candidates = nodes.filter { isCallButton(it) && matchesSim(it) }
            val chosen = candidates.singleOrNull()
                ?: return@withGuardedPhoneAction failed("call_target_or_sim_not_verified")
            val chosenSimLabel = label(chosen)
            val clicked = steps.effect {
                nodes = dialerNodes()
                !km.isKeyguardLocked && isInCall() == false && hasRequestedNumber(nodes) &&
                    chosen.refresh() && isCallButton(chosen) && matchesSim(chosen) &&
                    chosen.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) return@withGuardedPhoneAction failed("call_action_rejected")

            var callConfirmed = false
            for (attempt in 0 until 15) {
                steps.pause(100L)
                val state = isInCall()
                if (state == true) { callConfirmed = true; break }
                if (state == null) break
            }
            if (!callConfirmed) return@withGuardedPhoneAction failed("call_not_confirmed", requested = true)

            val callPackages = dialerPackages + setOf("com.android.incallui", "com.miui.incallui")
            var speakerEnabled = false
            if (enableSpeaker) {
                steps.pause(500L)
                speakerEnabled = ensureSpeakerphoneEnabled(steps, callPackages, ::isInCall)
            }

            if (!hasInCallSpeech) {
                return@withGuardedPhoneAction JSONObject().apply {
                    put("ok", true)
                    put("action", "call_phone")
                    put("number", cleanNumber)
                    put("simUsed", chosenSimLabel)
                    put("callRequested", true)
                    put("callPlaced", true)
                    put("speakerEnabled", speakerEnabled)
                }
            }

            // Wait for the remote party to answer the call before playing Neural TTS speech
            val waitAnswerMs = (params.optInt("waitAnswerSec", 35).coerceIn(5, 65) * 1000L)
            val postAnswerDelayMs = params.optLong("postAnswerDelayMs", 650L).coerceIn(150L, 4000L)
            val repeatCount = params.optInt("repeatCount", params.optInt("repeat", 1)).coerceIn(1, 10)
            val hangupAfter = params.optBoolean("hangupAfter", false)
            val useAlarmStream = params.optBoolean("useAlarmStream", true)

            fun collectCallWindowSnapshots(): List<CallAnswerDetector.NodeSnapshot> {
                val list = mutableListOf<CallAnswerDetector.NodeSnapshot>()
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                collectAllWindowRoots().filter { it.packageName?.toString() in callPackages }
                    .forEach { queue.addLast(it) }
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    if (n.packageName?.toString() in callPackages && n.isVisibleToUser) {
                        val txt = n.text?.toString()?.trim() ?: ""
                        val desc = n.contentDescription?.toString()?.trim() ?: ""
                        val vid = n.viewIdResourceName ?: ""
                        if (txt.isNotEmpty() || desc.isNotEmpty() || vid.isNotEmpty()) {
                            list.add(CallAnswerDetector.NodeSnapshot(txt, desc, vid))
                        }
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { queue.addLast(it) }
                    }
                }
                return list
            }

            val answerResult = CallAnswerDetector.awaitCallAnswered(
                maxWaitMs = waitAnswerMs,
                pollIntervalMs = 250L,
                isInCall = ::isInCall,
                observeInCallNodes = ::collectCallWindowSnapshots,
                checkAuthorization = { steps.check() },
                pause = { ms ->
                    AppLauncherHelper.wakeScreenIfNeeded(this@OmsAccessibilityService)
                    steps.pause(ms)
                }
            )

            if (!answerResult.answered) {
                var hungUpOnTimeout = false
                if (answerResult.reason == "answer_timeout" && isInCall() == true) {
                    hungUpOnTimeout = executeVerifiedHangup(
                        trustedPackages = callPackages,
                        isInCall = ::isInCall,
                        checkAuth = { steps.check() },
                        pause = { steps.pause(it) }
                    ).hungUp
                }
                return@withGuardedPhoneAction JSONObject().apply {
                    put("ok", false)
                    put("action", "call_phone")
                    put("number", cleanNumber)
                    put("simUsed", chosenSimLabel)
                    put("callRequested", true)
                    put("callPlaced", true)
                    put("speakerEnabled", speakerEnabled)
                    put("callAnswered", false)
                    put("answerWaitMs", answerResult.waitedMs)
                    put("spoken", false)
                    put("hungUpOnTimeout", hungUpOnTimeout)
                    put("reason", answerResult.reason)
                }
            }

            // Ensure speakerphone is enabled after call is connected (some ROMs only allow toggling after answer)
            if (enableSpeaker && !speakerEnabled) {
                speakerEnabled = ensureSpeakerphoneEnabled(steps, callPackages, ::isInCall)
            }

            if (waitForAnswerOnly && sayText.isEmpty() && audioBase64 == null && aiCallText.isEmpty()) {
                return@withGuardedPhoneAction JSONObject().apply {
                    put("ok", true)
                    put("action", "call_phone")
                    put("number", cleanNumber)
                    put("simUsed", chosenSimLabel)
                    put("callRequested", true)
                    put("callPlaced", true)
                    put("speakerEnabled", speakerEnabled)
                    put("callAnswered", true)
                    put("answerWaitMs", answerResult.waitedMs)
                    if (answerResult.matchedTimer != null) {
                        put("matchedCallTimer", answerResult.matchedTimer)
                    }
                }
            }

            steps.pause(postAnswerDelayMs)
            if (isInCall() != true) {
                return@withGuardedPhoneAction JSONObject().apply {
                    put("ok", false)
                    put("action", "call_phone")
                    put("number", cleanNumber)
                    put("simUsed", chosenSimLabel)
                    put("callRequested", true)
                    put("callPlaced", true)
                    put("speakerEnabled", speakerEnabled)
                    put("callAnswered", true)
                    put("answerWaitMs", answerResult.waitedMs)
                    put("spoken", false)
                    put("reason", "call_ended_before_speech")
                }
            }

            val spokenOk = if (aiCallText.isNotEmpty()) {
                performXiaomiAiCallSpeech(steps, callPackages, aiCallText, ::isInCall)
            } else {
                NotificationExecutor.speakTextAndAwait(
                    context = this@OmsAccessibilityService,
                    rawText = sayText,
                    useAlarmStream = useAlarmStream,
                    speechRate = 1.0f,
                    audioBase64 = audioBase64,
                    repeatCount = repeatCount,
                    maxWaitMs = 45_000L,
                    ignoreHardwareStop = true,
                    shouldContinue = { !guard.stopped && sessionStillValid() && isInCall() == true }
                )
            }

            var hungUpAfter = false
            var hangupReason = "not_requested"
            if (hangupAfter) {
                if (spokenOk && isInCall() == true) {
                    steps.pause(600L)
                }
                if (isInCall() == true) {
                    val hangupRes = executeVerifiedHangup(
                        trustedPackages = callPackages,
                        isInCall = ::isInCall,
                        checkAuth = { steps.check() },
                        pause = { steps.pause(it) }
                    )
                    hungUpAfter = hangupRes.hungUp
                    hangupReason = hangupRes.reason
                } else {
                    hungUpAfter = true
                    hangupReason = "remote_already_ended"
                }
            }

            JSONObject().apply {
                put("ok", spokenOk)
                put("action", "call_phone")
                put("number", cleanNumber)
                put("simUsed", chosenSimLabel)
                put("callRequested", true)
                put("callPlaced", true)
                put("speakerEnabled", speakerEnabled)
                put("callAnswered", true)
                put("answerWaitMs", answerResult.waitedMs)
                put("matchedCallTimer", answerResult.matchedTimer)
                put("spoken", spokenOk)
                put("spokenText", if (aiCallText.isNotEmpty()) aiCallText else sayText)
                put("ttsEngine", when {
                    aiCallText.isNotEmpty() -> "xiaomi_ai_call"
                    audioBase64 != null -> "edge_neural"
                    else -> "android_local"
                })
                put("repeatCount", repeatCount)
                put("hangupAfterRequested", hangupAfter)
                put("hungUpAfter", hungUpAfter)
                put("hangupReason", hangupReason)
                if (!spokenOk) {
                    put("reason", if (isInCall() == false) "call_ended_during_speech" else "speech_interrupted_or_failed")
                }
            }
        }
    }

    private suspend fun performXiaomiAiCallSpeech(
        steps: GuardedRemoteAction.Steps,
        callPackages: Set<String>,
        textToSend: String,
        isInCall: () -> Boolean?
    ): Boolean {
        val aiAllowedPkgs = callPackages + setOf(
            "com.xiaomi.aiasst.service",
            "com.xiaomi.aiasst.vision",
            "com.miui.voiceassist"
        )
        val aiButton = findClickableCallControl(
            roots = collectAllWindowRoots(),
            allowedPackages = aiAllowedPkgs,
            predicate = { lbl, vid ->
                lbl.contains("AI通话", ignoreCase = true) ||
                    lbl.contains("AI代接", ignoreCase = true) ||
                    vid.lowercase().contains("ai_call") ||
                    vid.lowercase().contains("aicall")
            }
        )
        if (aiButton != null) {
            steps.effect {
                if (isInCall() == true && aiButton.refresh() && aiButton.isVisibleToUser && aiButton.isClickable) {
                    aiButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            steps.pause(600L)
        }

        var editableNode: AccessibilityNodeInfo? = null
        var clickedKeyboardToggle = false
        for (attempt in 0 until 14) {
            if (isInCall() != true) return false
            val roots = collectAllWindowRoots()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            roots.forEach { queue.addLast(it) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (n.isVisibleToUser && n.isEditable && n.isEnabled) {
                    editableNode = n
                    break
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
            }
            if (editableNode != null) break
            if (!clickedKeyboardToggle) {
                val kbBtn = findClickableCallControl(
                    roots = roots,
                    allowedPackages = aiAllowedPkgs,
                    predicate = { lbl, vid ->
                        val t = lbl.trim()
                        t in setOf("打字", "键盘", "输入", "文字输入", "手动输入") ||
                            vid.lowercase().contains("keyboard") ||
                            vid.lowercase().contains("input_switch")
                    }
                )
                if (kbBtn != null) {
                    clickedKeyboardToggle = true
                    steps.effect {
                        if (kbBtn.refresh() && kbBtn.isClickable) {
                            kbBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                }
            }
            steps.pause(250L)
        }

        val targetEdit = editableNode ?: return false
        val textFilled = steps.effect {
            if (isInCall() != true || !targetEdit.refresh() || !targetEdit.isEditable) {
                false
            } else {
                targetEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSend)
                }
                targetEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        if (!textFilled) return false
        steps.pause(250L)

        val sendBtn = findClickableCallControl(
            roots = collectAllWindowRoots(),
            allowedPackages = aiAllowedPkgs,
            predicate = { lbl, vid ->
                val t = lbl.trim()
                t in setOf("发送", "播报", "朗读", "确定", "Send") ||
                    vid.lowercase().contains("send")
            }
        ) ?: return false

        return steps.effect {
            isInCall() == true && sendBtn.refresh() && sendBtn.isVisibleToUser &&
                sendBtn.isEnabled && sendBtn.isClickable &&
                sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    suspend fun performHangupCall(sessionStillValid: () -> Boolean): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            val deadline = System.currentTimeMillis() + 5000L
            val queuedRevision = guard.revision
            withTimeout(5000L) { guiMutex.withLock {
                guard.check(System.currentTimeMillis(), deadline, windowIdentity(), queuedRevision,
                    null, false, sessionStillValid())
                activeAction = currentCoroutineContext()[Job]
                safetyOverlay?.showActivePill()
                try {
                    val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val inCallUiPkgs = setOf("com.android.incallui", "com.miui.incallui")
                    fun isInCall(): Boolean? {
                        try {
                            val tc = telecom?.isInCall
                            if (tc != null) return tc
                        } catch (_: SecurityException) {
                        }
                        val mode = audio?.mode
                        if (mode == android.media.AudioManager.MODE_IN_CALL ||
                            mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                            mode == android.media.AudioManager.MODE_RINGTONE
                        ) {
                            return true
                        }
                        return collectAllWindowRoots().any { it.packageName?.toString() in inCallUiPkgs }
                    }
                    val dialerPackage = try { telecom?.defaultDialerPackage } catch (_: SecurityException) { null }
                    val trustedPackages = setOfNotNull(dialerPackage, "com.android.incallui", "com.miui.incallui")
                    val result = executeVerifiedHangup(
                        trustedPackages = trustedPackages,
                        isInCall = ::isInCall,
                        checkAuth = {
                            guard.checkAuthorization(System.currentTimeMillis(), deadline, sessionStillValid())
                        },
                        pause = { delay(it) }
                    )
                    JSONObject().apply {
                        put("ok", result.hungUp)
                        put("action", "hangup_call")
                        put("hungUp", result.hungUp)
                        put("hangupRequested", result.requested)
                        put("reason", result.reason)
                    }
                } finally {
                    activeAction = null
                    if (!guard.stopped) safetyOverlay?.hideActivePill()
                }
            } }
        }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val activeFocus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (activeFocus != null && activeFocus.isEditable) return activeFocus

        val roots = collectAllWindowRoots()
        for (r in roots) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.addLast(it) }
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isVisibleToUser) {
                if (n.isFocused) return n
                if (firstEditable == null) firstEditable = n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return firstEditable
    }

    /**
     * Direct, low-latency human input injection for Mac Live Screen Mirroring (L1/L2).
     * Skips the semantic settle delay, but shares GUI serialization, session revocation
     * and the phone's durable emergency-stop latch with automated input.
     */
    suspend fun executeMirrorInput(
        params: JSONObject,
        sessionStillValid: () -> Boolean
    ): JSONObject = withContext(Dispatchers.Main.immediate) {
        val now = System.currentTimeMillis()
        val deadline = minOf(now + 5000L, params.optLong("deadlineMs", now + 5000L))
        val queuedRevision = guard.revision
        guard.checkAuthorization(now, deadline, sessionStillValid())
        withTimeout((deadline - now).coerceAtLeast(1L)) {
            guiMutex.withLock {
                guard.check(System.currentTimeMillis(), deadline, windowIdentity(), queuedRevision,
                    null, false, sessionStillValid())
                activeAction = currentCoroutineContext()[Job]
                safetyOverlay?.showActivePill()
                try {
                    val result = executeMirrorInputLocked(params)
                    currentCoroutineContext().ensureActive()
                    guard.checkAuthorization(System.currentTimeMillis(), deadline, sessionStillValid())
                    result
                } finally {
                    activeAction = null
                    if (!guard.stopped) safetyOverlay?.hideActivePill()
                }
            }
        }
    }

    private suspend fun executeMirrorInputLocked(params: JSONObject): JSONObject {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val type = params.optString("type", params.optString("action", "tap")).lowercase()

        fun resolveCoord(normKey: String, pxKey: String, maxDim: Int, defaultPx: Int): Int {
            if (params.has(normKey)) {
                val norm = params.optDouble(normKey, 0.5).coerceIn(0.0, 1.0)
                return (norm * maxDim).toInt().coerceIn(1, maxDim - 1)
            }
            if (params.has(pxKey)) {
                return params.optInt(pxKey, defaultPx).coerceIn(1, maxDim - 1)
            }
            return defaultPx.coerceIn(1, maxDim - 1)
        }

        var ok = false
        val typeLower = type.lowercase()
        val isSafeMirrorNav = typeLower in setOf("key", "nav") &&
            params.optString("key", params.optString("target", "home")).lowercase() in setOf("home", "back", "lock", "lock_screen")
        if (!isSafeMirrorNav && isBlockedFinancialOrPasswordWindow(rootInActiveWindow, collectApplicationRoots())) {
            throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Payment/password safety policy")
        }
        when (type) {
            "tap", "click" -> {
                val tx = resolveCoord("normX", "x", screenW, screenW / 2)
                val ty = resolveCoord("normY", "y", screenH, screenH / 2)
                val dur = params.optLong("durationMs", 55L).coerceIn(30L, 250L)
                ok = injectTapGesture(tx, ty, dur)
            }
            "long_press", "long_click" -> {
                val tx = resolveCoord("normX", "x", screenW, screenW / 2)
                val ty = resolveCoord("normY", "y", screenH, screenH / 2)
                val dur = params.optLong("durationMs", 620L).coerceIn(400L, 2500L)
                ok = injectTapGesture(tx, ty, dur)
            }
            "swipe", "drag" -> {
                val x1 = resolveCoord("normX1", "x1", screenW, resolveCoord("normX", "x", screenW, screenW / 2))
                val y1 = resolveCoord("normY1", "y1", screenH, resolveCoord("normY", "y", screenH, (screenH * 0.7).toInt()))
                val x2 = resolveCoord("normX2", "x2", screenW, screenW / 2)
                val y2 = resolveCoord("normY2", "y2", screenH, (screenH * 0.3).toInt())
                val dur = params.optLong("durationMs", 220L).coerceIn(60L, 1500L)
                val path = Path().apply {
                    moveTo(x1.toFloat(), y1.toFloat())
                    lineTo(x2.toFloat(), y2.toFloat())
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                ok = dispatchAndAwait(gesture)
            }
            "scroll", "wheel" -> {
                val cx = resolveCoord("normX", "x", screenW, screenW / 2)
                val cy = resolveCoord("normY", "y", screenH, screenH / 2)
                val dy = params.optDouble("deltaY", 120.0)
                val dx = params.optDouble("deltaX", 0.0)
                // Positive wheel deltaY on Mac means scrolling down content (finger swipes up on phone)
                val spanY = (-dy * 2.4).toInt().coerceIn(-(screenH * 0.42).toInt(), (screenH * 0.42).toInt())
                val spanX = (-dx * 2.0).toInt().coerceIn(-(screenW * 0.42).toInt(), (screenW * 0.42).toInt())
                val endX = (cx + spanX).coerceIn(24, screenW - 24)
                val endY = (cy + spanY).coerceIn(48, screenH - 48)
                if (abs(endX - cx) > 8 || abs(endY - cy) > 8) {
                    val path = Path().apply {
                        moveTo(cx.toFloat(), cy.toFloat())
                        lineTo(endX.toFloat(), endY.toFloat())
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, 165L)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    ok = dispatchAndAwait(gesture)
                }
            }
            "text", "input_text" -> {
                val text = params.optString("text", "")
                val append = params.optBoolean("append", false)
                val submit = params.optBoolean("submit", false)
                val node = findFocusedEditableNode()
                if (node?.isPassword == true) {
                    throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Password field input is blocked")
                }
                if (node != null) {
                    val existing = if (append && !node.isShowingHintText) {
                        node.text?.toString() ?: ""
                    } else {
                        ""
                    }
                    val finalStr = existing + text
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            finalStr
                        )
                    }
                    ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (submit && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                }
            }
            "backspace" -> {
                val node = findFocusedEditableNode()
                if (node?.isPassword == true) {
                    throw SecurityException("ERR_SECURE_WINDOW_BLOCKED: Password field input is blocked")
                }
                if (node != null && !node.isShowingHintText) {
                    val cur = node.text?.toString() ?: ""
                    if (cur.isNotEmpty()) {
                        val next = cur.dropLast(1)
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                next
                            )
                        }
                        ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
            }
            "enter" -> {
                val node = findFocusedEditableNode()
                if (node != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }
            "key", "nav" -> {
                val key = params.optString("key", params.optString("target", "home")).lowercase()
                ok = when (key) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                    "lock", "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    "wake" -> {
                        AppLauncherHelper.wakeScreenIfNeeded(this@OmsAccessibilityService)
                        true
                    }
                    else -> false
                }
            }
        }

        onMirrorUiEvent?.invoke()
        return JSONObject().apply {
            put("ok", ok)
            put("type", type)
            put("screenWidth", screenW)
            put("screenHeight", screenH)
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
                    if (activeAction != null) {
                        setStopped("gesture_cancelled_by_touch")
                    }
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

}
