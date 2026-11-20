import { ipcTimeoutForAction } from "./request-timeouts.js";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { spawn, spawnSync } from "node:child_process";
import { generateUuidV7 } from "@oms/shared-protocol";

/**
 * Module 06: Thin Local Unix Socket IPC Client for MCP Server & CLI
 * Automatically spawns `omsd` background daemon if not already running.
 */
export class OmsIpcClient {
  private readonly homeDir: string;
  private readonly socketPath: string;

  constructor(customHomeDir?: string) {
    this.homeDir = customHomeDir || process.env.OMS_HOME || path.join(os.homedir(), ".one_more_step");
    this.socketPath = path.join(this.homeDir, "run", "omsd.sock");
  }

  public async call<T = any>(
    action: string,
    params: Record<string, any> = {},
    timeoutMs = ipcTimeoutForAction(action, params)
  ): Promise<T> {
    await this.ensureDaemonRunning();

    return await new Promise<T>((resolve, reject) => {
      const reqId = `ipc_${generateUuidV7()}`;
      const socket = net.createConnection(this.socketPath);
      let buffer = "";
      let settled = false;

      const finishResolve = (val: T) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(val);
      };

      const finishReject = (err: unknown) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(err instanceof Error ? err : new Error(String(err)));
      };

      const timer = setTimeout(() => {
        socket.destroy();
        finishReject(new Error(`IPC call '${action}' timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      socket.setEncoding("utf8");

      socket.on("connect", () => {
        socket.write(JSON.stringify({ id: reqId, action, params }) + "\n");
      });

      socket.on("data", (chunk: string) => {
        buffer += chunk;
        const idx = buffer.indexOf("\n");
        if (idx !== -1) {
          const line = buffer.slice(0, idx).trim();
          try {
            const resp = JSON.parse(line);
            if (resp.ok) {
              finishResolve(resp.result as T);
            } else {
              const err = new Error(resp.error?.message || "IPC execution error") as Error & {
                code?: number;
              };
              err.code = resp.error?.code;
              finishReject(err);
            }
          } catch (e) {
            finishReject(e);
          } finally {
            socket.end();
          }
        }
      });

      socket.on("error", (err) => {
        finishReject(err);
      });

      socket.on("close", () => {
        if (!settled) {
          finishReject(
            new Error(`IPC connection closed unexpectedly before '${action}' completed`)
          );
        }
      });
    });
  }

  private isPidAlive(pidFile: string): boolean {
    try {
      if (!fs.existsSync(pidFile)) return false;
      const pid = Number(fs.readFileSync(pidFile, "utf8").trim());
      if (!Number.isFinite(pid) || pid <= 0) return false;
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  }

  private tryKickstartLaunchAgent(): boolean {
    if (
      process.platform !== "darwin" ||
      process.env.OMS_TEST_ISOLATION === "1" ||
      process.env.OMS_DISABLE_OSASCRIPT === "1"
    ) {
      return false;
    }
    const defaultHome = path.join(os.homedir(), ".one_more_step");
    if (path.resolve(this.homeDir) !== path.resolve(defaultHome)) {
      return false;
    }
    const plistPath = path.join(os.homedir(), "Library/LaunchAgents/com.oms.daemon.plist");
    if (!fs.existsSync(plistPath)) return false;
    const uid = typeof process.getuid === "function" ? process.getuid() : undefined;
    if (uid === undefined) return false;
    const serviceTarget = `gui/${uid}/com.oms.daemon`;
    const kick = spawnSync("launchctl", ["kickstart", serviceTarget], { stdio: "ignore" });
    if (kick.status === 0) return true;
    const boot = spawnSync("launchctl", ["bootstrap", `gui/${uid}`, plistPath], {
      stdio: "ignore",
    });
    if (boot.status === 0) {
      spawnSync("launchctl", ["kickstart", serviceTarget], { stdio: "ignore" });
      return true;
    }
    return false;
  }

  private async ensureDaemonRunning(): Promise<void> {
    if (await this.canConnectOnce()) return;

    // If an instance is already booting (holding omsd.pid before binding omsd.sock), give it a brief window first.
    const pidFile = path.join(this.homeDir, "run", "omsd.pid");
    if (this.isPidAlive(pidFile)) {
      const bootWaitDeadline = Date.now() + 3_500;
      while (Date.now() < bootWaitDeadline) {
        await new Promise((r) => setTimeout(r, 150));
        if (await this.canConnectOnce()) return;
        if (!this.isPidAlive(pidFile)) break;
      }
      if (await this.canConnectOnce()) return;
    }

    const usedLaunchAgent = this.tryKickstartLaunchAgent();
    if (usedLaunchAgent) {
      const launchdDeadline = Date.now() + 8_000;
      while (Date.now() < launchdDeadline) {
        await new Promise((r) => setTimeout(r, 150));
        if (await this.canConnectOnce()) return;
      }
    }

    const mainScript = path.resolve(
      path.dirname(new URL(import.meta.url).pathname),
      "./main.ts"
    );
    const logDir = path.join(this.homeDir, "logs");
    fs.mkdirSync(logDir, { recursive: true });
    const outFd = fs.openSync(path.join(logDir, "omsd.log"), "a");
    try {
      const child = spawn(process.execPath, ["run", mainScript], {
        detached: true,
        stdio: ["ignore", outFd, outFd],
        env: { ...process.env, OMS_HOME: this.homeDir },
      });
      child.on("error", () => {});
      child.unref();
    } finally {
      try {
        fs.closeSync(outFd);
      } catch {
        // ignore close error
      }
    }

    const deadlineMs = Date.now() + 15_000;
    while (Date.now() < deadlineMs) {
      await new Promise((r) => setTimeout(r, 150));
      if (await this.canConnectOnce()) return;
    }
  }

  private canConnectOnce(): Promise<boolean> {
    if (!fs.existsSync(this.socketPath)) return Promise.resolve(false);
    return new Promise<boolean>((resolve) => {
      const sock = net.createConnection(this.socketPath);
      sock.once("connect", () => {
        sock.destroy();
        resolve(true);
      });
      sock.once("error", () => {
        sock.destroy();
        resolve(false);
      });
    });
  }
}
