import { approvalTimeoutSec } from "./request-timeouts.js";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import net from "node:net";
import crypto from "node:crypto";
import { execFile, execFileSync, spawn } from "node:child_process";
import {
  AirPodsStatusSnapshot,
  ApprovalRespondParams,
  buildPairingUri,
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
  MirrorStatusSnapshot,
  OMS_BLE_SERVICE_SHORT_UUID,
  OMS_DEFAULT_LAN_PORT,
  OmsErrorCode,
  PairedAirPodsDevice,
  ScreenFrameCodec,
  ScreenFrameFlags,
} from "@oms/shared-protocol";
import { PhoneSessionManager, TrustedPhoneDevice } from "../transport/session-manager.js";
import { InboxManager } from "../inbox/inbox-manager.js";
import { handleDashboardHttpRequest } from "../inbox/inbox-web-dashboard.js";
import { DingTalkConfig, DingTalkPlugin } from "../plugins/dingtalk.js";

export interface OmsHostConfig {
  lanPort: number;
  wanRelayWssUrl?: string;
  enableBluetoothBridge: boolean;
  autoCopyTextToClipboard?: boolean;
  autoPreviewImageOnMac?: boolean;
  autoSyncOtpToMac?: boolean;
  autoSwitchAirpods?: boolean;
  pauseOtherOnAirpodsSwitch?: boolean;
  preferredAirpodsMac?: string;
  preferredTtsVoice?: string;
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

/**
 * Module 06: Mac Host Singleton Daemon (`omsd`)
 * Manages ~/.one_more_step/, Multi-Agent Local Unix Socket IPC, Inbox, Presence Routing & Approvals
 */
export class OmsDaemon {
  public readonly homeDir: string;
  public readonly ipcSocketPath: string;
  public readonly btSocketPath: string;
  public readonly inbox: InboxManager;
  public readonly session: PhoneSessionManager;
  public readonly dingtalk: DingTalkPlugin;

  private readonly configPath: string;
  private readonly identityPath: string;
  private readonly trustedDevicesPath: string;
  private readonly config: OmsHostConfig;
  private readonly identity: Ed25519Identity;
  private ipcServer: net.Server | null = null;
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
  private phoneAirpodsState: AirPodsStatusSnapshot["phone"] = {
    connected: false,
    mediaPlaying: false,
    inCall: false,
    pairedAirpods: [],
  };

  constructor(customHomeDir?: string) {
    this.homeDir = customHomeDir || path.join(os.homedir(), ".one_more_step");
    fs.mkdirSync(path.join(this.homeDir, "keystore"), { recursive: true });
    fs.mkdirSync(path.join(this.homeDir, "run"), { recursive: true });
    fs.mkdirSync(path.join(this.homeDir, "logs"), { recursive: true });
    fs.mkdirSync(path.join(this.homeDir, "cache", "tts"), { recursive: true });

    this.configPath = path.join(this.homeDir, "config.json");
    this.identityPath = path.join(this.homeDir, "keystore", "host_identity.json");
    this.trustedDevicesPath = path.join(this.homeDir, "keystore", "trusted_devices.json");
    this.ipcSocketPath = path.join(this.homeDir, "run", "omsd.sock");
    this.btSocketPath = path.join(this.homeDir, "run", "bt.sock");

    this.config = this.loadOrInitConfig();
    this.identity = this.loadOrInitIdentity();
    const trusted = this.loadTrustedDevices();

    this.inbox = new InboxManager(path.join(this.homeDir, "inbox"));
    this.dingtalk = new DingTalkPlugin(this.config.dingtalk);

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
    this.session.on("sessionRevoked", () => {
      for (const p of this.pendingApprovals.values()) { clearTimeout(p.timer); p.reject(new Error("Pairing session revoked")); }
      this.pendingApprovals.clear();
      this.settledApprovals.clear();
    });
  }

  private loadOrInitConfig(): OmsHostConfig {
    if (fs.existsSync(this.configPath)) {
      try {
        const parsed = JSON.parse(fs.readFileSync(this.configPath, "utf8")) as OmsHostConfig;
        return {
          autoCopyTextToClipboard: true,
          autoPreviewImageOnMac: true,
          autoSyncOtpToMac: true,
          autoSwitchAirpods: true,
          pauseOtherOnAirpodsSwitch: true,
          ...parsed,
        };
      } catch {
        // fallback to default
      }
    }
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
    fs.writeFileSync(this.configPath, JSON.stringify(defaultCfg, null, 2), "utf8");
    return defaultCfg;
  }

  public getHostPreferences(): {
    autoCopyTextToClipboard: boolean;
    autoPreviewImageOnMac: boolean;
    autoSyncOtpToMac: boolean;
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
    autoSwitchAirpods?: boolean;
    pauseOtherOnAirpodsSwitch?: boolean;
    preferredAirpodsMac?: string;
    preferredTtsVoice?: string;
    unlockPattern?: string;
    unlockPin?: string;
    unlockBounds?: string;
  }): {
    autoCopyTextToClipboard: boolean;
    autoPreviewImageOnMac: boolean;
    autoSyncOtpToMac: boolean;
    autoSwitchAirpods: boolean;
    pauseOtherOnAirpodsSwitch: boolean;
    preferredAirpodsMac?: string;
    preferredTtsVoice: string;
    hasUnlockPattern: boolean;
    hasUnlockPin: boolean;
    unlockBounds?: string;
  } {
    if (typeof prefs.autoCopyTextToClipboard === "boolean") {
      this.config.autoCopyTextToClipboard = prefs.autoCopyTextToClipboard;
    }
    if (typeof prefs.autoPreviewImageOnMac === "boolean") {
      this.config.autoPreviewImageOnMac = prefs.autoPreviewImageOnMac;
    }
    if (typeof prefs.autoSyncOtpToMac === "boolean") {
      this.config.autoSyncOtpToMac = prefs.autoSyncOtpToMac;
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
    fs.writeFileSync(this.configPath, JSON.stringify(this.config, null, 2), {
      encoding: "utf8",
      mode: 0o600,
    });
    this.syncAirpodsConfigToBridgeAndPhone();
    return this.getHostPreferences();
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
    opts?: { voice?: string; rate?: string; pitch?: string }
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
    if (!cleanText) return null;

    const resolved = this.resolveNeuralTtsVoice(opts?.voice);
    const rate = opts?.rate || "+6%";
    const pitch = opts?.pitch || "+0Hz";
    const cacheDir = path.join(this.homeDir, "cache", "tts");
    fs.mkdirSync(cacheDir, { recursive: true });
    const hash = crypto
      .createHash("sha256")
      .update(`${resolved.voiceId}|${rate}|${pitch}|${cleanText}`)
      .digest("hex")
      .slice(0, 32);
    const mp3Path = path.join(cacheDir, `${hash}.mp3`);

    try {
      if (fs.existsSync(mp3Path)) {
        const stat = fs.statSync(mp3Path);
        if (stat.size > 200) {
          const buf = fs.readFileSync(mp3Path);
          return {
            audioBase64: buf.toString("base64"),
            voiceId: resolved.voiceId,
            voiceLabel: resolved.label,
            cached: true,
            bytes: buf.length,
          };
        }
      }
    } catch {
      // regenerate
    }

    const studioScriptDir = path.join(
      os.homedir(),
      ".codex",
      "skills",
      "video-neural-voiceover",
      "scripts"
    );
    const studioScriptFile = path.join(studioScriptDir, "neural_voiceover_studio.py");
    if (!fs.existsSync(studioScriptFile)) {
      return null;
    }

    const pyCode = [
      "import sys, os",
      `sys.path.insert(0, ${JSON.stringify(studioScriptDir)})`,
      "from neural_voiceover_studio import synthesize_edge_tts",
      "ca = '/etc/ssl/cert.pem' if os.path.exists('/etc/ssl/cert.pem') else None",
      "synthesize_edge_tts(sys.argv[1], voice=sys.argv[2], out_mp3=sys.argv[3], rate=sys.argv[4], pitch=sys.argv[5], ca_file=ca)",
    ].join("; ");

    return await new Promise((resolve) => {
      execFile(
        "python3",
        ["-c", pyCode, cleanText, resolved.voiceId, mp3Path, rate, pitch],
        { timeout: 7500 },
        (err) => {
          if (err || !fs.existsSync(mp3Path)) {
            resolve(null);
            return;
          }
          try {
            const buf = fs.readFileSync(mp3Path);
            if (buf.length <= 200) {
              resolve(null);
              return;
            }
            resolve({
              audioBase64: buf.toString("base64"),
              voiceId: resolved.voiceId,
              voiceLabel: resolved.label,
              cached: false,
              bytes: buf.length,
            });
          } catch {
            resolve(null);
          }
        }
      );
    });
  }

  public copyTextToMacClipboard(text: string): void {
    if (!text || process.env.OMS_DISABLE_OSASCRIPT === "1") return;
    try {
      const proc = spawn("pbcopy");
      proc.stdin.write(text, "utf8");
      proc.stdin.end();
    } catch {
      // ignore if pbcopy unavailable
    }
  }

  public openFileOnMac(absPath: string, revealInFinder = false): void {
    if (!absPath || process.env.OMS_DISABLE_OSASCRIPT === "1") return;
    try {
      const args = revealInFinder ? ["-R", absPath] : [absPath];
      execFile("open", args);
    } catch {
      // ignore
    }
  }

  private loadOrInitIdentity(): Ed25519Identity {
    if (fs.existsSync(this.identityPath)) {
      try {
        return JSON.parse(fs.readFileSync(this.identityPath, "utf8")) as Ed25519Identity;
      } catch {
        // regenerate
      }
    }
    const id = generateEd25519Identity("mac");
    fs.writeFileSync(this.identityPath, JSON.stringify(id, null, 2), {
      mode: 0o600,
    });
    return id;
  }

  private loadTrustedDevices(): TrustedPhoneDevice[] {
    if (!fs.existsSync(this.trustedDevicesPath)) return [];
    try {
      return JSON.parse(fs.readFileSync(this.trustedDevicesPath, "utf8")) as TrustedPhoneDevice[];
    } catch {
      return [];
    }
  }

  private saveTrustedDevices(devices: TrustedPhoneDevice[]): void {
    const tempPath = `${this.trustedDevicesPath}.${generateUuidV7()}.tmp`;
    let descriptor: number | undefined;
    try {
      descriptor = fs.openSync(tempPath, "wx", 0o600);
      fs.writeFileSync(descriptor, JSON.stringify(devices, null, 2), "utf8");
      fs.fsyncSync(descriptor);
      fs.closeSync(descriptor);
      descriptor = undefined;
      fs.renameSync(tempPath, this.trustedDevicesPath);
    } catch (err) {
      if (descriptor !== undefined) fs.closeSync(descriptor);
      if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath);
      throw err;
    }
  }

  private wireSessionEvents(): void {
    this.session.on("trustStore", (devices: TrustedPhoneDevice[]) => {
      this.saveTrustedDevices(devices);
    });

    this.session.on("phoneAuthenticated", (evt: any) => {
      if (evt?.status) {
        this.ingestPhoneAirpodsSnapshot(evt.status);
      }
      this.syncAirpodsConfigToBridgeAndPhone();
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
              },
              3500
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
      (count: number, newWs?: { send: (data: string | Uint8Array) => unknown }) => {
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
          if (!this.mirrorRunning && this.session.getActiveTier() !== "DISCONNECTED") {
            void this.startMirror({}).catch(() => {});
          }
        } else if (this.mirrorRunning) {
          if (this.mirrorIdleStopTimer) clearTimeout(this.mirrorIdleStopTimer);
          this.mirrorIdleStopTimer = setTimeout(() => {
            this.mirrorIdleStopTimer = null;
            if (this.session.getMirrorViewerCount() === 0 && this.mirrorRunning) {
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
        reply: (res: { result?: unknown; error?: { code: number; message: string } }) => void
      ) => {
        try {
          if (req.method === "inbox.submitItem") {
            const params = req.params as InboxSubmitItemParams;
            const alreadyCommitted = Boolean(this.inbox.getItem(params.clientItemId));
            const stored = this.inbox.storeSubmittedItem(params, (streamId) =>
              this.session.getCompletedStream(streamId)
            );

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
                  "Glass"
                );
              }
            } else {
              if (textBody && this.config.autoCopyTextToClipboard !== false) {
                this.copyTextToMacClipboard(textBody);
                actionHint = " · 已复制可 ⌘V";
              }
              if (imageFile && this.config.autoPreviewImageOnMac !== false) {
                this.openFileOnMac(imageFile.absPath, false);
                actionHint = actionHint ? `${actionHint} & 已打开预览` : " · 已打开图片预览";
              }
              if (audioFile && !stored.voiceTranscript) {
                actionHint = actionHint ? `${actionHint} · 正在离线转写语音` : " · 正在离线转写语音";
                void this.transcribeInboxItem(stored.itemId).catch(() => {});
              }

              this.showMacDesktopNotification(
                `📱 收到小米手机投递 (#${stored.intentTag})${actionHint}`,
                textBody || stored.title || `新文件 (${stored.files[0]?.fileName || "附件"}) 已存入 Inbox`
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
            void this.session
              .getBluetoothBridge()
              ?.releaseAirpodsFromMac({
                pauseMedia: pauseMacMedia,
                targetMac,
                timeoutMs: 2200,
              })
              .then(() => {
                reply({ result: { ok: true, released: true, pausedMacMedia: pauseMacMedia } });
              })
              .catch(() => {
                reply({ result: { ok: true, released: true } });
              });
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
            void this.synthesizeNeuralTts(text, { voice })
              .then((res) => {
                if (!res) {
                  reply({ result: { ok: false } });
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
          } else {
            reply({ result: { ack: true } });
          }
        } catch (err: any) {
          reply({
            error: {
              code: -32000,
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
    };
  }

  public async switchAirpodsToMac(targetMac?: string, reason = "manual"): Promise<Record<string, any>> {
    const pausePhoneMedia = this.config.pauseOtherOnAirpodsSwitch !== false;
    this.lastAirpodsSwitchEvent = {
      direction: "PHONE_TO_MAC",
      reason,
      deviceMac: targetMac,
      atMs: Date.now(),
    };
    if (this.session.getActiveTier() !== "DISCONNECTED") {
      await this.session
        .callPhone(
          "airpods.handoffToMac",
          {
            targetMac,
            pausePhoneMedia,
            disconnectOnPhone: true,
            reason,
          },
          2500
        )
        .catch(() => {});
    }
    const res = await this.session.getBluetoothBridge()?.connectAirpodsToMac(targetMac);
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
      message: res?.message || (res?.ok ? "已切换到 Mac" : "切换到 Mac 失败"),
      status: this.getAirpodsStatusSnapshot(),
    };
  }

  public async switchAirpodsToPhone(targetMac?: string, reason = "manual"): Promise<Record<string, any>> {
    const pauseMacMedia = this.config.pauseOtherOnAirpodsSwitch !== false;
    this.lastAirpodsSwitchEvent = {
      direction: "MAC_TO_PHONE",
      reason,
      deviceMac: targetMac,
      atMs: Date.now(),
    };
    await this.session
      .getBluetoothBridge()
      ?.releaseAirpodsFromMac({
        pauseMedia: pauseMacMedia,
        targetMac,
        timeoutMs: 2500,
      })
      .catch(() => {});
    let phoneRes: any = null;
    if (this.session.getActiveTier() !== "DISCONNECTED") {
      phoneRes = await this.session
        .callPhone<any>(
          "airpods.connectOnPhone",
          {
            targetMac: targetMac || this.config.preferredAirpodsMac,
            reason,
          },
          5500
        )
        .catch((err) => ({ ok: false, error: err?.message || String(err) }));
      if (phoneRes?.status) {
        this.session.setPhoneStatus(phoneRes.status);
        this.ingestPhoneAirpodsSnapshot(phoneRes.status);
      }
    }
    return {
      ok: phoneRes ? Boolean(phoneRes.ok) : true,
      direction: "MAC_TO_PHONE",
      phoneResult: phoneRes,
      status: this.getAirpodsStatusSnapshot(),
    };
  }

  private showMacDesktopNotification(
    title: string,
    message: string,
    subtitle?: string,
    soundName?: string
  ): void {
    if (process.env.OMS_DISABLE_OSASCRIPT === "1") return;
    if (soundName) {
      this.session.getBluetoothBridge()?.suppressAirpodsAutoGrab(2600);
    }
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
    execFile("osascript", ["-e", script]);
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

    return await new Promise<string | null>((resolve) => {
      execFile(
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
    });
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
      this.inbox.updateItemTranscript(itemId, transcript);
      if (this.config.autoCopyTextToClipboard !== false) {
        this.copyTextToMacClipboard(transcript);
      }
      this.showMacDesktopNotification(
        `🎙️ 手机语音已转写 (#${item.intentTag} · 可 ⌘V)`,
        transcript
      );
      return { ok: true, itemId, transcript };
    }
    return { ok: false, itemId, transcript: null };
  }

  private async refreshPhoneStatusLive(force = false): Promise<void> {
    if (this.session.getActiveTier() === "DISCONNECTED") return;
    const now = Date.now();
    if (!force && now - this.lastStatusRefreshMs < 2500) return;
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
      mode: this.mirrorRunning ? this.mirrorMode : "stopped",
      codec: "webp",
      fps,
      seq: this.mirrorSeq,
      width: this.mirrorWidth,
      height: this.mirrorHeight,
      isSecureWindow: Boolean(this.mirrorFlags & ScreenFrameFlags.IS_SECURE_WINDOW),
      isScreenOff: Boolean(this.mirrorFlags & ScreenFrameFlags.IS_SCREEN_OFF),
      isScreenLocked:
        Boolean(this.mirrorFlags & ScreenFrameFlags.IS_SCREEN_LOCKED) ||
        Boolean(ps?.screenLocked),
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
    const pollOnce = async () => {
      if (!this.mirrorRunning || this.mirrorMode !== "legacy_poll") return;
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
              if (obs?.screenWidth) this.mirrorWidth = obs.screenWidth;
              if (obs?.screenHeight) this.mirrorHeight = obs.screenHeight;
              streamId = obs?.screenshotStreamId;
            }
          }
          if (streamId && this.mirrorRunning) {
            const comp = await this.session.waitForCompletedStream(streamId, 5000);
            if (comp && fs.existsSync(comp.savedAbsPath)) {
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
          this.mirrorLegacyPollingInFlight = false;
        }
      }
      if (!this.mirrorRunning || this.mirrorMode !== "legacy_poll") return;
      const isBoosted = Date.now() < this.mirrorLegacyBoostUntilMs;
      const delayMs = isBoosted ? 180 : 520;
      this.mirrorLegacyPollTimer = setTimeout(pollOnce, delayMs);
      (this.mirrorLegacyPollTimer as any).unref?.();
    };
    this.mirrorLegacyBoostUntilMs = Date.now() + 3000;
    void pollOnce();
  }

  public async startMirror(params: Record<string, any> = {}): Promise<MirrorStatusSnapshot> {
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
    const autoWake = params.autoWake !== false;

    try {
      const res = await this.session.callPhone<any>(
        "mirror.start",
        {
          maxWidth: this.mirrorMaxWidth,
          quality: this.mirrorQuality,
          maxFps: this.mirrorMaxFps,
          autoWake,
          keepScreenOn: params.keepScreenOn !== false,
        },
        6000
      );
      this.stopLegacyMirrorLoop();
      this.mirrorRunning = true;
      this.mirrorMode = "native_stream";
      if (res?.screenWidth) this.mirrorWidth = Number(res.screenWidth);
      if (res?.screenHeight) this.mirrorHeight = Number(res.screenHeight);
    } catch (err: any) {
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
        throw err;
      }
    }

    const snap = this.getMirrorStatus();
    this.session.broadcastMirrorEvent({ type: "mirror_status", status: snap });
    return snap;
  }

  public async stopMirror(): Promise<MirrorStatusSnapshot> {
    this.mirrorRunning = false;
    this.mirrorMode = "stopped";
    this.stopLegacyMirrorLoop();
    if (this.mirrorIdleStopTimer) {
      clearTimeout(this.mirrorIdleStopTimer);
      this.mirrorIdleStopTimer = null;
    }
    if (this.session.getActiveTier() !== "DISCONNECTED") {
      await this.session.callPhone("mirror.stop", {}, 3000).catch(() => {});
    }
    const snap = this.getMirrorStatus();
    this.session.broadcastMirrorEvent({ type: "mirror_status", status: snap });
    return snap;
  }

  public async sendMirrorInput(params: Record<string, any>): Promise<any> {
    this.mirrorLegacyBoostUntilMs = Date.now() + 2800;
    const inputType = String(params.inputType || params.action || params.type || "tap").toLowerCase();

    if (inputType === "unlock" || inputType === "unlock_screen") {
      return await this.handleIpcAction("phone_device_control", {
        action: "unlock_screen",
        pattern: params.pattern,
        pin: params.pin,
      });
    }

    const rpcPayload = {
      ...params,
      type: inputType,
    };

    if (this.mirrorMode !== "legacy_poll") {
      try {
        return await this.session.callPhone("mirror.input", rpcPayload, 6000);
      } catch (err: any) {
        if (!String(err?.message || "").includes("Unknown RPC method")) {
          throw err;
        }
      }
    }

    // Legacy fallback input translation for APK <= v1.4.9
    const w = this.mirrorWidth || 1080;
    const h = this.mirrorHeight || 2400;
    const toPxX = (norm?: number, px?: number) =>
      typeof norm === "number" ? Math.round(norm * w) : typeof px === "number" ? Math.round(px) : Math.round(w / 2);
    const toPxY = (norm?: number, px?: number) =>
      typeof norm === "number" ? Math.round(norm * h) : typeof px === "number" ? Math.round(px) : Math.round(h / 2);

    if (inputType === "tap" || inputType === "click" || inputType === "long_press") {
      const x = toPxX(params.normX, params.x);
      const y = toPxY(params.normY, params.y);
      return await this.session.callPhone("gui.act", {
        action: inputType === "long_press" ? "long_click" : "click",
        x,
        y,
        deadlineMs: Date.now() + 10000,
      });
    }
    if (inputType === "swipe" || inputType === "drag") {
      const x1 = toPxX(params.normX1 ?? params.normX, params.x1 ?? params.x);
      const y1 = toPxY(params.normY1 ?? params.normY, params.y1 ?? params.y);
      const x2 = toPxX(params.normX2, params.x2);
      const y2 = toPxY(params.normY2, params.y2);
      return await this.session.callPhone("gui.act", {
        action: "swipe",
        x: x1,
        y: y1,
        x2,
        y2,
        deadlineMs: Date.now() + 10000,
      });
    }
    if (inputType === "scroll" || inputType === "wheel") {
      const dy = Number(params.deltaY ?? 120);
      return await this.session.callPhone("gui.act", {
        action: dy >= 0 ? "scroll_down" : "scroll_up",
        deadlineMs: Date.now() + 10000,
      });
    }
    if (inputType === "text" || inputType === "input_text") {
      const text = String(params.text || "");
      await this.session.callPhone("clipboard.set", { text, showToast: false }).catch(() => {});
      return await this.session
        .callPhone("gui.act", {
          action: "input_text",
          text,
          deadlineMs: Date.now() + 10000,
        })
        .catch(() => ({ ok: true, clipboardFallback: true }));
    }
    if (inputType === "key" || inputType === "nav") {
      const key = String(params.key || params.target || "home").toLowerCase();
      return await this.session.callPhone("sys.control", {
        action: "system_action",
        target: key,
      });
    }
    return { ok: false, error: `Unsupported mirror input type: ${inputType}` };
  }

  public async start(opts?: { startTransport?: boolean }): Promise<void> {
    if (opts?.startTransport !== false) {
      const macHostRoot = path.resolve(
        path.dirname(new URL(import.meta.url).pathname),
        "../.."
      );
      const btBin = path.join(macHostRoot, "bin", "oms-bt-bridge");
      const btSrc = path.join(macHostRoot, "bluetooth-bridge", "main.swift");
      const btStat = fs.existsSync(btBin) ? fs.statSync(btBin) : null;
      const srcStat = fs.existsSync(btSrc) ? fs.statSync(btSrc) : null;
      if (
        this.config.enableBluetoothBridge &&
        process.platform === "darwin" &&
        srcStat &&
        (!btStat || srcStat.mtimeMs > btStat.mtimeMs)
      ) {
        try {
          fs.mkdirSync(path.dirname(btBin), { recursive: true });
          execFileSync("swiftc", [btSrc, "-o", btBin], { timeout: 30000, stdio: "ignore" });
        } catch {
          // ignore if swiftc / Xcode CLT is unavailable
        }
      }
      await this.session.start({
        btSocketPath: this.config.enableBluetoothBridge ? this.btSocketPath : undefined,
        btBinaryPath: fs.existsSync(btBin) ? btBin : undefined,
        wanRelayWssUrl: this.config.wanRelayWssUrl,
      });
      this.syncAirpodsConfigToBridgeAndPhone();
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
        socket.on("data", async (chunk: string) => {
          buffer += chunk;
          let idx: number;
          while ((idx = buffer.indexOf("\n")) !== -1) {
            const line = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 1);
            if (!line) continue;
            try {
              const req = JSON.parse(line) as {
                id: string;
                action: string;
                params?: Record<string, any>;
              };
              const result = await this.handleIpcAction(req.action, req.params ?? {});
              socket.write(JSON.stringify({ id: req.id, ok: true, result }) + "\n");
            } catch (err: any) {
              socket.write(
                JSON.stringify({
                  id: "err",
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
      this.ipcServer.listen(this.ipcSocketPath, () => resolve());
    });

    fs.writeFileSync(
      path.join(this.homeDir, "run", "omsd.pid"),
      String(process.pid),
      "utf8"
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
          phoneStatus: this.session.getPhoneStatus(),
        };
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
        return { ok, itemId, processed };
      }

      case "inbox.clear_processed": {
        const removedCount = this.inbox.clearProcessed();
        return { ok: true, removedCount };
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
          typeof params.autoSwitchAirpods === "boolean" ||
          typeof params.pauseOtherOnAirpodsSwitch === "boolean" ||
          typeof params.preferredAirpodsMac === "string" ||
          typeof params.unlockPattern === "string" ||
          typeof params.unlockPin === "string" ||
          typeof params.unlockBounds === "string"
        ) {
          return this.updateHostPreferences({
            autoCopyTextToClipboard: params.autoCopyTextToClipboard,
            autoPreviewImageOnMac: params.autoPreviewImageOnMac,
            autoSyncOtpToMac: params.autoSyncOtpToMac,
            autoSwitchAirpods: params.autoSwitchAirpods,
            pauseOtherOnAirpodsSwitch: params.pauseOtherOnAirpodsSwitch,
            preferredAirpodsMac: params.preferredAirpodsMac,
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
            if (latestOtp && latestOtp.otpCode) {
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
        const repeatCount =
          typeof params.repeat === "number" && Number.isFinite(params.repeat)
            ? Math.max(1, Math.min(20, Math.round(params.repeat)))
            : 1;
        let neuralTts: Awaited<ReturnType<typeof this.synthesizeNeuralTts>> = null;
        if (ttsSpeak) {
          const speakContent = [title, body].filter(Boolean).join("。");
          neuralTts = await this.synthesizeNeuralTts(speakContent, {
            voice: params.voice ? String(params.voice) : undefined,
          });
        }

        const res = await this.session.callPhone<any>("notify.send", {
          title,
          body,
          level: effectivePhoneLevel,
          ttsSpeak,
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
            : {}),
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
        const isBle = this.session.getActiveTier() === "BT_RFCOMM";
        const lanUrl = `http://${ips[0]}:${this.config.lanPort}/OneMoreStep.apk?t=${Date.now()}`;
        const url = isBle ? meta.githubDownloadUrl : lanUrl;
        return await this.session.callPhone("sys.otaUpdate", {
          ip: ips[0],
          port: this.config.lanPort,
          url,
          githubDownloadUrl: meta.githubDownloadUrl,
        });
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
          }

          return {
            ok: true,
            action: "take_screenshot",
            localAbsPath: savedAbsPath,
            inboxItemId,
          };
        }

        if (subAction === "unlock_screen" || subAction === "unlock") {
          const rawPattern =
            typeof params.pattern === "string" && params.pattern.trim()
              ? params.pattern.trim()
              : undefined;
          const rawPin =
            typeof params.pin === "string" && params.pin.trim()
              ? params.pin.trim()
              : undefined;
          const rawBounds =
            typeof params.bounds === "string" && params.bounds.trim()
              ? params.bounds.trim()
              : undefined;
          if (params.savePattern && rawPattern) {
            this.updateHostPreferences({ unlockPattern: rawPattern });
          }
          if (params.savePin && rawPin) {
            this.updateHostPreferences({ unlockPin: rawPin });
          }
          if ((params.saveBounds || params.savePattern) && rawBounds) {
            this.updateHostPreferences({ unlockBounds: rawBounds });
          }
          const effectivePattern = rawPattern || this.config.unlockPattern || "";
          const effectivePin = rawPin || this.config.unlockPin || "";
          const effectiveBounds = rawBounds || this.config.unlockBounds || "175,1516,1045,2386";
          const unlockPayload = {
            ...params,
            action: "unlock_screen",
            pattern: effectivePattern,
            pin: effectivePin,
            bounds: effectiveBounds,
          };

          try {
            const cachedStatus = this.session.getPhoneStatus();
            if (effectivePattern && cachedStatus && cachedStatus.screenInteractive === false) {
              await this.session
                .callPhone<any>(
                  "sys.control",
                  { action: "unlock_screen", pattern: "", bounds: "" },
                  10000
                )
                .catch(() => {});
              await new Promise((r) => setTimeout(r, 750));
            }

            let res = await this.session.callPhone<any>(
              "sys.control",
              unlockPayload,
              25000
            );
            if (effectivePattern && res && res.unlocked === false && res.screenLocked === true) {
              await new Promise((r) => setTimeout(r, 450));
              res = await this.session.callPhone<any>(
                "sys.control",
                {
                  ...params,
                  action: "unlock_screen",
                  pattern: effectivePattern,
                  pin: effectivePin,
                  bounds: "",
                },
                25000
              );
            }
            if (res?.status) {
              this.session.setPhoneStatus(res.status);
            } else {
              void this.refreshPhoneStatusLive(true);
            }
            return {
              ...res,
              savedPatternToMacConfig: Boolean(params.savePattern && rawPattern),
              savedBoundsToMacConfig: Boolean((params.saveBounds || params.savePattern) && rawBounds),
            };
          } catch (err: any) {
            const msg = String(err?.message || "");
            if (msg.includes("Unsupported sys.control action")) {
              // APK < v1.4.7 fallback: wake screen + swipe up to bouncer, and prompt OTA update
              await this.session
                .callPhone("sys.control", { action: "launch_app", packageOrAlias: "oms" })
                .catch(() => {});
              await this.session
                .callPhone("gui.act", { action: "scroll_down", deadlineMs: Date.now() + 15000 })
                .catch(() => {});
              return {
                ok: false,
                action: "unlock_screen",
                bouncerOpened: true,
                requiresApkUpdate: true,
                savedPatternToMacConfig: Boolean(params.savePattern && rawPattern),
                error:
                  "已点亮屏幕并上滑唤出图案密码盘；当前手机端 APK 版本低于 v1.4.7，请运行 `oms ota` 升级手机端后即可支持连续轨迹图案自动绘制解锁。",
              };
            }
            throw err;
          }
        }

        if (subAction === "call_phone" || subAction === "call" || subAction === "dial") {
          const rawNum = String(params.number || params.phoneNumber || params.text || "").trim();
          const cleanNumber = rawNum.replace(/[^\d+*#,]/g, "");
          if (!cleanNumber) {
            throw new Error("请提供要拨打的电话号码，例如: oms phone call 10000");
          }
          const dialOnly = subAction === "dial" ? true : Boolean(params.dialOnly);
          const simSlot = params.simSlot !== undefined ? String(params.simSlot) : params.sim !== undefined ? String(params.sim) : "1";
          const speaker = Boolean(params.speaker);

          // Auto-unlock first if phone is locked
          const cached = this.session.getPhoneStatus();
          if (!cached || cached.screenLocked !== false || cached.screenInteractive === false) {
            await this.handleIpcAction("phone_device_control", {
              action: "unlock_screen",
              pattern: params.pattern,
              pin: params.pin,
            }).catch(() => {});
            await new Promise((r) => setTimeout(r, 400));
          }

          try {
            const res = await this.session.callPhone<any>(
              "sys.control",
              {
                action: "call_phone",
                number: cleanNumber,
                dialOnly,
                simSlot,
                speaker,
                pattern: this.config.unlockPattern,
                pin: this.config.unlockPin,
                bounds: this.config.unlockBounds || "175,1516,1045,2386",
              },
              25000
            );
            if (res?.status) this.session.setPhoneStatus(res.status);
            return res;
          } catch (err: any) {
            if (!String(err?.message || "").includes("Unsupported sys.control action")) {
              throw err;
            }
            // Seamless execution for APK <= v1.4.8 via tel:// URI + Accessibility SIM button click
            await this.session.callPhone<any>("sys.control", {
              action: "open_url",
              url: `tel://${cleanNumber}`,
            });
            if (dialOnly) {
              return {
                ok: true,
                action: "call_phone",
                number: cleanNumber,
                dialedOnly: true,
                callPlaced: false,
              };
            }
            await new Promise((r) => setTimeout(r, 500));
            const obs = await this.session.callPhone<any>("gui.observe", {
              includeScreenshot: false,
              somAnnotation: false,
            });
            const nodes: any[] = Array.isArray(obs?.nodes) ? obs.nodes : [];
            const callBtns = nodes
              .filter(
                (n) =>
                  n.clickable &&
                  (n.role === "Button" || n.role === "ImageButton") &&
                  Array.isArray(n.bounds) &&
                  n.bounds[1] >= 2100 &&
                  n.bounds[0] >= 220 &&
                  n.bounds[2] <= 1000 &&
                  !String(n.label || "").includes("菜单") &&
                  !String(n.label || "").includes("关闭")
              )
              .sort((a, b) => (a.center?.[0] || 0) - (b.center?.[0] || 0));

            let chosen =
              callBtns.find((b) => simSlot && String(b.label || "").includes(simSlot)) ||
              (simSlot === "2" && callBtns.length >= 2 ? callBtns[1] : callBtns[0]);

            if (chosen) {
              await this.session.callPhone<any>("gui.act", {
                action: "click",
                targetNodeId: chosen.id,
                observationId: obs.observationId,
                deadlineMs: Date.now() + 15000,
              });
            } else {
              const tapX = simSlot === "2" ? 781 : 438;
              await this.session.callPhone<any>("gui.act", {
                action: "click",
                x: tapX,
                y: 2344,
                deadlineMs: Date.now() + 15000,
              });
            }

            let speakerEnabled = false;
            if (speaker) {
              await new Promise((r) => setTimeout(r, 1800));
              const inCallObs = await this.session
                .callPhone<any>("gui.observe", { includeScreenshot: false, somAnnotation: false })
                .catch(() => null);
              const spkNode = (inCallObs?.nodes || []).find(
                (n: any) =>
                  n.clickable &&
                  (String(n.label || "").includes("免提") ||
                    String(n.label || "").includes("扬声器"))
              );
              if (spkNode) {
                await this.session
                  .callPhone<any>("gui.act", {
                    action: "click",
                    targetNodeId: spkNode.id,
                    observationId: inCallObs.observationId,
                    deadlineMs: Date.now() + 15000,
                  })
                  .catch(() => {});
                speakerEnabled = true;
              }
            }

            return {
              ok: true,
              action: "call_phone",
              number: cleanNumber,
              simUsed: chosen?.label || (simSlot === "2" ? "SIM卡2" : "SIM卡1"),
              callPlaced: true,
              speakerEnabled,
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
            const obs = await this.session
              .callPhone<any>("gui.observe", { includeScreenshot: false, somAnnotation: false })
              .catch(() => null);
            const hangupNode = (obs?.nodes || []).find(
              (n: any) =>
                n.clickable &&
                (String(n.label || "").includes("挂断") ||
                  String(n.label || "").includes("结束"))
            );
            if (hangupNode) {
              await this.session.callPhone<any>("gui.act", {
                action: "click",
                targetNodeId: hangupNode.id,
                observationId: obs.observationId,
                deadlineMs: Date.now() + 15000,
              });
            } else {
              await this.session.callPhone<any>("gui.act", {
                action: "click",
                x: 610,
                y: 2345,
                deadlineMs: Date.now() + 15000,
              });
            }
            return {
              ok: true,
              action: "hangup_call",
              hungUp: true,
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
            neuralTtsMeta = await this.synthesizeNeuralTts(speakText, {
              voice: params.voice ? String(params.voice) : undefined,
            });
          }
          enrichedParams = {
            ...params,
            text: speakText,
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
          return res;
        } catch (err: any) {
          const msg = String(err?.message || "");
          if (!msg.includes("Unknown RPC method")) {
            throw err;
          }
          // Backward compatibility fallback for APK < v1.3.3
          if (subAction === "find_phone") {
            const text = String(enrichedParams.text || "正在寻找手机，我在这里！");
            return await this.session.callPhone("notify.send", {
              title: "🔔 Mac 正在寻找此手机",
              body: text,
              level: "urgent",
              ttsSpeak: true,
              forceSpeakerAlarmStream: true,
              audioBase64: neuralTtsMeta?.audioBase64,
              repeatCount: enrichedParams.repeatCount,
            });
          }
          if (subAction === "tts_speak") {
            const text = String(enrichedParams.text || "");
            return await this.session.callPhone("notify.send", {
              title: "🗣️ 语音播报",
              body: text,
              level: "silent",
              ttsSpeak: true,
              forceSpeakerAlarmStream: Boolean(params.useAlarmStream),
              audioBase64: neuralTtsMeta?.audioBase64,
              repeatCount: enrichedParams.repeatCount,
            });
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
    const daemonScript = path.join(repoRootPath, "mac-host/src/daemon/main.ts");
    const logPath = path.join(os.homedir(), ".one_more_step/logs/omsd.log");
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
    this.mirrorRunning = false;
    this.stopLegacyMirrorLoop();
    if (this.mirrorIdleStopTimer) {
      clearTimeout(this.mirrorIdleStopTimer);
      this.mirrorIdleStopTimer = null;
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
    await this.session.stop();
  }
}
