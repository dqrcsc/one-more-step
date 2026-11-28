package com.oms.client.cockpit

import com.oms.client.transport.OmsFrameProtocol
import com.oms.client.transport.OmsFrameStreamDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MacHidAndMotionTest {

    @Test
    fun `encodes exact 20-byte Big-Endian 0x08 HID frame click counts and smooth flags`() {
        val raw = OmsFrameProtocol.encodeHidInput(
            opcode = MacHidOpcode.POINTER_MOVE_REL,
            modsOrBtn = MacHidModMask.COMMAND or MacHidModMask.SHIFT,
            dx = MacHidFixedPoint.pxToQuarter(10.5f),
            dy = MacHidFixedPoint.pxToQuarter(-2.25f),
            aux = 140,
            seq = 65535,
            flags = MacHidFlags.LASER_KEEPALIVE or MacHidFlags.SMOOTH_INTERPOLATE
        )
        assertEquals(20, raw.size)
        // OMS 8-byte header check: 'O' (0x4F), v1 (0x01), type 0x08, flags 0x00, len 12
        assertEquals(0x4F, raw[0].toInt() and 0xFF)
        assertEquals(0x01, raw[1].toInt() and 0xFF)
        assertEquals(0x08, raw[2].toInt() and 0xFF)
        assertEquals(0x00, raw[3].toInt() and 0xFF)
        assertEquals(12, ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.BIG_ENDIAN).int)

        val decoded = OmsFrameStreamDecoder().push(raw).single()
        assertEquals(OmsFrameProtocol.TYPE_HID_INPUT, decoded.frameType)
        assertEquals(OmsFrameProtocol.HID_INPUT_PAYLOAD_BYTES, decoded.payload.size)

        val buf = ByteBuffer.wrap(decoded.payload).order(ByteOrder.BIG_ENDIAN)
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, buf.get().toInt() and 0xFF)
        assertEquals(MacHidModMask.COMMAND or MacHidModMask.SHIFT, buf.get().toInt() and 0xFF)
        assertEquals(42, buf.short.toInt())   // 10.5 * 4 = 42
        assertEquals(-9, buf.short.toInt())   // -2.25 * 4 = -9
        assertEquals(140, buf.short.toInt())
        assertEquals(65535, buf.short.toInt() and 0xFFFF)
        assertEquals(
            MacHidFlags.LASER_KEEPALIVE or MacHidFlags.SMOOTH_INTERPOLATE,
            buf.short.toInt() and 0xFFFF
        )

        // mouseClickWithCount (action = 6, aux = 1 / 2 / 3 with clamping)
        val c1 = MacHidPacket.mouseClickWithCount(MacHidButton.LEFT, clickCount = 1)
        val c2 = MacHidPacket.mouseClickWithCount(MacHidButton.LEFT, clickCount = 2)
        val c3 = MacHidPacket.mouseClickWithCount(MacHidButton.LEFT, clickCount = 3, modifiers = MacHidModMask.COMMAND)
        val cClampedHigh = MacHidPacket.mouseClickWithCount(MacHidButton.LEFT, clickCount = 9)
        val cClampedLow = MacHidPacket.mouseClickWithCount(MacHidButton.LEFT, clickCount = 0)

        assertEquals(MacHidOpcode.MOUSE_BUTTON, c1.opcode)
        assertEquals(MacHidButtonAction.CLICK_WITH_COUNT, c1.dx)
        assertEquals(6, c1.dx)
        assertEquals(1, c1.aux)
        assertTrue(c1.isBarrier)

        assertEquals(2, c2.aux)
        assertEquals(3, c3.aux)
        assertEquals(MacHidModMask.COMMAND, c3.dy)
        assertEquals(3, cClampedHigh.aux)
        assertEquals(1, cClampedLow.aux)

        // Desktop/Launchpad keycodes
        assertEquals(103, MacKeyCodes.F11_SHOW_DESKTOP)
        assertEquals(131, MacKeyCodes.LAUNCHPAD)

        // Quarter-pixel fixed-point precision and int16 clamping
        assertEquals(10.5f, MacHidFixedPoint.quarterToPx(42), 0.0001f)
        assertEquals(1, MacHidFixedPoint.pxToQuarter(0.24f))
        assertEquals(32767, MacHidFixedPoint.pxToQuarter(99999f))
        assertEquals(-32768, MacHidFixedPoint.pxToQuarter(-99999f))
        assertEquals(0, MacHidFixedPoint.pxToQuarter(Float.NaN))
        assertEquals(32767, MacHidFixedPoint.pxToQuarter(Float.POSITIVE_INFINITY))
        assertEquals(-32768, MacHidFixedPoint.pxToQuarter(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `coalescing buffer merges CHANGED and MOMENTUM_CONTINUE scrolls while preserving BEGAN ENDED and barriers`() {
        val buffer = MacHidCoalescingBuffer()

        // 1. Consecutive pointer moves (with SMOOTH_INTERPOLATE) merge, followed by CLICK_WITH_COUNT barrier, then move
        buffer.offer(MacHidPacket.moveRel(4f, -2f, flags = MacHidFlags.SMOOTH_INTERPOLATE))
        buffer.offer(MacHidPacket.moveRel(6f, 5f, flags = MacHidFlags.SMOOTH_INTERPOLATE))
        buffer.offer(MacHidPacket.mouseClickWithCount(MacHidButton.LEFT, clickCount = 2))
        buffer.offer(MacHidPacket.moveRel(2f, 2f))

        // 2. Native scroll lifecycle: BEGAN(1) -> CHANGED(2)+CHANGED(2) -> ENDED(3)
        val sBegan = MacHidPacket.scrollPixel(0f, -4f, phase = MacHidScrollPhase.BEGAN)
        val sChanged1 = MacHidPacket.scrollPixel(1f, -10f, phase = MacHidScrollPhase.CHANGED)
        val sChanged2 = MacHidPacket.scrollPixel(2f, -6f, phase = MacHidScrollPhase.CHANGED)
        val sEnded = MacHidPacket.scrollPixel(0f, 0f, phase = MacHidScrollPhase.ENDED)
        assertTrue("BEGAN must be urgent/barrier", sBegan.isBarrier)
        assertFalse("CHANGED must be coalesceable", sChanged1.isBarrier)
        assertTrue("ENDED must be urgent/barrier", sEnded.isBarrier)
        buffer.offer(sBegan)
        buffer.offer(sChanged1)
        buffer.offer(sChanged2)
        buffer.offer(sEnded)

        // 3. Momentum lifecycle: MOMENTUM_BEGIN(4) -> MOMENTUM_CONTINUE(5)+MOMENTUM_CONTINUE(5) -> MOMENTUM_END(6)
        val mBegin = MacHidPacket.scrollPixel(0f, -14f, phase = MacHidScrollPhase.MOMENTUM_BEGIN, momentum = true)
        val mCont1 = MacHidPacket.scrollPixel(0f, -12f, phase = MacHidScrollPhase.MOMENTUM_CONTINUE, momentum = true)
        val mCont2 = MacHidPacket.scrollPixel(0f, -9f, phase = MacHidScrollPhase.MOMENTUM_CONTINUE, momentum = true)
        val mEnd = MacHidPacket.scrollPixel(0f, 0f, phase = MacHidScrollPhase.MOMENTUM_END, momentum = true)
        assertTrue("MOMENTUM_BEGIN must be urgent/barrier", mBegin.isBarrier)
        assertFalse("MOMENTUM_CONTINUE must be coalesceable", mCont1.isBarrier)
        assertTrue("MOMENTUM_END must be urgent/barrier", mEnd.isBarrier)
        buffer.offer(mBegin)
        buffer.offer(mCont1)
        buffer.offer(mCont2)
        buffer.offer(mEnd)

        // 4. Laser same-mode merges, mode switch and OFF act as barriers
        buffer.offer(MacHidPacket.laserUpdate(MacHidLaserMode.RED_LASER, dxPx = 5f, dyPx = -3f))
        buffer.offer(MacHidPacket.laserUpdate(MacHidLaserMode.RED_LASER, dxPx = 7f, dyPx = 1f, keepalive = true))
        buffer.offer(MacHidPacket.laserUpdate(MacHidLaserMode.SPOTLIGHT, dxPx = 4f, dyPx = 4f, spotlightRadiusPx = 180))
        val laserOff = MacHidPacket.laserUpdate(MacHidLaserMode.OFF, dxPx = 0f, dyPx = 0f)
        assertTrue("LASER OFF must flush immediately as barrier", laserOff.isBarrier)
        buffer.offer(laserOff)
        buffer.offer(MacHidPacket.resetAll())

        assertTrue(buffer.hasBarrier())
        val drained = buffer.drainAll()
        assertTrue(buffer.isEmpty())
        assertEquals(13, drained.size)

        // [0] Merged pre-click move: (4+6, -2+5) = (10px, 3px) with SMOOTH_INTERPOLATE preserved
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, drained[0].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(10f), drained[0].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(3f), drained[0].dy)
        assertEquals(MacHidFlags.SMOOTH_INTERPOLATE, drained[0].flags)

        // [1] Barrier: Double Click via CLICK_WITH_COUNT
        assertEquals(MacHidOpcode.MOUSE_BUTTON, drained[1].opcode)
        assertEquals(MacHidButtonAction.CLICK_WITH_COUNT, drained[1].dx)
        assertEquals(2, drained[1].aux)

        // [2] Post-click move preserved separately
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, drained[2].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(2f), drained[2].dx)

        // [3] BEGAN (1) NOT swallowed into CHANGED!
        assertEquals(MacHidScrollPhase.BEGAN, drained[3].aux)
        assertEquals(MacHidFixedPoint.pxToQuarter(-4f), drained[3].dy)

        // [4] Merged CHANGED + CHANGED (aux = 2): (1+2, -10-6) = (3px, -16px)
        assertEquals(MacHidScrollPhase.CHANGED, drained[4].aux)
        assertEquals(MacHidFixedPoint.pxToQuarter(3f), drained[4].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(-16f), drained[4].dy)

        // [5] ENDED (3) NOT swallowed into CHANGED!
        assertEquals(MacHidScrollPhase.ENDED, drained[5].aux)
        assertEquals(0, drained[5].dx)
        assertEquals(0, drained[5].dy)

        // [6] MOMENTUM_BEGIN (4) preserved
        assertEquals(MacHidScrollPhase.MOMENTUM_BEGIN, drained[6].aux)
        assertEquals(MacHidFixedPoint.pxToQuarter(-14f), drained[6].dy)

        // [7] Merged MOMENTUM_CONTINUE + MOMENTUM_CONTINUE (aux = 5): -12 + -9 = -21px
        assertEquals(MacHidScrollPhase.MOMENTUM_CONTINUE, drained[7].aux)
        assertEquals(MacHidFixedPoint.pxToQuarter(-21f), drained[7].dy)
        assertTrue((drained[7].flags and MacHidFlags.MOMENTUM_SCROLL) != 0)

        // [8] MOMENTUM_END (6) preserved
        assertEquals(MacHidScrollPhase.MOMENTUM_END, drained[8].aux)
        assertEquals(0, drained[8].dy)

        // [9] Merged RED_LASER: (5+7, -3+1) = (12px, -2px) + keepalive flag preserved
        assertEquals(MacHidLaserMode.RED_LASER, drained[9].modsOrBtn)
        assertEquals(MacHidFixedPoint.pxToQuarter(12f), drained[9].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(-2f), drained[9].dy)
        assertEquals(MacHidFlags.LASER_KEEPALIVE, drained[9].flags)

        // [10] SPOTLIGHT, [11] LASER OFF, [12] RESET_HID_STATE
        assertEquals(MacHidLaserMode.SPOTLIGHT, drained[10].modsOrBtn)
        assertEquals(180, drained[10].aux)
        assertEquals(MacHidLaserMode.OFF, drained[11].modsOrBtn)
        assertEquals(MacHidOpcode.RESET_HID_STATE, drained[12].opcode)

        // Saturation test on coalesced huge deltas
        buffer.offer(MacHidPacket.moveRel(6000f, -6000f))
        buffer.offer(MacHidPacket.moveRel(5000f, -5000f))
        val sat = buffer.drainAll().single()
        assertEquals(32767, sat.dx)
        assertEquals(-32768, sat.dy)

        // Tier cadence check
        assertEquals(8L, MacHidCoalescingBuffer.flushIntervalMsForTier("LAN_WS"))
        assertEquals(16L, MacHidCoalescingBuffer.flushIntervalMsForTier("WAN_WEBRTC"))
        assertEquals(28L, MacHidCoalescingBuffer.flushIntervalMsForTier("BT_RFCOMM"))
    }

    @Test
    fun `trackpad acceleration tapers toward 1_05x for steady aiming and reaches 2_8x to 8_5x on thumb flicks`() {
        // Ultra-slow (< 0.08 dp/ms) tapers gently toward 1.05x
        assertEquals(1.05f, TrackpadPhysicsEngine.computeGain(0f), 0.01f)
        val microGain = TrackpadPhysicsEngine.computeGain(0.02f)
        assertTrue("Micro gain should taper near 1.05..1.25, got $microGain", microGain in 1.05f..1.25f)

        // Mid-to-fast thumb swipes (0.8..2.5 dp/ms -> ~2.8x..7.5x) and max clamp (8.50x)
        val midSwipeGain = TrackpadPhysicsEngine.computeGain(0.8f)
        val fastSwipeGain = TrackpadPhysicsEngine.computeGain(2.5f)
        val maxGain = TrackpadPhysicsEngine.computeGain(5.0f)
        assertTrue("0.8 dp/ms gain expected ~2.8x, got $midSwipeGain", midSwipeGain in 2.65f..3.05f)
        assertTrue("2.5 dp/ms gain expected ~7.4x, got $fastSwipeGain", fastSwipeGain in 7.10f..7.75f)
        assertEquals(8.50f, maxGain, 0.001f)

        val micro = TrackpadPhysicsEngine.accelerateDelta(dxDp = 0.3f, dyDp = 0f, dtMs = 16L)
        val slow = TrackpadPhysicsEngine.accelerateDelta(dxDp = 0.8f, dyDp = 0f, dtMs = 16L)
        val fast = TrackpadPhysicsEngine.accelerateDelta(dxDp = 24f, dyDp = 0f, dtMs = 8L)
        assertTrue("Micro motion stays sub-pixel (< 0.45px), got ${micro.first}", micro.first in 0.30f..0.45f)
        assertTrue("Slow motion stays ~1px, got ${slow.first}", slow.first in 0.80f..1.35f)
        assertTrue("Fast flick crosses Retina screen effortlessly, got ${fast.first}", fast.first > 180f)

        // Diagonal movement preserves exact vector direction ratio
        val diag = TrackpadPhysicsEngine.accelerateDelta(dxDp = 12f, dyDp = -6f, dtMs = 8L)
        assertEquals(-0.5f, diag.second / diag.first, 0.001f)

        // Recalibrated scroll acceleration curve (1.2x..5.4x)
        val slowScroll = TrackpadPhysicsEngine.accelerateScrollDelta(dxDp = 0f, dyDp = -1.0f, dtMs = 16L)
        val fastScroll = TrackpadPhysicsEngine.accelerateScrollDelta(dxDp = 0f, dyDp = -20.0f, dtMs = 8L)
        assertTrue(abs(slowScroll.second) in 1.2f..1.8f)
        assertTrue(abs(fastScroll.second) > 65f)
    }

    @Test
    fun `momentum fling scroller decays exponentially and stops immediately on touch brake`() {
        val scroller = MomentumFlingScroller()
        assertFalse(scroller.isActive)

        assertTrue(scroller.startFling(vxPxPerFrame = 0f, vyPxPerFrame = -30f))
        assertTrue(scroller.isActive)

        val step1 = scroller.stepFrame()
        assertNotNull(step1)
        val step2 = scroller.stepFrame()
        assertNotNull(step2)
        // Exponential decay: |step2| < |step1| < 30
        assertTrue(abs(step1!!.second) < 30f)
        assertTrue(abs(step2!!.second) < abs(step1.second))
        assertEquals(0.92f, step2.second / step1.second, 0.01f)

        // Touch-down brake immediately halts momentum
        scroller.stopImmediate()
        assertFalse(scroller.isActive)
        assertNull(scroller.stepFrame())

        // Natural decay to rest below cutoff threshold
        assertTrue(scroller.startFling(vxPxPerFrame = 2.0f, vyPxPerFrame = 0f))
        var steps = 0
        var prevX = Float.MAX_VALUE
        while (true) {
            val delta = scroller.stepFrame() ?: break
            assertTrue(delta.first < prevX)
            prevX = delta.first
            steps++
            assertTrue("Momentum must converge in bounded frames", steps < 60)
        }
        assertTrue(steps in 5..25)
        assertFalse(scroller.isActive)
    }

    @Test
    fun `gyro integrator runs cleanly at 200Hz filters micro-tremor and uses dual-slope alpha`() {
        val gyro = AirWandGyroIntegrator()
        assertEquals(0.014f, gyro.deadzoneRadPerSec, 0.0001f)
        assertEquals(1850f, gyro.basePixelsPerRad, 0.01f)

        // 1. Identity quaternion at t=0 (trigger pressed -> auto zero baseline)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        assertEquals(0f to 0f, gyro.onRotationSample(q0, timestampNs = 1_000_000_000L))

        // 2. Tiny physiological micro-tremor (< 0.014 rad/s over 5ms at 200Hz -> angle ~0.00004 rad) -> exact 0f!
        val qTiny = floatArrayOf(0.00002f, 0f, -0.00002f, 1f)
        val tremorOut = gyro.onRotationSample(qTiny, timestampNs = 1_005_000_000L)
        assertEquals(0f, tremorOut.first, 0.0001f)
        assertEquals(0f, tremorOut.second, 0.0001f)

        // 3. 200Hz (5ms) Right-hand rule check A: Active Wrist YAW RIGHT around +Z (wz = -2.0 rad/s -> alpha = 0.90!)
        // Over 5ms, angle = -0.01 rad -> halfAngle = -0.005 rad
        val halfYawRight = -0.005f
        val qYawRight = floatArrayOf(0f, 0f, sin(halfYawRight), cos(halfYawRight))
        val yawRightOut = gyro.onRotationSample(qYawRight, timestampNs = 1_010_000_000L)
        // Expected excess ~ (2.0 - 0.014) = 1.986 rad/s, alpha = 0.90 -> smoothed = 1.787 rad/s -> * 0.005 * 1850 = ~16.5px!
        assertTrue(
            "200Hz active yaw right must respond immediately with ~0.90 alpha, got ${yawRightOut.first}",
            yawRightOut.first in 15.5f..17.5f
        )
        assertEquals(0f, yawRightOut.second, 0.1f)

        // 4. Right-hand rule check B: Reset calibration, then 200Hz Wrist PITCH UP around +X (wx > 0 -> dy < 0)
        gyro.resetCalibration()
        assertEquals(0f to 0f, gyro.onRotationSample(q0, timestampNs = 2_000_000_000L))

        val halfPitchUp = +0.005f // +0.01 rad over 5ms = +2.0 rad/s around +X
        val qPitchUp = floatArrayOf(sin(halfPitchUp), 0f, 0f, cos(halfPitchUp))
        val pitchUpOut = gyro.onRotationSample(qPitchUp, timestampNs = 2_005_000_000L)
        assertEquals(0f, pitchUpOut.first, 0.001f)
        assertTrue("Pitch up (wx > 0) must move Mac cursor UP (dy < 0), got ${pitchUpOut.second}", pitchUpOut.second < -15f)

        // 5. Body-frame invariance: starting rotated 90 deg around world Z (pointing West), pitching nose UP
        gyro.resetCalibration()
        val s = sqrt(0.5f)
        val qFacingWest = floatArrayOf(0f, 0f, s, s)
        assertEquals(0f to 0f, gyro.onRotationSample(qFacingWest, timestampNs = 3_000_000_000L))
        val sx = sin(halfPitchUp)
        val cw = cos(halfPitchUp)
        val qWestPitchedUp = floatArrayOf(s * sx, s * sx, s * cw, s * cw)
        val westPitchOut = gyro.onRotationSample(qWestPitchedUp, timestampNs = 3_005_000_000L)
        assertEquals(0f, westPitchOut.first, 0.01f)
        assertTrue("Body-frame pitch up while facing West must still produce dy < 0, got ${westPitchOut.second}", westPitchOut.second < -15f)
    }
}
