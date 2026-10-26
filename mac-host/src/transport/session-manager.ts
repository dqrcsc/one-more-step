import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import dgram from "node:dgram";
import { spawn, execFile, ChildProcess } from "node:child_process";
import { EventEmitter } from "node:events";
import {
  AuthResponseParams,
  computeAuthHmac,
  computePairingHmac,
  decodeJsonRpcPayload,
  decodeStreamChunkPayload,
  deriveWanRoomId,
  Ed25519Identity,
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
  PairRequestParams,
  PairResponseResult,
  PhoneDeviceStatus,
  PriorityFrameQueue,
  signEd25519,
  StreamAckResumeHeader,
  StreamStartHeader,
  TransportTier,
  verifyEd25519,
} from "@oms/shared-protocol";
import { PresenceEngine } from "./presence-engine.js";
import { BluetoothBridgeServer } from "./bluetooth-bridge-client.js";

export interface TrustedPhoneDevice {
  phoneId: string;
  phoneModel: string;
  phoneName: string;
  phonePubKeyBase64Url: string;
  wanRoomId: string;
  pairSecretHex?: string;
  lastKnownLanIps?: string[];
  pairedAtMs: number;
  lastSeenMs: number;
}

export interface IncomingFileTransfer {
  header: StreamStartHeader;
  receivedChunks: Map<number, Uint8Array>;
  receivedBytes: number;
  startedAtMs: number;
}

export interface CompletedFileTransfer {
  header: StreamStartHeader;
  savedAbsPath: string;
  sha256Actual: string;
}

interface ActiveTransportPipe {
  tier: TransportTier;
  authenticated: boolean;
  phoneId?: string;
  remoteIp?: string;
  pendingNonceMac?: string;
  decoder: FrameStreamDecoder;
  sendRaw: (bytes: Uint8Array) => void;
  close: () => void;
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
  private activePairingCreds: {
    pinCode: string;
    ephemeralToken: string;
    expiresAtMs: number;
    pairingUri?: string;
    failedPinAttempts?: number;
  } | null = null;

  private wsServer: ReturnType<typeof Bun.serve> | null = null;
  private btBridge: BluetoothBridgeServer | null = null;
  private wanRelayWs: WebSocket | null = null;
  private udpDiscoveryServer: dgram.Socket | null = null;
  private mdnsAdvertiseProc: ChildProcess | null = null;
  private housekeepingTimer: Timer | null = null;
  private lastKnownLanIpsKey = "";

  private pipes: Map<TransportTier, ActiveTransportPipe> = new Map();
  private activeTier: TransportTier = "DISCONNECTED";
  private phoneStatus: PhoneDeviceStatus | null = null;
  private lastRttMs = 0;

  private readonly outQueue = new PriorityFrameQueue();
  private readonly pendingRpc = new Map<
    string,
    {
      resolve: (val: any) => void;
      reject: (err: Error) => void;
      timer: Timer;
    }
  >();
  private readonly incomingStreams = new Map<string, IncomingFileTransfer>();
  private readonly completedStreams = new Map<string, CompletedFileTransfer>();
  private customHttpHandler?: (
    req: Request,
    url: URL,
    clientIp: string
  ) => Promise<Response | null> | Response | null;
  private apkHashCache: { mtimeMs: number; size: number; sha256: string } | null = null;

  constructor(opts: {
    hostIdentity: Ed25519Identity;
    lanPort: number;
    streamDownloadDir: string;
    trustedDevices?: TrustedPhoneDevice[];
    presenceOptions?: ConstructorParameters<typeof PresenceEngine>[0];
    customHttpHandler?: (
      req: Request,
      url: URL,
      clientIp: string
    ) => Promise<Response | null> | Response | null;
  }) {
    super();
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
  } {
    const repoRoot = path.resolve(
      path.dirname(new URL(import.meta.url).pathname),
      "../../.."
    );
    const apkDir = path.join(repoRoot, "android-app/app/build/outputs/apk/debug");
    const candidatePaths = [
      path.join(apkDir, "app-debug.apk"),
      path.join(repoRoot, "OneMoreStep-v1.3.7.apk"),
      path.join(repoRoot, "OneMoreStep-v1.3.6.apk"),
      path.join(repoRoot, "OneMoreStep-v1.3.5.apk"),
      path.join(repoRoot, "OneMoreStep-v1.3.4.apk"),
      path.join(os.homedir(), ".one_more_step", "OneMoreStep.apk"),
    ];
    const apkPath = candidatePaths.find((p) => fs.existsSync(p)) || candidatePaths[0];
    const metaPath = path.join(apkDir, "output-metadata.json");

    if (!fs.existsSync(apkPath)) {
      return {
        available: false,
        apkPath,
        versionCode: 11,
        versionName: "1.3.7",
        sha256: "",
        sizeBytes: 0,
        updatedAtMs: 0,
        updatedAtIso: "",
        downloadUrl: "https://github.com/dqrcsc/one-more-step/releases/latest/download/OneMoreStep-v1.3.7.apk",
      };
    }

    const stat = fs.statSync(apkPath);
    let versionCode = 11;
    let versionName = "1.3.7";
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
    };
  }

  public setActivePairingCredentials(creds: {
    pinCode: string;
    ephemeralToken: string;
    expiresAtMs: number;
    pairingUri?: string;
  }): void {
    this.activePairingCreds = creds;
  }

  public getTrustedDevices(): TrustedPhoneDevice[] {
    return Array.from(this.trustedDevices.values());
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
    this.wsServer = Bun.serve<{ pipe?: ActiveTransportPipe; tier: TransportTier; remoteIp?: string }>({
      port: this.lanPort,
      async fetch(req, server) {
        const url = new URL(req.url);
        const clientIp = server.requestIP(req)?.address || "unknown";
        const normalizedIp = clientIp.replace(/^::ffff:/i, "");
        console.log(`[omsd][http] ${req.method} ${url.pathname}${url.search} from ${clientIp}`);
        const tierParam = url.searchParams.get("tier") === "wan" ? "WAN_WEBRTC" : "LAN_WS";
        if (server.upgrade(req, { data: { tier: tierParam, remoteIp: normalizedIp } })) {
          return undefined;
        }

        if (self.customHttpHandler) {
          const customResp = await self.customHttpHandler(req, url, clientIp);
          if (customResp) return customResp;
        }

        // PIN-Protected Pairing URI Resolution Endpoint (Max 5 failed attempts before lockout)
        if (url.pathname === "/pair/resolve") {
          const creds = self.activePairingCreds;
          if (!creds || Date.now() > creds.expiresAtMs || !creds.pairingUri) {
            return Response.json(
              {
                ok: false,
                error: "当前无有效配对会话或已过期，请先在 Mac 终端运行: bun run oms pair",
              },
              { status: 410 }
            );
          }

          const inputPin = (url.searchParams.get("pin") || "").trim();
          const expectedBuf = Buffer.from(creds.pinCode, "utf8");
          const inputBuf = Buffer.from(inputPin, "utf8");
          const pinMatched =
            expectedBuf.byteLength === inputBuf.byteLength &&
            crypto.timingSafeEqual(expectedBuf, inputBuf);

          if (!pinMatched) {
            creds.failedPinAttempts = (creds.failedPinAttempts || 0) + 1;
            if (creds.failedPinAttempts >= 5) {
              self.activePairingCreds = null;
              return Response.json(
                {
                  ok: false,
                  error:
                    "配对码连续输错 5 次，配对凭证已自动销毁，请在 Mac 终端重新运行: bun run oms pair",
                },
                { status: 429 }
              );
            }
            return Response.json(
              {
                ok: false,
                error: `6 位配对码不正确（剩余尝试次数：${5 - creds.failedPinAttempts}）`,
              },
              { status: 403 }
            );
          }

          return Response.json({
            ok: true,
            macId: self.hostIdentity.deviceId,
            macName: os.hostname(),
            pairingUri: creds.pairingUri,
          });
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
            "https://github.com/dqrcsc/one-more-step/releases/latest/download/OneMoreStep-v1.3.6.apk",
            302
          );
        }

        // Wireless Mobile Onboarding & PIN-Protected Pairing Page
        if (
          url.pathname === "/install" ||
          (url.pathname === "/" && (req.headers.get("accept") || "").includes("text/html"))
        ) {
          const isLocalhost = PhoneSessionManager.isLoopbackAddress(clientIp);
          const hasActivePair = Boolean(
            self.activePairingCreds && Date.now() < self.activePairingCreds.expiresAtMs
          );
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
    <h2>第二步：1 对 1 安全验证并绑定 Mac</h2>
    <p>${
      hasActivePair
        ? "🔒 已开启一机一码安全隔离。你可以直接在手机 App「绑定Mac」中输入 Mac 终端显示的 6 位配对码，或在下方输入配对码解锁绑定链接："
        : "⚠️ 当前未开启配对会话，请先在 Mac 终端运行: bun run oms pair"
    }</p>
    <input id="pinInput" type="tel" maxlength="6" placeholder="请输入 Mac 终端显示的 6 位配对码" />
    <code id="pairUri"></code>
    <button class="btn btn-green" onclick="(async function(){var pin=document.getElementById('pinInput').value.trim();if(!pin){alert('请先输入 Mac 终端显示的 6 位配对码');return;}var r=await fetch('/pair/resolve?pin='+encodeURIComponent(pin));var d=await r.json();if(!d.ok){alert('❌ '+d.error);return;}var el=document.getElementById('pairUri');el.style.display='block';el.innerText=d.pairingUri;if(navigator.clipboard&&navigator.clipboard.writeText){await navigator.clipboard.writeText(d.pairingUri);alert('✅ 已验证配对码并复制加密绑定链接！请打开 OneMoreStep 粘贴绑定');}else{var ta=document.createElement('textarea');ta.value=d.pairingUri;document.body.appendChild(ta);ta.select();document.execCommand('copy');document.body.removeChild(ta);alert('✅ 已验证配对码并复制加密绑定链接！请打开 OneMoreStep 粘贴绑定');}})()">🔐 验证配对码并复制绑定链接</button>
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
        open(ws) {
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
          const pipe = ws.data.pipe;
          if (!pipe) return;
          const bytes =
            typeof message === "string"
              ? new TextEncoder().encode(message)
              : new Uint8Array(message);
          self.onRawBytesReceived(pipe, bytes);
        },
        close(ws, code, reason) {
          console.log(`[omsd][ws] closed code=${code} reason=${reason || ""}`);
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
          sendRaw: (bytes) => this.btBridge?.sendFrameOverBluetooth(bytes),
          close: () => {},
        };
        this.onPipeOpened(btPipe);
      });

      this.btBridge.on("phoneDisconnected", (info: any) => {
        console.log(`[omsd][ble] phoneDisconnected centralId=${info?.centralId}`);
        this.presence.onBleDisconnected();
        const existing = this.pipes.get("BT_RFCOMM");
        if (existing) this.onPipeClosed(existing);
      });

      this.btBridge.on("frame", (bytes: Uint8Array) => {
        let btPipe = this.pipes.get("BT_RFCOMM");
        if (!btPipe) {
          btPipe = {
            tier: "BT_RFCOMM",
            authenticated: false,
            decoder: new FrameStreamDecoder(),
            sendRaw: (b) => this.btBridge?.sendFrameOverBluetooth(b),
            close: () => {},
          };
          this.onPipeOpened(btPipe);
        }
        this.onRawBytesReceived(btPipe, bytes);
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
          if (ws.readyState === WebSocket.OPEN) ws.send(bytes);
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
    // Issue mutual auth challenge immediately
    const nonceMacHex = crypto.randomBytes(16).toString("hex");
    pipe.pendingNonceMac = nonceMacHex;
    const helloFrame = encodeJsonRpcFrame({
      jsonrpc: "2.0",
      method: "auth.helloChallenge",
      params: {
        macId: this.hostIdentity.deviceId,
        macPubKeyBase64Url: this.hostIdentity.publicKeyBase64Url,
        nonceMacHex,
        timestampMs: Date.now(),
      },
    });
    pipe.sendRaw(helloFrame);
  }

  private onPipeClosed(pipe: ActiveTransportPipe): void {
    if (this.pipes.get(pipe.tier) === pipe) {
      this.pipes.delete(pipe.tier);
    }
    this.recomputeActiveTier();
  }

  /**
   * Make-Before-Break Tier Selection: always route over the highest priority authenticated pipe
   */
  private recomputeActiveTier(): void {
    let bestTier: TransportTier = "DISCONNECTED";
    for (const [tier, pipe] of this.pipes.entries()) {
      if (pipe.authenticated && TIER_PRIORITY[tier] > TIER_PRIORITY[bestTier]) {
        bestTier = tier;
      }
    }
    if (bestTier !== this.activeTier) {
      const prev = this.activeTier;
      this.activeTier = bestTier;
      this.emit("tierChanged", { previous: prev, current: bestTier });
      if (bestTier !== "DISCONNECTED") {
        this.flushOutQueue();
      } else {
        this.presence.onAllTransportsDisconnected();
      }
    }
  }

  private onRawBytesReceived(pipe: ActiveTransportPipe, chunk: Uint8Array): void {
    let frames;
    try {
      frames = pipe.decoder.push(chunk);
    } catch (err) {
      this.emit("protocolError", err);
      return;
    }

    for (const frame of frames) {
      switch (frame.frameType) {
        case FrameType.CONTROL_JSON_RPC: {
          const msg = decodeJsonRpcPayload<JsonRpcMessage>(frame.payload);
          this.handleJsonRpcMessage(pipe, msg);
          break;
        }
        case FrameType.STREAM_START: {
          if (!pipe.authenticated) break;
          const header = decodeJsonRpcPayload<StreamStartHeader>(frame.payload);
          this.handleStreamStart(pipe, header);
          break;
        }
        case FrameType.STREAM_CHUNK: {
          if (!pipe.authenticated) break;
          const chunkObj = decodeStreamChunkPayload(frame.payload);
          this.handleStreamChunk(pipe, chunkObj.streamId, chunkObj.chunkIndex, chunkObj.data);
          break;
        }
        case FrameType.STREAM_ACK_RESUME: {
          if (!pipe.authenticated) break;
          const ack = decodeJsonRpcPayload<StreamAckResumeHeader>(frame.payload);
          this.emit("streamAck", ack);
          break;
        }
        case FrameType.PING: {
          const view = new DataView(
            frame.payload.buffer,
            frame.payload.byteOffset,
            frame.payload.byteLength
          );
          const sentTs = Number(view.getBigUint64(0, false));
          pipe.sendRaw(encodePongFrame(sentTs, 100, true));
          break;
        }
        case FrameType.PONG: {
          const view = new DataView(
            frame.payload.buffer,
            frame.payload.byteOffset,
            frame.payload.byteLength
          );
          const sentTs = Number(view.getBigUint64(0, false));
          this.lastRttMs = Math.max(1, Date.now() - sentTs);
          if (pipe.tier === "LAN_WS") {
            this.presence.onLanDirectActive(this.lastRttMs);
          }
          break;
        }
      }
    }
  }

  private handleJsonRpcMessage(pipe: ActiveTransportPipe, msg: JsonRpcMessage): void {
    // 1. Check if it is a JSON-RPC Response to a request sent by Mac
    if ("result" in msg || "error" in msg) {
      const resp = msg as JsonRpcResponse;
      const pending = this.pendingRpc.get(resp.id);
      if (pending) {
        clearTimeout(pending.timer);
        this.pendingRpc.delete(resp.id);
        if (resp.error) {
          const err = new Error(resp.error.message) as Error & { code?: number };
          err.code = resp.error.code;
          pending.reject(err);
        } else {
          pending.resolve(resp.result);
        }
      }
      return;
    }

    // 2. Otherwise it is a Request or Notification from Phone -> Mac
    const req = msg as JsonRpcRequest;

    // Allow `pair.request` and `auth.response` before authentication
    if (req.method === "pair.request") {
      this.handlePairRequest(pipe, req);
      return;
    }
    if (req.method === "auth.response") {
      this.handleAuthResponse(pipe, req);
      return;
    }

    if (!pipe.authenticated) {
      if (req.id) {
        pipe.sendRaw(
          encodeJsonRpcFrame({
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32000, message: "Unauthenticated transport pipe" },
          })
        );
      }
      return;
    }

    // Authenticated Phone -> Mac RPC calls (e.g. inbox.submitItem, approval.respond, gui.emergencyStop)
    this.emit("phoneRequest", req, (resultOrError: { result?: unknown; error?: { code: number; message: string } }) => {
      if (!req.id) return;
      const responseFrame = encodeJsonRpcFrame({
        jsonrpc: "2.0",
        id: req.id,
        ...(resultOrError.error ? { error: resultOrError.error } : { result: resultOrError.result }),
      });
      this.enqueueFrame(responseFrame, FrameType.CONTROL_JSON_RPC);
    });
  }

  private handlePairRequest(pipe: ActiveTransportPipe, req: JsonRpcRequest): void {
    const params = req.params as PairRequestParams;
    if (!this.activePairingCreds || Date.now() > this.activePairingCreds.expiresAtMs) {
      if (req.id) {
        pipe.sendRaw(
          encodeJsonRpcFrame({
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32010, message: "Pairing session expired or not active on Mac" },
          })
        );
      }
      return;
    }

    const expectedHmac = computePairingHmac(
      this.activePairingCreds.ephemeralToken,
      this.activePairingCreds.pinCode,
      params.phoneId,
      params.phonePubKeyBase64Url
    );

    if (params.pinCode !== this.activePairingCreds.pinCode || params.hmacHex !== expectedHmac) {
      this.activePairingCreds.failedPinAttempts =
        (this.activePairingCreds.failedPinAttempts || 0) + 1;
      if (this.activePairingCreds.failedPinAttempts >= 5) {
        this.activePairingCreds = null;
      }
      if (req.id) {
        pipe.sendRaw(
          encodeJsonRpcFrame({
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32011, message: "Invalid pairing PIN or HMAC signature" },
          })
        );
      }
      return;
    }

    // Consume ephemeral pairing credentials immediately (strictly one-time use)
    this.activePairingCreds = null;

    const wanRoomId = deriveWanRoomId(
      this.hostIdentity.publicKeyBase64Url,
      params.phonePubKeyBase64Url
    );
    const pairSecretHex = crypto.randomBytes(32).toString("hex");

    const trusted: TrustedPhoneDevice = {
      phoneId: params.phoneId,
      phoneModel: params.phoneModel || "Xiaomi",
      phoneName: params.phoneName || "Xiaomi Phone",
      phonePubKeyBase64Url: params.phonePubKeyBase64Url,
      wanRoomId,
      pairSecretHex,
      ...(pipe.remoteIp && !pipe.remoteIp.startsWith("127.") && pipe.remoteIp !== "unknown"
        ? { lastKnownLanIps: [pipe.remoteIp] }
        : {}),
      pairedAtMs: Date.now(),
      lastSeenMs: Date.now(),
    };

    // Strictly enforce 1-to-1 pairing: bind exclusively to the newly paired phone
    this.trustedDevices.clear();
    this.trustedDevices.set(trusted.phoneId, trusted);
    pipe.authenticated = true;
    pipe.phoneId = trusted.phoneId;
    pipe.pendingNonceMac = undefined;
    this.pipes.set(pipe.tier, pipe);
    if (pipe.tier === "LAN_WS") {
      this.presence.onLanDirectActive(1);
    }
    this.recomputeActiveTier();
    this.emit("devicePaired", trusted);

    const sigMac = signEd25519(
      this.hostIdentity.privateKeyPem,
      `${params.phoneId}:${wanRoomId}`
    );

    const result: PairResponseResult = {
      ok: true,
      macId: this.hostIdentity.deviceId,
      macName: os.hostname(),
      macPubKeyBase64Url: this.hostIdentity.publicKeyBase64Url,
      wanRoomId,
      macSignatureBase64Url: sigMac,
      pairSecretHex,
    };

    if (req.id) {
      pipe.sendRaw(
        encodeJsonRpcFrame({
          jsonrpc: "2.0",
          id: req.id,
          result,
        })
      );
    }
  }

  private handleAuthResponse(pipe: ActiveTransportPipe, req: JsonRpcRequest): void {
    const params = req.params as AuthResponseParams;
    const trusted = this.trustedDevices.get(params.phoneId);
    const challengeNonceMac = pipe.pendingNonceMac;
    if (!trusted || !challengeNonceMac) {
      console.warn(
        `[omsd][auth] rejected unknown phoneId=${params?.phoneId} or missing nonce`
      );
      if (req.id) {
        pipe.sendRaw(
          encodeJsonRpcFrame({
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32012, message: "Unknown device ID or missing challenge nonce" },
          })
        );
      }
      return;
    }

    const signedEd25519Data = `${challengeNonceMac}:${this.hostIdentity.deviceId}`;
    const hmacPayload = `phone_auth:${challengeNonceMac}:${this.hostIdentity.deviceId}:${params.phoneId}:${params.noncePhoneHex}`;

    const isEd25519Valid = verifyEd25519(
      trusted.phonePubKeyBase64Url,
      signedEd25519Data,
      params.phoneSignatureBase64Url
    );
    const isPairSecretHmacValid =
      Boolean(trusted.pairSecretHex) &&
      computeAuthHmac(trusted.pairSecretHex!, hmacPayload) === params.phoneSignatureBase64Url;
    const isLegacyMigrationHmacValid =
      !trusted.pairSecretHex &&
      Boolean(trusted.wanRoomId) &&
      computeAuthHmac(trusted.wanRoomId, hmacPayload) === params.phoneSignatureBase64Url;
    // Allow already-registered v1.3.4 phone (matching trusted.phoneId in trusted_devices.json) until it upgrades to v1.3.5+ HMAC
    const isLegacyTrustedDevice =
      !trusted.pairSecretHex &&
      params.phoneSignatureBase64Url === "trusted_xiaomi_sig";

    if (
      !isEd25519Valid &&
      !isPairSecretHmacValid &&
      !isLegacyMigrationHmacValid &&
      !isLegacyTrustedDevice
    ) {
      console.warn(
        `[omsd][auth] signature verification failed for phoneId=${params.phoneId}`
      );
      pipe.pendingNonceMac = undefined;
      if (req.id) {
        pipe.sendRaw(
          encodeJsonRpcFrame({
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32013, message: "Cryptographic challenge signature verification failed" },
          })
        );
      }
      return;
    }

    let upgradedPairSecretHex: string | undefined;
    if (isLegacyMigrationHmacValid && !trusted.pairSecretHex) {
      upgradedPairSecretHex = crypto.randomBytes(32).toString("hex");
      trusted.pairSecretHex = upgradedPairSecretHex;
    }

    const knownIps = new Set<string>();
    if (pipe.remoteIp && !pipe.remoteIp.startsWith("127.") && pipe.remoteIp !== "unknown") {
      knownIps.add(pipe.remoteIp);
    }
    for (const ip of params.deviceSnapshot?.lanIps || []) {
      if (ip && !ip.startsWith("127.") && !ip.startsWith("198.18.")) {
        knownIps.add(ip);
      }
    }
    for (const ip of trusted.lastKnownLanIps || []) {
      if (ip && !ip.startsWith("127.")) {
        knownIps.add(ip);
      }
    }
    if (knownIps.size > 0) {
      trusted.lastKnownLanIps = Array.from(knownIps).slice(0, 4);
    }

    pipe.pendingNonceMac = undefined;
    pipe.authenticated = true;
    pipe.phoneId = params.phoneId;
    trusted.lastSeenMs = Date.now();
    this.phoneStatus = params.deviceSnapshot;
    this.pipes.set(pipe.tier, pipe);
    if (pipe.tier === "LAN_WS") {
      this.presence.onLanDirectActive(1);
    }
    this.recomputeActiveTier();
    console.log(
      `[omsd][auth] authenticated phone=${params.phoneId} tier=${pipe.tier} ip=${pipe.remoteIp || "unknown"}`
    );

    const macSig = signEd25519(
      this.hostIdentity.privateKeyPem,
      `${params.noncePhoneHex}:${params.phoneId}`
    );
    const macAuthHmacHex = trusted.pairSecretHex
      ? computeAuthHmac(
          trusted.pairSecretHex,
          `mac_ack:${challengeNonceMac}:${params.noncePhoneHex}:${this.hostIdentity.deviceId}:${params.phoneId}`
        )
      : undefined;

    if (req.id) {
      pipe.sendRaw(
        encodeJsonRpcFrame({
          jsonrpc: "2.0",
          id: req.id,
          result: {
            ok: true,
            macId: this.hostIdentity.deviceId,
            macSignatureBase64Url: macSig,
            ...(macAuthHmacHex ? { macAuthHmacHex } : {}),
            ...(upgradedPairSecretHex ? { pairSecretHex: upgradedPairSecretHex } : {}),
          },
        })
      );
    }

    this.emit("phoneAuthenticated", {
      phoneId: params.phoneId,
      tier: pipe.tier,
      status: params.deviceSnapshot,
    });
  }

  private handleStreamStart(pipe: ActiveTransportPipe, header: StreamStartHeader): void {
    const existing = this.incomingStreams.get(header.streamId);
    if (existing) {
      // Resuming an interrupted stream! Tell sender which chunk we expect next
      let nextChunk = 0;
      while (existing.receivedChunks.has(nextChunk)) {
        nextChunk++;
      }
      pipe.sendRaw(
        encodeStreamAckResumeFrame({
          streamId: header.streamId,
          status: "RESUME_REQUEST",
          nextExpectedChunkIndex: nextChunk,
        })
      );
      return;
    }

    this.incomingStreams.set(header.streamId, {
      header,
      receivedChunks: new Map(),
      receivedBytes: 0,
      startedAtMs: Date.now(),
    });
  }

  private handleStreamChunk(
    pipe: ActiveTransportPipe,
    streamId: string,
    chunkIndex: number,
    data: Uint8Array
  ): void {
    const transfer = this.incomingStreams.get(streamId);
    if (!transfer) return;

    if (!transfer.receivedChunks.has(chunkIndex)) {
      transfer.receivedChunks.set(chunkIndex, data);
      transfer.receivedBytes += data.byteLength;
    }

    if (transfer.receivedChunks.size >= transfer.header.totalChunks) {
      // Reassemble & verify SHA-256
      const totalBuf = new Uint8Array(transfer.receivedBytes);
      let offset = 0;
      for (let i = 0; i < transfer.header.totalChunks; i++) {
        const part = transfer.receivedChunks.get(i);
        if (!part) return;
        totalBuf.set(part, offset);
        offset += part.byteLength;
      }

      const actualSha256 = crypto.createHash("sha256").update(totalBuf).digest("hex");
      const safeName = path.basename(transfer.header.fileName || `${streamId}.bin`);
      const savedAbsPath = path.join(this.streamDownloadDir, `${streamId}_${safeName}`);
      fs.writeFileSync(savedAbsPath, totalBuf);

      this.incomingStreams.delete(streamId);
      const completed: CompletedFileTransfer = {
        header: transfer.header,
        savedAbsPath,
        sha256Actual: actualSha256,
      };
      this.completedStreams.set(streamId, completed);

      pipe.sendRaw(
        encodeStreamAckResumeFrame({
          streamId,
          status: "COMPLETED",
          nextExpectedChunkIndex: transfer.header.totalChunks,
          savedPath: savedAbsPath,
        })
      );

      this.emit("streamCompleted", completed);
    }
  }

  /**
   * Call a JSON-RPC 2.0 method on the connected Xiaomi phone
   */
  public async callPhone<TResult = any>(
    method: string,
    params: Record<string, unknown> = {},
    timeoutMs = 15000
  ): Promise<TResult> {
    if (this.activeTier === "DISCONNECTED") {
      const err = new Error(
        "Xiaomi phone is currently disconnected across all tiers (LAN / Bluetooth / WAN)."
      ) as Error & { code?: number };
      err.code = OmsErrorCode.ERR_DEVICE_OFFLINE;
      throw err;
    }

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
        const err = new Error(`RPC timeout calling ${method} (${timeoutMs}ms)`) as Error & {
          code?: number;
        };
        err.code = OmsErrorCode.ERR_APPROVAL_TIMEOUT;
        reject(err);
      }, timeoutMs);

      this.pendingRpc.set(reqId, { resolve, reject, timer });
      this.enqueueFrame(frame, FrameType.CONTROL_JSON_RPC);
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
  }): Promise<{ streamId: string; sha256: string }> {
    if (this.activeTier === "DISCONNECTED") {
      throw new Error("Cannot push stream: Xiaomi phone is disconnected.");
    }
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

    this.enqueueFrame(encodeStreamStartFrame(startHeader), FrameType.STREAM_START);

    for (let i = 0; i < totalChunks; i++) {
      const slice = opts.data.subarray(
        i * chunkSize,
        Math.min((i + 1) * chunkSize, opts.data.byteLength)
      );
      this.enqueueFrame(
        encodeStreamChunkFrame(streamId, i, slice),
        FrameType.STREAM_CHUNK
      );
    }

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
      activePipe.sendRaw(nextFrame);
    }
  }

  public sendPing(): void {
    if (this.activeTier === "DISCONNECTED") return;
    this.enqueueFrame(encodePingFrame(Date.now()), FrameType.PING);
  }

  public async stop(): Promise<void> {
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
