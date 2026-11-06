import { expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { CallContinuation } from "../src/daemon/call-continuation.js";
import { BluetoothBridgeServer } from "../src/transport/bluetooth-bridge-client.js";

async function withSpeakerFixture(run: (f: any) => Promise<void>) {
  const previous = process.env.OMS_DISABLE_OSASCRIPT;
  delete process.env.OMS_DISABLE_OSASCRIPT;
  const daemon = Object.create(OmsDaemon.prototype) as any;
  const control = new CallContinuation(Date.now() + 3000, () => {});
  let output: any = { ok: true, builtInSpeaker: true, uid: "built-in-speaker" };
  let played = 0;
  Object.assign(daemon, {
    homeDir: fs.mkdtempSync(path.join(os.tmpdir(), "oms-speaker-route-")),
    session: { getBluetoothBridge: () => ({ queryAudioOutput: async () => output }) },
    playMacAudioFile: async () => { played++; },
  });
  try {
    await run({ daemon, control, plays: () => played, setOutput: (value: any) => { output = value; },
      play: (repeat = 1) => daemon.playNeuralAudioOnMacSpeaker("ZmFrZQ==", repeat, control) });
  } finally {
    control.dispose();
    if (previous === undefined) delete process.env.OMS_DISABLE_OSASCRIPT;
    else process.env.OMS_DISABLE_OSASCRIPT = previous;
  }
}

test("unknown, Bluetooth and HDMI outputs fail before invoking a player", async () => {
  for (const output of [undefined, { ok: false }, { ok: true, uid: "AirPods", builtInSpeaker: false },
    { ok: true, uid: "HDMI", builtInSpeaker: false }, { ok: true, uid: "speaker", builtInSpeaker: "true" }]) {
    await withSpeakerFixture(async f => {
      f.setOutput(output);
      await expect(f.play()).rejects.toThrow("ERR_MAC_SPEAKER_ROUTE");
      expect(f.plays()).toBe(0);
    });
  }
});

test("positively verified built-in speaker succeeds using only the fake player", async () => {
  await withSpeakerFixture(async f => {
    expect(await f.play()).toBe(true);
    expect(f.plays()).toBe(1);
  });
});

test("route change during playback aborts playback and cannot report spoken success", async () => {
  await withSpeakerFixture(async f => {
    let aborted = false;
    f.daemon.playMacAudioFile = async (_file: string, control: CallContinuation) => {
      control.signal.addEventListener("abort", () => { aborted = true; }, { once: true });
      f.setOutput({ ok: true, uid: "AirPods", builtInSpeaker: false });
      await control.pause(2000);
    };
    await expect(f.play()).rejects.toThrow("ERR_MAC_SPEAKER_ROUTE");
    expect(aborted).toBe(true);
  });
});

test("route is checked after playback and before repeating", async () => {
  await withSpeakerFixture(async f => {
    let played = 0;
    f.daemon.playMacAudioFile = async () => {
      played++;
      f.setOutput({ ok: true, uid: "different-speaker", builtInSpeaker: true });
    };
    await expect(f.play(2)).rejects.toThrow("ERR_MAC_SPEAKER_ROUTE");
    expect(played).toBe(1);
  });
});

test("audio-output query uses a fresh correlated native reply and fails closed on timeout", async () => {
  const bridge = new BluetoothBridgeServer({ socketPath: "/unused", macId: "test", lanIps: [], lanPort: 0, autoSpawnBinary: false }) as any;
  expect((await bridge.queryAudioOutput(10)).ok).toBe(false);
  let request: any;
  bridge.activeClient = {};
  bridge.sendToBridge = (message: any) => { request = message; };
  const query = bridge.queryAudioOutput(100);
  expect(request.type).toBe("audio_output_query");
  bridge.handleBridgeMessage({ type: "audio_output_result", reqId: request.reqId, ok: true, builtInSpeaker: true, uid: "speaker" });
  expect((await query).builtInSpeaker).toBe(true);
  expect((await bridge.queryAudioOutput(10)).ok).toBe(false);
  expect(bridge.pendingCmdWaiters.size).toBe(0);
});
