import { expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { CallContinuation, runCallAudioProcess } from "../src/daemon/call-continuation.js";

function fixture() {
  const daemon = Object.create(OmsDaemon.prototype) as any;
  let current = true;
  const effects: any[] = [];
  let onAnswered = async () => {};
  let onPlay = async (control: CallContinuation) => { control.check(); };
  const assertCurrent = () => { if (!current) throw new Error("ERR_SESSION_REVOKED"); };
  const call = Object.assign(async (method: string, params: any, timeout: number) => {
    assertCurrent();
    effects.push({ method, params, timeout });
    if (params.action === "call_phone") { await onAnswered(); return { ok: true, callAnswered: true }; }
    return { ok: true, hungUp: true };
  }, { assertCurrent });
  Object.assign(daemon, {
    config: {}, phoneEmergencyStopEpoch: 0,
    synthesizeNeuralTts: async () => ({ audioBase64: "fake", voiceId: "test", voiceLabel: "test", cached: false, bytes: 4 }),
    playNeuralAudioOnMacSpeaker: async (_audio: string, _repeat: number, control: CallContinuation) => {
      effects.push({ play: true }); await onPlay(control); return true;
    },
    session: {
      bindPhoneSession: () => call,
      callPhone: () => { throw new Error("Unbound call must never be used"); },
      setPhoneStatus: () => {},
    },
  });
  return {
    daemon, effects, revoke: () => { current = false; },
    onAnswered: (fn: () => Promise<void>) => { onAnswered = fn; },
    onPlay: (fn: (control: CallContinuation) => Promise<void>) => { onPlay = fn; },
    run: (overrides = {}) => daemon.handleIpcAction("phone_device_control", {
      action: "call", number: "2025550123", macSay: "test", hangupAfter: true,
      postAnswerDelayMs: 150, deadlineMs: Date.now() + 2000, ...overrides,
    }),
  };
}

test("Mac speech cannot start after the answered call's session is replaced", async () => {
  const f = fixture();
  f.onAnswered(async () => { f.revoke(); });
  await expect(f.run()).rejects.toThrow("ERR_SESSION_REVOKED");
  expect(f.effects.map(e => e.params?.action)).toEqual(["call_phone"]);
});

test("the original deadline covers synthesis and prevents expired dial dispatch", async () => {
  const f = fixture();
  f.daemon.synthesizeNeuralTts = async () => { await Bun.sleep(30); return { audioBase64: "fake" }; };
  await expect(f.run({ deadlineMs: Date.now() + 10 })).rejects.toThrow("ERR_GUI_DEADLINE");
  expect(f.effects).toEqual([]);
});

test("expiry during the post-answer delay cannot start speech or hangup", async () => {
  const f = fixture();
  await expect(f.run({ deadlineMs: Date.now() + 60 })).rejects.toThrow("ERR_GUI_DEADLINE");
  expect(f.effects).toHaveLength(1);
});

test("replacement and phone emergency stop cancel ongoing Mac speech without hangup", async () => {
  for (const trigger of ["replacement", "emergency"]) {
    const f = fixture();
    let aborted = false;
    f.onPlay(async control => {
      control.signal.addEventListener("abort", () => { aborted = true; }, { once: true });
      if (trigger === "replacement") f.revoke();
      else f.daemon.phoneEmergencyStopEpoch++;
      await control.pause(1000);
    });
    await expect(f.run()).rejects.toThrow(trigger === "replacement" ? "ERR_SESSION_REVOKED" : "ERR_USER_INTERRUPTED");
    expect(aborted).toBe(true);
    expect(f.effects.filter(e => e.params?.action === "hangup_call")).toHaveLength(0);
  }
});

test("the authenticated emergency-stop event invalidates the local continuation", async () => {
  const daemon = new OmsDaemon(fs.mkdtempSync(path.join(os.tmpdir(), "oms-call-stop-"))) as any;
  daemon.showMacDesktopNotification = () => {};
  const epoch = daemon.phoneEmergencyStopEpoch;
  let reply: any;
  daemon.session.emit("phoneRequest", { method: "gui.emergencyStop", params: {} }, (value: any) => { reply = value; });
  expect(daemon.phoneEmergencyStopEpoch).toBe(epoch + 1);
  expect(reply).toEqual({ result: { ack: true } });
});

test("successful post-answer speech hangs up through the same bound session and deadline", async () => {
  const f = fixture();
  const deadlineMs = Date.now() + 2000;
  const result = await f.run({ deadlineMs });
  expect(result.spoken).toBe(true);
  expect(result.hungUpAfter).toBe(true);
  expect(f.effects.map(e => e.params?.action || "play")).toEqual(["call_phone", "play", "hangup_call"]);
  expect(f.effects[0].params.deadlineMs).toBe(deadlineMs);
  expect(f.effects[2].params.deadlineMs).toBe(deadlineMs);
  expect(f.effects[2].timeout).toBeLessThan(2000);
});

test("revocation kills and reaps a stuck playback child before returning", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-audio-cancel-"));
  const pidFile = path.join(dir, "pid");
  let valid = true;
  const control = new CallContinuation(Date.now() + 3000, () => {
    if (!valid) throw new Error("ERR_SESSION_REVOKED");
  });
  // A silent JS child stands in for afplay. No audio device is used.
  const child = runCallAudioProcess(process.execPath, ["-e",
    "require('fs').writeFileSync(process.argv[1], String(process.pid)); process.on('SIGTERM',()=>{}); setInterval(()=>{},1000)", pidFile], control);
  const stopped = child.then(() => { throw new Error("Unexpected successful playback"); }, error => error);
  try {
    for (let i = 0; i < 100 && !fs.existsSync(pidFile); i++) await Bun.sleep(10);
    expect(fs.existsSync(pidFile)).toBe(true);
    const pid = Number(fs.readFileSync(pidFile, "utf8"));
    valid = false;
    expect(String(await stopped)).toContain("ERR_SESSION_REVOKED");
    expect(() => process.kill(pid, 0)).toThrow();
  } finally { control.dispose(); }
});

test("the original deadline kills and reaps a stuck playback child", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-audio-deadline-"));
  const pidFile = path.join(dir, "pid");
  const control = new CallContinuation(Date.now() + 400, () => {});
  const stopped = runCallAudioProcess(process.execPath, ["-e",
    "require('fs').writeFileSync(process.argv[1], String(process.pid)); process.on('SIGTERM',()=>{}); setInterval(()=>{},1000)", pidFile], control)
    .then(() => false, () => true);
  try {
    expect(await stopped).toBe(true);
    expect(fs.existsSync(pidFile)).toBe(true);
    const pid = Number(fs.readFileSync(pidFile, "utf8"));
    expect(() => process.kill(pid, 0)).toThrow();
  } finally { control.dispose(); }
});
