import { describe, expect, test } from "bun:test";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import {
  computePairingHmac,
  createEphemeralPairingCredentials,
  decodeJsonRpcPayload,
  encodeJsonRpcFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  FrameStreamDecoder,
  FrameType,
  generateEd25519Identity,
  generateUuidV7,
  JsonRpcMessage,
  signEd25519,
} from "@oms/shared-protocol";
import { PresenceEngine } from "../src/transport/presence-engine.js";
import { PhoneSessionManager } from "../src/transport/session-manager.js";

describe("Module 02: Multi-Tier Transport & Presence Engine", () => {
  test("PresenceEngine EMA & Hysteresis prevents oscillation between AT_DESK and AWAY", () => {
    const engine = new PresenceEngine({
      emaAlpha: 0.5,
      arriveThresholdDbm: -68,
      leaveThresholdDbm: -82,
      arriveHoldMs: 3000,
      leaveHoldMs: 10000,
      disconnectGraceMs: 5000,
    });

    expect(engine.getSnapshot().state).toBe("AWAY");

    // 1. User walks near desk (-60 dBm)
    engine.onBleRssiSample(-60, 1000);
    expect(engine.getSnapshot().state).toBe("AWAY"); // Held < 3000ms
    engine.onBleRssiSample(-58, 4100);
    expect(engine.getSnapshot().state).toBe("AT_DESK"); // Held >= 3000ms

    // 2. Momentary signal dip (-75 dBm is between -68 and -82 hysteresis band) -> stays AT_DESK!
    engine.onBleRssiSample(-75, 5000);
    expect(engine.getSnapshot().state).toBe("AT_DESK");

    // 3. BLE disconnects for 6 seconds (> 5000ms grace) -> transitions to AWAY
    engine.onBleDisconnected(6000);
    expect(engine.getSnapshot().state).toBe("AT_DESK");
    engine.evaluateTimeouts(11500);
    expect(engine.getSnapshot().state).toBe("AWAY");
  });

  test("PhoneSessionManager pairing, mutual Ed25519 auth, RPC & chunked file stream", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-test-"));
    const macId = generateEd25519Identity("mac");
    const phoneId = generateEd25519Identity("xiaomi");
    const port = 29527;

    const manager = new PhoneSessionManager({
      hostIdentity: macId,
      lanPort: port,
      streamDownloadDir: tmpDir,
    });

    const creds = createEphemeralPairingCredentials();
    manager.setActivePairingCredentials(creds);
    await manager.start();

    // Connect simulated Xiaomi phone over WebSocket
    const ws = new WebSocket(`ws://127.0.0.1:${port}/?tier=lan`);
    ws.binaryType = "arraybuffer";
    const decoder = new FrameStreamDecoder();

    let pairedResolved!: () => void;
    const pairedPromise = new Promise<void>((r) => (pairedResolved = r));

    ws.onmessage = (ev) => {
      const frames = decoder.push(new Uint8Array(ev.data as ArrayBuffer));
      for (const frame of frames) {
        if (frame.frameType === FrameType.CONTROL_JSON_RPC) {
          const msg = decodeJsonRpcPayload<JsonRpcMessage>(frame.payload);
          if ("method" in msg && msg.method === "auth.helloChallenge") {
            // Send pair.request
            const hmacHex = computePairingHmac(
              creds.ephemeralToken,
              creds.pinCode,
              phoneId.deviceId,
              phoneId.publicKeyBase64Url
            );
            ws.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: "pair_1",
                method: "pair.request",
                params: {
                  phoneId: phoneId.deviceId,
                  phoneModel: "Xiaomi 15 Pro",
                  phoneName: "My Xiaomi",
                  phonePubKeyBase64Url: phoneId.publicKeyBase64Url,
                  pinCode: creds.pinCode,
                  hmacHex,
                  timestampMs: Date.now(),
                },
              })
            );
          } else if ("id" in msg && msg.id === "pair_1") {
            pairedResolved();
          } else if ("method" in msg && msg.method === "alarm.set") {
            // Respond to Mac -> Phone RPC call
            ws.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id!,
                result: { ok: true, mode: "system_alarm_intent" },
              })
            );
          }
        }
      }
    };

    await pairedPromise;
    expect(manager.getActiveTier()).toBe("LAN_WS");
    expect(manager.getTrustedDevices().length).toBe(1);

    // Test Mac -> Phone RPC call (`alarm.set`)
    const alarmRes = await manager.callPhone("alarm.set", {
      hour: 7,
      minute: 45,
      label: "Morning Standup",
    });
    expect(alarmRes.ok).toBe(true);
    expect(alarmRes.mode).toBe("system_alarm_intent");

    // Test Phone -> Mac chunked binary stream transfer
    const streamId = generateUuidV7();
    const fileContent = new TextEncoder().encode("Hello from Xiaomi Screenshot Stream!");
    const sha256 = crypto.createHash("sha256").update(fileContent).digest("hex");

    const completedPromise = new Promise<{ savedAbsPath: string }>((resolve) => {
      manager.once("streamCompleted", resolve);
    });

    ws.send(
      encodeStreamStartFrame({
        streamId,
        fileName: "screenshot_test.txt",
        mimeType: "text/plain",
        totalBytes: fileContent.byteLength,
        chunkSize: 16,
        totalChunks: Math.ceil(fileContent.byteLength / 16),
        sha256,
        intentTag: "ui_bug",
      })
    );

    const totalChunks = Math.ceil(fileContent.byteLength / 16);
    for (let i = 0; i < totalChunks; i++) {
      const part = fileContent.subarray(i * 16, Math.min((i + 1) * 16, fileContent.byteLength));
      ws.send(encodeStreamChunkFrame(streamId, i, part));
    }

    const completed = await completedPromise;
    expect(fs.readFileSync(completed.savedAbsPath, "utf8")).toBe(
      "Hello from Xiaomi Screenshot Stream!"
    );

    ws.close();
    await manager.stop();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
});
