import { expect, test } from "bun:test";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  decodeJsonRpcPayload, encodeFrame, encodeJsonRpcFrame, encodeStreamAckResumeFrame,
  establishSecureSession, FrameStreamDecoder, FrameType, generateEd25519Identity,
  generateSecureEphemeral, generateUuidV7, phoneStart,
} from "@oms/shared-protocol";
import { PhoneSessionManager } from "../src/transport/session-manager.js";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { secureMemoryPhone, simulatedFinish } from "./secure-phone-helper.js";

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "oms-secure-v2-"));
  const identity = generateEd25519Identity("mac");
  const manager = new PhoneSessionManager({ hostIdentity: identity, lanPort: 0, streamDownloadDir: root });
  const raw = manager as any;
  const token = crypto.randomBytes(32).toString("hex");
  const credentials = { ephemeralToken: token, expiresAtMs: Date.now() + 60000 };
  manager.setActivePairingCredentials(credentials);
  const connect = (overrides: Record<string, any> = {}) => secureMemoryPhone(raw, { phoneId: "phone_secure_test", macId: identity.deviceId, pskHex: token, ...overrides });
  return { root, identity, manager, raw, token, credentials, connect };
}
function rawPipe(manager: any) {
  const wire: Uint8Array[] = [];
  const pipe: any = { tier: "LAN_WS", authenticated: false, decoder: new FrameStreamDecoder(), sendRaw: (bytes: Uint8Array) => { wire.push(bytes.slice()); return true; }, close: () => {} };
  manager.onPipeOpened(pipe);
  const hello = decodeJsonRpcPayload<any>(new FrameStreamDecoder().push(wire[0])[0].payload).params;
  return { pipe, wire, hello };
}

test("pair token is reserved until encrypted finish and released on a failed connection", async () => {
  const f = fixture();
  const reserved = f.connect({ autoFinish: false });
  expect(f.manager.getTrustedDevices()).toHaveLength(0);
  expect(f.manager.getActiveTier()).toBe("DISCONNECTED");
  expect(f.raw.activePairingCreds).not.toBeNull();
  expect(() => f.connect({ phoneId: "racing_phone", autoFinish: false })).toThrow();
  expect(f.raw.activePairingCreds.reservedBy).toBe(reserved.pipe);
  f.raw.failPipe(reserved.pipe, "simulated failed connection");
  expect(f.raw.activePairingCreds.reservedBy).toBeUndefined();
  const completed = f.connect();
  expect(completed.pipe.authenticated).toBe(true);
  expect(f.raw.activePairingCreds).toBeNull();
  expect(f.manager.getTrustedDevices()[0].pairSecretHex).toBe(completed.pairSecretHex);
  await f.manager.stop();
});

test("wrong proof, wrong token, expired credentials and legacy trust fail closed", async () => {
  for (const failure of ["token", "proof", "expired", "legacy", "identity"]) {
    const f = fixture();
    const { pipe, hello } = rawPipe(f.raw);
    const ephemeral = generateSecureEphemeral();
    const psk = failure === "token" ? "00".repeat(32) : f.token;
    const mode = failure === "legacy" ? "auth" : "pair";
    const start = phoneStart(failure === "identity" ? { ...hello, macId: "wrong_mac" } : hello, mode, "phone_secure_test", psk, ephemeral);
    if (failure === "proof") start.proofHex = "00".repeat(32);
    if (failure === "expired") f.raw.activePairingCreds.expiresAtMs = Date.now() - 1;
    if (failure === "legacy") f.raw.trustedDevices.set(start.phoneId, { phoneId: start.phoneId, pairSecretHex: f.token });
    f.raw.onRawBytesReceived(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", method: "secure.start", params: start }));
    expect(pipe.closed).toBe(true);
    expect(pipe.authenticated).toBe(false);
    expect(f.manager.getActiveTier()).toBe("DISCONNECTED");
    await f.manager.stop();
  }
});

test("handshake timeout and unconfirmed byte budget destroy context and release reservation", async () => {
  const f = fixture();
  const originalTimeout = globalThis.setTimeout;
  let expire!: () => void;
  globalThis.setTimeout = ((fn: (...args: any[]) => void, ms: number, ...args: any[]) => {
    if (ms === 30000) expire = () => fn(...args);
    return originalTimeout(fn, ms, ...args);
  }) as typeof setTimeout;
  let pending: ReturnType<typeof f.connect>;
  try { pending = f.connect({ autoFinish: false }); } finally { globalThis.setTimeout = originalTimeout; }
  expire();
  expect(pending!.pipe.closed).toBe(true);
  expect(pending!.pipe.secure).toBeUndefined();
  expect(f.raw.activePairingCreds.reservedBy).toBeUndefined();
  const oversized = rawPipe(f.raw);
  const header = encodeFrame(FrameType.CONTROL_JSON_RPC, new Uint8Array(100000)).subarray(0, 8);
  f.raw.onRawBytesReceived(oversized.pipe, header);
  f.raw.onRawBytesReceived(oversized.pipe, new Uint8Array(65529));
  expect(oversized.pipe.closed).toBe(true);
  expect(f.manager.getTrustedDevices()).toHaveLength(0);
  await f.manager.stop();
});

test("only secure.finish is accepted before confirmation and invalid snapshot cannot become trusted", async () => {
  for (const failure of ["business", "snapshot", "oversized"]) {
    const f = fixture();
    const pending = f.connect({ autoFinish: false });
    const request = failure === "business"
      ? { jsonrpc: "2.0" as const, id: "not_finish", method: "inbox.submitItem", params: { textContent: "hidden" } }
      : { jsonrpc: "2.0" as const, id: "secure_finish", method: "secure.finish", params: { ...simulatedFinish, deviceSnapshot: failure === "snapshot" ? { ...simulatedFinish.deviceSnapshot, batteryPct: "not a number" } : { ...simulatedFinish.deviceSnapshot, foregroundPackage: "x".repeat(40000) } } };
    pending.send(encodeJsonRpcFrame(request));
    expect(pending.pipe.closed).toBe(true);
    expect(f.manager.getTrustedDevices()).toHaveLength(0);
    expect(f.raw.activePairingCreds.reservedBy).toBeUndefined();
    await f.manager.stop();
  }
});

test("ready connection rejects plaintext, replay, tampering and foreign-session records", async () => {
  for (const failure of ["plaintext", "replay", "tamper", "foreign"]) {
    const f = fixture(); const phone = f.connect();
    let received = 0;
    f.manager.on("phoneRequest", () => { received++; });
    const request = encodeJsonRpcFrame({ jsonrpc: "2.0", id: "probe", method: "inbox.submitItem", params: {} });
    let record = phone.session.seal(request);
    if (failure === "plaintext") record = request;
    if (failure === "tamper") record[record.length - 1] ^= 1;
    if (failure === "foreign") {
      const second = secureMemoryPhone(f.raw, { phoneId: "phone_secure_test", macId: f.identity.deviceId, pskHex: phone.pairSecretHex, mode: "auth", tier: "WAN_WEBRTC" });
      record = second.session.seal(request);
    }
    f.raw.onRawBytesReceived(phone.pipe, record);
    if (failure === "replay") {
      expect(received).toBe(1);
      f.raw.onRawBytesReceived(phone.pipe, record);
    }
    expect(phone.pipe.closed).toBe(true);
    expect(received).toBe(failure === "replay" ? 1 : 0);
    await f.manager.stop();
  }
});

test("encrypted RPC responses and delivery ACKs remain bound to the issuing physical pipe", async () => {
  const f = fixture(); const phone = f.connect();
  const other = secureMemoryPhone(f.raw, { phoneId: "phone_secure_test", macId: f.identity.deviceId, pskHex: phone.pairSecretHex, mode: "auth", tier: "WAN_WEBRTC" });
  let resolved = false;
  const rpc = f.manager.callPhone("sys.getStatus", {}, 1000).then((result) => { resolved = true; return result; });
  const request = phone.messages.at(-1)!.value;
  const reply = encodeJsonRpcFrame({ jsonrpc: "2.0", id: request.id, result: { ok: true } });
  other.send(reply);
  await Promise.resolve(); expect(resolved).toBe(false);
  phone.send(reply); expect(await rpc).toEqual({ ok: true });
  let delivered = false;
  const stream = f.manager.pushStreamToPhone({ fileName: "test", mimeType: "text/plain", data: new Uint8Array(), timeoutMs: 1000 }).then((result) => { delivered = true; return result; });
  const streamId = phone.messages.filter((message: any) => message.frameType === FrameType.STREAM_START).at(-1).value.streamId;
  const ack = encodeStreamAckResumeFrame({ streamId, status: "COMPLETED", nextExpectedChunkIndex: 1 });
  other.send(ack); await Promise.resolve(); expect(delivered).toBe(false);
  phone.send(ack); expect((await stream).streamId).toBe(streamId);
  await f.manager.stop();
});

test("identical same-connection start/finish retry is idempotent without reusing encrypted records", async () => {
  const f = fixture(); const phone = f.connect({ autoFinish: false });
  const originalSession = phone.pipe.secure.session;
  f.raw.onRawBytesReceived(phone.pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", method: "secure.start", params: phone.start }));
  expect(phone.pipe.secure.session).toBe(originalSession);
  let paired = 0; f.manager.on("devicePaired", () => { paired++; });
  phone.finish();
  const firstAck = phone.wire.at(-1)!;
  phone.finish();
  expect(paired).toBe(1);
  expect(phone.pipe.authenticated).toBe(true);
  expect(Buffer.from(phone.wire.at(-1)!)).not.toEqual(Buffer.from(firstAck));
  expect(f.manager.getTrustedDevices()).toHaveLength(1);
  await f.manager.stop();
});

test("trust-store failure or dropped finish ACK never activates a candidate; queued -1 ACK does", async () => {
  for (const failure of ["persist", "dropped", "backpressure"]) {
    const f = fixture();
    const phone = f.connect({ autoFinish: false });
    const saved: any[] = [];
    f.manager.on("trustStore", (devices) => {
      if (failure === "persist") throw new Error("synthetic save failure");
      saved.push(devices);
    });
    const send = phone.pipe.sendRaw;
    if (failure === "dropped") phone.pipe.sendRaw = () => 0;
    if (failure === "backpressure") phone.pipe.sendRaw = (bytes: Uint8Array) => { send(bytes); return -1; };
    phone.finish();
    if (failure === "backpressure") {
      expect(phone.pipe.authenticated).toBe(true);
      expect(f.manager.getTrustedDevices()).toHaveLength(1);
      expect(saved).toHaveLength(1);
    } else {
      expect(phone.pipe.authenticated).toBe(false);
      expect(phone.pipe.closed).toBe(true);
      expect(f.manager.getTrustedDevices()).toHaveLength(0);
      expect(f.raw.activePairingCreds.reservedBy).toBeUndefined();
      if (failure === "dropped") expect(saved).toEqual([expect.any(Array), []]);
    }
    await f.manager.stop();
  }
});

test("daemon generates local v2 token without PIN and atomically preserves old trust on persistence failure", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "oms-secure-persistence-"));
  const daemon = new OmsDaemon(root);
  const pair = await daemon.handleIpcAction("daemon.pair", {});
  const uri = new URL(pair.pairingUri);
  expect(uri.searchParams.get("v")).toBe("2");
  expect(uri.searchParams.get("token")).toMatch(/^[a-f0-9]{64}$/);
  expect(uri.searchParams.has("pin")).toBe(false);
  expect(pair.pinCode).toBeUndefined();
  const trustPath = path.join(root, "keystore", "trusted_devices.json");
  fs.writeFileSync(trustPath, "[]");
  const originalRename = fs.renameSync;
  fs.renameSync = ((from: any, to: any) => {
    if (String(to) === trustPath) throw new Error("synthetic trust rename failure");
    return originalRename(from, to);
  }) as typeof fs.renameSync;
  try {
    expect(() => daemon.session.emit("trustStore", [{ phoneId: "candidate" }])).toThrow("rename failure");
    expect(fs.readFileSync(trustPath, "utf8")).toBe("[]");
  } finally { fs.renameSync = originalRename; }
  expect(fs.readdirSync(path.dirname(trustPath)).filter((name) => name.endsWith(".tmp"))).toEqual([]);
  await daemon.stop();
});

test("failed trust rollback closes old sessions and requires explicit re-pair", async () => {
  const f = fixture();
  const old = f.connect();
  f.manager.setActivePairingCredentials(f.credentials);
  const replacement = f.connect({ phoneId: "replacement_phone", autoFinish: false, tier: "WAN_WEBRTC" });
  let writes = 0;
  f.manager.on("trustStore", () => {
    if (++writes === 2) throw new Error("synthetic rollback failure");
  });
  replacement.pipe.sendRaw = () => 0;
  replacement.finish();
  expect(old.pipe.closed).toBe(true);
  expect(replacement.pipe.closed).toBe(true);
  expect(f.manager.getSecurityStatus().requiresRePair).toBe(true);
  expect(() => secureMemoryPhone(f.raw, { phoneId: "phone_secure_test", macId: f.identity.deviceId, pskHex: old.pairSecretHex, mode: "auth" })).toThrow();
  f.manager.removeAllListeners("trustStore");
  const repaired = f.connect({ phoneId: "repaired_phone" });
  expect(repaired.pipe.authenticated).toBe(true);
  expect(f.manager.getSecurityStatus().requiresRePair).toBe(false);
  await f.manager.stop();
});
