#!/usr/bin/env bun
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");

console.log("🚀 Setting up OneMoreStep (青简·咫尺)...");

// Optional online voice provider. Failure must not prevent the local bridge from working.
try {
  execFileSync(process.execPath, [path.join(repoRoot, "scripts/setup-tts.ts")], { stdio: "inherit" });
} catch {
  console.warn("⚠️ Neural TTS unavailable; using Android local speech. Retry: bun run setup:tts");
}

// 1. Compile macOS CoreBluetooth bridge if on macOS
if (process.platform === "darwin") {
  const btSrc = path.join(repoRoot, "mac-host/bluetooth-bridge/main.swift");
  const btBinDir = path.join(repoRoot, "mac-host/bin");
  const btBin = path.join(btBinDir, "oms-bt-bridge");
  try {
    fs.mkdirSync(btBinDir, { recursive: true });
    execFileSync("swiftc", [path.join(path.dirname(btSrc), "AirPodsOperation.swift"), path.join(path.dirname(btSrc), "AirPodsNativeRequest.swift"), btSrc, "-o", btBin], { stdio: "inherit" });
    console.log(`✅ Compiled macOS CoreBluetooth bridge: ${btBin}`);
  } catch (err: any) {
    console.warn(`⚠️ Skipping Bluetooth binary compilation (${err?.message || err})`);
  }
}

// 2. Link global CLI `oms` to ~/.local/bin/oms
const cliSrc = path.join(repoRoot, "mac-host/src/cli/index.ts");
const localBinDir = path.join(os.homedir(), ".local/bin");
const omsBinLink = path.join(localBinDir, "oms");
try {
  fs.chmodSync(cliSrc, 0o755);
  fs.mkdirSync(localBinDir, { recursive: true });
  if (fs.existsSync(omsBinLink) || fs.lstatSync(omsBinLink, { throwIfNoEntry: false })) {
    fs.unlinkSync(omsBinLink);
  }
  fs.symlinkSync(cliSrc, omsBinLink);
  console.log(`✅ Linked global CLI: ${omsBinLink} -> ${cliSrc}`);
} catch (err: any) {
  console.warn(`⚠️ Could not link ${omsBinLink}: ${err?.message || err}`);
}

// 3. Install global Agent Skill to ~/.codex/skills/one-more-step/SKILL.md
const skillTemplatePath = path.join(repoRoot, "skills/one-more-step/SKILL.md");
const globalSkillDir = path.join(os.homedir(), ".codex/skills/one-more-step");
const globalSkillPath = path.join(globalSkillDir, "SKILL.md");
if (fs.existsSync(skillTemplatePath)) {
  try {
    const raw = fs.readFileSync(skillTemplatePath, "utf8");
    const rendered = raw.replaceAll("{{PROJECT_ROOT}}", repoRoot);
    fs.mkdirSync(globalSkillDir, { recursive: true });
    fs.writeFileSync(globalSkillPath, rendered, "utf8");
    console.log(`✅ Installed global AI Agent Skill: ${globalSkillPath}`);
  } catch (err: any) {
    console.warn(`⚠️ Could not install global Skill: ${err?.message || err}`);
  }
}

console.log("\n🎉 Setup complete! Run `oms pair` or `bun run oms pair` to start.");
