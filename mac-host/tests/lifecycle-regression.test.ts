import { expect, test } from "bun:test";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { BluetoothBridgeServer } from "../src/transport/bluetooth-bridge-client.js";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture() {
  const daemon = new OmsDaemon(fs.mkdtempSync(path.join(os.tmpdir(), "oms-lifecycle-")));
  const d = daemon as any;
  let tier = "LAN_WS";
  let viewers = 1;
  const calls: string[] = [];
  const s = daemon.session as any;
  s.getActiveTier = () => tier;
  s.getMirrorViewerCount = () => viewers;
  s.getPhoneStatus = () => null;
  s.bindPhoneSession = () => s.callPhone.bind(s);
  s.broadcastMirrorEvent = () => {};
  s.callPhone = async (method: string) => { calls.push(method); return { ok: true }; };
  d.getAirpodsStatusSnapshot = () => ({});
  return { daemon, d, s, calls, setTier: (v: string) => { tier = v; }, setViewers: (v: number) => { viewers = v; } };
}

test("offline AirPods handoff leaves Mac connected and reports failure", async () => {
  const f = fixture();
  f.setTier("DISCONNECTED");
  let releases = 0;
  f.s.getBluetoothBridge = () => ({ releaseAirpodsFromMac: async () => { releases++; return { ok: true }; } });
  const result = await f.daemon.switchAirpodsToPhone();
  expect(result.ok).toBe(false);
  expect(releases).toBe(0);
  expect(f.calls).toEqual([]);
});

test("AirPods handoff propagates unavailable/failed release and phone failure", async () => {
  const f = fixture();
  f.s.getBluetoothBridge = () => null;
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(false);
  f.s.getBluetoothBridge = () => ({ releaseAirpodsFromMac: async () => ({ ok: false, message: "release timeout" }) });
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(false);
  expect(f.calls).toEqual([]);
  f.s.getBluetoothBridge = () => ({ releaseAirpodsFromMac: async () => { throw new Error("bridge closed"); } });
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(false);
  f.s.getBluetoothBridge = () => ({ releaseAirpodsFromMac: async () => ({ ok: true }) });
  f.s.callPhone = async () => { throw new Error("phone closed"); };
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(false);
  f.s.callPhone = async () => ({ ok: false, message: "not paired" });
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(false);
  f.s.callPhone = async () => ({ ok: true });
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(true);
});

test("AirPods bridge release timeout is not a delivery acknowledgement", async () => {
  const bridge = new BluetoothBridgeServer({ socketPath: "/unused", macId: "test", lanIps: [], lanPort: 0 });
  (bridge as any).activeClient = {};
  (bridge as any).sendToBridge = () => {};
  const result = await bridge.releaseAirpodsFromMac({ timeoutMs: 1 });
  expect(result.ok).toBe(false);
});

test("a stop during pending mirror start wins and sends remote stop after start settles", async () => {
  const f = fixture();
  const started = deferred<any>();
  f.s.callPhone = async (method: string) => { f.calls.push(method); return method === "mirror.start" ? started.promise : { ok: true }; };
  const starting = f.daemon.startMirror();
  await Promise.resolve();
  const stopping = f.daemon.stopMirror();
  started.resolve({ ok: true });
  await Promise.all([starting, stopping]);
  expect(f.daemon.getMirrorStatus().running).toBe(false);
  expect(f.calls).toEqual(["mirror.start", "mirror.stop"]);
});

test("last viewer leaving during start still arms idle stop and multiple viewers share a start", async () => {
  const f = fixture();
  const started = deferred<any>();
  f.s.callPhone = async (method: string) => { f.calls.push(method); return method === "mirror.start" ? started.promise : { ok: true }; };
  const original = globalThis.setTimeout;
  let expire!: () => void;
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  globalThis.setTimeout = ((callback: () => void, delay?: number, ...args: any[]) => {
    if (delay === 25000) { expire = callback; idleTimer = original(() => {}, 60000); return idleTimer; }
    return original(callback, delay, ...args);
  }) as typeof setTimeout;
  try {
    f.s.emit("mirrorViewerChanged", 1);
    f.setViewers(2);
    f.s.emit("mirrorViewerChanged", 2);
    await Promise.resolve();
    f.setViewers(0);
    f.s.emit("mirrorViewerChanged", 0);
    expect(typeof expire).toBe("function");
    started.resolve({ ok: true });
    await f.d.mirrorLifecycleQueue;
    expect(f.calls.filter(x => x === "mirror.start")).toHaveLength(1);
    expire();
    await f.d.mirrorLifecycleQueue;
    expect(f.daemon.getMirrorStatus().running).toBe(false);
    expect(f.calls.at(-1)).toBe("mirror.stop");
  } finally {
    globalThis.setTimeout = original;
    if (idleTimer) clearTimeout(idleTimer);
  }
});

test("mirror start/stop failures do not poison subsequent lifecycle operations", async () => {
  const f = fixture();
  f.s.callPhone = async () => { throw new Error("start failed"); };
  await expect(f.daemon.startMirror()).rejects.toThrow("start failed");
  f.s.callPhone = async () => ({ ok: true });
  expect((await f.daemon.startMirror()).running).toBe(true);
  f.s.callPhone = async () => { throw new Error("stop failed"); };
  await expect(f.daemon.stopMirror()).rejects.toThrow("stop failed");
  f.s.callPhone = async () => ({ ok: true });
  expect((await f.daemon.startMirror()).running).toBe(true);
  await f.daemon.stopMirror();
});

test("daemon shutdown cancels pending mirror start and prevents restart", async () => {
  const f = fixture();
  const started = deferred<any>();
  f.s.callPhone = async (method: string) => { f.calls.push(method); return method === "mirror.start" ? started.promise : { ok: true }; };
  f.s.stop = async () => { f.calls.push("session.stop"); };
  const starting = f.daemon.startMirror();
  await Promise.resolve();
  const stopping = f.daemon.stop();
  started.resolve({ ok: true });
  await Promise.all([starting, stopping]);
  expect(f.daemon.getMirrorStatus().running).toBe(false);
  expect(f.calls).toEqual(["mirror.start", "mirror.stop", "session.stop"]);
  await expect(f.daemon.startMirror()).rejects.toThrow("stopping");
});

test("mirror operations are ordered when a new start follows a pending start and stop", async () => {
  const f = fixture();
  const firstResponse = deferred<any>();
  const firstEntered = deferred<void>();
  let starts = 0;
  let activeRpcs = 0;
  let maxActiveRpcs = 0;
  f.s.callPhone = async (method: string) => {
    f.calls.push(method);
    activeRpcs++;
    maxActiveRpcs = Math.max(maxActiveRpcs, activeRpcs);
    try {
      if (method === "mirror.start" && ++starts === 1) {
        firstEntered.resolve();
        return await firstResponse.promise;
      }
      return { ok: true };
    } finally { activeRpcs--; }
  };
  const first = f.daemon.startMirror();
  await firstEntered.promise;
  const stop = f.daemon.stopMirror();
  const restart = f.daemon.startMirror();
  firstResponse.resolve({ ok: true });
  await Promise.all([first, stop, restart]);
  expect(f.calls).toEqual(["mirror.start", "mirror.stop", "mirror.start"]);
  expect(maxActiveRpcs).toBe(1);
  expect(f.daemon.getMirrorStatus().running).toBe(true);
  await f.daemon.stopMirror();
});

test("mirror RPC explicit rejection and offline stop are not reported as confirmed success", async () => {
  const f = fixture();
  f.s.callPhone = async () => ({ ok: false, error: "permission revoked" });
  await expect(f.daemon.startMirror()).rejects.toThrow("permission revoked");
  expect(f.daemon.getMirrorStatus().running).toBe(false);
  f.s.callPhone = async () => ({ ok: true });
  await f.daemon.startMirror();
  f.setTier("DISCONNECTED");
  await expect(f.daemon.stopMirror()).rejects.toThrow("not confirmed");
});

test("a late legacy screenshot cannot feed or restart a replacement mirror session", async () => {
  const f = fixture();
  const screenshot = deferred<any>();
  const captureEntered = deferred<void>();
  let starts = 0;
  let streamReads = 0;
  f.s.waitForCompletedStream = async () => { streamReads++; return null; };
  f.s.callPhone = async (method: string) => {
    if (method === "mirror.start" && ++starts === 1) throw new Error("Unknown RPC method");
    if (method === "sys.control") { captureEntered.resolve(); return await screenshot.promise; }
    return { ok: true };
  };
  expect((await f.daemon.startMirror()).mode).toBe("legacy_poll");
  await captureEntered.promise;
  await f.daemon.stopMirror();
  expect((await f.daemon.startMirror()).mode).toBe("native_stream");
  screenshot.resolve({ streamId: "old-session-screenshot" });
  await Bun.sleep(0);
  expect(streamReads).toBe(0);
  expect(f.d.mirrorLegacyPollTimer).toBeNull();
  await f.daemon.stopMirror();
});

test("mirror input carries an absolute deadline and preserves shorter caller limits", async () => {
  const f = fixture();
  const sent: any[] = [];
  f.s.callPhone = async (_method: string, payload: any) => { sent.push(payload); return { ok: true }; };
  const before = Date.now();
  await f.daemon.sendMirrorInput({ type: "tap", normX: 0.5, normY: 0.5 });
  expect(sent[0].deadlineMs).toBeGreaterThanOrEqual(before + 5000);
  expect(sent[0].deadlineMs).toBeLessThanOrEqual(Date.now() + 5000);
  const shorter = Date.now() + 1000;
  await f.daemon.sendMirrorInput({ type: "tap", deadlineMs: shorter });
  expect(sent[1].deadlineMs).toBe(shorter);
  await expect(f.daemon.sendMirrorInput({ type: "tap", deadlineMs: 1 })).rejects.toThrow("ERR_GUI_DEADLINE");
  expect(sent).toHaveLength(2);
});

test("AirPods bridge supplies a native deadline and cancels a timed-out request", async () => {
  const bridge = new BluetoothBridgeServer({ socketPath: "/unused", macId: "test", lanIps: [], lanPort: 0 });
  const packets: any[] = [];
  (bridge as any).activeClient = {};
  (bridge as any).sendToBridge = (packet: any) => packets.push(packet);
  const started = Date.now();
  const result = await bridge.connectAirpodsToMac("AA:BB:CC:DD:EE:FF", 10);
  expect(result.ok).toBe(false);
  expect(packets[0].deadlineMs).toBeGreaterThanOrEqual(started);
  expect(packets[0].deadlineMs).toBeLessThanOrEqual(started + 10);
  expect(packets[1]).toEqual({ type: "airpods_cancel", reqId: packets[0].reqId });
  (bridge as any).handleBridgeMessage({ type: "airpods_cmd_result", reqId: packets[0].reqId, ok: true });
  expect((bridge as any).pendingCmdWaiters.size).toBe(0);
  expect(result.ok).toBe(false);
});

test("AirPods native budget ends before the bridge caller and confirms native result", async () => {
  const bridge = new BluetoothBridgeServer({ socketPath: "/unused", macId: "test", lanIps: [], lanPort: 0 });
  (bridge as any).activeClient = {};
  let request: any;
  (bridge as any).sendToBridge = (packet: any) => {
    request = packet;
    queueMicrotask(() => (bridge as any).handleBridgeMessage({ type: "airpods_cmd_result", reqId: packet.reqId, ok: true }));
  };
  const start = Date.now();
  expect((await bridge.connectAirpodsToMac()).ok).toBe(true);
  expect(request.deadlineMs - start).toBeGreaterThanOrEqual(22000);
  expect(request.deadlineMs - start).toBeLessThan(23000);
});

test("phone release failure prevents a Mac connection attempt", async () => {
  const f = fixture();
  let connections = 0;
  f.s.callPhone = async () => ({ ok: false, message: "release not confirmed" });
  f.s.getBluetoothBridge = () => ({ connectAirpodsToMac: async () => { connections++; return { ok: true }; } });
  const result = await f.daemon.switchAirpodsToMac("AA:BB:CC:DD:EE:FF");
  expect(result.ok).toBe(false);
  expect(result.stage).toBe("phone_release");
  expect(connections).toBe(0);
});

test("phone handoff stays bound to the original phone session during Mac release", async () => {
  const f = fixture();
  let newSessionCalls = 0;
  f.s.bindPhoneSession = () => async () => { throw new Error("session revoked"); };
  f.s.callPhone = async () => { newSessionCalls++; return { ok: true }; };
  f.s.getBluetoothBridge = () => ({ releaseAirpodsFromMac: async () => ({ ok: true }) });
  const result = await f.daemon.switchAirpodsToPhone();
  expect(result.ok).toBe(false);
  expect(result.stage).toBe("phone_connect");
  expect(newSessionCalls).toBe(0);
});

test("phone connection deadline leaves time for its response", async () => {
  const f = fixture();
  f.s.getBluetoothBridge = () => ({ releaseAirpodsFromMac: async () => ({ ok: true }) });
  f.s.callPhone = async (_method: string, params: any, timeoutMs: number) => {
    expect(timeoutMs).toBe(16000);
    expect(params.deadlineMs - Date.now()).toBeGreaterThan(13900);
    expect(params.deadlineMs - Date.now()).toBeLessThanOrEqual(14000);
    return { ok: true };
  };
  expect((await f.daemon.switchAirpodsToPhone()).ok).toBe(true);
});

test("overlapping handoffs do not dispatch interleaved phone and Mac effects", async () => {
  const f = fixture();
  const released = deferred<any>();
  let macConnections = 0;
  f.s.getBluetoothBridge = () => ({
    releaseAirpodsFromMac: () => released.promise,
    connectAirpodsToMac: async () => { macConnections++; return { ok: true }; },
  });
  const first = f.daemon.switchAirpodsToPhone();
  const second = await f.daemon.switchAirpodsToMac();
  expect(second.stage).toBe("busy");
  expect(second.ok).toBe(false);
  expect(macConnections).toBe(0);
  released.resolve({ ok: false, message: "timeout" });
  expect((await first).ok).toBe(false);
  expect((await f.daemon.switchAirpodsToMac()).ok).toBe(true);
  expect(macConnections).toBe(1);
});

test("automatic phone claims never acknowledge unavailable or failed Mac release", async () => {
  const f = fixture();
  for (const release of [null, async () => ({ ok: false, message: "timeout" }), async () => { throw new Error("closed"); }]) {
    f.s.getBluetoothBridge = () => release ? { releaseAirpodsFromMac: release } : null;
    const response: any = await new Promise(resolve => f.s.emit("phoneRequest", {
      method: "airpods.claimByPhone", params: { targetMac: "AA:BB:CC:DD:EE:FF", reason: "phone_audio_started" },
    }, resolve));
    expect(response.result.ok).toBe(false);
    expect(response.result.released).toBe(false);
  }
});

test("AirPods IPC budget covers phone handoff and native connection plus response margin", async () => {
  const { ipcTimeoutForAction } = await import("../src/daemon/request-timeouts.js");
  expect(ipcTimeoutForAction("airpods.switch_to_mac", {})).toBeGreaterThan(4000 + 23000);
  expect(ipcTimeoutForAction("airpods.switch_to_phone", {})).toBeGreaterThan(8000 + 16000);
});


test("late native AirPods diagnostics remain observable without completing another request", () => {
  const bridge = new BluetoothBridgeServer({ socketPath: "/unused", macId: "test", lanIps: [], lanPort: 0 });
  const seen: unknown[] = [];
  bridge.on("airpodsDiagnostics", (d) => seen.push(d));
  const diagnostics = { operationId: "expired", finished: true, events: [{ stage: "connection_callback", late: true }] };
  let resolved = false;
  (bridge as any).pendingCmdWaiters.set("new", { resolve: () => { resolved = true; } });
  (bridge as any).handleBridgeMessage({ type: "airpods_diagnostics", diagnostics });
  expect(seen).toEqual([diagnostics]);
  expect(resolved).toBe(false);
  expect((bridge as any).pendingCmdWaiters.has("new")).toBe(true);
  (bridge as any).handleBridgeMessage({ type: "airpods_diagnostics", diagnostics: [] });
  expect(seen).toHaveLength(1);
});
