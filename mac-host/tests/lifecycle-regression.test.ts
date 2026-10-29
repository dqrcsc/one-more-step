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
