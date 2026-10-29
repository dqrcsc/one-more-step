import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { synthesizeNeuralAudio } from "../src/daemon/neural-tts.js";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";

function fixture(code?: string) {
  const homeDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-tts-test-"));
  const scriptPath = path.join(homeDir, "fake-provider.ts");
  if (code) fs.writeFileSync(scriptPath, code);
  return { homeDir, scriptPath, pythonPath: process.execPath,
    text: "PRIVATE_TEST_TEXT", voice: "zh-CN-XiaoyiNeural", rate: "+6%", pitch: "+0Hz" };
}

describe("packaged Neural TTS provider", () => {
  test("missing runtime returns a fixed fallback code without sending text", async () => {
    const f = fixture();
    expect(await synthesizeNeuralAudio(f)).toEqual({ ok: false, reason: "runtime_not_installed" });
  });
  test("provider receives text via stdin; completed audio is cached without another provider call", async () => {
    const f = fixture(`import fs from 'node:fs';
      if(process.argv.some(a=>a.includes('PRIVATE_TEST_TEXT'))) process.exit(9);
      const r=JSON.parse(await Bun.stdin.text());
      if(r.text!=='PRIVATE_TEST_TEXT'||r.voice!=='zh-CN-XiaoyiNeural') process.exit(10);
      fs.writeFileSync(r.output,Buffer.alloc(256,42));`);
    const first = await synthesizeNeuralAudio(f);
    expect(first.ok).toBe(true);
    if (!first.ok) throw Error(first.reason);
    expect(first.cached).toBe(false);
    expect(Buffer.from(first.audioBase64, "base64")).toEqual(Buffer.alloc(256, 42));
    // Even with the runtime unavailable, the same completed audio remains usable.
    const next = await synthesizeNeuralAudio({ ...f, pythonPath: path.join(f.homeDir, "missing-python") });
    expect(next).toEqual({ ...first, cached: true });
  });
  test("dependency errors and provider stderr do not leak text", async () => {
    const f = fixture(`console.error('PRIVATE_TEST_TEXT'); process.exit(3);`);
    expect(await synthesizeNeuralAudio(f)).toEqual({ ok: false, reason: "dependency_missing" });
  });
  test("timeout and invalid output return explicit local fallback", async () => {
    const hanging = fixture(`await new Promise(r=>setTimeout(r,10000));`);
    expect(await synthesizeNeuralAudio({ ...hanging, timeoutMs: 100 })).toEqual({ ok: false, reason: "synthesis_timeout" });
    const empty = fixture(`process.exit(0);`);
    expect(await synthesizeNeuralAudio(empty)).toEqual({ ok: false, reason: "invalid_audio" });
  });
  test("notification and phone-speech callers expose their own fallback reasons", async () => {
    const d: any = Object.create(OmsDaemon.prototype);
    d.config = {};
    d.session = { presence: { getSnapshot: () => ({ state: "AWAY" }) },
      callPhone: async () => ({ ok: true }), getActiveTier: () => "DISCONNECTED" };
    d.synthesizeNeuralTts = async (_: string, opts: any) => { opts.onFallback("dependency_missing"); return null; };
    const notification = await d.handleIpcAction("phone_notify", { title: "test", ttsSpeak: true, level: "silent" });
    const speech = await d.handleIpcAction("phone_device_control", { action: "tts_speak", text: "test" });
    expect(notification.ttsFallbackReason).toBe("dependency_missing");
    expect(speech.ttsFallbackReason).toBe("dependency_missing");
    expect(speech.ttsEngine).toBe("android_local");
  });
  test("old phones never receive blind hangup GUI fallback", async () => {
    const d: any = Object.create(OmsDaemon.prototype);
    const methods: string[] = [];
    d.session = { callPhone: async (method: string) => {
      methods.push(method); throw Error("Unsupported sys.control action: hangup_call");
    } };
    const result = await d.handleIpcAction("phone_device_control", { action: "hangup_call" });
    expect(methods).toEqual(["sys.control"]);
    expect(result.ok).toBe(false);
    expect(result.hungUp).toBe(false);
    expect(result.error).toContain("ERR_PHONE_UPDATE_REQUIRED");
  });
});
