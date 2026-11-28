package com.oms.client.cockpit

import com.oms.client.transport.OmsFrameProtocol
import kotlin.math.roundToLong

object MacHidOpcode {
    const val POINTER_MOVE_REL: Int = 0x01
    const val MOUSE_BUTTON: Int = 0x02
    const val SCROLL_PIXEL: Int = 0x03
    const val KEY_TAP: Int = 0x04
    const val SYSTEM_DEFINED_KEY: Int = 0x05
    const val LASER_STATE_AND_DELTA: Int = 0x06
    const val RESET_HID_STATE: Int = 0x0F
}

object MacHidModMask {
    const val NONE: Int = 0x00
    const val COMMAND: Int = 0x01
    const val SHIFT: Int = 0x02
    const val OPTION: Int = 0x04
    const val CONTROL: Int = 0x08
}

object MacHidButton {
    const val LEFT: Int = 0x01
    const val RIGHT: Int = 0x02
    const val MIDDLE: Int = 0x03
}

object MacHidButtonAction {
    const val CLICK: Int = 1
    const val DOUBLE_CLICK: Int = 2
    const val DOWN: Int = 3
    const val UP: Int = 4
    const val DRAG_LOCK_TOGGLE: Int = 5
}

object MacHidLaserMode {
    const val OFF: Int = 0
    const val RED_LASER: Int = 1
    const val SPOTLIGHT: Int = 2
}

object MacHidSystemKey {
    const val SOUND_UP: Int = 0
    const val SOUND_DOWN: Int = 1
    const val BRIGHTNESS_UP: Int = 2
    const val BRIGHTNESS_DOWN: Int = 3
    const val MUTE: Int = 7
    const val PLAY_PAUSE: Int = 16
    const val NEXT_TRACK: Int = 17
    const val PREV_TRACK: Int = 18
}

object MacHidFlags {
    const val NONE: Int = 0x0000
    const val MOMENTUM_SCROLL: Int = 0x0001
    const val LASER_KEEPALIVE: Int = 0x0002
    const val NATURAL_SCROLL: Int = 0x0004
}

object MacHidScrollPhase {
    const val NONE: Int = 0
    const val BEGAN: Int = 1
    const val CHANGED: Int = 2
    const val ENDED: Int = 3
    const val MOMENTUM: Int = 4
}

/**
 * US ANSI macOS CGKeyCode constants for shortcuts, navigation, and presentation control.
 */
object MacKeyCodes {
    const val ANSI_A: Int = 0
    const val ANSI_S: Int = 1
    const val ANSI_D: Int = 2
    const val ANSI_F: Int = 3
    const val ANSI_Z: Int = 6
    const val ANSI_X: Int = 7
    const val ANSI_C: Int = 8
    const val ANSI_V: Int = 9
    const val ANSI_B: Int = 11
    const val ANSI_Q: Int = 12
    const val ANSI_W: Int = 13
    const val ANSI_R: Int = 15
    const val ANSI_T: Int = 17
    const val ANSI_PLUS: Int = 24
    const val ANSI_EQUAL: Int = 24
    const val ANSI_MINUS: Int = 27
    const val ANSI_0: Int = 29
    const val ANSI_RIGHT_BRACKET: Int = 30
    const val ANSI_LEFT_BRACKET: Int = 33
    const val ANSI_P: Int = 35

    const val RETURN: Int = 36
    const val TAB: Int = 48
    const val SPACE: Int = 49
    const val DELETE: Int = 51
    const val ESCAPE: Int = 53
    const val FORWARD_DELETE: Int = 117

    const val LEFT: Int = 123
    const val RIGHT: Int = 124
    const val DOWN: Int = 125
    const val UP: Int = 126
}

typealias HidOpcode = MacHidOpcode
typealias HidModifierMask = MacHidModMask
typealias HidButtonCode = MacHidButton
typealias HidButtonAction = MacHidButtonAction
typealias HidLaserMode = MacHidLaserMode
typealias HidSystemKey = MacHidSystemKey
typealias HidInputFlags = MacHidFlags

object MacHidFixedPoint {
    fun clampInt16(value: Int): Int = value.coerceIn(-32768, 32767)

    fun pxToQuarter(px: Float): Int {
        if (px.isNaN()) return 0
        if (px == Float.POSITIVE_INFINITY) return 32767
        if (px == Float.NEGATIVE_INFINITY) return -32768
        return (px * 4.0).roundToLong().coerceIn(-32768L, 32767L).toInt()
    }

    fun quarterToPx(raw: Int): Float = raw * 0.25f
}

data class MacHidPacket(
    val opcode: Int,
    val modsOrBtn: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val aux: Int = 0,
    val flags: Int = MacHidFlags.NONE
) {
    val isBarrier: Boolean
        get() = !MacHidCoalescingBuffer.isCoalesceableOpcode(opcode)

    fun toWireFrame(seq: Int): ByteArray = OmsFrameProtocol.encodeHidInput(
        opcode = opcode,
        modsOrBtn = modsOrBtn,
        dx = dx,
        dy = dy,
        aux = aux,
        seq = seq and 0xFFFF,
        flags = flags
    )

    companion object {
        fun moveRel(
            dxPx: Float,
            dyPx: Float,
            modifiers: Int = MacHidModMask.NONE,
            flags: Int = MacHidFlags.NONE
        ): MacHidPacket = MacHidPacket(
            opcode = MacHidOpcode.POINTER_MOVE_REL,
            modsOrBtn = modifiers and 0xFF,
            dx = MacHidFixedPoint.pxToQuarter(dxPx),
            dy = MacHidFixedPoint.pxToQuarter(dyPx),
            aux = 0,
            flags = flags
        )

        fun mouseButton(
            button: Int = MacHidButton.LEFT,
            action: Int = MacHidButtonAction.CLICK,
            modifiers: Int = MacHidModMask.NONE
        ): MacHidPacket = MacHidPacket(
            opcode = MacHidOpcode.MOUSE_BUTTON,
            modsOrBtn = button and 0xFF,
            dx = MacHidFixedPoint.clampInt16(action),
            dy = modifiers and 0xFF,
            aux = 0,
            flags = MacHidFlags.NONE
        )

        fun scrollPixel(
            dxPx: Float,
            dyPx: Float,
            phase: Int = MacHidScrollPhase.CHANGED,
            momentum: Boolean = false,
            naturalScroll: Boolean = true
        ): MacHidPacket {
            var fl = MacHidFlags.NONE
            if (momentum) fl = fl or MacHidFlags.MOMENTUM_SCROLL
            if (naturalScroll) fl = fl or MacHidFlags.NATURAL_SCROLL
            return MacHidPacket(
                opcode = MacHidOpcode.SCROLL_PIXEL,
                modsOrBtn = 0,
                dx = MacHidFixedPoint.pxToQuarter(dxPx),
                dy = MacHidFixedPoint.pxToQuarter(dyPx),
                aux = MacHidFixedPoint.clampInt16(phase),
                flags = fl
            )
        }

        fun keyTap(
            keyCode: Int,
            modifiers: Int = MacHidModMask.NONE
        ): MacHidPacket = MacHidPacket(
            opcode = MacHidOpcode.KEY_TAP,
            modsOrBtn = modifiers and 0xFF,
            dx = keyCode.coerceIn(0, 255),
            dy = 0,
            aux = 0,
            flags = MacHidFlags.NONE
        )

        fun systemKey(
            systemKeyType: Int
        ): MacHidPacket = MacHidPacket(
            opcode = MacHidOpcode.SYSTEM_DEFINED_KEY,
            modsOrBtn = systemKeyType and 0xFF,
            dx = systemKeyType.coerceIn(0, 255),
            dy = 0,
            aux = 0,
            flags = MacHidFlags.NONE
        )

        fun laserUpdate(
            mode: Int,
            dxPx: Float = 0f,
            dyPx: Float = 0f,
            spotlightRadiusPx: Int = 140,
            keepalive: Boolean = false
        ): MacHidPacket = MacHidPacket(
            opcode = MacHidOpcode.LASER_STATE_AND_DELTA,
            modsOrBtn = mode and 0xFF,
            dx = MacHidFixedPoint.pxToQuarter(dxPx),
            dy = MacHidFixedPoint.pxToQuarter(dyPx),
            aux = spotlightRadiusPx.coerceIn(0, 1000),
            flags = if (keepalive) MacHidFlags.LASER_KEEPALIVE else MacHidFlags.NONE
        )

        fun resetAll(): MacHidPacket = MacHidPacket(
            opcode = MacHidOpcode.RESET_HID_STATE,
            modsOrBtn = 0,
            dx = 0,
            dy = 0,
            aux = 0,
            flags = MacHidFlags.NONE
        )
    }
}

/**
 * Barrier-safe plaintext HID coalescing buffer.
 *
 * Merges consecutive delta packets of the same opcode (`POINTER_MOVE_REL`, `SCROLL_PIXEL`,
 * or `LASER_STATE_AND_DELTA` with identical `modsOrBtn` mode) BEFORE AEAD sealing,
 * while strictly preserving causal ordering across clicks, key taps, system keys,
 * mode switches, and `RESET_HID_STATE`.
 */
class MacHidCoalescingBuffer(
    private val maxEntries: Int = 256
) {
    private val queue = ArrayList<MacHidPacket>(16)

    @Synchronized
    fun offer(packet: MacHidPacket) {
        if (queue.isNotEmpty()) {
            val lastIdx = queue.lastIndex
            val prev = queue[lastIdx]
            if (canCoalesce(prev, packet)) {
                queue[lastIdx] = MacHidPacket(
                    opcode = packet.opcode,
                    modsOrBtn = packet.modsOrBtn,
                    dx = MacHidFixedPoint.clampInt16(prev.dx + packet.dx),
                    dy = MacHidFixedPoint.clampInt16(prev.dy + packet.dy),
                    aux = packet.aux,
                    flags = prev.flags or packet.flags
                )
                return
            }
        }
        if (queue.size >= maxEntries) {
            val dropIdx = queue.indexOfFirst { !it.isBarrier }
            if (dropIdx >= 0) {
                queue.removeAt(dropIdx)
            } else {
                queue.removeAt(0)
            }
        }
        queue.add(packet)
    }

    @Synchronized
    fun drainAll(): List<MacHidPacket> {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<MacHidPacket>(queue)
        queue.clear()
        return out
    }

    @Synchronized
    fun clear() {
        queue.clear()
    }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun hasBarrier(): Boolean = queue.any { it.isBarrier }

    companion object {
        fun isCoalesceableOpcode(opcode: Int): Boolean =
            opcode == MacHidOpcode.POINTER_MOVE_REL ||
                opcode == MacHidOpcode.SCROLL_PIXEL ||
                opcode == MacHidOpcode.LASER_STATE_AND_DELTA

        fun canCoalesce(prev: MacHidPacket, next: MacHidPacket): Boolean =
            isCoalesceableOpcode(prev.opcode) &&
                prev.opcode == next.opcode &&
                prev.modsOrBtn == next.modsOrBtn

        fun flushIntervalMsForTier(tier: String): Long = when (tier) {
            "LAN_WS" -> 8L       // 120Hz frame-aligned LAN fast path
            "WAN_WEBRTC" -> 16L  // 60Hz WAN relay path
            "BT_RFCOMM" -> 32L   // ~30Hz BLE GATT coalesced path
            else -> 16L
        }
    }
}
