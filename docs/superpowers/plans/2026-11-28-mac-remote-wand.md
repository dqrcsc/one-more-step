# Mac Multi-Modal Remote Wand & Laser Spotlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade OneMoreStep's Mac remote control from discrete power/app buttons into a 120Hz eyes-free multi-modal controller featuring a multi-touch Magic Trackpad, hold-to-point gyroscope Air Mouse + fullscreen穿透 (click-through) Red Laser & Spotlight overlay, lossless Unicode/voice-to-Mac dictation + shortcut deck, and hardware volume-key blind control.

**Architecture:** Introduce a 20-byte compact binary frame (`FrameType.HID_INPUT = 0x08`, 8B OMS header + 12B payload) scheduled at `P0_URGENT_CONTROL` inside the existing authenticated AEAD v2 tunnel (`LAN_WS` / `WAN_WEBRTC` / `BT_RFCOMM`) with barrier-safe in-queue delta coalescing, paired with `mac.hid.type_text` JSON-RPC for lossless UTF-16 text injection. Upgrade `mac-os-controller.ts` <-> `oms-mac-ctl` (`~/Applications/青简控机助手.app`) to a persistent streaming `AF_UNIX` NDJSON pipe driving native `CGEvent` pointer/pixel-scroll/shortcuts, `NX_KEYTYPE_*` media/brightness keys, and a borderless `.screenSaver`-level click-through `NSPanel` rendering a 120fps laser trail and spotlight mask with a `1.8s` dead-man watchdog.

**Tech Stack:** TypeScript (`@oms/shared-protocol`, `@oms/mac-host` on Bun), Swift 5 (`AppKit`, `CoreGraphics`, `ApplicationServices`, `QuartzCore` compiled via `swiftc`), Kotlin + Jetpack Compose (`SensorManager` `TYPE_GAME_ROTATION_VECTOR`, `PointerEventScope`, `VoiceRecorderHelper`, JUnit4).

**Spec:** `docs/plans/2026-11-28-mac-remote-wand-design.md`

## Global Constraints

- Zero new macOS TCC Screen Recording permissions and zero new UDP/TCP firewall ports; reuse only existing AEAD v2 sessions and `~/Applications/青简控机助手.app` (`com.oms.mac-control`) Accessibility grant.
- `FrameType.HID_INPUT` (`0x08`) payload MUST be exactly 12 bytes Big-Endian: `[1B opcode][1B modsOrBtn][2B int16 dx][2B int16 dy][2B int16 aux][2B uint16 seq][2B uint16 flags]`, using `0.25px` fixed-point units (`raw = clamp(round(px * 4), -32768, 32767)`) for pointer/scroll/laser deltas.
- Barrier-safe delta coalescing MUST only merge consecutive queued frames of the same `opcode` (`POINTER_MOVE_REL`, `SCROLL_PIXEL`, or `LASER_STATE_AND_DELTA` with identical `modsOrBtn` mode) and MUST NEVER merge across clicks, key taps, or mode changes.
- Security & Fail-Safe: reject unauthenticated `0x08` / `mac.hid.type_text`; block `mac.hid.type_text` and blind key taps when `screenLocked == true`; automatically execute `RESET_HID_STATE` (releasing held mouse drag, modifiers, and fading out laser/spotlight) on modal close, mode switch, session disconnect, or after `1.8s` without laser heartbeat.
- Gyroscope (`Sensor.TYPE_GAME_ROTATION_VECTOR`) MUST register ONLY while the user holds the Air Wand trigger or engages Double-Tap Lock, unregistering immediately on release/dispose for zero idle battery drain.

---

## File Structure

- **Shared Protocol (`shared-protocol/`)**
  - Modify: `shared-protocol/src/types.ts` — Add `FrameType.HID_INPUT = 0x08`, `HidOpcode`, `HidModifierMask`, `HidButtonCode`, `HidButtonAction`, `HidLaserMode`, `HidSystemKey`, `HidInputFlags`, `HidInputPayload`, `MacHidTypeTextParams`, `MacHidTypeTextResult`.
  - Modify: `shared-protocol/src/framing.ts` — Add `HID_INPUT_PAYLOAD_BYTES = 12`, `pxToFixedQuarter`, `fixedQuarterToPx`, `encodeHidInputFrame`, `decodeHidInputPayload`, `tryCoalesceHidFrames`, and `P0_URGENT_CONTROL` + coalescing support in `PriorityFrameQueue`.
  - Create: `shared-protocol/tests/hid-protocol.test.ts` — Unit tests for binary encoding/decoding, quarter-pixel saturation, and barrier-preserving queue coalescing.
- **macOS Native Engine (`mac-host/mac-ctl/`)**
  - Create: `mac-host/mac-ctl/HidMathAndScreenBounds.swift` — Pure multi-monitor coordinate clamping, quarter-pixel conversion, and laser trail point pruning math.
  - Create: `mac-host/mac-ctl/LaserOverlayController.swift` — Borderless `.screenSaver`-level click-through `NSPanel` per `NSScreen` rendering red laser core + 180ms fading glow trail and `even-odd` spotlight mask + `1.8s` dead-man watchdog.
  - Modify: `mac-host/mac-ctl/main.swift` — Add persistent multi-line NDJSON socket loop (`"noreply": true` fast path) and `HidEventEngine` (`CGEvent` move/drag/click/pixel-scroll/key-tap/UTF-16 `type-text`, `NX_KEYTYPE_*` hardware media/brightness keys, `reset-hid`).
  - Modify: `mac-host/package.json` — Update `build:ctl` to compile `HidMathAndScreenBounds.swift`, `LaserOverlayController.swift`, and `main.swift` into `bin/oms-mac-ctl`.
  - Create: `mac-host/tests/swift/HidMathAndScreenBoundsTests.swift` — Standalone Swift test binary verifying multi-monitor boundary clamping and trail pruning.
- **macOS Daemon (`mac-host/src/`)**
  - Modify: `mac-host/src/daemon/mac-os-controller.ts` — Bump `CFBundleVersion` to `66`, add persistent `AF_UNIX` client socket (`sendFastHelperCommand`), `handleHidInputFrame`, `typeText`, and `resetHidState` (plus `OMS_TEST_ISOLATION=1` trace recorder for deterministic unit tests).
  - Modify: `mac-host/src/transport/session-manager.ts` — Handle `FrameType.HID_INPUT` in `dispatchBusinessFrame` and emit `"hidInput"`.
  - Modify: `mac-host/src/daemon/oms-daemon.ts` — Wire `"hidInput"`, `"mac.hid.type_text"`, and disconnect/revoke `resetHidState()` cleanup.
  - Create: `mac-host/tests/mac-hid-control.test.ts` — Bun integration tests for `0x08` routing, lockscreen guard, `mac.hid.type_text`, and disconnect dead-man reset.
- **Android Protocol, Physics & UI (`android-app/`)**
  - Modify: `android-app/app/src/main/java/com/oms/client/transport/OmsFrameProtocol.kt` — Add `TYPE_HID_INPUT = 0x08`, `HID_INPUT_PAYLOAD_BYTES = 12`, and `encodeHidInput(...)`.
  - Modify: `android-app/app/src/main/java/com/oms/client/transport/OmsTransportClient.kt` — Add `sendHidFrame` with BLE queue in-place delta coalescing.
  - Create: `android-app/app/src/main/java/com/oms/client/cockpit/MacHidProtocolAndCoalescer.kt` — Constants (`HidOpcode`, `HidModifierMask`, `HidLaserMode`, `HidSystemKey`, `MacKeyCodes`), packet builder, and tier-adaptive `MacHidCoalescingBuffer`.
  - Create: `android-app/app/src/main/java/com/oms/client/cockpit/MacMotionAndTrackpadEngine.kt` — Non-linear pointer acceleration curve, inertial momentum scroller with instant touch-brake, and gyroscope `TYPE_GAME_ROTATION_VECTOR` angular velocity integrator with `0.018 rad/s` tremor deadzone.
  - Modify: `android-app/app/src/main/java/com/oms/client/cockpit/CockpitRuntimeCoordinator.kt` — Expose `sendMacHidPacket`, `flushMacHidNow`, `typeTextToMac`, `resetMacHidState`, and `vibrateLightTick`.
  - Create: `android-app/app/src/test/java/com/oms/client/cockpit/MacHidAndMotionTest.kt` — JUnit4 unit tests for binary framing, coalescing barriers, pointer curve, momentum brake, and gyro deadzone.
  - Create: `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacTrackpadAndLaserViews.kt` — 120Hz `MacTrackpadSurfaceView` (1/2/3-finger gestures + Drag Lock + quick voice/text bar) and `MacLaserAirWandView` (Hold-to-Point / Double-Tap Lock trigger, sensor binding, 500ms keepalive, laser/spotlight selector, presenter buttons).
  - Create: `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacKeyboardAndVoiceDeckView.kt` — Lossless Unicode input box, Hold-to-Speak voice dictation to Mac (`VoiceRecorderHelper` + `transcribeAudioFile`), and tactile shortcut/D-pad/media/brightness deck.
  - Modify: `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacRemoteControlModal.kt` — 4-mode `OmsSlidingSegmentedBar` (`触控板 · 激光飞鼠 · 键盘语音 · 系统应用`), lockscreen banner, hardware volume key interception (`KEYCODE_VOLUME_UP/DOWN`), and `DisposableEffect` `resetMacHidState()`.

---

### Task 1: Shared Binary HID Frame Protocol (`0x08`) & Priority Coalescing

**Files:**
- Modify: `shared-protocol/src/types.ts`
- Modify: `shared-protocol/src/framing.ts`
- Test: `shared-protocol/tests/hid-protocol.test.ts`

**Interfaces:**
- Consumes: `OMS_MAGIC_BYTE`, `OMS_PROTOCOL_VERSION`, `encodeFrame`, `FrameStreamDecoder`, `PriorityFrameQueue`
- Produces:
  - `FrameType.HID_INPUT = 0x08`
  - `HidOpcode`, `HidModifierMask`, `HidButtonCode`, `HidButtonAction`, `HidLaserMode`, `HidSystemKey`, `HidInputFlags`, `HidInputPayload`, `MacHidTypeTextParams`, `MacHidTypeTextResult`
  - `HID_INPUT_PAYLOAD_BYTES = 12`
  - `pxToFixedQuarter(px: number): number`, `fixedQuarterToPx(raw: number): number`
  - `encodeHidInputFrame(input: HidInputPayload): Uint8Array`
  - `decodeHidInputPayload(payload: Uint8Array): HidInputPayload`
  - `tryCoalesceHidFrames(prevFrame: Uint8Array, nextFrame: Uint8Array): Uint8Array | null`

- [ ] **Step 1: Write the failing test in `shared-protocol/tests/hid-protocol.test.ts`**

```typescript
import { describe, expect, test } from "bun:test";
import {
  FramePriority,
  FrameStreamDecoder,
  FrameType,
  HidButtonAction,
  HidButtonCode,
  HidInputFlags,
  HidLaserMode,
  HidModifierMask,
  HidOpcode,
  PriorityFrameQueue,
  decodeHidInputPayload,
  encodeHidInputFrame,
  encodeStreamChunkFrame,
  fixedQuarterToPx,
  pxToFixedQuarter,
  tryCoalesceHidFrames,
} from "../src/index.js";

describe("HID_INPUT (0x08) Binary Framing & Coalescing", () => {
  test("encodes and decodes 12-byte HID_INPUT payload inside 20-byte OMS frame", () => {
    const raw = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: HidModifierMask.COMMAND | HidModifierMask.SHIFT,
      dx: pxToFixedQuarter(12.5),
      dy: pxToFixedQuarter(-3.25),
      aux: 0,
      seq: 65535,
      flags: HidInputFlags.NONE,
    });
    expect(raw.byteLength).toBe(20);

    const decodedFrames = new FrameStreamDecoder().push(raw);
    expect(decodedFrames.length).toBe(1);
    expect(decodedFrames[0].frameType).toBe(FrameType.HID_INPUT);

    const hid = decodeHidInputPayload(decodedFrames[0].payload);
    expect(hid.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(hid.modsOrBtn).toBe(HidModifierMask.COMMAND | HidModifierMask.SHIFT);
    expect(fixedQuarterToPx(hid.dx)).toBe(12.5);
    expect(fixedQuarterToPx(hid.dy)).toBe(-3.25);
    expect(hid.seq).toBe(65535);
  });

  test("clamps quarter-pixel fixed-point values within int16 bounds", () => {
    expect(pxToFixedQuarter(99999)).toBe(32767);
    expect(pxToFixedQuarter(-99999)).toBe(-32768);
  });

  test("coalesces consecutive delta frames in PriorityFrameQueue without crossing click barriers", () => {
    const q = new PriorityFrameQueue();
    const bulk = encodeStreamChunkFrame("018f3b2c-4d5e-7000-8000-010203040506", 0, new Uint8Array(64));
    q.enqueue(bulk, FrameType.STREAM_CHUNK);

    const move1 = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: 0,
      dx: 16,
      dy: -8,
      aux: 0,
      seq: 1,
      flags: 0,
    });
    const move2 = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: 0,
      dx: 24,
      dy: 12,
      aux: 0,
      seq: 2,
      flags: 0,
    });
    const click = encodeHidInputFrame({
      opcode: HidOpcode.MOUSE_BUTTON,
      modsOrBtn: HidButtonCode.LEFT,
      dx: HidButtonAction.CLICK,
      dy: 0,
      aux: 0,
      seq: 3,
      flags: 0,
    });
    const moveAfterClick = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: 0,
      dx: 8,
      dy: 8,
      aux: 0,
      seq: 4,
      flags: 0,
    });

    q.enqueue(move1, FrameType.HID_INPUT);
    q.enqueue(move2, FrameType.HID_INPUT);
    q.enqueue(click, FrameType.HID_INPUT);
    q.enqueue(moveAfterClick, FrameType.HID_INPUT);

    // P0 HID frames dequeue ahead of P2 bulk data, and move1+move2 merged into 1 frame!
    expect(PriorityFrameQueue.priorityForType(FrameType.HID_INPUT)).toBe(FramePriority.P0_URGENT_CONTROL);

    const first = new FrameStreamDecoder().push(q.dequeue()!)[0];
    const firstHid = decodeHidInputPayload(first.payload);
    expect(firstHid.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(firstHid.dx).toBe(40);
    expect(firstHid.dy).toBe(4);
    expect(firstHid.seq).toBe(2);

    const second = decodeHidInputPayload(new FrameStreamDecoder().push(q.dequeue()!)[0].payload);
    expect(second.opcode).toBe(HidOpcode.MOUSE_BUTTON);

    const third = decodeHidInputPayload(new FrameStreamDecoder().push(q.dequeue()!)[0].payload);
    expect(third.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(third.dx).toBe(8);

    const fourth = new FrameStreamDecoder().push(q.dequeue()!)[0];
    expect(fourth.frameType).toBe(FrameType.STREAM_CHUNK);

    expect(tryCoalesceHidFrames(move1, click)).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test shared-protocol/tests/hid-protocol.test.ts`
Expected: FAIL with missing exports (`HidOpcode`, `encodeHidInputFrame`, etc.).

- [ ] **Step 3: Implement HID types in `shared-protocol/src/types.ts` and binary codec/coalescer in `shared-protocol/src/framing.ts`**

In `shared-protocol/src/types.ts`, update `FrameType` (`0x08`) and append HID contracts at the bottom:

```typescript
export enum FrameType {
  CONTROL_JSON_RPC = 0x01,
  STREAM_START = 0x02,
  STREAM_CHUNK = 0x03,
  STREAM_ACK_RESUME = 0x04,
  PING = 0x05,
  PONG = 0x06,
  SCREEN_FRAME = 0x07,
  HID_INPUT = 0x08,
  SECURE_RECORD = 0x7e,
}
```

Append at end of `shared-protocol/src/types.ts`:

```typescript
/**
 * v3.7: High-Frequency Binary Mac HID Remote & Laser Spotlight Contracts (FrameType.HID_INPUT = 0x08)
 */
export enum HidOpcode {
  POINTER_MOVE_REL = 0x01,
  MOUSE_BUTTON = 0x02,
  SCROLL_PIXEL = 0x03,
  KEY_TAP = 0x04,
  SYSTEM_DEFINED_KEY = 0x05,
  LASER_STATE_AND_DELTA = 0x06,
  RESET_HID_STATE = 0x0f,
}

export enum HidModifierMask {
  NONE = 0x00,
  COMMAND = 0x01,
  SHIFT = 0x02,
  OPTION = 0x04,
  CONTROL = 0x08,
}

export enum HidButtonCode {
  LEFT = 0x01,
  RIGHT = 0x02,
  MIDDLE = 0x03,
}

export enum HidButtonAction {
  CLICK = 1,
  DOUBLE_CLICK = 2,
  DOWN = 3,
  UP = 4,
  DRAG_LOCK_TOGGLE = 5,
}

export enum HidLaserMode {
  OFF = 0,
  RED_LASER = 1,
  SPOTLIGHT = 2,
}

export enum HidSystemKey {
  SOUND_UP = 0,
  SOUND_DOWN = 1,
  BRIGHTNESS_UP = 2,
  BRIGHTNESS_DOWN = 3,
  MUTE = 7,
  PLAY_PAUSE = 16,
  NEXT_TRACK = 17,
  PREV_TRACK = 18,
}

export enum HidInputFlags {
  NONE = 0x0000,
  MOMENTUM_SCROLL = 0x0001,
  LASER_KEEPALIVE = 0x0002,
  NATURAL_SCROLL = 0x0004,
}

export interface HidInputPayload {
  opcode: HidOpcode | number;
  modsOrBtn: number;
  dx: number;
  dy: number;
  aux: number;
  seq: number;
  flags: number;
}

export interface MacHidTypeTextParams {
  text: string;
  submitReturn?: boolean;
}

export interface MacHidTypeTextResult {
  ok: boolean;
  typedChars: number;
  message?: string;
}
```

In `shared-protocol/src/framing.ts`, import `HidInputPayload` and `HidOpcode`, add `HID_INPUT_PAYLOAD_BYTES = 12`, codec functions, `tryCoalesceHidFrames`, and update `PriorityFrameQueue`:

```typescript
export const HID_INPUT_PAYLOAD_BYTES = 12;

export function clampInt16(n: number): number {
  const rounded = Math.round(Number.isFinite(n) ? n : 0);
  return Math.max(-32768, Math.min(32767, rounded));
}

export function pxToFixedQuarter(px: number): number {
  return clampInt16((Number.isFinite(px) ? px : 0) * 4);
}

export function fixedQuarterToPx(raw: number): number {
  return raw * 0.25;
}

export function encodeHidInputFrame(input: HidInputPayload): Uint8Array {
  const payload = new Uint8Array(HID_INPUT_PAYLOAD_BYTES);
  const view = new DataView(payload.buffer);
  view.setUint8(0, input.opcode & 0xff);
  view.setUint8(1, input.modsOrBtn & 0xff);
  view.setInt16(2, clampInt16(input.dx), false);
  view.setInt16(4, clampInt16(input.dy), false);
  view.setInt16(6, clampInt16(input.aux), false);
  view.setUint16(8, input.seq & 0xffff, false);
  view.setUint16(10, input.flags & 0xffff, false);
  return encodeFrame(FrameType.HID_INPUT, payload);
}

export function decodeHidInputPayload(payload: Uint8Array): HidInputPayload {
  if (payload.byteLength < HID_INPUT_PAYLOAD_BYTES) {
    throw new Error(`HID_INPUT payload too short: ${payload.byteLength}`);
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    opcode: view.getUint8(0),
    modsOrBtn: view.getUint8(1),
    dx: view.getInt16(2, false),
    dy: view.getInt16(4, false),
    aux: view.getInt16(6, false),
    seq: view.getUint16(8, false),
    flags: view.getUint16(10, false),
  };
}

const COALESCEABLE_HID_OPCODES = new Set<number>([
  HidOpcode.POINTER_MOVE_REL,
  HidOpcode.SCROLL_PIXEL,
  HidOpcode.LASER_STATE_AND_DELTA,
]);

export function tryCoalesceHidFrames(
  prevFrame: Uint8Array,
  nextFrame: Uint8Array
): Uint8Array | null {
  const expectedTotal = FRAME_HEADER_BYTES + HID_INPUT_PAYLOAD_BYTES;
  if (
    prevFrame.byteLength !== expectedTotal ||
    nextFrame.byteLength !== expectedTotal ||
    prevFrame[2] !== FrameType.HID_INPUT ||
    nextFrame[2] !== FrameType.HID_INPUT
  ) {
    return null;
  }
  const prev = decodeHidInputPayload(prevFrame.subarray(FRAME_HEADER_BYTES));
  const next = decodeHidInputPayload(nextFrame.subarray(FRAME_HEADER_BYTES));
  if (
    !COALESCEABLE_HID_OPCODES.has(prev.opcode) ||
    prev.opcode !== next.opcode ||
    prev.modsOrBtn !== next.modsOrBtn
  ) {
    return null;
  }
  return encodeHidInputFrame({
    opcode: next.opcode,
    modsOrBtn: next.modsOrBtn,
    dx: clampInt16(prev.dx + next.dx),
    dy: clampInt16(prev.dy + next.dy),
    aux: next.aux,
    seq: next.seq,
    flags: prev.flags | next.flags,
  });
}
```

In `PriorityFrameQueue`:
- Add `case FrameType.HID_INPUT:` under `FramePriority.P0_URGENT_CONTROL`.
- In `enqueue(rawFrame, frameType)`, when `frameType === FrameType.HID_INPUT && q.length > 0`, check `const merged = tryCoalesceHidFrames(q[q.length - 1], rawFrame); if (merged) { q[q.length - 1] = merged; return; }` (coalescing ONLY with the tail frame `q[q.length - 1]` so barriers are strictly respected!).

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test shared-protocol/tests/hid-protocol.test.ts shared-protocol/tests/protocol.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared-protocol/src/types.ts shared-protocol/src/framing.ts shared-protocol/tests/hid-protocol.test.ts
git commit -m "feat(protocol): add 20-byte HID_INPUT (0x08) binary frame & barrier-safe coalescing"
```

---

### Task 2: macOS Native Swift HID Injector & Fullscreen Laser/Spotlight Overlay (`oms-mac-ctl`)

**Files:**
- Create: `mac-host/mac-ctl/HidMathAndScreenBounds.swift`
- Create: `mac-host/mac-ctl/LaserOverlayController.swift`
- Modify: `mac-host/mac-ctl/main.swift`
- Modify: `mac-host/package.json`
- Test: `mac-host/tests/swift/HidMathAndScreenBoundsTests.swift`

**Interfaces:**
- Consumes: NDJSON commands over `AF_UNIX` socket from `MacOsController`:
  - `{"cmd":"hid","op":1..15,"m":0,"dx":0,"dy":0,"aux":0,"fl":0,"noreply":true}`
  - `{"cmd":"type-text","text":"你好 OMS 🚀","submitReturn":true}`
  - `{"cmd":"reset-hid"}`
- Produces: Compiled `mac-host/bin/oms-mac-ctl` binary supporting persistent streaming NDJSON connections, multi-display `CGEvent` cursor/drag/scroll/shortcut/Unicode injection, `NX_KEYTYPE_*` hardware media/brightness keys, and `.screenSaver`-level click-through `LaserOverlayController`.

- [ ] **Step 1: Write the failing Swift test in `mac-host/tests/swift/HidMathAndScreenBoundsTests.swift`**

```swift
import CoreGraphics
import Foundation

@main
struct HidMathAndScreenBoundsTests {
    static func assertNear(_ a: CGFloat, _ b: CGFloat, eps: CGFloat = 0.01, _ msg: String) {
        if abs(a - b) > eps {
            fputs("FAIL: \(msg) (got \(a), expected \(b))\n", stderr)
            exit(1)
        }
    }

    static func main() {
        // 1. Quarter-pixel conversion
        assertNear(HidMath.quarterPxToPoints(10), 2.5, "quarterPxToPoints positive")
        assertNear(HidMath.quarterPxToPoints(-5), -1.25, "quarterPxToPoints negative")

        // 2. Multi-monitor clamping: Primary (0,0,1440,900) + Right External (1440,-180,2560,1440)
        let screens = [
            CGRect(x: 0, y: 0, width: 1440, height: 900),
            CGRect(x: 1440, y: -180, width: 2560, height: 1440),
        ]
        // Crossing seamlessly from primary into external display
        let across = HidMath.clampToDisplays(CGPoint(x: 1500, y: 200), displays: screens)
        assertNear(across.x, 1500, "crosses into right monitor X")
        assertNear(across.y, 200, "crosses into right monitor Y")

        // Clamping beyond far-left edge of primary
        let clampedLeft = HidMath.clampToDisplays(CGPoint(x: -40, y: 400), displays: screens)
        assertNear(clampedLeft.x, 0, "clamps left edge")
        assertNear(clampedLeft.y, 400, "preserves valid Y")

        // Clamping above primary (y = -100 is outside primary [0..900], nearest point on primary is y=0 vs external x=1440)
        let clampedTop = HidMath.clampToDisplays(CGPoint(x: 500, y: -50), displays: screens)
        assertNear(clampedTop.x, 500, "clamps top X on primary")
        assertNear(clampedTop.y, 0, "clamps top Y to 0 on primary")

        // 3. Laser trail pruning (180ms window)
        let samples: [LaserTrailSample] = [
            LaserTrailSample(point: CGPoint(x: 0, y: 0), timestampSec: 10.00),
            LaserTrailSample(point: CGPoint(x: 10, y: 10), timestampSec: 10.10),
            LaserTrailSample(point: CGPoint(x: 20, y: 20), timestampSec: 10.22),
        ]
        let pruned = HidMath.pruneTrail(samples, nowSec: 10.25, maxAgeSec: 0.18)
        if pruned.count != 2 || pruned.first?.point.x != 10 {
            fputs("FAIL: pruneTrail expected 2 recent samples\n", stderr)
            exit(1)
        }
        print("PASS: HidMathAndScreenBoundsTests")
    }
}
```

- [ ] **Step 2: Run Swift test compilation to verify it fails**

Run: `swiftc mac-host/mac-ctl/HidMathAndScreenBounds.swift mac-host/tests/swift/HidMathAndScreenBoundsTests.swift -o /tmp/oms-hid-math-test && /tmp/oms-hid-math-test`
Expected: FAIL (`HidMathAndScreenBounds.swift: No such file or directory`).

- [ ] **Step 3: Create `HidMathAndScreenBounds.swift`, `LaserOverlayController.swift`, update `mac-ctl/main.swift` and `mac-host/package.json`**

Create `mac-host/mac-ctl/HidMathAndScreenBounds.swift`:

```swift
import CoreGraphics
import Foundation

struct LaserTrailSample {
    let point: CGPoint
    let timestampSec: TimeInterval
}

enum HidMath {
    static func quarterPxToPoints(_ raw: Int) -> CGFloat {
        return CGFloat(raw) * 0.25
    }

    /// Clamp a global CoreGraphics point (top-left origin) into the nearest active display rect,
    /// allowing seamless crossing across shared edges of multi-monitor layouts.
    static func clampToDisplays(_ target: CGPoint, displays: [CGRect]) -> CGPoint {
        guard !displays.isEmpty else {
            return CGPoint(x: max(0, target.x), y: max(0, target.y))
        }
        for rect in displays {
            if target.x >= rect.minX && target.x <= rect.maxX - 1 &&
               target.y >= rect.minY && target.y <= rect.maxY - 1 {
                return target
            }
        }
        var bestPoint = target
        var bestDistSq = CGFloat.greatestFiniteMagnitude
        for rect in displays {
            let maxX = max(rect.minX, rect.maxX - 1)
            let maxY = max(rect.minY, rect.maxY - 1)
            let cx = min(max(target.x, rect.minX), maxX)
            let cy = min(max(target.y, rect.minY), maxY)
            let dx = target.x - cx
            let dy = target.y - cy
            let d2 = dx * dx + dy * dy
            if d2 < bestDistSq {
                bestDistSq = d2
                bestPoint = CGPoint(x: cx, y: cy)
            }
        }
        return bestPoint
    }

    static func pruneTrail(
        _ samples: [LaserTrailSample],
        nowSec: TimeInterval,
        maxAgeSec: TimeInterval = 0.18,
        maxPoints: Int = 36
    ) -> [LaserTrailSample] {
        let cutoff = nowSec - maxAgeSec
        let filtered = samples.filter { $0.timestampSec >= cutoff }
        if filtered.count <= maxPoints {
            return filtered
        }
        return Array(filtered.suffix(maxPoints))
    }
}
```

Create `mac-host/mac-ctl/LaserOverlayController.swift`:

```swift
import AppKit
import CoreGraphics
import Foundation
import QuartzCore

final class LaserCanvasView: NSView {
    var mode: Int = 0 // 0 = off, 1 = red laser, 2 = spotlight
    var localCursorPoint: CGPoint? = nil
    var localTrail: [LaserTrailSample] = []
    var spotlightRadius: CGFloat = 140
    var nowSec: TimeInterval = CACurrentMediaTime()

    override var isOpaque: Bool { false }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext, mode != 0 else { return }
        ctx.clear(bounds)

        if mode == 2 {
            // Spotlight: 58% dark overlay across all screens; cut out smooth circular aperture on cursor's screen
            ctx.saveGState()
            ctx.setFillColor(NSColor.black.withAlphaComponent(0.58).cgColor)
            let path = CGMutablePath()
            path.addRect(bounds)
            if let pt = localCursorPoint {
                let r = max(60, min(320, spotlightRadius))
                let hole = CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)
                path.addEllipse(in: hole)
            }
            ctx.addPath(path)
            ctx.fillPath(using: .evenOdd)

            if let pt = localCursorPoint {
                let r = max(60, min(320, spotlightRadius))
                let ring = CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)
                ctx.setStrokeColor(NSColor.white.withAlphaComponent(0.65).cgColor)
                ctx.setLineWidth(2.2)
                ctx.strokeEllipse(in: ring)
            }
            ctx.restoreGState()
            return
        }

        // Mode 1: Red Laser Dot + 180ms Fading Glow Trail
        if localTrail.count >= 2 {
            for i in 1..<localTrail.count {
                let prev = localTrail[i - 1]
                let curr = localTrail[i]
                let age = max(0, nowSec - curr.timestampSec)
                let progress = max(0, 1.0 - (age / 0.18))
                guard progress > 0.02 else { continue }

                ctx.saveGState()
                ctx.setLineCap(.round)
                ctx.setLineWidth(CGFloat(3.0 + 7.0 * progress))
                ctx.setStrokeColor(NSColor(calibratedRed: 1.0, green: 0.14, blue: 0.18, alpha: CGFloat(0.45 * progress)).cgColor)
                ctx.move(to: prev.point)
                ctx.addLine(to: curr.point)
                ctx.strokePath()
                ctx.restoreGState()
            }
        }

        if let pt = localCursorPoint {
            ctx.saveGState()
            // Outer soft red halo
            let haloR: CGFloat = 18
            ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.10, blue: 0.14, alpha: 0.28).cgColor)
            ctx.fillEllipse(in: CGRect(x: pt.x - haloR, y: pt.y - haloR, width: haloR * 2, height: haloR * 2))

            // Mid vivid red body
            let midR: CGFloat = 9.5
            ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.12, blue: 0.16, alpha: 0.92).cgColor)
            ctx.fillEllipse(in: CGRect(x: pt.x - midR, y: pt.y - midR, width: midR * 2, height: midR * 2))

            // Hot white-pink core
            let coreR: CGFloat = 4.2
            ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.94, blue: 0.94, alpha: 1.0).cgColor)
            ctx.fillEllipse(in: CGRect(x: pt.x - coreR, y: pt.y - coreR, width: coreR * 2, height: coreR * 2))
            ctx.restoreGState()
        }
    }
}

final class LaserOverlayController {
    static let shared = LaserOverlayController()

    private struct ScreenWindowEntry {
        let panel: NSPanel
        let canvas: LaserCanvasView
        let cgBounds: CGRect
        let nsFrame: NSRect
    }

    private var entries: [ScreenWindowEntry] = []
    private var mode: Int = 0
    private var spotlightRadius: CGFloat = 140
    private var globalPoint: CGPoint = .zero
    private var globalTrail: [LaserTrailSample] = []
    private var lastHeartbeatSec: TimeInterval = 0
    private var renderTimer: Timer? = nil
    private let watchdogTimeoutSec: TimeInterval = 1.8

    private init() {
        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.rebuildWindowsOnMain()
        }
    }

    static func currentGlobalDisplayRects() -> [CGRect] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else {
            return []
        }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else {
            return []
        }
        return ids.prefix(Int(count)).map { CGDisplayBounds($0) }
    }

    func updateOnMain(mode: Int, globalCursor: CGPoint, spotlightRadius: CGFloat) {
        assert(Thread.isMainThread)
        let now = CACurrentMediaTime()
        if mode <= 0 {
            hideImmediateOnMain()
            return
        }
        self.mode = mode
        if spotlightRadius >= 60 {
            self.spotlightRadius = min(320, spotlightRadius)
        }
        self.globalPoint = globalCursor
        self.lastHeartbeatSec = now
        if mode == 1 {
            self.globalTrail.append(LaserTrailSample(point: globalCursor, timestampSec: now))
            self.globalTrail = HidMath.pruneTrail(self.globalTrail, nowSec: now)
        } else {
            self.globalTrail.removeAll(keepingCapacity: true)
        }

        if entries.isEmpty {
            rebuildWindowsOnMain()
        }
        for e in entries {
            e.panel.alphaValue = 1.0
            if !e.panel.isVisible {
                e.panel.orderFrontRegardless()
            }
        }
        ensureTimerRunningOnMain()
        renderFrameOnMain(nowSec: now)
    }

    func hideImmediateOnMain() {
        assert(Thread.isMainThread)
        mode = 0
        globalTrail.removeAll(keepingCapacity: true)
        renderTimer?.invalidate()
        renderTimer = nil
        for e in entries {
            e.canvas.mode = 0
            e.canvas.needsDisplay = true
            e.panel.orderOut(nil)
        }
    }

    private func rebuildWindowsOnMain() {
        for e in entries {
            e.panel.orderOut(nil)
            e.panel.close()
        }
        entries.removeAll()

        let screens = NSScreen.screens
        guard let primaryHeight = screens.first?.frame.height else { return }

        for screen in screens {
            let nsFrame = screen.frame
            // Convert AppKit bottom-left screen frame to CoreGraphics top-left global bounds
            let cgY = primaryHeight - nsFrame.maxY
            let cgBounds = CGRect(x: nsFrame.minX, y: cgY, width: nsFrame.width, height: nsFrame.height)

            let panel = NSPanel(
                contentRect: nsFrame,
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            panel.level = .screenSaver
            panel.backgroundColor = .clear
            panel.isOpaque = false
            panel.hasShadow = false
            panel.ignoresMouseEvents = true
            panel.hidesOnDeactivate = false
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]

            let canvas = LaserCanvasView(frame: NSRect(origin: .zero, size: nsFrame.size))
            canvas.autoresizingMask = [.width, .height]
            panel.contentView = canvas

            entries.append(ScreenWindowEntry(panel: panel, canvas: canvas, cgBounds: cgBounds, nsFrame: nsFrame))
        }
    }

    private func ensureTimerRunningOnMain() {
        guard renderTimer == nil else { return }
        let timer = Timer(timeInterval: 1.0 / 90.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let now = CACurrentMediaTime()
            if now - self.lastHeartbeatSec > self.watchdogTimeoutSec {
                self.hideImmediateOnMain()
                return
            }
            if self.mode == 1 {
                self.globalTrail = HidMath.pruneTrail(self.globalTrail, nowSec: now)
            }
            self.renderFrameOnMain(nowSec: now)
        }
        RunLoop.main.add(timer, forMode: .common)
        renderTimer = timer
    }

    private func renderFrameOnMain(nowSec: TimeInterval) {
        for e in entries {
            e.canvas.mode = mode
            e.canvas.spotlightRadius = spotlightRadius
            e.canvas.nowSec = nowSec

            if e.cgBounds.contains(globalPoint) {
                let lx = globalPoint.x - e.cgBounds.minX
                let ly = e.cgBounds.height - (globalPoint.y - e.cgBounds.minY)
                e.canvas.localCursorPoint = CGPoint(x: lx, y: ly)
            } else {
                e.canvas.localCursorPoint = nil
            }

            if mode == 1 && !globalTrail.isEmpty {
                e.canvas.localTrail = globalTrail.compactMap { sample in
                    let lx = sample.point.x - e.cgBounds.minX
                    let ly = e.cgBounds.height - (sample.point.y - e.cgBounds.minY)
                    if lx >= -32 && lx <= e.cgBounds.width + 32 && ly >= -32 && ly <= e.cgBounds.height + 32 {
                        return LaserTrailSample(point: CGPoint(x: lx, y: ly), timestampSec: sample.timestampSec)
                    }
                    return nil
                }
            } else {
                e.canvas.localTrail = []
            }
            e.canvas.needsDisplay = true
        }
    }
}
```

In `mac-host/mac-ctl/main.swift`:
1. Add `HidEventEngine` encapsulating:
   - Virtual floating-point cursor `(virtualPos: CGPoint?)` synced from `CGEvent(source: nil)?.location` when idle >350ms, clamped via `HidMath.clampToDisplays(..., displays: LaserOverlayController.currentGlobalDisplayRects())`.
   - `leftButtonDown: Bool` state so `POINTER_MOVE_REL (0x01)` posts `.leftMouseDragged` when `leftButtonDown == true` else `.mouseMoved`, and `CGWarpMouseCursorPosition` is NOT needed because `.mouseMoved` / `.leftMouseDragged` posted to `.cghidEventTap` natively moves the macOS system cursor and triggers hover events!
   - `MOUSE_BUTTON (0x02)`: `CLICK`, `DOUBLE_CLICK` (`mouseEventClickState = 1` then `2`), `DOWN`, `UP`, `DRAG_LOCK_TOGGLE`.
   - `SCROLL_PIXEL (0x03)`: `CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 2, wheel1: Int32(dyPx), wheel2: Int32(dxPx), wheel3: 0)`.
   - `KEY_TAP (0x04)`: `CGEvent` keyDown + keyUp with `CGEventFlags` decoded from `modsOrBtn` (`bit0:.maskCommand`, `bit1:.maskShift`, `bit2:.maskAlternate`, `bit3:.maskControl`).
   - `SYSTEM_DEFINED_KEY (0x05)`: `NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: NSEvent.ModifierFlags(rawValue: 0xa00), timestamp: 0, windowNumber: 0, context: nil, subtype: 8, data1: (keyType << 16) | (0x0a << 8), data2: -1)?.cgEvent?.post(tap: .cghidEventTap)` followed by release `(0x0b << 8)` on `DispatchQueue.main.async`.
   - `LASER_STATE_AND_DELTA (0x06)`: updates virtual cursor by `(dx, dy)` (also moving macOS cursor silently or keeping laser synced) and dispatches `LaserOverlayController.shared.updateOnMain(mode: modsOrBtn, globalCursor: nextPos, spotlightRadius: CGFloat(aux))` on `DispatchQueue.main.async`.
   - `RESET_HID_STATE (0x0f)` / `cmd == "reset-hid"`: releases `leftButtonDown` if true and calls `LaserOverlayController.shared.hideImmediateOnMain()`.
   - `cmd == "type-text"`: chunks `text` UTF-16 code units into slices of 16 units, posting `keyboardSetUnicodeString` down/up via `.cghidEventTap`, optionally followed by `Return (keyCode 36)` when `submitReturn == true`.
2. Upgrade `runUnixSocketDaemon` per-client loop so a persistent client connection can send newline-delimited JSON messages continuously until EOF, skipping socket write when `obj["noreply"] as? Bool == true`!

Update `mac-host/package.json` `build:ctl`:

```json
"build:ctl": "mkdir -p bin && swiftc -O mac-ctl/HidMathAndScreenBounds.swift mac-ctl/LaserOverlayController.swift mac-ctl/main.swift -framework AppKit -framework ApplicationServices -framework CoreGraphics -framework QuartzCore -o bin/oms-mac-ctl"
```

- [ ] **Step 4: Run Swift unit test & build `bin/oms-mac-ctl` to verify both pass**

Run: `swiftc mac-host/mac-ctl/HidMathAndScreenBounds.swift mac-host/tests/swift/HidMathAndScreenBoundsTests.swift -o /tmp/oms-hid-math-test && /tmp/oms-hid-math-test && (cd mac-host && bun run build:ctl)`
Expected: `PASS: HidMathAndScreenBoundsTests` and `mac-host/bin/oms-mac-ctl` compiled with exit code 0.

- [ ] **Step 5: Commit**

```bash
git add mac-host/mac-ctl/HidMathAndScreenBounds.swift mac-host/mac-ctl/LaserOverlayController.swift mac-host/mac-ctl/main.swift mac-host/package.json mac-host/tests/swift/HidMathAndScreenBoundsTests.swift
git commit -m "feat(mac-ctl): add native CGEvent HID injector, streaming IPC & fullscreen laser/spotlight overlay"
```

---

### Task 3: Mac Daemon Persistent IPC Pipe, `0x08` Frame Routing & Dead-Man Reset

**Files:**
- Modify: `mac-host/src/daemon/mac-os-controller.ts`
- Modify: `mac-host/src/transport/session-manager.ts`
- Modify: `mac-host/src/daemon/oms-daemon.ts`
- Test: `mac-host/tests/mac-hid-control.test.ts`

**Interfaces:**
- Consumes: `FrameType.HID_INPUT`, `decodeHidInputPayload`, `HidInputPayload`, `HidOpcode`, `MacHidTypeTextParams`, `MacHidTypeTextResult` from `@oms/shared-protocol`
- Produces:
  - `MacOsController.handleHidInputFrame(hid: HidInputPayload): void`
  - `MacOsController.typeText(params: MacHidTypeTextParams): Promise<MacHidTypeTextResult>`
  - `MacOsController.resetHidState(): void`
  - `MacOsController.getTestRecordedHidEvents()` (active under `OMS_TEST_ISOLATION=1`)
  - `SessionManager` `"hidInput"` event emission for authenticated pipes

- [ ] **Step 1: Write the failing Bun test in `mac-host/tests/mac-hid-control.test.ts`**

```typescript
import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  HidLaserMode,
  HidOpcode,
  encodeHidInputFrame,
  encodeJsonRpcFrame,
  pxToFixedQuarter,
} from "@oms/shared-protocol";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { connectAuthenticatedTestPhone } from "./secure-phone-helper.js";

describe("Mac HID Binary Routing, Lockscreen Guard & Dead-Man Reset", () => {
  test("routes authenticated 0x08 HID frames, blocks blind typing when locked, and resets HID on disconnect", async () => {
    process.env.OMS_TEST_ISOLATION = "1";
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-hid-test-"));
    const port = 29400 + Math.floor(Math.random() * 400);
    const daemon = new OmsDaemon({ dataDir: tmpDir, lanPort: port, disableBle: true, disableHud: true });
    await daemon.start();

    try {
      const phone = await connectAuthenticatedTestPhone(daemon, port);

      // 1. Unlocked: send 0x08 pointer move & laser spotlight frame
      phone.sendEncryptedInner(
        encodeHidInputFrame({
          opcode: HidOpcode.POINTER_MOVE_REL,
          modsOrBtn: 0,
          dx: pxToFixedQuarter(15),
          dy: pxToFixedQuarter(-5),
          aux: 0,
          seq: 1,
          flags: 0,
        })
      );
      phone.sendEncryptedInner(
        encodeHidInputFrame({
          opcode: HidOpcode.LASER_STATE_AND_DELTA,
          modsOrBtn: HidLaserMode.SPOTLIGHT,
          dx: 0,
          dy: 0,
          aux: 160,
          seq: 2,
          flags: 0,
        })
      );

      // 2. Unlocked: type lossless Chinese + Emoji via mac.hid.type_text
      const typeResp = await phone.callRpc("mac.hid.type_text", {
        text: "你好 Mac 遥控器 🎯",
        submitReturn: true,
      });
      expect(typeResp.result?.ok).toBe(true);

      const recordedBeforeLock = (daemon as any).macControl.getTestRecordedHidEvents();
      expect(recordedBeforeLock.some((e: any) => e.type === "hid" && e.op === HidOpcode.POINTER_MOVE_REL)).toBe(true);
      expect(recordedBeforeLock.some((e: any) => e.type === "type-text" && e.text === "你好 Mac 遥控器 🎯")).toBe(true);

      // 3. Lock screen -> blind mac.hid.type_text and KEY_TAP must be blocked!
      await phone.callRpc("mac.control.execute", { action: "lock" });
      const blockedType = await phone.callRpc("mac.hid.type_text", { text: "wrong-pwd" });
      expect(blockedType.result?.ok).toBe(false);

      // 4. Disconnect phone -> triggers automatic resetHidState() dead-man switch!
      const resetsBefore = recordedBeforeLock.filter((e: any) => e.type === "reset-hid").length;
      phone.close();
      await new Promise((r) => setTimeout(r, 120));
      const recordedAfterClose = (daemon as any).macControl.getTestRecordedHidEvents();
      const resetsAfter = recordedAfterClose.filter((e: any) => e.type === "reset-hid").length;
      expect(resetsAfter).toBeGreaterThan(resetsBefore);
    } finally {
      await daemon.stop();
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});
```

*(Note: verify exact helper export in `mac-host/tests/secure-phone-helper.ts` when implementing Step 1 and match its exact method signatures.)*

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test mac-host/tests/mac-hid-control.test.ts`
Expected: FAIL (`Unsupported authenticated frame` / missing `handleHidInputFrame`).

- [ ] **Step 3: Implement persistent Unix socket pipe & HID routing in `mac-os-controller.ts`, `session-manager.ts`, and `oms-daemon.ts`**

1. In `mac-host/src/daemon/mac-os-controller.ts`:
   - Bump `CFBundleShortVersionString` to `3.6.8` and `CFBundleVersion` to `66` so `ensureHelperAppBundleInstalled()` automatically upgrades `~/Applications/青简控机助手.app` and restarts any stale `oms-mac-ctl` daemon.
   - Add persistent streaming `net.Socket` (`private fastHelperSocket: net.Socket | null = null`, `private isLaunchingFastHelper = false`) with `writeFastHelperLine(payload: Record<string, unknown>): void` that writes `JSON.stringify({ ...payload, noreply: true }) + "\n"` in `<0.1ms` if connected, or asynchronously spawns `青简控机助手.app` and queues the latest state.
   - Add `handleHidInputFrame(hid: HidInputPayload): void`:
     - Under `this.isTestIsolation`: if `this.testMockLocked && hid.opcode !== HidOpcode.RESET_HID_STATE`, ignore non-reset input; otherwise push `{ type: "hid", op: hid.opcode, m: hid.modsOrBtn, dx: hid.dx, dy: hid.dy, aux: hid.aux, fl: hid.flags }` to `this.testHidEvents` (capped at 256).
     - In production: if `this.lastKnownNativeStatus?.screenLocked && Date.now() - this.lastKnownNativeStatus.atMs < 5000 && hid.opcode === HidOpcode.KEY_TAP`, drop blind key taps while locked; otherwise call `this.writeFastHelperLine({ cmd: "hid", op: hid.opcode, m: hid.modsOrBtn, dx: hid.dx, dy: hid.dy, aux: hid.aux, fl: hid.flags })`.
   - Add `async typeText(params: MacHidTypeTextParams): Promise<MacHidTypeTextResult>`:
     - Reject empty text; check `probeNativeStatusAsync()` -> if `screenLocked`, return `{ ok: false, typedChars: 0, message: "Mac 当前处于锁屏状态，请先点击「远程解锁」后再输入文字" }`.
     - Under `isTestIsolation`, record `{ type: "type-text", text: params.text, submitReturn: Boolean(params.submitReturn) }` and return `{ ok: true, typedChars: Array.from(params.text).length }`.
     - In production, call `await this.callHelperAppDaemon({ cmd: "type-text", text: params.text, submitReturn: Boolean(params.submitReturn) }, 6000)`.
   - Add `resetHidState(): void`:
     - Under `isTestIsolation`, push `{ type: "reset-hid" }`.
     - In production, call `this.writeFastHelperLine({ cmd: "reset-hid" })`.
   - Also replace slow `osascript` in `media_play_pause` with native `HidSystemKey.PLAY_PAUSE` via helper when available!

2. In `mac-host/src/transport/session-manager.ts` (`dispatchBusinessFrame`):
   ```typescript
   case FrameType.HID_INPUT: {
     const hid = decodeHidInputPayload(frame.payload);
     this.emit("hidInput", hid, {
       tier: pipe.tier,
       connectionId: pipe.diagnosticId,
       phoneId: pipe.phoneId,
     });
     break;
   }
   ```

3. In `mac-host/src/daemon/oms-daemon.ts`:
   - In `wireSessionEvents()`:
     - `this.session.on("hidInput", (hid: HidInputPayload) => { this.macControl.handleHidInputFrame(hid); });`
     - `this.session.on("tierChanged", (evt: { previous: string; current: string }) => { if (evt.current === "DISCONNECTED") this.macControl.resetHidState(); });`
     - In `this.session.on("sessionRevoked", ...)`, also call `this.macControl.resetHidState();`.
   - In `phoneRequest` & local IPC switch:
     - Handle `req.method === "mac.hid.type_text"` -> `this.macControl.typeText(req.params as MacHidTypeTextParams)`.

- [ ] **Step 4: Run tests and TypeScript check to verify everything passes**

Run: `(cd mac-host && bun run build) && bun test mac-host/tests/mac-hid-control.test.ts mac-host/tests/control-boundary.test.ts`
Expected: PASS with 0 TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add mac-host/src/daemon/mac-os-controller.ts mac-host/src/transport/session-manager.ts mac-host/src/daemon/oms-daemon.ts mac-host/tests/mac-hid-control.test.ts
git commit -m "feat(mac-host): wire persistent HID IPC pipe, 0x08 routing, type_text & disconnect dead-man reset"
```

---

### Task 4: Android Binary HID Protocol, Coalescer & Trackpad/Gyro Physics Engine

**Files:**
- Modify: `android-app/app/src/main/java/com/oms/client/transport/OmsFrameProtocol.kt`
- Modify: `android-app/app/src/main/java/com/oms/client/transport/OmsTransportClient.kt`
- Create: `android-app/app/src/main/java/com/oms/client/cockpit/MacHidProtocolAndCoalescer.kt`
- Create: `android-app/app/src/main/java/com/oms/client/cockpit/MacMotionAndTrackpadEngine.kt`
- Modify: `android-app/app/src/main/java/com/oms/client/cockpit/CockpitRuntimeCoordinator.kt`
- Test: `android-app/app/src/test/java/com/oms/client/cockpit/MacHidAndMotionTest.kt`

**Interfaces:**
- Consumes: `OmsFrameProtocol.encodeFrame`, `OmsTransportClient.sendRawFrame` / `callHostRpc`
- Produces:
  - `OmsFrameProtocol.TYPE_HID_INPUT = 0x08`, `OmsFrameProtocol.encodeHidInput(...)`
  - `MacHidPacket`, `HidOpcode`, `HidModifierMask`, `HidButtonCode`, `HidButtonAction`, `HidLaserMode`, `HidSystemKey`, `MacKeyCodes`, `MacHidCoalescingBuffer`
  - `TrackpadPhysicsEngine` (non-linear acceleration curve + momentum decay step + touch brake)
  - `AirWandGyroIntegrator` (quaternion delta -> yaw/pitch rad/s -> deadzone filter -> screen px delta, zero-drift re-center on press)
  - `CockpitRuntimeCoordinator`: `sendMacHid(packet: MacHidPacket, flushImmediate: Boolean = false)`, `typeTextToMac(text: String, submitReturn: Boolean): Pair<Boolean, String>`, `resetMacHidState()`, `vibrateLightTick()`

- [ ] **Step 1: Write the failing JUnit4 test in `android-app/app/src/test/java/com/oms/client/cockpit/MacHidAndMotionTest.kt`**

```kotlin
package com.oms.client.cockpit

import com.oms.client.transport.OmsFrameProtocol
import com.oms.client.transport.OmsFrameStreamDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MacHidAndMotionTest {

    @Test
    fun `encodes exact 20-byte Big-Endian 0x08 HID frame matching TypeScript spec`() {
        val raw = OmsFrameProtocol.encodeHidInput(
            opcode = MacHidOpcode.POINTER_MOVE_REL,
            modsOrBtn = MacHidModMask.COMMAND,
            dx = MacHidFixedPoint.pxToQuarter(10.5f),
            dy = MacHidFixedPoint.pxToQuarter(-2.25f),
            aux = 140,
            seq = 42,
            flags = MacHidFlags.LASER_KEEPALIVE
        )
        assertEquals(20, raw.size)
        val decoded = OmsFrameStreamDecoder().push(raw).single()
        assertEquals(OmsFrameProtocol.TYPE_HID_INPUT, decoded.frameType)

        val buf = ByteBuffer.wrap(decoded.payload).order(ByteOrder.BIG_ENDIAN)
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, buf.get().toInt() and 0xFF)
        assertEquals(MacHidModMask.COMMAND, buf.get().toInt() and 0xFF)
        assertEquals(42, buf.short.toInt())   // 10.5 * 4 = 42
        assertEquals(-9, buf.short.toInt())   // -2.25 * 4 = -9
        assertEquals(140, buf.short.toInt())
        assertEquals(42, buf.short.toInt() and 0xFFFF)
        assertEquals(MacHidFlags.LASER_KEEPALIVE, buf.short.toInt() and 0xFFFF)
    }

    @Test
    fun `coalescing buffer merges consecutive moves but preserves click barriers`() {
        val buffer = MacHidCoalescingBuffer()
        buffer.offer(MacHidPacket.moveRel(4f, -2f))
        buffer.offer(MacHidPacket.moveRel(6f, 5f))
        buffer.offer(MacHidPacket.mouseButton(MacHidButton.LEFT, MacHidButtonAction.CLICK))
        buffer.offer(MacHidPacket.moveRel(2f, 2f))

        val drained = buffer.drainAll()
        assertEquals(3, drained.size)
        assertEquals(MacHidOpcode.POINTER_MOVE_REL, drained[0].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(10f), drained[0].dx)
        assertEquals(MacHidFixedPoint.pxToQuarter(3f), drained[0].dy)
        assertEquals(MacHidOpcode.MOUSE_BUTTON, drained[1].opcode)
        assertEquals(MacHidFixedPoint.pxToQuarter(2f), drained[2].dx)
    }

    @Test
    fun `trackpad acceleration scales gently for micro-motion and rapidly for fast flicks`() {
        val slow = TrackpadPhysicsEngine.accelerateDelta(dxDp = 0.8f, dyDp = 0f, dtMs = 16L)
        val fast = TrackpadPhysicsEngine.accelerateDelta(dxDp = 24f, dyDp = 0f, dtMs = 8L)
        assertTrue("Slow motion stays fine-grained", slow.first in 0.5f..2.5f)
        assertTrue("Fast flick crosses screen rapidly", fast.first > 45f)
    }

    @Test
    fun `gyro integrator filters physiological hand tremor and maps yaw-pitch cleanly`() {
        val gyro = AirWandGyroIntegrator()
        // Identity quaternion at t=0 (trigger pressed -> auto zero)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f) // [x, y, z, w]
        assertEquals(0f to 0f, gyro.onRotationSample(q0, timestampNs = 1_000_000_000L))

        // Tiny micro-tremor (< 0.018 rad/s over 10ms -> angle < 0.00018 rad) -> filtered to 0!
        val qTiny = floatArrayOf(0.00004f, 0f, -0.00004f, 1f)
        val tremorOut = gyro.onRotationSample(qTiny, timestampNs = 1_010_000_000L)
        assertEquals(0f, tremorOut.first, 0.001f)
        assertEquals(0f, tremorOut.second, 0.001f)

        // Deliberate wrist turn right (negative Z rotation in phone frame -> positive cursor X)
        val halfAngle = -0.025f // ~0.05 rad over 10ms = 5 rad/s
        val qTurnRight = floatArrayOf(0f, 0f, kotlin.math.sin(halfAngle), kotlin.math.cos(halfAngle))
        val moveOut = gyro.onRotationSample(qTurnRight, timestampNs = 1_020_000_000L)
        assertTrue("Expected positive rightward X cursor movement, got ${moveOut.first}", moveOut.first > 5f)
    }
}
```

- [ ] **Step 2: Run Gradle unit test to verify it fails**

Run: `(cd android-app && ./gradlew :app:testDebugUnitTest --tests "com.oms.client.cockpit.MacHidAndMotionTest")`
Expected: FAIL (unresolved symbols `MacHidOpcode`, `MacHidCoalescingBuffer`, `TrackpadPhysicsEngine`, `AirWandGyroIntegrator`).

- [ ] **Step 3: Implement `OmsFrameProtocol.encodeHidInput`, `MacHidProtocolAndCoalescer.kt`, `MacMotionAndTrackpadEngine.kt`, `OmsTransportClient.sendHidFrame`, and `CockpitRuntimeCoordinator` methods**

1. In `OmsFrameProtocol.kt`:
   ```kotlin
   const val TYPE_HID_INPUT: Int = 0x08
   const val HID_INPUT_PAYLOAD_BYTES: Int = 12

   fun encodeHidInput(
       opcode: Int,
       modsOrBtn: Int,
       dx: Int,
       dy: Int,
       aux: Int = 0,
       seq: Int = 0,
       flags: Int = 0
   ): ByteArray {
       val payload = ByteBuffer.allocate(HID_INPUT_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
       payload.put((opcode and 0xFF).toByte())
       payload.put((modsOrBtn and 0xFF).toByte())
       payload.putShort(dx.coerceIn(-32768, 32767).toShort())
       payload.putShort(dy.coerceIn(-32768, 32767).toShort())
       payload.putShort(aux.coerceIn(-32768, 32767).toShort())
       payload.putShort((seq and 0xFFFF).toShort())
       payload.putShort((flags and 0xFFFF).toShort())
       return encodeFrame(TYPE_HID_INPUT, payload.array())
   }
   ```

2. Create `MacHidProtocolAndCoalescer.kt` with:
   - `MacHidOpcode`, `MacHidModMask`, `MacHidButton`, `MacHidButtonAction`, `MacHidLaserMode`, `MacHidSystemKey`, `MacHidFlags`, `MacKeyCodes` (`ESCAPE=53`, `TAB=48`, `SPACE=49`, `DELETE=51`, `RETURN=36`, `LEFT=123`, `RIGHT=124`, `DOWN=125`, `UP=126`, `ANSI_C=8`, `ANSI_V=9`, `ANSI_X=7`, `ANSI_Z=6`, `ANSI_W=13`, `ANSI_Q=12`, `ANSI_B=11`, `ANSI_F=3`, `ANSI_PLUS=24`, `ANSI_MINUS=27`).
   - `MacHidFixedPoint.pxToQuarter(px: Float): Int = (px * 4f).roundToInt().coerceIn(-32768, 32767)`.
   - `MacHidPacket` factory helpers (`moveRel`, `mouseButton`, `scrollPixel`, `keyTap`, `systemKey`, `laserUpdate`, `resetAll`) + `MacHidCoalescingBuffer` thread-safe ring/list merging consecutive `POINTER_MOVE_REL` / `SCROLL_PIXEL` / `LASER_STATE_AND_DELTA` (same `modsOrBtn`) while never merging across non-coalesceable opcodes.
   - Tier flush interval helper: `LAN_WS -> 8L`, `WAN_WEBRTC -> 16L`, `BT_RFCOMM -> 32L`.

3. Create `MacMotionAndTrackpadEngine.kt` with:
   - `TrackpadPhysicsEngine`: `accelerateDelta(dxDp, dyDp, dtMs, sensitivity = 1.0f)` using smooth power curve `gain = (1.15f + 0.34f * speedDpPerMs.pow(1.25f)).coerceIn(1.0f, 5.8f) * sensitivity`, plus `MomentumFlingState` (`stepFrame()` decaying velocity by `0.92f` until `< 0.6f`, `stopImmediate()` on touch down).
   - `AirWandGyroIntegrator`: quaternion conjugate relative rotation `dq = conj(qPrev) * qCurr`, extracting instantaneous angular velocity `(wx, wy, wz)` in device body frame (`dt` clamped to `2ms..60ms`), applying soft deadzone `0.018f` rad/s so hand tremor produces `0.0f`, and mapping `-wz -> dxPx` (yaw right = positive X) and `-wx -> dyPx` (pitch up = negative Y, i.e., cursor moves up on Mac!). `resetCalibration()` clears `prevQuat` on every trigger press/release.

4. In `CockpitRuntimeCoordinator.kt`:
   - Add `private val hidSeq = AtomicInteger(0)` and `private val hidCoalescer = MacHidCoalescingBuffer()` with an on-demand coroutine loop flushing via `transportClient.sendRawFrame(pkt.toWireFrame(hidSeq.incrementAndGet()))` at the active tier's cadence (`8ms / 16ms / 32ms`), or immediately when `flushImmediate = true`.
   - Add `suspend fun typeTextToMac(text: String, submitReturn: Boolean = false): Pair<Boolean, String>` calling `transportClient.callHostRpc("mac.hid.type_text", JSONObject().put("text", text).put("submitReturn", submitReturn), 8_000L)`.
   - Add `fun resetMacHidState()` clearing `hidCoalescer` and immediately sending `MacHidPacket.resetAll()`.
   - Add `fun vibrateLightTick()` (`EFFECT_TICK` / `10ms` subtle pulse throttled to max 1 tick per `28ms` so scrolling/laser feels crisp without buzzing continuously).

- [ ] **Step 4: Run JUnit4 test suite to verify it passes**

Run: `(cd android-app && ./gradlew :app:testDebugUnitTest --tests "com.oms.client.cockpit.MacHidAndMotionTest")`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src/main/java/com/oms/client/transport/OmsFrameProtocol.kt android-app/app/src/main/java/com/oms/client/transport/OmsTransportClient.kt android-app/app/src/main/java/com/oms/client/cockpit/MacHidProtocolAndCoalescer.kt android-app/app/src/main/java/com/oms/client/cockpit/MacMotionAndTrackpadEngine.kt android-app/app/src/main/java/com/oms/client/cockpit/CockpitRuntimeCoordinator.kt android-app/app/src/test/java/com/oms/client/cockpit/MacHidAndMotionTest.kt
git commit -m "feat(android-hid): add 0x08 HID coalescer, trackpad momentum physics & gyro air-wand integrator"
```

---

### Task 5: Android 4-Mode Mac Remote Wand UI, Multi-Touch Gestures, Voice-to-Mac & Hardware Volume Keys

**Files:**
- Create: `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacTrackpadAndLaserViews.kt`
- Create: `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacKeyboardAndVoiceDeckView.kt`
- Modify: `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacRemoteControlModal.kt`

**Interfaces:**
- Consumes: `CockpitRuntimeCoordinator` (`sendMacHid`, `typeTextToMac`, `resetMacHidState`, `vibrateLightTick`, `vibrateConfirm`, `aiServices.transcribeAudioFile`), `VoiceRecorderHelper`, `OmsSlidingSegmentedBar`, `OmsSurfaceCard`, `OmsIcons`
- Produces: Complete 4-mode `MacRemoteControlModal` (`TRACKPAD`, `LASER_WAND`, `KEYBOARD_VOICE`, `SYSTEM_APPS`)

- [ ] **Step 1: Create `MacTrackpadAndLaserViews.kt` (Trackpad Surface + Air Wand & Laser/Spotlight Controller)**

Implement two focused composables in `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacTrackpadAndLaserViews.kt`:
1. `MacTrackpadModeView`:
   - Large edge-to-edge tactile surface using `Modifier.pointerInput(macConnected, sensitivity)` (`awaitEachGesture`) tracking 1, 2, and 3 pointers simultaneously:
     - Touch-down immediately stops any active `MomentumFlingState` (`brake`).
     - **1 finger**: nonlinear accelerated `MacHidPacket.moveRel`; quick tap (`< 210ms`, `< 10.dp` travel) -> `LEFT CLICK` (or if a second tap begins within `260ms` and drags -> `LEFT DOWN` double-tap-drag, sending `LEFT UP` on release!).
     - **2 fingers**: quick tap -> `RIGHT CLICK`; pan -> `MacHidPacket.scrollPixel` (natural direction by default) + velocity tracker launching 60fps `MomentumFlingState` coroutine on lift; pinch zoom ratio delta (`> 1.12f` or `< 0.89f`) -> `⌘+` / `⌘-` with haptic tick!
     - **3 fingers**: cumulative swipe threshold (`42.dp`) -> Up = Mission Control (`⌃↑`), Down = App Exposé (`⌃↓`), Left/Right = switch Spaces (`⌃→` / `⌃←`) with `vibrateConfirm()`.
   - Bottom Micro-Switch Bar: `[左键]` · `[拖拽锁 (高亮常驻左键按下，单指轻松跨屏拖拽)]` · `[右键]`, plus quick row `[⌨️ 切键盘直输]` · `[🎙️ 按住说话直输 Mac]`.
2. `MacLaserAirWandView`:
   - Top visual effect pill selector (`OmsSlidingSegmentedBar`): `原生飞鼠` (`OFF` overlay, moves real cursor) · `红点激光` (`RED_LASER`) · `聚光灯高亮` (`SPOTLIGHT`). When `SPOTLIGHT` is selected, show compact aperture radius slider (`80..280px`, default `140px`).
   - Center oversized ergonomic **Thumb Trigger Pad (`按住指向 · 松手定格 / 双击常驻锁`)**:
     - Registers `Sensor.TYPE_GAME_ROTATION_VECTOR` at `SENSOR_DELAY_GAME` ONLY while `isTriggerHeld || isTriggerLocked` inside `DisposableEffect(activeSensor, laserEffect, spotlightRadius)`; unregisters immediately when released!
     - While `laserEffect != OFF` and active, runs a `LaunchedEffect` sending a `LASER_KEEPALIVE` frame every `480ms` so the Mac `1.8s` watchdog keeps the spotlight/dot alive even when holding the hand completely still; on release (when not locked), sends `MacHidPacket.laserUpdate(MacHidLaserMode.OFF, 0f, 0f)` so the spotlight/laser cleanly disappears when you let go of the trigger!
   - Bottom Presenter & Click Row:
     - Large `[◀ 上一页]` (`LEFT` arrow) · `[● 左键点击]` (`LEFT CLICK`) · `[下一页 ▶]` (`RIGHT` arrow)
     - Secondary presenter pills: `[全屏演示 ⌘⌃F / ⌘⌥P]` · `[黑屏 B]` · `[退出 Esc]`.

- [ ] **Step 2: Create `MacKeyboardAndVoiceDeckView.kt` (Lossless Text + Voice-to-Mac + Shortcut Deck)**

Implement `MacKeyboardAndVoiceDeckView` in `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacKeyboardAndVoiceDeckView.kt`:
1. **Direct Text & Voice-to-Mac Composer Card**:
   - Multi-line `BasicTextField` (`输入任意中英文、代码或符号，一键无损注入 Mac 光标处…`) + toggle pill `[⏎ 附带回车]` + `[注入 Mac]` primary button.
   - Prominent **Hold-to-Speak / Tap-to-Toggle Voice Dictation Bar (`🎙️ 按住说话 · 直输 Mac`)**:
     - Checks `Manifest.permission.RECORD_AUDIO` (requests via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` if missing).
     - Calls `VoiceRecorderHelper.startRecording(context)` on press and `VoiceRecorderHelper.stopRecording()` on release -> `coordinator.aiServices.transcribeAudioFile(audioFile)` -> `coordinator.typeTextToMac(transcript, submitReturn = autoSubmitReturn)`, showing live status (`正在听写…` -> `正在转写注入 Mac…` -> toast confirmation).
2. **Blind-Friendly Tactile Shortcut & Navigation Deck**:
   - Row 1 (Core Keys): `Esc` · `Tab` · `Space` · `⌫ 退格` · `⏎ 回车` (`⌫` supports hold-to-repeat every `75ms` after `350ms` initial delay).
   - Row 2 (Efficiency): `⌘Space (聚焦)` · `⌘Tab (切应用)` · `⌘C` · `⌘V` · `⌘Z` · `⌘W`.
   - Row 3 (Inverted-T Arrow D-Pad + Spaces): `⌃◀ 左桌面` · `[◀] [▲ / ▼] [▶]` (arrows support hold-to-repeat) · `右桌面 ⌃▶`.
   - Row 4 (Hardware Media & Display Brightness): `🔆-` · `🔆+` · `⏮` · `⏯` · `⏭` · `🔇`.

- [ ] **Step 3: Upgrade `MacRemoteControlModal.kt` to host the 4-Mode Shell & Hardware Volume Key Interceptor**

In `android-app/app/src/main/java/com/oms/client/ui/cockpit/MacRemoteControlModal.kt`:
1. Add `enum class MacRemoteTab { TRACKPAD, LASER_WAND, KEYBOARD_VOICE, SYSTEM_APPS }` defaulting to `MacRemoteTab.TRACKPAD` (or `SYSTEM_APPS` automatically if `status?.screenLocked == true` on initial open, plus a floating 1-tap `🔓 当前屏幕已锁定 · 点击秒解` banner across all tabs when `status?.screenLocked == true`!).
2. Add `OmsSlidingSegmentedBar` right below the top header bar switching smoothly between `触控板`, `激光飞鼠`, `键盘语音`, and `系统应用`, calling `coordinator.resetMacHidState()` whenever switching away from `TRACKPAD` or `LASER_WAND`.
3. Attach hardware volume key interception inside the `Dialog` content root (`DialogWindowProvider` `window.Callback` / `View.OnKeyListener` + Compose `onPreviewKeyEvent` with a focusable root `FocusRequester` so hardware `KEYCODE_VOLUME_UP` and `KEYCODE_VOLUME_DOWN` are reliably intercepted even when no text field is focused!):
   - When `currentMode == MacRemoteTab.LASER_WAND`: `VOLUME_DOWN` -> `RIGHT` arrow (Next Slide), `VOLUME_UP` -> `LEFT` arrow (Previous Slide), consuming `ACTION_DOWN` & `ACTION_UP` with `vibrateTick()`.
   - On all other tabs: `VOLUME_UP` -> `MacHidPacket.systemKey(MacHidSystemKey.SOUND_UP)`, `VOLUME_DOWN` -> `MacHidPacket.systemKey(MacHidSystemKey.SOUND_DOWN)`, consuming the event so phone local media volume doesn't pop up while adjusting Mac volume!
4. Add `DisposableEffect(Unit) { onDispose { VoiceRecorderHelper.cancelRecording(); coordinator.resetMacHidState() } }` so closing the modal guarantees clean state on both phone and Mac.

- [ ] **Step 4: Compile Android Debug APK & Unit Tests to verify zero errors**

Run: `(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug)`
Expected: `BUILD SUCCESSFUL` with 0 Kotlin compilation errors and all unit tests passing.

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src/main/java/com/oms/client/ui/cockpit/MacTrackpadAndLaserViews.kt android-app/app/src/main/java/com/oms/client/ui/cockpit/MacKeyboardAndVoiceDeckView.kt android-app/app/src/main/java/com/oms/client/ui/cockpit/MacRemoteControlModal.kt
git commit -m "feat(android-ui): upgrade MacRemoteControlModal into 4-mode trackpad, laser air-wand & voice-keyboard cockpit"
```

---

### Task 6: Full Cross-Stack Verification & Local Helper Bundle Refresh

**Files:**
- Verify: `shared-protocol`, `mac-host`, `android-app`

**Interfaces:**
- Consumes: All artifacts from Tasks 1–5
- Produces: Verified green test suites across TypeScript, Swift, and Kotlin + fresh `mac-host/bin/oms-mac-ctl` binary ready for immediate use.

- [ ] **Step 1: Run full cross-stack build and test verification**

Run:
```bash
(cd mac-host && bun run build:ctl && bun run build) && \
bun test shared-protocol/tests mac-host/tests/mac-hid-control.test.ts mac-host/tests/control-boundary.test.ts && \
(cd android-app && ./gradlew testDebugUnitTest assembleDebug) && \
git status -sb
```
Expected: All Swift builds, TypeScript checks, Bun tests, and Gradle unit tests + `assembleDebug` succeed cleanly.
