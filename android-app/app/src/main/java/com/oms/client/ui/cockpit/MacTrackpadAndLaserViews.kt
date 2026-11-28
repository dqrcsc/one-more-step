package com.oms.client.ui.cockpit

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.cockpit.AirWandGyroIntegrator
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.MacHidButton
import com.oms.client.cockpit.MacHidButtonAction
import com.oms.client.cockpit.MacHidLaserMode
import com.oms.client.cockpit.MacHidModMask
import com.oms.client.cockpit.MacHidPacket
import com.oms.client.cockpit.MacHidScrollPhase
import com.oms.client.cockpit.MacKeyCodes
import com.oms.client.cockpit.TrackpadPhysicsEngine
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsSegmentItem
import com.oms.client.ui.theme.OmsSlidingSegmentedBar
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.omsBounceClick
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 120Hz Multi-Touch Magic Trackpad View (`单指微操拖拽 · 双指惯性滚轮与缩放 · 三指调度中心与桌面空间`).
 */
@Composable
fun MacTrackpadModeView(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    onJumpToKeyboardVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val densityObj = LocalDensity.current
    val scope = rememberCoroutineScope()

    var sensitivity by remember { mutableFloatStateOf(1.0f) }
    val naturalScroll = true
    var isDragLockActive by remember { mutableStateOf(false) }
    var liveGestureBadge by remember { mutableStateOf<String?>(null) }
    var touchPointVisual by remember { mutableStateOf<Offset?>(null) }
    var activeFingerCount by remember { mutableIntStateOf(0) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val currentMacConnected by rememberUpdatedState(macConnected)
    val currentSensitivity by rememberUpdatedState(sensitivity)
    val currentDragLock by rememberUpdatedState(isDragLockActive)

    DisposableEffect(Unit) {
        onDispose {
            flingJob?.cancel()
            coordinator.trackpadMomentumScroller.stopImmediate()
            isDragLockActive = false
        }
    }

    LaunchedEffect(macConnected) {
        if (!macConnected) {
            flingJob?.cancel()
            coordinator.trackpadMomentumScroller.stopImmediate()
            isDragLockActive = false
            liveGestureBadge = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Full-Height Tactile Matte-Glass Trackpad Canvas
        val padShape = RoundedCornerShape(22.dp)
        val borderHighlight = when {
            isDragLockActive -> palette.accent.copy(alpha = 0.72f)
            activeFingerCount >= 2 -> palette.accent.copy(alpha = 0.48f)
            activeFingerCount == 1 -> palette.strongBorder
            else -> palette.hairline
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(padShape)
                .background(palette.cardSurface, padShape)
                .border(0.85.dp, borderHighlight, padShape)
                .pointerInput(Unit) {
                    val densityPx = densityObj.density.coerceAtLeast(1f)
                    var lastSingleTapUpMs = 0L
                    var lastSingleTapPos = Offset.Zero

                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        firstDown.consume()

                        // Instant brake on any active inertial scroll fling
                        flingJob?.cancel()
                        coordinator.trackpadMomentumScroller.stopImmediate()
                        if (liveGestureBadge == "双指惯性滚动" || liveGestureBadge == "双指右键") {
                            liveGestureBadge = if (currentDragLock) "拖拽锁定中" else null
                        }

                        val gestureStartMs = SystemClock.uptimeMillis()
                        var lastEventMs = gestureStartMs
                        var maxPointerCount = 1
                        val firstDownPos = firstDown.position
                        var maxSingleTravelPx = 0f
                        var maxTwoFingerTravelPx = 0f

                        val dtSincePrevTap = gestureStartMs - lastSingleTapUpMs
                        val distFromPrevTapDp = (firstDownPos - lastSingleTapPos).getDistance() / densityPx
                        val isDoubleTapCandidate = !currentDragLock &&
                            dtSincePrevTap in 1L..270L &&
                            distFromPrevTapDp <= 18f
                        var isDoubleTapDragging = false

                        var prevTwoCentroid: Offset? = null
                        var pinchBaselineDistPx = 0f
                        val twoFingerStarts = HashMap<PointerId, Offset>(4)

                        var threeFingerStartCentroid: Offset? = null
                        var threeFingerTriggered = false

                        touchPointVisual = firstDownPos
                        activeFingerCount = 1

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                event.changes.forEach { it.consume() }
                                if (pressed.isEmpty()) break

                                val nowMs = SystemClock.uptimeMillis()
                                val dtMs = (nowMs - lastEventMs).coerceIn(2L, 80L)
                                lastEventMs = nowMs

                                val pointerCount = pressed.size
                                activeFingerCount = pointerCount
                                if (pointerCount > maxPointerCount) {
                                    maxPointerCount = pointerCount
                                }

                                if (pointerCount >= 3) {
                                    if (isDoubleTapDragging) {
                                        isDoubleTapDragging = false
                                        if (currentMacConnected) {
                                            coordinator.sendMacHid(
                                                MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.UP),
                                                flushImmediate = true
                                            )
                                        }
                                    }
                                    val cx = pressed.map { it.position.x }.average().toFloat()
                                    val cy = pressed.map { it.position.y }.average().toFloat()
                                    val centroid = Offset(cx, cy)
                                    touchPointVisual = centroid

                                    if (threeFingerStartCentroid == null) {
                                        threeFingerStartCentroid = centroid
                                        liveGestureBadge = "三指桌面手势"
                                    } else if (!threeFingerTriggered) {
                                        val disp = centroid - threeFingerStartCentroid
                                        val dxDp = disp.x / densityPx
                                        val dyDp = disp.y / densityPx
                                        val travelDp = hypot(dxDp, dyDp)
                                        if (travelDp > 40f) {
                                            threeFingerTriggered = true
                                            if (currentMacConnected) {
                                                val (keyCode, badgeText) = when {
                                                    dyDp < -abs(dxDp) -> MacKeyCodes.UP to "三指调度中心"
                                                    dyDp > abs(dxDp) -> MacKeyCodes.DOWN to "三指应用窗口"
                                                    dxDp < -abs(dyDp) -> MacKeyCodes.RIGHT to "三指下一桌面"
                                                    else -> MacKeyCodes.LEFT to "三指上一桌面"
                                                }
                                                liveGestureBadge = badgeText
                                                coordinator.sendMacHid(
                                                    MacHidPacket.keyTap(keyCode, MacHidModMask.CONTROL),
                                                    flushImmediate = true
                                                )
                                                coordinator.vibrateConfirm()
                                            }
                                        }
                                    }
                                } else if (pointerCount == 2 && maxPointerCount == 2) {
                                    if (isDoubleTapDragging) {
                                        isDoubleTapDragging = false
                                        if (currentMacConnected) {
                                            coordinator.sendMacHid(
                                                MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.UP),
                                                flushImmediate = true
                                            )
                                        }
                                    }
                                    val p0 = pressed[0]
                                    val p1 = pressed[1]
                                    val s0 = twoFingerStarts.getOrPut(p0.id) { p0.position }
                                    val s1 = twoFingerStarts.getOrPut(p1.id) { p1.position }
                                    maxTwoFingerTravelPx = maxOf(
                                        maxTwoFingerTravelPx,
                                        (p0.position - s0).getDistance(),
                                        (p1.position - s1).getDistance()
                                    )

                                    val centroid = Offset(
                                        (p0.position.x + p1.position.x) * 0.5f,
                                        (p0.position.y + p1.position.y) * 0.5f
                                    )
                                    touchPointVisual = centroid
                                    val distPx = (p0.position - p1.position).getDistance()

                                    if (prevTwoCentroid == null) {
                                        prevTwoCentroid = centroid
                                    }
                                    if (pinchBaselineDistPx <= 1f && distPx > 12f) {
                                        pinchBaselineDistPx = distPx
                                    }

                                    // 2-Finger Pinch Zoom (`> 1.15f` -> Cmd+, `< 0.87f` -> Cmd-)
                                    var pinchedThisFrame = false
                                    if (pinchBaselineDistPx > 14f && distPx > 14f) {
                                        val ratio = distPx / pinchBaselineDistPx
                                        if (ratio > 1.15f) {
                                            pinchedThisFrame = true
                                            pinchBaselineDistPx = distPx
                                            liveGestureBadge = "双指放大 ⌘+"
                                            if (currentMacConnected) {
                                                coordinator.sendMacHid(
                                                    MacHidPacket.keyTap(MacKeyCodes.ANSI_PLUS, MacHidModMask.COMMAND),
                                                    flushImmediate = true
                                                )
                                                coordinator.vibrateLightTick()
                                            }
                                        } else if (ratio < 0.87f) {
                                            pinchedThisFrame = true
                                            pinchBaselineDistPx = distPx
                                            liveGestureBadge = "双指缩小 ⌘-"
                                            if (currentMacConnected) {
                                                coordinator.sendMacHid(
                                                    MacHidPacket.keyTap(MacKeyCodes.ANSI_MINUS, MacHidModMask.COMMAND),
                                                    flushImmediate = true
                                                )
                                                coordinator.vibrateLightTick()
                                            }
                                        }
                                    }

                                    // 2-Finger Pixel Scroll + Momentum Velocity Tracking
                                    val panDeltaPx = centroid - prevTwoCentroid
                                    prevTwoCentroid = centroid
                                    val dxDp = panDeltaPx.x / densityPx
                                    val dyDp = panDeltaPx.y / densityPx
                                    if (!pinchedThisFrame && (maxTwoFingerTravelPx / densityPx) >= 2.2f && hypot(dxDp, dyDp) >= 0.18f) {
                                        val (scrollDxPx, scrollDyPx) = TrackpadPhysicsEngine.accelerateScrollDelta(
                                            dxDp = dxDp,
                                            dyDp = dyDp,
                                            dtMs = dtMs,
                                            sensitivity = currentSensitivity
                                        )
                                        if ((scrollDxPx != 0f || scrollDyPx != 0f) && currentMacConnected) {
                                            coordinator.sendMacHid(
                                                MacHidPacket.scrollPixel(
                                                    dxPx = scrollDxPx,
                                                    dyPx = scrollDyPx,
                                                    phase = MacHidScrollPhase.CHANGED,
                                                    naturalScroll = naturalScroll
                                                )
                                            )
                                            coordinator.trackpadMomentumScroller.recordScrollSample(scrollDxPx, scrollDyPx, dtMs)
                                            coordinator.vibrateLightTick(45L)
                                            if (liveGestureBadge != "双指放大 ⌘+" && liveGestureBadge != "双指缩小 ⌘-") {
                                                liveGestureBadge = "双指平滑滚动"
                                            }
                                        }
                                    }
                                } else if (pointerCount == 1 && maxPointerCount == 1) {
                                    val change = pressed[0]
                                    touchPointVisual = change.position
                                    val distFromDownPx = (change.position - firstDownPos).getDistance()
                                    if (distFromDownPx > maxSingleTravelPx) {
                                        maxSingleTravelPx = distFromDownPx
                                    }
                                    val travelDp = maxSingleTravelPx / densityPx

                                    if (isDoubleTapCandidate && !isDoubleTapDragging && travelDp > 5f) {
                                        isDoubleTapDragging = true
                                        lastSingleTapUpMs = 0L
                                        liveGestureBadge = "拖拽锁定中"
                                        if (currentMacConnected) {
                                            coordinator.sendMacHid(
                                                MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.DOWN),
                                                flushImmediate = true
                                            )
                                            coordinator.vibrateConfirm()
                                        }
                                    }

                                    val stepPx = change.position - change.previousPosition
                                    val dxDp = stepPx.x / densityPx
                                    val dyDp = stepPx.y / densityPx
                                    if (dxDp != 0f || dyDp != 0f) {
                                        val (dxOutPx, dyOutPx) = TrackpadPhysicsEngine.accelerateDelta(
                                            dxDp = dxDp,
                                            dyDp = dyDp,
                                            dtMs = dtMs,
                                            sensitivity = currentSensitivity
                                        )
                                        if ((dxOutPx != 0f || dyOutPx != 0f) && currentMacConnected) {
                                            coordinator.sendMacHid(MacHidPacket.moveRel(dxOutPx, dyOutPx))
                                        }
                                        if (currentDragLock) {
                                            liveGestureBadge = "拖拽锁定中"
                                        }
                                    }
                                }
                            }

                            // All fingers lifted cleanly
                            val totalDurationMs = SystemClock.uptimeMillis() - gestureStartMs
                            when {
                                isDoubleTapDragging -> {
                                    isDoubleTapDragging = false
                                    if (currentMacConnected) {
                                        coordinator.sendMacHid(
                                            MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.UP),
                                            flushImmediate = true
                                        )
                                        coordinator.vibrateLightTick()
                                    }
                                    liveGestureBadge = if (currentDragLock) "拖拽锁定中" else null
                                }

                                maxPointerCount == 1 -> {
                                    val maxTravelDp = maxSingleTravelPx / densityPx
                                    if (totalDurationMs <= 210L && maxTravelDp < 10f) {
                                        if (currentMacConnected) {
                                            if (currentDragLock) {
                                                isDragLockActive = false
                                                liveGestureBadge = null
                                                coordinator.sendMacHid(
                                                    MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.UP),
                                                    flushImmediate = true
                                                )
                                                coordinator.vibrateTick()
                                            } else if (isDoubleTapCandidate) {
                                                lastSingleTapUpMs = 0L
                                                coordinator.sendMacHid(
                                                    MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.DOUBLE_CLICK),
                                                    flushImmediate = true
                                                )
                                                coordinator.vibrateConfirm()
                                            } else {
                                                lastSingleTapUpMs = SystemClock.uptimeMillis()
                                                lastSingleTapPos = firstDownPos
                                                coordinator.sendMacHid(
                                                    MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.CLICK),
                                                    flushImmediate = true
                                                )
                                                coordinator.vibrateTick()
                                            }
                                        }
                                    } else {
                                        lastSingleTapUpMs = 0L
                                        if (!currentDragLock) liveGestureBadge = null
                                    }
                                }

                                maxPointerCount == 2 -> {
                                    lastSingleTapUpMs = 0L
                                    val maxTwoDp = maxTwoFingerTravelPx / densityPx
                                    if (totalDurationMs <= 230L && maxTwoDp < 12f) {
                                        coordinator.trackpadMomentumScroller.stopImmediate()
                                        if (currentMacConnected) {
                                            coordinator.sendMacHid(
                                                MacHidPacket.mouseButton(MacHidButton.RIGHT, MacHidButtonAction.CLICK),
                                                flushImmediate = true
                                            )
                                            coordinator.vibrateConfirm()
                                        }
                                        liveGestureBadge = if (currentDragLock) "拖拽锁定中" else null
                                    } else if (currentMacConnected && coordinator.trackpadMomentumScroller.startFromTrackedVelocity()) {
                                        liveGestureBadge = "双指惯性滚动"
                                        flingJob?.cancel()
                                        flingJob = scope.launch {
                                            try {
                                                while (coordinator.trackpadMomentumScroller.isActive) {
                                                    delay(16L)
                                                    val step = coordinator.trackpadMomentumScroller.stepFrame() ?: break
                                                    coordinator.sendMacHid(
                                                        MacHidPacket.scrollPixel(
                                                            dxPx = step.first,
                                                            dyPx = step.second,
                                                            phase = MacHidScrollPhase.MOMENTUM,
                                                            momentum = true,
                                                            naturalScroll = naturalScroll
                                                        )
                                                    )
                                                }
                                            } finally {
                                                if (liveGestureBadge == "双指惯性滚动") {
                                                    liveGestureBadge = if (currentDragLock) "拖拽锁定中" else null
                                                }
                                            }
                                        }
                                    } else {
                                        coordinator.trackpadMomentumScroller.stopImmediate()
                                        if (!currentDragLock) liveGestureBadge = null
                                    }
                                }

                                else -> {
                                    lastSingleTapUpMs = 0L
                                    scope.launch {
                                        delay(420L)
                                        if (activeFingerCount == 0 && !currentDragLock) {
                                            liveGestureBadge = null
                                        }
                                    }
                                }
                            }
                        } finally {
                            touchPointVisual = null
                            activeFingerCount = 0
                            if (isDoubleTapDragging && currentMacConnected) {
                                coordinator.sendMacHid(
                                    MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.UP),
                                    flushImmediate = true
                                )
                            }
                        }
                    }
                }
        ) {
            // Precision Dot-Grid, Corner Crosshair Reticles & Live Touch Halo
            val dotColor = palette.textTertiary.copy(alpha = if (palette.isDark) 0.13f else 0.10f)
            val cornerColor = palette.textTertiary.copy(alpha = 0.28f)
            val accentColor = palette.accent

            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepPx = 26.dp.toPx()
                val rPx = 1.15.dp.toPx()
                var y = stepPx
                while (y < size.height - stepPx * 0.6f) {
                    var x = stepPx
                    while (x < size.width - stepPx * 0.6f) {
                        drawCircle(color = dotColor, radius = rPx, center = Offset(x, y))
                        x += stepPx
                    }
                    y += stepPx
                }

                // Corner L-brackets
                val inset = 16.dp.toPx()
                val arm = 11.dp.toPx()
                val stroke = 1.3.dp.toPx()
                // Top-left
                drawLine(cornerColor, Offset(inset, inset), Offset(inset + arm, inset), stroke, StrokeCap.Round)
                drawLine(cornerColor, Offset(inset, inset), Offset(inset, inset + arm), stroke, StrokeCap.Round)
                // Top-right
                drawLine(cornerColor, Offset(size.width - inset, inset), Offset(size.width - inset - arm, inset), stroke, StrokeCap.Round)
                drawLine(cornerColor, Offset(size.width - inset, inset), Offset(size.width - inset, inset + arm), stroke, StrokeCap.Round)
                // Bottom-left
                drawLine(cornerColor, Offset(inset, size.height - inset), Offset(inset + arm, size.height - inset), stroke, StrokeCap.Round)
                drawLine(cornerColor, Offset(inset, size.height - inset), Offset(inset, size.height - inset - arm), stroke, StrokeCap.Round)
                // Bottom-right
                drawLine(cornerColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - arm, size.height - inset), stroke, StrokeCap.Round)
                drawLine(cornerColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - arm), stroke, StrokeCap.Round)

                // Center subtle crosshair
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val crossArm = 7.dp.toPx()
                drawLine(cornerColor.copy(alpha = 0.20f), Offset(center.x - crossArm, center.y), Offset(center.x + crossArm, center.y), 1.dp.toPx(), StrokeCap.Round)
                drawLine(cornerColor.copy(alpha = 0.20f), Offset(center.x, center.y - crossArm), Offset(center.x, center.y + crossArm), 1.dp.toPx(), StrokeCap.Round)

                // Live finger halo
                touchPointVisual?.let { pt ->
                    val haloRadius = if (activeFingerCount >= 2) 48.dp.toPx() else 34.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = 0.24f), Color.Transparent),
                            center = pt,
                            radius = haloRadius
                        ),
                        radius = haloRadius,
                        center = pt
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.55f),
                        radius = 4.5.dp.toPx(),
                        center = pt
                    )
                }
            }

            // Top HUD Overlay inside Trackpad Pad
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = OmsIcons.Mouse,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(14.5.dp)
                    )
                    Text(
                        text = if (macConnected) "120Hz 触控板 · 双击拖移 / 双指滚轮缩放 / 三指空间" else "未连接 Mac",
                        color = palette.textTertiary,
                        fontSize = 11.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val activeBadge = liveGestureBadge ?: if (isDragLockActive) "拖拽锁定中" else null
                if (activeBadge != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accentSoft)
                            .border(0.6.dp, palette.accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                        )
                        Text(
                            text = activeBadge,
                            color = palette.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Bottom subtle watermark hint when idle
            if (activeFingerCount == 0 && !isDragLockActive && liveGestureBadge == null) {
                Text(
                    text = "单指轻点左键 · 双指轻点右键 · 侧边音量键盲调 Mac 音量",
                    color = palette.textTertiary.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }
        }

        // 2. Tactile Micro-Switch Bar: [左键] · [拖拽锁] · [右键]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackpadMicroSwitchButton(
                label = "左键",
                subtitle = "单击",
                active = false,
                enabled = macConnected,
                onClick = {
                    if (isDragLockActive) {
                        isDragLockActive = false
                        liveGestureBadge = null
                    }
                    coordinator.sendMacHid(
                        MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.CLICK),
                        flushImmediate = true
                    )
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(1f)
            )

            TrackpadMicroSwitchButton(
                label = if (isDragLockActive) "拖拽已锁" else "拖拽锁",
                subtitle = if (isDragLockActive) "轻点释放" else "单指跨屏拖",
                active = isDragLockActive,
                enabled = macConnected,
                onClick = {
                    val next = !isDragLockActive
                    isDragLockActive = next
                    liveGestureBadge = if (next) "拖拽锁定中" else null
                    coordinator.sendMacHid(
                        MacHidPacket.mouseButton(
                            button = MacHidButton.LEFT,
                            action = if (next) MacHidButtonAction.DOWN else MacHidButtonAction.UP
                        ),
                        flushImmediate = true
                    )
                    coordinator.vibrateConfirm()
                },
                modifier = Modifier.weight(1.1f)
            )

            TrackpadMicroSwitchButton(
                label = "右键",
                subtitle = "菜单",
                active = false,
                enabled = macConnected,
                onClick = {
                    coordinator.sendMacHid(
                        MacHidPacket.mouseButton(MacHidButton.RIGHT, MacHidButtonAction.CLICK),
                        flushImmediate = true
                    )
                    coordinator.vibrateConfirm()
                },
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Compact Quick-Jump & Couch Voice Bar + Pointer Speed Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [⌨️ 键盘直输]
            val jumpShape = RoundedCornerShape(16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .height(38.dp)
                    .omsBounceClick(scaleDown = 0.95f, shape = jumpShape, onClick = onJumpToKeyboardVoice)
                    .background(palette.subtleSurface, jumpShape)
                    .border(0.6.dp, palette.hairline, jumpShape)
                    .padding(horizontal = 11.dp)
            ) {
                Icon(
                    imageVector = OmsIcons.Keyboard,
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "键盘直输",
                    color = palette.textPrimary,
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            // [🎙️ 按住语音直输 Mac] (Inline hold-to-speak right on trackpad!)
            MacInlineVoiceDictatePill(
                coordinator = coordinator,
                macConnected = macConnected,
                autoSubmitReturn = false,
                compact = true,
                modifier = Modifier.weight(1f)
            )

            // Pointer Speed Pill (`0.8x / 1.0x / 1.4x`)
            val speedLabel = when {
                sensitivity < 0.9f -> "0.8x"
                sensitivity > 1.2f -> "1.4x"
                else -> "1.0x"
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(38.dp)
                    .omsBounceClick(scaleDown = 0.94f, shape = jumpShape) {
                        sensitivity = when {
                            sensitivity < 0.9f -> 1.0f
                            sensitivity < 1.2f -> 1.4f
                            else -> 0.8f
                        }
                        coordinator.vibrateTick()
                    }
                    .background(palette.subtleSurface, jumpShape)
                    .border(0.6.dp, palette.hairline, jumpShape)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    text = "指针 $speedLabel",
                    color = palette.textSecondary,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TrackpadMicroSwitchButton(
    label: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val bg = if (active) palette.accentSoft else palette.subtleSurface
    val border = if (active) palette.accent.copy(alpha = 0.65f) else palette.hairline
    val titleColor = if (active) palette.accent else palette.textPrimary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(50.dp)
            .omsBounceClick(enabled = enabled, scaleDown = 0.94f, shape = shape, haptic = false, onClick = onClick)
            .background(bg, shape)
            .border(0.75.dp, border, shape)
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = label,
            color = titleColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = subtitle,
            color = if (active) palette.accent.copy(alpha = 0.85f) else palette.textTertiary,
            fontSize = 10.5.sp,
            maxLines = 1
        )
    }
}

/**
 * Ergonomic Gyroscope Air-Mouse & Fullscreen Click-Through Red Laser / Spotlight Wand (`MacLaserAirWandView`).
 *
 * Zero idle battery drain: registers `Sensor.TYPE_GAME_ROTATION_VECTOR` (with fallback to `TYPE_ROTATION_VECTOR`)
 * ONLY while the thumb trigger is held or double-tap locked!
 */
@Composable
fun MacLaserAirWandView(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors

    var laserEffect by remember { mutableIntStateOf(MacHidLaserMode.RED_LASER) }
    var spotlightRadius by remember { mutableFloatStateOf(140f) }
    var airSensitivity by remember { mutableFloatStateOf(1.0f) }

    var isTriggerHeld by remember { mutableStateOf(false) }
    var isTriggerLocked by remember { mutableStateOf(false) }
    val gyroIntegrator = remember { AirWandGyroIntegrator() }

    val sensorActive = macConnected && (isTriggerHeld || isTriggerLocked)

    val currentLaserEffect by rememberUpdatedState(laserEffect)
    val currentSpotlightRadius by rememberUpdatedState(spotlightRadius)
    val currentAirSensitivity by rememberUpdatedState(airSensitivity)
    val currentIsTriggerLocked by rememberUpdatedState(isTriggerLocked)

    LaunchedEffect(macConnected) {
        if (!macConnected) {
            isTriggerHeld = false
            isTriggerLocked = false
            gyroIntegrator.resetCalibration()
        }
    }

    // If user switches to native air-mouse (`OFF`) while locked/held, clear any lingering laser/spotlight overlay
    LaunchedEffect(laserEffect) {
        if (sensorActive && laserEffect == MacHidLaserMode.OFF) {
            coordinator.sendMacHid(
                MacHidPacket.laserUpdate(mode = MacHidLaserMode.OFF, dxPx = 0f, dyPx = 0f),
                flushImmediate = true
            )
        }
    }

    // Strict Zero-Drain Sensor Lifecycle: registered ONLY when `sensorActive == true`
    DisposableEffect(sensorActive) {
        if (!sensorActive) {
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensorManager == null || rotationSensor == null) {
            OmsFeedbackController.show("当前设备未检测到陀螺仪姿态传感器", OmsToastKind.WARNING)
            return@DisposableEffect onDispose {}
        }

        gyroIntegrator.resetCalibration()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                val (dxPx, dyPx) = gyroIntegrator.onRotationSample(
                    rotationVector = values,
                    timestampNs = event.timestamp,
                    sensitivity = currentAirSensitivity
                )
                if (dxPx != 0f || dyPx != 0f) {
                    val mode = currentLaserEffect
                    if (mode == MacHidLaserMode.OFF) {
                        coordinator.sendMacHid(MacHidPacket.moveRel(dxPx, dyPx))
                    } else {
                        coordinator.sendMacHid(
                            MacHidPacket.laserUpdate(
                                mode = mode,
                                dxPx = dxPx,
                                dyPx = dyPx,
                                spotlightRadiusPx = currentSpotlightRadius.toInt()
                            )
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            runCatching { sensorManager.unregisterListener(listener) }
            gyroIntegrator.resetCalibration()
            if (currentLaserEffect != MacHidLaserMode.OFF) {
                coordinator.sendMacHid(
                    MacHidPacket.laserUpdate(mode = MacHidLaserMode.OFF, dxPx = 0f, dyPx = 0f),
                    flushImmediate = true
                )
            }
        }
    }

    // 450ms Heartbeat Keepalive for Mac 1.8s Dead-Man Watchdog (keeps beam steady when hand is still!)
    LaunchedEffect(sensorActive, laserEffect, spotlightRadius) {
        if (!sensorActive || laserEffect == MacHidLaserMode.OFF) return@LaunchedEffect
        while (true) {
            coordinator.sendMacHid(
                MacHidPacket.laserUpdate(
                    mode = laserEffect,
                    dxPx = 0f,
                    dyPx = 0f,
                    spotlightRadiusPx = spotlightRadius.toInt(),
                    keepalive = true
                )
            )
            delay(450L)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Visual Effect Mode Selector + Air Sensitivity Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OmsSlidingSegmentedBar(
                items = listOf(
                    OmsSegmentItem(MacHidLaserMode.OFF, "原生飞鼠"),
                    OmsSegmentItem(MacHidLaserMode.RED_LASER, "红点激光", dotColor = Color(0xFFEF4444)),
                    OmsSegmentItem(MacHidLaserMode.SPOTLIGHT, "聚光灯")
                ),
                selected = laserEffect,
                onSelect = { mode ->
                    if (mode != laserEffect) {
                        laserEffect = mode
                        coordinator.vibrateTick()
                    }
                },
                height = 36.dp,
                modifier = Modifier.weight(1f)
            )

            val sensLabel = when {
                airSensitivity < 0.9f -> "0.8x"
                airSensitivity > 1.2f -> "1.4x"
                else -> "1.0x"
            }
            val pillShape = RoundedCornerShape(18.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(36.dp)
                    .omsBounceClick(scaleDown = 0.94f, shape = pillShape) {
                        airSensitivity = when {
                            airSensitivity < 0.9f -> 1.0f
                            airSensitivity < 1.2f -> 1.4f
                            else -> 0.8f
                        }
                        coordinator.vibrateTick()
                    }
                    .background(palette.subtleSurface, pillShape)
                    .border(0.6.dp, palette.hairline, pillShape)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    text = "体感 $sensLabel",
                    color = palette.textSecondary,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        // 2. Spotlight Aperture Radius Slider (shown only in SPOTLIGHT mode)
        AnimatedVisibility(
            visible = laserEffect == MacHidLaserMode.SPOTLIGHT,
            enter = expandVertically(tween(160)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(110))
        ) {
            OmsSurfaceCard(
                cornerRadius = 15.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "聚光灯视窗半径",
                        color = palette.textSecondary,
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${spotlightRadius.toInt()} px",
                        color = palette.accent,
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = spotlightRadius,
                    onValueChange = { nextRadius ->
                        spotlightRadius = nextRadius
                    },
                    valueRange = 80f..280f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // 3. Central Ergonomic Thumb Trigger Pad (`按住指向 · 松手定格 / 双击常驻锁`)
        val triggerScale by animateFloatAsState(
            targetValue = if (isTriggerHeld) 0.975f else 1f,
            animationSpec = spring(dampingRatio = 0.68f, stiffness = 580f),
            label = "wandTriggerScale"
        )
        val infinitePulse = rememberInfiniteTransition(label = "laserPulse")
        val ringAlpha by infinitePulse.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse),
            label = "laserRingAlpha"
        )

        val activeThemeColor = when (laserEffect) {
            MacHidLaserMode.RED_LASER -> Color(0xFFEF4444)
            MacHidLaserMode.SPOTLIGHT -> palette.warning
            else -> palette.accent
        }
        val triggerShape = RoundedCornerShape(26.dp)
        val triggerBg = if (sensorActive) {
            activeThemeColor.copy(alpha = if (palette.isDark) 0.16f else 0.10f)
        } else {
            palette.cardSurface
        }
        val triggerBorder = if (sensorActive) {
            activeThemeColor.copy(alpha = ringAlpha)
        } else {
            palette.strongBorder
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = triggerScale
                    scaleY = triggerScale
                }
                .clip(triggerShape)
                .background(triggerBg, triggerShape)
                .border(if (sensorActive) 1.4.dp else 0.8.dp, triggerBorder, triggerShape)
                .pointerInput(Unit) {
                    var lastTapUpMs = 0L

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downMs = SystemClock.uptimeMillis()
                        val wasLockedOnDown = currentIsTriggerLocked

                        gyroIntegrator.resetCalibration()

                        if (!wasLockedOnDown) {
                            if (downMs - lastTapUpMs in 1L..280L) {
                                isTriggerLocked = true
                                isTriggerHeld = true
                                lastTapUpMs = 0L
                                coordinator.vibrateConfirm()
                            } else {
                                isTriggerHeld = true
                                coordinator.vibrateTick()
                            }
                        } else {
                            isTriggerHeld = true
                        }

                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                            val upMs = SystemClock.uptimeMillis()
                            val heldMs = upMs - downMs
                            if (wasLockedOnDown) {
                                // Single tap unlocks persistent laser/air-mouse lock cleanly
                                isTriggerLocked = false
                                lastTapUpMs = 0L
                                coordinator.vibrateTick()
                            } else if (!currentIsTriggerLocked) {
                                lastTapUpMs = if (heldMs <= 260L) upMs else 0L
                            }
                        } finally {
                            isTriggerHeld = false
                        }
                    }
                }
                .padding(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            if (sensorActive) activeThemeColor.copy(alpha = 0.22f) else palette.subtleSurface
                        )
                        .border(
                            width = 1.dp,
                            color = if (sensorActive) activeThemeColor else palette.hairline,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isTriggerLocked) OmsIcons.Lock else OmsIcons.Target,
                        contentDescription = null,
                        tint = if (sensorActive) activeThemeColor else palette.textPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Text(
                    text = when {
                        !macConnected -> "等待连接 Mac 后启用体感指向"
                        isTriggerLocked -> "🔒 常驻指向已锁定 · 轻点解除"
                        isTriggerHeld -> when (laserEffect) {
                            MacHidLaserMode.RED_LASER -> "🔴 红点激光激活中 · 转动手腕指向"
                            MacHidLaserMode.SPOTLIGHT -> "🔦 聚光灯激活中 · 转动手腕移动"
                            else -> "🎯 原生飞鼠激活中 · 松手即定格"
                        }
                        else -> "按住拇指扳机指向 · 松手即定格"
                    },
                    color = if (sensorActive) activeThemeColor else palette.textPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (isTriggerLocked) {
                        "陀螺仪已启用生理微颤滤波 · 轻触扳机任意处立即关闭"
                    } else {
                        "每次按下自动归零防漂移 · 双击扳机可开启常驻锁定 · 闲置零耗电"
                    },
                    color = palette.textTertiary,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.5.sp
                )
            }
        }

        // 4. Presenter & Mouse Primary Action Row: [◀ 上一页] · [● 左键点击] · [下一页 ▶]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PresenterPrimaryButton(
                label = "◀ 上一页",
                subtitle = "音量+ 同效",
                enabled = macConnected,
                accent = false,
                onClick = {
                    coordinator.sendMacHid(MacHidPacket.keyTap(MacKeyCodes.LEFT), flushImmediate = true)
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(1f)
            )

            PresenterPrimaryButton(
                label = "● 左键点击",
                subtitle = "点击光标处",
                enabled = macConnected,
                accent = true,
                onClick = {
                    coordinator.sendMacHid(
                        MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.CLICK),
                        flushImmediate = true
                    )
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(1.1f)
            )

            PresenterPrimaryButton(
                label = "下一页 ▶",
                subtitle = "音量- 同效",
                enabled = macConnected,
                accent = false,
                onClick = {
                    coordinator.sendMacHid(MacHidPacket.keyTap(MacKeyCodes.RIGHT), flushImmediate = true)
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(1f)
            )
        }

        // 5. Quick Presenter Shortcut Strip: [全屏 ⌘⌃F] · [Keynote播放 ⌘⌥P] · [黑屏 B] · [退出 Esc]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PresenterMiniPill(
                label = "全屏 ⌘⌃F",
                enabled = macConnected,
                onClick = {
                    coordinator.sendMacHid(
                        MacHidPacket.keyTap(
                            MacKeyCodes.ANSI_F,
                            MacHidModMask.COMMAND or MacHidModMask.CONTROL
                        ),
                        flushImmediate = true
                    )
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(1.05f)
            )
            PresenterMiniPill(
                label = "播放 ⌘⌥P",
                enabled = macConnected,
                onClick = {
                    coordinator.sendMacHid(
                        MacHidPacket.keyTap(
                            MacKeyCodes.ANSI_P,
                            MacHidModMask.COMMAND or MacHidModMask.OPTION
                        ),
                        flushImmediate = true
                    )
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(1.1f)
            )
            PresenterMiniPill(
                label = "黑屏 B",
                enabled = macConnected,
                onClick = {
                    coordinator.sendMacHid(MacHidPacket.keyTap(MacKeyCodes.ANSI_B), flushImmediate = true)
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(0.85f)
            )
            PresenterMiniPill(
                label = "退出 Esc",
                enabled = macConnected,
                onClick = {
                    coordinator.sendMacHid(MacHidPacket.keyTap(MacKeyCodes.ESCAPE), flushImmediate = true)
                    coordinator.vibrateTick()
                },
                modifier = Modifier.weight(0.95f)
            )
        }
    }
}

@Composable
private fun PresenterPrimaryButton(
    label: String,
    subtitle: String,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val bg = if (accent) palette.accentSoft else palette.subtleSurface
    val border = if (accent) palette.accent.copy(alpha = 0.55f) else palette.hairline
    val fg = if (accent) palette.accent else palette.textPrimary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(54.dp)
            .omsBounceClick(enabled = enabled, scaleDown = 0.94f, shape = shape, haptic = false, onClick = onClick)
            .background(bg, shape)
            .border(0.75.dp, border, shape)
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.2.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = subtitle,
            color = palette.textTertiary,
            fontSize = 10.2.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun PresenterMiniPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(13.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(35.dp)
            .omsBounceClick(enabled = enabled, scaleDown = 0.94f, shape = shape, haptic = false, onClick = onClick)
            .background(palette.subtleSurface, shape)
            .border(0.6.dp, palette.hairline, shape)
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = label,
            color = palette.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
