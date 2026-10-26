import { expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { generateEd25519Identity, generateUuidV7, FrameStreamDecoder, FrameType, decodeJsonRpcPayload, encodeStreamAckResumeFrame, computePairingHmac, createEphemeralPairingCredentials, computeAuthHmac } from "@oms/shared-protocol";
import { PhoneSessionManager } from "../src/transport/session-manager.js";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { approvalTimeoutSec, ipcTimeoutForAction } from "../src/daemon/request-timeouts.js";

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "oms-reliability-"));
  const identity = generateEd25519Identity("mac");
  const phone = generateEd25519Identity("phone");
  const trusted = { phoneId: phone.deviceId, phoneModel: "test", phoneName: "test", phonePubKeyBase64Url: phone.publicKeyBase64Url, wanRoomId: "public", pairSecretHex: "ab".repeat(32), pairedAtMs: Date.now(), lastSeenMs: Date.now() };
  const manager = new PhoneSessionManager({ hostIdentity: identity, lanPort: 0, streamDownloadDir: root, trustedDevices: [trusted] });
  const raw = manager as any;
  const messages: any[] = [];
  const pipe: any = { tier: "LAN_WS", authenticated: true, phoneId: phone.deviceId, decoder: new FrameStreamDecoder(), sendRaw: (bytes: Uint8Array) => {
    for (const f of new FrameStreamDecoder().push(bytes)) messages.push({ frameType: f.frameType, value: f.frameType === FrameType.STREAM_CHUNK ? undefined : decodeJsonRpcPayload(f.payload) });
  }, close: () => {} };
  raw.pipes.set(pipe.tier, pipe); raw.openPipes.add(pipe); raw.recomputeActiveTier();
  return { manager, raw, pipe, messages, identity, phone, trusted, root };
}
function header(data: Uint8Array) {
  return { streamId: generateUuidV7(), fileName: "test.txt", mimeType: "text/plain", totalBytes: data.length, chunkSize: 4, totalChunks: Math.max(1, Math.ceil(data.length / 4)), sha256: crypto.createHash("sha256").update(data).digest("hex") };
}

test("RPC ignores unauthenticated and other-pipe replies, accepts issuing pipe", async () => {
  const f = fixture();
  let done = false;
  const response = f.manager.callPhone("status", {}, 1000).then(v => { done = true; return v; });
  const id = f.messages.at(-1).value.id;
  const msg = { jsonrpc: "2.0", id, result: "valid" };
  f.raw.handleJsonRpcMessage({ ...f.pipe, authenticated: false }, msg);
  f.raw.handleJsonRpcMessage({ ...f.pipe }, msg);
  await Promise.resolve(); expect(done).toBe(false);
  f.raw.handleJsonRpcMessage(f.pipe, msg);
  expect(await response).toBe("valid");
  await f.manager.stop();
});

test("legacy static and public-room signatures cannot authenticate; pair-secret HMAC can", async () => {
  const f = fixture();
  const record = f.raw.trustedDevices.get(f.phone.deviceId);
  delete record.pairSecretHex;
  for (const signature of ["trusted_xiaomi_sig", computeAuthHmac(record.wanRoomId, `phone_auth:nonce:${f.identity.deviceId}:${f.phone.deviceId}:phone-nonce`)]) {
    const pipe = { ...f.pipe, authenticated: false, pendingNonceMac: "nonce" };
    f.raw.handleAuthResponse(pipe, { id: "auth", params: { phoneId: f.phone.deviceId, noncePhoneHex: "phone-nonce", phoneSignatureBase64Url: signature } });
    expect(pipe.authenticated).toBe(false);
  }
  record.pairSecretHex = "ab".repeat(32);
  const pipe = { ...f.pipe, authenticated: false, pendingNonceMac: "nonce" };
  f.raw.handleAuthResponse(pipe, { id: "auth", params: { phoneId: f.phone.deviceId, noncePhoneHex: "phone-nonce", phoneSignatureBase64Url: computeAuthHmac(record.pairSecretHex, `phone_auth:nonce:${f.identity.deviceId}:${f.phone.deviceId}:phone-nonce`), deviceSnapshot: {} } });
  expect(pipe.authenticated).toBe(true);
  await f.manager.stop();
});

test("re-pair revokes old pipes and pending calls", async () => {
  const f = fixture(); let oldClosed = false;
  f.pipe.close = () => { oldClosed = true; };
  const pending = f.manager.callPhone("status", {}, 1000).catch(e => e.message);
  const creds = createEphemeralPairingCredentials(); f.manager.setActivePairingCredentials(creds);
  const newPhone = generateEd25519Identity("new");
  const newPipe = { ...f.pipe, authenticated: false, tier: "WAN_WEBRTC", close: () => {} };
  f.raw.onPipeOpened(newPipe);
  f.raw.handlePairRequest(newPipe, { id: "pair", params: { phoneId: newPhone.deviceId, phonePubKeyBase64Url: newPhone.publicKeyBase64Url, pinCode: creds.pinCode, hmacHex: computePairingHmac(creds.ephemeralToken, creds.pinCode, newPhone.deviceId, newPhone.publicKeyBase64Url) } });
  expect(oldClosed).toBe(true); expect(f.pipe.authenticated).toBe(false);
  expect(await pending).toContain("Transport closed");
  let received = false; f.manager.on("phoneRequest", () => { received = true; });
  f.raw.handleJsonRpcMessage(f.pipe, { id: "old", method: "inbox.submitItem", params: {} });
  expect(received).toBe(false);
  await f.manager.stop();
});

test("incoming streams reject bad hashes, invalid indices, oversized headers and foreign chunks", async () => {
  const f = fixture(); const data = new TextEncoder().encode("abcd");
  const bad = { ...header(data), sha256: "00".repeat(32) };
  f.raw.handleStreamStart(f.pipe, bad); f.raw.handleStreamChunk(f.pipe, bad.streamId, 0, data);
  expect(f.manager.getCompletedStream(bad.streamId)).toBeUndefined(); expect(f.messages.at(-1).value.status).toBe("ERROR");
  const h = header(data); f.raw.handleStreamStart(f.pipe, h);
  f.raw.handleStreamChunk({ ...f.pipe }, h.streamId, 0, data);
  expect(f.manager.getCompletedStream(h.streamId)).toBeUndefined();
  f.raw.handleStreamChunk(f.pipe, h.streamId, 1, data); expect(f.messages.at(-1).value.status).toBe("ERROR");
  f.raw.handleStreamStart(f.pipe, { ...h, totalBytes: 64 * 1024 * 1024 + 1 }); expect(f.messages.at(-1).value.status).toBe("ERROR");
  f.raw.handleStreamStart(f.pipe, { ...h, streamId: "../escape" }); expect(f.messages.at(-1).value.status).toBe("ERROR");
  await f.manager.stop();
});

test("verified stream commits bytes once and refuses duplicate ID metadata changes", async () => {
  const f = fixture(); const data = new TextEncoder().encode("abcdefgh"); const h = header(data);
  f.raw.handleStreamStart(f.pipe, h);
  f.raw.handleStreamChunk(f.pipe, h.streamId, 1, data.slice(4));
  f.raw.handleStreamChunk(f.pipe, h.streamId, 0, data.slice(0, 4));
  const saved = await f.manager.waitForCompletedStream(h.streamId);
  expect(fs.readFileSync(saved.savedAbsPath).toString()).toBe("abcdefgh");
  f.raw.handleStreamStart(f.pipe, { ...h, fileName: "other.txt" }); expect(f.messages.at(-1).value.status).toBe("ERROR");
  await f.manager.stop();
});

test("outbound delivery requires matching source ACK and rejects errors or timeout", async () => {
  const f = fixture(); const data = new Uint8Array([1]); let done = false;
  const result = f.manager.pushStreamToPhone({ fileName: "a", mimeType: "x", data, timeoutMs: 1000 }).then(v => { done = true; return v; });
  const id = f.messages.find(m => m.frameType === FrameType.STREAM_START).value.streamId;
  const ack = { streamId: id, status: "COMPLETED", nextExpectedChunkIndex: 1 };
  f.raw.onRawBytesReceived({ ...f.pipe }, encodeStreamAckResumeFrame(ack as any));
  await Promise.resolve(); expect(done).toBe(false);
  f.raw.onRawBytesReceived(f.pipe, encodeStreamAckResumeFrame(ack as any));
  expect((await result).streamId).toBe(id);
  const failure = f.manager.pushStreamToPhone({ fileName: "a", mimeType: "x", data, timeoutMs: 1000 });
  const failedId = f.messages.filter(m => m.frameType === FrameType.STREAM_START).at(-1).value.streamId;
  f.raw.handleStreamAck(f.pipe, { streamId: failedId, status: "ERROR", errorMessage: "disk full" });
  await expect(failure).rejects.toThrow("disk full");
  await expect(f.manager.pushStreamToPhone({ fileName: "a", mimeType: "x", data, timeoutMs: 5 })).rejects.toThrow("unconfirmed");
  await f.manager.stop();
});

test("stream wait timeout removes listeners", async () => {
  const f = fixture(); const count = f.manager.listenerCount("streamCompleted");
  await expect(f.manager.waitForCompletedStream(generateUuidV7(), 5)).rejects.toThrow("Timed out");
  expect(f.manager.listenerCount("streamCompleted")).toBe(count);
  await f.manager.stop();
});

test("approval registers before phone response and preserves custom options", async () => {
  const daemon = new OmsDaemon(fs.mkdtempSync(path.join(os.tmpdir(), "oms-approval-")));
  (daemon.session as any).callPhone = async (_: string, p: any) => {
    let response: any;
    daemon.session.emit("phoneRequest", { method: "approval.respond", params: { approvalId: p.approvalId, approved: false, selectedOption: "稍后", decidedAtMs: Date.now() } }, (r: any) => response = r);
    expect(response.result.ack).toBe(true);
    return { ok: true };
  };
  const result = await (daemon as any).handleIpcAction("phone_request_approval", { timeoutSec: 1, options: ["执行", "稍后", "拒绝"] });
  expect(result.selectedOption).toBe("稍后"); expect(result.approved).toBe(false);
  let unknown: any;
  daemon.session.emit("phoneRequest", { method: "approval.respond", params: { approvalId: "missing", approved: true, selectedOption: "执行" } }, (r: any) => unknown = r);
  expect(unknown.error).toBeDefined();
  await daemon.stop();
});

test("approval deadlines align across CLI/MCP IPC and reject invalid timeout", () => {
  expect(ipcTimeoutForAction("phone_request_approval", {})).toBe(1820000);
  expect(ipcTimeoutForAction("phone_gui_action", {})).toBeGreaterThan(65000);
  for (const n of [0, -1, NaN, Infinity, 3601, 1.5]) expect(() => approvalTimeoutSec(n)).toThrow();
});

test("same-connection re-pair also revokes old RPC and stream ACK waiters", async () => {
  const f = fixture();
  const rpc = f.manager.callPhone("old", {}, 1000).catch(e => e.message);
  const stream = f.manager.pushStreamToPhone({ fileName: "old", mimeType: "x", data: new Uint8Array([1]), timeoutMs: 1000 }).catch(e => e.message);
  const creds = createEphemeralPairingCredentials(); f.manager.setActivePairingCredentials(creds);
  f.raw.handlePairRequest(f.pipe, { id: "pair", params: { phoneId: f.phone.deviceId, phonePubKeyBase64Url: f.phone.publicKeyBase64Url, pinCode: creds.pinCode, hmacHex: computePairingHmac(creds.ephemeralToken, creds.pinCode, f.phone.deviceId, f.phone.publicKeyBase64Url) } });
  expect(await rpc).toContain("Transport closed"); expect(await stream).toContain("Transport closed");
  expect(f.raw.pendingRpc.size).toBe(0); expect(f.raw.outgoingStreams.size).toBe(0);
  expect(f.pipe.authenticated).toBe(true);
  await f.manager.stop();
});

test("GUI and system actions share an absolute deadline within outer wait budgets", async () => {
  const daemon = new OmsDaemon(fs.mkdtempSync(path.join(os.tmpdir(), "oms-gui-deadline-")));
  const calls: any[] = [];
  (daemon.session as any).callPhone = async (method: string, params: any, timeout: number) => { calls.push({ method, params, timeout }); return { ok: true, status: {} }; };
  for (const action of ["phone_gui_action", "phone_device_control"]) {
    const params = { action: action === "phone_gui_action" ? "click" : "system_action", target: "home", targetNodeId: "#1", observationId: "observed" };
    const before = Date.now();
    await (daemon as any).handleIpcAction(action, params);
    const call = calls.at(-1);
    expect(call.params.deadlineMs).toBeGreaterThanOrEqual(before + 60000);
    expect(call.params.deadlineMs).toBeLessThanOrEqual(Date.now() + 60000);
    expect(call.timeout).toBe(65000);
    expect(ipcTimeoutForAction(action, params)).toBe(70000);
  }
  await daemon.stop();
});

test("approval rejects invalid option mappings and late responses, accepts an identical retry", async () => {
  const daemon = new OmsDaemon(fs.mkdtempSync(path.join(os.tmpdir(), "oms-approval-validation-")));
  let request: any;
  (daemon.session as any).callPhone = async (_: string, params: any) => { request = params; return {}; };
  const promise = (daemon as any).handleIpcAction("phone_request_approval", { timeoutSec: 1, options: ["execute", "reject"] });
  function respond(selectedOption: string, approved: boolean) {
    let reply: any;
    daemon.session.emit("phoneRequest", { method: "approval.respond", params: { approvalId: request.approvalId, selectedOption, approved, decidedAtMs: Date.now() } }, (r: any) => reply = r);
    return reply;
  }
  expect(respond("missing", true).error).toBeDefined();
  expect(respond("reject", true).error).toBeDefined();
  expect(respond("execute", true).result.ack).toBe(true);
  expect((await promise).approved).toBe(true);
  expect(respond("execute", true).result.ack).toBe(true);
  expect(respond("reject", false).error).toBeDefined();
  const expired = (daemon as any).handleIpcAction("phone_request_approval", { timeoutSec: 1, options: ["execute", "reject"] });
  await expect(expired).rejects.toThrow("timed out");
  expect(respond("execute", true).error).toBeDefined();
  await daemon.stop();
});
