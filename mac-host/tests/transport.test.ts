import { describe, expect, test } from "bun:test";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import {
  decodeJsonRpcPayload,
  decodeStreamChunkPayload,
  encodeJsonRpcFrame,
  encodeStreamAckResumeFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  FrameType,
  generateEd25519Identity,
  generateUuidV7,
} from "@oms/shared-protocol";
import { PresenceEngine } from "../src/transport/presence-engine.js";
import { connectSecurePhone, SecureSimulatedPhone } from "./secure-phone-helper.js";
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

  test("Secure v2 pairing protects RPC and bidirectional chunked delivery", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-test-"));
    const mac = generateEd25519Identity("mac");
    const phoneId = generateEd25519Identity("phone").deviceId;
    const manager = new PhoneSessionManager({ hostIdentity: mac, lanPort: 0, streamDownloadDir: tmpDir });
    const token = crypto.randomBytes(32).toString("hex");
    manager.setActivePairingCredentials({ ephemeralToken: token, expiresAtMs: Date.now() + 60000 });
    await manager.start();
    const url = `ws://127.0.0.1:${(manager as any).wsServer.port}`;
    const phone = await connectSecurePhone(url, {
      macId: mac.deviceId, phoneId, pskHex: token,
      onFrame(frame, simulated) {
        if (frame.frameType === FrameType.CONTROL_JSON_RPC) {
          const message = decodeJsonRpcPayload<any>(frame.payload);
          if (message.method === "alarm.set") simulated.send(encodeJsonRpcFrame({ jsonrpc: "2.0", id: message.id, result: { ok: true, mode: "system_alarm_intent" } }));
        }
        if (frame.frameType === FrameType.STREAM_START) {
          const header = decodeJsonRpcPayload<any>(frame.payload);
          (simulated as any).outboundHeader = header;
          (simulated as any).outboundChunks = 0;
          (simulated as any).outboundData = [];
        }
        if (frame.frameType === FrameType.STREAM_CHUNK) {
          (simulated as any).outboundChunks++;
          (simulated as any).outboundData.push(decodeStreamChunkPayload(frame.payload).data);
          const header = (simulated as any).outboundHeader;
          if ((simulated as any).outboundChunks === header.totalChunks) {
            const received = Buffer.concat((simulated as any).outboundData);
            expect(received.length).toBe(header.totalBytes);
            expect(crypto.createHash("sha256").update(received).digest("hex")).toBe(header.sha256);
            simulated.send(encodeStreamAckResumeFrame({ streamId: header.streamId, status: "COMPLETED", nextExpectedChunkIndex: header.totalChunks }));
          }
        }
      },
    });
    try {
      expect(manager.getActiveTier()).toBe("LAN_WS");
      expect(manager.getTrustedDevices()[0].pairingVersion).toBe(2);
      expect(manager.getTrustedDevices()[0].pairSecretHex).toBe(phone.pairSecretHex);
      expect(manager.getPhoneStatus()?.secureProtocolVersion).toBe(2);
      const alarm = await manager.callPhone<any>("alarm.set", { hour: 7, minute: 45, label: "PRIVATE_ALARM_SENTINEL" });
      expect(alarm).toEqual({ ok: true, mode: "system_alarm_intent" });
      const large = Buffer.alloc(65 * 1024, 0x5a);
      Buffer.from("PRIVATE_PHONE_FILE_SENTINEL").copy(large);
      for (const content of [Buffer.alloc(0), Buffer.from("PRIVATE_PHONE_FILE_SENTINEL"), large]) {
        const streamId = generateUuidV7();
        const chunkSize = 65536;
        const totalChunks = Math.max(1, Math.ceil(content.length / chunkSize));
        const complete = new Promise<any>((resolve) => manager.once("streamCompleted", resolve));
        phone.send(encodeStreamStartFrame({ streamId, fileName: "PRIVATE_FILENAME_SENTINEL.txt", mimeType: "text/plain", totalBytes: content.length, chunkSize, totalChunks, sha256: crypto.createHash("sha256").update(content).digest("hex") }));
        for (let i = 0; i < totalChunks; i++) phone.send(encodeStreamChunkFrame(streamId, i, content.subarray(i * chunkSize, (i + 1) * chunkSize)));
        const saved = await complete;
        expect(fs.readFileSync(saved.savedAbsPath)).toEqual(content);
        const outbound = Buffer.from(content);
        if (outbound.length) Buffer.from("PRIVATE_MAC_BODY_SENTINEL").copy(outbound);
        const delivered = await manager.pushStreamToPhone({ fileName: "PRIVATE_MAC_FILENAME.txt", mimeType: "text/plain", data: outbound, timeoutMs: 1000 });
        expect(delivered.streamId).toBeTruthy();
        expect(delivered.sha256).toBe(crypto.createHash("sha256").update(outbound).digest("hex"));
      }
      const captured = Buffer.concat(phone.wire.map((bytes) => Buffer.from(bytes))).toString("latin1");
      for (const sensitive of [token, phone.pairSecretHex, "PRIVATE_ALARM_SENTINEL", "PRIVATE_PHONE_FILE_SENTINEL", "PRIVATE_FILENAME_SENTINEL", "PRIVATE_MAC_FILENAME", "PRIVATE_MAC_BODY_SENTINEL", "batteryPct", "Simulated phone"]) expect(captured).not.toContain(sensitive);
    } finally { phone.close(); await manager.stop(); }
  });

  test("secure LAN removes PIN resolution, consumes token once and reconnects without changing stored secret", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-sec-test-"));
    const mac = generateEd25519Identity("mac");
    const phoneId = "phone_secure_reconnect";
    const manager = new PhoneSessionManager({ hostIdentity: mac, lanPort: 0, streamDownloadDir: tmpDir });
    const token = crypto.randomBytes(32).toString("hex");
    manager.setActivePairingCredentials({ ephemeralToken: token, expiresAtMs: Date.now() + 60000, pairingUri: `oms://pair?v=2&token=${token}` });
    await manager.start();
    const port = (manager as any).wsServer.port;
    const url = `ws://127.0.0.1:${port}`;
    try {
      const html = await (await fetch(`http://127.0.0.1:${port}/install`)).text();
      expect(html).not.toContain(token); expect(html).not.toContain("pinInput"); expect(html).not.toContain("/pair/resolve");
      expect((await fetch(`http://127.0.0.1:${port}/pair/resolve?pin=000000`)).status).toBe(410);
      const first = await connectSecurePhone(url, { macId: mac.deviceId, phoneId, pskHex: token });
      const savedSecret = first.pairSecretHex;
      const firstNonce = first.hello!.nonceMacHex;
      first.close(); await first.closed;
      const replay = new SecureSimulatedPhone(url, { macId: mac.deviceId, phoneId: "phone_replay", pskHex: token });
      await expect(replay.ready).rejects.toThrow(); await replay.closed;
      for (let i = 0; i < 2; i++) {
        const auth = await connectSecurePhone(url, {
          macId: mac.deviceId, phoneId, pskHex: savedSecret, mode: "auth",
          onFrame(frame, simulated) {
            if (frame.frameType !== FrameType.CONTROL_JSON_RPC) return;
            const request = decodeJsonRpcPayload<any>(frame.payload);
            if (request.method === "sys.getStatus") simulated.send(encodeJsonRpcFrame({ jsonrpc: "2.0", id: request.id, result: { marker: "AUTH_RECONNECT_PRIVATE_MARKER" } }));
          },
        });
        expect(auth.hello!.nonceMacHex).not.toBe(firstNonce);
        expect(auth.pairSecretHex).toBe(savedSecret);
        expect(manager.getTrustedDevices()[0].pairSecretHex).toBe(savedSecret);
        expect(await manager.callPhone("sys.getStatus", { marker: "PRIVATE_AUTH_REQUEST_SENTINEL" })).toEqual({ marker: "AUTH_RECONNECT_PRIVATE_MARKER" });
        const wire = Buffer.concat(auth.wire.map((bytes) => Buffer.from(bytes))).toString("latin1");
        for (const sensitive of [token, savedSecret, "PRIVATE_AUTH_REQUEST_SENTINEL", "AUTH_RECONNECT_PRIVATE_MARKER", "batteryPct", "Simulated phone"]) expect(wire).not.toContain(sensitive);
        auth.close(); await auth.closed;
      }
      const rogue = new WebSocket(url);
      const rejected = new Promise<void>((resolve) => { rogue.onclose = () => resolve(); });
      rogue.onmessage = () => rogue.send(encodeJsonRpcFrame({ jsonrpc: "2.0", id: "rogue", method: "auth.response", params: { phoneSignatureBase64Url: "trusted_xiaomi_sig" } }));
      await rejected;
      expect(manager.getTrustedDevices()).toHaveLength(1);
    } finally { await manager.stop(); }
  });
});
