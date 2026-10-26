import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { EventEmitter } from "node:events";
import { spawn, ChildProcess } from "node:child_process";

export interface BluetoothBridgeOptions {
  socketPath: string;
  macId: string;
  lanIps: string[];
  lanPort: number;
  autoSpawnBinary?: boolean;
  binaryPath?: string;
}

/**
 * Module 02: TypeScript IPC Server for macOS Native Swift CoreBluetooth Bridge (`oms-bt-bridge`)
 */
export class BluetoothBridgeServer extends EventEmitter {
  private server: net.Server | null = null;
  private activeClient: net.Socket | null = null;
  private childProc: ChildProcess | null = null;
  private connectedCentralId: string | null = null;
  private readonly opts: BluetoothBridgeOptions;

  constructor(opts: BluetoothBridgeOptions) {
    super();
    this.opts = opts;
  }

  public async start(): Promise<void> {
    const dir = path.dirname(this.opts.socketPath);
    fs.mkdirSync(dir, { recursive: true });
    if (fs.existsSync(this.opts.socketPath)) {
      try {
        fs.unlinkSync(this.opts.socketPath);
      } catch {
        // ignore
      }
    }

    await new Promise<void>((resolve, reject) => {
      this.server = net.createServer((socket) => {
        this.activeClient = socket;
        let buffer = "";
        socket.setEncoding("utf8");

        // Immediately send latest Mac LAN info
        this.sendToBridge({
          type: "update_mac_info",
          payload: JSON.stringify({
            macId: this.opts.macId,
            lanIps: this.opts.lanIps,
            lanPort: this.opts.lanPort,
          }),
        });

        socket.on("data", (chunk: string) => {
          buffer += chunk;
          let idx: number;
          while ((idx = buffer.indexOf("\n")) !== -1) {
            const line = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 1);
            if (!line) continue;
            try {
              const msg = JSON.parse(line);
              this.handleBridgeMessage(msg);
            } catch {
              // ignore malformed line
            }
          }
        });

        socket.on("close", () => {
          if (this.activeClient === socket) {
            this.activeClient = null;
          }
        });
      });

      this.server.once("error", reject);
      this.server.listen(this.opts.socketPath, () => resolve());
    });

    if (this.opts.autoSpawnBinary && this.opts.binaryPath && fs.existsSync(this.opts.binaryPath)) {
      const infoJson = JSON.stringify({
        macId: this.opts.macId,
        lanIps: this.opts.lanIps,
        lanPort: this.opts.lanPort,
      });
      this.childProc = spawn(
        this.opts.binaryPath,
        ["--socket", this.opts.socketPath, "--info", infoJson],
        { stdio: "ignore" }
      );
      this.childProc.unref();
    }
  }

  public get isPhoneConnectedOverBt(): boolean {
    return this.activeClient !== null && this.connectedCentralId !== null;
  }

  public sendFrameOverBluetooth(rawFrame: Uint8Array): boolean {
    if (!this.activeClient || !this.connectedCentralId) return false;
    return this.sendToBridge({
      type: "frame_tx",
      base64: Buffer.from(rawFrame).toString("base64"),
    });
  }

  public updateMacLanInfo(lanIps: string[], lanPort: number): void {
    this.opts.lanIps = lanIps;
    this.opts.lanPort = lanPort;
    this.sendToBridge({
      type: "update_mac_info",
      payload: JSON.stringify({
        macId: this.opts.macId,
        lanIps,
        lanPort,
      }),
    });
  }

  private sendToBridge(obj: Record<string, unknown>): boolean {
    if (!this.activeClient) return false;
    try {
      this.activeClient.write(JSON.stringify(obj) + "\n");
      return true;
    } catch {
      return false;
    }
  }

  private handleBridgeMessage(msg: Record<string, any>): void {
    switch (msg.type) {
      case "ble_state":
        this.emit("bleState", msg.state);
        break;
      case "central_connected":
        this.connectedCentralId = msg.centralId;
        this.emit("phoneConnected", { centralId: msg.centralId });
        break;
      case "central_disconnected":
        this.connectedCentralId = null;
        this.emit("phoneDisconnected", { centralId: msg.centralId });
        break;
      case "rssi":
        if (typeof msg.rssi === "number") {
          this.emit("rssi", msg.rssi);
        }
        break;
      case "oob_announce":
        this.emit("oobAnnounce", msg.data);
        break;
      case "frame_rx":
        if (typeof msg.base64 === "string") {
          const bytes = new Uint8Array(Buffer.from(msg.base64, "base64"));
          this.emit("frame", bytes);
        }
        break;
    }
  }

  public async stop(): Promise<void> {
    if (this.childProc) {
      this.childProc.kill();
      this.childProc = null;
    }
    if (this.activeClient) {
      this.activeClient.destroy();
      this.activeClient = null;
    }
    if (this.server) {
      await new Promise<void>((resolve) => this.server!.close(() => resolve()));
      this.server = null;
    }
    if (fs.existsSync(this.opts.socketPath)) {
      try {
        fs.unlinkSync(this.opts.socketPath);
      } catch {
        // ignore
      }
    }
  }
}
