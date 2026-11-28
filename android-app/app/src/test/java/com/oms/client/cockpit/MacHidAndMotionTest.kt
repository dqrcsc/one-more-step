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
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class MacHidAndMotionTest {

    @Test
    fun `encodes exact 20-byte Big-Endian 0x08 HID frame and clamps quarter-pixel bounds`() {
        val raw = OmsFrameProtocol.encodeHidInput(
            opcode = MacHidOpcode.POINTER_MOVE_REL,
            modsOrBtn = MacHidModMask.COMMAND or MacHidModMask.SHIFT,
            dx = MacHidFixedPoint.pxToQuarter(10.5f),
            dy = MacHidFixedPoint.pxToQuarter(-2.25f),
            aux = 140,
            seq = 65535,
            flags = MacHidFlags.LASER_KEEPALIVE
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
        assertEquals(MacHidFlags.LASER_KEEPALIVE, buf.short.toInt() and 0xFFFF)

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
    fun `coalescing buffer merges consecutive moves scrolls and same-mode lasers while preserving all barriers`() {
        val buffer = MacHidCoalescingBuffer()

        // 1. Consecutive pointer moves merge, followed by CLICK barrier, followed by post-click move
        buffer.offer(MacHidPacket.moveRel(4f, -2f))
        buffer.offer(MacHidPacket.moveRel(6f, 5f))
        buffer.offer(MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.CLICK))
        buffer.offer(MacHidPacket.moveRel(2f, 2f))

        // 2. KEY_TAP and SYSTEM_DEFINED_KEY barriers between scrolls
        buffer.offer(MacHidPacket.scrollPixel(0f, -12f, phase = MacHidScrollPhase.BEGAN))
        buffer.offer(MacHidPacket.scrollPixel(3f, -8f, phase = MacHidScrollPhase.MOMENTUM, momentum = true))
        buffer.offer(MacHidPacket.keyTap(MacKeyCodes.ANSI_C, MacHidModMask.COMMAND))
        buffer.offer(MacHidPacket.systemKey(MacHidSystemKey.SOUND_UP))

        // 3. Laser same-mode merges, laser mode change acts as barrier, RESET_HID_STATE acts as barrier
        buffer.offer(MacHidPacket.laserUpdate(MacHidLaserMode.RED_LASER, dxPx = 5f, dyPx = -3f))
        buffer.offer(MacHidPacket.laserUpdate(MacHidLaserMode.RED_LASER, dxPx = 7f, dyPx = 1f, keepalive = true))
        buffer.offer(MacHidPacket.laserUpdate(MacHidLaserMode.SPOTLIGHT, dxPx = 4f, dyPx = 4f, spotlightRadiusPx = 180))
        buffer.offer(MacHidPacket.resetAll())
        buffer.offer(MacHidPacket.moveRel(1f, 1f))

        assertTrue(buffer.hasBarrier())
        val drained = buffer.drainAll()
        assertTrue(buffer.isEmpty())
        assertEquals(10, drained.size)

        // Merged pre-click move: (4+6, -2+5) = (10px, 3px)
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, drained[0].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(10f), drained[0].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(3f), drained[0].dy)

        // Barrier: Left Click
        assertEquals(MacHidOpcode.MOUSE_BUTTON, drained[1].opcode)
        assertEquals(MacHidButton.LEFT, drained[1].modsOrBtn)
        assertEquals(MacHidButtonAction.CLICK, drained[1].dx)

        // Post-click move preserved separately
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, drained[2].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(2f), drained[2].dx)

        // Merged scroll: (0+3, -12-8) = (3px, -20px), latest phase = MOMENTUM, flags OR'd
        assertEquals(MacHidOpcode.SCROLL_PIXEL, drained[3].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(3f), drained[3].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(-20f), drained[3].dy)
        assertEquals(MacHidScrollPhase.MOMENTUM, drained[3].aux)
        assertTrue((drained[3].flags and MacHidFlags.MOMENTUM_SCROLL) != 0)

        // Barriers: KEY_TAP & SYSTEM_DEFINED_KEY
        assertEquals(MacHidOpcode.KEY_TAP, drained[4].opcode)
        assertEquals(MacHidOpcode.SYSTEM_DEFINED_KEY, drained[5].opcode)

        // Merged RED_LASER: (5+7, -3+1) = (12px, -2px) + keepalive flag preserved
        assertEquals(MacHidOpcode.LASER_STATE_AND_DELTA, drained[6].opcode)
        assertEquals(MacHidLaserMode.RED_LASER, drained[6].modsOrBtn)
        assertEquals(MacHidFixedPoint.pxToQuarter(12f), drained[6].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(-2f), drained[6].dy)
        assertEquals(MacHidFlags.LASER_KEEPALIVE, drained[6].flags)

        // Barrier on mode switch to SPOTLIGHT
        assertEquals(MacHidOpcode.LASER_STATE_AND_DELTA, drained[7].opcode)
        assertEquals(MacHidLaserMode.SPOTLIGHT, drained[7].modsOrBtn)
        assertEquals(180, drained[7].aux)

        // Barrier: RESET_HID_STATE followed by unmerged move
        assertEquals(MacHidOpcode.RESET_HID_STATE, drained[8].opcode)
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, drained[9].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(1f), drained[9].dx)

        // Saturation test on coalesced huge deltas
        buffer.offer(MacHidPacket.moveRel(6000f, -6000f))
        buffer.offer(MacHidPacket.moveRel(5000f, -5000f))
        val sat = buffer.drainAll().single()
        assertEquals(32767, sat.dx)
        assertEquals(-32768, sat.dy)

        // Tier cadence check
        assertEquals(8L, MacHidCoalescingBuffer.flushIntervalMsForTier("LAN_WS"))
        assertEquals(16L, MacHidCoalescingBuffer.flushIntervalMsForTier("WAN_WEBRTC"))
        assertEquals(32L, MacHidCoalescingBuffer.flushIntervalMsForTier("BT_RFCOMM"))
    }

    @Test
    fun `trackpad acceleration scales gently for sub-pixel micro-motion and rapidly for fast flicks`() {
        val micro = TrackpadPhysicsEngine.accelerateDelta(dxDp = 0.3f, dyDp = 0f, dtMs = 16L)
        val slow = TrackpadPhysicsEngine.accelerateDelta(dxDp = 0.8f, dyDp = 0f, dtMs = 16L)
        val fast = TrackpadPhysicsEngine.accelerateDelta(dxDp = 24f, dyDp = 0f, dtMs = 8L)

        assertTrue("Micro motion supports sub-pixel precision (< 0.5px), got ${micro.first}", micro.first in 0.1f..0.45f)
        assertTrue("Slow motion stays fine-grained, got ${slow.first}", slow.first in 0.5f..2.5f)
        assertTrue("Fast flick crosses screen rapidly, got ${fast.first}", fast.first > 45f)

        // Diagonal movement preserves vector direction ratio
        val diag = TrackpadPhysicsEngine.accelerateDelta(dxDp = 12f, dyDp = -6f, dtMs = 8L)
        assertEquals(-0.5f, diag.second / diag.first, 0.001f)
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
    fun `gyro integrator auto-zeros filters hand tremor and obeys right-hand rule for yaw and pitch`() {
        val gyro = AirWandGyroIntegrator()

        // 1. Identity quaternion at t=0 (trigger pressed -> auto zero baseline)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f) // [x, y, z, w]
        assertEquals(0f to 0f, gyro.onRotationSample(q0, timestampNs = 1_000_000_000L))

        // 2. Tiny physiological micro-tremor (< 0.018 rad/s over 10ms -> angle ~0.0001 rad) -> filtered to exact 0f!
        val qTiny = floatArrayOf(0.00004f, 0f, -0.00004f, 1f)
        val tremorOut = gyro.onRotationSample(qTiny, timestampNs = 1_010_000_000L)
        assertEquals(0f, tremorOut.first, 0.0001f)
        assertEquals(0f, tremorOut.second, 0.0001f)

        // 3. Right-hand rule check A: Wrist YAW RIGHT around +Z (clockwise looking down +Z -> wz < 0 -> dx > 0)
        val halfYawRight = -0.025f // ~ -0.05 rad over 10ms = -5 rad/s around +Z
        val qYawRight = floatArrayOf(0f, 0f, sin(halfYawRight), cos(halfYawRight))
        val yawRightOut = gyro.onRotationSample(qYawRight, timestampNs = 1_020_000_000L)
        assertTrue("Yaw right (wz < 0) must move cursor right (dx > 0), got ${yawRightOut.first}", yawRightOut.first > 5f)
        assertEquals(0f, yawRightOut.second, 0.1f)

        // 4. Right-hand rule check B: Reset calibration, then Wrist PITCH UP around +X
        // Thumb right (+X): fingers curl from +Y (forward towards Mac) UP towards +Z (sky) -> wx > 0!
        // On macOS CoreGraphics (top-left origin), moving cursor UP means dy < 0!
        gyro.resetCalibration()
        assertEquals(0f to 0f, gyro.onRotationSample(q0, timestampNs = 2_000_000_000L))

        val halfPitchUp = +0.025f // +0.05 rad over 10ms = +5 rad/s around +X
        val qPitchUp = floatArrayOf(sin(halfPitchUp), 0f, 0f, cos(halfPitchUp))
        val pitchUpOut = gyro.onRotationSample(qPitchUp, timestampNs = 2_010_000_000L)
        assertEquals(0f, pitchUpOut.first, 0.001f)
        assertTrue("Pitch up (wx > 0) must move Mac cursor UP (dy < 0), got ${pitchUpOut.second}", pitchUpOut.second < -5f)

        // 5. Body-frame invariance: starting rotated 90 deg around world Z (pointing West), pitching nose UP
        gyro.resetCalibration()
        val s = sqrt(0.5f)
        val qFacingWest = floatArrayOf(0f, 0f, s, s) // 90 deg CCW around world +Z
        assertEquals(0f to 0f, gyro.onRotationSample(qFacingWest, timestampNs = 3_000_000_000L))
        // Apply body-frame pitch up: qNext = qFacingWest * [sin(halfPitchUp), 0, 0, cos(halfPitchUp)]
        val sx = sin(halfPitchUp)
        val cw = cos(halfPitchUp)
        val qWestPitchedUp = floatArrayOf(
            s * sx,  // w1*x2 + z1*y2 = s*sx + 0
            s * sx,  // w1*y2 + x1*z2 - z1*x2... wait: (0,0,s,s)*(sx,0,0,cw) -> x=s*sx, y=s*sx, z=s*cw, w=s*cw
            s * cw,
            s * cw
        )
        val westPitchOut = gyro.onRotationSample(qWestPitchedUp, timestampNs = 3_010_000_000L)
        assertEquals(0f, westPitchOut.first, 0.01f)
        assertTrue("Body-frame pitch up while facing West must still produce dy < 0, got ${westPitchOut.second}", westPitchOut.second < -5f)
    }
}
