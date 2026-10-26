package com.oms.client.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Module 05: Hardware-Level Safety Overlay Pill ("🤖 AI 操作中 · 点击急停")
 * & Sensitive Action User Confirmation Gate
 */
class SafetyStopOverlay(private val context: Context, private val onEmergencyStop: () -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pillView: TextView? = null
    private var confirmBar: LinearLayout? = null

    fun showActivePill() {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (pillView != null) return@post
            val tv = TextView(context).apply {
                text = "🤖 Mac AI 操作中 · 点击立即停止"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(36, 16, 36, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6FF5722"))
                    cornerRadius = 48f
                }
                setOnClickListener {
                    hideActivePill()
                    onEmergencyStop()
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 24
            }
            try {
                windowManager.addView(tv, params)
                pillView = tv
            } catch (_: Throwable) {
            }
        }
    }

    fun hideActivePill() {
        mainHandler.post {
            pillView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Throwable) {
                }
            }
            pillView = null
        }
    }

    /**
     * Guardrail #4: Pause before clicking sensitive actions ("提交", "发送", "删除", etc.)
     * and wait for user's one-tap confirmation on the phone screen.
     */
    suspend fun awaitUserConfirmForSensitiveAction(actionDesc: String): Boolean =
        suspendCancellableCoroutine { cont ->
            if (!Settings.canDrawOverlays(context)) {
                cont.resume(true)
                return@suspendCancellableCoroutine
            }
            mainHandler.post {
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(36, 24, 36, 24)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F21E293B"))
                        cornerRadius = 36f
                    }
                }

                val labelTv = TextView(context).apply {
                    text = "⚠️ AI 待确认：$actionDesc  "
                    setTextColor(Color.WHITE)
                    textSize = 13f
                }

                val approveBtn = TextView(context).apply {
                    text = " [✅ 放行] "
                    setTextColor(Color.parseColor("#4ADE80"))
                    textSize = 14f
                    setOnClickListener {
                        dismissConfirmBar()
                        if (cont.isActive) cont.resume(true)
                    }
                }

                val cancelBtn = TextView(context).apply {
                    text = " [❌ 拦截] "
                    setTextColor(Color.parseColor("#F87171"))
                    textSize = 14f
                    setOnClickListener {
                        dismissConfirmBar()
                        if (cont.isActive) cont.resume(false)
                    }
                }

                container.addView(labelTv)
                container.addView(approveBtn)
                container.addView(cancelBtn)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 140
                }

                try {
                    windowManager.addView(container, params)
                    confirmBar = container
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    private fun dismissConfirmBar() {
        mainHandler.post {
            confirmBar?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Throwable) {
                }
            }
            confirmBar = null
        }
    }
}
