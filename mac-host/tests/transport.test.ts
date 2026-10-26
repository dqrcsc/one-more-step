import { describe, expect, test } from "bun:test";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import {
  computeAuthHmac,
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
    // Retain this isolated fixture for inspection; never recursively delete user files.
    console.log(`Retained test fixture: ${tmpDir}`);
  });

  test("LAN Security: rejects trusted_xiaomi_sig bypass, enforces 1-time pairing token, and verifies mutual HMAC", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-sec-test-"));
    const macId = generateEd25519Identity("mac");
    const port = 29537;

    const manager = new PhoneSessionManager({
      hostIdentity: macId,
      lanPort: port,
      streamDownloadDir: tmpDir,
    });

    const creds = createEphemeralPairingCredentials();
    manager.setActivePairingCredentials({
      ...creds,
      pairingUri: `oms://pair?v=1&id=${macId.deviceId}&pin=${creds.pinCode}&token=${creds.ephemeralToken}`,
    });
    await manager.start();

    // 1. Verify /install page does NOT leak PIN or ephemeralToken in HTML
    const installHtml = await (await fetch(`http://127.0.0.1:${port}/install`)).text();
    expect(installHtml).not.toContain(creds.ephemeralToken);
    expect(installHtml).not.toContain(`oms://pair?v=1&id=${macId.deviceId}`);

    // 2. Verify /pair/resolve rejects wrong PIN and resolves right PIN
    const wrongPinResp = await fetch(`http://127.0.0.1:${port}/pair/resolve?pin=000000`);
    expect(wrongPinResp.status).toBe(403);
    const rightPinResp = await fetch(`http://127.0.0.1:${port}/pair/resolve?pin=${creds.pinCode}`);
    expect(rightPinResp.status).toBe(200);
    const rightPinJson = (await rightPinResp.json()) as any;
    expect(rightPinJson.ok).toBe(true);
    expect(rightPinJson.pairingUri).toContain(creds.ephemeralToken);

    // 3. Verify un-paired rogue client sending "trusted_xiaomi_sig" is strictly rejected
    const rogueWs = new WebSocket(`ws://127.0.0.1:${port}/?tier=lan`);
    rogueWs.binaryType = "arraybuffer";
    const rogueDecoder = new FrameStreamDecoder();
    const rogueErrorPromise = new Promise<number>((resolve) => {
      rogueWs.onmessage = (ev) => {
        const frames = rogueDecoder.push(new Uint8Array(ev.data as ArrayBuffer));
        for (const f of frames) {
          const msg = decodeJsonRpcPayload<any>(f.payload);
          if (msg.method === "auth.helloChallenge") {
            rogueWs.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: "rogue_auth_1",
                method: "auth.response",
                params: {
                  phoneId: "xiaomi_rogue_device",
                  noncePhoneHex: "deadbeef",
                  phoneSignatureBase64Url: "trusted_xiaomi_sig",
                  deviceSnapshot: {},
                },
              })
            );
          } else if (msg.id === "rogue_auth_1" && msg.error) {
            resolve(msg.error.code);
          }
        }
      };
    });
    expect(await rogueErrorPromise).toBe(-32012);
    expect(manager.getTrustedDevices().length).toBe(0);
    rogueWs.close();

    // 4. Legitimate phone pairs and receives 256-bit pairSecretHex; token is consumed immediately
    const phoneId = "xiaomi_legit_01";
    const phonePub = `pub_${phoneId}`;
    const ws1 = new WebSocket(`ws://127.0.0.1:${port}/?tier=lan`);
    ws1.binaryType = "arraybuffer";
    const dec1 = new FrameStreamDecoder();
    const pairSecretPromise = new Promise<string>((resolve) => {
      ws1.onmessage = (ev) => {
        for (const f of dec1.push(new Uint8Array(ev.data as ArrayBuffer))) {
          const msg = decodeJsonRpcPayload<any>(f.payload);
          if (msg.method === "auth.helloChallenge") {
            ws1.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: "pair_legit",
                method: "pair.request",
                params: {
                  phoneId,
                  phoneModel: "Xiaomi 15",
                  phoneName: "Legit Phone",
                  phonePubKeyBase64Url: phonePub,
                  pinCode: creds.pinCode,
                  hmacHex: computePairingHmac(creds.ephemeralToken, creds.pinCode, phoneId, phonePub),
                  timestampMs: Date.now(),
                },
              })
            );
          } else if (msg.id === "pair_legit" && msg.result?.ok) {
            resolve(msg.result.pairSecretHex);
          }
        }
      };
    });
    const pairSecretHex = await pairSecretPromise;
    expect(pairSecretHex.length).toBe(64);
    ws1.close();

    // 5. Verify pairing token is strictly one-time use (cannot be replayed after 1st pairing)
    const replayPinResp = await fetch(`http://127.0.0.1:${port}/pair/resolve?pin=${creds.pinCode}`);
    expect(replayPinResp.status).toBe(410);

    // 6. Verify mutual HMAC authentication on reconnect using pairSecretHex
    const ws2 = new WebSocket(`ws://127.0.0.1:${port}/?tier=lan`);
    ws2.binaryType = "arraybuffer";
    const dec2 = new FrameStreamDecoder();
    const mutualAuthPromise = new Promise<{ ok: boolean; validMacAck: boolean }>((resolve) => {
      let savedNonceMac = "";
      const noncePhoneHex = "aabbccddeeff00112233445566778899";
      ws2.onmessage = (ev) => {
        for (const f of dec2.push(new Uint8Array(ev.data as ArrayBuffer))) {
          const msg = decodeJsonRpcPayload<any>(f.payload);
          if (msg.method === "auth.helloChallenge") {
            savedNonceMac = msg.params.nonceMacHex;
            const sig = computeAuthHmac(
              pairSecretHex,
              `phone_auth:${savedNonceMac}:${macId.deviceId}:${phoneId}:${noncePhoneHex}`
            );
            ws2.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: "auth_reconnect_1",
                method: "auth.response",
                params: {
                  phoneId,
                  noncePhoneHex,
                  phoneSignatureBase64Url: sig,
                  deviceSnapshot: { batteryPct: 88, isCharging: true, lanIps: ["127.0.0.1"], screenLocked: false, permissions: {} },
                },
              })
            );
          } else if (msg.id === "auth_reconnect_1") {
            const expectedMacAck = computeAuthHmac(
              pairSecretHex,
              `mac_ack:${savedNonceMac}:${noncePhoneHex}:${macId.deviceId}:${phoneId}`
            );
            resolve({
              ok: Boolean(msg.result?.ok),
              validMacAck: msg.result?.macAuthHmacHex === expectedMacAck,
            });
          }
        }
      };
    });

    const mutualRes = await mutualAuthPromise;
    expect(mutualRes.ok).toBe(true);
    expect(mutualRes.validMacAck).toBe(true);

    ws2.close();
    await manager.stop();
    // Retain this isolated fixture for inspection; never recursively delete user files.
    console.log(`Retained test fixture: ${tmpDir}`);
  });
});
