import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";

export type TtsFallbackReason = "runtime_not_installed" | "dependency_missing" | "synthesis_failed" | "synthesis_timeout" | "invalid_audio" | "empty_text";
export type NeuralAudioResult =
  | { ok: true; audioBase64: string; cached: boolean; bytes: number }
  | { ok: false; reason: TtsFallbackReason };

export const neuralTtsScript = fileURLToPath(new URL("../../scripts/neural_tts.py", import.meta.url));
export function neuralTtsPython(homeDir: string): string {
  return process.env.OMS_TTS_PYTHON || path.join(homeDir, "runtime", "tts", "bin", "python3");
}

/** Text goes over stdin, never process arguments or diagnostic logs. */
export async function synthesizeNeuralAudio(options: {
  homeDir: string; text: string; voice: string; rate: string; pitch: string;
  pythonPath?: string; scriptPath?: string; timeoutMs?: number;
}): Promise<NeuralAudioResult> {
  if (!options.text) return { ok: false, reason: "empty_text" };
  const cacheDir = path.join(options.homeDir, "cache", "tts");
  const hash = crypto.createHash("sha256")
    .update(`${options.voice}|${options.rate}|${options.pitch}|${options.text}`).digest("hex").slice(0, 32);
  const output = path.join(cacheDir, `${hash}.mp3`);
  const readAudio = () => {
    const audio = fs.readFileSync(output);
    return audio.length > 200 ? audio : null;
  };
  try {
    if (fs.existsSync(output)) {
      const audio = readAudio();
      if (audio) return { ok: true, audioBase64: audio.toString("base64"), cached: true, bytes: audio.length };
    }
  } catch { /* Retry synthesis for an unreadable cache entry. */ }
  const python = options.pythonPath || neuralTtsPython(options.homeDir);
  const script = options.scriptPath || neuralTtsScript;
  if (!fs.existsSync(python) || !fs.existsSync(script)) return { ok: false, reason: "runtime_not_installed" };
  try { fs.mkdirSync(cacheDir, { recursive: true }); }
  catch { return { ok: false, reason: "synthesis_failed" }; }

  return await new Promise((resolve) => {
    const child = execFile(python, [script], { timeout: options.timeoutMs ?? 7500, maxBuffer: 8192 }, (err) => {
      if (err) {
        // The packaged wrapper uses 3 only for a missing dependency; never surface provider stderr.
        resolve({ ok: false, reason: err.killed ? "synthesis_timeout" : err.code === 3 ? "dependency_missing" : "synthesis_failed" });
        return;
      }
      try {
        const audio = readAudio();
        resolve(audio ? { ok: true, audioBase64: audio.toString("base64"), cached: false, bytes: audio.length }
          : { ok: false, reason: "invalid_audio" });
      } catch { resolve({ ok: false, reason: "invalid_audio" }); }
    });
    child.stdin?.on("error", () => {}); // Early process failure is reported by execFile's callback.
    child.stdin?.end(JSON.stringify({ text: options.text, voice: options.voice, rate: options.rate, pitch: options.pitch, output }));
  });
}
