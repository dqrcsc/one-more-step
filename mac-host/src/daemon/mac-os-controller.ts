import crypto from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import type {
  MacControlActionType,
  MacControlExecuteParams,
  MacControlExecuteResult,
  MacControlStatusSnapshot,
  MacInstalledAppItem,
} from "@oms/shared-protocol";

const KEYCHAIN_SERVICE = "com.oms.mac-unlock";
const HELPER_BUNDLE_ID = "com.oms.mac-control";
const HELPER_APP_NAME = "青简控机助手.app";

const APP_ALIAS_MAP: Record<string, string[]> = {
  微信: ["WeChat"],
  wechat: ["WeChat"],
  飞书: ["Feishu", "Lark"],
  钉钉: ["DingTalk"],
  qq: ["QQ"],
  浏览器: ["Google Chrome", "Arc", "Safari"],
  chrome: ["Google Chrome"],
  谷歌浏览器: ["Google Chrome"],
  safari: ["Safari"],
  终端: ["iTerm", "Warp", "Ghostty", "Terminal"],
  terminal: ["Terminal", "iTerm"],
  访达: ["Finder"],
  finder: ["Finder"],
  设置: ["System Settings", "System Preferences"],
  系统设置: ["System Settings"],
  备忘录: ["Notes"],
  日历: ["Calendar"],
  音乐: ["Music", "NetEaseMusic", "QQMusic", "Spotify"],
  网易云音乐: ["NetEaseMusic"],
  xcode: ["Xcode"],
  vscode: ["Visual Studio Code"],
  cursor: ["Cursor"],
  codex: ["Codex", "ChatGPT"],
  antigravity: ["Antigravity", "Antigravity IDE"],
  agy: ["Antigravity", "Antigravity IDE"],
  workbuddy: ["WorkBuddy"],
  opencode: ["OpenCode"],
  obsidian: ["Obsidian"],
  嘉立创: ["EasyEDA Pro", "LCEDA-Pro"],
  拓竹: ["BambuStudio", "Bambu Studio"],
  bambu: ["BambuStudio", "Bambu Studio"],
};

export interface MacControlRuntimeContext {
  macId: string;
  macName: string;
  activeTier: string;
  /** Authenticated request connection, never supplied by the requesting client. */
  requestConnectionId?: string;
  stayAwakeEnabled: boolean;
  onSetStayAwake: (enabled: boolean) => boolean;
}

export class MacOsController {
  private readonly ctlBinaryPath: string;
  private readonly omsHomeDir: string;
  private readonly helperAppDir: string;
  private readonly helperSocketPath: string;
  private readonly sealedKeyPath: string;
  private readonly sealedPwdPath: string;
  private cachedApps: { atMs: number; apps: MacInstalledAppItem[] } | null = null;
  private lastKnownNativeStatus: {
    screenLocked: boolean;
    accessibilityTrusted: boolean;
    frontmostApp: string;
    atMs: number;
  } | null = null;
  private testMockLocked = false;
  private testMockPassword = "";
  private readonly unlockChallenges = new Map<string, { token: string; expiresAt: number }>();

  constructor(repoRoot: string, customOmsHomeDir?: string) {
    this.ctlBinaryPath = path.join(repoRoot, "mac-host", "bin", "oms-mac-ctl");
    this.omsHomeDir = customOmsHomeDir || path.join(os.homedir(), ".one_more_step");
    this.helperAppDir = path.join(os.homedir(), "Applications", HELPER_APP_NAME);
    this.helperSocketPath = path.join(this.omsHomeDir, "run", "mac-ctl.sock");
    this.sealedKeyPath = path.join(this.omsHomeDir, "chat", ".mac_unlock.key");
    this.sealedPwdPath = path.join(this.omsHomeDir, "chat", "mac_unlock.enc");
  }

  private get isTestIsolation(): boolean {
    return process.env.OMS_TEST_ISOLATION === "1";
  }

  // =========================================================================
  // Standalone LaunchServices App Bundle Provisioner (`~/Applications/青简控机助手.app`)
  // Decouples macOS TCC Accessibility authorization from `bun.exe` under `launchd`!
  // =========================================================================

  private ensureHelperAppBundleInstalled(): boolean {
    if (this.isTestIsolation || process.platform !== "darwin") return false;
    if (!fs.existsSync(this.ctlBinaryPath)) return false;

    try {
      const contentsDir = path.join(this.helperAppDir, "Contents");
      const macOsDir = path.join(contentsDir, "MacOS");
      const dstBin = path.join(macOsDir, "oms-mac-ctl");
      const infoPlistPath = path.join(contentsDir, "Info.plist");

      const srcStat = fs.statSync(this.ctlBinaryPath);
      const dstExists = fs.existsSync(dstBin) && fs.existsSync(infoPlistPath);
      const needsUpdate = !dstExists || Math.abs(fs.statSync(dstBin).size - srcStat.size) > 0 || fs.statSync(dstBin).mtimeMs < srcStat.mtimeMs - 1000;

      if (needsUpdate) {
        fs.mkdirSync(macOsDir, { recursive: true });
        fs.copyFileSync(this.ctlBinaryPath, dstBin);
        fs.chmodSync(dstBin, 0o755);

        const plist = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>oms-mac-ctl</string>
    <key>CFBundleIdentifier</key>
    <string>${HELPER_BUNDLE_ID}</string>
    <key>CFBundleName</key>
    <string>青简控机助手</string>
    <key>CFBundleDisplayName</key>
    <string>青简控机助手</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>3.6.2</string>
    <key>CFBundleVersion</key>
    <string>65</string>
    <key>LSUIElement</key>
    <true/>
</dict>
</plist>
`;
        fs.writeFileSync(infoPlistPath, plist, "utf8");
        spawnSync(
          "/usr/bin/codesign",
          ["--force", "--deep", "--sign", "-", "--identifier", HELPER_BUNDLE_ID, this.helperAppDir],
          { stdio: "ignore", timeout: 5000 }
        );
        // Restart existing helper daemon if binary was upgraded
        spawnSync("/usr/bin/pkill", ["-f", `${HELPER_APP_NAME}/Contents/MacOS/oms-mac-ctl`], {
          stdio: "ignore",
          timeout: 1500,
        });
      }
      return true;
    } catch {
      return false;
    }
  }

  private callSocketOnce(payload: Record<string, unknown>, timeoutMs = 3500): Promise<Record<string, any> | null> {
    return new Promise((resolve) => {
      if (!fs.existsSync(this.helperSocketPath)) {
        resolve(null);
        return;
      }
      let settled = false;
      const done = (val: Record<string, any> | null) => {
        if (settled) return;
        settled = true;
        resolve(val);
      };
      const sock = net.createConnection(this.helperSocketPath);
      const timer = setTimeout(() => {
        sock.destroy();
        done(null);
      }, timeoutMs);

      let buf = "";
      sock.setEncoding("utf8");
      sock.on("connect", () => {
        sock.write(`${JSON.stringify(payload)}\n`);
      });
      sock.on("data", (chunk: string) => {
        buf += chunk;
        const nl = buf.indexOf("\n");
        if (nl >= 0) {
          clearTimeout(timer);
          sock.end();
          try {
            done(JSON.parse(buf.slice(0, nl).trim()));
          } catch {
            done(null);
          }
        }
      });
      sock.on("error", () => {
        clearTimeout(timer);
        done(null);
      });
      sock.on("close", () => {
        clearTimeout(timer);
        if (!settled) done(null);
      });
    });
  }

  private async callHelperAppDaemon(
    payload: Record<string, unknown>,
    timeoutMs = 3500
  ): Promise<Record<string, any> | null> {
    if (this.isTestIsolation) return null;
    const firstTry = await this.callSocketOnce(payload, Math.min(1500, timeoutMs));
    if (firstTry) return firstTry;

    if (!this.ensureHelperAppBundleInstalled()) return null;
    fs.mkdirSync(path.dirname(this.helperSocketPath), { recursive: true, mode: 0o700 });

    // Launch via macOS LaunchServices (`/usr/bin/open -g -a`) so TCC attributes Accessibility to `青简控机助手.app`!
    spawnSync(
      "/usr/bin/open",
      ["-g", "-a", this.helperAppDir, "--args", "--daemon", this.helperSocketPath],
      { stdio: "ignore", timeout: 3000 }
    );

    for (let i = 0; i < 8; i++) {
      await new Promise((r) => setTimeout(r, 160));
      if (fs.existsSync(this.helperSocketPath)) {
        const res = await this.callSocketOnce(payload, timeoutMs);
        if (res) return res;
      }
    }
    return null;
  }

  // =========================================================================
  // Dual-Safe Unlock Password Vault (macOS Keychain + 0o600 AES-256-GCM Sleep-Safe Backup)
  // =========================================================================

  private getOrCreateLocalSealKey(): Buffer {
    const dir = path.dirname(this.sealedKeyPath);
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    if (fs.existsSync(this.sealedKeyPath)) {
      const buf = fs.readFileSync(this.sealedKeyPath);
      if (buf.length === 32) return buf;
    }
    const key = crypto.randomBytes(32);
    fs.writeFileSync(this.sealedKeyPath, key, { mode: 0o600 });
    return key;
  }

  private writeSealedFallbackPassword(cleanPassword: string): void {
    try {
      const key = this.getOrCreateLocalSealKey();
      const iv = crypto.randomBytes(12);
      const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
      const enc = Buffer.concat([cipher.update(cleanPassword, "utf8"), cipher.final()]);
      const tag = cipher.getAuthTag();
      const payload = JSON.stringify({
        v: 1,
        iv: iv.toString("base64"),
        tag: tag.toString("base64"),
        ct: enc.toString("base64"),
      });
      fs.writeFileSync(this.sealedPwdPath, payload, { encoding: "utf8", mode: 0o600 });
    } catch {}
  }

  private readSealedFallbackPassword(): string {
    try {
      if (!fs.existsSync(this.sealedPwdPath) || !fs.existsSync(this.sealedKeyPath)) return "";
      const key = fs.readFileSync(this.sealedKeyPath);
      if (key.length !== 32) return "";
      const parsed = JSON.parse(fs.readFileSync(this.sealedPwdPath, "utf8"));
      const iv = Buffer.from(String(parsed.iv || ""), "base64");
      const tag = Buffer.from(String(parsed.tag || ""), "base64");
      const ct = Buffer.from(String(parsed.ct || ""), "base64");
      const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
      decipher.setAuthTag(tag);
      return Buffer.concat([decipher.update(ct), decipher.final()]).toString("utf8");
    } catch {
      return "";
    }
  }

  public hasUnlockPasswordConfigured(): boolean {
    if (this.isTestIsolation) {
      return this.testMockPassword.length > 0;
    }
    if (fs.existsSync(this.sealedPwdPath)) return true;
    try {
      const user = os.userInfo().username;
      const res = spawnSync(
        "/usr/bin/security",
        ["find-generic-password", "-a", user, "-s", KEYCHAIN_SERVICE],
        { stdio: ["ignore", "ignore", "ignore"], timeout: 2500 }
      );
      return res.status === 0;
    } catch {
      return false;
    }
  }

  public setUnlockPassword(password: string): boolean {
    const clean = String(password || "").replace(/[\r\n]+/g, "");
    if (!clean) throw new Error("解锁密码不能为空");
    if (this.isTestIsolation) {
      this.testMockPassword = clean;
      return true;
    }
    this.writeSealedFallbackPassword(clean);
    const user = os.userInfo().username;
    const escapeSq = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;
    const batchCmd = `add-generic-password -U -a ${escapeSq(user)} -s ${escapeSq(KEYCHAIN_SERVICE)} -w ${escapeSq(clean)}\n`;
    const res = spawnSync("/usr/bin/security", ["-i"], {
      input: batchCmd,
      encoding: "utf8",
      timeout: 4000,
    });
    return res.status === 0 || fs.existsSync(this.sealedPwdPath);
  }

  public clearUnlockPassword(): boolean {
    if (this.isTestIsolation) {
      this.testMockPassword = "";
      return true;
    }
    try {
      if (fs.existsSync(this.sealedPwdPath)) fs.unlinkSync(this.sealedPwdPath);
    } catch {}
    try {
      const user = os.userInfo().username;
      const res = spawnSync(
        "/usr/bin/security",
        ["delete-generic-password", "-a", user, "-s", KEYCHAIN_SERVICE],
        { stdio: ["ignore", "ignore", "ignore"], timeout: 2500 }
      );
      return res.status === 0 || !fs.existsSync(this.sealedPwdPath);
    } catch {
      return false;
    }
  }

  private readUnlockPassword(): string {
    if (this.isTestIsolation) return this.testMockPassword;
    const sealed = this.readSealedFallbackPassword();
    if (sealed) return sealed;
    try {
      const user = os.userInfo().username;
      const res = spawnSync(
        "/usr/bin/security",
        ["find-generic-password", "-a", user, "-s", KEYCHAIN_SERVICE, "-w"],
        { encoding: "utf8", timeout: 3000 }
      );
      if (res.status === 0 && res.stdout) {
        const clean = String(res.stdout).replace(/[\r\n]+/g, "");
        if (clean) {
          this.writeSealedFallbackPassword(clean);
          return clean;
        }
      }
    } catch {}
    return "";
  }

  private async probeNativeStatusAsync(): Promise<{
    screenLocked: boolean;
    accessibilityTrusted: boolean;
    frontmostApp: string;
  }> {
    if (this.isTestIsolation) {
      return {
        screenLocked: this.testMockLocked,
        accessibilityTrusted: true,
        frontmostApp: "Finder",
      };
    }

    // 1. Query LaunchServices `青简控机助手.app` daemon first so Accessibility reflects the app bundle's TCC grant!
    const daemonRes = await this.callHelperAppDaemon({ cmd: "status" }, 2500);
    if (daemonRes && daemonRes.ok) {
      const snap = {
        screenLocked: Boolean(daemonRes.screenLocked),
        accessibilityTrusted: Boolean(daemonRes.accessibilityTrusted),
        frontmostApp: String(daemonRes.frontmostApp || ""),
      };
      this.lastKnownNativeStatus = { ...snap, atMs: Date.now() };
      return snap;
    }

    // 2. Fallback to direct CLI probe
    return this.probeNativeStatusSync();
  }

  private probeNativeStatusSync(): {
    screenLocked: boolean;
    accessibilityTrusted: boolean;
    frontmostApp: string;
  } {
    if (this.isTestIsolation) {
      return {
        screenLocked: this.testMockLocked,
        accessibilityTrusted: true,
        frontmostApp: "Finder",
      };
    }
    if (fs.existsSync(this.ctlBinaryPath)) {
      try {
        const res = spawnSync(this.ctlBinaryPath, ["status"], {
          encoding: "utf8",
          timeout: 2000,
        });
        if (res.status === 0 && res.stdout) {
          const parsed = JSON.parse(res.stdout.trim());
          return {
            screenLocked: Boolean(parsed.screenLocked),
            accessibilityTrusted: Boolean(parsed.accessibilityTrusted) || Boolean(this.lastKnownNativeStatus?.accessibilityTrusted),
            frontmostApp: String(parsed.frontmostApp || ""),
          };
        }
      } catch {}
    }
    return { screenLocked: false, accessibilityTrusted: false, frontmostApp: "" };
  }

  private probeAudioVolume(): { volumePct: number; muted: boolean } {
    if (this.isTestIsolation) return { volumePct: 50, muted: false };
    try {
      const res = spawnSync(
        "/usr/bin/osascript",
        ["-e", "set v to get volume settings\nreturn (output volume of v as string) & \",\" & (output muted of v as string)"],
        { encoding: "utf8", timeout: 1800 }
      );
      if (res.status === 0 && res.stdout) {
        const [volRaw, muteRaw] = res.stdout.trim().split(",");
        const vol = Math.max(0, Math.min(100, Number.parseInt(volRaw || "50", 10) || 0));
        return { volumePct: vol, muted: muteRaw?.trim() === "true" };
      }
    } catch {}
    return { volumePct: 50, muted: false };
  }

  public listInstalledApps(forceRefresh = false): MacInstalledAppItem[] {
    if (this.isTestIsolation) {
      return [
        { name: "Codex", bundlePath: "/Applications/Codex.app", running: true },
        { name: "Antigravity", bundlePath: "/Applications/Antigravity.app", running: true },
        { name: "WorkBuddy", bundlePath: "/Applications/WorkBuddy.app", running: false },
        { name: "OpenCode", bundlePath: "/Applications/OpenCode.app", running: false },
        { name: "WeChat", bundlePath: "/Applications/WeChat.app", running: true },
      ];
    }
    const now = Date.now();
    if (!forceRefresh && this.cachedApps && now - this.cachedApps.atMs < 45_000) {
      return this.cachedApps.apps;
    }

    const runningNames = new Set<string>();
    try {
      const ps = spawnSync("/bin/ps", ["-axo", "comm="], { encoding: "utf8", timeout: 1500 });
      if (ps.status === 0 && ps.stdout) {
        for (const line of ps.stdout.split(/\r?\n/)) {
          const m = line.match(/\/([^/]+)\.app\/Contents\/MacOS\//);
          if (m?.[1]) runningNames.add(m[1]);
        }
      }
    } catch {}

    const found = new Map<string, MacInstalledAppItem>();
    const appDirs = ["/Applications", path.join(os.homedir(), "Applications"), "/System/Applications"];
    for (const dir of appDirs) {
      if (!fs.existsSync(dir)) continue;
      try {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
          if (!entry.name.endsWith(".app") || entry.name === HELPER_APP_NAME) continue;
          const name = entry.name.slice(0, -4);
          if (found.has(name)) continue;
          found.set(name, {
            name,
            bundlePath: path.join(dir, entry.name),
            running: runningNames.has(name),
          });
        }
      } catch {}
    }

    const priorityNames = new Set([
      "WeChat",
      "Google Chrome",
      "Safari",
      "Arc",
      "Antigravity",
      "Codex",
      "ChatGPT",
      "WorkBuddy",
      "OpenCode",
      "Cursor",
      "Visual Studio Code",
      "Xcode",
      "iTerm",
      "Terminal",
      "Warp",
      "Feishu",
      "Lark",
      "DingTalk",
      "Obsidian",
      "BambuStudio",
      "Finder",
      "Music",
      "Notes",
      "Calendar",
    ]);

    const sorted = Array.from(found.values()).sort((a, b) => {
      const aPri = priorityNames.has(a.name) ? 1 : 0;
      const bPri = priorityNames.has(b.name) ? 1 : 0;
      if (a.running !== b.running) return a.running ? -1 : 1;
      if (aPri !== bPri) return bPri - aPri;
      return a.name.localeCompare(b.name);
    });

    const capped = sorted.slice(0, 60);
    this.cachedApps = { atMs: now, apps: capped };
    return capped;
  }

  public resolveMacAppTarget(rawQuery: string): { name: string; bundlePath?: string } | null {
    const clean = String(rawQuery || "").trim().replace(/\.app$/i, "");
    if (!clean) return null;
    const lower = clean.toLowerCase();
    const apps = this.listInstalledApps(false);

    const aliasCandidates = APP_ALIAS_MAP[clean] || APP_ALIAS_MAP[lower];
    if (aliasCandidates) {
      for (const cand of aliasCandidates) {
        const hit = apps.find((a) => a.name.toLowerCase() === cand.toLowerCase());
        if (hit) return hit;
      }
      return { name: aliasCandidates[0] };
    }

    const exact = apps.find((a) => a.name.toLowerCase() === lower);
    if (exact) return exact;
    const partial = apps.find(
      (a) => a.name.toLowerCase().includes(lower) || lower.includes(a.name.toLowerCase())
    );
    if (partial) return partial;

    return { name: clean };
  }

  public async getStatusSnapshotAsync(ctx: MacControlRuntimeContext, forceRefreshApps = false): Promise<MacControlStatusSnapshot> {
    const native = await this.probeNativeStatusAsync();
    const audio = this.probeAudioVolume();
    return {
      macId: ctx.macId,
      macName: ctx.macName,
      screenLocked: native.screenLocked,
      displayAsleep: false,
      stayAwakeEnabled: ctx.stayAwakeEnabled,
      unlockPasswordConfigured: this.hasUnlockPasswordConfigured(),
      accessibilityTrusted: native.accessibilityTrusted,
      volumePct: audio.volumePct,
      muted: audio.muted,
      frontmostApp: native.frontmostApp || undefined,
      installedApps: this.listInstalledApps(forceRefreshApps),
      updatedAtMs: Date.now(),
    };
  }

  public getStatusSnapshot(ctx: MacControlRuntimeContext, forceRefreshApps = false): MacControlStatusSnapshot {
    const native = this.probeNativeStatusSync();
    const audio = this.probeAudioVolume();
    return {
      macId: ctx.macId,
      macName: ctx.macName,
      screenLocked: native.screenLocked,
      displayAsleep: false,
      stayAwakeEnabled: ctx.stayAwakeEnabled,
      unlockPasswordConfigured: this.hasUnlockPasswordConfigured(),
      accessibilityTrusted: native.accessibilityTrusted,
      volumePct: audio.volumePct,
      muted: audio.muted,
      frontmostApp: native.frontmostApp || undefined,
      installedApps: this.listInstalledApps(forceRefreshApps),
      updatedAtMs: Date.now(),
    };
  }

  private wakeDisplayNow(): void {
    if (this.isTestIsolation) return;
    try {
      const child = spawn("/usr/bin/caffeinate", ["-u", "-t", "5"], {
        detached: true,
        stdio: "ignore",
      });
      child.unref();
    } catch {}
  }

  public async execute(
    params: MacControlExecuteParams,
    ctx: MacControlRuntimeContext
  ): Promise<MacControlExecuteResult> {
    const action = params.action as MacControlActionType;
    const effectiveTier = ctx.activeTier;

    switch (action) {
      case "wake": {
        this.wakeDisplayNow();
        await this.callHelperAppDaemon({ cmd: "wake" }, 2000).catch(() => null);
        return {
          ok: true,
          action,
          message: `已点亮 ${ctx.macName} 屏幕`,
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "request_accessibility": {
        this.wakeDisplayNow();
        const res = await this.callHelperAppDaemon({ cmd: "request-accessibility" }, 3500);
        const trusted = Boolean(res?.accessibilityTrusted);
        return {
          ok: true,
          action,
          message: trusted
            ? "「青简控机助手」已拥有辅助功能权限，可直接远程解锁"
            : "已在 Mac 弹出「青简控机助手」辅助功能授权设置，请在电脑上勾选开启一次即可永久生效",
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "lock": {
        if (this.isTestIsolation) {
          this.testMockLocked = true;
          return {
            ok: true,
            action,
            message: `已锁定 ${ctx.macName} 屏幕`,
            status: this.getStatusSnapshot(ctx),
          };
        }
        let lockedOk = false;
        const daemonLock = await this.callHelperAppDaemon({ cmd: "lock" }, 2500);
        if (daemonLock?.ok) {
          lockedOk = true;
        } else if (fs.existsSync(this.ctlBinaryPath)) {
          const res = spawnSync(this.ctlBinaryPath, ["lock"], { timeout: 2500 });
          lockedOk = res.status === 0;
        }
        if (!lockedOk) {
          const fallback = spawnSync("/usr/bin/pmset", ["displaysleepnow"], { timeout: 2500 });
          lockedOk = fallback.status === 0;
        }
        await new Promise((r) => setTimeout(r, 250));
        return {
          ok: lockedOk,
          action,
          message: lockedOk ? `已立即锁定 ${ctx.macName}` : "锁屏指令执行失败",
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "unlock": {
        // Policy B: LAN_WS / BT_RFCOMM / LOCAL_IPC unlocks immediately; WAN_WEBRTC requires phone biometric verification!
        // Fail closed for unknown origins. A client hint cannot downgrade WAN to LAN.
        const needsConfirmation = !["LAN_WS", "BT_RFCOMM", "LOCAL_IPC"].includes(effectiveTier);
        const scope = ctx.requestConnectionId || "local-ipc";
        for (const [key, value] of this.unlockChallenges) {
          if (value.expiresAt <= Date.now()) this.unlockChallenges.delete(key);
        }
        const challenge = this.unlockChallenges.get(scope);
        const confirmed = params.biometricVerified === true && challenge &&
          params.unlockChallenge === challenge.token && challenge.expiresAt > Date.now();
        if (needsConfirmation && !confirmed) {
          if (this.unlockChallenges.size >= 64) this.unlockChallenges.clear();
          const token = crypto.randomBytes(24).toString("hex");
          this.unlockChallenges.set(scope, { token, expiresAt: Date.now() + 60_000 });
          return {
            ok: false,
            action,
            requiresBiometric: true,
            unlockChallenge: token,
            message: "当前为跨网远程连接，请先通过手机指纹/面容确认后再解锁 Mac",
            status: await this.getStatusSnapshotAsync(ctx),
          };
        }
        // Consume before any asynchronous OS operation; duplicate requests cannot replay approval.
        this.unlockChallenges.delete(scope);

        const before = await this.probeNativeStatusAsync();
        if (!before.screenLocked) {
          this.wakeDisplayNow();
          return {
            ok: true,
            action,
            message: `${ctx.macName} 当前已处于解锁亮屏状态`,
            status: await this.getStatusSnapshotAsync(ctx),
          };
        }

        const pwd = this.readUnlockPassword();
        if (!pwd) {
          return {
            ok: false,
            action,
            message: "尚未配置 Mac 解锁密码：请在手机「Mac 遥控」面板点击右上角「配解锁密码」",
            status: await this.getStatusSnapshotAsync(ctx),
          };
        }

        if (this.isTestIsolation) {
          this.testMockLocked = false;
          return {
            ok: true,
            action,
            message: `已远程唤醒并解锁 ${ctx.macName}`,
            status: this.getStatusSnapshot(ctx),
          };
        }

        this.wakeDisplayNow();

        // Route 1: Standalone LaunchServices `青简控机助手.app` daemon (independent TCC identity + hardware CGKeyCode)
        const daemonUnlock = await this.callHelperAppDaemon(
          { cmd: "type-unlock", password: pwd },
          10_000
        );
        if (daemonUnlock?.ok) {
          return {
            ok: true,
            action,
            message: `已远程点亮并解锁 ${ctx.macName}`,
            status: await this.getStatusSnapshotAsync(ctx),
          };
        }

        // Route 2: Fallback direct CLI (in case parent process itself already has Accessibility permission)
        const directStatus = this.probeNativeStatusSync();
        if (directStatus.accessibilityTrusted && fs.existsSync(this.ctlBinaryPath)) {
          const typeRes = spawnSync(this.ctlBinaryPath, ["type-unlock"], {
            input: `${pwd}\n`,
            encoding: "utf8",
            timeout: 10_000,
          });
          if (typeRes.status === 0) {
            return {
              ok: true,
              action,
              message: `已远程点亮并解锁 ${ctx.macName}`,
              status: await this.getStatusSnapshotAsync(ctx),
            };
          }
        }

        const errCode = String(daemonUnlock?.error || "");
        if (errCode === "ERR_AX_NOT_TRUSTED" || !before.accessibilityTrusted) {
          await this.callHelperAppDaemon({ cmd: "request-accessibility" }, 3000).catch(() => null);
          return {
            ok: false,
            action,
            message: "首次远程输密码解锁需授权：已在 Mac 自动打开「隐私与安全性 -> 辅助功能」，请勾选开启「青简控机助手」后再点解锁即可",
            status: await this.getStatusSnapshotAsync(ctx),
          };
        }

        return {
          ok: false,
          action,
          message: "已唤醒屏幕并输入密码，但仍处于锁定态（请点击右上角「钥匙串已配」核对保存的电脑登录密码是否正确）",
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "open_app": {
        const rawTarget = String(params.target || "").trim();
        if (!rawTarget) {
          return { ok: false, action, message: "请指定要打开的 Mac 应用或网址" };
        }
        this.wakeDisplayNow();
        if (this.isTestIsolation) {
          const resolved = this.resolveMacAppTarget(rawTarget);
          return {
            ok: true,
            action,
            message: `已在 ${ctx.macName} 打开 ${resolved?.name || rawTarget}`,
            status: this.getStatusSnapshot(ctx),
          };
        }

        if (/^https?:\/\//i.test(rawTarget)) {
          const r = spawnSync("/usr/bin/open", [rawTarget], { timeout: 3000 });
          return {
            ok: r.status === 0,
            action,
            message: r.status === 0 ? `已在 ${ctx.macName} 默认浏览器打开链接` : "打开网页失败",
            status: await this.getStatusSnapshotAsync(ctx),
          };
        }

        const resolved = this.resolveMacAppTarget(rawTarget);
        const targetArg = resolved?.bundlePath || resolved?.name || rawTarget;
        const r = spawnSync("/usr/bin/open", ["-a", targetArg], { encoding: "utf8", timeout: 4000 });
        const ok = r.status === 0;
        return {
          ok,
          action,
          message: ok
            ? `已在 ${ctx.macName} 打开「${resolved?.name || rawTarget}」`
            : `未能打开「${rawTarget}」：${(r.stderr || "").trim().slice(0, 80) || "未找到该应用"}`,
          status: await this.getStatusSnapshotAsync(ctx, true),
        };
      }

      case "quit_app": {
        const rawTarget = String(params.target || "").trim();
        const resolved = this.resolveMacAppTarget(rawTarget);
        const appName = resolved?.name || rawTarget;
        if (!appName) return { ok: false, action, message: "请指定要退出的应用名称" };
        if (!this.isTestIsolation) {
          const safeName = appName.replace(/["\\]/g, "");
          spawnSync("/usr/bin/osascript", ["-e", `tell application "${safeName}" to quit`], {
            timeout: 3000,
          });
        }
        return {
          ok: true,
          action,
          message: `已请求退出 ${appName}`,
          status: await this.getStatusSnapshotAsync(ctx, true),
        };
      }

      case "set_volume": {
        const pct = Math.max(0, Math.min(100, Number(params.value) || 0));
        if (!this.isTestIsolation) {
          spawnSync(
            "/usr/bin/osascript",
            ["-e", `set volume output muted false\nset volume output volume ${pct}`],
            { timeout: 2000 }
          );
        }
        return {
          ok: true,
          action,
          message: `已将 ${ctx.macName} 音量调至 ${pct}%`,
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "toggle_mute": {
        const cur = this.probeAudioVolume();
        const nextMute = typeof params.value === "boolean" ? params.value : !cur.muted;
        if (!this.isTestIsolation) {
          spawnSync("/usr/bin/osascript", ["-e", `set volume output muted ${nextMute ? "true" : "false"}`], {
            timeout: 2000,
          });
        }
        return {
          ok: true,
          action,
          message: nextMute ? `${ctx.macName} 已静音` : `${ctx.macName} 已取消静音`,
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "media_play_pause": {
        if (!this.isTestIsolation) {
          spawnSync(
            "/usr/bin/osascript",
            [
              "-e",
              `if application "Music" is running then tell application "Music" to playpause\nif application "Spotify" is running then tell application "Spotify" to playpause`,
            ],
            { timeout: 2000 }
          );
        }
        return {
          ok: true,
          action,
          message: `已切换 ${ctx.macName} 媒体播放/暂停`,
          status: await this.getStatusSnapshotAsync(ctx),
        };
      }

      case "stay_awake": {
        const targetEnable = typeof params.value === "boolean" ? params.value : !ctx.stayAwakeEnabled;
        const next = ctx.onSetStayAwake(targetEnable);
        return {
          ok: true,
          action,
          message: next ? `已开启 ${ctx.macName} 离家防休眠驻守` : `已关闭 ${ctx.macName} 防休眠驻守`,
          status: await this.getStatusSnapshotAsync({ ...ctx, stayAwakeEnabled: next }),
        };
      }

      default:
        return {
          ok: false,
          action,
          message: `不支持的 Mac 控制指令: ${String(action)}`,
        };
    }
  }
}
