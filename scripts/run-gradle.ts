#!/usr/bin/env bun
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync, spawnSync } from "node:child_process";

/**
 * Resolves a JDK >= 17 installation on macOS / Linux / CI so Gradle 8.x builds
 * work out-of-the-box even when system default `java` is Java 8.
 */
function parseJavaMajor(versionOutput: string): number {
  const m = versionOutput.match(/version\s+"(\d+)(?:\.(\d+))?/i);
  if (!m) return 0;
  const first = Number(m[1]);
  return first === 1 ? Number(m[2] || 0) : first;
}

function probeJavaHome(candidate: string | undefined): string | null {
  if (!candidate) return null;
  const javaBin = path.join(candidate, "bin", "java");
  if (!fs.existsSync(javaBin)) return null;
  try {
    const res = spawnSync(javaBin, ["-version"], { encoding: "utf8" });
    const combined = `${res.stdout || ""}\n${res.stderr || ""}`;
    if (parseJavaMajor(combined) >= 17) {
      return candidate;
    }
  } catch {
    // ignore invalid candidate
  }
  return null;
}

function resolveJdk17Plus(): string | null {
  const fromEnv = probeJavaHome(process.env.JAVA_HOME);
  if (fromEnv) return fromEnv;

  if (process.platform === "darwin" && fs.existsSync("/usr/libexec/java_home")) {
    try {
      const detected = execFileSync("/usr/libexec/java_home", ["-v", "17+"], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      }).trim();
      const valid = probeJavaHome(detected);
      if (valid) return valid;
    } catch {
      // fallback to Android Studio JBR / Homebrew paths
    }
  }

  const candidates = [
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
    "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home",
    path.join(os.homedir(), "Applications/Android Studio.app/Contents/jbr/Contents/Home"),
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
    "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
  ];

  for (const c of candidates) {
    const valid = probeJavaHome(c);
    if (valid) return valid;
  }
  return null;
}

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const androidDir = path.join(repoRoot, "android-app");
const gradlew = path.join(androidDir, process.platform === "win32" ? "gradlew.bat" : "gradlew");
const args = process.argv.slice(2);
if (args.length === 0) {
  args.push("assembleDebug");
}

const javaHome = resolveJdk17Plus();
const env: NodeJS.ProcessEnv = { ...process.env };
if (javaHome) {
  env.JAVA_HOME = javaHome;
  env.PATH = `${path.join(javaHome, "bin")}${path.delimiter}${env.PATH || ""}`;
}

const result = spawnSync(gradlew, args, {
  cwd: androidDir,
  env,
  stdio: "inherit",
});

process.exit(result.status ?? 1);
