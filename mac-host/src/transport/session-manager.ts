import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import dgram from "node:dgram";
import { spawn, execFile, ChildProcess } from "node:child_process";
import { EventEmitter } from "node:events";
import {
  DecodedScreenFrame,
  decodeJsonRpcPayload,
  decodeScreenFramePayload,
  decodeStreamChunkPayload,
  deriveWanRoomId,
  Ed25519Identity,
  encodeFrame,
  encodeJsonRpcFrame,
  encodePingFrame,
  encodePongFrame,
  encodeStreamAckResumeFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  FrameStreamDecoder,
  FrameType,
  generateUuidV7,
  JsonRpcMessage,
  JsonRpcRequest,
  JsonRpcResponse,
  OmsErrorCode,
  PhoneDeviceStatus,
  PriorityFrameQueue,
  StreamAckResumeHeader,
  StreamStartHeader,
  TransportTier,
  SecureHello,
  SecureStart,
  SecureSession,
  generateSecureEphemeral,
  secureRandomHex,
  establishSecureSession,
} from "@oms/shared-protocol";
import { ConnectionDiagnostics } from "./connection-diagnostics.js";
import { PresenceEngine } from "./presence-engine.js";
import { BluetoothBridgeServer } from "./bluetooth-bridge-client.js";

export interface TrustedPhoneDevice {
  phoneId: string;
  phoneModel: string;
  phoneName: string;
  phonePubKeyBase64Url: string;
  wanRoomId: string;
  pairSecretHex?: string;
  pairingVersion?: number;
  lastKnownLanIps?: string[];
  pairedAtMs: number;
  lastSeenMs: number;
}

export interface IncomingFileTransfer {
  header: StreamStartHeader;
  receivedChunks: Map<number, Uint8Array>;
  receivedBytes: number;
  startedAtMs: number;
  pipe: ActiveTransportPipe;
}

export interface CompletedFileTransfer {
  header: StreamStartHeader;
  savedAbsPath: string;
  sha256Actual: string;
}

interface ActiveTransportPipe {
  diagnosticId?: string;
  tier: TransportTier;
  authenticated: boolean;
  phoneId?: string;
  remoteIp?: string;
  closed?: boolean;
  secure?: {
    phase: "hello" | "finish" | "ready";
    hello: SecureHello;
    ephemeral: ReturnType<typeof generateSecureEphemeral>;
    timer: Timer;
    receivedHandshakeBytes: number;
    start?: SecureStart;
    startJson?: string;
    session?: SecureSession;
    pairSecretHex?: string;
    macProofHex?: string;
    pairingCredentials?: PairingCredentials;
    finishJson?: string;
    finishResult?: { ok: true; pairingVersion: 2; macId: string; wanRoomId: string };
  };
  decoder: FrameStreamDecoder;
  sendRaw: (bytes: Uint8Array) => boolean | number;
  close: () => void;
}

interface PairingCredentials {
  ephemeralToken: string;
  expiresAtMs: number;
  pairingUri?: string;
  reservedBy?: ActiveTransportPipe;
}

const TIER_PRIORITY: Record<TransportTier, number> = {
  LAN_WS: 3,
  BT_RFCOMM: 2,
  WAN_WEBRTC: 1,
  DISCONNECTED: 0,
};

/**
 * Module 02: Unified Multi-Tier Session Manager (LAN WebSocket + Bluetooth + WAN P2P/Relay)
 * Supports Make-Before-Break hot switching, P0/P2 priority scheduling, and resumable file streams.
 */
export class PhoneSessionManager extends EventEmitter {
  public readonly presence: PresenceEngine;
  private readonly hostIdentity: Ed25519Identity;
  private readonly lanPort: number;
  private readonly streamDownloadDir: string;
  private trustedDevices: Map<string, TrustedPhoneDevice> = new Map();
  private trustPersistenceUncertain = false;
  private activePairingCreds: PairingCredentials | null = null;

  private wsServer: ReturnType<typeof Bun.serve> | null = null;
  private btBridge: BluetoothBridgeServer | null = null;
  private wanRelayWs: WebSocket | null = null;
  private udpDiscoveryServer: dgram.Socket | null = null;
  private mdnsAdvertiseProc: ChildProcess | null = null;
  private housekeepingTimer: Timer | null = null;
  private lastKnownLanIpsKey = "";

  private pipes: Map<TransportTier, ActiveTransportPipe> = new Map();
  private readonly openPipes = new Set<ActiveTransportPipe>();
  private pendingBtPipe?: ActiveTransportPipe;
  private readonly outgoingStreams = new Map<string, { pipe: ActiveTransportPipe; resolve: () => void; reject: (err: Error) => void; timer: Timer }>();
  private activeTier: TransportTier = "DISCONNECTED";
  private activeRouteGeneration = 0;
  private phoneStatus: PhoneDeviceStatus | null = null;
  private lastRttMs = 0;

  private readonly outQueue = new PriorityFrameQueue();
  private readonly pendingRpc = new Map<
    string,
    {
      resolve: (val: any) => void;
      reject: (err: Error) => void;
      timer: Timer;
      pipe: ActiveTransportPipe;
    }
  >();
  private readonly incomingStreams = new Map<string, IncomingFileTransfer>();
  private readonly completedStreams = new Map<string, CompletedFileTransfer>();
  private readonly mirrorViewers = new Set<{ send: (data: string | Uint8Array) => unknown; close: () => void }>();
  private latestRawScreenFrame: Uint8Array | null = null;
  private latestDecodedScreenFrame: DecodedScreenFrame | null = null;
  private customHttpHandler?: (
    req: Request,
    url: URL,
    clientIp: string
  ) => Promise<Response | null> | Response | null;
  private apkHashCache: { mtimeMs: number; size: number; sha256: string } | null = null;

  private connectionDiagnostics: ConnectionDiagnostics;

  public getConnectionDiagnostics() {
    return { persistenceAvailable: this.connectionDiagnostics.persistenceAvailable, events: this.connectionDiagnostics.snapshot() };
  }

  public getBluetoothBridge(): BluetoothBridgeServer | null {
    return this.btBridge;
  }

  constructor(opts: {
    hostIdentity: Ed25519Identity;
    lanPort: number;
    streamDownloadDir: string;
    diagnosticsPath?: string;
    trustedDevices?: TrustedPhoneDevice[];
    presenceOptions?: ConstructorParameters<typeof PresenceEngine>[0];
    customHttpHandler?: (
      req: Request,
      url: URL,
      clientIp: string
    ) => Promise<Response | null> | Response | null;
  }) {
    super();
    this.connectionDiagnostics = new ConnectionDiagnostics(opts.diagnosticsPath);
    this.hostIdentity = opts.hostIdentity;
    this.lanPort = opts.lanPort;
    this.streamDownloadDir = opts.streamDownloadDir;
    this.presence = new PresenceEngine(opts.presenceOptions);
    this.customHttpHandler = opts.customHttpHandler;
    fs.mkdirSync(this.streamDownloadDir, { recursive: true });

    for (const dev of opts.trustedDevices ?? []) {
      this.trustedDevices.set(dev.phoneId, dev);
    }
  }

  public setCustomHttpHandler(
    handler: (
      req: Request,
      url: URL,
      clientIp: string
    ) => Promise<Response | null> | Response | null
  ): void {
    this.customHttpHandler = handler;
  }

  public static isLoopbackAddress(ip: string): boolean {
    const normalized = (ip || "").trim().toLowerCase();
    return (
      normalized === "127.0.0.1" ||
      normalized === "::1" ||
      normalized === "::ffff:127.0.0.1" ||
      normalized === "localhost"
    );
  }

  public getApkMetadata(): {
    available: boolean;
    apkPath: string;
    versionCode: number;
    versionName: string;
    sha256: string;
    sizeBytes: number;
    updatedAtMs: number;
    updatedAtIso: string;
    downloadUrl: string;
    githubDownloadUrl: string;
  } {
    const repoRoot = path.resolve(
      path.dirname(new URL(import.meta.url).pathname),
      "../../.."
    );
    const apkDir = path.join(repoRoot, "android-app/app/build/outputs/apk/debug");
    let rootReleaseApks: string[] = [];
    try {
      rootReleaseApks = fs
        .readdirSync(repoRoot)
        .filter((f) => /^OneMoreStep-v[\d.]+\.apk$/.test(f))
        .sort()
        .reverse()
        .map((f) => path.join(repoRoot, f));
    } catch {
      // ignore
    }
    const candidatePaths = [
      path.join(apkDir, "app-debug.apk"),
      ...rootReleaseApks,
      path.join(os.homedir(), ".one_more_step", "OneMoreStep.apk"),
    ];
    const apkPath = candidatePaths.find((p) => fs.existsSync(p)) || candidatePaths[0];
    const metaPath = path.join(apkDir, "output-metadata.json");

    if (!fs.existsSync(apkPath)) {
      const defaultVer = "1.5.6";
      const ghUrl = `https://github.com/dqrcsc/one-more-step/releases/download/v${defaultVer}/OneMoreStep-v${defaultVer}.apk`;
      return {
        available: false,
        apkPath,
        versionCode: 30,
        versionName: defaultVer,
        sha256: "",
        sizeBytes: 0,
        updatedAtMs: 0,
        updatedAtIso: "",
        downloadUrl: ghUrl,
        githubDownloadUrl: ghUrl,
      };
    }

    const stat = fs.statSync(apkPath);
    let versionCode = 30;
    let versionName = "1.5.6";
    const fileMatch = path.basename(apkPath).match(/^OneMoreStep-v([\d.]+)\.apk$/);
    if (fileMatch) {
      versionName = fileMatch[1];
    }
    if (fs.existsSync(metaPath)) {
      try {
        const meta = JSON.parse(fs.readFileSync(metaPath, "utf8"));
        const el = meta?.elements?.[0];
        if (typeof el?.versionCode === "number") versionCode = el.versionCode;
        if (typeof el?.versionName === "string") versionName = el.versionName;
      } catch {
        // fallback to defaults
      }
    }

    if (
      !this.apkHashCache ||
      this.apkHashCache.mtimeMs !== stat.mtimeMs ||
      this.apkHashCache.size !== stat.size
    ) {
      const bytes = fs.readFileSync(apkPath);
      const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
      this.apkHashCache = { mtimeMs: stat.mtimeMs, size: stat.size, sha256 };
    }

    return {
      available: true,
      apkPath,
      versionCode,
      versionName,
      sha256: this.apkHashCache.sha256,
      sizeBytes: stat.size,
      updatedAtMs: Math.floor(stat.mtimeMs),
      updatedAtIso: new Date(stat.mtimeMs).toISOString(),
      downloadUrl: "/OneMoreStep.apk",
      githubDownloadUrl: `https://github.com/dqrcsc/one-more-step/releases/download/v${versionName}/OneMoreStep-v${versionName}.apk`,
    };
  }

  public setActivePairingCredentials(creds: { ephemeralToken: string; expiresAtMs: number; pairingUri?: string }): void {
    if (!/^[0-9a-f]{64}$/.test(creds.ephemeralToken) || !Number.isFinite(creds.expiresAtMs)) {
      throw new Error("Secure v2 pairing requires a 256-bit token");
    }
    const reserved = this.activePairingCreds?.reservedBy;
    if (reserved) this.failPipe(reserved, "Pairing credentials were replaced; start pairing again");
    this.activePairingCreds = { ...creds };
  }

  public getTrustedDevices(): TrustedPhoneDevice[] {
    return Array.from(this.trustedDevices.values());
  }

  public getSecurityStatus(): { secureProtocolVersion: 2; requiresRePair: boolean } {
    return {
      secureProtocolVersion: 2,
      requiresRePair: this.trustPersistenceUncertain || this.getTrustedDevices().some((device) => device.pairingVersion !== 2),
    };
  }

  public getActiveTier(): TransportTier {
    return this.activeTier;
  }

  public getPhoneStatus(): PhoneDeviceStatus | null {
    return this.phoneStatus;
  }

  public setPhoneStatus(status: PhoneDeviceStatus | null): void {
    if (status) {
      this.phoneStatus = { ...(this.phoneStatus || {}), ...status };
    }
  }

  public getLastRttMs(): number {
    return this.lastRttMs;
  }

  public getCompletedStream(streamId: string): CompletedFileTransfer | undefined {
    return this.completedStreams.get(streamId);
  }

  public getMirrorViewerCount(): number {
    return this.mirrorViewers.size;
  }

  public getLatestScreenFrame(): { raw: Uint8Array; decoded: DecodedScreenFrame } | null {
    if (!this.latestRawScreenFrame || !this.latestDecodedScreenFrame) return null;
    return { raw: this.latestRawScreenFrame, decoded: this.latestDecodedScreenFrame };
  }

  public ingestScreenFrame(rawPayload: Uint8Array, decoded?: DecodedScreenFrame): DecodedScreenFrame {
    const frame = decoded ?? decodeScreenFramePayload(rawPayload);
    const copy = new Uint8Array(rawPayload);
    this.latestRawScreenFrame = copy;
    this.latestDecodedScreenFrame = frame;
    for (const viewer of this.mirrorViewers) {
      try {
        viewer.send(copy);
      } catch {
        this.mirrorViewers.delete(viewer);
      }
    }
    this.emit("screenFrame", frame, copy);
    return frame;
  }

  public broadcastMirrorEvent(eventObj: Record<string, unknown>): void {
    const json = JSON.stringify(eventObj);
    for (const viewer of this.mirrorViewers) {
      try {
        viewer.send(json);
      } catch {
        this.mirrorViewers.delete(viewer);
      }
    }
  }

  public static getLocalLanIps(): string[] {
    const nets = os.networkInterfaces();
    const primaryIps: string[] = [];
    const secondaryIps: string[] = [];
    for (const name of Object.keys(nets)) {
      if (/^(utun|tun|tap|ppp|awdl|llw|bridge|lo|gif|stf|ap)/i.test(name)) continue;
      for (const netInfo of nets[name] ?? []) {
        if (netInfo.family === "IPv4" && !netInfo.internal) {
          const addr = netInfo.address;
          if (addr.startsWith("198.18.") || addr.startsWith("169.254.")) continue;
          if (/^en\d+$/i.test(name)) {
            primaryIps.push(addr);
          } else {
            secondaryIps.push(addr);
          }
        }
      }
    }
    const ips = [...primaryIps, ...secondaryIps];
    return ips.length > 0 ? ips : ["127.0.0.1"];
  }

  /**
   * Start Tier 1 (LAN WebSocket Server), Tier 2 (Bluetooth Bridge), and optional Tier 3 (WAN Signaling/Relay)
   */
  public async start(opts?: {
    btSocketPath?: string;
    btBinaryPath?: string;
    wanRelayWssUrl?: string;
  }): Promise<void> {
    const self = this;

    // 1. Start Tier 1: High-Speed LAN WebSocket Server
    this.wsServer = Bun.serve<{ pipe?: ActiveTransportPipe; tier: TransportTier; remoteIp?: string; isMirrorViewer?: boolean }>({
      port: this.lanPort,
      async fetch(req, server) {
        const url = new URL(req.url);
        const clientIp = server.requestIP(req)?.address || "unknown";
        const normalizedIp = clientIp.replace(/^::ffff:/i, "");
        console.log(`[omsd][http] ${req.method} ${url.pathname} from ${clientIp}`);

        // Localhost-only WebSocket for real-time screen mirroring in Mac browser (/ws/mirror)
        if (url.pathname === "/ws/mirror") {
          const reqHost = req.headers.get("host") || "";
          let validHost = PhoneSessionManager.isLoopbackAddress(url.hostname);
          if (reqHost) {
            try {
              validHost = validHost && PhoneSessionManager.isLoopbackAddress(new URL(`http://${reqHost}`).hostname);
            } catch {
              validHost = false;
            }
          }
          const origin = req.headers.get("origin");
          const validOrigin = !origin || origin === url.origin;
          if (!PhoneSessionManager.isLoopbackAddress(clientIp) || !validHost || !validOrigin) {
            return new Response(
              JSON.stringify({ ok: false, error: "Forbidden: Mirror WebSocket is strictly restricted to localhost (127.0.0.1 / ::1)." }),
              { status: 403, headers: { "Content-Type": "application/json; charset=utf-8" } }
            );
          }
          if (server.upgrade(req, { data: { tier: "LAN_WS", remoteIp: normalizedIp, isMirrorViewer: true } })) {
            return undefined;
          }
          return new Response("WebSocket upgrade required", { status: 426 });
        }

        const tierParam = url.searchParams.get("tier") === "wan" ? "WAN_WEBRTC" : "LAN_WS";
        if (server.upgrade(req, { data: { tier: tierParam, remoteIp: normalizedIp } })) {
          return undefined;
        }

        if (self.customHttpHandler) {
          const customResp = await self.customHttpHandler(req, url, clientIp);
          if (customResp) return customResp;
        }

        if (url.pathname === "/pair/resolve") {
          return Response.json({ ok: false, error: "HTTP PIN pairing was removed. Use a new local oms pair v2 URI through a trusted channel." }, { status: 410 });
        }

        // APK Version Metadata Endpoint for Smart OTA Update Check
        if (url.pathname === "/apk/version") {
          const meta = self.getApkMetadata();
          return new Response(
            JSON.stringify({
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
              lanPort: self.lanPort,
            }),
            {
              headers: {
                "Content-Type": "application/json",
                "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
              },
            }
          );
        }

        // Wireless APK Download Endpoint (No USB cable needed!)
        if (url.pathname === "/OneMoreStep.apk" || url.pathname === "/apk") {
          const meta = self.getApkMetadata();
          if (meta.available && fs.existsSync(meta.apkPath)) {
            return new Response(Bun.file(meta.apkPath), {
              headers: {
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Disposition": `attachment; filename="OneMoreStep_v${meta.versionName}.apk"`,
                "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
              },
            });
          }
          return Response.redirect(
            meta.githubDownloadUrl,
            302
          );
        }

        // Wireless Mobile Onboarding & PIN-Protected Pairing Page
        if (
          url.pathname === "/install" ||
          (url.pathname === "/" && (req.headers.get("accept") || "").includes("text/html"))
        ) {
          const isLocalhost = PhoneSessionManager.isLoopbackAddress(clientIp);
          const apkMeta = self.getApkMetadata();
          const sizeMb = apkMeta.sizeBytes ? (apkMeta.sizeBytes / 1048576).toFixed(1) : "21.0";
          const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>OneMoreStep · 小米手机无线安装与安全配对台</title>
<style>
  body { background:#0F172A; color:#F8FAFC; font-family:-apple-system,BlinkMacSystemFont,sans-serif; padding:20px; margin:0; }
  .card { background:#1E293B; border-radius:16px; padding:20px; margin-bottom:16px; border:1px solid #334155; }
  h2 { margin:0 0 8px 0; font-size:18px; color:#38BDF8; }
  p { margin:6px 0; font-size:14px; color:#CBD5E1; line-height:1.5; }
  .btn { display:block; width:100%; box-sizing:border-box; text-align:center; background:#0284C7; color:#fff; font-weight:600; padding:14px; border-radius:12px; text-decoration:none; border:none; font-size:15px; margin-top:12px; cursor:pointer; }
  .btn-green { background:#16A34A; }
  .btn-purple { background:#4F46E5; }
  input { width:100%; box-sizing:border-box; background:#0F172A; color:#F8FAFC; border:1px solid #475569; border-radius:10px; padding:12px; font-size:16px; margin-top:8px; letter-spacing:2px; text-align:center; }
  code { display:none; background:#0F172A; padding:10px; border-radius:8px; word-break:break-all; font-size:12px; color:#FBBF24; margin-top:8px; }
</style>
</head>
<body>
  ${
    isLocalhost
      ? `<div class="card">
    <h2>💻 Mac 端实时收件箱与协同看板（仅限本机访问）</h2>
    <p>直观查看手机发来的文字、截图大图预览、一键复制或反向推消息到手机：</p>
    <a class="btn btn-purple" href="/inbox">📬 打开 Mac 实时收件箱看板 (/inbox)</a>
  </div>`
      : ""
  }
  <div class="card">
    <h2>第一步：无线下载安装 APK (v${apkMeta.versionName})</h2>
    <p>无需数据线，直接通过当前局域网从 Mac 高速下载安装包（${sizeMb} MB）：</p>
    <a class="btn" href="/OneMoreStep.apk">📥 点击下载 OneMoreStep_v${apkMeta.versionName}.apk</a>
  </div>
  <div class="card">
    <h2>第二步：安全 v2 配对</h2>
    <p>在 Mac 本地终端运行 oms pair，使用你控制的可信途径将完整 v2 配对链接输入手机。安装网页不提供 PIN 换取链接，也不展示配对凭据。旧版手机需升级后重新配对。</p>
  </div>
  <div class="card">
    <h2>第三步：小米 HyperOS 权限开启提醒</h2>
    <p>1. 若小米提示「受限制的设置」，请打开系统「设置 ➔ 应用设置 ➔ OneMoreStep ➔ 右上角三个点 ➔ 允许受限制的设置」。</p>
    <p>2. 打开 App 点击右上角「小米权限」，一键开启「后台弹出界面」、「省电无限制」、「悬浮窗」与「无障碍服务」。</p>
  </div>
</body>
</html>`;
          return new Response(html, {
            headers: { "content-type": "text/html; charset=utf-8" },
          });
        }

        return new Response(
          JSON.stringify({
            service: "OneMoreStep Mac Host Daemon",
            macId: self.hostIdentity.deviceId,
            macName: os.hostname(),
            macPubKeyBase64Url: self.hostIdentity.publicKeyBase64Url,
            lanIps: PhoneSessionManager.getLocalLanIps(),
            lanPort: self.lanPort,
            activeTier: self.activeTier,
          }),
          { headers: { "content-type": "application/json" } }
        );
      },
      websocket: {
        idleTimeout: 120,
        sendPings: true,
        open(ws) {
          if (ws.data.isMirrorViewer) {
            self.mirrorViewers.add(ws);
            if (self.latestRawScreenFrame) {
              try {
                ws.send(self.latestRawScreenFrame);
              } catch {}
            }
            self.emit("mirrorViewerChanged", self.mirrorViewers.size, ws);
            return;
          }
          const tier = ws.data.tier;
          const remoteIp = ws.data.remoteIp;
          console.log(`[omsd][ws] opened tier=${tier} from=${remoteIp || "unknown"}`);
          const pipe: ActiveTransportPipe = {
            tier,
            authenticated: false,
            remoteIp,
            decoder: new FrameStreamDecoder(),
            sendRaw: (bytes) => ws.send(bytes),
            close: () => ws.close(),
          };
          ws.data.pipe = pipe;
          self.onPipeOpened(pipe);
        },
        message(ws, message) {
          if (ws.data.isMirrorViewer) {
            try {
              const txt = typeof message === "string" ? message : new TextDecoder().decode(new Uint8Array(message));
              const parsed = JSON.parse(txt);
              if (parsed && typeof parsed === "object") {
                self.emit("mirrorViewerCommand", parsed, ws);
              }
            } catch {}
            return;
          }
          const pipe = ws.data.pipe;
          if (!pipe) return;
          const bytes =
            typeof message === "string"
              ? new TextEncoder().encode(message)
              : new Uint8Array(message);
          self.onRawBytesReceived(pipe, bytes);
        },
        close(ws, code, reason) {
          if (ws.data.isMirrorViewer) {
            self.mirrorViewers.delete(ws);
            self.emit("mirrorViewerChanged", self.mirrorViewers.size);
            return;
          }
          // Remote close reason is untrusted and may contain secrets; keep numeric code only.
          if (ws.data.pipe?.diagnosticId) self.connectionDiagnostics.record("WS_CLOSED", ws.data.pipe.tier, ws.data.pipe.diagnosticId, code);
          const pipe = ws.data.pipe;
          if (pipe) {
            self.onPipeClosed(pipe);
          }
        },
      },
    });

    // 2. Start Tier 2: macOS Bluetooth Bridge Server
    if (opts?.btSocketPath) {
      this.btBridge = new BluetoothBridgeServer({
        socketPath: opts.btSocketPath,
        macId: this.hostIdentity.deviceId,
        lanIps: PhoneSessionManager.getLocalLanIps(),
        lanPort: this.lanPort,
        autoSpawnBinary: Boolean(opts.btBinaryPath),
        binaryPath: opts.btBinaryPath,
      });

      this.btBridge.on("bleState", (st: string) => {
        console.log(`[omsd][ble] state=${st}`);
      });

      this.btBridge.on("rssi", (rssi: number) => {
        this.presence.onBleRssiSample(rssi);
      });

      this.btBridge.on("phoneConnected", (info: any) => {
        console.log(`[omsd][ble] phoneConnected centralId=${info?.centralId}`);
        this.presence.onBleRssiSample(-58);
        const btPipe: ActiveTransportPipe = {
          tier: "BT_RFCOMM",
          authenticated: false,
          decoder: new FrameStreamDecoder(),
            sendRaw: (bytes) => this.btBridge?.sendFrameOverBluetooth(bytes) ?? false,
          close: () => {},
        };
        const previousBt = this.pendingBtPipe;
        if (previousBt) this.onPipeClosed(previousBt);
        this.pendingBtPipe = btPipe;
        this.onPipeOpened(btPipe);
      });

      this.btBridge.on("phoneDisconnected", (info: any) => {
        console.log(`[omsd][ble] phoneDisconnected centralId=${info?.centralId}`);
        this.presence.onBleDisconnected();
        const existing = this.pendingBtPipe || this.pipes.get("BT_RFCOMM");
        if (existing) this.onPipeClosed(existing);
        this.pendingBtPipe = undefined;
      });

      this.btBridge.on("frame", (bytes: Uint8Array) => {
        let btPipe = this.pendingBtPipe || this.pipes.get("BT_RFCOMM");
        if (!btPipe) {
          btPipe = {
            tier: "BT_RFCOMM",
            authenticated: false,
            decoder: new FrameStreamDecoder(),
            sendRaw: (b) => this.btBridge?.sendFrameOverBluetooth(b) ?? false,
            close: () => {},
          };
          this.pendingBtPipe = btPipe;
          this.onPipeOpened(btPipe);
        }
        this.onRawBytesReceived(btPipe, bytes);
      });

      this.btBridge.on("airpodsStatus", (status) => {
        this.emit("airpodsStatus", status);
      });

      this.btBridge.on("airpodsMacClaimTriggered", (evt) => {
        this.emit("airpodsMacClaimTriggered", evt);
      });

      await this.btBridge.start();
    }

    // 3. Optional Tier 3 Outbound WAN Signaling/Relay connection
    if (opts?.wanRelayWssUrl) {
      this.connectWanRelay(opts.wanRelayWssUrl);
    }

    // 4. Start UDP LAN Discovery Responder & macOS Bonjour (_oms._tcp) + Housekeeping Timer
    this.startLanDiscoveryAndHousekeeping(Boolean(opts?.btSocketPath));
  }

  private startLanDiscoveryAndHousekeeping(enableMdns: boolean): void {
    try {
      const sock = dgram.createSocket({ type: "udp4", reuseAddr: true });
      sock.on("error", () => {
        try {
          sock.close();
        } catch {}
      });
      sock.on("message", (msg, rinfo) => {
        const str = msg.toString("utf8").trim();
        console.log(`[omsd][udp] ${str} from ${rinfo.address}:${rinfo.port}`);
        if (str.startsWith("OMS_DISCOVER")) {
          const reply = JSON.stringify({
            service: "OneMoreStep Mac Host Daemon",
            macId: this.hostIdentity.deviceId,
            macName: os.hostname(),
            macPubKeyBase64Url: this.hostIdentity.publicKeyBase64Url,
            lanIps: PhoneSessionManager.getLocalLanIps(),
            lanPort: this.lanPort,
          });
          sock.send(Buffer.from(reply, "utf8"), rinfo.port, rinfo.address);
        }
      });
      sock.bind(this.lanPort, () => {
        try {
          sock.setBroadcast(true);
        } catch {}
      });
      this.udpDiscoveryServer = sock;
    } catch {
      // ignore if UDP port unavailable
    }

    if (enableMdns && process.platform === "darwin") {
      try {
        this.mdnsAdvertiseProc = spawn(
          "dns-sd",
          [
            "-R",
            `OneMoreStep-${this.hostIdentity.deviceId.slice(-6)}`,
            "_oms._tcp",
            "local",
            String(this.lanPort),
            `macId=${this.hostIdentity.deviceId}`,
            `macName=${os.hostname()}`,
            `port=${this.lanPort}`,
          ],
          { stdio: "ignore" }
        );
        this.mdnsAdvertiseProc.unref();
      } catch {
        // ignore if dns-sd unavailable
      }
    }

    this.lastKnownLanIpsKey = PhoneSessionManager.getLocalLanIps().join(",");
    if (enableMdns) {
      this.broadcastLanBeacon();
    }
    this.housekeepingTimer = setInterval(() => {
      const currentIps = PhoneSessionManager.getLocalLanIps();
      const key = currentIps.join(",");
      if (key !== this.lastKnownLanIpsKey) {
        this.lastKnownLanIpsKey = key;
        this.btBridge?.updateMacLanInfo(currentIps, this.lanPort);
      }
      if (this.activeTier !== "DISCONNECTED") {
        this.sendPing();
        if (this.pipes.get("BT_RFCOMM")?.authenticated) {
          this.presence.onBleRssiSample(this.presence.getSnapshot().rssiRaw ?? -58);
        }
        if (this.activeTier !== "LAN_WS" && enableMdns) {
          this.broadcastLanBeacon();
        }
      } else {
        this.presence.evaluateTimeouts();
        if (enableMdns) {
          this.broadcastLanBeacon();
        }
      }
    }, 4000);
    if (typeof (this.housekeepingTimer as any)?.unref === "function") {
      (this.housekeepingTimer as any).unref();
    }
  }

  private broadcastLanBeacon(): void {
    const sock = this.udpDiscoveryServer;
    if (!sock) return;
    const lanIps = PhoneSessionManager.getLocalLanIps();
    const payload = Buffer.from(
      JSON.stringify({
        service: "OneMoreStep Mac Host Daemon",
        type: "OMS_MAC_BEACON",
        macId: this.hostIdentity.deviceId,
        macName: os.hostname(),
        macPubKeyBase64Url: this.hostIdentity.publicKeyBase64Url,
        lanIps,
        lanPort: this.lanPort,
      }),
      "utf8"
    );
    const beaconPort = this.lanPort + 1; // 19528

    // 1. Broadcast to local /24 and /23 broadcast addresses
    const bcastTargets = new Set<string>(["255.255.255.255"]);
    for (const ip of lanIps) {
      const parts = ip.split(".");
      if (parts.length === 4) {
        const p2 = Number(parts[2]);
        if (!Number.isNaN(p2)) {
          bcastTargets.add(`${parts[0]}.${parts[1]}.${p2}.255`);
          bcastTargets.add(`${parts[0]}.${parts[1]}.${p2 | 1}.255`);
        }
      }
    }
    for (const target of bcastTargets) {
      try {
        sock.send(payload, beaconPort, target);
      } catch {}
    }

    // 2. Direct unicast to known trusted phone LAN IPs (wakes up phone even after ARP cache expiration)
    const trustedPhoneIps = new Set<string>();
    for (const dev of this.trustedDevices.values()) {
      for (const pip of dev.lastKnownLanIps || []) {
        if (pip && !lanIps.includes(pip) && !pip.startsWith("127.")) {
          trustedPhoneIps.add(pip);
        }
      }
    }
    for (const pip of this.phoneStatus?.lanIps || []) {
      if (pip && !lanIps.includes(pip) && !pip.startsWith("127.")) {
        trustedPhoneIps.add(pip);
      }
    }
    for (const pip of trustedPhoneIps) {
      try {
        sock.send(payload, beaconPort, pip);
      } catch {}
      if (process.platform === "darwin") {
        execFile("ping", ["-c", "1", "-W", "300", pip], { timeout: 600 }, () => {
          try {
            sock.send(payload, beaconPort, pip);
          } catch {}
        });
      }
    }

    // 3. Unicast to active ARP neighbors (bypasses enterprise Wi-Fi broadcast filtering & refreshes phone ARP cache!)
    if (process.platform === "darwin") {
      execFile("arp", ["-an"], { timeout: 1500 }, (err, stdout) => {
        if (err || !stdout) return;
        const lines = stdout.split("\n");
        for (const line of lines) {
          if (line.includes("(incomplete)")) continue;
          const m = line.match(/\((\d+\.\d+\.\d+\.\d+)\)/);
          if (m && m[1] && !lanIps.includes(m[1]) && !m[1].startsWith("224.") && !m[1].endsWith(".255")) {
            try {
              sock.send(payload, beaconPort, m[1]);
            } catch {}
          }
        }
      });
    }
  }

  private connectWanRelay(wssUrl: string): void {
    try {
      const ws = new WebSocket(wssUrl);
      ws.binaryType = "arraybuffer";
      const pipe: ActiveTransportPipe = {
        tier: "WAN_WEBRTC",
        authenticated: false,
        decoder: new FrameStreamDecoder(),
        sendRaw: (bytes) => {
          if (ws.readyState !== WebSocket.OPEN) throw new Error("WAN socket is not open");
          ws.send(bytes);
          return true;
        },
        close: () => ws.close(),
      };
      ws.onopen = () => this.onPipeOpened(pipe);
      ws.onmessage = (ev) => {
        const bytes =
          typeof ev.data === "string"
            ? new TextEncoder().encode(ev.data)
            : new Uint8Array(ev.data as ArrayBuffer);
        this.onRawBytesReceived(pipe, bytes);
      };
      ws.onclose = () => this.onPipeClosed(pipe);
      this.wanRelayWs = ws;
    } catch {
      // ignore initial WAN connection errors
    }
  }

  private onPipeOpened(pipe: ActiveTransportPipe): void {
    if (this.openPipes.has(pipe)) return;
    pipe.diagnosticId = crypto.randomBytes(6).toString("hex");
    this.connectionDiagnostics.record("OPENED", pipe.tier, pipe.diagnosticId);
    pipe.closed = false;
    pipe.authenticated = false;
    this.openPipes.add(pipe);
    const ephemeral = generateSecureEphemeral();
    const hello: SecureHello = { v: 2, macId: this.hostIdentity.deviceId, nonceMacHex: secureRandomHex(), publicKeyHex: ephemeral.publicKeyHex };
    const timer = setTimeout(() => this.failPipe(pipe, "Secure handshake timed out; reconnect or re-pair"), 30000);
    timer.unref?.();
    pipe.secure = { phase: "hello", hello, ephemeral, timer, receivedHandshakeBytes: 0 };
    try {
      this.writeRaw(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", method: "secure.hello", params: hello }));
    } catch {
      this.failPipe(pipe, "Could not send secure handshake");
    }
  }

  private writeRaw(pipe: ActiveTransportPipe, bytes: Uint8Array): void {
    if (pipe.closed) throw new Error("Transport is closed");
    const result = pipe.sendRaw(bytes);
    // Bun -1 means queued under backpressure; 0 means dropped.
    if (result !== true && result !== -1 && !(typeof result === "number" && result > 0)) {
      throw new Error("Transport rejected encrypted data; reconnect required");
    }
  }

  private sendSecure(pipe: ActiveTransportPipe, inner: Uint8Array): void {
    const secure = pipe.secure;
    if (pipe.closed || !secure?.session || secure.phase === "hello") throw new Error("Secure session is not confirmed");
    try {
      this.writeRaw(pipe, secure.session.seal(inner));
    } catch (err) {
      this.failPipe(pipe, "Encrypted send failed; reconnect or re-pair");
      throw err;
    }
  }

  private failPipe(pipe: ActiveTransportPipe, message: string): void {
    if (pipe.closed) return;
    if (pipe.diagnosticId) this.connectionDiagnostics.record(
      message.includes("timed out") ? "HANDSHAKE_TIMEOUT" : message.includes("send") ? "SEND_FAILED" : "PROTOCOL_REJECTED",
      pipe.tier, pipe.diagnosticId);
    this.onPipeClosed(pipe);
    try { pipe.close(); } catch {}
    this.emit("protocolError", new Error(message));
  }

  private isLivePipe(pipe: ActiveTransportPipe): boolean {
    return !pipe.closed && pipe.secure?.phase === "ready" && pipe.authenticated
      && Boolean(pipe.phoneId && this.trustedDevices.get(pipe.phoneId)?.pairingVersion === 2)
      && this.pipes.get(pipe.tier) === pipe;
  }

  private onPipeClosed(pipe: ActiveTransportPipe): void {
    if (!pipe.closed && pipe.diagnosticId) this.connectionDiagnostics.record("CLOSED", pipe.tier, pipe.diagnosticId);
    pipe.authenticated = false;
    pipe.closed = true;
    if (pipe.secure) {
      clearTimeout(pipe.secure.timer);
      pipe.secure.session?.destroy();
      if (pipe.secure.pairingCredentials?.reservedBy === pipe) pipe.secure.pairingCredentials.reservedBy = undefined;
      pipe.secure = undefined;
    }
    this.openPipes.delete(pipe);
    if (this.pipes.get(pipe.tier) === pipe) this.pipes.delete(pipe.tier);
    for (const [id, pending] of this.pendingRpc) {
      if (pending.pipe !== pipe) continue;
      clearTimeout(pending.timer);
      this.pendingRpc.delete(id);
      pending.reject(new Error("Transport closed; retry the operation"));
    }
    for (const [id, pending] of this.outgoingStreams) {
      if (pending.pipe !== pipe) continue;
      clearTimeout(pending.timer);
      this.outgoingStreams.delete(id);
      pending.reject(new Error("Transport closed before delivery acknowledgement"));
    }
    for (const [id, transfer] of this.incomingStreams) {
      if (transfer.pipe === pipe) this.incomingStreams.delete(id);
    }
    this.recomputeActiveTier();
  }

  private registerAuthenticatedPipe(pipe: ActiveTransportPipe): void {
    const previous = this.pipes.get(pipe.tier);
    if (previous && previous !== pipe) {
      this.onPipeClosed(previous);
      previous.close();
    }
    this.openPipes.add(pipe);
    this.pipes.set(pipe.tier, pipe);
  }

  /**
   * Make-Before-Break Tier Selection: always route over the highest priority authenticated pipe
   */
  private recomputeActiveTier(): void {
    let bestTier: TransportTier = "DISCONNECTED";
    for (const [tier, pipe] of this.pipes.entries()) {
      if (this.isLivePipe(pipe) && TIER_PRIORITY[tier] > TIER_PRIORITY[bestTier]) {
        bestTier = tier;
      }
    }
    if (bestTier !== this.activeTier) {
      const prev = this.activeTier;
      this.activeTier = bestTier;
      ++this.activeRouteGeneration;
      this.emit("tierChanged", { previous: prev, current: bestTier });
      if (bestTier !== "DISCONNECTED") {
        this.flushOutQueue();
      } else {
        this.presence.onAllTransportsDisconnected();
      }
    }
  }

  private onRawBytesReceived(pipe: ActiveTransportPipe, chunk: Uint8Array): void {
    if (pipe.closed || !this.openPipes.has(pipe)) return;
    try {
      if (pipe.secure && pipe.secure.phase !== "ready") {
        pipe.secure.receivedHandshakeBytes += chunk.byteLength;
        if (pipe.secure.receivedHandshakeBytes > 65536) throw new Error("Handshake byte budget exceeded");
      }
      for (const outer of pipe.decoder.push(chunk)) {
        if (pipe.closed) break;
        const secure = pipe.secure;
        if (!secure || outer.flags !== 0) throw new Error("Invalid secure connection state");
        if (outer.frameType === FrameType.SECURE_RECORD) {
          if (!secure.session || secure.phase === "hello") throw new Error("Encrypted record before key agreement");
          const record = encodeFrame(outer.frameType, outer.payload, outer.flags);
          const innerBytes = secure.session.open(record);
          const decoded = new FrameStreamDecoder().push(innerBytes);
          if (decoded.length !== 1) throw new Error("Expected one authenticated inner frame");
          const frame = decoded[0];
          if (secure.phase === "finish") {
            if (frame.frameType !== FrameType.CONTROL_JSON_RPC) throw new Error("Expected encrypted key confirmation");
            this.handleSecureFinish(pipe, decodeJsonRpcPayload<JsonRpcRequest>(frame.payload));
          } else {
            this.dispatchBusinessFrame(pipe, frame);
          }
        } else {
          if (secure.phase === "ready" || outer.frameType !== FrameType.CONTROL_JSON_RPC || outer.payload.byteLength > 4096) {
            throw new Error("Plaintext business traffic is forbidden; upgrade and re-pair");
          }
          const req = decodeJsonRpcPayload<JsonRpcRequest>(outer.payload);
          if (req.method !== "secure.start" || req.id !== "secure_start") throw new Error("Secure v2 handshake required; upgrade and re-pair");
          this.handleSecureStart(pipe, req);
        }
      }
    } catch {
      this.failPipe(pipe, "Secure protocol validation failed; upgrade/reconnect or re-pair");
    }
  }

  private handleSecureStart(pipe: ActiveTransportPipe, req: JsonRpcRequest): void {
    const secure = pipe.secure;
    if (!secure || pipe.closed) throw new Error("Connection has no handshake");
    const start = req.params as SecureStart;
    const startJson = JSON.stringify(start);
    if (secure.phase === "finish") {
      if (startJson !== secure.startJson) throw new Error("Handshake context cannot change");
      this.writeRaw(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", result: { v: 2, proofHex: secure.macProofHex } }));
      return;
    }
    if (secure.phase !== "hello" || !start || (start.mode !== "pair" && start.mode !== "auth")) throw new Error("Invalid handshake mode");
    let psk: string;
    let credentials: PairingCredentials | undefined;
    if (start.mode === "pair") {
      const active = this.activePairingCreds;
      if (!active || Date.now() >= active.expiresAtMs || (active.reservedBy && active.reservedBy !== pipe)) throw new Error("Pairing token unavailable");
      credentials = active;
      psk = active.ephemeralToken;
    } else {
      const trusted = this.trustedDevices.get(start.phoneId);
      if (this.trustPersistenceUncertain || trusted?.pairingVersion !== 2 || !trusted.pairSecretHex) throw new Error("Pairing must be replaced with secure v2");
      psk = trusted.pairSecretHex;
    }
    const established = establishSecureSession("mac", secure.hello, start, psk, secure.ephemeral.privateKeyHex);
    if (credentials) credentials.reservedBy = pipe;
    secure.phase = "finish";
    secure.start = start;
    secure.startJson = startJson;
    secure.session = established.session;
    secure.pairSecretHex = established.pairSecretHex;
    secure.macProofHex = established.macProofHex;
    secure.pairingCredentials = credentials;
    this.writeRaw(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", result: { v: 2, proofHex: established.macProofHex } }));
  }

  private validateFinish(params: unknown): { phoneName: string; phoneModel: string; deviceSnapshot: PhoneDeviceStatus } {
    if (!params || typeof params !== "object" || Array.isArray(params) || Buffer.byteLength(JSON.stringify(params)) > 32768) throw new Error("Invalid finish metadata");
    const p = params as Record<string, unknown>;
    for (const key of ["phoneName", "phoneModel"]) {
      if (typeof p[key] !== "string" || (p[key] as string).length > 256) throw new Error("Invalid phone metadata");
    }
    const status = p.deviceSnapshot as Record<string, unknown>;
    if (!status || typeof status !== "object" || Array.isArray(status)
      || typeof status.batteryPct !== "number" || !Number.isFinite(status.batteryPct) || status.batteryPct < 0 || status.batteryPct > 100
      || typeof status.isCharging !== "boolean" || typeof status.screenLocked !== "boolean"
      || !Array.isArray(status.lanIps) || status.lanIps.length > 16 || status.lanIps.some((ip) => typeof ip !== "string" || ip.length > 128)
      || !status.permissions || typeof status.permissions !== "object" || Array.isArray(status.permissions)
      || Object.values(status.permissions).some((v) => typeof v !== "boolean")) throw new Error("Invalid encrypted device snapshot");
    for (const [key, value] of Object.entries(status)) {
      if (["permissions", "lanIps"].includes(key)) continue;
      if (key === "pairedAirpods" && Array.isArray(value) && value.length <= 32 && value.every((item) =>
        item && typeof item === "object" && !Array.isArray(item)
        && typeof (item as Record<string, unknown>).name === "string" && ((item as Record<string, unknown>).name as string).length <= 256
        && typeof (item as Record<string, unknown>).mac === "string" && ((item as Record<string, unknown>).mac as string).length <= 64
        && typeof (item as Record<string, unknown>).connected === "boolean"
      )) continue;
      if (value === null || value === undefined) continue;
      if (typeof value === "string" && value.length <= 1024) continue;
      if (typeof value === "boolean" || (typeof value === "number" && Number.isFinite(value))) continue;
      throw new Error("Invalid device snapshot field");
    }
    return p as unknown as { phoneName: string; phoneModel: string; deviceSnapshot: PhoneDeviceStatus };
  }

  private handleSecureFinish(pipe: ActiveTransportPipe, req: JsonRpcRequest): void {
    const secure = pipe.secure;
    if (!secure?.start || !secure.session || req.method !== "secure.finish" || req.id !== "secure_finish") throw new Error("Expected secure.finish");
    const finishJson = JSON.stringify(req.params);
    if (secure.phase === "ready") {
      if (finishJson !== secure.finishJson || !this.isLivePipe(pipe)) throw new Error("Finish metadata changed");
      this.sendSecure(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_finish", result: secure.finishResult }));
      return;
    }
    if (secure.phase !== "finish") throw new Error("Handshake not ready for confirmation");
    const { phoneName, phoneModel, deviceSnapshot } = this.validateFinish(req.params);
    const start = secure.start;
    const previousTrust = this.getTrustedDevices();
    const existing = this.trustedDevices.get(start.phoneId);
    const credentials = secure.pairingCredentials;
    if (start.mode === "pair" && (!credentials || this.activePairingCreds !== credentials || credentials.reservedBy !== pipe || Date.now() >= credentials.expiresAtMs)) throw new Error("Pairing token expired or replaced");
    if (start.mode === "auth" && (existing?.pairingVersion !== 2 || !existing.pairSecretHex)) throw new Error("Pairing was revoked");
    const wanRoomId = existing && start.mode === "auth" ? existing.wanRoomId : deriveWanRoomId(this.hostIdentity.publicKeyBase64Url, start.phoneId);
    const trusted: TrustedPhoneDevice = {
      phoneId: start.phoneId, phoneName, phoneModel, phonePubKeyBase64Url: "", wanRoomId,
      pairingVersion: 2,
      pairSecretHex: start.mode === "pair" ? secure.pairSecretHex : existing!.pairSecretHex,
      pairedAtMs: start.mode === "pair" ? Date.now() : existing!.pairedAtMs,
      lastSeenMs: Date.now(),
      lastKnownLanIps: [...new Set([...(pipe.remoteIp && !pipe.remoteIp.startsWith("127.") ? [pipe.remoteIp] : []), ...deviceSnapshot.lanIps])].slice(0, 4),
    };
    const nextTrust = start.mode === "pair" ? [trusted] : previousTrust.map((old) => old.phoneId === trusted.phoneId ? trusted : old);
    // Persist before acknowledging. A failed ACK rolls back the saved candidate when possible.
    this.emit("trustStore", nextTrust);
    const result = { ok: true as const, pairingVersion: 2 as const, macId: this.hostIdentity.deviceId, wanRoomId };
    try {
      this.sendSecure(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_finish", result }));
    } catch (err) {
      try {
        this.emit("trustStore", previousTrust);
      } catch {
        this.trustPersistenceUncertain = true;
        for (const old of [...this.openPipes]) this.failPipe(old, "Pairing persistence rollback failed; explicit re-pair is required");
        this.emit("protocolError", new Error("Pairing persistence rollback failed; explicit re-pair is required"));
      }
      throw err;
    }
    if (start.mode === "pair") {
      this.activePairingCreds = null;
      for (const old of [...this.openPipes]) {
        if (old === pipe) continue;
        this.onPipeClosed(old);
        try { old.close(); } catch {}
      }
      this.incomingStreams.clear();
      this.completedStreams.clear();
      while (this.outQueue.size) this.outQueue.dequeue();
      this.emit("sessionRevoked");
    }
    this.trustedDevices.clear();
    for (const record of nextTrust) this.trustedDevices.set(record.phoneId, record);
    this.trustPersistenceUncertain = false;
    clearTimeout(secure.timer);
    secure.phase = "ready";
    if (pipe.diagnosticId) this.connectionDiagnostics.record("AUTHENTICATED", pipe.tier, pipe.diagnosticId);
    secure.finishJson = finishJson;
    secure.finishResult = result;
    pipe.authenticated = true;
    pipe.phoneId = start.phoneId;
    this.phoneStatus = { ...deviceSnapshot, secureProtocolVersion: 2 };
    this.registerAuthenticatedPipe(pipe);
    if (pipe.tier === "LAN_WS") this.presence.onLanDirectActive(1);
    else if (pipe.tier === "BT_RFCOMM") this.presence.onBleRssiSample(this.presence.getSnapshot().rssiRaw ?? -58);
    this.recomputeActiveTier();
    if (start.mode === "pair") this.emit("devicePaired", trusted);
    this.emit("phoneAuthenticated", { phoneId: start.phoneId, tier: pipe.tier, status: this.phoneStatus });
  }

  private dispatchBusinessFrame(pipe: ActiveTransportPipe, frame: ReturnType<FrameStreamDecoder["push"]>[number]): void {
    if (!this.isLivePipe(pipe)) throw new Error("Inactive encrypted pipe");
    if (pipe.tier === "BT_RFCOMM" && pipe.authenticated) {
      this.presence.onBleRssiSample(this.presence.getSnapshot().rssiRaw ?? -58);
    }
    switch (frame.frameType) {
      case FrameType.CONTROL_JSON_RPC: {
        const msg = decodeJsonRpcPayload<JsonRpcMessage>(frame.payload);
        if ("method" in msg && msg.method === "secure.finish") this.handleSecureFinish(pipe, msg);
        else this.handleJsonRpcMessage(pipe, msg);
        break;
      }
      case FrameType.STREAM_START: this.handleStreamStart(pipe, decodeJsonRpcPayload(frame.payload)); break;
      case FrameType.STREAM_CHUNK: {
        const chunk = decodeStreamChunkPayload(frame.payload);
        this.handleStreamChunk(pipe, chunk.streamId, chunk.chunkIndex, chunk.data);
        break;
      }
      case FrameType.STREAM_ACK_RESUME: this.handleStreamAck(pipe, decodeJsonRpcPayload(frame.payload)); break;
      case FrameType.PING: {
        if (frame.payload.byteLength !== 8) throw new Error("Invalid ping");
        const sentTs = Number(new DataView(frame.payload.buffer, frame.payload.byteOffset, frame.payload.byteLength).getBigUint64(0, false));
        this.sendSecure(pipe, encodePongFrame(sentTs, 100, true));
        break;
      }
      case FrameType.PONG: {
        if (frame.payload.byteLength !== 10) throw new Error("Invalid pong");
        const sentTs = Number(new DataView(frame.payload.buffer, frame.payload.byteOffset, frame.payload.byteLength).getBigUint64(0, false));
        this.lastRttMs = Math.max(1, Date.now() - sentTs);
        if (pipe.tier === "LAN_WS") this.presence.onLanDirectActive(this.lastRttMs);
        else if (pipe.tier === "BT_RFCOMM") this.presence.onBleRssiSample(this.presence.getSnapshot().rssiRaw ?? -58);
        break;
      }
      case FrameType.SCREEN_FRAME: {
        this.ingestScreenFrame(frame.payload);
        break;
      }
      default: throw new Error("Unsupported authenticated frame");
    }
  }

  private handleJsonRpcMessage(pipe: ActiveTransportPipe, msg: JsonRpcMessage): void {
    if (!this.isLivePipe(pipe)) return;
    if ("result" in msg || "error" in msg) {
      const resp = msg as JsonRpcResponse;
      const pending = this.pendingRpc.get(resp.id);
      if (pending && pending.pipe === pipe) {
        clearTimeout(pending.timer);
        this.pendingRpc.delete(resp.id);
        if (resp.error) pending.reject(Object.assign(new Error(resp.error.message), { code: resp.error.code }));
        else pending.resolve(resp.result);
      }
      return;
    }
    const req = msg as JsonRpcRequest;
    if (!req || typeof req.method !== "string" || ["pair.request", "auth.response", "secure.start", "secure.hello"].includes(req.method)) throw new Error("Legacy or unexpected handshake message");
    this.emit("phoneRequest", req, (resultOrError: { result?: unknown; error?: { code: number; message: string } }) => {
      if (!req.id || !this.isLivePipe(pipe)) return;
      this.sendSecure(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: req.id, ...(resultOrError.error ? { error: resultOrError.error } : { result: resultOrError.result }) }));
    });
  }

  private equalDigest(expected: string, actual: unknown): boolean {
    return typeof actual === "string" && /^[a-f0-9]{64}$/i.test(actual)
      && crypto.timingSafeEqual(Buffer.from(expected, "hex"), Buffer.from(actual, "hex"));
  }

  private streamError(pipe: ActiveTransportPipe, streamId: string, message: string): void {
    const transfer = this.incomingStreams.get(streamId);
    if (transfer?.pipe === pipe) this.incomingStreams.delete(streamId);
    this.sendSecure(pipe, encodeStreamAckResumeFrame({ streamId, status: "ERROR", nextExpectedChunkIndex: 0, errorMessage: message }));
  }

  private handleStreamStart(pipe: ActiveTransportPipe, header: StreamStartHeader): void {
    if (!this.isLivePipe(pipe)) return;
    if (!header || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(header.streamId)
      || !Number.isSafeInteger(header.totalBytes) || header.totalBytes < 0 || header.totalBytes > 64 * 1024 * 1024
      || !Number.isSafeInteger(header.chunkSize) || header.chunkSize < 1 || header.chunkSize > 65536
      || header.totalChunks > 65536
      || header.totalChunks !== Math.max(1, Math.ceil(header.totalBytes / header.chunkSize))
      || !/^[a-f0-9]{64}$/i.test(header.sha256) || typeof header.fileName !== "string") {
      this.streamError(pipe, header?.streamId || "invalid", "Invalid stream header or size limit exceeded");
      return;
    }
    const existing = this.incomingStreams.get(header.streamId);
    const completed = this.completedStreams.get(header.streamId);
    if (completed) {
      if (JSON.stringify(completed.header) !== JSON.stringify(header)) {
        this.streamError(pipe, header.streamId, "Stream ID already committed with different metadata");
      } else {
        this.sendSecure(pipe, encodeStreamAckResumeFrame({ streamId: header.streamId, status: "COMPLETED", nextExpectedChunkIndex: header.totalChunks }));
      }
      return;
    }
    if (existing) {
      if (existing.pipe !== pipe || JSON.stringify(existing.header) !== JSON.stringify(header)) {
        this.streamError(pipe, header.streamId, "Stream ID belongs to a different transfer");
        return;
      }
      let next = 0;
      while (existing.receivedChunks.has(next)) next++;
      this.sendSecure(pipe, encodeStreamAckResumeFrame({ streamId: header.streamId, status: "RESUME_REQUEST", nextExpectedChunkIndex: next }));
      return;
    }
    // Bound abandoned partial transfers as well as their individual sizes.
    for (const [id, transfer] of this.incomingStreams) {
      if (Date.now() - transfer.startedAtMs > 180000) this.incomingStreams.delete(id);
    }
    if (this.incomingStreams.size >= 16) {
      this.streamError(pipe, header.streamId, "Too many active streams");
      return;
    }
    this.incomingStreams.set(header.streamId, { header, pipe, receivedChunks: new Map(), receivedBytes: 0, startedAtMs: Date.now() });
  }

  private handleStreamChunk(pipe: ActiveTransportPipe, streamId: string, chunkIndex: number, data: Uint8Array): void {
    const transfer = this.incomingStreams.get(streamId);
    if (!this.isLivePipe(pipe) || !transfer || transfer.pipe !== pipe) return;
    const h = transfer.header;
    const expectedLength = chunkIndex === h.totalChunks - 1 ? h.totalBytes - chunkIndex * h.chunkSize : h.chunkSize;
    if (!Number.isSafeInteger(chunkIndex) || chunkIndex < 0 || chunkIndex >= h.totalChunks || data.byteLength !== expectedLength) {
      this.streamError(pipe, streamId, "Invalid chunk index or length");
      return;
    }
    const previous = transfer.receivedChunks.get(chunkIndex);
    if (previous && !Buffer.from(previous).equals(Buffer.from(data))) {
      this.streamError(pipe, streamId, "Conflicting duplicate chunk");
      return;
    }
    if (!previous) {
      transfer.receivedChunks.set(chunkIndex, data.slice());
      transfer.receivedBytes += data.byteLength;
    }
    if (transfer.receivedChunks.size !== h.totalChunks) return;
    const totalBuf = Buffer.concat(Array.from({ length: h.totalChunks }, (_, i) => Buffer.from(transfer.receivedChunks.get(i)!)));
    const actualSha256 = crypto.createHash("sha256").update(totalBuf).digest("hex");
    if (totalBuf.byteLength !== h.totalBytes || !this.equalDigest(actualSha256, h.sha256)) {
      this.streamError(pipe, streamId, "Stream length or SHA-256 mismatch");
      return;
    }
    const safeName = path.basename(h.fileName || `${streamId}.bin`);
    const savedAbsPath = path.join(this.streamDownloadDir, `${streamId}_${safeName}`);
    try {
      fs.writeFileSync(savedAbsPath + ".partial", totalBuf);
      fs.renameSync(savedAbsPath + ".partial", savedAbsPath);
      const completed: CompletedFileTransfer = { header: h, savedAbsPath, sha256Actual: actualSha256 };
      this.incomingStreams.delete(streamId);
      while (this.completedStreams.size >= 128) {
        const oldestKey = this.completedStreams.keys().next().value;
        if (oldestKey === undefined) break;
        this.completedStreams.delete(oldestKey);
      }
      this.completedStreams.set(streamId, completed);
      this.sendSecure(pipe, encodeStreamAckResumeFrame({ streamId, status: "COMPLETED", nextExpectedChunkIndex: h.totalChunks, savedPath: savedAbsPath }));
      this.emit("streamCompleted", completed);
    } catch (err) {
      this.streamError(pipe, streamId, `Could not commit stream: ${String(err)}`);
    }
  }

  private handleStreamAck(pipe: ActiveTransportPipe, ack: StreamAckResumeHeader): void {
    const pending = this.outgoingStreams.get(ack.streamId);
    if (!pending || pending.pipe !== pipe || !this.isLivePipe(pipe)) return;
    if (ack.status !== "COMPLETED" && ack.status !== "ERROR") return;
    clearTimeout(pending.timer);
    this.outgoingStreams.delete(ack.streamId);
    if (ack.status === "COMPLETED") pending.resolve();
    else pending.reject(new Error(ack.errorMessage || "Phone could not commit stream"));
  }

  public waitForCompletedStream(streamId: string, timeoutMs?: number): Promise<CompletedFileTransfer> {
    const existing = this.completedStreams.get(streamId);
    if (existing) return Promise.resolve(existing);
    const effectiveTimeoutMs = timeoutMs ?? (this.activeTier === "BT_RFCOMM" ? 60000 : 15000);
    return new Promise((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); this.off("streamCompleted", onDone); this.off("sessionRevoked", onRevoke); };
      const onDone = (completed: CompletedFileTransfer) => {
        if (completed.header.streamId !== streamId) return;
        cleanup(); resolve(completed);
      };
      const onRevoke = () => { cleanup(); reject(new Error("Session revoked")); };
      const timer = setTimeout(() => { cleanup(); reject(new Error("Timed out waiting for verified stream")); }, effectiveTimeoutMs);
      this.on("streamCompleted", onDone);
      this.on("sessionRevoked", onRevoke);
    });
  }

  /**
   * Capture one authenticated physical connection for a multi-step operation.
   * A later route change or reconnect invalidates this caller; it never silently
   * transfers an already authorized operation to another connection or phone.
   */
  public bindPhoneSession(): <TResult = any>(method: string, params: Record<string, unknown>, timeoutMs?: number) => Promise<TResult> {
    const pipe = this.pipes.get(this.activeTier);
    if (!pipe || !this.isLivePipe(pipe)) {
      throw Object.assign(new Error("Xiaomi phone is currently disconnected across all tiers (LAN / Bluetooth / WAN)."), {
        code: OmsErrorCode.ERR_DEVICE_OFFLINE,
      });
    }
    const routeGeneration = this.activeRouteGeneration;
    return <TResult = any>(method: string, params: Record<string, unknown>, timeoutMs?: number) =>
      this.callPhoneOnPipe<TResult>(pipe, routeGeneration, method, params, timeoutMs);
  }

  /**
   * Call a JSON-RPC 2.0 method on the connected Xiaomi phone
   */
  public async callPhone<TResult = any>(
    method: string,
    params: Record<string, unknown> = {},
    timeoutMs?: number
  ): Promise<TResult> {
    return this.bindPhoneSession()<TResult>(method, params, timeoutMs);
  }

  private async callPhoneOnPipe<TResult>(
    pipe: ActiveTransportPipe,
    routeGeneration: number,
    method: string,
    params: Record<string, unknown>,
    timeoutMs?: number
  ): Promise<TResult> {
    // No await between this check and sendSecure: routing cannot change while
    // preparing a dispatch. A submitted request/reply remains bound to this pipe.
    if (routeGeneration !== this.activeRouteGeneration || !this.isLivePipe(pipe) || this.pipes.get(this.activeTier) !== pipe) {
      throw new Error("ERR_SESSION_REVOKED: Phone connection changed; request a new operation");
    }
    const effectiveTimeoutMs = timeoutMs ?? (pipe.tier === "BT_RFCOMM" ? 45000 : 15000);

    const reqId = `req_${generateUuidV7()}`;
    const frame = encodeJsonRpcFrame({
      jsonrpc: "2.0",
      id: reqId,
      method,
      params,
    });

    return new Promise<TResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRpc.delete(reqId);
        const err = new Error(`RPC timeout calling ${method} (${effectiveTimeoutMs}ms)`) as Error & {
          code?: number;
        };
        err.code = OmsErrorCode.ERR_APPROVAL_TIMEOUT;
        reject(err);
      }, effectiveTimeoutMs);

      this.pendingRpc.set(reqId, { resolve, reject, timer, pipe });
      try { this.sendSecure(pipe, frame); } catch (err) {
        clearTimeout(timer); this.pendingRpc.delete(reqId); reject(err);
      }
    });
  }

  /**
   * Push a file or buffer from Mac -> Xiaomi Phone using chunked binary frames (P1/P2 priority)
   */
  public async pushStreamToPhone(opts: {
    fileName: string;
    mimeType: string;
    data: Uint8Array;
    title?: string;
    enableCommuteTts?: boolean;
    timeoutMs?: number;
  }): Promise<{ streamId: string; sha256: string }> {
    const pipe = this.pipes.get(this.activeTier);
    if (!pipe || !this.isLivePipe(pipe)) throw new Error("Cannot push stream: Xiaomi phone is disconnected.");
    if (opts.data.byteLength > 64 * 1024 * 1024) throw new Error("Stream exceeds 64 MiB limit");
    const streamId = generateUuidV7();
    const sha256 = crypto.createHash("sha256").update(opts.data).digest("hex");
    const chunkSize = this.activeTier === "BT_RFCOMM" ? 8192 : 65536;
    const totalChunks = Math.max(1, Math.ceil(opts.data.byteLength / chunkSize));

    const startHeader: StreamStartHeader = {
      streamId,
      fileName: opts.fileName,
      mimeType: opts.mimeType,
      totalBytes: opts.data.byteLength,
      chunkSize,
      totalChunks,
      sha256,
      title: opts.title,
      enableCommuteTts: opts.enableCommuteTts,
    };

    const effectiveTimeoutMs =
      opts.timeoutMs ??
      (this.activeTier === "BT_RFCOMM"
        ? Math.max(90000, 30000 + totalChunks * 800)
        : 60000);

    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.outgoingStreams.delete(streamId);
        reject(new Error("Phone delivery acknowledgement timed out; delivery is unconfirmed"));
      }, effectiveTimeoutMs);
      this.outgoingStreams.set(streamId, { pipe, resolve, reject, timer });
      try {
        this.sendSecure(pipe, encodeStreamStartFrame(startHeader));
        for (let i = 0; i < totalChunks; i++) {
          if (!this.outgoingStreams.has(streamId)) break;
          if (!this.isLivePipe(pipe)) throw new Error("Transport changed during stream transfer");
          this.sendSecure(pipe, encodeStreamChunkFrame(streamId, i, opts.data.subarray(i * chunkSize, Math.min((i + 1) * chunkSize, opts.data.byteLength))));
        }
      } catch (err) {
        clearTimeout(timer); this.outgoingStreams.delete(streamId); reject(err);
      }
    });

    return { streamId, sha256 };
  }

  private enqueueFrame(rawFrame: Uint8Array, frameType: FrameType): void {
    this.outQueue.enqueue(rawFrame, frameType);
    this.flushOutQueue();
  }

  private flushOutQueue(): void {
    if (this.activeTier === "DISCONNECTED") return;
    const activePipe = this.pipes.get(this.activeTier);
    if (!activePipe || !activePipe.authenticated) return;

    while (this.outQueue.size > 0) {
      const nextFrame = this.outQueue.dequeue();
      if (!nextFrame) break;
      try {
        this.sendSecure(activePipe, nextFrame);
      } catch {
        break;
      }
    }
  }

  public sendPing(): void {
    if (this.activeTier === "DISCONNECTED") return;
    this.enqueueFrame(encodePingFrame(Date.now()), FrameType.PING);
  }

  public async stop(): Promise<void> {
    for (const pipe of [...this.openPipes]) this.onPipeClosed(pipe);
    this.emit("sessionRevoked");
    if (this.housekeepingTimer) {
      clearInterval(this.housekeepingTimer);
      this.housekeepingTimer = null;
    }
    if (this.udpDiscoveryServer) {
      try {
        this.udpDiscoveryServer.close();
      } catch {}
      this.udpDiscoveryServer = null;
    }
    if (this.mdnsAdvertiseProc) {
      try {
        this.mdnsAdvertiseProc.kill();
      } catch {}
      this.mdnsAdvertiseProc = null;
    }
    for (const [, p] of this.pendingRpc) {
      clearTimeout(p.timer);
      p.reject(new Error("Session manager stopped"));
    }
    this.pendingRpc.clear();

    if (this.wsServer) {
      this.wsServer.stop(true);
      this.wsServer = null;
    }
    if (this.btBridge) {
      await this.btBridge.stop();
      this.btBridge = null;
    }
    if (this.wanRelayWs) {
      this.wanRelayWs.close();
      this.wanRelayWs = null;
    }
  }
}
