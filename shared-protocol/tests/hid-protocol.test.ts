import { describe, expect, test } from "bun:test";
import {
  FRAME_HEADER_BYTES,
  FramePriority,
  FrameStreamDecoder,
  FrameType,
  HID_INPUT_PAYLOAD_BYTES,
  HidButtonAction,
  HidButtonCode,
  HidInputFlags,
  type HidInputPayload,
  HidLaserMode,
  HidModifierMask,
  HidOpcode,
  HidSystemKey,
  type MacHidTypeTextParams,
  type MacHidTypeTextResult,
  OMS_MAGIC_BYTE,
  OMS_PROTOCOL_VERSION,
  PriorityFrameQueue,
  decodeHidInputPayload,
  encodeFrame,
  encodeHidInputFrame,
  encodeJsonRpcFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  fixedQuarterToPx,
  pxToFixedQuarter,
  tryCoalesceHidFrames,
} from "../src/index.js";

describe("HID_INPUT (0x08) Binary Framing & Coalescing", () => {
  test("encodes and decodes 12-byte Big-Endian HID_INPUT payload inside 20-byte OMS frame", () => {
    expect(FrameType.HID_INPUT).toBe(0x08);
    expect(HID_INPUT_PAYLOAD_BYTES).toBe(12);

    const payloadInput: HidInputPayload = {
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: HidModifierMask.COMMAND | HidModifierMask.SHIFT,
      dx: pxToFixedQuarter(12.5),
      dy: pxToFixedQuarter(-3.25),
      aux: -140,
      seq: 65535,
      flags: HidInputFlags.MOMENTUM_SCROLL | HidInputFlags.NATURAL_SCROLL,
    };

    const raw = encodeHidInputFrame(payloadInput);
    expect(raw.byteLength).toBe(20);

    // Verify exact 8B OMS header + 12B Big-Endian wire layout
    const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
    expect(view.getUint8(0)).toBe(OMS_MAGIC_BYTE);
    expect(view.getUint8(1)).toBe(OMS_PROTOCOL_VERSION);
    expect(view.getUint8(2)).toBe(0x08);
    expect(view.getUint8(3)).toBe(0);
    expect(view.getUint32(4, false)).toBe(12);

    expect(view.getUint8(8)).toBe(0x01); // opcode: POINTER_MOVE_REL
    expect(view.getUint8(9)).toBe(0x03); // COMMAND (0x01) | SHIFT (0x02)
    expect(view.getInt16(10, false)).toBe(50); // 12.5 * 4
    expect(view.getInt16(12, false)).toBe(-13); // -3.25 * 4
    expect(view.getInt16(14, false)).toBe(-140); // aux
    expect(view.getUint16(16, false)).toBe(65535); // seq
    expect(view.getUint16(18, false)).toBe(0x0005); // MOMENTUM_SCROLL (1) | NATURAL_SCROLL (4)

    const decodedFrames = new FrameStreamDecoder().push(raw);
    expect(decodedFrames.length).toBe(1);
    expect(decodedFrames[0].frameType).toBe(FrameType.HID_INPUT);

    // Also verify non-zero byteOffset subarray decoding
    const padded = new Uint8Array(24);
    padded.set(decodedFrames[0].payload, 5);
    const hid = decodeHidInputPayload(padded.subarray(5, 5 + HID_INPUT_PAYLOAD_BYTES));
    expect(hid.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(hid.modsOrBtn).toBe(HidModifierMask.COMMAND | HidModifierMask.SHIFT);
    expect(fixedQuarterToPx(hid.dx)).toBe(12.5);
    expect(fixedQuarterToPx(hid.dy)).toBe(-3.25);
    expect(hid.aux).toBe(-140);
    expect(hid.seq).toBe(65535);
    expect(hid.flags).toBe(HidInputFlags.MOMENTUM_SCROLL | HidInputFlags.NATURAL_SCROLL);

    expect(() => decodeHidInputPayload(new Uint8Array(11))).toThrow("HID_INPUT payload too short");
  });

  test("exposes all required HID enums and MacHidTypeText RPC contracts", () => {
    expect(HidOpcode.POINTER_MOVE_REL).toBe(0x01);
    expect(HidOpcode.MOUSE_BUTTON).toBe(0x02);
    expect(HidOpcode.SCROLL_PIXEL).toBe(0x03);
    expect(HidOpcode.KEY_TAP).toBe(0x04);
    expect(HidOpcode.SYSTEM_DEFINED_KEY).toBe(0x05);
    expect(HidOpcode.LASER_STATE_AND_DELTA).toBe(0x06);
    expect(HidOpcode.RESET_HID_STATE).toBe(0x0f);

    expect(HidModifierMask.NONE).toBe(0x00);
    expect(HidModifierMask.COMMAND).toBe(0x01);
    expect(HidModifierMask.SHIFT).toBe(0x02);
    expect(HidModifierMask.OPTION).toBe(0x04);
    expect(HidModifierMask.CONTROL).toBe(0x08);

    expect(HidButtonCode.LEFT).toBe(0x01);
    expect(HidButtonCode.RIGHT).toBe(0x02);
    expect(HidButtonCode.MIDDLE).toBe(0x03);

    expect(HidButtonAction.CLICK).toBe(1);
    expect(HidButtonAction.DOUBLE_CLICK).toBe(2);
    expect(HidButtonAction.DOWN).toBe(3);
    expect(HidButtonAction.UP).toBe(4);
    expect(HidButtonAction.DRAG_LOCK_TOGGLE).toBe(5);

    expect(HidLaserMode.OFF).toBe(0);
    expect(HidLaserMode.RED_LASER).toBe(1);
    expect(HidLaserMode.SPOTLIGHT).toBe(2);

    expect(HidSystemKey.SOUND_UP).toBe(0);
    expect(HidSystemKey.SOUND_DOWN).toBe(1);
    expect(HidSystemKey.BRIGHTNESS_UP).toBe(2);
    expect(HidSystemKey.BRIGHTNESS_DOWN).toBe(3);
    expect(HidSystemKey.MUTE).toBe(7);
    expect(HidSystemKey.PLAY_PAUSE).toBe(16);
    expect(HidSystemKey.NEXT_TRACK).toBe(17);
    expect(HidSystemKey.PREV_TRACK).toBe(18);

    expect(HidInputFlags.NONE).toBe(0x0000);
    expect(HidInputFlags.MOMENTUM_SCROLL).toBe(0x0001);
    expect(HidInputFlags.LASER_KEEPALIVE).toBe(0x0002);
    expect(HidInputFlags.NATURAL_SCROLL).toBe(0x0004);

    const params: MacHidTypeTextParams = { text: "你好 🚀", submitReturn: true };
    const result: MacHidTypeTextResult = { ok: true, typedChars: 4, message: "ok" };
    expect(params.submitReturn).toBe(true);
    expect(result.typedChars).toBe(4);
  });

  test("converts quarter-pixel fixed-point values accurately, clamps int16, and handles NaN/Infinity safely", () => {
    expect(pxToFixedQuarter(0)).toBe(0);
    expect(pxToFixedQuarter(-0.01)).toBe(0);
    expect(pxToFixedQuarter(0.25)).toBe(1);
    expect(pxToFixedQuarter(-0.25)).toBe(-1);
    expect(pxToFixedQuarter(8191.75)).toBe(32767);
    expect(pxToFixedQuarter(-8192)).toBe(-32768);
    expect(pxToFixedQuarter(99999)).toBe(32767);
    expect(pxToFixedQuarter(-99999)).toBe(-32768);

    // Non-finite inputs safely resolve to 0
    expect(pxToFixedQuarter(Number.NaN)).toBe(0);
    expect(pxToFixedQuarter(Number.POSITIVE_INFINITY)).toBe(0);
    expect(pxToFixedQuarter(Number.NEGATIVE_INFINITY)).toBe(0);

    expect(fixedQuarterToPx(0)).toBe(0);
    expect(fixedQuarterToPx(1)).toBe(0.25);
    expect(fixedQuarterToPx(-1)).toBe(-0.25);
    expect(fixedQuarterToPx(32767)).toBe(8191.75);
    expect(fixedQuarterToPx(-32768)).toBe(-8192);
    expect(fixedQuarterToPx(Number.NaN)).toBe(0);
    expect(fixedQuarterToPx(Number.POSITIVE_INFINITY)).toBe(0);
  });

  test("tryCoalesceHidFrames merges only eligible 20B frames with identical modsOrBtn and saturates int16", () => {
    // 1. POINTER_MOVE_REL saturating dx/dy + preserving next.aux, next.seq, prev.flags | next.flags
    const moveA = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: HidModifierMask.SHIFT,
      dx: 30000,
      dy: -30000,
      aux: 10,
      seq: 100,
      flags: HidInputFlags.NATURAL_SCROLL,
    });
    const moveB = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: HidModifierMask.SHIFT,
      dx: 5000,
      dy: -5000,
      aux: 20,
      seq: 101,
      flags: HidInputFlags.MOMENTUM_SCROLL,
    });
    const mergedMoveRaw = tryCoalesceHidFrames(moveA, moveB);
    expect(mergedMoveRaw).not.toBeNull();
    const mergedMove = decodeHidInputPayload(mergedMoveRaw!.subarray(FRAME_HEADER_BYTES));
    expect(mergedMove.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(mergedMove.modsOrBtn).toBe(HidModifierMask.SHIFT);
    expect(mergedMove.dx).toBe(32767);
    expect(mergedMove.dy).toBe(-32768);
    expect(mergedMove.aux).toBe(20);
    expect(mergedMove.seq).toBe(101);
    expect(mergedMove.flags).toBe(HidInputFlags.NATURAL_SCROLL | HidInputFlags.MOMENTUM_SCROLL);

    // 2. SCROLL_PIXEL merges when modsOrBtn matches
    const scrollA = encodeHidInputFrame({
      opcode: HidOpcode.SCROLL_PIXEL,
      modsOrBtn: 0,
      dx: -12,
      dy: 40,
      aux: 1,
      seq: 10,
      flags: HidInputFlags.NATURAL_SCROLL,
    });
    const scrollB = encodeHidInputFrame({
      opcode: HidOpcode.SCROLL_PIXEL,
      modsOrBtn: 0,
      dx: -8,
      dy: 60,
      aux: 2,
      seq: 11,
      flags: HidInputFlags.MOMENTUM_SCROLL,
    });
    const mergedScrollRaw = tryCoalesceHidFrames(scrollA, scrollB);
    expect(mergedScrollRaw).not.toBeNull();
    const mergedScroll = decodeHidInputPayload(mergedScrollRaw!.subarray(FRAME_HEADER_BYTES));
    expect(mergedScroll.dx).toBe(-20);
    expect(mergedScroll.dy).toBe(100);
    expect(mergedScroll.aux).toBe(2);
    expect(mergedScroll.seq).toBe(11);
    expect(mergedScroll.flags).toBe(HidInputFlags.NATURAL_SCROLL | HidInputFlags.MOMENTUM_SCROLL);

    // 3. LASER_STATE_AND_DELTA merges when mode (modsOrBtn) stays identical, rejects when mode changes
    const laserSpot1 = encodeHidInputFrame({
      opcode: HidOpcode.LASER_STATE_AND_DELTA,
      modsOrBtn: HidLaserMode.SPOTLIGHT,
      dx: 15,
      dy: -10,
      aux: 140,
      seq: 20,
      flags: HidInputFlags.LASER_KEEPALIVE,
    });
    const laserSpot2 = encodeHidInputFrame({
      opcode: HidOpcode.LASER_STATE_AND_DELTA,
      modsOrBtn: HidLaserMode.SPOTLIGHT,
      dx: 25,
      dy: 30,
      aux: 180,
      seq: 21,
      flags: HidInputFlags.NONE,
    });
    const laserRed = encodeHidInputFrame({
      opcode: HidOpcode.LASER_STATE_AND_DELTA,
      modsOrBtn: HidLaserMode.RED_LASER,
      dx: 5,
      dy: 5,
      aux: 140,
      seq: 22,
      flags: HidInputFlags.NONE,
    });
    const mergedLaserRaw = tryCoalesceHidFrames(laserSpot1, laserSpot2);
    expect(mergedLaserRaw).not.toBeNull();
    const mergedLaser = decodeHidInputPayload(mergedLaserRaw!.subarray(FRAME_HEADER_BYTES));
    expect(mergedLaser.dx).toBe(40);
    expect(mergedLaser.dy).toBe(20);
    expect(mergedLaser.aux).toBe(180);
    expect(mergedLaser.seq).toBe(21);
    expect(mergedLaser.flags).toBe(HidInputFlags.LASER_KEEPALIVE);

    // Changing laser mode (SPOTLIGHT -> RED_LASER) must NOT coalesce
    expect(tryCoalesceHidFrames(laserSpot2, laserRed)).toBeNull();

    // Changing modifiers on POINTER_MOVE_REL must NOT coalesce
    const moveNoMods = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: HidModifierMask.NONE,
      dx: 4,
      dy: 4,
      aux: 0,
      seq: 102,
      flags: 0,
    });
    expect(tryCoalesceHidFrames(moveA, moveNoMods)).toBeNull();
    // Different coalesceable opcodes must NOT coalesce
    expect(tryCoalesceHidFrames(moveNoMods, scrollA)).toBeNull();

    // 4. Non-coalesceable barrier opcodes NEVER merge even with identical opcode & modsOrBtn
    for (const barrierOpcode of [
      HidOpcode.MOUSE_BUTTON,
      HidOpcode.KEY_TAP,
      HidOpcode.SYSTEM_DEFINED_KEY,
      HidOpcode.RESET_HID_STATE,
    ]) {
      const f1 = encodeHidInputFrame({
        opcode: barrierOpcode,
        modsOrBtn: 1,
        dx: 1,
        dy: 0,
        aux: 0,
        seq: 1,
        flags: 0,
      });
      const f2 = encodeHidInputFrame({
        opcode: barrierOpcode,
        modsOrBtn: 1,
        dx: 1,
        dy: 0,
        aux: 0,
        seq: 2,
        flags: 0,
      });
      expect(tryCoalesceHidFrames(f1, f2)).toBeNull();
      expect(tryCoalesceHidFrames(moveNoMods, f1)).toBeNull();
      expect(tryCoalesceHidFrames(f1, moveNoMods)).toBeNull();
    }

    // 5. Malformed / non-20B / non-HID frames return null safely
    expect(tryCoalesceHidFrames(new Uint8Array(19), moveNoMods)).toBeNull();
    expect(tryCoalesceHidFrames(moveNoMods, new Uint8Array(21))).toBeNull();
    const nonHid20B = encodeFrame(FrameType.PING, new Uint8Array(12));
    expect(nonHid20B.byteLength).toBe(20);
    expect(tryCoalesceHidFrames(nonHid20B, moveNoMods)).toBeNull();

    const badMagic = moveNoMods.slice();
    badMagic[0] = 0x00;
    expect(tryCoalesceHidFrames(badMagic, moveNoMods)).toBeNull();

    const badLengthHeader = moveNoMods.slice();
    new DataView(badLengthHeader.buffer).setUint32(4, 99, false);
    expect(tryCoalesceHidFrames(badLengthHeader, moveNoMods)).toBeNull();
  });

  test("coalesces consecutive delta frames in PriorityFrameQueue without crossing click/key/RPC barriers", () => {
    const q = new PriorityFrameQueue();
    const streamId = "018f3b2c-4d5e-7000-8000-010203040506";
    const bulk = encodeStreamChunkFrame(streamId, 0, new Uint8Array(64));
    const meta = encodeStreamStartFrame({
      streamId,
      fileName: "slide.pdf",
      mimeType: "application/pdf",
      totalBytes: 64,
      chunkSize: 64,
      totalChunks: 1,
      sha256: "deadbeef",
    });
    q.enqueue(bulk, FrameType.STREAM_CHUNK);
    q.enqueue(meta, FrameType.STREAM_START);

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
    const keyTap = encodeHidInputFrame({
      opcode: HidOpcode.KEY_TAP,
      modsOrBtn: HidModifierMask.COMMAND,
      dx: 8, // 'C'
      dy: 0,
      aux: 0,
      seq: 5,
      flags: 0,
    });
    const moveAfterKey = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: 0,
      dx: -12,
      dy: 4,
      aux: 0,
      seq: 6,
      flags: 0,
    });
    const rpcBarrier = encodeJsonRpcFrame({ jsonrpc: "2.0", method: "mac.hid.type_text" });
    const moveAfterRpc = encodeHidInputFrame({
      opcode: HidOpcode.POINTER_MOVE_REL,
      modsOrBtn: 0,
      dx: 20,
      dy: 20,
      aux: 0,
      seq: 7,
      flags: 0,
    });

    q.enqueue(move1, FrameType.HID_INPUT);
    q.enqueue(move2, FrameType.HID_INPUT);
    q.enqueue(click, FrameType.HID_INPUT);
    q.enqueue(moveAfterClick, FrameType.HID_INPUT);
    q.enqueue(keyTap, FrameType.HID_INPUT);
    q.enqueue(moveAfterKey, FrameType.HID_INPUT);
    q.enqueue(rpcBarrier, FrameType.CONTROL_JSON_RPC);
    q.enqueue(moveAfterRpc, FrameType.HID_INPUT);

    // P0 HID frames dequeue ahead of P1 stream meta and P2 bulk data, and move1+move2 merged into 1 frame!
    expect(PriorityFrameQueue.priorityForType(FrameType.HID_INPUT)).toBe(FramePriority.P0_URGENT_CONTROL);
    // Queue contains: [move1+2, click, moveAfterClick, keyTap, moveAfterKey, rpcBarrier, moveAfterRpc] (7 P0) + 1 P1 + 1 P2 = 9
    expect(q.size).toBe(9);

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
    expect(third.seq).toBe(4);

    const fourth = decodeHidInputPayload(new FrameStreamDecoder().push(q.dequeue()!)[0].payload);
    expect(fourth.opcode).toBe(HidOpcode.KEY_TAP);
    expect(fourth.seq).toBe(5);

    const fifth = decodeHidInputPayload(new FrameStreamDecoder().push(q.dequeue()!)[0].payload);
    expect(fifth.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(fifth.dx).toBe(-12);
    expect(fifth.seq).toBe(6);

    const sixth = new FrameStreamDecoder().push(q.dequeue()!)[0];
    expect(sixth.frameType).toBe(FrameType.CONTROL_JSON_RPC);

    const seventh = decodeHidInputPayload(new FrameStreamDecoder().push(q.dequeue()!)[0].payload);
    expect(seventh.opcode).toBe(HidOpcode.POINTER_MOVE_REL);
    expect(seventh.dx).toBe(20);
    expect(seventh.seq).toBe(7);

    const eighth = new FrameStreamDecoder().push(q.dequeue()!)[0];
    expect(eighth.frameType).toBe(FrameType.STREAM_START);

    const ninth = new FrameStreamDecoder().push(q.dequeue()!)[0];
    expect(ninth.frameType).toBe(FrameType.STREAM_CHUNK);

    expect(q.dequeue()).toBeUndefined();
  });
});
