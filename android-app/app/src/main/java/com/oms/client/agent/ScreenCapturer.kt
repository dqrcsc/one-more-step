package com.oms.client.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Module 05: Silent Screenshot Capturer (Android 11+ API 30+) & Set-of-Mark (SoM) Visual Annotator
 */
object ScreenCapturer {

    suspend fun captureWebpBytes(
        service: AccessibilityService,
        nodes: List<ExtractedSemanticNode>,
        drawSomAnnotation: Boolean,
        compactForBle: Boolean = false
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hwBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        hwBuffer.close()

                        if (hwBitmap == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }

                        val mutableBmp = hwBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        hwBitmap.recycle()

                        if (drawSomAnnotation && nodes.isNotEmpty()) {
                            drawSetOfMarkBadges(mutableBmp, nodes)
                        }

                        val targetBmp = if (compactForBle && mutableBmp.width > 540) {
                            val ratio = 540f / mutableBmp.width.toFloat()
                            val targetW = 540
                            val targetH = (mutableBmp.height * ratio).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(mutableBmp, targetW, targetH, true)
                            if (scaled !== mutableBmp) {
                                mutableBmp.recycle()
                            }
                            scaled
                        } else {
                            mutableBmp
                        }

                        val out = ByteArrayOutputStream()
                        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                        val quality = if (compactForBle) 62 else 80
                        targetBmp.compress(format, quality, out)
                        targetBmp.recycle()

                        if (cont.isActive) cont.resume(out.toByteArray())
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        cont.resumeWithException(SecurityException("ERR_SECURE_WINDOW_BLOCKED: Android rejected screenshot of a secure window"))
                    } else {
                        cont.resumeWithException(IllegalStateException("ERR_SCREENSHOT_FAILED: Android error $errorCode"))
                    }
                }
            }
        )
    }

    /**
     * Draw bounding boxes and high-contrast #ID badges onto the screenshot for VLM precision
     */
    private fun drawSetOfMarkBadges(bitmap: Bitmap, nodes: List<ExtractedSemanticNode>) {
        val canvas = Canvas(bitmap)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#FF3B30")
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#E6FF3B30")
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            isFakeBoldText = true
        }

        for (node in nodes) {
            if (!node.clickable && !node.editable && !node.checkable) continue
            val b = node.bounds
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                strokePaint
            )

            val tagText = node.id
            val textWidth = textPaint.measureText(tagText)
            val badgeLeft = b.left.toFloat().coerceAtLeast(4f)
            val badgeTop = (b.top.toFloat() - 34f).coerceAtLeast(4f)
            val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + textWidth + 16f, badgeTop + 34f)
            canvas.drawRoundRect(badgeRect, 8f, 8f, bgPaint)
            canvas.drawText(tagText, badgeLeft + 8f, badgeTop + 26f, textPaint)
        }
    }
}
