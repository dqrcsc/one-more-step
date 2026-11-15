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
 * Module 03: Screenshot & Camera Photo Instant-Send Floating Pill (`ScreenshotPillController`)
 * Watches MediaStore for newly captured screenshots (and optionally new camera photos)
 * and pops a 4.0s non-focusable quick-send capsule.
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
            checkLatestMediaImage()
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

    companion object {
        internal fun classifyNewMediaCapture(
            fileName: String,
            relativePath: String,
            cameraPillEnabled: Boolean
        ): Pair<Boolean, Boolean> {
            val isScreenshot = relativePath.contains("Screenshots", ignoreCase = true) ||
                fileName.contains("Screenshot", ignoreCase = true)
            if (isScreenshot) return true to true
            if (!cameraPillEnabled) return false to false
            val isCameraPhoto = relativePath.contains("DCIM/Camera", ignoreCase = true) ||
                relativePath.contains("Pictures/Camera", ignoreCase = true)
            return isCameraPhoto to false
        }
    }

    private fun checkLatestMediaImage() {
        val nowSec = System.currentTimeMillis() / 1000L
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_PENDING
        )
        val cameraEnabled = OmsApplication.instance?.repository?.isCameraPillEnabled() ?: false

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

                if (isPending == 0 && id != lastHandledId && (nowSec - dateAdded) <= 5L) {
                    val (shouldShow, isScreenshot) = classifyNewMediaCapture(name, relPath, cameraEnabled)
                    if (shouldShow) {
                        lastHandledId = id
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        showFloatingPill(contentUri, name, isScreenshot)
                    }
                }
            }
        }
    }

    private fun showFloatingPill(imageUri: Uri, fileName: String, isScreenshot: Boolean) {
        if (!Settings.canDrawOverlays(context)) return
        dismissPill()

        val defaultTag = if (isScreenshot) "ui_bug" else "default_inbox"
        val titlePrefix = if (isScreenshot) "手机截屏" else "手机拍照"

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 18, 28, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0F172A"))
                cornerRadius = 64f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            filterTouchesWhenObscured = true
        }

        val sendBtn = TextView(context).apply {
            text = if (isScreenshot) "🚀 截屏·发Mac" else "📷 新拍图·发Mac"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(10, 6, 16, 6)
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                    intentTag = defaultTag,
                    itemType = "MEDIA",
                    title = "$titlePrefix：$fileName",
                    textContent = null,
                    voiceTranscript = null,
                    fileUri = imageUri
                )
                text = "正在保存，查看首页状态"
                mainHandler.postDelayed({ dismissPill() }, 700L)
            }
        }

        val pkbBtn = TextView(context).apply {
            text = "📚存库"
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 12.5f
            setPadding(12, 6, 12, 6)
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                    intentTag = "pkb_vault",
                    itemType = "MEDIA",
                    title = "$titlePrefix：$fileName",
                    textContent = null,
                    voiceTranscript = null,
                    fileUri = imageUri
                )
                sendBtn.text = "正在保存，查看首页状态"
                mainHandler.postDelayed({ dismissPill() }, 700L)
            }
        }

        val voiceBtn = TextView(context).apply {
            text = "🎙️附言"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12.5f
            setPadding(12, 6, 10, 6)
            setOnClickListener {
                dismissPill()
                val intent = Intent(context, VoiceIdeaActivity::class.java).apply {
                    putExtra("screenshot_uri", imageUri.toString())
                    putExtra("intent_tag", defaultTag)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        pill.addView(sendBtn)
        pill.addView(pkbBtn)
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
            mainHandler.postDelayed(autoDismissRunnable, 4200L)
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
