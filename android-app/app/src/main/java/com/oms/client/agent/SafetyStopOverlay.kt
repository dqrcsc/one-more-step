package com.oms.client.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Phone-local stop/resume and fail-closed sensitive-action confirmation. */
class SafetyStopOverlay(
    private val context: Context,
    private val onEmergencyStop: () -> Unit,
    private val onLocalResume: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pillView: TextView? = null
    private var pillStoppedState: Boolean? = null
    private var confirmBar: LinearLayout? = null
    private var pendingConfirmation: CancellableContinuation<Boolean>? = null
    private val deferredHidePillRunnable: Runnable = Runnable { hideActivePill() }

    val isAwaitingConfirmation: Boolean get() = pendingConfirmation?.isActive == true

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun remove(view: View?) {
        if (view != null) try { windowManager.removeView(view) } catch (_: Exception) { }
    }

    fun showActivePill(): Boolean = showPill(false)
    fun showStoppedPill(): Boolean = showPill(true)

    private fun showPill(stopped: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        mainHandler.removeCallbacks(deferredHidePillRunnable)
        if (!Settings.canDrawOverlays(context)) {
            remove(pillView)
            pillView = null
            pillStoppedState = null
            return false
        }
        val existing = pillView
        if (existing != null && pillStoppedState == stopped && existing.isAttachedToWindow) {
            return true
        }
        remove(existing)
        pillView = null
        pillStoppedState = null

        val tv = TextView(context).apply {
            text = if (stopped) "⏸ AI 已急停 · 点击恢复后请重新观察" else "🤖 Mac AI 操作中 · 点击立即停止"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(36, 16, 36, 16)
            filterTouchesWhenObscured = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (stopped) "#E6334155" else "#E6FF5722"))
                cornerRadius = 48f
            }
            setOnClickListener { if (stopped) onLocalResume() else onEmergencyStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 24 }
        return try {
            windowManager.addView(tv, params)
            pillView = tv
            pillStoppedState = stopped
            true
        } catch (_: Exception) {
            false
        }
    }

    fun scheduleHideActivePill(delayMs: Long = 2_200L): Unit = onMain {
        if (pillStoppedState == true) return@onMain
        mainHandler.removeCallbacks(deferredHidePillRunnable)
        mainHandler.postDelayed(deferredHidePillRunnable, delayMs.coerceAtLeast(100L))
    }

    fun hideActivePill(): Unit = onMain {
        mainHandler.removeCallbacks(deferredHidePillRunnable)
        remove(pillView)
        pillView = null
        pillStoppedState = null
    }

    fun cancelConfirmation() = onMain {
        val continuation = pendingConfirmation
        pendingConfirmation = null
        remove(confirmBar)
        confirmBar = null
        continuation?.cancel()
    }

    suspend fun awaitUserConfirmForSensitiveAction(actionDesc: String): Boolean =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                onMain {
                    if (pendingConfirmation === cont) {
                        pendingConfirmation = null
                        remove(confirmBar)
                        confirmBar = null
                    }
                }
            }
            onMain {
                if (!cont.isActive) return@onMain
                if (!Settings.canDrawOverlays(context)) {
                    cont.resume(false)
                    return@onMain
                }
                cancelConfirmation()
                pendingConfirmation = cont
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(36, 24, 36, 24)
                    filterTouchesWhenObscured = true
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F21E293B")); cornerRadius = 36f
                    }
                }
                fun finish(approved: Boolean) {
                    if (pendingConfirmation !== cont) return
                    pendingConfirmation = null
                    remove(container)
                    confirmBar = null
                    if (cont.isActive) cont.resume(approved)
                }
                container.addView(TextView(context).apply {
                    text = "⚠️ AI 待确认：$actionDesc  "
                    setTextColor(Color.WHITE); textSize = 13f
                })
                container.addView(TextView(context).apply {
                    text = " [✅ 放行] "; setTextColor(Color.parseColor("#4ADE80")); textSize = 14f
                    filterTouchesWhenObscured = true
                    setOnClickListener { finish(true) }
                })
                container.addView(TextView(context).apply {
                    text = " [❌ 拦截] "; setTextColor(Color.parseColor("#F87171")); textSize = 14f
                    filterTouchesWhenObscured = true
                    setOnClickListener { finish(false) }
                })
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 140 }
                try {
                    if (!cont.isActive) { finish(false); return@onMain }
                    windowManager.addView(container, params)
                    confirmBar = container
                } catch (_: Exception) { finish(false) }
            }
        }
}
