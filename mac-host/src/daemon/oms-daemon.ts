import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import net from "node:net";
import { execFile, execFileSync, spawn } from "node:child_process";
import {
  ApprovalRespondParams,
  buildPairingUri,
  createEphemeralPairingCredentials,
  Ed25519Identity,
  generateEd25519Identity,
  generateUuidV7,
  InboxSubmitItemParams,
  IntentTag,
  JsonRpcRequest,
  OMS_BLE_SERVICE_SHORT_UUID,
  OMS_DEFAULT_LAN_PORT,
  OmsErrorCode,
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
  dingtalk: DingTalkConfig;
}

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
    }
  >();

  constructor(customHomeDir?: string) {
    this.homeDir = customHomeDir || path.join(os.homedir(), ".one_more_step");
    fs.mkdirSync(path.join(this.homeDir, "keystore"), { recursive: true });
    fs.mkdirSync(path.join(this.homeDir, "run"), { recursive: true });
    fs.mkdirSync(path.join(this.homeDir, "logs"), { recursive: true });

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
      trustedDevices: trusted,
      customHttpHandler: (req, url, clientIp) =>
        handleDashboardHttpRequest(this, req, url, clientIp),
    });

    this.wireSessionEvents();
  }

  private loadOrInitConfig(): OmsHostConfig {
    if (fs.existsSync(this.configPath)) {
      try {
        const parsed = JSON.parse(fs.readFileSync(this.configPath, "utf8")) as OmsHostConfig;
        return {
          autoCopyTextToClipboard: true,
          autoPreviewImageOnMac: true,
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
      dingtalk: { enabled: false },
    };
    fs.writeFileSync(this.configPath, JSON.stringify(defaultCfg, null, 2), "utf8");
    return defaultCfg;
  }

  public getHostPreferences(): {
    autoCopyTextToClipboard: boolean;
    autoPreviewImageOnMac: boolean;
  } {
    return {
      autoCopyTextToClipboard: this.config.autoCopyTextToClipboard !== false,
      autoPreviewImageOnMac: this.config.autoPreviewImageOnMac !== false,
    };
  }

  public updateHostPreferences(prefs: {
    autoCopyTextToClipboard?: boolean;
    autoPreviewImageOnMac?: boolean;
  }): {
    autoCopyTextToClipboard: boolean;
    autoPreviewImageOnMac: boolean;
  } {
    if (typeof prefs.autoCopyTextToClipboard === "boolean") {
      this.config.autoCopyTextToClipboard = prefs.autoCopyTextToClipboard;
    }
    if (typeof prefs.autoPreviewImageOnMac === "boolean") {
      this.config.autoPreviewImageOnMac = prefs.autoPreviewImageOnMac;
    }
    fs.writeFileSync(this.configPath, JSON.stringify(this.config, null, 2), "utf8");
    return this.getHostPreferences();
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

  private saveTrustedDevices(): void {
    fs.writeFileSync(
      this.trustedDevicesPath,
      JSON.stringify(this.session.getTrustedDevices(), null, 2),
      { encoding: "utf8", mode: 0o600 }
    );
  }

  private wireSessionEvents(): void {
    this.session.on("devicePaired", () => {
      this.saveTrustedDevices();
    });

    this.session.on("phoneAuthenticated", () => {
      this.saveTrustedDevices();
    });

    this.session.on(
      "phoneRequest",
      (
        req: JsonRpcRequest,
        reply: (res: { result?: unknown; error?: { code: number; message: string } }) => void
      ) => {
        try {
          if (req.method === "inbox.submitItem") {
            const params = req.params as InboxSubmitItemParams;
            const stored = this.inbox.storeSubmittedItem(params, (streamId) =>
              this.session.getCompletedStream(streamId)
            );

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
            reply({
              result: {
                ack: true,
                savedId: stored.itemId,
                macPath: stored.files[0]?.absPath ?? this.inbox.inboxRootDir,
              },
            });
          } else if (req.method === "approval.respond") {
            const params = req.params as ApprovalRespondParams;
            const waiter = this.pendingApprovals.get(params.approvalId);
            if (waiter) {
              clearTimeout(waiter.timer);
              this.pendingApprovals.delete(params.approvalId);
              waiter.resolve(params);
            }
            reply({ result: { ack: true } });
          } else if (req.method === "gui.emergencyStop") {
            this.showMacDesktopNotification(
              "🛑 手机端已触发 AI 急停",
              "你在小米手机上点击了急停胶囊，GUI 自动化已立即中止。"
            );
            reply({ result: { ack: true } });
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

  private showMacDesktopNotification(title: string, message: string): void {
    if (process.env.OMS_DISABLE_OSASCRIPT === "1") return;
    const safeTitle = title.replace(/"/g, '\\"');
    const safeMsg = message.replace(/"/g, '\\"').slice(0, 160);
    execFile("osascript", [
      "-e",
      `display notification "${safeMsg}" with title "${safeTitle}"`,
    ]);
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
    try {
      const live = await Promise.race([
        this.session.callPhone<any>("sys.getStatus", {}),
        new Promise<null>((r) => setTimeout(() => r(null), 1200)),
      ]);
      if (live && typeof live === "object") {
        this.session.setPhoneStatus(live);
      }
    } catch {
      // keep cached phoneStatus on transient RPC error
    }
  }

  public async start(opts?: { startTransport?: boolean }): Promise<void> {
    if (opts?.startTransport !== false) {
      const macHostRoot = path.resolve(
        path.dirname(new URL(import.meta.url).pathname),
        "../.."
      );
      const btBin = path.join(macHostRoot, "bin", "oms-bt-bridge");
      const btSrc = path.join(macHostRoot, "bluetooth-bridge", "main.swift");
      if (
        this.config.enableBluetoothBridge &&
        process.platform === "darwin" &&
        !fs.existsSync(btBin) &&
        fs.existsSync(btSrc)
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
        const creds = createEphemeralPairingCredentials(10 * 60 * 1000);
        const ips = PhoneSessionManager.getLocalLanIps();
        const uri = buildPairingUri({
          v: 1,
          id: this.identity.deviceId,
          name: os.hostname(),
          ips,
          port: this.config.lanPort,
          btUuid: OMS_BLE_SERVICE_SHORT_UUID,
          pubKeyBase64Url: this.identity.publicKeyBase64Url,
          pinCode: creds.pinCode,
          ephemeralToken: creds.ephemeralToken,
          expiresAtMs: creds.expiresAtMs,
        });
        this.session.setActivePairingCredentials({ ...creds, pairingUri: uri });
        return {
          pairingUri: uri,
          pinCode: creds.pinCode,
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
          lastRttMs: this.session.getLastRttMs(),
          lanPort: this.config.lanPort,
          dashboardUrl: `http://127.0.0.1:${this.config.lanPort}/inbox`,
          trustedDevices: this.session.getTrustedDevices().map((d) => ({
            phoneId: d.phoneId,
            phoneModel: d.phoneModel,
            lastSeenIso: new Date(d.lastSeenMs).toISOString(),
          })),
          phoneStatus: this.session.getPhoneStatus(),
        };
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
          typeof params.autoPreviewImageOnMac === "boolean"
        ) {
          return this.updateHostPreferences({
            autoCopyTextToClipboard: params.autoCopyTextToClipboard,
            autoPreviewImageOnMac: params.autoPreviewImageOnMac,
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

        // Wait up to 15s for stream assembly completion if not already completed
        let completed = this.session.getCompletedStream(pullMeta.streamId);
        if (!completed) {
          completed = await new Promise((resolve, reject) => {
            const timer = setTimeout(
              () => reject(new Error("Timed out waiting for pulled file stream chunks")),
              15000
            );
            const onDone = (c: any) => {
              if (c.header.streamId === pullMeta.streamId) {
                clearTimeout(timer);
                this.session.off("streamCompleted", onDone);
                resolve(c);
              }
            };
            this.session.on("streamCompleted", onDone);
          });
        }

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
          return await this.session.callPhone("clipboard.get", {
            includeRecentOtp: Boolean(params.includeRecentOtp),
          });
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

        return await this.session.callPhone("notify.send", {
          title,
          body,
          level: effectivePhoneLevel,
          ttsSpeak: Boolean(params.ttsSpeak),
        });
      }

      // Tool 9: phone_request_approval (Blocking Human-in-the-Loop Remote Approval)
      case "phone_request_approval": {
        const approvalId = `apr_${generateUuidV7()}`;
        const timeoutSec = Number(params.timeoutSec ?? 1800);
        const options = params.options ?? ["批准继续", "拒绝并中止"];

        await this.session.callPhone("notify.requestApproval", {
          approvalId,
          title: params.title ?? "需要远程确认",
          summary: params.summary ?? "",
          contextText: params.diffOrContext ?? "",
          options,
          timeoutSec,
        });

        return await new Promise<ApprovalRespondParams>((resolve, reject) => {
          const timer = setTimeout(() => {
            this.pendingApprovals.delete(approvalId);
            const err = new Error(
              `Remote approval timed out after ${timeoutSec}s`
            ) as Error & { code?: number };
            err.code = OmsErrorCode.ERR_APPROVAL_TIMEOUT;
            reject(err);
          }, timeoutSec * 1000);

          this.pendingApprovals.set(approvalId, { resolve, reject, timer });
        });
      }

      // Tool 11: phone_gui_observe
      case "phone_gui_observe": {
        const obs = await this.session.callPhone<{
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
          const comp = this.session.getCompletedStream(obs.screenshotStreamId);
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
          x: params.x,
          y: params.y,
          text: params.text,
          requireUserConfirm: Boolean(params.requireUserConfirm),
        });
      }

      // OTA APK Update Helper
      case "phone_ota_update": {
        const ips = PhoneSessionManager.getLocalLanIps();
        const url = `http://${ips[0]}:${this.config.lanPort}/OneMoreStep.apk?t=${Date.now()}`;
        return await this.session.callPhone("sys.otaUpdate", {
          ip: ips[0],
          port: this.config.lanPort,
          url,
        });
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

          let completed = this.session.getCompletedStream(streamId);
          if (!completed) {
            completed = await new Promise((resolve, reject) => {
              const timer = setTimeout(
                () => reject(new Error("等待手机截屏数据流超时")),
                10000
              );
              const onDone = (c: any) => {
                if (c.header.streamId === streamId) {
                  clearTimeout(timer);
                  this.session.off("streamCompleted", onDone);
                  resolve(c);
                }
              };
              this.session.on("streamCompleted", onDone);
            });
          }

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

        try {
          const res = await this.session.callPhone<any>("sys.control", params);
          if (res?.status) {
            this.session.setPhoneStatus(res.status);
          } else {
            void this.refreshPhoneStatusLive(true);
          }
          return res;
        } catch (err: any) {
          const msg = String(err?.message || "");
          if (!msg.includes("Unknown RPC method")) {
            throw err;
          }
          // Backward compatibility fallback for APK < v1.3.3
          if (subAction === "find_phone") {
            const text = String(params.text || "正在寻找手机，我在这里！");
            return await this.session.callPhone("notify.send", {
              title: "🔔 Mac 正在寻找此手机",
              body: text,
              level: "urgent",
              ttsSpeak: true,
              forceSpeakerAlarmStream: true,
            });
          }
          if (subAction === "tts_speak") {
            const text = String(params.text || "");
            return await this.session.callPhone("notify.send", {
              title: "🗣️ 语音播报",
              body: text,
              level: "silent",
              ttsSpeak: true,
              forceSpeakerAlarmStream: Boolean(params.useAlarmStream),
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
