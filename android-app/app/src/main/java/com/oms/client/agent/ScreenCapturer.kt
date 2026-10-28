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

    data class MirrorFrameResult(
        val bytes: ByteArray,
        val screenWidth: Int,
        val screenHeight: Int,
        val frameHash: Long,
        val isSecureWindow: Boolean = false,
        val unchanged: Boolean = false
    )

    private fun computeFastGridHash(bmp: Bitmap): Long {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0L
        var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
        val stepX = (w / 9).coerceAtLeast(1)
        val stepY = (h / 9).coerceAtLeast(1)
        for (iy in 1..8) {
            val py = (iy * stepY).coerceAtMost(h - 1)
            for (ix in 1..8) {
                val px = (ix * stepX).coerceAtMost(w - 1)
                val pixel = bmp.getPixel(px, py)
                // Quantize slightly (drop lowest 2 bits per channel) to ignore minor GPU dithering noise
                val quantized = pixel and 0xFCFCFC
                hash = (hash xor quantized.toLong()) * 1099511628211L
            }
        }
        return if (hash == 0L) 1L else hash
    }

    /**
     * Fast, zero-disk-IO screen frame capture for real-time Mac screen mirroring (L1 Silent Stream).
     * Computes an 8x8 perceptual grid hash on the scaled bitmap and skips WebP encoding if unchanged.
     */
    suspend fun captureMirrorFrame(
        service: AccessibilityService,
        maxWidth: Int = 640,
        quality: Int = 72,
        previousHash: Long = 0L,
        forceEncode: Boolean = false
    ): MirrorFrameResult? = suspendCancellableCoroutine { cont ->
        val dm = service.resources.displayMetrics
        val fallbackW = dm.widthPixels.coerceAtLeast(1)
        val fallbackH = dm.heightPixels.coerceAtLeast(1)

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

                        val origW = hwBitmap.width.coerceAtLeast(1)
                        val origH = hwBitmap.height.coerceAtLeast(1)
                        val swBmp = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()

                        if (swBmp == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }

                        val clampedMaxW = maxWidth.coerceIn(360, 1280)
                        val targetBmp = if (origW > clampedMaxW) {
                            val ratio = clampedMaxW.toFloat() / origW.toFloat()
                            val targetH = (origH * ratio).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(swBmp, clampedMaxW, targetH, true)
                            if (scaled !== swBmp) {
                                swBmp.recycle()
                            }
                            scaled
                        } else {
                            swBmp
                        }

                        val hash = computeFastGridHash(targetBmp)
                        if (!forceEncode && previousHash != 0L && hash == previousHash) {
                            targetBmp.recycle()
                            if (cont.isActive) {
                                cont.resume(
                                    MirrorFrameResult(
                                        bytes = ByteArray(0),
                                        screenWidth = origW,
                                        screenHeight = origH,
                                        frameHash = hash,
                                        isSecureWindow = false,
                                        unchanged = true
                                    )
                                )
                            }
                            return
                        }

                        val out = ByteArrayOutputStream(32768)
                        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                        targetBmp.compress(format, quality.coerceIn(40, 92), out)
                        targetBmp.recycle()

                        if (cont.isActive) {
                            cont.resume(
                                MirrorFrameResult(
                                    bytes = out.toByteArray(),
                                    screenWidth = origW,
                                    screenHeight = origH,
                                    frameHash = hash,
                                    isSecureWindow = false,
                                    unchanged = false
                                )
                            )
                        }
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        cont.resume(
                            MirrorFrameResult(
                                bytes = ByteArray(0),
                                screenWidth = fallbackW,
                                screenHeight = fallbackH,
                                frameHash = -1L,
                                isSecureWindow = true,
                                unchanged = false
                            )
                        )
                    } else {
                        cont.resume(null)
                    }
                }
            }
        )
    }

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
