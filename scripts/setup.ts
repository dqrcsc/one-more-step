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

// 1. Compile macOS CoreBluetooth bridge & Native Mac HUD if on macOS
if (process.platform === "darwin") {
  const btSrc = path.join(repoRoot, "mac-host/bluetooth-bridge/main.swift");
  const hudSrc = path.join(repoRoot, "mac-host/mac-hud/main.swift");
  const ctlSrc = path.join(repoRoot, "mac-host/mac-ctl/main.swift");
  const btBinDir = path.join(repoRoot, "mac-host/bin");
  const btBin = path.join(btBinDir, "oms-bt-bridge");
  const hudBin = path.join(btBinDir, "oms-mac-hud");
  const ctlBin = path.join(btBinDir, "oms-mac-ctl");
  try {
    fs.mkdirSync(btBinDir, { recursive: true });
    execFileSync("swiftc", [path.join(path.dirname(btSrc), "AirPodsOperation.swift"), path.join(path.dirname(btSrc), "AirPodsNativeRequest.swift"), btSrc, "-o", btBin], { stdio: "inherit" });
    console.log(`✅ Compiled macOS CoreBluetooth bridge: ${btBin}`);
  } catch (err: any) {
    console.warn(`⚠️ Skipping Bluetooth binary compilation (${err?.message || err})`);
  }
  try {
    execFileSync("swiftc", [hudSrc, "-framework", "Cocoa", "-framework", "PDFKit", "-framework", "Quartz", "-framework", "AVFoundation", "-framework", "WebKit", "-o", hudBin], { stdio: "inherit" });
    console.log(`✅ Compiled macOS Native HUD helper: ${hudBin}`);
  } catch (err: any) {
    console.warn(`⚠️ Skipping Mac HUD binary compilation (${err?.message || err})`);
  }
  try {
    execFileSync(
      "swiftc",
      ["-O", "-framework", "AppKit", "-framework", "ApplicationServices", "-framework", "CoreGraphics", "-framework", "IOKit", ctlSrc, "-o", ctlBin],
      { stdio: "inherit" }
    );
    console.log(`✅ Compiled macOS Remote Control helper: ${ctlBin}`);
  } catch (err: any) {
    console.warn(`⚠️ Skipping Mac Control binary compilation (${err?.message || err})`);
  }
}

// 2. Link global CLI `oms` (and `wx-ctx` compatibility wrapper) to ~/.local/bin/
const cliSrc = path.join(repoRoot, "mac-host/src/cli/index.ts");
const localBinDir = path.join(os.homedir(), ".local/bin");
const omsBinLink = path.join(localBinDir, "oms");
const wxCtxBinPath = path.join(localBinDir, "wx-ctx");
try {
  fs.chmodSync(cliSrc, 0o755);
  fs.mkdirSync(localBinDir, { recursive: true });
  if (fs.existsSync(omsBinLink) || fs.lstatSync(omsBinLink, { throwIfNoEntry: false })) {
    fs.unlinkSync(omsBinLink);
  }
  fs.symlinkSync(cliSrc, omsBinLink);
  console.log(`✅ Linked global CLI: ${omsBinLink} -> ${cliSrc}`);

  const wxWrapper = `#!/usr/bin/env bash\nexec "${omsBinLink}" wx "$@"\n`;
  fs.writeFileSync(wxCtxBinPath, wxWrapper, { encoding: "utf8", mode: 0o755 });
  fs.chmodSync(wxCtxBinPath, 0o755);
  console.log(`✅ Linked WeChat Context CLI alias: ${wxCtxBinPath} -> oms wx`);
} catch (err: any) {
  console.warn(`⚠️ Could not link ${omsBinLink}: ${err?.message || err}`);
}

// 3. Install unified global Agent Skill (+ references/) to ~/.codex/skills/one-more-step/
const skillTemplatePath = path.join(repoRoot, "skills/one-more-step/SKILL.md");
const skillRefsSrcDir = path.join(repoRoot, "skills/one-more-step/references");
const globalSkillDir = path.join(os.homedir(), ".codex/skills/one-more-step");
const globalSkillPath = path.join(globalSkillDir, "SKILL.md");
if (fs.existsSync(skillTemplatePath)) {
  try {
    const raw = fs.readFileSync(skillTemplatePath, "utf8");
    const rendered = raw.replaceAll("{{PROJECT_ROOT}}", repoRoot);
    fs.mkdirSync(globalSkillDir, { recursive: true });
    fs.writeFileSync(globalSkillPath, rendered, "utf8");
    if (fs.existsSync(skillRefsSrcDir)) {
      const globalRefsDir = path.join(globalSkillDir, "references");
      fs.mkdirSync(globalRefsDir, { recursive: true });
      for (const entry of fs.readdirSync(skillRefsSrcDir)) {
        const srcFile = path.join(skillRefsSrcDir, entry);
        if (fs.statSync(srcFile).isFile()) {
          const refRaw = fs.readFileSync(srcFile, "utf8");
          fs.writeFileSync(
            path.join(globalRefsDir, entry),
            refRaw.replaceAll("{{PROJECT_ROOT}}", repoRoot),
            "utf8"
          );
        }
      }
    }
    console.log(`✅ Installed unified global AI Agent Skill: ${globalSkillPath}`);
  } catch (err: any) {
    console.warn(`⚠️ Could not install global Skill: ${err?.message || err}`);
  }
}

console.log("\n🎉 Setup complete! Run `oms pair` or `bun run oms pair` to start.");
