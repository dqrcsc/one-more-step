#!/usr/bin/env bun
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Install dependencies only: never submit text or play audio during setup.
const homeDir = process.env.OMS_HOME || path.join(os.homedir(), ".one_more_step");
const runtime = path.join(homeDir, "runtime", "tts");
const python = path.join(runtime, "bin", "python3");
const requirements = fileURLToPath(new URL("../mac-host/scripts/requirements-tts.txt", import.meta.url));
try {
  execFileSync("python3", ["-m", "venv", runtime], { stdio: "inherit" });
  execFileSync(python, ["-m", "pip", "install", "--disable-pip-version-check", "-r", requirements], { stdio: "inherit" });
  execFileSync(python, ["-c", "import edge_tts"], { stdio: "inherit" });
  console.log(`Neural TTS ready: ${python}`);
} catch {
  console.error("Neural TTS setup failed; Android local speech remains available. Check Python/venv and package download access, then rerun bun run setup:tts.");
  process.exitCode = 1;
}
