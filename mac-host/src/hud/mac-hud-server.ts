import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { EventEmitter } from "node:events";
import { spawn, ChildProcess } from "node:child_process";
import { StoredInboxItem } from "../inbox/inbox-manager.js";

export interface MacHudServerOptions {
  socketPath: string;
  binaryPath?: string;
  autoSpawnBinary?: boolean;
}

export interface HudToastPayload {
  title: string;
  message: string;
  subtitle?: string;
  soundName?: string;
  itemId?: string;
}

export interface HudStatusPayload {
  activeTier: string;
  phoneName?: string;
  inboxDir: string;
  mirrorUrl?: string;
}

/**
 * Unix Domain Socket IPC Server for macOS Native Menu Bar + Top-Right Toast + Inline Quick-Detail HUD (`oms-mac-hud`).
 * Replaces `osascript` notifications (which falsely attribute to Script Editor.app) and external Preview.app popups.
 */
export class MacHudServer extends EventEmitter {
  private server: net.Server | null = null;
  private activeClient: net.Socket | null = null;
  private childProc: ChildProcess | null = null;
  private respawnTimer: ReturnType<typeof setTimeout> | null = null;
  private respawnAttempt = 0;
  private stopped = false;
  private readonly opts: MacHudServerOptions;

  constructor(opts: MacHudServerOptions) {
    super();
    this.opts = opts;
  }

  public isConnected(): boolean {
    return Boolean(this.activeClient && !this.activeClient.destroyed);
  }

  public async start(): Promise<void> {
    this.stopped = false;
    const dir = path.dirname(this.opts.socketPath);
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    try {
      fs.chmodSync(dir, 0o700);
    } catch {}
    if (fs.existsSync(this.opts.socketPath)) {
      try {
        fs.unlinkSync(this.opts.socketPath);
      } catch {}
    }

    await new Promise<void>((resolve, reject) => {
      this.server = net.createServer((socket) => {
        this.activeClient = socket;
        let buffer = "";
        socket.setEncoding("utf8");
        socket.on("error", () => {});

        this.emit("clientConnected");

        socket.on("data", (chunk: string) => {
          buffer += chunk;
          let idx: number;
          while ((idx = buffer.indexOf("\n")) !== -1) {
            const line = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 1);
            if (!line) continue;
            try {
              const msg = JSON.parse(line) as Record<string, any>;
              this.handleHudMessage(msg);
            } catch {}
          }
        });

        socket.on("close", () => {
          if (this.activeClient === socket) {
            this.activeClient = null;
          }
        });
      });

      this.server.once("error", reject);
      this.server.listen(this.opts.socketPath, () => {
        try {
          fs.chmodSync(this.opts.socketPath, 0o600);
        } catch {}
        resolve();
      });
    });

    if (
      this.opts.autoSpawnBinary !== false &&
      this.opts.binaryPath &&
      fs.existsSync(this.opts.binaryPath) &&
      process.platform === "darwin" &&
      process.env.OMS_DISABLE_OSASCRIPT !== "1"
    ) {
      this.spawnBinary(this.opts.binaryPath);
    }
  }

  private spawnBinary(binPath: string): void {
    if (this.stopped || this.childProc) return;
    try {
      const proc = spawn(binPath, ["--socket", this.opts.socketPath], {
        stdio: ["ignore", "ignore", "ignore"],
      });
      this.childProc = proc;
      const spawnedAt = Date.now();
      proc.on("error", () => {});
      proc.on("exit", () => {
        if (this.childProc === proc) {
          this.childProc = null;
        }
        if (this.stopped) return;
        if (Date.now() - spawnedAt > 15_000) {
          this.respawnAttempt = 0;
        }
        const delayMs = Math.min(10_000, 1000 * Math.pow(2, this.respawnAttempt++));
        if (this.respawnTimer) clearTimeout(this.respawnTimer);
        this.respawnTimer = setTimeout(() => {
          this.respawnTimer = null;
          if (!this.stopped && fs.existsSync(binPath)) {
            this.spawnBinary(binPath);
          }
        }, delayMs);
        (this.respawnTimer as any).unref?.();
      });
    } catch {
      this.childProc = null;
    }
  }

  private handleHudMessage(msg: Record<string, any>): void {
    if (msg.type === "mark_processed" && typeof msg.itemId === "string" && msg.itemId) {
      this.emit("markProcessed", msg.itemId);
    } else if (msg.type === "request_sync") {
      this.emit("requestSync");
    }
  }

  private sendEnvelope(payload: Record<string, unknown>): boolean {
    if (!this.activeClient || this.activeClient.destroyed) return false;
    try {
      this.activeClient.write(JSON.stringify(payload) + "\n");
      return true;
    } catch {
      return false;
    }
  }

  public syncState(items: StoredInboxItem[], status: HudStatusPayload): boolean {
    return this.sendEnvelope({
      type: "sync_items",
      items,
      status,
    });
  }

  public notify(
    toast: HudToastPayload,
    opts?: {
      item?: StoredInboxItem;
      items?: StoredInboxItem[];
      status?: HudStatusPayload;
    }
  ): boolean {
    return this.sendEnvelope({
      type: "notify",
      toast,
      ...(opts?.item ? { item: opts.item } : {}),
      ...(opts?.items ? { items: opts.items } : {}),
      ...(opts?.status ? { status: opts.status } : {}),
    });
  }

  public peek(
    itemId?: string,
    opts?: {
      item?: StoredInboxItem;
      status?: HudStatusPayload;
    }
  ): boolean {
    return this.sendEnvelope({
      type: "peek",
      ...(itemId ? { itemId } : {}),
      ...(opts?.item ? { item: opts.item } : {}),
      ...(opts?.status ? { status: opts.status } : {}),
    });
  }

  public async stop(): Promise<void> {
    this.stopped = true;
    if (this.respawnTimer) {
      clearTimeout(this.respawnTimer);
      this.respawnTimer = null;
    }
    if (this.activeClient) {
      try {
        this.activeClient.destroy();
      } catch {}
      this.activeClient = null;
    }
    if (this.childProc) {
      try {
        this.childProc.kill("SIGTERM");
      } catch {}
      this.childProc = null;
    }
    if (this.server) {
      await new Promise<void>((resolve) => this.server!.close(() => resolve()));
      this.server = null;
    }
    if (fs.existsSync(this.opts.socketPath)) {
      try {
        fs.unlinkSync(this.opts.socketPath);
      } catch {}
    }
  }
}
