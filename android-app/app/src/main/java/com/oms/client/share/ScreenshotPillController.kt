package com.oms.client.share

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.oms.client.OmsApplication

/**
 * Module 03: Screenshot Instant-Send Floating Pill (`ScreenshotPillController`)
 * Watches MediaStore for newly captured screenshots and pops a 3.5s non-focusable capsule.
 */
class ScreenshotPillController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lastHandledId: Long = -1L
    private var currentPill: LinearLayout? = null
    private val autoDismissRunnable = Runnable { dismissPill() }

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            checkLatestScreenshot()
        }
    }

    fun startWatching() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
    }

    fun stopWatching() {
        try {
            context.contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Throwable) {
        }
        dismissPill()
    }

    private fun checkLatestScreenshot() {
        val nowSec = System.currentTimeMillis() / 1000L
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_PENDING
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: ""
                val relPath = cursor.getString(2) ?: ""
                val dateAdded = cursor.getLong(3)
                val isPending = cursor.getInt(4)

                if (isPending == 0 &&
                    id != lastHandledId &&
                    (nowSec - dateAdded) <= 5L &&
                    (relPath.contains("Screenshots", ignoreCase = true) ||
                        name.contains("Screenshot", ignoreCase = true))
                ) {
                    lastHandledId = id
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    showFloatingPill(contentUri, name)
                }
            }
        }
    }

    private fun showFloatingPill(screenshotUri: Uri, fileName: String) {
        if (!Settings.canDrawOverlays(context)) return
        dismissPill()

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0F172A"))
                cornerRadius = 64f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
        }

        val sendBtn = TextView(context).apply {
            text = "🚀 青简·发Mac"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setPadding(12, 6, 20, 6)
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                    intentTag = "ui_bug",
                    itemType = "MEDIA",
                    title = "手机截屏：$fileName",
                    textContent = null,
                    voiceTranscript = null,
                    fileUri = screenshotUri
                )
                text = "✅ 已送达 Mac"
                mainHandler.postDelayed({ dismissPill() }, 700L)
            }
        }

        val voiceBtn = TextView(context).apply {
            text = " 🎙️附言"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13.5f
            setPadding(16, 6, 12, 6)
            setOnClickListener {
                dismissPill()
                val intent = Intent(context, VoiceIdeaActivity::class.java).apply {
                    putExtra("screenshot_uri", screenshotUri.toString())
                    putExtra("intent_tag", "ui_bug")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        pill.addView(sendBtn)
        pill.addView(voiceBtn)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        try {
            windowManager.addView(pill, lp)
            currentPill = pill
            mainHandler.postDelayed(autoDismissRunnable, 3500L)
        } catch (_: Throwable) {
        }
    }

    private fun dismissPill() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        currentPill?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        currentPill = null
    }
}
