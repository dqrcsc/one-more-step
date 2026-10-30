import { expect, test } from "bun:test";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { boundedControlDeadline } from "../src/daemon/request-timeouts.js";

// Exercise the actual IPC routing without constructing a server or device session.
function fixture(reply: (method: string, params: any) => Promise<any>) {
  const daemon = Object.create(OmsDaemon.prototype) as OmsDaemon;
  const calls: { method: string; params: any; timeout: number }[] = [];
  let bindings = 0;
  Object.assign(daemon, {
    config: {},
    mirrorMode: "native_stream",
    session: {
      bindPhoneSession: () => {
        bindings++;
        return async (method: string, params: any, timeout: number) => {
          calls.push({ method, params, timeout });
          return reply(method, params);
        };
      },
      callPhone: () => { throw new Error("Unbound dispatch must not be used"); },
      setPhoneStatus: () => {},
    },
  });
  return { daemon, calls, bindings: () => bindings };
}

test("control deadlines reject malformed/expired values and never extend shorter deadlines", () => {
  for (const value of [null, "1500", NaN, Infinity, 0, 999, 1000, 1000.5]) {
    expect(() => boundedControlDeadline(value, 25000, 1000)).toThrow("ERR_GUI_DEADLINE");
  }
  expect(boundedControlDeadline(undefined, 25000, 1000)).toBe(26000);
  expect(boundedControlDeadline(1500, 25000, 1000)).toBe(1500);
  expect(boundedControlDeadline(100000, 25000, 1000)).toBe(26000);
});

test("expired unlock, dial and mirror never bind or dispatch", async () => {
  const f = fixture(async () => ({ ok: true }));
  for (const action of ["unlock", "dial"]) {
    await expect(f.daemon.handleIpcAction("phone_device_control", {
      action, number: "123", deadlineMs: 1,
    })).rejects.toThrow("ERR_GUI_DEADLINE");
  }
  await expect(f.daemon.sendMirrorInput({ type: "text", text: "test", deadlineMs: 1 }))
    .rejects.toThrow("ERR_GUI_DEADLINE");
  expect(f.bindings()).toBe(0);
  expect(f.calls).toEqual([]);
});

test("unlock failure returns once without credential retry or fallback gestures", async () => {
  const f = fixture(async () => ({ ok: false, unlocked: false }));
  const result = await f.daemon.handleIpcAction("phone_device_control", { action: "unlock" });
  expect(result.ok).toBe(false);
  expect(result.unlocked).toBe(false);
  expect(f.calls.map(c => c.method)).toEqual(["sys.control"]);
  expect(f.calls[0].params.action).toBe("unlock_screen");
});

test("dial carries explicit credentials and a single bounded deadline to the Android sequence", async () => {
  const f = fixture(async () => ({ ok: true, dialOnly: true }));
  const deadlineMs = Date.now() + 2000;
  await f.daemon.handleIpcAction("phone_device_control", {
    action: "dial", number: "123", pattern: "12369", pin: "0000", bounds: "1,2,3,4", deadlineMs,
  });
  expect(f.calls).toHaveLength(1);
  expect(f.calls[0].params).toMatchObject({
    action: "call_phone", number: "123", dialOnly: true,
    pattern: "12369", pin: "0000", bounds: "1,2,3,4", deadlineMs,
  });
  expect(f.calls[0].timeout).toBeGreaterThan(0);
  expect(f.calls[0].timeout).toBeLessThanOrEqual(2000);
});

test("unsupported unlock and dial require an update instead of legacy UI actions", async () => {
  for (const action of ["unlock", "call"]) {
    const f = fixture(async () => { throw new Error("Unsupported sys.control action"); });
    const result = await f.daemon.handleIpcAction("phone_device_control", { action, number: "123" });
    expect(result.ok).toBe(false);
    expect(result.requiresApkUpdate).toBe(true);
    expect(f.calls.map(c => c.method)).toEqual(["sys.control"]);
  }
});

test("failed or revoked phone action does not start a second action", async () => {
  for (const message of ["ERR_UNLOCK_FAILED", "ERR_SESSION_REVOKED", "RPC timeout"]) {
    const f = fixture(async () => { throw new Error(message); });
    await expect(f.daemon.handleIpcAction("phone_device_control", { action: "call", number: "123" }))
      .rejects.toThrow(message);
    expect(f.calls).toHaveLength(1);
  }
});

test("legacy mirror text/nav refuse input without clipboard or system-action bypass", async () => {
  for (const type of ["text", "nav"]) {
    const f = fixture(async () => { throw new Error("Unknown RPC method"); });
    const result = await f.daemon.sendMirrorInput({ type, text: "test", key: "home" });
    expect(result.ok).toBe(false);
    expect(result.requiresApkUpdate).toBe(true);
    expect(f.calls.map(c => c.method)).toEqual(["mirror.input"]);
    (f.daemon as any).mirrorMode = "legacy_poll";
    expect((await f.daemon.sendMirrorInput({ type })).ok).toBe(false);
    expect(f.calls).toHaveLength(1);
  }
});

test("mirror unlock forwards the caller deadline without extending it", async () => {
  const f = fixture(async () => ({ ok: true }));
  const deadlineMs = Date.now() + 2000;
  await f.daemon.sendMirrorInput({ type: "unlock", deadlineMs });
  expect(f.calls[0].params.deadlineMs).toBe(deadlineMs);
  expect(f.calls).toHaveLength(1);
});

test("call with say forwards speech options, enables speaker by default, and uses extended deadline budget", async () => {
  const f = fixture(async () => ({ ok: true, callPlaced: true, callAnswered: true, spoken: true }));
  const result = await f.daemon.handleIpcAction("phone_device_control", {
    action: "call",
    number: "10086",
    say: "你好，我是智能语音助手，帮主人转达一句话。",
    repeat: 2,
    hangupAfter: true,
    waitAnswerSec: 30,
  });
  expect(result.ok).toBe(true);
  expect(f.calls).toHaveLength(1);
  expect(f.calls[0].params).toMatchObject({
    action: "call_phone",
    number: "10086",
    dialOnly: false,
    speaker: true,
    sayText: "你好，我是智能语音助手，帮主人转达一句话。",
    repeatCount: 2,
    hangupAfter: true,
    waitAnswerSec: 30,
    useAlarmStream: true,
  });
  expect(f.calls[0].timeout).toBeGreaterThan(25000);
});
