package com.oms.client.cockpit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

private fun cleanZero(v: Float): Float = if (v == 0f) 0f else v

/**
 * Magic Trackpad-grade non-linear pointer and two-finger scroll acceleration curve,
 * calibrated for a `~390dp` phone surface driving a `1792x1120`+ Retina Mac display.
 *
 * - Below `0.08 dp/ms`, gently tapers down toward `1.05x` so slow pixel-by-pixel aiming is rock-steady;
 * - Across `0.8..2.5 dp/ms`, scales smoothly across `~2.8x..7.5x` (up to `8.50x`) so a single thumb swipe
 *   effortlessly crosses the entire Mac screen.
 */
object TrackpadPhysicsEngine {
    private const val BASE_GAIN = 1.45f
    private const val ACCEL_K = 1.85f
    private const val ACCEL_EXPONENT = 1.28f
    private const val MIN_GAIN = 1.05f
    private const val MAX_GAIN = 8.50f
    private const val PRECISION_TAPER_SPEED = 0.08f

    fun computeGain(speedDpPerMs: Float, sensitivity: Float = 1.0f): Float {
        val safeSpeed = if (speedDpPerMs.isFinite() && speedDpPerMs > 0f) speedDpPerMs else 0f
        val safeSens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.25f, 3.0f) else 1.0f
        val curve = if (safeSpeed < PRECISION_TAPER_SPEED) {
            val boundaryGain = BASE_GAIN + ACCEL_K * PRECISION_TAPER_SPEED.pow(ACCEL_EXPONENT)
            val t = (safeSpeed / PRECISION_TAPER_SPEED).coerceIn(0f, 1f)
            MIN_GAIN + (boundaryGain - MIN_GAIN) * t
        } else {
            BASE_GAIN + ACCEL_K * safeSpeed.pow(ACCEL_EXPONENT)
        }.coerceIn(MIN_GAIN, MAX_GAIN)
        return curve * safeSens
    }

    fun accelerateDelta(
        dxDp: Float,
        dyDp: Float,
        dtMs: Long,
        sensitivity: Float = 1.0f
    ): Pair<Float, Float> {
        if (!dxDp.isFinite() || !dyDp.isFinite()) return 0f to 0f
        val distDp = hypot(dxDp, dyDp)
        if (distDp == 0f) return 0f to 0f
        val safeDtMs = dtMs.coerceIn(2L, 100L).toFloat()
        val speedDpPerMs = distDp / safeDtMs
        val gain = computeGain(speedDpPerMs, sensitivity)
        return cleanZero(dxDp * gain) to cleanZero(dyDp * gain)
    }

    fun accelerateScrollDelta(
        dxDp: Float,
        dyDp: Float,
        dtMs: Long,
        sensitivity: Float = 1.0f
    ): Pair<Float, Float> {
        if (!dxDp.isFinite() || !dyDp.isFinite()) return 0f to 0f
        val distDp = hypot(dxDp, dyDp)
        if (distDp == 0f) return 0f to 0f
        val safeDtMs = dtMs.coerceIn(2L, 100L).toFloat()
        val speedDpPerMs = distDp / safeDtMs
        val safeSens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.25f, 3.0f) else 1.0f
        val gain = (1.45f + 0.72f * speedDpPerMs.pow(1.15f)).coerceIn(1.2f, 5.4f) * safeSens
        return cleanZero(dxDp * gain) to cleanZero(dyDp * gain)
    }
}

/**
 * Inertial two-finger momentum scroller with exponential per-frame decay (`0.92f`)
 * and instant touch-down brake (`stopImmediate()`).
 */
class MomentumFlingScroller {
    companion object {
        const val DEFAULT_FRICTION: Float = 0.92f
        const val MIN_SPEED_PX_PER_FRAME: Float = 0.6f
        const val MAX_INITIAL_SPEED_PX_PER_FRAME: Float = 180f
        private const val REFERENCE_FRAME_MS: Float = 16f
    }

    private var active: Boolean = false
    private var vx: Float = 0f
    private var vy: Float = 0f
    private var trackedVx: Float = 0f
    private var trackedVy: Float = 0f

    val isActive: Boolean
        @Synchronized get() = active

    @Synchronized
    fun recordScrollSample(dxPx: Float, dyPx: Float, dtMs: Long) {
        if (!dxPx.isFinite() || !dyPx.isFinite()) return
        val safeDtMs = dtMs.coerceIn(4L, 80L).toFloat()
        val instVx = dxPx * (REFERENCE_FRAME_MS / safeDtMs)
        val instVy = dyPx * (REFERENCE_FRAME_MS / safeDtMs)
        trackedVx = trackedVx + 0.62f * (instVx - trackedVx)
        trackedVy = trackedVy + 0.62f * (instVy - trackedVy)
    }

    @Synchronized
    fun startFromTrackedVelocity(minTriggerSpeedPxPerFrame: Float = 1.8f): Boolean {
        val speed = hypot(trackedVx, trackedVy)
        val vxSnapshot = trackedVx
        val vySnapshot = trackedVy
        trackedVx = 0f
        trackedVy = 0f
        if (speed < max(MIN_SPEED_PX_PER_FRAME, minTriggerSpeedPxPerFrame)) {
            stopImmediate()
            return false
        }
        return startFling(vxSnapshot, vySnapshot)
    }

    @Synchronized
    fun startFling(vxPxPerFrame: Float, vyPxPerFrame: Float): Boolean {
        if (!vxPxPerFrame.isFinite() || !vyPxPerFrame.isFinite()) {
            stopImmediate()
            return false
        }
        val rawSpeed = hypot(vxPxPerFrame, vyPxPerFrame)
        if (rawSpeed < MIN_SPEED_PX_PER_FRAME) {
            stopImmediate()
            return false
        }
        val clampScale = if (rawSpeed > MAX_INITIAL_SPEED_PX_PER_FRAME) {
            MAX_INITIAL_SPEED_PX_PER_FRAME / rawSpeed
        } else {
            1f
        }
        vx = cleanZero(vxPxPerFrame * clampScale)
        vy = cleanZero(vyPxPerFrame * clampScale)
        active = true
        return true
    }

    @Synchronized
    fun stepFrame(friction: Float = DEFAULT_FRICTION): Pair<Float, Float>? {
        if (!active) return null
        val f = if (friction.isFinite()) friction.coerceIn(0.5f, 0.99f) else DEFAULT_FRICTION
        vx *= f
        vy *= f
        if (hypot(vx, vy) < MIN_SPEED_PX_PER_FRAME) {
            stopImmediate()
            return null
        }
        return cleanZero(vx) to cleanZero(vy)
    }

    @Synchronized
    fun stopImmediate() {
        active = false
        vx = 0f
        vy = 0f
        trackedVx = 0f
        trackedVy = 0f
    }
}

typealias MomentumFlingState = MomentumFlingScroller

/**
 * 200Hz+ Hold-to-Point gyroscope Air-Wand integrator driven by `Sensor.TYPE_GAME_ROTATION_VECTOR`.
 *
 * Coordinate & Right-Hand Rule Mapping (Android portrait device body frame):
 * - `+X` points right out the phone's right edge;
 * - `+Y` points forward out the phone's top edge (aimed towards the Mac screen);
 * - `+Z` points up out of the phone screen.
 *
 * Relative body-frame quaternion `dq = conj(qPrev) * qCurr` yields instantaneous body angular velocity:
 * - Wrist yaw right (clockwise around `+Z`) -> `wz < 0` -> `dxPx = -wz * dt * gain > 0` (cursor right);
 * - Wrist pitch up (top edge `+Y` tilted towards `+Z`, CCW around `+X`) -> `wx > 0` ->
 *   `dyPx = -wx * dt * gain < 0` (cursor up in macOS CoreGraphics top-left origin coordinates).
 */
class AirWandGyroIntegrator(
    val deadzoneRadPerSec: Float = DEFAULT_DEADZONE_RAD_PER_SEC,
    val basePixelsPerRad: Float = DEFAULT_PIXELS_PER_RAD,
    private val smoothingAlpha: Float = MAX_SMOOTHING_ALPHA
) {
    companion object {
        const val DEFAULT_DEADZONE_RAD_PER_SEC: Float = 0.014f
        const val DEFAULT_PIXELS_PER_RAD: Float = 1850f
        private const val ACTIVE_OMEGA_RAD_PER_SEC: Float = 0.14f
        private const val MIN_SMOOTHING_ALPHA: Float = 0.48f
        private const val MAX_SMOOTHING_ALPHA: Float = 0.90f
        private const val MIN_DT_SEC: Float = 0.001f
        private const val MAX_DT_SEC: Float = 0.060f
        private const val STALE_GAP_NS: Long = 250_000_000L
    }

    private var hasBaseline: Boolean = false
    private var prevX: Float = 0f
    private var prevY: Float = 0f
    private var prevZ: Float = 0f
    private var prevW: Float = 1f
    private var prevTimestampNs: Long = 0L
    private var smoothedWx: Float = 0f
    private var smoothedWz: Float = 0f

    val isCalibrated: Boolean
        @Synchronized get() = hasBaseline

    @Synchronized
    fun resetCalibration() {
        hasBaseline = false
        prevX = 0f
        prevY = 0f
        prevZ = 0f
        prevW = 1f
        prevTimestampNs = 0L
        smoothedWx = 0f
        smoothedWz = 0f
    }

    @Synchronized
    fun onRotationSample(
        rotationVector: FloatArray,
        timestampNs: Long,
        sensitivity: Float = 1.0f
    ): Pair<Float, Float> {
        if (rotationVector.size < 3) return 0f to 0f
        val rx = rotationVector[0]
        val ry = rotationVector[1]
        val rz = rotationVector[2]
        val rw = if (rotationVector.size >= 4) {
            rotationVector[3]
        } else {
            sqrt(max(0f, 1f - rx * rx - ry * ry - rz * rz))
        }
        if (!rx.isFinite() || !ry.isFinite() || !rz.isFinite() || !rw.isFinite()) {
            return 0f to 0f
        }
        val norm = sqrt(rx * rx + ry * ry + rz * rz + rw * rw)
        if (norm < 1e-6f) return 0f to 0f

        val x2 = rx / norm
        val y2 = ry / norm
        val z2 = rz / norm
        val w2 = rw / norm

        if (!hasBaseline) {
            prevX = x2
            prevY = y2
            prevZ = z2
            prevW = w2
            prevTimestampNs = timestampNs
            hasBaseline = true
            return 0f to 0f
        }

        val rawDtNs = timestampNs - prevTimestampNs
        val x1 = prevX
        val y1 = prevY
        val z1 = prevZ
        val w1 = prevW

        prevX = x2
        prevY = y2
        prevZ = z2
        prevW = w2
        prevTimestampNs = timestampNs

        if (rawDtNs <= 0L || rawDtNs > STALE_GAP_NS) {
            smoothedWx = 0f
            smoothedWz = 0f
            return 0f to 0f
        }

        val dtSec = (rawDtNs * 1e-9f).coerceIn(MIN_DT_SEC, MAX_DT_SEC)

        // Body-frame relative rotation: dq = conj(q1) * q2
        var dqX = w1 * x2 - x1 * w2 - y1 * z2 + z1 * y2
        var dqY = w1 * y2 + x1 * z2 - y1 * w2 - z1 * x2
        var dqZ = w1 * z2 - x1 * y2 + y1 * x2 - z1 * w2
        var dqW = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2

        // Shortest-arc hemisphere normalization (SO(3) double cover)
        if (dqW < 0f) {
            dqX = -dqX
            dqY = -dqY
            dqZ = -dqZ
            dqW = -dqW
        }

        val sinHalf = sqrt(dqX * dqX + dqY * dqY + dqZ * dqZ)
        val axisAngleScale = if (sinHalf > 1e-6f) {
            val angle = 2f * atan2(sinHalf, dqW)
            angle / sinHalf
        } else {
            2f
        }

        val rawWx = (dqX * axisAngleScale) / dtSec
        val rawWz = (dqZ * axisAngleScale) / dtSec

        val highAlpha = smoothingAlpha.coerceIn(0.15f, 1.0f)
        val lowAlpha = min(MIN_SMOOTHING_ALPHA, highAlpha)
        smoothedWx = applyDeadzoneAndSmooth(rawWx, smoothedWx, lowAlpha, highAlpha)
        smoothedWz = applyDeadzoneAndSmooth(rawWz, smoothedWz, lowAlpha, highAlpha)

        if (smoothedWx == 0f && smoothedWz == 0f) {
            return 0f to 0f
        }

        val safeSens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.25f, 3.0f) else 1.0f
        val pxScale = dtSec * basePixelsPerRad * safeSens

        // Yaw right (wz < 0) -> dx > 0; Pitch up (wx > 0) -> dy < 0 (macOS top-left origin)
        val dxPx = cleanZero(-smoothedWz * pxScale)
        val dyPx = cleanZero(-smoothedWx * pxScale)
        return dxPx to dyPx
    }

    private fun applyDeadzoneAndSmooth(
        rawOmega: Float,
        prevSmoothed: Float,
        lowAlpha: Float,
        highAlpha: Float
    ): Float {
        val mag = abs(rawOmega)
        if (mag <= deadzoneRadPerSec) {
            return 0f
        }
        val activeSpan = max(1e-4f, ACTIVE_OMEGA_RAD_PER_SEC - deadzoneRadPerSec)
        val blend = ((mag - deadzoneRadPerSec) / activeSpan).coerceIn(0f, 1f)
        val alpha = lowAlpha + blend * (highAlpha - lowAlpha)
        val excess = sign(rawOmega) * (mag - deadzoneRadPerSec)
        return prevSmoothed + alpha * (excess - prevSmoothed)
    }
}
