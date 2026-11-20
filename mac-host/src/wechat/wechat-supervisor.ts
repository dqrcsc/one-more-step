import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcess } from "node:child_process";
import type {
  WeChatArticleAssetParams,
  WeChatArticleAssetResult,
  WeChatArticleDetailParams,
  WeChatArticleDetailResult,
  WeChatArticleListItem,
  WeChatArticlesListResult,
} from "@oms/shared-protocol";

export interface ManagedWeChatEvent {
  eventType: "article" | "idea" | "plan" | "schedule";
  title: string;
  summary: string;
  articleId?: number;
  taskId?: number;
  taskKind?: string;
  triage?: string;
  url?: string;
  notePath?: string;
  sourcePath?: string;
  depositedPath?: string;
  files?: string[];
  timestamp: string;
}

export interface WeChatHealthSnapshot {
  enabled: boolean;
  running: boolean;
  pid?: number;
  internalPort: number;
  wechatHomeDir: string;
  credentialsConfigured: boolean;
  lastPoll: string | null;
  pendingMessages: number;
  activeAgentTasksCount: number;
  delivery?: Array<{ status: string; count: number }>;
  lastError?: string;
}

export interface WeChatSupervisorOptions {
  omsHomeDir: string;
  wechatHomeDir?: string;
  serverEntryPath: string;
  internalPort?: number;
  logFilePath: string;
}

const EVENT_LINE_PREFIX = "__OMS_WX_EVENT__:";

/**
 * Supervised Worker Process Manager for WeChat-as-Context (`青简·尺素`) inside `omsd`.
 * Isolates heavy headless Chrome / yt-dlp / FFmpeg / SQLite workloads from `omsd`'s
 * real-time phone screen-mirroring & Bluetooth event loop while keeping a single daemon lifecycle.
 */
export class WeChatBridgeSupervisor extends EventEmitter {
  public readonly wechatHomeDir: string;
  public readonly internalPort: number;
  public readonly serverEntryPath: string;
  public readonly logFilePath: string;

  private readonly omsHomeDir: string;
  private child: ChildProcess | null = null;
  private respawnTimer: ReturnType<typeof setTimeout> | null = null;
  private respawnAttempt = 0;
  private stopped = false;
  private cachedHealth: Omit<WeChatHealthSnapshot, "enabled" | "running" | "pid" | "internalPort" | "wechatHomeDir"> = {
    credentialsConfigured: false,
    lastPoll: null,
    pendingMessages: 0,
    activeAgentTasksCount: 0,
  };
  private lastHealthFetchMs = 0;

  constructor(opts: WeChatSupervisorOptions) {
    super();
    this.omsHomeDir = opts.omsHomeDir;
    this.wechatHomeDir = opts.wechatHomeDir || path.join(opts.omsHomeDir, "wechat");
    this.internalPort = opts.internalPort || 19528;
    this.serverEntryPath = opts.serverEntryPath;
    this.logFilePath = opts.logFilePath;
  }

  public isRunning(): boolean {
    return Boolean(this.child && this.child.exitCode === null && !this.child.killed);
  }

  public start(): void {
    this.stopped = false;
    fs.mkdirSync(this.wechatHomeDir, { recursive: true, mode: 0o700 });
    fs.mkdirSync(path.join(this.wechatHomeDir, "data"), { recursive: true, mode: 0o700 });
    fs.mkdirSync(path.dirname(this.logFilePath), { recursive: true, mode: 0o700 });
    this.rotateLogIfNeeded();
    this.spawnWorker();
  }

  private rotateLogIfNeeded(): void {
    try {
      if (fs.existsSync(this.logFilePath)) {
        const st = fs.statSync(this.logFilePath);
        if (st.size > 5 * 1024 * 1024) {
          fs.renameSync(this.logFilePath, `${this.logFilePath}.1`);
        }
      }
    } catch {
      // ignore
    }
  }

  private appendLog(line: string): void {
    try {
      fs.appendFileSync(this.logFilePath, line.endsWith("\n") ? line : `${line}\n`, "utf8");
    } catch {
      // ignore
    }
  }

  private spawnWorker(): void {
    if (this.stopped || this.child) return;
    if (!fs.existsSync(this.serverEntryPath)) {
      this.cachedHealth.lastError = `Missing server entry: ${this.serverEntryPath}`;
      return;
    }

    const home = os.homedir();
    const safePath = [
      path.join(home, ".local", "bin"),
      path.join(home, ".bun", "bin"),
      "/opt/homebrew/bin",
      "/usr/local/bin",
      "/usr/bin",
      "/bin",
      process.env.PATH || "",
    ]
      .filter(Boolean)
      .join(":");

    const spawnedAt = Date.now();
    const proc = spawn(process.execPath, ["run", this.serverEntryPath], {
      cwd: this.wechatHomeDir,
      env: {
        ...process.env,
        HOME: home,
        PATH: safePath,
        OMS_HOME: this.omsHomeDir,
        OMS_WECHAT_HOME: this.wechatHomeDir,
        OMS_MANAGED_WORKER: "1",
        OMS_PARENT_PID: String(process.pid),
        OMS_WORKER_PORT: String(this.internalPort),
        PORT: String(this.internalPort),
        BUN_CONFIG_DNS_TIME_TO_LIVE_SECONDS: "0",
      },
      stdio: ["ignore", "pipe", "pipe"],
    });

    this.child = proc;
    let stdoutBuf = "";

    proc.stdout?.setEncoding("utf8");
    proc.stdout?.on("data", (chunk: string) => {
      stdoutBuf += chunk;
      let idx: number;
      while ((idx = stdoutBuf.indexOf("\n")) !== -1) {
        const line = stdoutBuf.slice(0, idx);
        stdoutBuf = stdoutBuf.slice(idx + 1);
        const trimmed = line.trim();
        if (trimmed.startsWith(EVENT_LINE_PREFIX)) {
          const rawJson = trimmed.slice(EVENT_LINE_PREFIX.length);
          try {
            const evt = JSON.parse(rawJson) as ManagedWeChatEvent;
            this.emit("wechatEvent", evt);
          } catch {
            // ignore malformed IPC event
          }
        } else if (trimmed) {
          this.appendLog(line);
        }
      }
    });

    proc.stderr?.setEncoding("utf8");
    proc.stderr?.on("data", (chunk: string) => {
      this.appendLog(chunk);
    });

    proc.on("error", (err) => {
      this.cachedHealth.lastError = err.message;
      this.appendLog(`[wechat-supervisor] spawn error: ${err.message}`);
    });

    proc.on("exit", (code, signal) => {
      if (this.child === proc) {
        this.child = null;
      }
      this.emit("statusChanged");
      if (this.stopped) return;
      if (Date.now() - spawnedAt > 20_000) {
        this.respawnAttempt = 0;
      }
      const delayMs = Math.min(15_000, 1200 * Math.pow(2, this.respawnAttempt++));
      this.appendLog(
        `[${new Date().toISOString()}] [wechat-supervisor] worker exited (code=${code}, signal=${signal}), restarting in ${delayMs}ms`
      );
      if (this.respawnTimer) clearTimeout(this.respawnTimer);
      this.respawnTimer = setTimeout(() => {
        this.respawnTimer = null;
        if (!this.stopped) {
          this.spawnWorker();
        }
      }, delayMs);
      (this.respawnTimer as any).unref?.();
    });

    // Refresh health snapshot shortly after boot
    const bootProbe = setTimeout(() => {
      void this.refreshHealth(true).then(() => this.emit("statusChanged"));
    }, 1200);
    (bootProbe as any).unref?.();
  }

  public async refreshHealth(force = false): Promise<WeChatHealthSnapshot> {
    const now = Date.now();
    if (this.isRunning() && (force || now - this.lastHealthFetchMs > 6000)) {
      this.lastHealthFetchMs = now;
      try {
        const res = await fetch(`http://127.0.0.1:${this.internalPort}/health`, {
          headers: { Host: `127.0.0.1:${this.internalPort}` },
          signal: AbortSignal.timeout(1800),
        });
        if (res.ok) {
          const data = (await res.json()) as Record<string, any>;
          this.cachedHealth = {
            credentialsConfigured: Boolean(data.credentialsConfigured),
            lastPoll: typeof data.lastPoll === "string" ? data.lastPoll : null,
            pendingMessages: Number(data.pendingMessages || 0),
            activeAgentTasksCount: Array.isArray(data.activeAgentTasks) ? data.activeAgentTasks.length : 0,
            delivery: Array.isArray(data.delivery) ? data.delivery : undefined,
            lastError: undefined,
          };
        }
      } catch (err: any) {
        this.cachedHealth.lastError = err?.message || String(err);
      }
    } else if (!this.isRunning()) {
      const envExists = fs.existsSync(path.join(this.wechatHomeDir, ".env"));
      const dbExists = fs.existsSync(path.join(this.wechatHomeDir, "data", "wechat_context.db"));
      this.cachedHealth.credentialsConfigured = this.cachedHealth.credentialsConfigured || (envExists && dbExists);
    }

    return this.getCachedSnapshot();
  }

  public getCachedSnapshot(): WeChatHealthSnapshot {
    return {
      enabled: true,
      running: this.isRunning(),
      pid: this.child?.pid,
      internalPort: this.internalPort,
      wechatHomeDir: this.wechatHomeDir,
      ...this.cachedHealth,
    };
  }

  public async proxyLoopbackRequest(req: Request, targetPathname: string, search = ""): Promise<Response> {
    if (!this.isRunning()) {
      return Response.json(
        { ok: false, error: "WeChat Context worker is starting or unavailable." },
        { status: 503 }
      );
    }
    const targetUrl = `http://127.0.0.1:${this.internalPort}${targetPathname}${search}`;
    const headers = new Headers();
    const contentType = req.headers.get("content-type");
    if (contentType) headers.set("Content-Type", contentType);
    headers.set("Host", `127.0.0.1:${this.internalPort}`);

    const method = req.method.toUpperCase();
    const body = method !== "GET" && method !== "HEAD" ? await req.arrayBuffer() : undefined;

    try {
      const upstream = await fetch(targetUrl, {
        method,
        headers,
        body,
        signal: AbortSignal.timeout(65_000),
      });
      const respHeaders = new Headers();
      const upCt = upstream.headers.get("content-type");
      if (upCt) respHeaders.set("Content-Type", upCt);
      const upCsp = upstream.headers.get("content-security-policy");
      if (upCsp) respHeaders.set("Content-Security-Policy", upCsp);
      respHeaders.set("Cache-Control", "no-store");
      respHeaders.set("X-Content-Type-Options", "nosniff");
      respHeaders.set("Cross-Origin-Resource-Policy", "same-origin");
      return new Response(await upstream.arrayBuffer(), {
        status: upstream.status,
        headers: respHeaders,
      });
    } catch (err: any) {
      return Response.json(
        { ok: false, error: `WeChat worker proxy failed: ${err?.message || String(err)}` },
        { status: 502 }
      );
    }
  }

  public async fetchEdgeStandbySnapshot(): Promise<Record<string, any> | null> {
    if (!this.isRunning()) return null;
    try {
      const res = await fetch(`http://127.0.0.1:${this.internalPort}/internal/edge/snapshot`, {
        headers: { Host: `127.0.0.1:${this.internalPort}` },
        signal: AbortSignal.timeout(4000),
      });
      if (!res.ok) return null;
      return (await res.json()) as Record<string, any>;
    } catch {
      return null;
    }
  }

  public async setStandbyPollPaused(paused: boolean): Promise<boolean> {
    if (!this.isRunning()) return false;
    try {
      const res = await fetch(`http://127.0.0.1:${this.internalPort}/internal/edge/pause-poll`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Host: `127.0.0.1:${this.internalPort}`,
        },
        body: JSON.stringify({ paused }),
        signal: AbortSignal.timeout(3000),
      });
      return res.ok;
    } catch {
      return false;
    }
  }

  public async submitStandbyHandoff(payload: unknown): Promise<Record<string, any>> {
    if (!this.isRunning()) {
      throw new Error("WeChat Context worker is not running");
    }
    const res = await fetch(`http://127.0.0.1:${this.internalPort}/internal/edge/handoff`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Host: `127.0.0.1:${this.internalPort}`,
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(45_000),
    });
    const data = (await res.json()) as Record<string, any>;
    if (!res.ok || !data?.ok) {
      throw new Error(data?.error || `Handoff failed with HTTP ${res.status}`);
    }
    return data;
  }

  private classifyArticleMediaKind(url: string, title = ""): "article" | "image_note" | "video" | "idea" {
    const u = String(url || "").toLowerCase();
    const t = String(title || "").toLowerCase();
    if (
      u.includes("youtube.com/") ||
      u.includes("youtu.be/") ||
      u.includes("douyin.com/") ||
      u.includes("bilibili.com/") ||
      u.includes("weixin.qq.com/sph/") ||
      t.includes("视频语音转写")
    ) {
      return "video";
    }
    if (u.includes("xiaohongshu.com/") || u.includes("xhslink.com/")) {
      return "image_note";
    }
    return "article";
  }

  private decodeDepositedPaths(raw: unknown): string[] {
    const text = String(raw || "").trim();
    if (!text) return [];
    if (text.startsWith("[")) {
      try {
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed)) {
          return parsed.map((x) => String(x || "").trim()).filter(Boolean);
        }
      } catch {}
    }
    return [text];
  }

  private cleanTurnReplyForReport(rawReply: string): string {
    const lines = String(rawReply || "").replace(/\r\n/g, "\n").split("\n");
    const kept: string[] = [];
    let skippedHeader = false;
    for (const line of lines) {
      const t = line.trim();
      if (
        !skippedHeader &&
        (t.startsWith("🖼️ 已解密并归档") ||
          t.startsWith("🎬 已归档视频") ||
          t.startsWith("💡 想法已保存") ||
          t.startsWith("📥 已归档") ||
          t.startsWith("📋 内容提炼"))
      ) {
        if (t.startsWith("📋 内容提炼")) skippedHeader = true;
        continue;
      }
      if (kept.length === 0 && t === "") continue;
      kept.push(line);
    }
    return kept.join("\n").trim() || String(rawReply || "").trim();
  }

  private promoteInlineImageAttachments(markdown: string): string {
    if (!markdown) return "";
    const imagePaths: string[] = [];
    const linkRe = /(?<!!)\[([^\]]*)\]\(([^)\n]+\.(?:png|jpe?g|webp|gif|bmp)(?:\?[^)\n]*)?)\)/gi;
    let m: RegExpExecArray | null;
    while ((m = linkRe.exec(markdown)) !== null) {
      const target = String(m[2] || "").trim();
      if (target && !imagePaths.includes(target)) {
        imagePaths.push(target);
      }
    }
    if (imagePaths.length === 0) return markdown;

    const alreadyInline = new Set<string>();
    const imgRe = /!\[[^\]]*\]\(([^)\n]+)\)/g;
    while ((m = imgRe.exec(markdown)) !== null) {
      alreadyInline.add(String(m[1] || "").trim());
    }
    const missing = imagePaths.filter((p) => !alreadyInline.has(p));
    if (missing.length === 0) return markdown;

    const galleryMd = missing
      .map((p, idx) => `![原图附件${missing.length > 1 ? ` ${idx + 1}` : ""}](${p})`)
      .join("\n\n");

    // Inject right after YAML frontmatter (if any) so ChisuDocumentParser keeps the images in the body
    const fmMatch = markdown.match(/^\uFEFF?\s*---\r?\n[\s\S]*?\r?\n---\r?\n*/);
    if (fmMatch) {
      const fmBlock = fmMatch[0];
      const rest = markdown.slice(fmBlock.length);
      return `${fmBlock}${galleryMd}\n\n${rest}`;
    }
    return `${galleryMd}\n\n${markdown}`;
  }

  private extractCleanTurnTitle(
    raw: Record<string, any>,
    mediaKind: "article" | "image_note" | "video" | "idea",
    depositedPath: string
  ): string {
    const reply = String(raw.assistant_reply || "");
    const headingMatch = reply.match(/^(?:#{1,4})\s+(.+)$/m);
    if (headingMatch?.[1]) {
      const h = headingMatch[1].replace(/[*`#]/g, "").trim();
      if (h.length >= 2) return h.slice(0, 72);
    }
    const coreMatch = reply.match(/核心提炼[：:]\s*([^\n]+)/);
    if (coreMatch?.[1]) {
      return coreMatch[1].replace(/[*`]/g, "").trim().slice(0, 72);
    }
    const userText = String(raw.user_text || "").trim();
    if (userText && !userText.startsWith("[多媒体消息:") && !userText.startsWith("[电脑端主动推送:")) {
      const firstLine = userText.split(/\r?\n/)[0]?.trim() || "";
      if (firstLine.length >= 2) return firstLine.slice(0, 64);
    }
    if (depositedPath) {
      const stem = path.basename(depositedPath, path.extname(depositedPath));
      if (stem && !/^20\d{2}_\d{2}_\d{2}_wechat_(media_capture|video_transcript)_/i.test(stem)) {
        return stem.replace(/_/g, " ").slice(0, 64);
      }
    }
    return mediaKind === "video"
      ? "微信视频口播笔记"
      : mediaKind === "image_note"
        ? "微信图片笔记"
        : mediaKind === "idea"
          ? "微信灵感速记"
          : "微信尺素记录";
  }

  private mapRawTurnSummary(raw: Record<string, any>): WeChatArticleListItem | null {
    const turnId = Number(raw.id || 0);
    if (!Number.isFinite(turnId) || turnId <= 0) return null;
    const intent = String(raw.intent || "").trim().toLowerCase();
    if (intent !== "multimodal_ingest" && intent !== "capture_idea" && intent !== "capture_inbox") {
      return null;
    }
    const paths = this.decodeDepositedPaths(raw.deposited_path);
    const mdPath = paths.find((p) => /\.(md|markdown|txt)$/i.test(p)) || paths[0] || "";
    const mediaSummary = String(raw.media_summary || "").trim();
    const userText = String(raw.user_text || "").trim();
    const assistantReply = String(raw.assistant_reply || "").trim();
    if (!mdPath && !assistantReply) return null;

    let mediaKind: "article" | "image_note" | "video" | "idea" = "idea";
    if (intent === "capture_idea") {
      mediaKind = "idea";
    } else if (
      /video/i.test(mediaSummary) ||
      /\[多媒体消息:\s*video/i.test(userText) ||
      mdPath.includes("/01_inputs/video/") ||
      mdPath.includes("video_transcript")
    ) {
      mediaKind = "video";
    } else if (
      /image|photo|jpg|jpeg|png|webp/i.test(mediaSummary) ||
      /\[多媒体消息:\s*image/i.test(userText) ||
      mdPath.includes("media_capture")
    ) {
      mediaKind = "image_note";
    }

    const cleanReport = this.cleanTurnReplyForReport(assistantReply);
    const authorLabel =
      mediaKind === "video"
        ? "微信视频"
        : mediaKind === "image_note"
          ? "微信图片笔记"
          : intent === "capture_idea"
            ? "个人灵感"
            : "微信速记";
    const userNotes =
      userText && !userText.startsWith("[多媒体消息:") && intent === "multimodal_ingest"
        ? userText.slice(0, 140)
        : "";

    return {
      id: 0,
      entryKind: "turn",
      turnId,
      mediaKind,
      mediaSummary,
      title: this.extractCleanTurnTitle(raw, mediaKind, mdPath),
      author: authorLabel,
      url: "",
      publishDate: String(raw.created_at || "").slice(0, 10),
      createdAt: String(raw.created_at || "").trim(),
      status: intent,
      processLevel: cleanReport ? "light" : "raw",
      triage: mediaKind === "idea" ? "process_now" : "reference",
      userNotes,
      shortSummary: cleanReport.slice(0, 320),
      digest: userText && !userText.startsWith("[多媒体消息:") ? userText.slice(0, 220) : "",
      hasNote: Boolean(cleanReport),
      hasSource: Boolean(mdPath && fs.existsSync(mdPath)),
      isFallbackNote: false,
    };
  }

  private mapRawArticleSummary(raw: Record<string, any>): WeChatArticleListItem {
    const notePath = typeof raw.note_md_path === "string" ? raw.note_md_path.trim() : "";
    const sourcePath = typeof raw.source_md_path === "string" ? raw.source_md_path.trim() : "";
    const url = String(raw.url || "").trim();
    const title = String(raw.title || "").trim();
    return {
      id: Number(raw.id || 0),
      entryKind: "article",
      mediaKind: this.classifyArticleMediaKind(url, title),
      title,
      author: String(raw.author || "").trim(),
      url,
      publishDate: String(raw.publish_date || "").trim(),
      createdAt: String(raw.created_at || "").trim(),
      status: String(raw.status || "").trim(),
      processLevel: String(raw.process_level || "").trim(),
      triage: String(raw.triage || "").trim(),
      userNotes: String(raw.user_notes || "").trim(),
      shortSummary: String(raw.short_summary || "").trim(),
      digest: String(raw.digest || "").trim(),
      hasNote: Boolean(notePath && fs.existsSync(notePath)),
      hasSource: Boolean(sourcePath && fs.existsSync(sourcePath)),
      isFallbackNote: Boolean(raw.isFallbackNote),
    };
  }

  private normalizeMatchTitle(raw: string): string {
    return String(raw || "")
      .replace(/^💬\s*/, "")
      .replace(/[（(]初筛伴读笔记[)）]\s*$/, "")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }

  public async fetchArticlesOverview(limit = 80): Promise<WeChatArticlesListResult> {
    if (!this.isRunning()) {
      return { ok: false, articles: [], error: "WeChat Context worker is not running" };
    }
    const clampedLimit = Math.min(200, Math.max(1, Math.floor(Number(limit) || 80)));
    try {
      const res = await fetch(`http://127.0.0.1:${this.internalPort}/api/overview?limit=${clampedLimit}`, {
        headers: { Host: `127.0.0.1:${this.internalPort}` },
        signal: AbortSignal.timeout(5000),
      });
      if (!res.ok) {
        return { ok: false, articles: [], error: `WeChat overview HTTP ${res.status}` };
      }
      const data = (await res.json()) as Record<string, any>;
      const rawList = Array.isArray(data.articles) ? data.articles : [];
      const rawTurns = Array.isArray(data.turns) ? data.turns : [];

      const mappedArticles = rawList
        .map((item: Record<string, any>) => this.mapRawArticleSummary(item))
        .filter((item: WeChatArticleListItem) => item.id > 0);
      const mappedTurns = rawTurns
        .map((t: Record<string, any>) => this.mapRawTurnSummary(t))
        .filter((item: WeChatArticleListItem | null): item is WeChatArticleListItem => item !== null);

      const merged = [...mappedArticles, ...mappedTurns].sort((a, b) => {
        const ta = Date.parse(a.createdAt || "") || 0;
        const tb = Date.parse(b.createdAt || "") || 0;
        return tb - ta;
      });

      return {
        ok: true,
        articles: merged,
      };
    } catch (err: any) {
      return { ok: false, articles: [], error: err?.message || String(err) };
    }
  }

  public async fetchArticleDetail(params: WeChatArticleDetailParams): Promise<WeChatArticleDetailResult> {
    if (!this.isRunning()) {
      return { ok: false, error: "WeChat Context worker is not running" };
    }

    const turnId = Number(params.turnId || 0);
    if (Number.isFinite(turnId) && turnId > 0) {
      try {
        const res = await fetch(`http://127.0.0.1:${this.internalPort}/api/article?turnId=${turnId}`, {
          headers: { Host: `127.0.0.1:${this.internalPort}` },
          signal: AbortSignal.timeout(8000),
        });
        if (!res.ok) {
          return { ok: false, error: `Turn fetch HTTP ${res.status}` };
        }
        const data = (await res.json()) as Record<string, any>;
        if (!data.ok || !data.turn) {
          return { ok: false, error: String(data.error || "Turn not found") };
        }
        const rawTurn = data.turn as Record<string, any>;
        const mapped = this.mapRawTurnSummary(rawTurn) || {
          id: 0,
          entryKind: "turn" as const,
          turnId,
          mediaKind: "idea" as const,
          title: `微信记录 #${turnId}`,
          author: "微信尺素",
          url: "",
          publishDate: String(rawTurn.created_at || "").slice(0, 10),
          createdAt: String(rawTurn.created_at || ""),
          status: String(rawTurn.intent || ""),
          processLevel: "light",
          triage: "reference",
          userNotes: "",
          shortSummary: "",
          digest: "",
          hasNote: false,
          hasSource: false,
          isFallbackNote: false,
        };
        const rawDeposited = typeof data.depositedMarkdown === "string" ? data.depositedMarkdown : "";
        const sourceMarkdown = rawDeposited
          ? this.promoteInlineImageAttachments(rawDeposited)
          : (String(rawTurn.user_text || "").trim() || null);
        const replyClean = this.cleanTurnReplyForReport(String(rawTurn.assistant_reply || ""));
        const noteMarkdown = replyClean || (rawDeposited ? this.promoteInlineImageAttachments(rawDeposited) : null);
        mapped.hasNote = Boolean(noteMarkdown);
        mapped.hasSource = Boolean(sourceMarkdown);
        return {
          ok: true,
          article: mapped,
          noteMarkdown,
          sourceMarkdown,
          summaryMarkdown: mapped.shortSummary || null,
          noteTruncated: false,
          sourceTruncated: Boolean(data.depositedTruncated),
        };
      } catch (err: any) {
        return { ok: false, error: err?.message || String(err) };
      }
    }

    let targetId = Number(params.articleId || 0);
    if (!Number.isFinite(targetId) || targetId <= 0) {
      const overview = await this.fetchArticlesOverview(200);
      if (overview.ok) {
        const wantUrl = String(params.sourceUrl || "").trim();
        const wantTitle = this.normalizeMatchTitle(params.title || "");
        const matched = overview.articles.find((a) => {
          if (wantUrl && a.url && a.url.trim() === wantUrl) return true;
          if (wantTitle && this.normalizeMatchTitle(a.title) === wantTitle) return true;
          return false;
        });
        if (matched) {
          if (matched.entryKind === "turn" && matched.turnId) {
            return this.fetchArticleDetail({ turnId: matched.turnId });
          }
          targetId = matched.id;
        }
      }
    }
    if (!Number.isFinite(targetId) || targetId <= 0) {
      return { ok: false, error: "Article not found" };
    }

    try {
      const res = await fetch(`http://127.0.0.1:${this.internalPort}/api/article?id=${targetId}`, {
        headers: { Host: `127.0.0.1:${this.internalPort}` },
        signal: AbortSignal.timeout(8000),
      });
      if (!res.ok) {
        return { ok: false, error: `Article fetch HTTP ${res.status}` };
      }
      const data = (await res.json()) as Record<string, any>;
      if (!data.ok || !data.article) {
        return { ok: false, error: String(data.error || "Article not found") };
      }
      const mapped = this.mapRawArticleSummary(data.article);
      mapped.hasNote = Boolean(data.noteMarkdown) || mapped.hasNote;
      mapped.hasSource = Boolean(data.sourceMarkdown) || mapped.hasSource;
      const summaryMarkdown = String(data.article.short_summary || data.article.digest || "").trim() || null;
      return {
        ok: true,
        article: mapped,
        noteMarkdown: typeof data.noteMarkdown === "string" ? data.noteMarkdown : null,
        sourceMarkdown:
          typeof data.sourceMarkdown === "string"
            ? this.promoteInlineImageAttachments(data.sourceMarkdown)
            : null,
        summaryMarkdown,
        noteTruncated: Boolean(data.noteTruncated),
        sourceTruncated: Boolean(data.sourceTruncated),
      };
    } catch (err: any) {
      return { ok: false, error: err?.message || String(err) };
    }
  }

  public async fetchArticleAsset(params: WeChatArticleAssetParams): Promise<WeChatArticleAssetResult> {
    if (!this.isRunning()) {
      return { ok: false, error: "WeChat Context worker is not running" };
    }
    const articleId = Number(params.articleId || 0);
    const turnId = Number(params.turnId || 0);
    const relPath = String(params.path || "").trim();
    const doc = params.doc === "note" ? "note" : "source";
    if (!relPath || ((!Number.isFinite(articleId) || articleId <= 0) && (!Number.isFinite(turnId) || turnId <= 0))) {
      return { ok: false, error: "Valid articleId/turnId and path required" };
    }
    try {
      const qs = new URLSearchParams({ path: relPath });
      if (Number.isFinite(turnId) && turnId > 0) {
        qs.set("turnId", String(turnId));
      } else {
        qs.set("articleId", String(articleId));
        qs.set("doc", doc);
      }
      const res = await fetch(`http://127.0.0.1:${this.internalPort}/api/asset?${qs.toString()}`, {
        headers: { Host: `127.0.0.1:${this.internalPort}` },
        signal: AbortSignal.timeout(8000),
      });
      if (!res.ok) {
        return { ok: false, error: `Asset fetch HTTP ${res.status}` };
      }
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.byteLength === 0 || buf.byteLength > 3 * 1024 * 1024) {
        return { ok: false, error: "Asset empty or exceeds 3 MiB mobile cap" };
      }
      const mimeType = res.headers.get("content-type") || "image/jpeg";
      return {
        ok: true,
        mimeType,
        dataBase64: buf.toString("base64"),
      };
    } catch (err: any) {
      return { ok: false, error: err?.message || String(err) };
    }
  }

  public async stop(): Promise<void> {
    this.stopped = true;
    if (this.respawnTimer) {
      clearTimeout(this.respawnTimer);
      this.respawnTimer = null;
    }
    const proc = this.child;
    this.child = null;
    if (!proc) return;

    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        try {
          proc.kill("SIGKILL");
        } catch {}
        resolve();
      }, 2500);
      proc.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
      try {
        proc.kill("SIGTERM");
      } catch {
        clearTimeout(timer);
        resolve();
      }
    });
  }
}
