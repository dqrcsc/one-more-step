import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  FrameStreamDecoder,
  FrameType,
  HidButtonAction,
  HidButtonCode,
  HidInputFlags,
  HidLaserMode,
  HidModifierMask,
  HidOpcode,
  HidSystemKey,
  JsonRpcMessage,
  JsonRpcResponse,
  decodeJsonRpcPayload,
  encodeHidInputFrame,
  encodeJsonRpcFrame,
  generateEd25519Identity,
  parsePairingUri,
  pxToFixedQuarter,
} from "@oms/shared-protocol";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { connectSecurePhone, secureMemoryPhone } from "./secure-phone-helper.js";

describe("Mac HID Binary Frame (0x08) Routing, Lockscreen Guard & Dead-Man Reset", () => {
  let tmpHome: string;
  const prevIsolation = process.env.OMS_TEST_ISOLATION;
  const prevDisableOsa = process.env.OMS_DISABLE_OSASCRIPT;
  const prevDisableWeChat = process.env.OMS_DISABLE_WECHAT_WORKER;

  beforeEach(() => {
    process.env.OMS_TEST_ISOLATION = "1";
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    process.env.OMS_DISABLE_WECHAT_WORKER = "1";
    tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "oms-hid-control-test-"));
    fs.writeFileSync(
      path.join(tmpHome, "config.json"),
      JSON.stringify({
        lanPort: 0,
        enableBluetoothBridge: false,
        enableWeChatBridge: false,
        dingtalk: { enabled: false },
      }),
      "utf8"
    );
  });

  afterEach(() => {
    if (prevIsolation === undefined) delete process.env.OMS_TEST_ISOLATION;
    else process.env.OMS_TEST_ISOLATION = prevIsolation;

    if (prevDisableOsa === undefined) delete process.env.OMS_DISABLE_OSASCRIPT;
    else process.env.OMS_DISABLE_OSASCRIPT = prevDisableOsa;

    if (prevDisableWeChat === undefined) delete process.env.OMS_DISABLE_WECHAT_WORKER;
    else process.env.OMS_DISABLE_WECHAT_WORKER = prevDisableWeChat;

    fs.rmSync(tmpHome, { recursive: true, force: true });
  });

  test("rejects plaintext and pre-confirmation 0x08 HID_INPUT frames before handshake completes", async () => {
    const daemon = new OmsDaemon(tmpHome);
    try {
      const pairInfo = await daemon.handleIpcAction("daemon.pair", {});
      const parsedUri = parsePairingUri(pairInfo.pairingUri);
      const rawSession = daemon.session as any;

      // 1. Plaintext 0x08 frame during initial hello phase must fail closed immediately
      const unauthPipe: any = {
        tier: "LAN_WS",
        authenticated: false,
        decoder: new FrameStreamDecoder(),
        sendRaw: () => true,
        close: () => {},
      };
      rawSession.onPipeOpened(unauthPipe);
      rawSession.onRawBytesReceived(
        unauthPipe,
        encodeHidInputFrame({
          opcode: HidOpcode.POINTER_MOVE_REL,
          modsOrBtn: 0,
          dx: 16,
          dy: 16,
          aux: 0,
          seq: 1,
          flags: 0,
        })
      );
      expect(unauthPipe.closed).toBe(true);
      expect(unauthPipe.authenticated).toBe(false);
      expect(daemon.macControl.getTestRecordedHidEvents()).toEqual([]);

      // 2. Encrypted 0x08 frame during finish phase (before secure.finish key confirmation) must fail closed
      const halfOpen = secureMemoryPhone(rawSession, {
        phoneId: "phone_half_open",
        macId: parsedUri.id,
        pskHex: parsedUri.ephemeralToken,
        autoFinish: false,
      });
      halfOpen.send(
        encodeHidInputFrame({
          opcode: HidOpcode.KEY_TAP,
          modsOrBtn: HidModifierMask.COMMAND,
          dx: 8,
          dy: 0,
          aux: 0,
          seq: 2,
          flags: 0,
        })
      );
      expect(halfOpen.pipe.closed).toBe(true);
      expect(halfOpen.pipe.authenticated).toBe(false);
      expect(daemon.macControl.getTestRecordedHidEvents()).toEqual([]);
    } finally {
      await daemon.stop();
    }
  });

  test("routes all authenticated 0x08 HID opcodes, handles mac.hid.type_text, enforces lockscreen guard, and fires dead-man reset", async () => {
    const daemon = new OmsDaemon(tmpHome);
    await daemon.start({ startTransport: true });

    try {
      const port = (daemon.session as any).wsServer.port as number;
      const pairInfo = await daemon.handleIpcAction("daemon.pair", {});
      const parsedUri = parsePairingUri(pairInfo.pairingUri);
      const phoneIdentity = generateEd25519Identity("xiaomi");

      const pendingRpc = new Map<string, (resp: JsonRpcResponse<any>) => void>();
      const phone = await connectSecurePhone(`ws://127.0.0.1:${port}/?tier=lan`, {
        phoneId: phoneIdentity.deviceId,
        macId: parsedUri.id,
        pskHex: parsedUri.ephemeralToken,
        onFrame(frame) {
          if (frame.frameType !== FrameType.CONTROL_JSON_RPC) return;
          const msg = decodeJsonRpcPayload<JsonRpcMessage>(frame.payload);
          if ("id" in msg && msg.id && ("result" in msg || "error" in msg)) {
            pendingRpc.get(msg.id)?.(msg as JsonRpcResponse<any>);
            pendingRpc.delete(msg.id);
          }
        },
      });

      let rpcCounter = 0;
      const callHostRpc = <T = any>(method: string, params: Record<string, unknown> = {}): Promise<JsonRpcResponse<T>> => {
        const id = `hid_rpc_${++rpcCounter}`;
        return new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            pendingRpc.delete(id);
            reject(new Error(`Timed out waiting for ${method}`));
          }, 4000);
          pendingRpc.set(id, (resp) => {
            clearTimeout(timer);
            resolve(resp as JsonRpcResponse<T>);
          });
          phone.send(encodeJsonRpcFrame({ jsonrpc: "2.0", id, method, params }));
        });
      };

      // 1. Send all 6 authenticated continuous/discrete 0x08 HID opcodes while unlocked
      const sampleFrames = [
        {
          opcode: HidOpcode.POINTER_MOVE_REL,
          modsOrBtn: 0,
          dx: pxToFixedQuarter(18.5),
          dy: pxToFixedQuarter(-6.25),
          aux: 0,
          seq: 10,
          flags: HidInputFlags.NONE,
        },
        {
          opcode: HidOpcode.SCROLL_PIXEL,
          modsOrBtn: 0,
          dx: pxToFixedQuarter(0),
          dy: pxToFixedQuarter(-32),
          aux: 2,
          seq: 11,
          flags: HidInputFlags.MOMENTUM_SCROLL | HidInputFlags.NATURAL_SCROLL,
        },
        {
          opcode: HidOpcode.MOUSE_BUTTON,
          modsOrBtn: HidButtonCode.LEFT,
          dx: HidButtonAction.CLICK,
          dy: 0,
          aux: 0,
          seq: 12,
          flags: HidInputFlags.NONE,
        },
        {
          opcode: HidOpcode.LASER_STATE_AND_DELTA,
          modsOrBtn: HidLaserMode.SPOTLIGHT,
          dx: pxToFixedQuarter(4),
          dy: pxToFixedQuarter(2),
          aux: 160,
          seq: 13,
          flags: HidInputFlags.LASER_KEEPALIVE,
        },
        {
          opcode: HidOpcode.SYSTEM_DEFINED_KEY,
          modsOrBtn: HidSystemKey.PLAY_PAUSE,
          dx: 0,
          dy: 0,
          aux: 0,
          seq: 14,
          flags: HidInputFlags.NONE,
        },
        {
          opcode: HidOpcode.KEY_TAP,
          modsOrBtn: HidModifierMask.COMMAND | HidModifierMask.SHIFT,
          dx: 8,
          dy: 0,
          aux: 0,
          seq: 15,
          flags: HidInputFlags.NONE,
        },
      ];

      for (const pkt of sampleFrames) {
        phone.send(encodeHidInputFrame(pkt));
      }

      // 2. Authenticated mac.hid.type_text RPC injects Chinese + English + Emoji with submitReturn
      const textSample = "你好 OMS Remote Wand 🎯🚀";
      const typeResp = await callHostRpc("mac.hid.type_text", {
        text: textSample,
        submitReturn: true,
      });
      expect(typeResp.error).toBeUndefined();
      expect(typeResp.result).toEqual({
        ok: true,
        typedChars: Array.from(textSample).length,
      });

      // Empty text validation
      const emptyResp = await callHostRpc("mac.hid.type_text", { text: "" });
      expect(emptyResp.result?.ok).toBe(false);
      expect(emptyResp.result?.typedChars).toBe(0);

      // Local IPC mac.hid.type_text also works
      const ipcTypeResp = await daemon.handleIpcAction("mac.hid.type_text", {
        text: "Local IPC 测试 ✨",
        submitReturn: false,
      });
      expect(ipcTypeResp).toEqual({
        ok: true,
        typedChars: Array.from("Local IPC 测试 ✨").length,
      });

      const recordedUnlocked = daemon.macControl.getTestRecordedHidEvents();
      for (const expected of sampleFrames) {
        expect(
          recordedUnlocked.some(
            (e) =>
              e.type === "hid" &&
              e.op === expected.opcode &&
              e.m === expected.modsOrBtn &&
              e.dx === expected.dx &&
              e.dy === expected.dy &&
              e.aux === expected.aux &&
              e.seq === expected.seq &&
              e.fl === expected.flags
          )
        ).toBe(true);
      }
      expect(
        recordedUnlocked.some(
          (e) => e.type === "type-text" && e.text === textSample && e.submitReturn === true
        )
      ).toBe(true);

      // 3. Lockscreen guard: lock Mac -> block mac.hid.type_text and non-reset HID frames, allow RESET_HID_STATE
      const lockResp = await callHostRpc("mac.control.execute", { action: "lock" });
      expect(lockResp.result?.ok).toBe(true);
      expect(lockResp.result?.status?.screenLocked).toBe(true);

      const countBeforeLockedInputs = daemon.macControl.getTestRecordedHidEvents().length;

      const blockedType = await callHostRpc("mac.hid.type_text", {
        text: "should-not-type-into-password-box",
        submitReturn: true,
      });
      expect(blockedType.result?.ok).toBe(false);
      expect(blockedType.result?.typedChars).toBe(0);
      expect(blockedType.result?.message).toContain("锁屏");

      // Send blind KEY_TAP, POINTER_MOVE_REL, MOUSE_BUTTON while locked -> all must be ignored
      for (const blockedPkt of [
        { opcode: HidOpcode.KEY_TAP, modsOrBtn: 0, dx: 36, dy: 0, aux: 0, seq: 20, flags: 0 },
        { opcode: HidOpcode.POINTER_MOVE_REL, modsOrBtn: 0, dx: 20, dy: 20, aux: 0, seq: 21, flags: 0 },
        { opcode: HidOpcode.MOUSE_BUTTON, modsOrBtn: HidButtonCode.LEFT, dx: HidButtonAction.DOWN, dy: 0, aux: 0, seq: 22, flags: 0 },
      ]) {
        phone.send(encodeHidInputFrame(blockedPkt));
      }

      // Send RESET_HID_STATE while locked -> MUST still be allowed through!
      phone.send(
        encodeHidInputFrame({
          opcode: HidOpcode.RESET_HID_STATE,
          modsOrBtn: 0,
          dx: 0,
          dy: 0,
          aux: 0,
          seq: 23,
          flags: 0,
        })
      );
      // Sync barrier via status RPC to ensure preceding WebSocket frames were processed
      await callHostRpc("mac.control.status", {});

      const eventsWhileLocked = daemon.macControl
        .getTestRecordedHidEvents()
        .slice(countBeforeLockedInputs);
      expect(eventsWhileLocked).toEqual([
        {
          type: "hid",
          op: HidOpcode.RESET_HID_STATE,
          m: 0,
          dx: 0,
          dy: 0,
          aux: 0,
          seq: 23,
          fl: 0,
        },
      ]);

      // 4. Dead-man switch on sessionRevoked AND on phone disconnect (tier -> DISCONNECTED)
      const resetsBeforeRevoke = daemon.macControl
        .getTestRecordedHidEvents()
        .filter((e) => e.type === "reset-hid").length;
      daemon.session.emit("sessionRevoked");
      const resetsAfterRevoke = daemon.macControl
        .getTestRecordedHidEvents()
        .filter((e) => e.type === "reset-hid").length;
      expect(resetsAfterRevoke).toBe(resetsBeforeRevoke + 1);

      phone.close();
      await phone.closed;
      // Wait briefly for server close callback to transition tier -> DISCONNECTED
      const deadline = Date.now() + 1500;
      while (daemon.session.getActiveTier() !== "DISCONNECTED" && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 15));
      }
      expect(daemon.session.getActiveTier()).toBe("DISCONNECTED");

      const resetsAfterDisconnect = daemon.macControl
        .getTestRecordedHidEvents()
        .filter((e) => e.type === "reset-hid").length;
      expect(resetsAfterDisconnect).toBeGreaterThan(resetsAfterRevoke);
    } finally {
      await daemon.stop();
    }
  });
});
