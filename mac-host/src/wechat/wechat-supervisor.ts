import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcess } from "node:child_process";

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
