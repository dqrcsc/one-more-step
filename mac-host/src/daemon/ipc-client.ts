import { ipcTimeoutForAction } from "./request-timeouts.js";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { spawn } from "node:child_process";
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

      const timer = setTimeout(() => {
        socket.destroy();
        reject(new Error(`IPC call '${action}' timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      socket.setEncoding("utf8");

      socket.on("connect", () => {
        socket.write(JSON.stringify({ id: reqId, action, params }) + "\n");
      });

      socket.on("data", (chunk: string) => {
        buffer += chunk;
        const idx = buffer.indexOf("\n");
        if (idx !== -1) {
          clearTimeout(timer);
          const line = buffer.slice(0, idx).trim();
          socket.end();
          try {
            const resp = JSON.parse(line);
            if (resp.ok) {
              resolve(resp.result as T);
            } else {
              const err = new Error(resp.error?.message || "IPC execution error") as Error & {
                code?: number;
              };
              err.code = resp.error?.code;
              reject(err);
            }
          } catch (e) {
            reject(e);
          }
        }
      });

      socket.on("error", (err) => {
        clearTimeout(timer);
        reject(err);
      });
    });
  }

  private async ensureDaemonRunning(): Promise<void> {
    if (await this.canConnectOnce()) return;

    const mainScript = path.resolve(
      path.dirname(new URL(import.meta.url).pathname),
      "./main.ts"
    );
    const logDir = path.join(this.homeDir, "logs");
    fs.mkdirSync(logDir, { recursive: true });
    const outFd = fs.openSync(path.join(logDir, "omsd.log"), "a");

    const child = spawn(process.execPath, ["run", mainScript], {
      detached: true,
      stdio: ["ignore", outFd, outFd],
      env: { ...process.env, OMS_HOME: this.homeDir },
    });
    child.unref();

    for (let i = 0; i < 25; i++) {
      await new Promise((r) => setTimeout(r, 120));
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
