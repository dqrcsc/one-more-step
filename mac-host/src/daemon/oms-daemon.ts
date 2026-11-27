import { approvalTimeoutSec, boundedControlDeadline } from "./request-timeouts.js";
import { CallContinuation, runCallAudioProcess } from "./call-continuation.js";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import net from "node:net";
import crypto from "node:crypto";
import { execFile, execFileSync, spawn } from "node:child_process";
import { synthesizeNeuralAudio, neuralTtsPython, type TtsFallbackReason } from "./neural-tts.js";
import {
  AgentBackendType,
  AgentSessionContinueParams,
  AgentSessionStartParams,
  AgentSessionStreamEventParams,
  AirPodsStatusSnapshot,
  ApprovalRespondParams,
  buildPairingUri,
  ChatSyncBatchParams,
  CurateBrowserUrlParams,
  CurateBrowserUrlResult,
  DecodedScreenFrame,
  encodeScreenFrame,
  FRAME_HEADER_BYTES,
  secureRandomHex,
  Ed25519Identity,
  generateEd25519Identity,
  generateUuidV7,
  InboxSubmitItemParams,
  IntentTag,
  JsonRpcRequest,
  MacControlExecuteParams,
  MemoryPromoteVaultParams,
  MirrorStatusSnapshot,
  OMS_BLE_SERVICE_SHORT_UUID,
  OMS_DEFAULT_LAN_PORT,
  OmsErrorCode,
  PairedAirPodsDevice,
  ScreenFrameCodec,
  ScreenFrameFlags,
  SpeechAudioPolicyName,
  WanRelayClientConfig,
} from "@oms/shared-protocol";
import { PhoneSessionManager, TrustedPhoneDevice } from "../transport/session-manager.js";
import { InboxManager, StoredInboxItem } from "../inbox/inbox-manager.js";
import { handleDashboardHttpRequest } from "../inbox/inbox-web-dashboard.js";
import { DingTalkConfig, DingTalkPlugin } from "../plugins/dingtalk.js";
import { HudStatusPayload, MacHudServer } from "../hud/mac-hud-server.js";
import { ManagedWeChatEvent, WeChatBridgeSupervisor } from "../wechat/wechat-supervisor.js";
import { buildEdgeSyncPayloadFromHost } from "../wechat/edge-proxy-bootstrap.js";
import { MobileMemoryMirror } from "../memory/mobile-memory-mirror.js";
import { SecretaryCore } from "../memory/secretary-core.js";
import { importEpubToVault, listImportedBooks } from "../knowledge/epub-importer.js";
import { AgentSessionHub, type AgentSessionHubOptions } from "../agent/agent-session-hub.js";
import { MacOsController, type MacControlRuntimeContext } from "./mac-os-controller.js";

function normalizeAgentBackend(raw: unknown): AgentBackendType {
  if (raw === "agy" || raw === "workbuddy" || raw === "opencode") return raw;
  return "codex";
}

export interface OmsHostConfig {
  lanPort: number;
  wanRelayWssUrl?: string;
  wanRelay?: WanRelayClientConfig;
  wanOtaDownloadUrl?: string;
  stayAwakeEnabled?: boolean;
  enableBluetoothBridge: boolean;
  enableWeChatBridge?: boolean;
  wechatInternalPort?: number;
  autoSyncWeChatToPhone?: boolean;
  autoCopyTextToClipboard?: boolean;
  autoPreviewImageOnMac?: boolean;
  autoSyncOtpToMac?: boolean;
  autoSwitchAirpods?: boolean;
  pauseOtherOnAirpodsSwitch?: boolean;
  preferredAirpodsMac?: string;
  preferredTtsVoice?: string;
  preferredSimSlot?: string;
  contacts?: Record<string, { number: string; sim?: string; note?: string }>;
  unlockPattern?: string;
  unlockPin?: string;
  unlockBounds?: string;
  dingtalk: DingTalkConfig;
}

export const NEURAL_TTS_VOICE_CATALOG: Record<string, { id: string; label: string }> = {
  xiaoyi: { id: "zh-CN-XiaoyiNeural", label: "晓伊 · 轻快灵动甜美女声 (默认推荐)" },
  xiaoxiao: { id: "zh-CN-XiaoxiaoNeural", label: "晓晓 · 温暖知性自然女声" },
  yunxia: { id: "zh-CN-YunxiaNeural", label: "云夏 · 活泼元气动漫少年音" },
  yunxi: { id: "zh-CN-YunxiNeural", label: "云希 · 阳光清朗青年男声" },
  yunjian: { id: "zh-CN-YunjianNeural", label: "云健 · 磁性沉稳男声" },
  xiaobei: { id: "zh-CN-liaoning-XiaobeiNeural", label: "晓北 · 幽默亲切东北话女声" },
  xiaoni: { id: "zh-CN-shaanxi-XiaoniNeural", label: "晓妮 · 热情陕西中原女声" },
  hsiaochen: { id: "zh-TW-HsiaoChenNeural", label: "晓臻 · 温柔台湾腔女声" },
  hiugaai: { id: "zh-HK-HiuGaaiNeural", label: "晓佳 · 自然粤语女声" },
};

const SAFE_MAC_PREVIEW_EXTENSIONS = new Set([
  ".png",
  ".jpg",
  ".jpeg",
  ".webp",
  ".gif",
  ".heic",
  ".bmp",
  ".tiff",
  ".pdf",
  ".mp3",
  ".m4a",
  ".wav",
  ".aac",
  ".ogg",
  ".mp4",
  ".mov",
  ".txt",
  ".md",
  ".csv",
  ".json",
]);

/**
 * Module 06: Mac Host Singleton Daemon (`omsd`)
 * Manages ~/.one_more_step/, Multi-Agent Local Unix Socket IPC, Inbox, Presence Routing & Approvals
 */
export class OmsDaemon {
  public readonly homeDir: string;
  public readonly ipcSocketPath: string;
  public readonly btSocketPath: string;
  public readonly hudSocketPath: string;
  public readonly inbox: InboxManager;
  public readonly session: PhoneSessionManager;
  public readonly dingtalk: DingTalkPlugin;
  public readonly wechat: WeChatBridgeSupervisor;
  public readonly memoryMirror: MobileMemoryMirror;
  public readonly secretary: SecretaryCore;
  public readonly agentHub: AgentSessionHub;
  public readonly macControl: MacOsController;

  private readonly configPath: string;
  private readonly wechatPendingPhoneQueuePath: string;
  private wechatPhoneFlush: Promise<void> | null = null;
  private readonly identityPath: string;
  private readonly trustedDevicesPath: string;
  private readonly config: OmsHostConfig;
  private readonly identity: Ed25519Identity;
  private ipcServer: net.Server | null = null;
  private hudServer: MacHudServer | null = null;
  private lastStatusRefreshMs = 0;

  private readonly pendingApprovals = new Map<
    string,
    {
      resolve: (res: ApprovalRespondParams) => void;
      reject: (err: Error) => void;
      timer: Timer;
      expiresAtMs: number;
      options: string[];
    }
  >();

  private readonly settledApprovals = new Map<string, { response: ApprovalRespondParams; expiresAtMs: number }>();

  // Real-time screen mirroring state
  private mirrorRunning = false;
  private mirrorStartPending = false;
  private mirrorGeneration = 0;
  private mirrorLifecycleQueue: Promise<void> = Promise.resolve();
  private mirrorDisposed = false;
  private mirrorMode: "native_stream" | "legacy_poll" | "stopped" = "stopped";
  private mirrorMaxWidth = 640;
  private mirrorQuality = 72;
  private mirrorMaxFps = 8;
  private mirrorLegacyPollTimer: Timer | null = null;
  private mirrorLegacyPollingInFlight = false;
  private mirrorLegacyBoostUntilMs = 0;
  private mirrorIdleStopTimer: Timer | null = null;
  private mirrorFrameTimestamps: number[] = [];
  private mirrorSeq = 0;
  private mirrorWidth = 1080;
  private mirrorHeight = 2400;
  private mirrorFlags = 0;
  private mirrorLastFrameAtMs = 0;

  // AirPods Seamless Auto-Switch State
  private lastAirpodsSwitchEvent?: AirPodsStatusSnapshot["lastSwitchEvent"];
  private suppressPhoneAirpodsClaimUntilMs = 0;
  private airpodsHandoffInFlight = false;
  private phoneEmergencyStopEpoch = 0;
  private airpodsDiagnostics: { mac?: Record<string, any>; phone?: Record<string, any> } = {};
  private phoneAirpodsState: AirPodsStatusSnapshot["phone"] = {
    connected: false,
    mediaPlaying: false,
    inCall: false,
    pairedAirpods: [],
  };

  // Milestone 1 (v2.0): Edge Standby Cursor & Capsule Sync State
  private lastSyncedCapsuleRevision = "";
  private lastSyncedUpdatesBuf = "";
  private lastKnownPhoneWifiSsid = "";
  private edgeCursorSyncTimer: Timer | null = null;
  private stayAwakeProc: ReturnType<typeof spawn> | null = null;

  constructor(customHomeDir?: string) {
    this.homeDir = customHomeDir || path.join(os.homedir(), ".one_more_step");
    this.ensureSecureDir(this.homeDir);
    this.ensureSecureDir(path.join(this.homeDir, "keystore"));
    this.ensureSecureDir(path.join(this.homeDir, "run"));
    this.ensureSecureDir(path.join(this.homeDir, "logs"));
    this.ensureSecureDir(path.join(this.homeDir, "cache"));
    this.ensureSecureDir(path.join(this.homeDir, "cache", "tts"));
    this.ensureSecureDir(path.join(this.homeDir, "wechat"));

    this.configPath = path.join(this.homeDir, "config.json");
    this.wechatPendingPhoneQueuePath = path.join(this.homeDir, "wechat", "pending_phone_sync.json");
    this.identityPath = path.join(this.homeDir, "keystore", "host_identity.json");
    this.trustedDevicesPath = path.join(this.homeDir, "keystore", "trusted_devices.json");
    this.ipcSocketPath = path.join(this.homeDir, "run", "omsd.sock");
    this.btSocketPath = path.join(this.homeDir, "run", "bt.sock");
    this.hudSocketPath = path.join(this.homeDir, "run", "hud.sock");

    this.rotateLogFileIfNeeded(path.join(this.homeDir, "logs", "omsd.log"));
    this.rotateLogFileIfNeeded(path.join(this.homeDir, "logs", "connections.jsonl"));

    this.config = this.loadOrInitConfig();
    this.identity = this.loadOrInitIdentity();
    const trusted = this.loadTrustedDevices();

    this.inbox = new InboxManager(path.join(this.homeDir, "inbox"));
    this.dingtalk = new DingTalkPlugin(this.config.dingtalk);

    const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../..");
    this.wechat = new WeChatBridgeSupervisor({
      omsHomeDir: this.homeDir,
      wechatHomeDir: process.env.OMS_WECHAT_HOME || path.join(this.homeDir, "wechat"),
      serverEntryPath: path.join(repoRoot, "wechat-context", "src", "server.ts"),
      internalPort: this.config.wechatInternalPort || 19528,
      logFilePath: path.join(this.homeDir, "logs", "wechat.log"),
    });
    this.wechat.on("statusChanged", () => this.syncMacHudState());
    this.wechat.on("wechatEvent", (evt: ManagedWeChatEvent) => {
      void this.handleManagedWeChatEvent(evt);
    });

    this.memoryMirror = new MobileMemoryMirror({
      omsHomeDir: this.homeDir,
      wechatEnvPath: path.join(this.wechat.wechatHomeDir, ".env"),
    });
    this.secretary = new SecretaryCore(this.homeDir);
    this.macControl = new MacOsController(repoRoot);
    this.agentHub = new AgentSessionHub({
      omsHomeDir: this.homeDir,
      defaultWorkspacePath: repoRoot,
    });
    this.agentHub.on("session_event", (evt: AgentSessionStreamEventParams) => {
      if (this.session.getActiveTier() !== "DISCONNECTED") {
        void this.session
          .callPhone("agent.session.event", evt as unknown as Record<string, unknown>, 4000)
          .catch(() => {});
      }
    });

    this.session = new PhoneSessionManager({
      hostIdentity: this.identity,
      lanPort: this.config.lanPort,
      streamDownloadDir: path.join(this.homeDir, "inbox", "pulled_cache"),
      diagnosticsPath: path.join(this.homeDir, "logs", "connections.jsonl"),
      trustedDevices: trusted,
      customHttpHandler: (req, url, clientIp) =>
        handleDashboardHttpRequest(this, req, url, clientIp),
    });

    this.wireSessionEvents();
    if (this.config.wanOtaDownloadUrl) {
      this.session.setWanOtaDownloadUrl(this.config.wanOtaDownloadUrl);
    }
    this.session.on("sessionRevoked", () => {
      for (const p of this.pendingApprovals.values()) { clearTimeout(p.timer); p.reject(new Error("Pairing session revoked")); }
      this.pendingApprovals.clear();
      this.settledApprovals.clear();
    });
  }

  private ensureSecureDir(dirPath: string): void {
    fs.mkdirSync(dirPath, { recursive: true, mode: 0o700 });
    try {
      fs.chmodSync(dirPath, 0o700);
    } catch {
      // ignore on platforms/filesystems without POSIX chmod
    }
  }

  private writeSecureFileAtomic(filePath: string, content: string): void {
    const tempPath = `${filePath}.${generateUuidV7()}.tmp`;
    let descriptor: number | undefined;
    try {
      descriptor = fs.openSync(tempPath, "wx", 0o600);
      fs.writeFileSync(descriptor, content, "utf8");
      fs.fsyncSync(descriptor);
      fs.closeSync(descriptor);
      descriptor = undefined;
      fs.renameSync(tempPath, filePath);
      try {
        fs.chmodSync(filePath, 0o600);
      } catch {
        // ignore
      }
    } catch (err) {
      if (descriptor !== undefined) {
        try { fs.closeSync(descriptor); } catch {}
      }
      if (fs.existsSync(tempPath)) {
        try { fs.unlinkSync(tempPath); } catch {}
      }
      throw err;
    }
  }

  private saveConfig(): void {
    if (!this.configPath) return;
    this.writeSecureFileAtomic(this.configPath, JSON.stringify(this.config, null, 2));
  }

  private rotateLogFileIfNeeded(logPath: string, maxBytes = 2 * 1024 * 1024, keepBytes = 512 * 1024): void {
    try {
      if (!fs.existsSync(logPath)) return;
      try {
        fs.chmodSync(logPath, 0o600);
      } catch {}
      const stat = fs.statSync(logPath);
      if (stat.size <= maxBytes) return;
      const buf = fs.readFileSync(logPath);
      const slice = buf.subarray(Math.max(0, buf.byteLength - keepBytes));
      const firstNewline = slice.indexOf(0x0a);
      const aligned = firstNewline !== -1 && firstNewline + 1 < slice.byteLength ? slice.subarray(firstNewline + 1) : slice;
      fs.writeFileSync(logPath, aligned, { mode: 0o600 });
    } catch {
      // ignore log rotation error
    }
  }

  private loadOrInitConfig(): OmsHostConfig {
    const defaultCfg: OmsHostConfig = {
      lanPort: OMS_DEFAULT_LAN_PORT,
      enableBluetoothBridge: true,
      autoCopyTextToClipboard: true,
      autoPreviewImageOnMac: true,
      autoSyncOtpToMac: true,
      autoSwitchAirpods: true,
      pauseOtherOnAirpodsSwitch: true,
      dingtalk: { enabled: false },
    };
    if (fs.existsSync(this.configPath)) {
      try {
        fs.chmodSync(this.configPath, 0o600);
      } catch {}
      try {
        const parsed = JSON.parse(fs.readFileSync(this.configPath, "utf8")) as OmsHostConfig;
        return {
          ...defaultCfg,
          ...parsed,
        };
      } catch {
        // Back up corrupted config without overwriting the original file so user credentials/contacts are never lost
        try {
          const backupPath = `${this.configPath}.corrupt.${Date.now()}.bak`;
          fs.copyFileSync(this.configPath, backupPath);
          fs.chmodSync(backupPath, 0o600);
        } catch {}
        return defaultCfg;
      }
    }
    this.writeSecureFileAtomic(this.configPath, JSON.stringify(defaultCfg, null, 2));
    return defaultCfg;
  }

  public getHostPreferences(): {
    autoCopyTextToClipboard: boolean;
    autoPreviewImageOnMac: boolean;
    autoSyncOtpToMac: boolean;
    autoSyncWeChatToPhone: boolean;
    autoSwitchAirpods: boolean;
    pauseOtherOnAirpodsSwitch: boolean;
    preferredAirpodsMac?: string;
    preferredTtsVoice: string;
    hasUnlockPattern: boolean;
    hasUnlockPin: boolean;
    unlockBounds?: string;
  } {
    return {
      autoCopyTextToClipboard: this.config.autoCopyTextToClipboard !== false,
      autoPreviewImageOnMac: this.config.autoPreviewImageOnMac !== false,
      autoSyncOtpToMac: this.config.autoSyncOtpToMac !== false,
      autoSyncWeChatToPhone: this.config.autoSyncWeChatToPhone !== false,
      autoSwitchAirpods: this.config.autoSwitchAirpods !== false,
      pauseOtherOnAirpodsSwitch: this.config.pauseOtherOnAirpodsSwitch !== false,
      preferredAirpodsMac: this.config.preferredAirpodsMac,
      preferredTtsVoice: this.config.preferredTtsVoice || "xiaoyi",
      hasUnlockPattern: Boolean(this.config.unlockPattern),
      hasUnlockPin: Boolean(this.config.unlockPin),
      unlockBounds: this.config.unlockBounds,
    };
  }

  public updateHostPreferences(prefs: {
    autoCopyTextToClipboard?: boolean;
    autoPreviewImageOnMac?: boolean;
    autoSyncOtpToMac?: boolean;
    autoSyncWeChatToPhone?: boolean;
    autoSwitchAirpods?: boolean;
    pauseOtherOnAirpodsSwitch?: boolean;
    preferredAirpodsMac?: string;
    preferredTtsVoice?: string;
    unlockPattern?: string;
    unlockPin?: string;
    unlockBounds?: string;
  }) {
    if (typeof prefs.autoCopyTextToClipboard === "boolean") {
      this.config.autoCopyTextToClipboard = prefs.autoCopyTextToClipboard;
    }
    if (typeof prefs.autoPreviewImageOnMac === "boolean") {
      this.config.autoPreviewImageOnMac = prefs.autoPreviewImageOnMac;
    }
    if (typeof prefs.autoSyncOtpToMac === "boolean") {
      this.config.autoSyncOtpToMac = prefs.autoSyncOtpToMac;
    }
    if (typeof prefs.autoSyncWeChatToPhone === "boolean") {
      this.config.autoSyncWeChatToPhone = prefs.autoSyncWeChatToPhone;
    }
    if (typeof prefs.autoSwitchAirpods === "boolean") {
      this.config.autoSwitchAirpods = prefs.autoSwitchAirpods;
    }
    if (typeof prefs.pauseOtherOnAirpodsSwitch === "boolean") {
      this.config.pauseOtherOnAirpodsSwitch = prefs.pauseOtherOnAirpodsSwitch;
    }
    if (typeof prefs.preferredAirpodsMac === "string") {
      const cleaned = prefs.preferredAirpodsMac.trim().toUpperCase();
      this.config.preferredAirpodsMac = cleaned || undefined;
    }
    if (typeof prefs.preferredTtsVoice === "string") {
      const cleaned = prefs.preferredTtsVoice.trim();
      this.config.preferredTtsVoice = cleaned || undefined;
    }
    if (typeof prefs.unlockPattern === "string") {
      const cleaned = prefs.unlockPattern.trim();
      this.config.unlockPattern = cleaned || undefined;
    }
    if (typeof prefs.unlockPin === "string") {
      const cleaned = prefs.unlockPin.trim();
      this.config.unlockPin = cleaned || undefined;
    }
    if (typeof prefs.unlockBounds === "string") {
      const cleaned = prefs.unlockBounds.trim();
      this.config.unlockBounds = cleaned || undefined;
    }
    this.saveConfig();
    this.syncAirpodsConfigToBridgeAndPhone();
    return this.getHostPreferences();
  }

  public getStayAwakeStatus(): {
    enabled: boolean;
    running: boolean;
    pid: number | null;
    mode: string;
  } {
    const running = Boolean(this.stayAwakeProc && this.stayAwakeProc.exitCode === null && !this.stayAwakeProc.killed);
    return {
      enabled: Boolean(this.config.stayAwakeEnabled),
      running,
      pid: running ? this.stayAwakeProc?.pid ?? null : null,
      mode: "caffeinate -i -s (允许锁屏息屏，防止系统与网络休眠)",
    };
  }

  public setStayAwakeEnabled(enabled: boolean) {
    this.config.stayAwakeEnabled = enabled;
    this.saveConfig();
    this.applyStayAwakeState();
    return this.getStayAwakeStatus();
  }

  private buildMacControlContext(): MacControlRuntimeContext {
    return {
      macId: this.identity.deviceId,
      macName: os.hostname().replace(/\.local$/i, "") || "Mac",
      activeTier: this.session.getActiveTier(),
      stayAwakeEnabled: Boolean(this.config.stayAwakeEnabled),
      onSetStayAwake: (en: boolean) => this.setStayAwakeEnabled(en).enabled,
    };
  }

  private applyStayAwakeState(): void {
    if (!this.config.stayAwakeEnabled) {
      this.stopStayAwakeProcess();
      return;
    }
    if (this.stayAwakeProc && this.stayAwakeProc.exitCode === null && !this.stayAwakeProc.killed) {
      return;
    }
    if (process.platform !== "darwin" || process.env.OMS_DISABLE_OSASCRIPT === "1") {
      return;
    }
    try {
      const child = spawn("/usr/bin/caffeinate", ["-i", "-s", "-w", String(process.pid)], {
        stdio: "ignore",
      });
      child.on("error", () => {
        if (this.stayAwakeProc === child) this.stayAwakeProc = null;
      });
      child.on("exit", () => {
        if (this.stayAwakeProc === child) this.stayAwakeProc = null;
      });
      child.unref();
      this.stayAwakeProc = child;
    } catch {
      this.stayAwakeProc = null;
    }
  }

  private stopStayAwakeProcess(): void {
    if (this.stayAwakeProc) {
      const child = this.stayAwakeProc;
      this.stayAwakeProc = null;
      try {
        child.kill("SIGTERM");
      } catch {}
    }
  }

  public configureWanRelayHost(params: {
    enabled?: boolean;
    wssUrl?: string;
    authToken?: string;
    caCertPem?: string;
    caCertPath?: string;
  }) {
    const prev = this.config.wanRelay || { enabled: false, wssUrl: "", authToken: "" };
    let caCertPem = typeof params.caCertPem === "string" ? params.caCertPem : prev.caCertPem;
    if (typeof params.caCertPath === "string" && params.caCertPath.trim()) {
      caCertPem = fs.readFileSync(params.caCertPath.trim(), "utf8");
    }
    const next: WanRelayClientConfig = {
      enabled: typeof params.enabled === "boolean" ? params.enabled : prev.enabled !== false,
      wssUrl: typeof params.wssUrl === "string" ? params.wssUrl.trim() : prev.wssUrl,
      authToken: typeof params.authToken === "string" ? params.authToken.trim() : prev.authToken,
      ...(caCertPem ? { caCertPem } : {}),
    };
    this.config.wanRelay = next;
    this.saveConfig();
    this.session.configureWanRelay(next);
    void this.syncEdgeConfigAndCapsuleToPhone(false).catch(() => {});
    return this.session.getWanRelayStatus();
  }

  public async probeWanRelayHealth(): Promise<Record<string, unknown>> {
    const cfg = this.config.wanRelay;
    if (!cfg?.wssUrl) {
      return { ok: false, error: "WAN relay is not configured" };
    }
    try {
      const u = new URL(cfg.wssUrl);
      u.protocol = u.protocol === "wss:" ? "https:" : "http:";
      u.pathname = u.pathname.replace(/\/v1\/?$/, "/health");
      if (!u.pathname.endsWith("/health")) {
        u.pathname = u.pathname.replace(/\/+$/, "") + "/health";
      }
      u.search = "";
      const t0 = performance.now();
      const fetchOpts: Record<string, any> = {
        method: "GET",
        signal: AbortSignal.timeout(6000),
      };
      if (cfg.caCertPem && u.protocol === "https:") {
        fetchOpts.tls = { ca: cfg.caCertPem };
      }
      const res = await fetch(u.toString(), fetchOpts as any);
      const rttMs = Math.round(performance.now() - t0);
      const data = await res.json().catch(() => ({}));
      return {
        ok: res.ok,
        status: res.status,
        rttMs,
        healthUrl: u.toString(),
        relayHealth: data,
        sessionStatus: this.session.getWanRelayStatus(),
      };
    } catch (err: any) {
      return {
        ok: false,
        error: err?.message || String(err),
        sessionStatus: this.session.getWanRelayStatus(),
      };
    }
  }

  public resolveNeuralTtsVoice(rawVoice?: string): { alias: string; voiceId: string; label: string } {
    const key = String(rawVoice || this.config.preferredTtsVoice || "xiaoyi").trim().toLowerCase();
    const zhAliasMap: Record<string, string> = {
      晓伊: "xiaoyi",
      甜美: "xiaoyi",
      晓晓: "xiaoxiao",
      温柔: "xiaoxiao",
      云夏: "yunxia",
      皮卡丘: "yunxia",
      少年: "yunxia",
      云希: "yunxi",
      男声: "yunxi",
      云健: "yunjian",
      晓北: "xiaobei",
      东北: "xiaobei",
      东北话: "xiaobei",
      晓妮: "xiaoni",
      陕西: "xiaoni",
      晓臻: "hsiaochen",
      台湾: "hsiaochen",
      晓佳: "hiugaai",
      粤语: "hiugaai",
    };
    const mappedAlias = zhAliasMap[key] || key;
    if (NEURAL_TTS_VOICE_CATALOG[mappedAlias]) {
      return {
        alias: mappedAlias,
        voiceId: NEURAL_TTS_VOICE_CATALOG[mappedAlias].id,
        label: NEURAL_TTS_VOICE_CATALOG[mappedAlias].label,
      };
    }
    const byId = Object.entries(NEURAL_TTS_VOICE_CATALOG).find(
      ([, v]) => v.id.toLowerCase() === key
    );
    if (byId) {
      return { alias: byId[0], voiceId: byId[1].id, label: byId[1].label };
    }
    if (rawVoice && rawVoice.includes("Neural")) {
      return { alias: rawVoice, voiceId: rawVoice, label: rawVoice };
    }
    return {
      alias: "xiaoyi",
      voiceId: NEURAL_TTS_VOICE_CATALOG.xiaoyi.id,
      label: NEURAL_TTS_VOICE_CATALOG.xiaoyi.label,
    };
  }

  public async synthesizeNeuralTts(
    rawText: string,
    opts?: { voice?: string; rate?: string; pitch?: string; onFallback?: (reason: TtsFallbackReason) => void }
  ): Promise<{
    audioBase64: string;
    voiceId: string;
    voiceLabel: string;
    cached: boolean;
    bytes: number;
  } | null> {
    const cleanText = String(rawText || "")
      .replace(/```[\s\S]*?```/g, "，代码片段已略过，")
      .replace(/[#*`>|\[\]]/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 600);
    if (!cleanText) { opts?.onFallback?.("empty_text"); return null; }
    if (!this.homeDir) { opts?.onFallback?.("runtime_not_installed"); return null; }

    const resolved = this.resolveNeuralTtsVoice(opts?.voice);
    const rate = opts?.rate || "+6%";
    const pitch = opts?.pitch || "+0Hz";
    const audio = await synthesizeNeuralAudio({
      homeDir: this.homeDir, text: cleanText, voice: resolved.voiceId, rate, pitch,
    });
    if (!audio.ok) { opts?.onFallback?.(audio.reason); return null; }
    return { audioBase64: audio.audioBase64, voiceId: resolved.voiceId, voiceLabel: resolved.label,
      cached: audio.cached, bytes: audio.bytes };
  }

  public resolveContactTarget(
    rawTarget: string,
    explicitSim?: string
  ): { cleanNumber: string; simSlot: string; matchedContact?: string } {
    const trimmed = String(rawTarget || "").trim();
    const contacts = this.config?.contacts || {};
    const exactEntry = contacts[trimmed];
    const matchedEntry =
      exactEntry ||
      Object.entries(contacts).find(([k]) => k.toLowerCase() === trimmed.toLowerCase())?.[1];
    const matchedName = exactEntry
      ? trimmed
      : Object.keys(contacts).find((k) => k.toLowerCase() === trimmed.toLowerCase());

    if (matchedEntry?.number) {
      const cleanNumber = String(matchedEntry.number).replace(/[^\d+*#,]/g, "");
      const simSlot = String(explicitSim ?? matchedEntry.sim ?? this.config?.preferredSimSlot ?? "1");
      return { cleanNumber, simSlot, matchedContact: matchedName };
    }

    const cleanNumber = trimmed.replace(/[^\d+*#,]/g, "");
    const simSlot = String(explicitSim ?? this.config?.preferredSimSlot ?? "1");
    return { cleanNumber, simSlot };
  }

  protected playMacAudioFile(mp3Path: string, continuation: CallContinuation): Promise<void> {
    return runCallAudioProcess("afplay", [mp3Path], continuation);
  }

  protected playMacSystemSay(text: string, continuation: CallContinuation): Promise<void> {
    return runCallAudioProcess("say", [text], continuation);
  }

  public static isSafeForDirectMacPreview(absPath: string): boolean {
    if (!absPath) return false;
    const normalized = absPath.toLowerCase();
    if (normalized.endsWith(".app") || normalized.includes(".app/")) return false;
    const ext = path.extname(normalized);
    return Boolean(ext && SAFE_MAC_PREVIEW_EXTENSIONS.has(ext));
  }

  public static resolveSpeechAudioPolicy(
    params: Record<string, any> = {},
    defaultPolicy: SpeechAudioPolicyName = "alarm_respect_headset"
  ): SpeechAudioPolicyName {
    if (typeof params.audioPolicy === "string") {
      const normalized = params.audioPolicy.trim().toLowerCase();
      if (normalized === "force_speaker_alarm" || normalized === "force_speaker") {
        return "force_speaker_alarm";
      }
      if (normalized === "alarm_respect_headset" || normalized === "respect_headset") {
        return "alarm_respect_headset";
      }
      if (normalized === "media_commute" || normalized === "media") {
        return "media_commute";
      }
    }
    if (params.forceSpeakerAlarmStream === true || params.forceSpeaker === true) {
      return "force_speaker_alarm";
    }
    if (params.useAlarmStream === false) {
      return "media_commute";
    }
    if (params.forceSpeakerAlarmStream === false && defaultPolicy === "force_speaker_alarm") {
      return "alarm_respect_headset";
    }
    return defaultPolicy;
  }

  private async runMonitoredMacSpeakerLoop(
    repeatCount: number,
    continuation: CallContinuation,
    playOnce: () => Promise<void>
  ): Promise<boolean> {
    continuation.check();
    if (process.env.OMS_DISABLE_OSASCRIPT === "1") return false;
    let monitoring = false;
    let routeTimer: ReturnType<typeof setInterval> | undefined;
    try {
      const outputUid = await this.requireMacSpeakerOutput(continuation);
      monitoring = true;
      let checking = false;
      routeTimer = setInterval(async () => {
        if (!monitoring || checking) return;
        checking = true;
        try {
          await this.requireMacSpeakerOutput(continuation, outputUid);
        } catch (error) {
          if (monitoring) {
            continuation.cancel(error instanceof Error ? error : new Error(String(error)));
          }
        } finally {
          checking = false;
        }
      }, 250);
      const count = Math.max(1, Math.min(10, Math.round(repeatCount)));
      for (let i = 0; i < count; i++) {
        await this.requireMacSpeakerOutput(continuation, outputUid);
        await playOnce();
        await this.requireMacSpeakerOutput(continuation, outputUid);
        if (i < count - 1) {
          await continuation.pause(420);
        }
      }
      return true;
    } catch (error) {
      if (error instanceof Error && error.message.startsWith("ERR_MAC_SPEAKER_ROUTE")) {
        continuation.cancel(error);
      }
      continuation.check();
      return false;
    } finally {
      monitoring = false;
      if (routeTimer) clearInterval(routeTimer);
    }
  }

  private async requireMacSpeakerOutput(continuation: CallContinuation, expectedUid?: string): Promise<string> {
    continuation.check();
    const output = await this.session.getBluetoothBridge()?.queryAudioOutput(
      Math.max(1, Math.min(1000, continuation.deadlineMs - Date.now())));
    continuation.check();
    if (output?.ok !== true || output.builtInSpeaker !== true || typeof output.uid !== "string" || !output.uid
        || (expectedUid !== undefined && output.uid !== expectedUid)) {
      throw new Error("ERR_MAC_SPEAKER_ROUTE: 无法确认 Mac 内置扬声器输出，请在系统声音设置中选择内置扬声器后重试");
    }
    return output.uid;
  }

  public async playNeuralAudioOnMacSpeaker(audioBase64: string, repeatCount: number, continuation: CallContinuation): Promise<boolean> {
    continuation.check();
    if (!audioBase64 || process.env.OMS_DISABLE_OSASCRIPT === "1") return false;
    const cacheDir = path.join(this.homeDir || os.tmpdir(), "cache", "tts");
    fs.mkdirSync(cacheDir, { recursive: true });
    const mp3Path = path.join(cacheDir, "mac_speaker_active.mp3");
    fs.writeFileSync(mp3Path, Buffer.from(audioBase64, "base64"));
    return await this.runMonitoredMacSpeakerLoop(repeatCount, continuation, () =>
      this.playMacAudioFile(mp3Path, continuation)
    );
  }

  public async playFallbackSayOnMacSpeaker(text: string, repeatCount: number, continuation: CallContinuation): Promise<boolean> {
    const clean = text.trim();
    continuation.check();
    if (!clean || process.env.OMS_DISABLE_OSASCRIPT === "1") return false;
    return await this.runMonitoredMacSpeakerLoop(repeatCount, continuation, () =>
      this.playMacSystemSay(clean, continuation)
    );
  }

  public copyTextToMacClipboard(text: string): void {
    if (!text || process.env.OMS_DISABLE_OSASCRIPT === "1") return;
    try {
      const proc = spawn("pbcopy");
      proc.on("error", () => {});
      proc.stdin.on("error", () => {});
      proc.stdin.write(text, "utf8");
      proc.stdin.end();
    } catch {
      // ignore if pbcopy unavailable
    }
  }

  public openFileOnMac(absPath: string, revealInFinder = false): { args: string[]; downgradedToReveal: boolean } {
    const safeDirect = !revealInFinder && OmsDaemon.isSafeForDirectMacPreview(absPath);
    const effectiveReveal = revealInFinder || !safeDirect;
    const args = effectiveReveal ? ["-R", absPath] : [absPath];
    const downgradedToReveal = !revealInFinder && effectiveReveal;
    if (!absPath || process.env.OMS_DISABLE_OSASCRIPT === "1") {
      return { args, downgradedToReveal };
    }
    try {
      const child = execFile("open", args, () => {});
      child.on("error", () => {});
    } catch {
      // ignore
    }
    return { args, downgradedToReveal };
  }

  private loadOrInitIdentity(): Ed25519Identity {
    if (fs.existsSync(this.identityPath)) {
      try {
        fs.chmodSync(this.identityPath, 0o600);
      } catch {}
      try {
        return JSON.parse(fs.readFileSync(this.identityPath, "utf8")) as Ed25519Identity;
      } catch {
        // regenerate
      }
    }
    const id = generateEd25519Identity("mac");
    this.writeSecureFileAtomic(this.identityPath, JSON.stringify(id, null, 2));
    return id;
  }

  private loadTrustedDevices(): TrustedPhoneDevice[] {
    if (!fs.existsSync(this.trustedDevicesPath)) return [];
    try {
      fs.chmodSync(this.trustedDevicesPath, 0o600);
    } catch {}
    try {
      return JSON.parse(fs.readFileSync(this.trustedDevicesPath, "utf8")) as TrustedPhoneDevice[];
    } catch {
      return [];
    }
  }

  private saveTrustedDevices(devices: TrustedPhoneDevice[]): void {
    this.writeSecureFileAtomic(this.trustedDevicesPath, JSON.stringify(devices, null, 2));
  }

  private wireSessionEvents(): void {
    this.session.on("trustStore", (devices: TrustedPhoneDevice[]) => {
      this.saveTrustedDevices(devices);
    });

    this.session.on("phoneAuthenticated", (evt: any) => {
      if (evt?.status) {
        this.ingestPhoneAirpodsSnapshot(evt.status);
        if (typeof evt.status.wifiSsid === "string" && evt.status.wifiSsid.trim()) {
          this.lastKnownPhoneWifiSsid = evt.status.wifiSsid.trim().replace(/^"|"$/g, "");
        }
      }
      this.syncAirpodsConfigToBridgeAndPhone();
      this.syncMacHudState();
      void this.flushPendingWeChatPhoneQueue();
      void this.syncEdgeConfigAndCapsuleToPhone();
    });

    this.session.on("airpodsDiagnostics", (diagnostics) => {
      this.recordAirpodsDiagnostics("mac", diagnostics);
    });

    this.session.on("airpodsStatus", () => {
      this.syncAirpodsConfigToPhoneOnly();
    });

    this.session.on(
      "airpodsMacClaimTriggered",
      (evt: { targetMac?: string; targetName?: string; reason?: string }) => {
        if (this.config.autoSwitchAirpods === false) return;
        this.lastAirpodsSwitchEvent = {
          direction: "PHONE_TO_MAC",
          reason: evt.reason || "mac_audio_started",
          deviceName: evt.targetName || "AirPods",
          deviceMac: evt.targetMac,
          atMs: Date.now(),
        };
        if (this.session.getActiveTier() !== "DISCONNECTED") {
          void this.session
            .callPhone(
              "airpods.handoffToMac",
              {
                targetMac: evt.targetMac,
                targetName: evt.targetName,
                pausePhoneMedia: this.config.pauseOtherOnAirpodsSwitch !== false,
                disconnectOnPhone: true,
                reason: evt.reason || "mac_audio_started",
                deadlineMs: Date.now() + 3000,
              },
              4000
            )
            .catch(() => {});
        }
      }
    );

    this.session.on("screenFrame", (frame: DecodedScreenFrame) => {
      const now = Date.now();
      this.mirrorSeq = frame.seq;
      if (frame.width > 0) this.mirrorWidth = frame.width;
      if (frame.height > 0) this.mirrorHeight = frame.height;
      this.mirrorFlags = frame.flags;
      this.mirrorLastFrameAtMs = now;
      if (frame.codec !== ScreenFrameCodec.STATUS_ONLY && frame.data.byteLength > 0) {
        this.mirrorFrameTimestamps.push(now);
        while (
          this.mirrorFrameTimestamps.length > 0 &&
          now - this.mirrorFrameTimestamps[0] > 3000
        ) {
          this.mirrorFrameTimestamps.shift();
        }
      }
      this.session.broadcastMirrorEvent({
        type: "mirror_status",
        status: this.getMirrorStatus(),
      });
    });

    this.session.on(
      "mirrorViewerChanged",
      (count: number, newWs?: { send: (data: string | Uint8Array) => unknown }, autoStart = true) => {
        if (newWs) {
          try {
            newWs.send(JSON.stringify({ type: "mirror_status", status: this.getMirrorStatus() }));
          } catch {}
        }
        if (count > 0) {
          if (this.mirrorIdleStopTimer) {
            clearTimeout(this.mirrorIdleStopTimer);
            this.mirrorIdleStopTimer = null;
          }
          if (autoStart && !this.mirrorDisposed && !this.mirrorRunning && !this.mirrorStartPending && this.session.getActiveTier() !== "DISCONNECTED") {
            void this.startMirror({}).catch(() => {});
          }
        } else if (this.mirrorRunning || this.mirrorStartPending) {
          if (this.mirrorIdleStopTimer) clearTimeout(this.mirrorIdleStopTimer);
          this.mirrorIdleStopTimer = setTimeout(() => {
            this.mirrorIdleStopTimer = null;
            if (this.session.getMirrorViewerCount() === 0 && (this.mirrorRunning || this.mirrorStartPending)) {
              void this.stopMirror().catch(() => {});
            }
          }, 25000);
          (this.mirrorIdleStopTimer as any).unref?.();
        }
      }
    );

    this.session.on(
      "mirrorViewerCommand",
      async (
        cmd: Record<string, any>,
        ws: { send: (data: string | Uint8Array) => unknown }
      ) => {
        const cmdType = String(cmd.type || cmd.cmd || "");
        try {
          if (cmdType === "start") {
            const st = await this.startMirror(cmd);
            ws.send(JSON.stringify({ type: "mirror_status", status: st }));
          } else if (cmdType === "stop") {
            const st = await this.stopMirror();
            ws.send(JSON.stringify({ type: "mirror_status", status: st }));
          } else if (cmdType === "status") {
            ws.send(JSON.stringify({ type: "mirror_status", status: this.getMirrorStatus() }));
          } else if (cmdType === "input") {
            const res = await this.sendMirrorInput(cmd);
            ws.send(
              JSON.stringify({
                type: "input_ack",
                reqId: cmd.reqId,
                ok: true,
                result: res,
              })
            );
          }
        } catch (err: any) {
          try {
            ws.send(
              JSON.stringify({
                type: "mirror_error",
                reqId: cmd.reqId,
                error: err?.message || String(err),
              })
            );
          } catch {}
        }
      }
    );

    this.session.on(
      "phoneRequest",
      (
        req: JsonRpcRequest,
        reply: (res: { result?: unknown; error?: { code: number; message: string } }) => void,
        source?: { tier: string; connectionId?: string; phoneId?: string }
      ) => {
        try {
          if (req.method === "inbox.submitItem") {
            const params = req.params as InboxSubmitItemParams;
            const alreadyCommitted = Boolean(this.inbox.getItem(params.clientItemId));
            if (!alreadyCommitted) {
              const dupOtp = this.inbox.findRecentDuplicateOtp(params);
              if (dupOtp) {
                reply({
                  result: {
                    ack: true,
                    deduplicated: true,
                    savedId: params.clientItemId,
                    otpCode: dupOtp.otpCode,
                    macPath: this.inbox.inboxRootDir,
                  },
                });
                return;
              }
            }
            const stored = this.inbox.storeSubmittedItem(params, (streamId) =>
              this.session.getCompletedStream(streamId)
            );
            try {
              this.inbox.pruneStagingCache();
            } catch {
              // best-effort staging cleanup
            }

            if (alreadyCommitted) {
              reply({ result: { ack: true, savedId: stored.itemId, macPath: stored.files[0]?.absPath ?? this.inbox.inboxRootDir } });
              return;
            }
            const textBody = stored.textContent || stored.voiceTranscript || "";
            const imageFile = stored.files.find(
              (f) =>
                (f.mimeType || "").startsWith("image/") ||
                /\.(png|jpe?g|webp|gif)$/i.test(f.fileName)
            );
            const audioFile = stored.files.find(
              (f) =>
                (f.mimeType || "").startsWith("audio/") ||
                /\.(m4a|mp3|wav|ogg|aac)$/i.test(f.fileName)
            );

            let actionHint = "";
            const nativeHudActive = Boolean(this.hudServer?.isConnected());
            if (stored.intentTag === "sms_otp" || stored.otpCode) {
              const otpCode = stored.otpCode || textBody.trim();
              const isFreshOtp =
                Math.abs(Date.now() - (stored.createdAtMs || Date.now())) < 5 * 60 * 1000;
              if (this.config.autoSyncOtpToMac !== false) {
                let copiedOtp = false;
                if (isFreshOtp && otpCode && this.config.autoCopyTextToClipboard !== false) {
                  this.copyTextToMacClipboard(otpCode);
                  copiedOtp = true;
                }
                const senderBadge = stored.otpSender ? `【${stored.otpSender}】` : "手机";
                const subtitle = copiedOtp
                  ? `已复制纯验证码 ${otpCode} · 直接 ⌘V 粘贴`
                  : `来自小米手机验证码同步`;
                this.showMacDesktopNotification(
                  `🔑 ${senderBadge}验证码: ${otpCode}`,
                  textBody || stored.title || `验证码: ${otpCode}`,
                  subtitle,
                  "Glass",
                  stored
                );
              }
            } else {
              if (textBody && this.config.autoCopyTextToClipboard !== false) {
                this.copyTextToMacClipboard(textBody);
                actionHint = " · 已复制可 ⌘V";
              }
              if (imageFile && this.config.autoPreviewImageOnMac !== false) {
                if (nativeHudActive) {
                  actionHint = actionHint
                    ? `${actionHint} · 点卡片内联查看`
                    : " · 点卡片内联查看";
                } else {
                  const opened = this.openFileOnMac(imageFile.absPath, false);
                  const label = opened.downgradedToReveal ? "已在 Finder 中定位" : "已打开预览";
                  actionHint = actionHint ? `${actionHint} & ${label}` : ` · ${label}`;
                }
              } else if (!imageFile && stored.files.length > 0 && nativeHudActive) {
                actionHint = actionHint
                  ? `${actionHint} · 点卡片直接预览`
                  : " · 点卡片直接预览";
              }
              if (audioFile && !stored.voiceTranscript) {
                actionHint = actionHint ? `${actionHint} · 正在离线转写语音` : " · 正在离线转写语音";
                void this.transcribeInboxItem(stored.itemId).catch(() => {});
              }

              const attachmentDesc =
                stored.files.length > 1
                  ? `${stored.files[0].fileName} 等 ${stored.files.length} 个文件`
                  : stored.files[0]?.fileName || "附件";
              this.showMacDesktopNotification(
                `📱 收到小米手机投递 (#${stored.intentTag})${actionHint}`,
                textBody || stored.title || `新文件 (${attachmentDesc}) 已存入 Inbox`,
                nativeHudActive ? `#${stored.intentTag} · 点击查看详情(无需打开外部App)` : undefined,
                undefined,
                stored
              );
            }
            reply({
              result: {
                ack: true,
                savedId: stored.itemId,
                otpCode: stored.otpCode,
                macPath: stored.files[0]?.absPath ?? this.inbox.inboxRootDir,
              },
            });
          } else if (req.method === "approval.respond") {
            const params = req.params as ApprovalRespondParams;
            const settled = this.settledApprovals.get(params.approvalId);
            if (settled && Date.now() <= settled.expiresAtMs && settled.response.approved === params.approved
                && settled.response.selectedOption === params.selectedOption) {
              reply({ result: { ack: true } });
              return;
            }
            const waiter = this.pendingApprovals.get(params.approvalId);
            if (!waiter || Date.now() > waiter.expiresAtMs) throw new Error("Approval is unknown or expired");
            const selectedIndex = waiter.options.indexOf(params.selectedOption);
            if (selectedIndex < 0 || params.approved !== (selectedIndex === 0)) throw new Error("Invalid approval option or decision");
            clearTimeout(waiter.timer);
            this.pendingApprovals.delete(params.approvalId);
            this.settledApprovals.set(params.approvalId, { response: params, expiresAtMs: waiter.expiresAtMs });
            waiter.resolve(params);
            reply({ result: { ack: true } });
          } else if (req.method === "gui.emergencyStop") {
            this.phoneEmergencyStopEpoch++;
            this.showMacDesktopNotification(
              "🛑 手机端已触发 AI 急停",
              "你在小米手机上点击了急停胶囊，GUI 自动化已立即中止。"
            );
            reply({ result: { ack: true } });
          } else if (req.method === "apk.getVersion") {
            const meta = this.session.getApkMetadata();
            reply({
              result: {
                available: meta.available,
                versionCode: meta.versionCode,
                versionName: meta.versionName,
                sha256: meta.sha256,
                sizeBytes: meta.sizeBytes,
                updatedAtMs: meta.updatedAtMs,
                updatedAtIso: meta.updatedAtIso,
                downloadUrl: meta.downloadUrl,
                githubDownloadUrl: meta.githubDownloadUrl,
                lanIps: PhoneSessionManager.getLocalLanIps(),
                lanPort: this.config.lanPort,
              },
            });
          } else if (req.method === "airpods.claimByPhone") {
            const p = (req.params || {}) as Record<string, any>;
            const isManual = p.reason === "manual";
            if (this.config.autoSwitchAirpods === false && !isManual) {
              reply({ result: { ok: false, ignored: true, reason: "autoSwitchAirpods disabled" } });
              return;
            }
            if (!isManual && Date.now() < this.suppressPhoneAirpodsClaimUntilMs) {
              reply({ result: { ok: false, ignored: true, reason: "suppressed during TTS / Find-My-Phone" } });
              return;
            }
            const targetMac = p.targetMac ? String(p.targetMac) : undefined;
            const targetName = p.targetName ? String(p.targetName) : "AirPods";
            const pauseMacMedia =
              this.config.pauseOtherOnAirpodsSwitch !== false && p.pauseMacMedia !== false;
            this.lastAirpodsSwitchEvent = {
              direction: "MAC_TO_PHONE",
              reason: String(p.reason || "phone_audio_started"),
              deviceName: targetName,
              deviceMac: targetMac,
              atMs: Date.now(),
            };
            if (this.airpodsHandoffInFlight) {
              reply({ result: { ok: false, released: false, reason: "handoff in progress" } });
              return;
            }
            const bridge = this.session.getBluetoothBridge();
            if (!bridge) {
              reply({ result: { ok: false, released: false, reason: "Bluetooth bridge unavailable" } });
              return;
            }
            this.airpodsHandoffInFlight = true;
            void (async () => {
              let response;
              try {
                const result = await bridge.releaseAirpodsFromMac({ pauseMedia: pauseMacMedia, targetMac, timeoutMs: 8000 });
                response = { ok: Boolean(result.ok), released: Boolean(result.ok), message: result.message };
              } catch (err: any) {
                response = { ok: false, released: false, message: err?.message || String(err) };
              } finally {
                this.airpodsHandoffInFlight = false;
              }
              reply({ result: response });
            })();
          } else if (req.method === "airpods.statusReport") {
            const p = (req.params || {}) as Record<string, any>;
            this.ingestPhoneAirpodsSnapshot({
              airpodsConnected: Boolean(p.connected),
              airpodsName: p.deviceName ? String(p.deviceName) : undefined,
              airpodsMac: p.deviceMac ? String(p.deviceMac) : undefined,
              mediaPlaying: Boolean(p.mediaPlaying),
              audioModeInCall: Boolean(p.inCall),
              pairedAirpods: Array.isArray(p.pairedAirpods) ? p.pairedAirpods : undefined,
            });
            reply({ result: { ok: true } });
          } else if (req.method === "tts.synthesize") {
            const p = (req.params || {}) as Record<string, any>;
            const text = String(p.text || "").trim();
            const voice = p.voice ? String(p.voice) : undefined;
            let fallbackReason: TtsFallbackReason | undefined;
            void this.synthesizeNeuralTts(text, { voice, onFallback: (reason) => { fallbackReason = reason; } })
              .then((res) => {
                if (!res) {
                  reply({ result: { ok: false, ttsEngine: "android_local", ttsFallbackReason: fallbackReason || "synthesis_failed" } });
                  return;
                }
                reply({
                  result: {
                    ok: true,
                    audioBase64: res.audioBase64,
                    voiceId: res.voiceId,
                    voiceLabel: res.voiceLabel,
                    cached: res.cached,
                    bytes: res.bytes,
                  },
                });
              })
              .catch(() => {
                reply({ result: { ok: false } });
              });
          } else if (req.method === "wechat.standby.handoff") {
            const handoffParams = (req.params || {}) as Record<string, any>;
            void (async () => {
              try {
                await this.wechat.setStandbyPollPaused(true);
                const result = await this.wechat.submitStandbyHandoff(handoffParams);
                if (typeof handoffParams.latestUpdatesBuf === "string" && handoffParams.latestUpdatesBuf) {
                  this.lastSyncedUpdatesBuf = handoffParams.latestUpdatesBuf;
                }
                this.syncMacHudState();
                reply({ result });
              } catch (err: any) {
                await this.wechat.setStandbyPollPaused(false).catch(() => {});
                reply({
                  error: {
                    code: -32000,
                    message: err?.message || "Standby handoff failed",
                  },
                });
              }
            })();
          } else if (req.method === "chisu.curate_url") {
            const p = (req.params || {}) as CurateBrowserUrlParams;
            void this.curateBrowserUrlToVaultAndContext(p)
              .then((res) => reply({ result: res }))
              .catch((err: any) =>
                reply({
                  result: {
                    ok: false,
                    url: String(p.url || ""),
                    title: String(p.title || p.url || ""),
                    mediaKind: "article",
                    shortSummary: "",
                    error: err?.message || String(err),
                  } satisfies CurateBrowserUrlResult,
                })
              );
          } else if (req.method === "chisu.recent_curated") {
            const p = (req.params || {}) as { limit?: number };
            reply({ result: { ok: true, items: this.memoryMirror.getRecentCuratedContext(p.limit ?? 8) } });
          } else if (req.method === "wechat.articles.list") {
            const p = (req.params || {}) as { limit?: number };
            void this.wechat
              .fetchArticlesOverview(p.limit ?? 80)
              .then((res) => reply({ result: res }))
              .catch((err: any) => reply({ result: { ok: false, articles: [], error: err?.message || String(err) } }));
          } else if (req.method === "wechat.article.detail") {
            const p = (req.params || {}) as { articleId?: number; turnId?: number; sourceUrl?: string; title?: string };
            void this.wechat
              .fetchArticleDetail({
                articleId: p.articleId,
                turnId: p.turnId,
                sourceUrl: p.sourceUrl,
                title: p.title,
              })
              .then((res) => reply({ result: res }))
              .catch((err: any) => reply({ result: { ok: false, error: err?.message || String(err) } }));
          } else if (req.method === "wechat.article.asset") {
            const p = (req.params || {}) as { articleId?: number; turnId?: number; doc?: "note" | "source"; path?: string };
            void this.wechat
              .fetchArticleAsset({
                articleId: p.articleId ? Number(p.articleId) : undefined,
                turnId: p.turnId ? Number(p.turnId) : undefined,
                doc: p.doc === "note" ? "note" : "source",
                path: String(p.path || ""),
              })
              .then((res) => reply({ result: res }))
              .catch((err: any) => reply({ result: { ok: false, error: err?.message || String(err) } }));
          } else if (req.method === "edge.requestSync") {
            void this.syncEdgeConfigAndCapsuleToPhone(true)
              .then((summary) => reply({ result: { ok: true, ...summary } }))
              .catch((err: any) =>
                reply({
                  error: {
                    code: -32000,
                    message: err?.message || "Edge sync failed",
                  },
                })
              );
          } else if (req.method === "chat.sync.batch") {
            const summary = this.memoryMirror.syncBatch((req.params || {}) as ChatSyncBatchParams);
            reply({ result: { ok: true, ...summary } });
          } else if (req.method === "memory.promote_vault") {
            const promoted = this.memoryMirror.promoteToVault(
              (req.params || {}) as MemoryPromoteVaultParams
            );
            reply({ result: promoted });
          } else if (req.method === "memory.search_vault") {
            const p = (req.params || {}) as { query?: string; limit?: number };
            const found = this.memoryMirror.searchContext(String(p.query || ""), p.limit ?? 8);
            reply({ result: found });
          } else if (req.method === "memory.read_vault_doc") {
            const p = (req.params || {}) as { relPath?: string };
            reply({ result: this.memoryMirror.readVaultDocument(String(p.relPath || "")) });
          } else if (req.method === "book.list") {
            reply({ result: { ok: true, books: listImportedBooks(this.memoryMirror.resolveVaultRoot()) } });
          } else if (req.method === "secretary.brief") {
            const brief = this.secretary.buildTodayBrief({
              vaultRoot: this.memoryMirror.resolveVaultRoot(),
              wechatDbPath: path.join(this.wechat.wechatHomeDir, "data", "wechat_context.db"),
              agentSessions: this.agentHub.listSessions(20),
            });
            reply({ result: { ok: true, brief } });
          } else if (req.method === "secretary.commitment.upsert") {
            const p = (req.params || {}) as { id?: string; title?: string; dueHint?: string; source?: string; status?: "open" | "done" | "snoozed" };
            const item = this.secretary.upsertCommitment({
              id: p.id,
              title: String(p.title || ""),
              dueHint: p.dueHint,
              source: p.source || "mobile",
              status: p.status,
            });
            reply({ result: { ok: true, item } });
          } else if (req.method === "secretary.commitment.toggle") {
            const p = (req.params || {}) as { id?: string };
            const item = this.secretary.toggleCommitment(String(p.id || ""));
            reply({ result: { ok: Boolean(item), item } });
          } else if (req.method === "secretary.commitment.delete") {
            const p = (req.params || {}) as { id?: string };
            reply({ result: { ok: this.secretary.deleteCommitment(String(p.id || "")) } });
          } else if (req.method === "secretary.profile.save") {
            const p = (req.params || {}) as { items?: string[] };
            const items = this.secretary.saveProfileItems(Array.isArray(p.items) ? p.items : []);
            reply({ result: { ok: true, items } });
          } else if (req.method === "secretary.memory.evolve") {
            const p = (req.params || {}) as {
              profileAdditions?: string[];
              implicitCommitments?: Array<{ title: string; dueHint?: string }>;
            };
            const evolved = this.secretary.evolveMemoryFromDialogue({
              profileAdditions: Array.isArray(p.profileAdditions) ? p.profileAdditions : [],
              implicitCommitments: Array.isArray(p.implicitCommitments) ? p.implicitCommitments : [],
            });
            reply({ result: { ok: true, ...evolved } });
          } else if (req.method === "secretary.nudge.dismiss") {
            const p = (req.params || {}) as { id?: string };
            this.secretary.dismissNudge(String(p.id || ""));
            reply({ result: { ok: true } });
          } else if (req.method === "memory.center.get") {
            const snapshot = this.secretary.buildMemoryCenterSnapshot({
              workingMemoryItems: this.memoryMirror.loadWorkingMemory(),
              vaultRoot: this.memoryMirror.resolveVaultRoot(),
            });
            reply({ result: { ok: true, snapshot } });
          } else if (req.method === "memory.entry.upsert") {
            const p = (req.params || {}) as {
              id?: string;
              text?: string;
              source?: "manual" | "auto";
              originDetail?: string;
            };
            const entry = this.secretary.addOrUpdateMemoryEntry({
              id: p.id,
              text: String(p.text || ""),
              source: p.source || "manual",
              originDetail: p.originDetail,
            });
            reply({ result: { ok: true, entry } });
          } else if (req.method === "memory.entry.delete") {
            const p = (req.params || {}) as { id?: string; text?: string; category?: string };
            const target = String(p.text || p.id || "");
            const isWorking = p.category === "working" || String(p.id || "").startsWith("wm_");
            const deleted = isWorking
              ? this.memoryMirror.deleteWorkingMemoryItem(target)
              : this.secretary.deleteMemoryEntry(String(p.id || p.text || ""), true) ||
                (p.text ? this.secretary.deleteMemoryEntry(String(p.text), true) : false);
            reply({ result: { ok: deleted } });
          } else if (req.method === "mac.control.status") {
            const p = (req.params || {}) as { refreshApps?: boolean };
            void this.macControl
              .getStatusSnapshotAsync(this.buildMacControlContext(), Boolean(p.refreshApps))
              .then((status) => reply({ result: { ok: true, status } }))
              .catch(() =>
                reply({
                  result: {
                    ok: true,
                    status: this.macControl.getStatusSnapshot(this.buildMacControlContext(), Boolean(p.refreshApps)),
                  },
                })
              );
          } else if (req.method === "mac.control.execute") {
            const p = (req.params || {}) as MacControlExecuteParams;
            void this.macControl
              .execute(p, { ...this.buildMacControlContext(), activeTier: source?.tier || "UNKNOWN",
                requestConnectionId: source?.connectionId })
              .then((res) => reply({ result: res }))
              .catch((err: any) =>
                reply({
                  error: {
                    code: -32000,
                    message: err?.message || "Mac control failed",
                  },
                })
              );
          } else if (req.method === "mac.control.set_unlock_password") {
            const p = (req.params || {}) as { password?: string };
            const ok = this.macControl.setUnlockPassword(String(p.password || ""));
            reply({
              result: {
                ok,
                status: this.macControl.getStatusSnapshot(this.buildMacControlContext()),
              },
            });
          } else if (req.method === "mac.control.clear_unlock_password") {
            const ok = this.macControl.clearUnlockPassword();
            reply({
              result: {
                ok,
                status: this.macControl.getStatusSnapshot(this.buildMacControlContext()),
              },
            });
          } else if (req.method === "agent.sessions.list") {
            const p = (req.params || {}) as { limit?: number };
            reply({ result: { sessions: this.agentHub.listSessions(p.limit ?? 96) } });
          } else if (req.method === "agent.workspace_catalog" || req.method === "agent.projects.list") {
            const p = (req.params || {}) as { limit?: number };
            reply({ result: this.agentHub.getWorkspaceCatalog(p.limit ?? 96) });
          } else if (req.method === "agent.quotas") {
            reply({ result: this.agentHub.getQuotasSnapshot() });
          } else if (req.method === "agent.session.detail") {
            const p = (req.params || {}) as { backend?: string; sessionId: string };
            const detail = this.agentHub.getSessionDetail(
              normalizeAgentBackend(p.backend),
              String(p.sessionId || "")
            );
            reply({ result: detail });
          } else if (req.method === "agent.session.start") {
            const p = (req.params || {}) as AgentSessionStartParams;
            const started = this.agentHub.startSession({
              ...p,
              prompt: this.enrichAgentPromptWithRecentCurated(p.prompt),
            });
            reply({ result: { ok: true, session: started } });
          } else if (req.method === "agent.session.continue") {
            const p = (req.params || {}) as AgentSessionContinueParams;
            const resumed = this.agentHub.continueSession({
              ...p,
              prompt: this.enrichAgentPromptWithRecentCurated(p.prompt),
            });
            reply({ result: { ok: true, session: resumed } });
          } else if (req.method === "agent.session.cancel") {
            const p = (req.params || {}) as { backend?: string; sessionId: string };
            const cancelled = this.agentHub.cancelSession(
              normalizeAgentBackend(p.backend),
              String(p.sessionId || "")
            );
            reply({ result: { ok: cancelled } });
          } else {
            reply({ result: { ack: true } });
          }
        } catch (err: any) {
          reply({
            error: {
              code: typeof err?.code === "number" ? err.code : -32000,
              message: err?.message || "Failed handling phone request",
            },
          });
        }
      }
    );
  }

  private ingestPhoneAirpodsSnapshot(snap: Record<string, any>): void {
    if (typeof snap.airpodsConnected === "boolean") {
      this.phoneAirpodsState.connected = snap.airpodsConnected;
    }
    if (typeof snap.airpodsName === "string") {
      this.phoneAirpodsState.deviceName = snap.airpodsName || undefined;
    }
    if (typeof snap.airpodsMac === "string") {
      this.phoneAirpodsState.deviceMac = snap.airpodsMac || undefined;
    }
    if (typeof snap.mediaPlaying === "boolean") {
      this.phoneAirpodsState.mediaPlaying = snap.mediaPlaying;
    }
    if (typeof snap.audioModeInCall === "boolean") {
      this.phoneAirpodsState.inCall = snap.audioModeInCall;
    }
    if (Array.isArray(snap.pairedAirpods)) {
      this.phoneAirpodsState.pairedAirpods = snap.pairedAirpods.map((item: any) => ({
        name: String(item?.name || "AirPods"),
        mac: String(item?.mac || ""),
        connected: Boolean(item?.connected),
      }));
    }
    this.session.getBluetoothBridge()?.updateAirpodsConfig({
      autoSwitch: this.config.autoSwitchAirpods !== false,
      pauseOtherOnSwitch: this.config.pauseOtherOnAirpodsSwitch !== false,
      preferredMac: this.config.preferredAirpodsMac,
      phoneAirpodsConnected: this.phoneAirpodsState.connected,
      phoneAirpodsMac: this.phoneAirpodsState.deviceMac,
      phoneAirpodsName: this.phoneAirpodsState.deviceName,
      phoneInCall: this.phoneAirpodsState.inCall,
    });
  }

  private lastSyncedPhoneAirpodsKey = "";

  public syncAirpodsConfigToPhoneOnly(force = false): void {
    if (this.session.getActiveTier() === "DISCONNECTED") return;
    const btBridge = this.session.getBluetoothBridge();
    const macSt = btBridge?.getAirpodsStatus();
    const payload = {
      autoSwitchAirpods: this.config.autoSwitchAirpods !== false,
      pauseOtherOnAirpodsSwitch: this.config.pauseOtherOnAirpodsSwitch !== false,
      preferredAirpodsMac: this.config.preferredAirpodsMac || "",
      macAirpodsConnected: Boolean(macSt?.connected),
      macAirpodsMac: macSt?.deviceMac || "",
      macAirpodsName: macSt?.deviceName || "",
    };
    const key = JSON.stringify(payload);
    if (!force && key === this.lastSyncedPhoneAirpodsKey) return;
    this.lastSyncedPhoneAirpodsKey = key;
    void this.session.callPhone("airpods.syncConfig", payload, 2000).catch(() => {});
  }

  public syncAirpodsConfigToBridgeAndPhone(): void {
    const btBridge = this.session.getBluetoothBridge();
    btBridge?.updateAirpodsConfig({
      autoSwitch: this.config.autoSwitchAirpods !== false,
      pauseOtherOnSwitch: this.config.pauseOtherOnAirpodsSwitch !== false,
      preferredMac: this.config.preferredAirpodsMac,
      phoneAirpodsConnected: this.phoneAirpodsState.connected,
      phoneAirpodsMac: this.phoneAirpodsState.deviceMac,
      phoneAirpodsName: this.phoneAirpodsState.deviceName,
      phoneInCall: this.phoneAirpodsState.inCall,
    });
    this.syncAirpodsConfigToPhoneOnly(true);
  }

  private recordAirpodsDiagnostics(side: "mac" | "phone", diagnostics: any): void {
    if (!diagnostics || typeof diagnostics !== "object") return;
    const record = { atMs: Date.now(), ...diagnostics };
    this.airpodsDiagnostics[side] = record;
    try {
      const file = path.join(this.homeDir, "logs", "airpods-operations.jsonl");
      const lines = fs.existsSync(file) ? fs.readFileSync(file, "utf8").trim().split("\n").filter(Boolean) : [];
      lines.push(JSON.stringify({ side, ...record }));
      fs.writeFileSync(file, lines.slice(-200).join("\n") + "\n");
    } catch { /* Diagnostics must not break an otherwise completed handoff. */ }
  }

  public getAirpodsStatusSnapshot(): AirPodsStatusSnapshot {
    const macSt = this.session.getBluetoothBridge()?.getAirpodsStatus() || {
      connected: false,
      isDefaultOutput: false,
      macAudioRunning: false,
      pairedAirpods: [],
    };
    const activeOwner: "MAC" | "PHONE" | "NONE" = macSt.connected
      ? "MAC"
      : this.phoneAirpodsState.connected
        ? "PHONE"
        : "NONE";
    const activeDeviceName =
      (macSt.connected ? macSt.deviceName : undefined) ||
      (this.phoneAirpodsState.connected ? this.phoneAirpodsState.deviceName : undefined) ||
      macSt.deviceName ||
      this.phoneAirpodsState.deviceName;
    const activeDeviceMac =
      (macSt.connected ? macSt.deviceMac : undefined) ||
      (this.phoneAirpodsState.connected ? this.phoneAirpodsState.deviceMac : undefined) ||
      macSt.deviceMac ||
      this.phoneAirpodsState.deviceMac;

    return {
      autoSwitchEnabled: this.config.autoSwitchAirpods !== false,
      pauseOtherOnSwitch: this.config.pauseOtherOnAirpodsSwitch !== false,
      preferredAirpodsMac: this.config.preferredAirpodsMac,
      activeOwner,
      activeDeviceName,
      activeDeviceMac,
      mac: {
        connected: macSt.connected,
        isDefaultOutput: macSt.isDefaultOutput,
        audioRunning: macSt.macAudioRunning,
        defaultOutputName: macSt.defaultOutputName,
        deviceName: macSt.deviceName,
        deviceMac: macSt.deviceMac,
        pairedAirpods: macSt.pairedAirpods,
      },
      phone: {
        ...this.phoneAirpodsState,
      },
      lastSwitchEvent: this.lastAirpodsSwitchEvent,
      diagnostics: this.airpodsDiagnostics,
    };
  }

  public async switchAirpodsToMac(targetMac?: string, reason = "manual"): Promise<Record<string, any>> {
    if (this.airpodsHandoffInFlight) return { ok: false, direction: "PHONE_TO_MAC", stage: "busy", message: "已有 AirPods 切换正在进行，请等待结果" };
    this.airpodsHandoffInFlight = true;
    try { return await this.performAirpodsSwitchToMac(targetMac, reason); }
    finally { this.airpodsHandoffInFlight = false; }
  }

  private async performAirpodsSwitchToMac(targetMac?: string, reason = "manual"): Promise<Record<string, any>> {
    const pausePhoneMedia = this.config.pauseOtherOnAirpodsSwitch !== false;
    this.lastAirpodsSwitchEvent = {
      direction: "PHONE_TO_MAC",
      reason,
      deviceMac: targetMac,
      atMs: Date.now(),
    };
    if (this.session.getActiveTier() !== "DISCONNECTED") {
      const release = await this.session.bindPhoneSession()(
        "airpods.handoffToMac",
        { targetMac, pausePhoneMedia, disconnectOnPhone: true, reason, deadlineMs: Date.now() + 3000 },
        4000
      ).catch((err) => ({ ok: false, message: err?.message || String(err) }));
      this.recordAirpodsDiagnostics("phone", release?.diagnostics);
      if (!release?.ok) return {
        ok: false, direction: "PHONE_TO_MAC", stage: "phone_release",
        message: release?.message || "手机耳机释放未确认", status: this.getAirpodsStatusSnapshot(),
      };
    }
    const res = await this.session.getBluetoothBridge()?.connectAirpodsToMac(targetMac);
    this.recordAirpodsDiagnostics("mac", res?.diagnostics);
    if (res?.deviceName || res?.deviceMac) {
      this.lastAirpodsSwitchEvent = {
        direction: "PHONE_TO_MAC",
        reason,
        deviceName: res.deviceName,
        deviceMac: res.deviceMac,
        atMs: Date.now(),
      };
    }
    return {
      ok: Boolean(res?.ok),
      direction: "PHONE_TO_MAC",
      diagnostics: res?.diagnostics,
      message: res?.message || (res?.ok ? "已切换到 Mac" : "切换到 Mac 失败"),
      status: this.getAirpodsStatusSnapshot(),
    };
  }

  public async switchAirpodsToPhone(targetMac?: string, reason = "manual"): Promise<Record<string, any>> {
    if (this.airpodsHandoffInFlight) return { ok: false, direction: "MAC_TO_PHONE", stage: "busy", message: "已有 AirPods 切换正在进行，请等待结果" };
    this.airpodsHandoffInFlight = true;
    try { return await this.performAirpodsSwitchToPhone(targetMac, reason); }
    finally { this.airpodsHandoffInFlight = false; }
  }

  private async performAirpodsSwitchToPhone(targetMac?: string, reason = "manual"): Promise<Record<string, any>> {
    const failure = (stage: string, message: string, phoneResult: any = null) => ({
      ok: false,
      direction: "MAC_TO_PHONE",
      stage,
      message,
      phoneResult,
      status: this.getAirpodsStatusSnapshot(),
    });
    // Do not disconnect a working Mac audio route when the destination cannot receive a command.
    if (this.session.getActiveTier() === "DISCONNECTED") {
      return failure("phone_unavailable", "手机离线，未释放 Mac 耳机连接");
    }
    const bridge = this.session.getBluetoothBridge();
    if (!bridge) return failure("mac_release", "Mac 蓝牙桥不可用，未释放耳机连接");
    const callPhone = this.session.bindPhoneSession();
    const pauseMacMedia = this.config.pauseOtherOnAirpodsSwitch !== false;
    this.lastAirpodsSwitchEvent = {
      direction: "MAC_TO_PHONE",
      reason,
      deviceMac: targetMac,
      atMs: Date.now(),
    };
    const releaseResult: Record<string, any> = await bridge.releaseAirpodsFromMac({
      pauseMedia: pauseMacMedia,
      targetMac,
      timeoutMs: 8000,
    })
      .catch((err) => ({ ok: false, message: err?.message || String(err) }));
    this.recordAirpodsDiagnostics("mac", releaseResult?.diagnostics);
    if (!releaseResult?.ok) {
      return failure("mac_release", releaseResult?.message || "Mac 耳机释放未确认");
    }
    if (this.session.getActiveTier() === "DISCONNECTED") {
      return failure("phone_connect", "Mac 已释放耳机，但手机连接已断开，切换未完成");
    }
    const phoneRes = await callPhone<any>(
        "airpods.connectOnPhone",
        {
          targetMac: targetMac || this.config.preferredAirpodsMac,
          reason,
          deadlineMs: Date.now() + 14000,
        },
        16000
      )
      .catch((err) => ({ ok: false, error: err?.message || String(err) }));
    this.recordAirpodsDiagnostics("phone", phoneRes?.diagnostics);
    if (phoneRes?.status) {
      this.session.setPhoneStatus(phoneRes.status);
      this.ingestPhoneAirpodsSnapshot(phoneRes.status);
    }
    if (!phoneRes?.ok) {
      return failure("phone_connect", phoneRes?.error || phoneRes?.message || "手机连接耳机未确认", phoneRes);
    }
    return {
      ok: true,
      direction: "MAC_TO_PHONE",
      phoneResult: phoneRes,
      status: this.getAirpodsStatusSnapshot(),
    };
  }

  private getHudStatusPayload(): HudStatusPayload {
    const wxSnap = this.wechat.getCachedSnapshot();
    return {
      activeTier: this.session.getActiveTier(),
      phoneName: this.session.getPhoneStatus()?.deviceModel,
      inboxDir: this.inbox.inboxRootDir,
      mirrorUrl: `http://127.0.0.1:${this.config.lanPort}/mirror?hud=1`,
      wechatRunning: wxSnap.running,
      wechatConfigured: wxSnap.credentialsConfigured,
      wechatLastPoll: wxSnap.lastPoll ?? undefined,
      wechatApiBaseUrl: `http://127.0.0.1:${this.config.lanPort}/api/wechat`,
      wechatConfigUrl: `http://127.0.0.1:${this.config.lanPort}/wechat/config`,
    };
  }

  public peekWeChatInHud(articleId?: number): { ok: boolean; hudActive: boolean; articleId?: number } {
    if (this.hudServer?.isConnected()) {
      const delivered = this.hudServer.peekWeChat(articleId, {
        status: this.getHudStatusPayload(),
      });
      return { ok: delivered, hudActive: true, articleId };
    }
    return { ok: false, hudActive: false, articleId };
  }

  private enqueuePendingWeChatPhoneItem(entry: {
    key: string;
    articleId?: number;
    title: string;
    markdown: string;
    metadata?: Record<string, unknown>;
    createdAtMs: number;
  }): void {
    try {
      let queue: Array<{
        key: string;
        articleId?: number;
        title: string;
        markdown: string;
        metadata?: Record<string, unknown>;
        createdAtMs: number;
      }> = [];
      if (fs.existsSync(this.wechatPendingPhoneQueuePath)) {
        queue = JSON.parse(fs.readFileSync(this.wechatPendingPhoneQueuePath, "utf8"));
        if (!Array.isArray(queue)) queue = [];
      }
      queue = queue.filter((q) => q.key !== entry.key);
      queue.push({ ...entry, revision: generateUuidV7() } as typeof entry);
      if (queue.length > 25) {
        queue = queue.slice(queue.length - 25);
      }
      this.writeSecureFileAtomic(this.wechatPendingPhoneQueuePath, JSON.stringify(queue, null, 2));
    } catch {
      // ignore queue write errors
    }
  }

  public async pushWeChatNoteToPhone(opts: {
    articleId?: number;
    title?: string;
    markdown?: string;
    queueIfOffline?: boolean;
  }): Promise<{ ok: boolean; queuedOffline?: boolean; streamId?: string; title?: string; error?: string }> {
    let title = (opts.title || "").trim();
    let markdown = (opts.markdown || "").trim();
    const articleId = Number(opts.articleId || 0);
    let articleMeta: Record<string, unknown> | undefined;

    if (articleId > 0 && this.wechat.isRunning()) {
      try {
        const res = await fetch(`http://127.0.0.1:${this.wechat.internalPort}/api/article?id=${articleId}`, {
          headers: { Host: `127.0.0.1:${this.wechat.internalPort}` },
          signal: AbortSignal.timeout(3000),
        });
        if (res.ok) {
          const data = (await res.json()) as Record<string, any>;
          const art = data.article || {};
          title = title || `💬 ${art.title || `微信文章 #${articleId}`}`;
          markdown =
            markdown ||
            String(data.noteMarkdown || art.short_summary || art.digest || "").trim();
          articleMeta = {
            wechatArticleId: articleId,
            author: String(art.author || "").trim(),
            sourceUrl: String(art.url || "").trim(),
            publishDate: String(art.publish_date || "").trim(),
            triage: String(art.triage || "").trim(),
            processLevel: String(art.process_level || "").trim(),
            userNotes: String(art.user_notes || "").trim(),
            shortSummary: String(art.short_summary || art.digest || "").trim().slice(0, 2000),
            hasSource: Boolean(data.sourceMarkdown || (art.source_md_path && fs.existsSync(art.source_md_path))),
          };
        }
      } catch {
        // fallback to provided fields
      }
    }

    if (!markdown) {
      return { ok: false, error: "未找到可同步的微信伴读或初筛内容" };
    }
    const finalTitle = title.startsWith("💬") ? title : `💬 ${title || "微信尺素伴读"}`;
    const safeStem = (finalTitle.replace(/^💬\s*/, "").replace(/[^\p{L}\p{N}_-]+/gu, "_").slice(0, 48) || "wechat_note");
    const fileName = `wx_${articleId > 0 ? `${articleId}_` : ""}${safeStem}.md`;

    if (this.session.getActiveTier() === "DISCONNECTED") {
      if (opts.queueIfOffline !== false) {
        this.enqueuePendingWeChatPhoneItem({
          key: articleId > 0 ? `article_${articleId}` : `wx_${Date.now()}`,
          articleId: articleId > 0 ? articleId : undefined,
          title: finalTitle,
          markdown,
          metadata: articleMeta,
          createdAtMs: Date.now(),
        });
        return { ok: true, queuedOffline: true, title: finalTitle };
      }
      return { ok: false, error: "手机当前离线，无法立即推送至手机时间流" };
    }

    try {
      const bytes = new TextEncoder().encode(markdown);
      const pushed = await this.session.pushStreamToPhone({
        fileName,
        mimeType: "text/markdown",
        data: bytes,
        title: finalTitle,
        intentTag: "wechat_context",
        enableCommuteTts: true,
        wechatArticleId: articleId > 0 ? articleId : undefined,
        metadata: articleMeta,
      });
      return { ok: true, streamId: pushed.streamId, title: finalTitle };
    } catch (err: any) {
      if (opts.queueIfOffline !== false) {
        this.enqueuePendingWeChatPhoneItem({
          key: articleId > 0 ? `article_${articleId}` : `wx_${Date.now()}`,
          articleId: articleId > 0 ? articleId : undefined,
          title: finalTitle,
          markdown,
          metadata: articleMeta,
          createdAtMs: Date.now(),
        });
        return { ok: true, queuedOffline: true, title: finalTitle };
      }
      return { ok: false, error: err?.message || String(err) };
    }
  }

  private flushPendingWeChatPhoneQueue(): Promise<void> {
    if (this.wechatPhoneFlush) return this.wechatPhoneFlush;
    this.wechatPhoneFlush = this.drainPendingWeChatPhoneQueue().finally(() => { this.wechatPhoneFlush = null; });
    return this.wechatPhoneFlush;
  }

  private async drainPendingWeChatPhoneQueue(): Promise<void> {
    const readQueue = (): Array<{key: string; title: string; markdown: string; articleId?: number;
      metadata?: Record<string, unknown>; revision?: string; createdAtMs: number}> => {
      if (!fs.existsSync(this.wechatPendingPhoneQueuePath)) return [];
      const value = JSON.parse(fs.readFileSync(this.wechatPendingPhoneQueuePath, "utf8"));
      if (!Array.isArray(value)) throw new Error("Invalid pending WeChat phone queue");
      return value;
    };
    // Remember failures for this invocation; do not spin on an unreachable phone.
    const attempted = new Set<string>();
    while (this.session.getActiveTier() !== "DISCONNECTED") {
      let queue: ReturnType<typeof readQueue>;
      try { queue = readQueue(); } catch { return; }
      const item = queue.find(i => !attempted.has(JSON.stringify(i)));
      if (!item) return;
      const version = JSON.stringify(item);
      attempted.add(version);
      try {
        const safeStem = item.title.replace(/^💬\s*/, "").replace(/[^\p{L}\p{N}_-]+/gu, "_").slice(0, 48) || "wechat_note";
        await this.session.pushStreamToPhone({
          fileName: `${item.key}_${safeStem}.md`, mimeType: "text/markdown",
          data: new TextEncoder().encode(item.markdown), title: item.title,
          intentTag: "wechat_context", enableCommuteTts: true,
          wechatArticleId: item.articleId, metadata: item.metadata,
        });
        // Re-read after await and remove only the acknowledged version, including for the same key.
        const latest = readQueue().filter(i => JSON.stringify(i) !== version);
        this.writeSecureFileAtomic(this.wechatPendingPhoneQueuePath, JSON.stringify(latest, null, 2));
      } catch {
        // Keep failures and concurrent enqueues intact for the next retry.
      }
    }
  }

  private async handleManagedWeChatEvent(evt: ManagedWeChatEvent): Promise<void> {
    await this.wechat.refreshHealth(true).catch(() => {});
    this.syncMacHudState();
    void this.syncWeChatCursorCheckpointToPhone();

    const toastTitle =
      evt.eventType === "article"
        ? `💬 初筛完成 · ${evt.title}`
        : evt.title;
    const toastSubtitle =
      evt.eventType === "article"
        ? "点击在「微信尺素」Tab 内联查看伴读笔记"
        : "青简·尺素 · 点击打开面板查看详情";

    if (this.hudServer?.isConnected()) {
      this.hudServer.notify(
        {
          title: toastTitle,
          message: evt.summary.slice(0, 220),
          subtitle: toastSubtitle,
          channel: "wechat",
          wechatArticleId: evt.articleId,
        },
        {
          items: this.inbox.queryLatest({ limit: 25 }),
          status: this.getHudStatusPayload(),
        }
      );
    } else {
      this.showMacDesktopNotification(toastTitle, evt.summary.slice(0, 180), toastSubtitle);
    }

    if (
      this.config.autoSyncWeChatToPhone !== false &&
      (evt.eventType === "article" || (evt.eventType === "schedule" && evt.taskKind === "digest"))
    ) {
      let fullMarkdown = evt.summary;
      const candidatePath = evt.notePath || evt.depositedPath;
      if (candidatePath && fs.existsSync(candidatePath)) {
        try {
          const raw = fs.readFileSync(candidatePath, "utf8").trim();
          if (raw) fullMarkdown = raw;
        } catch {
          // fallback to summary
        }
      }
      await this.pushWeChatNoteToPhone({
        articleId: evt.articleId,
        title: evt.title,
        markdown: fullMarkdown,
        queueIfOffline: true,
      }).catch(() => {});
    }
  }

  public syncMacHudState(): void {
    if (!this.hudServer?.isConnected()) return;
    this.hudServer.syncState(
      this.inbox.queryLatest({ limit: 25 }),
      this.getHudStatusPayload()
    );
  }

  public peekInboxInHud(itemId?: string): { ok: boolean; hudActive: boolean; itemId?: string } {
    const targetItem = itemId
      ? this.inbox.getItem(itemId)
      : this.inbox.queryLatest({ limit: 1 })[0];
    if (this.hudServer?.isConnected()) {
      const delivered = this.hudServer.peek(targetItem?.itemId ?? itemId, {
        item: targetItem,
        status: this.getHudStatusPayload(),
      });
      return { ok: delivered, hudActive: true, itemId: targetItem?.itemId ?? itemId };
    }
    return { ok: false, hudActive: false, itemId: targetItem?.itemId ?? itemId };
  }

  public async syncEdgeConfigAndCapsuleToPhone(forceCapsule = false): Promise<{
    proxyNodesCount: number;
    hasOpenAi: boolean;
    hasGemini: boolean;
    hasClawbot: boolean;
  }> {
    if (this.session.getActiveTier() === "DISCONNECTED") {
      return { proxyNodesCount: 0, hasOpenAi: false, hasGemini: false, hasClawbot: false };
    }
    const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../..");
    const wxSnap = await this.wechat.fetchEdgeStandbySnapshot();
    const edgeCfg = await buildEdgeSyncPayloadFromHost({
      omsHomeDir: this.homeDir,
      wechatHomeDir: this.wechat.wechatHomeDir,
      repoRootDir: repoRoot,
      wechatWorkerSnapshot: wxSnap,
    });
    if (this.lastKnownPhoneWifiSsid) {
      edgeCfg.pairedLanSsidHint = this.lastKnownPhoneWifiSsid;
    }
    if (this.config.wanRelay?.wssUrl && this.config.wanRelay?.authToken) {
      edgeCfg.wanRelay = {
        enabled: this.config.wanRelay.enabled !== false,
        wssUrl: this.config.wanRelay.wssUrl,
        authToken: this.config.wanRelay.authToken,
        caCertPem: this.config.wanRelay.caCertPem,
      };
    }

    if (this.session.getActiveTier() !== "DISCONNECTED") {
      const edgePayload: Record<string, unknown> = {
        ...(edgeCfg as unknown as Record<string, unknown>),
        contacts: this.config.contacts || {},
      };
      await this.session
        .callPhone("edge.config.sync", edgePayload, 8000)
        .catch(() => {});
    }

    const workingMem = this.memoryMirror.loadWorkingMemory();
    const secretaryProfile = this.secretary.getProfileItems().map((x) => `[偏好] ${x}`);
    const secretaryOpenLoops = this.secretary
      .listCommitments(false)
      .slice(0, 6)
      .map((c) => `[待办] ${c.title}${c.dueHint ? `（${c.dueHint}）` : ""}`);
    const enrichedWorkingMem = Array.from(
      new Set([...secretaryOpenLoops, ...secretaryProfile, ...workingMem])
    ).slice(0, 18);
    const rawCapsule = wxSnap?.contextCapsule as Record<string, any> | undefined;
    const capsule: Record<string, unknown> | undefined = rawCapsule
      ? {
          ...rawCapsule,
          recentWorkingMemory: Array.from(
            new Set([
              ...enrichedWorkingMem,
              ...(Array.isArray(rawCapsule.recentWorkingMemory) ? rawCapsule.recentWorkingMemory : []),
            ])
          ).slice(0, 18),
        }
      : enrichedWorkingMem.length > 0
        ? {
            revision: `wm_${enrichedWorkingMem.length}_${enrichedWorkingMem[0]?.slice(0, 8) || ""}`,
            generatedAtIso: new Date().toISOString(),
            nowSummary: "",
            queueSummary: "",
            topicsOverview: "",
            analysisBackgroundFormatted: "",
            analysisWarnings: [],
            matchedCandidates: [],
            recentWorkingMemory: enrichedWorkingMem,
          }
        : undefined;
    if (
      capsule &&
      this.session.getActiveTier() !== "DISCONNECTED" &&
      (forceCapsule || capsule.revision !== this.lastSyncedCapsuleRevision)
    ) {
      const ack = await this.session.callPhone("edge.context_capsule.sync", capsule, 8000).catch(() => null);
      if (ack) this.lastSyncedCapsuleRevision = String(capsule.revision || "");
    }

    const cp = wxSnap?.cursorCheckpoint;
    if (cp && this.session.getActiveTier() !== "DISCONNECTED") {
      await this.session.callPhone("wechat.cursor.checkpoint", cp, 5000).catch(() => {});
      if (typeof cp.updatesBuf === "string") {
        this.lastSyncedUpdatesBuf = cp.updatesBuf;
      }
    }

    this.ensureEdgeCursorSyncLoop();

    return {
      proxyNodesCount: edgeCfg.bootstrapNodes.length,
      hasOpenAi: Boolean(edgeCfg.openai?.apiKey),
      hasGemini: Boolean(edgeCfg.gemini?.apiKey),
      hasClawbot: Boolean(edgeCfg.clawbotAuth?.botToken),
    };
  }

  public async syncWeChatCursorCheckpointToPhone(macSleeping = false): Promise<void> {
    if (this.session.getActiveTier() === "DISCONNECTED") return;
    const wxSnap = await this.wechat.fetchEdgeStandbySnapshot();
    const cp = wxSnap?.cursorCheckpoint;
    if (!cp) return;
    if (!macSleeping && cp.updatesBuf === this.lastSyncedUpdatesBuf) return;
    if (macSleeping) {
      await this.wechat.setStandbyPollPaused(true).catch(() => {});
    }
    await this.session
      .callPhone(
        "wechat.cursor.checkpoint",
        {
          ...cp,
          macSleeping,
          checkpointAtMs: Date.now(),
        },
        3500
      )
      .catch(() => {});
    if (typeof cp.updatesBuf === "string") {
      this.lastSyncedUpdatesBuf = cp.updatesBuf;
    }
  }

  private ensureEdgeCursorSyncLoop(): void {
    if (this.edgeCursorSyncTimer) return;
    this.edgeCursorSyncTimer = setInterval(() => {
      if (this.session.getActiveTier() !== "DISCONNECTED") {
        void this.syncWeChatCursorCheckpointToPhone(false);
      }
    }, 20_000);
    (this.edgeCursorSyncTimer as any).unref?.();
  }

  private showMacDesktopNotification(
    title: string,
    message: string,
    subtitle?: string,
    soundName?: string,
    item?: StoredInboxItem
  ): void {
    if (soundName) {
      this.session.getBluetoothBridge()?.suppressAirpodsAutoGrab(2600);
    }
    if (
      this.hudServer?.isConnected() &&
      this.hudServer.notify(
        {
          title,
          message,
          subtitle,
          soundName,
          itemId: item?.itemId,
        },
        {
          item,
          items: this.inbox.queryLatest({ limit: 25 }),
          status: this.getHudStatusPayload(),
        }
      )
    ) {
      return;
    }
    if (process.env.OMS_DISABLE_OSASCRIPT === "1") return;
    const safeTitle = title.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    const safeMsg = message.replace(/\\/g, "\\\\").replace(/"/g, '\\"').slice(0, 180);
    const safeSub = subtitle
      ? subtitle.replace(/\\/g, "\\\\").replace(/"/g, '\\"').slice(0, 100)
      : "";
    let script = `display notification "${safeMsg}" with title "${safeTitle}"`;
    if (safeSub) {
      script += ` subtitle "${safeSub}"`;
    }
    if (soundName) {
      const safeSound = soundName.replace(/[^A-Za-z0-9_-]/g, "");
      if (safeSound) {
        script += ` sound name "${safeSound}"`;
      }
    }
    const child = execFile("osascript", ["-e", script], () => {});
    child.on("error", () => {});
  }

  private pruneFlatCacheDir(dirPath: string, maxFiles = 200, maxAgeMs = 7 * 24 * 3600 * 1000): void {
    try {
      if (!fs.existsSync(dirPath)) return;
      const now = Date.now();
      const entries = fs
        .readdirSync(dirPath)
        .map((name) => {
          const full = path.join(dirPath, name);
          try {
            const st = fs.statSync(full);
            if (!st.isFile()) return null;
            return { full, mtimeMs: st.mtimeMs };
          } catch {
            return null;
          }
        })
        .filter((e): e is { full: string; mtimeMs: number } => e !== null)
        .sort((a, b) => b.mtimeMs - a.mtimeMs);
      for (let i = 0; i < entries.length; i++) {
        if (i >= maxFiles || now - entries[i].mtimeMs > maxAgeMs) {
          try {
            fs.unlinkSync(entries[i].full);
          } catch {}
        }
      }
    } catch {
      // best-effort cache prune
    }
  }

  public async transcribeAudioFileWithLocalAsr(audioAbsPath: string): Promise<string | null> {
    if (!audioAbsPath || !fs.existsSync(audioAbsPath)) return null;
    const asrBinCandidates = [
      path.join(os.homedir(), ".local/bin/local-asr"),
      "/usr/local/bin/local-asr",
      "local-asr",
    ];
    const asrBin =
      asrBinCandidates.find((p) => p.startsWith("/") && fs.existsSync(p)) || "local-asr";
    const outDir = path.join(this.homeDir, "inbox", "asr_cache");
    fs.mkdirSync(outDir, { recursive: true });

    try {
      return await new Promise<string | null>((resolve) => {
        const child = execFile(
          asrBin,
          [audioAbsPath, "--out-dir", outDir, "--format", "json,txt", "--print-json", "--quiet"],
          { timeout: 60000, maxBuffer: 4 * 1024 * 1024 },
          (err, stdout) => {
            if (!err && stdout) {
              try {
                const trimmed = stdout.trim();
                const jsonStart = trimmed.indexOf("{");
                if (jsonStart !== -1) {
                  const parsed = JSON.parse(trimmed.slice(jsonStart));
                  const txt = String(parsed.text || parsed.raw_text || "").trim();
                  if (txt) {
                    resolve(txt);
                    return;
                  }
                }
              } catch {
                // fallback to txt file
              }
            }
            const stem = path.basename(audioAbsPath, path.extname(audioAbsPath));
            const txtPath = path.join(outDir, `${stem}.txt`);
            if (fs.existsSync(txtPath)) {
              const txt = fs.readFileSync(txtPath, "utf8").trim();
              if (txt) {
                resolve(txt);
                return;
              }
            }
            resolve(null);
          }
        );
        child.on("error", () => {});
      });
    } finally {
      this.pruneFlatCacheDir(outDir);
    }
  }

  public async transcribeInboxItem(itemId: string): Promise<{
    ok: boolean;
    itemId: string;
    transcript: string | null;
  }> {
    const item = this.inbox.getItem(itemId);
    if (!item) {
      throw new Error(`Inbox item not found: ${itemId}`);
    }
    const audioFile = item.files.find(
      (f) =>
        (f.mimeType || "").startsWith("audio/") ||
        /\.(m4a|mp3|wav|ogg|aac)$/i.test(f.fileName)
    );
    if (!audioFile || !fs.existsSync(audioFile.absPath)) {
      return { ok: false, itemId, transcript: null };
    }
    const transcript = await this.transcribeAudioFileWithLocalAsr(audioFile.absPath);
    if (transcript) {
      const updated = this.inbox.updateItemTranscript(itemId, transcript);
      if (this.config.autoCopyTextToClipboard !== false) {
        this.copyTextToMacClipboard(transcript);
      }
      this.showMacDesktopNotification(
        `🎙️ 手机语音已转写 (#${item.intentTag} · 可 ⌘V)`,
        transcript,
        "点击卡片查看完整转写与播放原声",
        undefined,
        updated
      );
      return { ok: true, itemId, transcript };
    }
    return { ok: false, itemId, transcript: null };
  }

  private async refreshPhoneStatusLive(force = false): Promise<void> {
    if (this.session.getActiveTier() === "DISCONNECTED") return;
    const now = Date.now();
    if (!force && now - this.lastStatusRefreshMs < 8000) return;
    this.lastStatusRefreshMs = now;
    const waitMs = this.session.getActiveTier() === "BT_RFCOMM" ? 3500 : 1500;
    try {
      const live = await Promise.race([
        this.session.callPhone<any>("sys.getStatus", {}, waitMs),
        new Promise<null>((r) => setTimeout(() => r(null), waitMs)),
      ]);
      if (live && typeof live === "object") {
        this.session.setPhoneStatus(live);
        this.ingestPhoneAirpodsSnapshot(live);
      }
    } catch {
      // keep cached phoneStatus on transient RPC error
    }
  }

  public getMirrorStatus(): MirrorStatusSnapshot {
    const now = Date.now();
    while (
      this.mirrorFrameTimestamps.length > 0 &&
      now - this.mirrorFrameTimestamps[0] > 3000
    ) {
      this.mirrorFrameTimestamps.shift();
    }
    const fps =
      this.mirrorFrameTimestamps.length >= 2
        ? Math.round(
            ((this.mirrorFrameTimestamps.length - 1) * 10000) /
              Math.max(
                200,
                this.mirrorFrameTimestamps[this.mirrorFrameTimestamps.length - 1] -
                  this.mirrorFrameTimestamps[0]
              )
          ) / 10
        : this.mirrorFrameTimestamps.length === 1 && now - this.mirrorLastFrameAtMs < 1500
          ? 1
          : 0;
    const ps = this.session.getPhoneStatus();
    return {
      running: this.mirrorRunning,
      starting: this.mirrorStartPending,
      mode: this.mirrorRunning ? this.mirrorMode : "stopped",
      codec: "webp",
      fps,
      seq: this.mirrorSeq,
      width: this.mirrorWidth,
      height: this.mirrorHeight,
      isSecureWindow: Boolean(this.mirrorFlags & ScreenFrameFlags.IS_SECURE_WINDOW),
      isScreenOff: Boolean(this.mirrorFlags & ScreenFrameFlags.IS_SCREEN_OFF),
      isScreenLocked:
        this.mirrorLastFrameAtMs > 0
          ? Boolean(this.mirrorFlags & ScreenFrameFlags.IS_SCREEN_LOCKED)
          : Boolean(ps?.screenLocked),
      foregroundPackage: ps?.foregroundPackage,
      foregroundAppLabel: ps?.foregroundAppLabel,
      lastFrameAtMs: this.mirrorLastFrameAtMs,
      clientCount: this.session.getMirrorViewerCount(),
      maxWidth: this.mirrorMaxWidth,
      quality: this.mirrorQuality,
      maxFps: this.mirrorMaxFps,
    };
  }

  private stopLegacyMirrorLoop(): void {
    if (this.mirrorLegacyPollTimer) {
      clearTimeout(this.mirrorLegacyPollTimer);
      this.mirrorLegacyPollTimer = null;
    }
    this.mirrorLegacyPollingInFlight = false;
  }

  private startLegacyMirrorLoop(): void {
    this.stopLegacyMirrorLoop();
    const generation = this.mirrorGeneration;
    const isCurrent = () => generation === this.mirrorGeneration && this.mirrorRunning && this.mirrorMode === "legacy_poll";
    const pollOnce = async () => {
      if (!isCurrent()) return;
      if (this.session.getActiveTier() === "DISCONNECTED") {
        this.mirrorLegacyPollTimer = setTimeout(pollOnce, 1000);
        (this.mirrorLegacyPollTimer as any).unref?.();
        return;
      }
      if (!this.mirrorLegacyPollingInFlight) {
        this.mirrorLegacyPollingInFlight = true;
        try {
          let streamId: string | undefined;
          try {
            const res = await this.session.callPhone<any>(
              "sys.control",
              { action: "take_screenshot" },
              6000
            );
            streamId = res?.streamId;
          } catch (err: any) {
            if (!isCurrent()) return;
            const msg = String(err?.message || "");
            if (msg.includes("ERR_SECURE_WINDOW_BLOCKED") || msg.includes("隐私保护")) {
              const seq = (this.mirrorSeq + 1) & 0xffff;
              const rawFrame = encodeScreenFrame({
                codec: ScreenFrameCodec.STATUS_ONLY,
                flags: ScreenFrameFlags.IS_SECURE_WINDOW,
                width: this.mirrorWidth,
                height: this.mirrorHeight,
                seq,
                timestampMs: Date.now(),
              });
              this.session.ingestScreenFrame(rawFrame.subarray(FRAME_HEADER_BYTES));
            } else if (msg.includes("Unknown RPC method")) {
              const obs = await this.session.callPhone<any>(
                "gui.observe",
                { includeScreenshot: true, somAnnotation: false },
                6000
              );
              if (!isCurrent()) return;
              if (obs?.screenWidth) this.mirrorWidth = obs.screenWidth;
              if (obs?.screenHeight) this.mirrorHeight = obs.screenHeight;
              streamId = obs?.screenshotStreamId;
            }
          }
          if (streamId && isCurrent()) {
            const comp = await this.session.waitForCompletedStream(streamId, 5000);
            if (comp && isCurrent() && fs.existsSync(comp.savedAbsPath)) {
              const imgBytes = new Uint8Array(fs.readFileSync(comp.savedAbsPath));
              try {
                fs.unlinkSync(comp.savedAbsPath);
              } catch {}
              const seq = (this.mirrorSeq + 1) & 0xffff;
              const ps = this.session.getPhoneStatus();
              const flags = ps?.screenLocked ? ScreenFrameFlags.IS_SCREEN_LOCKED : 0;
              const rawFrame = encodeScreenFrame(
                {
                  codec: ScreenFrameCodec.WEBP,
                  flags,
                  width: this.mirrorWidth,
                  height: this.mirrorHeight,
                  seq,
                  timestampMs: Date.now(),
                },
                imgBytes
              );
              this.session.ingestScreenFrame(rawFrame.subarray(FRAME_HEADER_BYTES));
            }
          }
        } catch {
          // ignore transient poll error
        } finally {
          if (generation === this.mirrorGeneration) this.mirrorLegacyPollingInFlight = false;
        }
      }
      if (!isCurrent()) return;
      const isBoosted = Date.now() < this.mirrorLegacyBoostUntilMs;
      const delayMs = isBoosted ? 180 : 520;
      this.mirrorLegacyPollTimer = setTimeout(pollOnce, delayMs);
      (this.mirrorLegacyPollTimer as any).unref?.();
    };
    this.mirrorLegacyBoostUntilMs = Date.now() + 3000;
    void pollOnce();
  }

  private queueMirrorOperation(operation: () => Promise<MirrorStatusSnapshot>): Promise<MirrorStatusSnapshot> {
    const result = this.mirrorLifecycleQueue.then(operation);
    // A failed RPC must not prevent a later stop/restart from running.
    this.mirrorLifecycleQueue = result.then(() => {}, () => {});
    return result;
  }

  public async startMirror(params: Record<string, any> = {}): Promise<MirrorStatusSnapshot> {
    if (this.mirrorDisposed) throw new Error("Daemon stopping; mirror start rejected");
    const generation = ++this.mirrorGeneration;
    this.mirrorStartPending = true;
    if (this.mirrorIdleStopTimer) {
      clearTimeout(this.mirrorIdleStopTimer);
      this.mirrorIdleStopTimer = null;
    }
    if (typeof params.maxWidth === "number" && Number.isFinite(params.maxWidth)) {
      this.mirrorMaxWidth = Math.max(360, Math.min(1080, Math.round(params.maxWidth)));
    }
    if (typeof params.quality === "number" && Number.isFinite(params.quality)) {
      this.mirrorQuality = Math.max(45, Math.min(90, Math.round(params.quality)));
    }
    if (typeof params.maxFps === "number" && Number.isFinite(params.maxFps)) {
      this.mirrorMaxFps = Math.max(2, Math.min(12, Math.round(params.maxFps)));
    }
    const autoWake =
      params.autoWake !== undefined
        ? Boolean(params.autoWake)
        : params.wakeScreen !== false;
    const request = {
      maxWidth: this.mirrorMaxWidth,
      quality: this.mirrorQuality,
      maxFps: this.mirrorMaxFps,
      autoWake,
      keepScreenOn: params.keepScreenOn !== false,
    };

    return this.queueMirrorOperation(async () => {
      if (generation !== this.mirrorGeneration || this.mirrorDisposed) return this.getMirrorStatus();

      try {
        const res = await this.session.callPhone<any>(
          "mirror.start",
          request,
          6000
        );
        // A stop queued while this RPC was pending owns the state now. It will also
        // send mirror.stop after this response, so the phone cannot remain capturing.
        if (generation !== this.mirrorGeneration || this.mirrorDisposed) return this.getMirrorStatus();
        if (res?.ok === false) throw new Error(res.error || "Phone rejected mirror start");
        this.stopLegacyMirrorLoop();
        this.mirrorRunning = true;
        this.mirrorMode = "native_stream";
        if (res?.screenWidth) this.mirrorWidth = Number(res.screenWidth);
        if (res?.screenHeight) this.mirrorHeight = Number(res.screenHeight);
      } catch (err: any) {
        if (generation !== this.mirrorGeneration || this.mirrorDisposed) return this.getMirrorStatus();
        const msg = String(err?.message || "");
        if (msg.includes("Unknown RPC method")) {
          // Seamless backward-compatible fallback for APK <= v1.4.9
          this.mirrorRunning = true;
          this.mirrorMode = "legacy_poll";
          if (autoWake) {
            const ps = this.session.getPhoneStatus();
            if (ps?.screenInteractive === false) {
              void this.session
                .callPhone("sys.control", { action: "unlock_screen", pattern: "", bounds: "" }, 4000)
                .catch(() => {});
            }
          }
          this.startLegacyMirrorLoop();
        } else {
          this.mirrorRunning = false;
          this.mirrorMode = "stopped";
          this.stopLegacyMirrorLoop();
          throw err;
        }
      } finally {
        if (generation === this.mirrorGeneration) this.mirrorStartPending = false;
      }

      const snap = this.getMirrorStatus();
      this.session.broadcastMirrorEvent({ type: "mirror_status", status: snap });
      return snap;
    });
  }

  public async stopMirror(): Promise<MirrorStatusSnapshot> {
    const hadActiveRequest = this.mirrorRunning || this.mirrorStartPending;
    ++this.mirrorGeneration;
    this.mirrorStartPending = false;
    this.mirrorRunning = false;
    this.mirrorMode = "stopped";
    this.stopLegacyMirrorLoop();
    if (this.mirrorIdleStopTimer) {
      clearTimeout(this.mirrorIdleStopTimer);
      this.mirrorIdleStopTimer = null;
    }
    return this.queueMirrorOperation(async () => {
      if (this.session.getActiveTier() !== "DISCONNECTED") {
        const res = await this.session.callPhone<any>("mirror.stop", {}, 3000);
        if (res?.ok === false) throw new Error(res.error || "Phone rejected mirror stop");
      } else if (hadActiveRequest) {
        throw new Error("Phone disconnected; mirror stop was not confirmed");
      }
      const snap = this.getMirrorStatus();
      this.session.broadcastMirrorEvent({ type: "mirror_status", status: snap });
      return snap;
    });
  }

  public async sendMirrorInput(params: Record<string, any>): Promise<any> {
    this.mirrorLegacyBoostUntilMs = Date.now() + 2800;
    const inputType = String(params.inputType || params.action || params.type || "tap").toLowerCase();

    if (inputType === "unlock" || inputType === "unlock_screen") {
      return await this.handleIpcAction("phone_device_control", {
        action: "unlock_screen",
        pattern: params.pattern,
        pin: params.pin,
        bounds: params.bounds,
        deadlineMs: params.deadlineMs,
      });
    }

    const mayRequireConfirmation = ["tap", "click", "long_press", "long_click"].includes(inputType);
    const deadlineMs = boundedControlDeadline(params.deadlineMs, mayRequireConfirmation ? 65000 : 5000);
    const callPhone = this.session.bindPhoneSession();
    if (this.mirrorMode !== "legacy_poll") {
      try {
        return await callPhone("mirror.input", { ...params, type: inputType, deadlineMs },
          Math.max(1, deadlineMs - Date.now()) + 1000);
      } catch (err: any) {
        if (!String(err?.message || "").includes("Unknown RPC method")) throw err;
      }
    }
    // Legacy input translations bypassed mirror authorization and could report
    // clipboard delivery as successful text entry. Require the guarded endpoint.
    return {
      ok: false,
      requiresApkUpdate: true,
      error: "ERR_PHONE_UPDATE_REQUIRED: Update the Android app before mirror input; no fallback input was sent",
    };
  }

  public async start(opts?: { startTransport?: boolean }): Promise<void> {
    if (opts?.startTransport !== false) {
      const macHostRoot = path.resolve(
        path.dirname(new URL(import.meta.url).pathname),
        "../.."
      );
      const btBin = path.join(macHostRoot, "bin", "oms-bt-bridge");
      const btSrc = path.join(macHostRoot, "bluetooth-bridge", "main.swift");
      const btOperationSrc = path.join(macHostRoot, "bluetooth-bridge", "AirPodsOperation.swift");
      const btNativeRequestSrc = path.join(macHostRoot, "bluetooth-bridge", "AirPodsNativeRequest.swift");
      const btStat = fs.existsSync(btBin) ? fs.statSync(btBin) : null;
      const srcStat = fs.existsSync(btSrc) ? fs.statSync(btSrc) : null;
      if (
        this.config.enableBluetoothBridge &&
        process.platform === "darwin" &&
        srcStat &&
        (!btStat || srcStat.mtimeMs > btStat.mtimeMs || fs.statSync(btOperationSrc).mtimeMs > btStat.mtimeMs || fs.statSync(btNativeRequestSrc).mtimeMs > btStat.mtimeMs)
      ) {
        try {
          fs.mkdirSync(path.dirname(btBin), { recursive: true });
          execFileSync("swiftc", [btOperationSrc, btNativeRequestSrc, btSrc, "-o", btBin], { timeout: 30000, stdio: "ignore" });
        } catch {
          // ignore if swiftc / Xcode CLT is unavailable
        }
      }
      await this.session.start({
        btSocketPath: this.config.enableBluetoothBridge ? this.btSocketPath : undefined,
        btBinaryPath: fs.existsSync(btBin) ? btBin : undefined,
        wanRelayWssUrl: this.config.wanRelayWssUrl,
        wanRelay: this.config.wanRelay,
      });
      this.applyStayAwakeState();
      this.syncAirpodsConfigToBridgeAndPhone();

      if (process.platform === "darwin" && process.env.OMS_DISABLE_OSASCRIPT !== "1") {
        const hudBin = path.join(macHostRoot, "bin", "oms-mac-hud");
        const hudSrc = path.join(macHostRoot, "mac-hud", "main.swift");
        const hudBinStat = fs.existsSync(hudBin) ? fs.statSync(hudBin) : null;
        const hudSrcStat = fs.existsSync(hudSrc) ? fs.statSync(hudSrc) : null;
        if (hudSrcStat && (!hudBinStat || hudSrcStat.mtimeMs > hudBinStat.mtimeMs)) {
          try {
            fs.mkdirSync(path.dirname(hudBin), { recursive: true });
            execFileSync(
              "swiftc",
              [
                hudSrc,
                "-framework",
                "Cocoa",
                "-framework",
                "PDFKit",
                "-framework",
                "Quartz",
                "-framework",
                "AVFoundation",
                "-framework",
                "WebKit",
                "-o",
                hudBin,
              ],
              { timeout: 35000, stdio: "ignore" }
            );
          } catch {
            // ignore if swiftc / Xcode CLT is unavailable
          }
        }
        this.hudServer = new MacHudServer({
          socketPath: this.hudSocketPath,
          binaryPath: fs.existsSync(hudBin) ? hudBin : undefined,
          autoSpawnBinary: true,
        });
        this.hudServer.on("clientConnected", () => this.syncMacHudState());
        this.hudServer.on("requestSync", () => this.syncMacHudState());
        this.hudServer.on("markProcessed", (id: string) => {
          this.inbox.markProcessed(id, true);
          this.syncMacHudState();
        });
        this.hudServer.on("pushWeChatToPhone", (payload: { articleId?: number; title?: string; markdown?: string }) => {
          void this.pushWeChatNoteToPhone({
            articleId: payload.articleId,
            title: payload.title,
            markdown: payload.markdown,
            queueIfOffline: true,
          });
        });
        await this.hudServer.start().catch(() => {});
      }

      if (
        this.config.enableWeChatBridge !== false &&
        process.env.OMS_DISABLE_WECHAT_WORKER !== "1" &&
        (this.config.enableWeChatBridge === true || process.env.OMS_DISABLE_OSASCRIPT !== "1")
      ) {
        this.wechat.start();
      }
    }

    if (fs.existsSync(this.ipcSocketPath)) {
      try {
        fs.unlinkSync(this.ipcSocketPath);
      } catch {
        // ignore
      }
    }

    await new Promise<void>((resolve, reject) => {
      this.ipcServer = net.createServer((socket) => {
        let buffer = "";
        socket.setEncoding("utf8");
        socket.on("error", () => {
          // Ignore client disconnect / EPIPE on local IPC socket
        });
        const safeWrite = (payload: string) => {
          if (!socket.destroyed && socket.writable) {
            try {
              socket.write(payload);
            } catch {
              // ignore broken pipe
            }
          }
        };
        socket.on("data", async (chunk: string) => {
          buffer += chunk;
          let idx: number;
          while ((idx = buffer.indexOf("\n")) !== -1) {
            const line = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 1);
            if (!line) continue;
            let reqId = "err";
            try {
              const req = JSON.parse(line) as {
                id: string;
                action: string;
                params?: Record<string, any>;
              };
              if (req?.id) reqId = req.id;
              const result = await this.handleIpcAction(req.action, req.params ?? {});
              safeWrite(JSON.stringify({ id: req.id, ok: true, result }) + "\n");
            } catch (err: any) {
              safeWrite(
                JSON.stringify({
                  id: reqId,
                  ok: false,
                  error: {
                    code: err?.code ?? -32000,
                    message: err?.message ?? String(err),
                  },
                }) + "\n"
              );
            }
          }
        });
      });

      this.ipcServer.once("error", reject);
      this.ipcServer.listen(this.ipcSocketPath, () => {
        try {
          fs.chmodSync(this.ipcSocketPath, 0o600);
        } catch {}
        resolve();
      });
    });

    this.writeSecureFileAtomic(
      path.join(this.homeDir, "run", "omsd.pid"),
      String(process.pid)
    );
  }

  /**
   * Handle all 12 MCP Tools & CLI commands over local Unix Socket IPC
   */
  public async handleIpcAction(
    action: string,
    params: Record<string, any>
  ): Promise<any> {
    switch (action) {
      case "daemon.pair": {
        const creds = { ephemeralToken: secureRandomHex(), expiresAtMs: Date.now() + 10 * 60 * 1000 };
        const ips = PhoneSessionManager.getLocalLanIps();
        const uri = buildPairingUri({
          v: 2,
          id: this.identity.deviceId,
          name: os.hostname(),
          ips,
          port: this.config.lanPort,
          btUuid: OMS_BLE_SERVICE_SHORT_UUID,
          pubKeyBase64Url: this.identity.publicKeyBase64Url,
          pinCode: "",
          ephemeralToken: creds.ephemeralToken,
          expiresAtMs: creds.expiresAtMs,
        });
        const secureUri = new URL(uri);
        secureUri.searchParams.delete("pin");
        const pairingUri = secureUri.toString();
        this.session.setActivePairingCredentials({ ...creds, pairingUri });
        return {
          pairingUri,
          pairingVersion: 2,
          lanIps: ips,
          lanPort: this.config.lanPort,
          wirelessInstallUrl: `http://${ips[0]}:${this.config.lanPort}/install`,
          apkDirectUrl: `http://${ips[0]}:${this.config.lanPort}/OneMoreStep.apk`,
          dashboardUrl: `http://127.0.0.1:${this.config.lanPort}/inbox`,
          expiresAtIso: new Date(creds.expiresAtMs).toISOString(),
        };
      }

      // Tool 10: phone_presence_status
      case "phone_presence_status": {
        if (params.refresh !== false) {
          await this.refreshPhoneStatusLive(Boolean(params.force));
        }
        const presenceSnap = this.session.presence.getSnapshot();
        return {
          presenceState: presenceSnap.state,
          bleConnected: presenceSnap.bleConnected,
          rssiEmaDbm: presenceSnap.rssiEma,
          activeTransportTier: this.session.getActiveTier(),
          connectionDiagnostics: this.session.getConnectionDiagnostics(),
          ...this.session.getSecurityStatus(),
          lastRttMs: this.session.getLastRttMs(),
          lanPort: this.config.lanPort,
          dashboardUrl: `http://127.0.0.1:${this.config.lanPort}/inbox`,
          trustedDevices: this.session.getTrustedDevices().map((d) => ({
            phoneId: d.phoneId,
            phoneModel: d.phoneModel,
            pairingVersion: d.pairingVersion ?? 1,
            lastSeenIso: new Date(d.lastSeenMs).toISOString(),
          })),
          airpodsStatus: this.getAirpodsStatusSnapshot(),
          wanRelayStatus: this.session.getWanRelayStatus(),
          stayAwakeStatus: this.getStayAwakeStatus(),
          phoneStatus: this.session.getPhoneStatus(),
          wechatStatus: await this.wechat.refreshHealth(Boolean(params.force)),
        };
      }

      case "wan.status": {
        return {
          wanRelay: this.session.getWanRelayStatus(),
          stayAwake: this.getStayAwakeStatus(),
        };
      }

      case "wan.configure": {
        const wanRelay = this.configureWanRelayHost(params);
        return {
          ok: true,
          wanRelay,
          stayAwake: this.getStayAwakeStatus(),
        };
      }

      case "wan.probe": {
        return await this.probeWanRelayHealth();
      }

      case "host.stay_awake": {
        if (typeof params.enabled === "boolean") {
          return this.setStayAwakeEnabled(params.enabled);
        }
        return this.getStayAwakeStatus();
      }

      case "wechat.status": {
        return await this.wechat.refreshHealth(true);
      }

      case "wechat.peek": {
        const articleId = params.articleId !== undefined ? Number(params.articleId) : undefined;
        return this.peekWeChatInHud(Number.isFinite(articleId) ? articleId : undefined);
      }

      case "wechat.sync_to_phone": {
        return await this.pushWeChatNoteToPhone({
          articleId: params.articleId !== undefined ? Number(params.articleId) : undefined,
          title: typeof params.title === "string" ? params.title : undefined,
          markdown: typeof params.markdown === "string" ? params.markdown : undefined,
          queueIfOffline: params.queueIfOffline !== false,
        });
      }

      case "edge.sync_now": {
        return await this.syncEdgeConfigAndCapsuleToPhone(true);
      }

      case "chisu.curate_url": {
        return await this.curateBrowserUrlToVaultAndContext(params as unknown as CurateBrowserUrlParams);
      }

      case "chisu.recent_curated": {
        return {
          ok: true,
          items: this.memoryMirror.getRecentCuratedContext(Number(params.limit) || 8),
          workingMemory: this.memoryMirror.loadWorkingMemory(),
        };
      }

      case "agent.sessions.list": {
        return this.agentHub.getWorkspaceCatalog(Number(params.limit) || 48);
      }

      case "agent.sessions.reconcile": {
        return { ok: true, ...this.agentHub.reconcileDesktopVisibility() };
      }

      case "agent.quotas": {
        return this.agentHub.getQuotasSnapshot();
      }

      case "agent.session.detail": {
        const backend = normalizeAgentBackend(params.backend);
        return this.agentHub.getSessionDetail(backend, String(params.sessionId || ""));
      }

      case "agent.session.start": {
        const started = this.agentHub.startSession(params as unknown as AgentSessionStartParams);
        return { ok: true, session: started };
      }

      case "agent.session.continue": {
        const resumed = this.agentHub.continueSession(params as unknown as AgentSessionContinueParams);
        return { ok: true, session: resumed };
      }

      case "agent.session.cancel": {
        const backend = normalizeAgentBackend(params.backend);
        return { ok: this.agentHub.cancelSession(backend, String(params.sessionId || "")) };
      }

      case "memory.center.get": {
        return {
          ok: true,
          snapshot: this.secretary.buildMemoryCenterSnapshot({
            workingMemoryItems: this.memoryMirror.loadWorkingMemory(),
            vaultRoot: this.memoryMirror.resolveVaultRoot(),
          }),
        };
      }

      case "memory.entry.upsert": {
        const entry = this.secretary.addOrUpdateMemoryEntry({
          id: typeof params.id === "string" ? params.id : undefined,
          text: String(params.text || ""),
          source: params.source === "auto" ? "auto" : "manual",
          originDetail: typeof params.originDetail === "string" ? params.originDetail : undefined,
        });
        return { ok: true, entry };
      }

      case "memory.entry.delete": {
        const target = String(params.text || params.id || "");
        const isWorking = params.category === "working" || String(params.id || "").startsWith("wm_");
        const deleted = isWorking
          ? this.memoryMirror.deleteWorkingMemoryItem(target)
          : this.secretary.deleteMemoryEntry(String(params.id || params.text || ""), true);
        return { ok: deleted };
      }

      case "mac.control.status": {
        return {
          ok: true,
          status: await this.macControl.getStatusSnapshotAsync(
            this.buildMacControlContext(),
            Boolean(params.refreshApps)
          ),
        };
      }

      case "mac.control.execute": {
        return await this.macControl.execute(
          params as unknown as MacControlExecuteParams,
          {
            ...this.buildMacControlContext(),
            activeTier: "LOCAL_IPC",
            requestConnectionId: "local-ipc",
          }
        );
      }

      case "mac.control.set_unlock_password": {
        const ok = this.macControl.setUnlockPassword(String(params.password || ""));
        return { ok, status: this.macControl.getStatusSnapshot(this.buildMacControlContext()) };
      }

      case "mac.control.clear_unlock_password": {
        const ok = this.macControl.clearUnlockPassword();
        return { ok, status: this.macControl.getStatusSnapshot(this.buildMacControlContext()) };
      }

      case "memory.search_vault": {
        return this.memoryMirror.searchContext(String(params.query || ""), Number(params.limit) || 10);
      }

      case "memory.read_vault_doc": {
        return this.memoryMirror.readVaultDocument(String(params.relPath || ""));
      }

      case "book.import": {
        const vaultRoot =
          typeof params.vaultRoot === "string" && params.vaultRoot.trim()
            ? params.vaultRoot.trim()
            : this.memoryMirror.resolveVaultRoot();
        const result = importEpubToVault({
          epubPath: String(params.epubPath || ""),
          vaultRoot,
          slug: typeof params.slug === "string" ? params.slug : undefined,
          dryRun: Boolean(params.dryRun),
        });
        if (result.ok && !result.dryRun) {
          this.memoryMirror.syncVaultIndex(true);
        }
        return result;
      }

      case "book.list": {
        const vaultRoot =
          typeof params.vaultRoot === "string" && params.vaultRoot.trim()
            ? params.vaultRoot.trim()
            : this.memoryMirror.resolveVaultRoot();
        return { ok: true, vaultRoot, books: listImportedBooks(vaultRoot) };
      }

      case "secretary.brief": {
        return {
          ok: true,
          brief: this.secretary.buildTodayBrief({
            vaultRoot: this.memoryMirror.resolveVaultRoot(),
            wechatDbPath: path.join(this.wechat.wechatHomeDir, "data", "wechat_context.db"),
            agentSessions: this.agentHub.listSessions(20),
          }),
        };
      }

      case "secretary.commitment.list": {
        return { ok: true, commitments: this.secretary.listCommitments(params.includeDone !== false) };
      }

      case "secretary.commitment.upsert": {
        const item = this.secretary.upsertCommitment({
          id: typeof params.id === "string" ? params.id : undefined,
          title: String(params.title || ""),
          dueHint: typeof params.dueHint === "string" ? params.dueHint : undefined,
          source: typeof params.source === "string" ? params.source : "cli",
          status:
            params.status === "done" || params.status === "snoozed" ? params.status : "open",
        });
        return { ok: true, item };
      }

      case "secretary.commitment.toggle": {
        const item = this.secretary.toggleCommitment(String(params.id || ""));
        return { ok: Boolean(item), item };
      }

      case "secretary.commitment.delete": {
        return { ok: this.secretary.deleteCommitment(String(params.id || "")) };
      }

      case "secretary.profile.get": {
        return { ok: true, items: this.secretary.getProfileItems() };
      }

      case "secretary.profile.save": {
        const items = this.secretary.saveProfileItems(Array.isArray(params.items) ? params.items : []);
        return { ok: true, items };
      }

      case "airpods.status": {
        if (params.refresh !== false) {
          this.session.getBluetoothBridge()?.requestAirpodsStatusRefresh();
          await this.refreshPhoneStatusLive(Boolean(params.force));
        }
        return this.getAirpodsStatusSnapshot();
      }

      case "airpods.switch_to_mac": {
        return await this.switchAirpodsToMac(
          params.targetMac ? String(params.targetMac) : undefined,
          params.reason ? String(params.reason) : "manual"
        );
      }

      case "airpods.switch_to_phone": {
        return await this.switchAirpodsToPhone(
          params.targetMac ? String(params.targetMac) : undefined,
          params.reason ? String(params.reason) : "manual"
        );
      }

      case "airpods.config": {
        if (
          typeof params.autoSwitchAirpods === "boolean" ||
          typeof params.pauseOtherOnAirpodsSwitch === "boolean" ||
          typeof params.preferredAirpodsMac === "string"
        ) {
          this.updateHostPreferences({
            autoSwitchAirpods: params.autoSwitchAirpods,
            pauseOtherOnAirpodsSwitch: params.pauseOtherOnAirpodsSwitch,
            preferredAirpodsMac: params.preferredAirpodsMac,
          });
        }
        return this.getAirpodsStatusSnapshot();
      }

      // Tool 1: phone_inbox_latest
      case "phone_inbox_latest": {
        const items = this.inbox.queryLatest({
          tag: (params.tag as "all" | IntentTag) ?? "all",
          limit: params.limit ?? 5,
          onlyUnprocessed: Boolean(params.onlyUnprocessed),
          markAsProcessed: Boolean(params.markAsProcessed),
          searchQuery: params.searchQuery ? String(params.searchQuery) : undefined,
        });
        return {
          count: items.length,
          items,
        };
      }

      case "inbox.get_item": {
        const item = this.inbox.getItem(String(params.itemId || ""));
        if (!item) {
          throw new Error(`Inbox item not found: ${params.itemId}`);
        }
        return item;
      }

      case "inbox.mark_processed": {
        const itemId = String(params.itemId || "");
        const processed = params.processed !== false;
        const ok = this.inbox.markProcessed(itemId, processed);
        this.syncMacHudState();
        return { ok, itemId, processed };
      }

      case "inbox.clear_processed": {
        const removedCount = this.inbox.clearProcessed();
        this.syncMacHudState();
        return { ok: true, removedCount };
      }

      case "inbox.peek": {
        const itemId = params.itemId ? String(params.itemId) : undefined;
        return this.peekInboxInHud(itemId);
      }

      case "inbox.transcribe_item": {
        const itemId = String(params.itemId || "");
        return await this.transcribeInboxItem(itemId);
      }

      case "daemon.prefs": {
        if (
          typeof params.autoCopyTextToClipboard === "boolean" ||
          typeof params.autoPreviewImageOnMac === "boolean" ||
          typeof params.autoSyncOtpToMac === "boolean" ||
          typeof params.autoSyncWeChatToPhone === "boolean" ||
          typeof params.autoSwitchAirpods === "boolean" ||
          typeof params.pauseOtherOnAirpodsSwitch === "boolean" ||
          typeof params.preferredAirpodsMac === "string" ||
          typeof params.preferredTtsVoice === "string" ||
          typeof params.unlockPattern === "string" ||
          typeof params.unlockPin === "string" ||
          typeof params.unlockBounds === "string"
        ) {
          return this.updateHostPreferences({
            autoCopyTextToClipboard: params.autoCopyTextToClipboard,
            autoPreviewImageOnMac: params.autoPreviewImageOnMac,
            autoSyncOtpToMac: params.autoSyncOtpToMac,
            autoSyncWeChatToPhone: params.autoSyncWeChatToPhone,
            autoSwitchAirpods: params.autoSwitchAirpods,
            pauseOtherOnAirpodsSwitch: params.pauseOtherOnAirpodsSwitch,
            preferredAirpodsMac: params.preferredAirpodsMac,
            preferredTtsVoice:
              typeof params.preferredTtsVoice === "string"
                ? this.resolveNeuralTtsVoice(params.preferredTtsVoice).alias
                : undefined,
            unlockPattern: params.unlockPattern,
            unlockPin: params.unlockPin,
            unlockBounds: params.unlockBounds,
          });
        }
        return this.getHostPreferences();
      }

      // Tool 2: phone_media_recent
      case "phone_media_recent": {
        const sinceMs = params.sinceMinutesAgo
          ? Date.now() - Number(params.sinceMinutesAgo) * 60 * 1000
          : 0;
        return await this.session.callPhone("storage.listRecentMedia", {
          mediaType: params.mediaType ?? "screenshot",
          limit: params.limit ?? 10,
          sinceMs,
        });
      }

      // Tool 3: phone_file_pull
      case "phone_file_pull": {
        const pullMeta = await this.session.callPhone<{
          streamId: string;
          fileName: string;
          totalBytes: number;
          sha256: string;
        }>("storage.pullFile", {
          sourceUriOrPath: params.sourceUriOrPath,
          compressImage: Boolean(params.compressImage),
        });

        const completed = await this.session.waitForCompletedStream(pullMeta.streamId);

        let finalPath = completed!.savedAbsPath;
        if (params.saveToDir) {
          fs.mkdirSync(params.saveToDir, { recursive: true });
          finalPath = path.join(params.saveToDir, path.basename(pullMeta.fileName));
          fs.copyFileSync(completed!.savedAbsPath, finalPath);
        }

        return {
          ok: true,
          localAbsPath: finalPath,
          fileName: pullMeta.fileName,
          sizeBytes: pullMeta.totalBytes,
          sha256: completed!.sha256Actual,
        };
      }

      // Tool 4: phone_file_push
      case "phone_file_push": {
        let bytes: Uint8Array;
        let fileName: string;
        let mimeType = "text/markdown";

        if (params.localFilePath && fs.existsSync(params.localFilePath)) {
          bytes = new Uint8Array(fs.readFileSync(params.localFilePath));
          fileName = path.basename(params.localFilePath);
          if (fileName.endsWith(".png")) mimeType = "image/png";
          else if (fileName.endsWith(".pdf")) mimeType = "application/pdf";
        } else if (params.markdownOrText) {
          bytes = new TextEncoder().encode(String(params.markdownOrText));
          fileName = `${(params.title || "ai_note").replace(/\s+/g, "_")}.md`;
        } else {
          throw new Error("Either localFilePath or markdownOrText is required");
        }

        const pushed = await this.session.pushStreamToPhone({
          fileName,
          mimeType,
          data: bytes,
          title: params.title || fileName,
          intentTag: (params.intentTag as IntentTag) || undefined,
          enableCommuteTts: Boolean(params.enableCommuteTts),
        });

        return {
          ok: true,
          streamId: pushed.streamId,
          fileName,
          bytesSent: bytes.byteLength,
          sha256: pushed.sha256,
        };
      }

      // Tool 5: phone_clipboard_sync
      case "phone_clipboard_sync": {
        if (params.action === "set_phone_clipboard") {
          return await this.session.callPhone("clipboard.set", {
            text: params.textToSet ?? "",
            showToast: true,
          });
        } else {
          const clipRes = await this.session.callPhone<Record<string, any>>("clipboard.get", {
            includeRecentOtp: Boolean(params.includeRecentOtp),
          });
          if (params.includeRecentOtp && !clipRes?.recentOtp) {
            const latestOtp = this.inbox.queryLatest({ tag: "sms_otp", limit: 1 })[0];
            if (
              latestOtp &&
              latestOtp.otpCode &&
              Math.abs(Date.now() - (latestOtp.createdAtMs || 0)) <= 5 * 60 * 1000
            ) {
              return {
                ...clipRes,
                recentOtp: {
                  code: latestOtp.otpCode,
                  sender: latestOtp.otpSender,
                  fullText: latestOtp.textContent,
                  createdAtMs: latestOtp.createdAtMs,
                },
              };
            }
          }
          return clipRes;
        }
      }

      // Tool 6: phone_alarm_set
      case "phone_alarm_set": {
        return await this.session.callPhone("alarm.set", {
          hour: Number(params.hour),
          minute: Number(params.minute),
          label: params.label ?? "OneMoreStep AI 提醒",
          days: params.daysOfWeek,
          vibrate: params.vibrate ?? true,
        });
      }

      // Tool 7: phone_calendar_manage
      case "phone_calendar_manage": {
        const calAction = params.action ?? "list_events";
        if (calAction === "list_events") {
          const startMs = params.startIso ? Date.parse(params.startIso) : Date.now();
          const endMs = params.endIso
            ? Date.parse(params.endIso)
            : startMs + 3 * 24 * 3600 * 1000;
          return await this.session.callPhone("calendar.list", { startMs, endMs });
        } else if (calAction === "add_event") {
          const startMs = params.startIso
            ? Date.parse(params.startIso)
            : Date.now() + 3600 * 1000;
          const endMs = params.endIso ? Date.parse(params.endIso) : startMs + 3600 * 1000;
          return await this.session.callPhone("calendar.insert", {
            title: params.title ?? "AI 日程安排",
            description: params.description ?? "",
            location: params.location ?? "",
            startMs,
            endMs,
            reminderMinutes: params.reminderMinutesBefore ?? 15,
          });
        } else if (calAction === "delete_event") {
          return await this.session.callPhone("calendar.delete", {
            eventId: Number(params.eventId),
          });
        }
        throw new Error(`Unsupported calendar action: ${calAction}`);
      }

      // Tool 8: phone_notify (with Presence-Aware Routing & optional DingTalk CC)
      case "phone_notify": {
        const title = params.title ?? "AI Agent 提醒";
        const body = params.body ?? "";
        const level = params.level ?? "normal";
        const presenceState = this.session.presence.getSnapshot().state;

        if (presenceState === "AT_DESK" && level !== "silent") {
          this.showMacDesktopNotification(title, body);
        }

        if (params.syncDingtalk && this.dingtalk.isEnabled) {
          await this.dingtalk.sendMarkdownCard(title, body);
        }

        // If user is at desk and notification is normal, store silently in phone stream without buzzing phone on desk
        const effectivePhoneLevel =
          presenceState === "AT_DESK" && level === "normal" ? "silent" : level;

        const ttsSpeak = Boolean(params.ttsSpeak);
        const audioPolicy = OmsDaemon.resolveSpeechAudioPolicy(
          params,
          effectivePhoneLevel === "urgent" ? "force_speaker_alarm" : "alarm_respect_headset"
        );
        const useAlarmStream = audioPolicy !== "media_commute";
        const forceSpeakerAlarmStream = audioPolicy === "force_speaker_alarm";
        const repeatCount =
          typeof params.repeat === "number" && Number.isFinite(params.repeat)
            ? Math.max(1, Math.min(20, Math.round(params.repeat)))
            : 1;
        let neuralTts: Awaited<ReturnType<typeof this.synthesizeNeuralTts>> = null;
        let ttsFallbackReason: TtsFallbackReason | undefined;
        if (ttsSpeak) {
          this.suppressPhoneAirpodsClaimUntilMs = Date.now() + Math.max(12000, repeatCount * 4500);
          const speakContent = [title, body].filter(Boolean).join("。");
          neuralTts = await this.synthesizeNeuralTts(speakContent, {
            voice: params.voice ? String(params.voice) : undefined,
            onFallback: (reason) => { ttsFallbackReason = reason; },
          });
        }

        const res = await this.session.callPhone<any>("notify.send", {
          title,
          body,
          level: effectivePhoneLevel,
          ttsSpeak,
          audioPolicy,
          useAlarmStream,
          forceSpeakerAlarmStream,
          audioBase64: neuralTts?.audioBase64,
          voiceId: neuralTts?.voiceId,
          repeatCount,
        });
        return {
          ...res,
          ...(neuralTts
            ? {
                ttsEngine: "edge_neural",
                voiceId: neuralTts.voiceId,
                voiceLabel: neuralTts.voiceLabel,
                cached: neuralTts.cached,
              }
            : ttsSpeak ? { ttsEngine: "android_local", ttsFallbackReason } : {}),
        };
      }

      // Tool 9: phone_request_approval (Blocking Human-in-the-Loop Remote Approval)
      case "phone_request_approval": {
        const approvalId = `apr_${generateUuidV7()}`;
        const timeoutSec = approvalTimeoutSec(params.timeoutSec ?? 1800);
        const options = params.options ?? ["批准继续", "拒绝并中止"];
        if (!Array.isArray(options) || options.length < 2 || options.length > 16
            || options.some((o: unknown) => typeof o !== "string" || !o.trim()) || new Set(options).size !== options.length) {
          throw new Error("Approval requires 2 to 16 distinct non-empty options; the first option approves");
        }
        const expiresAtMs = Date.now() + timeoutSec * 1000;
        for (const [id, settled] of this.settledApprovals) {
          if (settled.expiresAtMs < Date.now()) this.settledApprovals.delete(id);
        }
        // Register before dispatch: a fast phone response must not be lost.
        return await new Promise<ApprovalRespondParams>((resolve, reject) => {
          const timer = setTimeout(() => {
            this.pendingApprovals.delete(approvalId);
            const err = new Error(`Remote approval timed out after ${timeoutSec}s`) as Error & { code?: number };
            err.code = OmsErrorCode.ERR_APPROVAL_TIMEOUT;
            reject(err);
          }, timeoutSec * 1000);
          this.pendingApprovals.set(approvalId, { resolve, reject, timer, options, expiresAtMs });
          this.session.callPhone("notify.requestApproval", {
            approvalId, title: params.title ?? "需要远程确认", summary: params.summary ?? "",
            contextText: params.diffOrContext ?? "", options, timeoutSec, expiresAtMs,
          }).catch((err) => {
            if (!this.pendingApprovals.has(approvalId)) return;
            clearTimeout(timer); this.pendingApprovals.delete(approvalId); reject(err);
          });
        });
      }

      // Tool 11: phone_gui_observe
      case "phone_gui_observe": {
        const obs = await this.session.callPhone<{
          observationId: string;
          packageName: string;
          activityName: string;
          isSecureWindow: boolean;
          screenWidth: number;
          screenHeight: number;
          nodes: any[];
          screenshotStreamId?: string;
        }>("gui.observe", {
          includeScreenshot: Boolean(params.includeScreenshot),
          somAnnotation: params.somAnnotation ?? true,
        });

        let screenshotLocalAbsPath: string | undefined;
        if (obs.screenshotStreamId) {
          const comp = await this.session.waitForCompletedStream(obs.screenshotStreamId);
          if (comp) {
            screenshotLocalAbsPath = comp.savedAbsPath;
          }
        }

        return {
          ...obs,
          screenshotLocalAbsPath,
        };
      }

      // Tool 12: phone_gui_action
      case "phone_gui_action": {
        const normalizedAction =
          params.action === "input" ? "input_text" : params.action;
        return await this.session.callPhone("gui.act", {
          action: normalizedAction,
          targetNodeId: params.targetNodeId,
          observationId: params.observationId,
          deadlineMs: Date.now() + 60000,
          x: params.x,
          y: params.y,
          text: params.text,
          requireUserConfirm: Boolean(params.requireUserConfirm),
        }, 65000);
      }

      // OTA APK Update Helper
      case "phone_ota_update": {
        const ips = PhoneSessionManager.getLocalLanIps();
        const meta = this.session.getApkMetadata();
        const lanUrl = `http://${ips[0]}:${this.config.lanPort}/OneMoreStep.apk?t=${Date.now()}`;
        return await this.session.callPhone("sys.otaUpdate", {
          ip: ips[0],
          port: this.config.lanPort,
          url: lanUrl,
          githubDownloadUrl: meta.githubDownloadUrl,
        }, 150000);
      }

      // Real-Time Screen Mirroring Actions
      case "phone_mirror_start": {
        return await this.startMirror(params);
      }
      case "phone_mirror_stop": {
        return await this.stopMirror();
      }
      case "phone_mirror_status": {
        return this.getMirrorStatus();
      }
      case "phone_mirror_input": {
        return await this.sendMirrorInput(params);
      }

      // Tool 13: phone_device_control (Rich Status & Remote Device Operations)
      case "phone_device_control": {
        const subAction = String(params.action || "status").toLowerCase();
        if (subAction === "status" || subAction === "refresh") {
          await this.refreshPhoneStatusLive(true);
          return await this.handleIpcAction("phone_presence_status", { refresh: false });
        }

        if (subAction === "take_screenshot") {
          let streamId: string | undefined;
          let fileName = `screen_${Date.now()}.webp`;
          let mimeType = "image/webp";
          let totalBytes = 0;
          let sha256 = "";

          try {
            const res = await this.session.callPhone<any>("sys.control", {
              action: "take_screenshot",
            });
            streamId = res?.streamId;
            fileName = res?.fileName || fileName;
            mimeType = res?.mimeType || mimeType;
            totalBytes = res?.totalBytes || 0;
            sha256 = res?.sha256 || "";
          } catch (err: any) {
            if (String(err?.message || "").includes("Unknown RPC method")) {
              const obs = await this.session.callPhone<any>("gui.observe", {
                includeScreenshot: true,
                somAnnotation: false,
              });
              streamId = obs?.screenshotStreamId;
            } else {
              throw err;
            }
          }

          if (!streamId) {
            throw new Error("手机端未能返回截屏数据流");
          }

          const completed = await this.session.waitForCompletedStream(streamId);

          let inboxItemId: string | undefined;
          let savedAbsPath = completed!.savedAbsPath;
          if (params.saveToInbox !== false) {
            const stored = this.inbox.storeSubmittedItem(
              {
                clientItemId: generateUuidV7(),
                intentTag: (params.intentTag as IntentTag) || "default_inbox",
                title: params.title || "📸 Mac 远程抓取手机截屏",
                textContent: params.textContent || undefined,
                createdAtMs: Date.now(),
                attachments: [
                  {
                    streamId,
                    fileName: completed!.header.fileName || fileName,
                    mimeType: completed!.header.mimeType || mimeType,
                    totalBytes: totalBytes || completed!.header.totalBytes,
                    sha256: sha256 || completed!.sha256Actual,
                  },
                ],
              },
              (id) => this.session.getCompletedStream(id)
            );
            inboxItemId = stored.itemId;
            if (stored.files[0]?.absPath) {
              savedAbsPath = stored.files[0].absPath;
            }
            this.syncMacHudState();
          }

          return {
            ok: true,
            action: "take_screenshot",
            localAbsPath: savedAbsPath,
            inboxItemId,
          };
        }

        if (subAction === "unlock_screen" || subAction === "unlock") {
          const deadlineMs = boundedControlDeadline(params.deadlineMs, 25000);
          const callPhone = this.session.bindPhoneSession();
          const rawPattern = typeof params.pattern === "string" && params.pattern.trim() ? params.pattern.trim() : undefined;
          const rawPin = typeof params.pin === "string" && params.pin.trim() ? params.pin.trim() : undefined;
          const rawBounds = typeof params.bounds === "string" && params.bounds.trim() ? params.bounds.trim() : undefined;
          if (params.savePattern && rawPattern) this.updateHostPreferences({ unlockPattern: rawPattern });
          if (params.savePin && rawPin) this.updateHostPreferences({ unlockPin: rawPin });
          if ((params.saveBounds || params.savePattern) && rawBounds) this.updateHostPreferences({ unlockBounds: rawBounds });

          try {
            // Android owns wake/unlock sequencing under one lock and one deadline.
            // Never retry credentials or navigate a legacy phone after a failed attempt.
            const res = await callPhone<any>("sys.control", {
              ...params,
              action: "unlock_screen",
              pattern: rawPattern || this.config.unlockPattern || "",
              pin: rawPin || this.config.unlockPin || "",
              bounds: rawBounds || this.config.unlockBounds || "175,1516,1045,2386",
              deadlineMs,
            }, Math.max(1, deadlineMs - Date.now()));
            if (res?.status) this.session.setPhoneStatus(res.status);
            return {
              ...res,
              savedPatternToMacConfig: Boolean(params.savePattern && rawPattern),
              savedBoundsToMacConfig: Boolean((params.saveBounds || params.savePattern) && rawBounds),
            };
          } catch (err: any) {
            if (!String(err?.message || "").includes("Unsupported sys.control action")) throw err;
            return {
              ok: false, action: "unlock_screen", unlocked: false, requiresApkUpdate: true,
              error: "ERR_PHONE_UPDATE_REQUIRED: Update the Android app before remote unlock; no fallback gestures were sent",
            };
          }
        }

        if (subAction === "contacts" || subAction === "contact") {
          const op = String(params.op || "list").toLowerCase();
          this.config.contacts = this.config.contacts || {};
          if (op === "set" || op === "add") {
            const name = String(params.name || "").trim();
            const num = String(params.number || "").replace(/[^\d+*#,]/g, "");
            if (!name || !num) {
              throw new Error("Usage: oms contacts set <name> <number> [--sim 1|2] [--note <text>]");
            }
            const sim = params.sim ? String(params.sim).trim() : undefined;
            const note = params.note ? String(params.note).trim() : undefined;
            this.config.contacts[name] = { number: num, ...(sim ? { sim } : {}), ...(note ? { note } : {}) };
            this.saveConfig();
          } else if (op === "remove" || op === "delete" || op === "rm") {
            const name = String(params.name || "").trim();
            delete this.config.contacts[name];
            this.saveConfig();
          }
          return {
            ok: true,
            contacts: this.config.contacts,
            preferredSimSlot: this.config.preferredSimSlot || "1",
          };
        }

        if (subAction === "call_phone" || subAction === "call" || subAction === "dial") {
          const dialOnly = subAction === "dial" || Boolean(params.dialOnly);
          const sayText = dialOnly
            ? ""
            : String(params.say ?? params.sayText ?? params.ttsText ?? "").trim();
          const macSayText = dialOnly
            ? ""
            : String(params.macSay ?? params.macSayText ?? "").trim();
          const aiCallText = dialOnly
            ? ""
            : String(params.aiCall ?? params.aiCallText ?? "").trim();
          const hasAnyPostAnswerTask = Boolean(sayText || macSayText || aiCallText || params.waitForAnswer);
          const maxBudgetMs = hasAnyPostAnswerTask ? 90000 : 25000;
          // Validate caller deadline upfront before any synthesis or session work
          const deadlineMs = boundedControlDeadline(params.deadlineMs, maxBudgetMs);
          const rawNum = String(params.number || params.phoneNumber || params.text || "").trim();
          const { cleanNumber, simSlot, matchedContact } = this.resolveContactTarget(
            rawNum,
            params.simSlot ?? params.sim
          );
          if (!cleanNumber) {
            throw new Error(
              `未找到联系人或有效电话号码 "${rawNum}"；可用 oms contacts set <姓名> <号码> [--sim 1|2] 保存常用联系人，或直接传入号码`
            );
          }
          const callPhone = this.session.bindPhoneSession();
          const stopEpoch = this.phoneEmergencyStopEpoch ?? 0;

          let callTtsMeta: Awaited<ReturnType<typeof this.synthesizeNeuralTts>> = null;
          let callTtsFallbackReason: TtsFallbackReason | undefined;
          const repeatCount =
            typeof params.repeat === "number" && Number.isFinite(params.repeat)
              ? Math.max(1, Math.min(10, Math.round(params.repeat)))
              : typeof params.repeatCount === "number" && Number.isFinite(params.repeatCount)
                ? Math.max(1, Math.min(10, Math.round(params.repeatCount)))
                : 1;

          const ttsSourceText = sayText || macSayText;
          if (ttsSourceText) {
            if (params.saveVoice && typeof params.voice === "string" && params.voice.trim()) {
              const resolved = this.resolveNeuralTtsVoice(params.voice);
              this.updateHostPreferences({ preferredTtsVoice: resolved.alias });
            }
            this.suppressPhoneAirpodsClaimUntilMs = Date.now() + 90000;
            callTtsMeta = await this.synthesizeNeuralTts(ttsSourceText, {
              voice: params.voice ? String(params.voice) : undefined,
              onFallback: (reason) => { callTtsFallbackReason = reason; },
            });
          }

          boundedControlDeadline(deadlineMs, maxBudgetMs);
          if (macSayText) {
            callPhone.assertCurrent();
            if ((this.phoneEmergencyStopEpoch ?? 0) !== stopEpoch) {
              throw new Error("ERR_USER_INTERRUPTED: Phone emergency stop");
            }
            const btBridge =
              typeof this.session.getBluetoothBridge === "function"
                ? this.session.getBluetoothBridge()
                : null;
            if (btBridge) {
              const preflightOutput = await btBridge.queryAudioOutput(
                Math.max(1, Math.min(1000, deadlineMs - Date.now()))
              );
              callPhone.assertCurrent();
              if ((this.phoneEmergencyStopEpoch ?? 0) !== stopEpoch) {
                throw new Error("ERR_USER_INTERRUPTED: Phone emergency stop");
              }
              if (
                preflightOutput?.ok !== true ||
                preflightOutput.builtInSpeaker !== true ||
                typeof preflightOutput.uid !== "string" ||
                !preflightOutput.uid
              ) {
                throw new Error(
                  "ERR_MAC_SPEAKER_ROUTE: 拨号前检测到 Mac 当前输出不是内置扬声器（如连接了耳机），已中止外放拨号；请在系统声音设置中选择内置扬声器或改用 --say"
                );
              }
            }
          }
          const speakerEnabled =
            params.speaker !== undefined
              ? Boolean(params.speaker)
              : Boolean(sayText || macSayText);
          try {
            // A single session-bound request lets Android abort before dialing when
            // unlock fails, the emergency stop is pressed, or authorization expires.
            const res = await callPhone<any>("sys.control", {
              action: "call_phone", number: cleanNumber,
              dialOnly,
              simSlot,
              speaker: speakerEnabled,
              pattern: typeof params.pattern === "string" ? params.pattern : this.config.unlockPattern,
              pin: typeof params.pin === "string" ? params.pin : this.config.unlockPin,
              bounds: typeof params.bounds === "string" ? params.bounds : this.config.unlockBounds || "175,1516,1045,2386",
              deadlineMs,
              ...(hasAnyPostAnswerTask
                ? {
                    ...(sayText ? { sayText } : {}),
                    ...(aiCallText ? { aiCallText } : {}),
                    ...(macSayText && !sayText && !aiCallText ? { waitForAnswer: true } : {}),
                    repeatCount,
                    hangupAfter: Boolean(params.hangupAfter ?? params.autoHangup),
                    waitAnswerSec:
                      typeof params.waitAnswerSec === "number" && Number.isFinite(params.waitAnswerSec)
                        ? Math.max(5, Math.min(65, Math.round(params.waitAnswerSec)))
                        : 35,
                    postAnswerDelayMs:
                      typeof params.postAnswerDelayMs === "number" && Number.isFinite(params.postAnswerDelayMs)
                        ? Math.max(150, Math.min(4000, Math.round(params.postAnswerDelayMs)))
                        : 650,
                    useAlarmStream: params.useAlarmStream !== false,
                    ...(sayText && callTtsMeta
                      ? {
                          audioBase64: callTtsMeta.audioBase64,
                          voiceId: callTtsMeta.voiceId,
                          voiceLabel: callTtsMeta.voiceLabel,
                        }
                      : {}),
                  }
                : {}),
            }, Math.max(1, deadlineMs - Date.now()));
            if (res?.status) this.session.setPhoneStatus(res.status);
            if (macSayText && res && typeof res === "object" && res.callAnswered) {
              const postDelay =
                typeof params.postAnswerDelayMs === "number" && Number.isFinite(params.postAnswerDelayMs)
                  ? Math.max(150, Math.min(4000, Math.round(params.postAnswerDelayMs)))
                  : 650;
              const continuation = new CallContinuation(deadlineMs, () => {
                callPhone.assertCurrent();
                if ((this.phoneEmergencyStopEpoch ?? 0) !== stopEpoch) throw new Error("ERR_USER_INTERRUPTED: Phone emergency stop");
              });
              try {
                await continuation.pause(postDelay);
                const playedOnMac = callTtsMeta
                  ? await this.playNeuralAudioOnMacSpeaker(callTtsMeta.audioBase64, repeatCount, continuation)
                  : await this.playFallbackSayOnMacSpeaker(macSayText, repeatCount, continuation);
                continuation.check();
                let hungUpAfter = false;
                if (Boolean(params.hangupAfter ?? params.autoHangup) && playedOnMac) {
                  try {
                    const hRes = await callPhone<any>("sys.control", { action: "hangup_call", deadlineMs }, Math.max(1, Math.min(10000, deadlineMs - Date.now())));
                    continuation.check();
                    hungUpAfter = Boolean(hRes?.hungUp);
                  } catch { continuation.check(); }
                }
                return {
                  ...res,
                  ...(matchedContact ? { matchedContact } : {}),
                  spoken: playedOnMac,
                  spokenText: macSayText,
                  ttsEngine: playedOnMac
                    ? callTtsMeta
                      ? "mac_speaker_edge_neural"
                      : "mac_speaker_system_say"
                    : "mac_speaker_failed",
                  ...(callTtsFallbackReason ? { ttsFallbackReason: callTtsFallbackReason } : {}),
                  ...(callTtsMeta
                    ? {
                        voiceId: callTtsMeta.voiceId,
                        voiceLabel: callTtsMeta.voiceLabel,
                        cached: callTtsMeta.cached,
                        audioBytes: callTtsMeta.bytes,
                      }
                    : {}),
                  ...(Boolean(params.hangupAfter ?? params.autoHangup) ? { hungUpAfter } : {}),
                };
              } finally { continuation.dispose(); }
            }
            if (sayText && res && typeof res === "object") {
              if (callTtsMeta) {
                return {
                  ...res,
                  ...(matchedContact ? { matchedContact } : {}),
                  ttsEngine: "edge_neural",
                  voiceId: callTtsMeta.voiceId,
                  voiceLabel: callTtsMeta.voiceLabel,
                  cached: callTtsMeta.cached,
                  audioBytes: callTtsMeta.bytes,
                };
              }
              if (callTtsFallbackReason) {
                return {
                  ...res,
                  ...(matchedContact ? { matchedContact } : {}),
                  ttsEngine: "android_local",
                  ttsFallbackReason: callTtsFallbackReason,
                };
              }
            }
            return matchedContact && res && typeof res === "object" ? { ...res, matchedContact } : res;
          } catch (err: any) {
            if (!String(err?.message || "").includes("Unsupported sys.control action")) throw err;
            return {
              ok: false, action: "call_phone", callPlaced: false, requiresApkUpdate: true,
              error: "ERR_PHONE_UPDATE_REQUIRED: Update the Android app before remote dialing; no fallback gestures were sent",
            };
          }
        }

        if (subAction === "hangup_call" || subAction === "hangup" || subAction === "end_call") {
          try {
            const res = await this.session.callPhone<any>(
              "sys.control",
              { action: "hangup_call" },
              15000
            );
            if (res?.status) this.session.setPhoneStatus(res.status);
            return res;
          } catch (err: any) {
            if (!String(err?.message || "").includes("Unsupported sys.control action")) {
              throw err;
            }
            return {
              ok: false,
              action: "hangup_call",
              hungUp: false,
              error: "ERR_PHONE_UPDATE_REQUIRED: Phone cannot verify safe hangup; update the Android app or end the call on the phone",
            };
          }
        }

        if (subAction === "tts_voices" || subAction === "voices") {
          if (typeof params.voice === "string" && params.voice.trim()) {
            const resolved = this.resolveNeuralTtsVoice(params.voice);
            this.updateHostPreferences({ preferredTtsVoice: resolved.alias });
          }
          const current = this.resolveNeuralTtsVoice(this.config.preferredTtsVoice);
          return {
            ok: true,
            currentVoice: current,
            neuralTtsRuntimeInstalled: fs.existsSync(neuralTtsPython(this.homeDir)),
            neuralTtsSetupCommand: "bun run setup:tts",
            voices: Object.entries(NEURAL_TTS_VOICE_CATALOG).map(([alias, v]) => ({
              alias,
              voiceId: v.id,
              label: v.label,
              active: alias === current.alias,
            })),
          };
        }

        let enrichedParams: Record<string, any> = params;
        let neuralTtsMeta: Awaited<ReturnType<typeof this.synthesizeNeuralTts>> = null;
        let ttsFallbackReason: TtsFallbackReason | undefined;
        if (subAction === "find_phone" || subAction === "tts_speak") {
          if (params.saveVoice && typeof params.voice === "string" && params.voice.trim()) {
            const resolved = this.resolveNeuralTtsVoice(params.voice);
            this.updateHostPreferences({ preferredTtsVoice: resolved.alias });
          }
          const speakText =
            subAction === "find_phone"
              ? String(params.text || "正在寻找手机，我在这里！请查看青简咫尺。").trim()
              : String(params.text || "").trim();
          const defaultRepeat = subAction === "find_phone" ? 2 : 1;
          const repeatCount =
            typeof params.repeat === "number" && Number.isFinite(params.repeat)
              ? Math.max(1, Math.min(20, Math.round(params.repeat)))
              : typeof params.repeatCount === "number" && Number.isFinite(params.repeatCount)
                ? Math.max(1, Math.min(20, Math.round(params.repeatCount)))
                : defaultRepeat;

          if (speakText) {
            this.suppressPhoneAirpodsClaimUntilMs = Date.now() + Math.max(12000, repeatCount * 4500);
            neuralTtsMeta = await this.synthesizeNeuralTts(speakText, {
              voice: params.voice ? String(params.voice) : undefined,
              onFallback: (reason) => { ttsFallbackReason = reason; },
            });
          }
          const audioPolicy = OmsDaemon.resolveSpeechAudioPolicy(
            params,
            subAction === "find_phone" ? "force_speaker_alarm" : "alarm_respect_headset"
          );
          const useAlarmStream = audioPolicy !== "media_commute";
          const forceSpeakerAlarmStream = audioPolicy === "force_speaker_alarm";
          enrichedParams = {
            ...params,
            text: speakText,
            audioPolicy,
            useAlarmStream,
            forceSpeakerAlarmStream,
            repeatCount,
            ...(neuralTtsMeta
              ? {
                  audioBase64: neuralTtsMeta.audioBase64,
                  voiceId: neuralTtsMeta.voiceId,
                  voiceLabel: neuralTtsMeta.voiceLabel,
                }
              : {}),
          };
        }

        try {
          const res = await this.session.callPhone<any>(
            "sys.control",
            subAction === "system_action"
              ? { ...enrichedParams, deadlineMs: Date.now() + 60000 }
              : enrichedParams,
            subAction === "system_action" ? 65000 : 15000
          );
          if (res?.status) {
            this.session.setPhoneStatus(res.status);
          } else {
            void this.refreshPhoneStatusLive(true);
          }
          if (neuralTtsMeta && res && typeof res === "object") {
            return {
              ...res,
              ttsEngine: "edge_neural",
              voiceId: neuralTtsMeta.voiceId,
              voiceLabel: neuralTtsMeta.voiceLabel,
              cached: neuralTtsMeta.cached,
              audioBytes: neuralTtsMeta.bytes,
              repeatCount: enrichedParams.repeatCount,
            };
          }
          if (ttsFallbackReason && res && typeof res === "object") {
            return { ...res, ttsEngine: "android_local", ttsFallbackReason };
          }
          return res;
        } catch (err: any) {
          const msg = String(err?.message || "");
          if (!msg.includes("Unknown RPC method")) {
            throw err;
          }
          // Backward compatibility fallback for APK < v1.3.3
          if (subAction === "find_phone") {
            const text = String(enrichedParams.text || "正在寻找手机，我在这里！");
            const fallback = await this.session.callPhone<any>("notify.send", {
              title: "🔔 Mac 正在寻找此手机",
              body: text,
              level: "urgent",
              ttsSpeak: true,
              audioPolicy: "force_speaker_alarm",
              useAlarmStream: true,
              forceSpeakerAlarmStream: true,
              audioBase64: neuralTtsMeta?.audioBase64,
              repeatCount: enrichedParams.repeatCount,
            });
            return { ...fallback, ...(ttsFallbackReason ? { ttsEngine: "android_local", ttsFallbackReason } : {}) };
          }
          if (subAction === "tts_speak") {
            const text = String(enrichedParams.text || "");
            const useAlarm = enrichedParams.useAlarmStream !== false;
            const forceSpeaker = Boolean(enrichedParams.forceSpeakerAlarmStream);
            const fallback = await this.session.callPhone<any>("notify.send", {
              title: "🗣️ 语音播报",
              body: text,
              level: "silent",
              ttsSpeak: true,
              audioPolicy: enrichedParams.audioPolicy,
              useAlarmStream: useAlarm,
              forceSpeakerAlarmStream: forceSpeaker,
              audioBase64: neuralTtsMeta?.audioBase64,
              repeatCount: enrichedParams.repeatCount,
            });
            return { ...fallback, ...(ttsFallbackReason ? { ttsEngine: "android_local", ttsFallbackReason } : {}) };
          }
          if (subAction === "open_url") {
            return await this.session.callPhone("gui.act", {
              action: "open_url",
              text: String(params.url || ""),
            });
          }
          if (subAction === "launch_app") {
            const aliasMap: Record<string, string> = {
              wechat: "com.tencent.mm",
              微信: "com.tencent.mm",
              lark: "com.ss.android.lark",
              feishu: "com.ss.android.lark",
              飞书: "com.ss.android.lark",
              dingtalk: "com.alibaba.android.rimet",
              钉钉: "com.alibaba.android.rimet",
              settings: "com.android.settings",
              设置: "com.android.settings",
              camera: "com.android.camera",
              相机: "com.android.camera",
              oms: "com.oms.client",
            };
            const raw = String(params.packageOrAlias || params.text || "").trim();
            const pkg = aliasMap[raw.toLowerCase()] || raw;
            return await this.session.callPhone("gui.act", {
              action: "launch_app",
              text: pkg,
            });
          }
          if (subAction === "system_action") {
            return await this.session.callPhone("gui.act", {
              action: String(params.target || "home"),
            });
          }
          throw err;
        }
      }

      default:
        throw new Error(`Unknown IPC action: ${action}`);
    }
  }

  public static generateLaunchAgentPlist(repoRootPath: string): string {
    const bunPath = process.execPath;
    const homeDir = os.homedir();
    const daemonScript = path.join(repoRootPath, "mac-host/src/daemon/main.ts");
    const logPath = path.join(homeDir, ".one_more_step/logs/omsd.log");
    const safePath = `${homeDir}/.local/bin:${homeDir}/.bun/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin`;
    return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.oms.daemon</string>
    <key>ProgramArguments</key>
    <array>
        <string>${bunPath}</string>
        <string>run</string>
        <string>${daemonScript}</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${repoRootPath}</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>HOME</key>
        <string>${homeDir}</string>
        <key>PATH</key>
        <string>${safePath}</string>
        <key>BUN_CONFIG_DNS_TIME_TO_LIVE_SECONDS</key>
        <string>0</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${logPath}</string>
    <key>StandardErrorPath</key>
    <string>${logPath}</string>
</dict>
</plist>`;
  }

  public async stop(): Promise<void> {
    this.mirrorDisposed = true;
    if (this.edgeCursorSyncTimer) {
      clearInterval(this.edgeCursorSyncTimer);
      this.edgeCursorSyncTimer = null;
    }
    // Drain an outstanding start and send its compensating stop before closing transport.
    if (this.mirrorRunning || this.mirrorStartPending) {
      await this.stopMirror().catch(() => {});
    } else {
      ++this.mirrorGeneration;
      this.stopLegacyMirrorLoop();
      if (this.mirrorIdleStopTimer) {
        clearTimeout(this.mirrorIdleStopTimer);
        this.mirrorIdleStopTimer = null;
      }
      await this.mirrorLifecycleQueue;
    }
    if (this.wechat.isRunning()) {
      await this.syncWeChatCursorCheckpointToPhone(true).catch(() => {});
      await this.wechat.stop().catch(() => {});
    } else {
      await this.wechat.stop().catch(() => {});
    }
    for (const [, w] of this.pendingApprovals) {
      clearTimeout(w.timer);
      w.reject(new Error("Daemon stopping"));
    }
    this.pendingApprovals.clear();

    if (this.ipcServer) {
      await new Promise<void>((resolve) => this.ipcServer!.close(() => resolve()));
      this.ipcServer = null;
    }
    if (fs.existsSync(this.ipcSocketPath)) {
      try {
        fs.unlinkSync(this.ipcSocketPath);
      } catch {
        // ignore
      }
    }
    if (this.hudServer) {
      await this.hudServer.stop();
      this.hudServer = null;
    }
    this.stopStayAwakeProcess();
    this.agentHub.stopAll();
    this.memoryMirror.close();
    await this.session.stop();
  }

  private canonicalizeCurateUrl(rawUrl: string): string {
    const trimmed = String(rawUrl || "").trim();
    try {
      const u = new URL(trimmed);
      const host = u.hostname.toLowerCase();
      if (host === "youtu.be") {
        const vid = u.pathname.replace(/^\/+/, "").split("/")[0]?.trim();
        if (vid && /^[\w-]{6,20}$/.test(vid)) return `https://www.youtube.com/watch?v=${vid}`;
      }
      if (host === "youtube.com" || host.endsWith(".youtube.com")) {
        if (u.pathname === "/watch") {
          const vid = u.searchParams.get("v")?.trim();
          if (vid && /^[\w-]{6,20}$/.test(vid)) return `https://www.youtube.com/watch?v=${vid}`;
        }
        const m = u.pathname.match(/^\/(shorts|live)\/([\w-]{6,20})/);
        if (m) return `https://www.youtube.com/${m[1]}/${m[2]}`;
      }
      u.hash = "";
      return u.toString();
    } catch {
      return trimmed;
    }
  }

  public async curateBrowserUrlToVaultAndContext(
    params: CurateBrowserUrlParams
  ): Promise<CurateBrowserUrlResult> {
    const cleanUrl = this.canonicalizeCurateUrl(params.url);
    if (!cleanUrl || !/^https?:\/\//i.test(cleanUrl)) {
      return {
        ok: false,
        url: cleanUrl,
        title: String(params.title || ""),
        mediaKind: "article",
        shortSummary: "",
        error: "无效的网页或视频链接",
      };
    }

    const vaultRoot = this.memoryMirror.resolveVaultRoot();
    let result: CurateBrowserUrlResult;

    const wxRes = await this.wechat.ingestUrlToVault({
      url: cleanUrl,
      title: params.title,
      author: params.author,
      userNote: params.userNote,
      fallbackMarkdown: params.extractedText,
    });

    if (wxRes.ok && wxRes.article) {
      const mediaKind: "video" | "article" =
        wxRes.article.mediaKind === "video" ? "video" : "article";
      const relNotePath =
        wxRes.notePath && wxRes.notePath.startsWith(vaultRoot)
          ? path.relative(vaultRoot, wxRes.notePath).replace(/\\/g, "/")
          : undefined;
      result = {
        ok: true,
        articleId: wxRes.article.id,
        url: wxRes.article.url || cleanUrl,
        title: wxRes.article.title || params.title || cleanUrl,
        mediaKind,
        shortSummary: wxRes.shortSummary || wxRes.article.digest || `已整理《${wxRes.article.title}》并存入知识库`,
        triage: wxRes.triage,
        sourcePath: wxRes.sourcePath,
        notePath: wxRes.notePath,
        relNotePath,
      };
    } else {
      result = this.memoryMirror.curateDirectToVaultFallback({
        ...params,
        url: cleanUrl,
      });
    }

    if (result.ok) {
      this.memoryMirror.recordCuratedContent({
        url: result.url,
        title: result.title,
        mediaKind: result.mediaKind,
        shortSummary: result.shortSummary,
        sourcePath: result.sourcePath,
        notePath: result.notePath,
        relNotePath: result.relNotePath,
        captureMode: params.captureMode === "auto_dwell" ? "auto_dwell" : "manual",
        curatedAtMs: Date.now(),
      });
      try {
        this.memoryMirror.syncVaultIndex(true);
      } catch {
        // non-fatal
      }
      void this.syncEdgeConfigAndCapsuleToPhone(true).catch(() => {});
    }

    return result;
  }

  public enrichAgentPromptWithRecentCurated(rawPrompt: string): string {
    const prompt = String(rawPrompt || "");
    if (!prompt.trim()) return prompt;
    const recent = this.memoryMirror
      .getRecentCuratedContext(5)
      .filter((x) => Date.now() - x.curatedAtMs <= 72 * 3600 * 1000);
    if (recent.length === 0) return prompt;

    const lower = prompt.toLowerCase();
    const mentionsReadingOrVault =
      /(?:刚才看|刚看|最近看|刚保存|刚收录|那个视频|那篇文章|伴读|知识库|youtube|油管|浏览器里)/i.test(prompt) ||
      recent.some((item) => {
        const cleanTitle = item.title.toLowerCase().trim();
        return cleanTitle.length >= 4 && lower.includes(cleanTitle.slice(0, 12));
      });
    if (!mentionsReadingOrVault) return prompt;

    const refLines = recent.slice(0, 3).map((item) => {
      const kind = item.mediaKind === "video" ? "视频" : "网页";
      const summary = item.shortSummary.replace(/\s+/g, " ").slice(0, 140);
      const paths = [
        item.notePath ? `伴读笔记: ${item.notePath}` : "",
        item.sourcePath ? `原文/字幕: ${item.sourcePath}` : "",
      ]
        .filter(Boolean)
        .join(" | ");
      return `- [${kind}] 《${item.title}》 (${item.url})${summary ? `\n  提炼摘要: ${summary}` : ""}${paths ? `\n  本地知识库路径: ${paths}` : ""}`;
    });

    return `${prompt}\n\n---\n[青简·伴读知识库上下文同步（用户近期在应用舱查看并沉淀的内容，可直接读取本地路径保持上下文一致）]\n${refLines.join("\n")}`;
  }
}
