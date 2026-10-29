import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { EventEmitter } from "node:events";
import { spawn, ChildProcess } from "node:child_process";
import { PairedAirPodsDevice } from "@oms/shared-protocol";

export interface BluetoothBridgeOptions {
  socketPath: string;
  macId: string;
  lanIps: string[];
  lanPort: number;
  autoSpawnBinary?: boolean;
  binaryPath?: string;
}

export interface MacBridgeAirPodsStatus {
  connected: boolean;
  isDefaultOutput: boolean;
  macAudioRunning: boolean;
  defaultOutputName?: string;
  deviceName?: string;
  deviceMac?: string;
  pairedAirpods: PairedAirPodsDevice[];
}

export interface MacBridgeAirPodsConfig {
  autoSwitch: boolean;
  pauseOtherOnSwitch: boolean;
  preferredMac?: string;
  phoneAirpodsConnected: boolean;
  phoneAirpodsMac?: string;
  phoneAirpodsName?: string;
  phoneInCall: boolean;
}

/**
 * Module 02: TypeScript IPC Server for macOS Native Swift CoreBluetooth & AirPods Bridge (`oms-bt-bridge`)
 */
export class BluetoothBridgeServer extends EventEmitter {
  private server: net.Server | null = null;
  private activeClient: net.Socket | null = null;
  private childProc: ChildProcess | null = null;
  private connectedCentralId: string | null = null;
  private readonly opts: BluetoothBridgeOptions;
  private latestAirpodsStatus: MacBridgeAirPodsStatus = {
    connected: false,
    isDefaultOutput: false,
    macAudioRunning: false,
    pairedAirpods: [],
  };
  private latestAirpodsConfig: MacBridgeAirPodsConfig = {
    autoSwitch: true,
    pauseOtherOnSwitch: true,
    phoneAirpodsConnected: false,
    phoneInCall: false,
  };
  private pendingCmdWaiters = new Map<
    string,
    {
      resolve: (res: Record<string, any>) => void;
      timer: Timer;
    }
  >();

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

        // Immediately send latest Mac LAN info & AirPods config
        this.sendToBridge({
          type: "update_mac_info",
          payload: JSON.stringify({
            macId: this.opts.macId,
            lanIps: this.opts.lanIps,
            lanPort: this.opts.lanPort,
          }),
        });
        this.pushAirpodsConfigToBridge();

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
            for (const waiter of this.pendingCmdWaiters.values()) {
              clearTimeout(waiter.timer);
              waiter.resolve({ ok: false, message: "蓝牙桥连接已断开" });
            }
            this.pendingCmdWaiters.clear();
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

  public getAirpodsStatus(): MacBridgeAirPodsStatus {
    return this.latestAirpodsStatus;
  }

  public updateAirpodsConfig(partial: Partial<MacBridgeAirPodsConfig>): void {
    this.latestAirpodsConfig = {
      ...this.latestAirpodsConfig,
      ...partial,
    };
    this.pushAirpodsConfigToBridge();
  }

  private pushAirpodsConfigToBridge(): void {
    this.sendToBridge({
      type: "airpods_config",
      autoSwitch: this.latestAirpodsConfig.autoSwitch,
      pauseOtherOnSwitch: this.latestAirpodsConfig.pauseOtherOnSwitch,
      preferredMac: this.latestAirpodsConfig.preferredMac || "",
      phoneAirpodsConnected: this.latestAirpodsConfig.phoneAirpodsConnected,
      phoneAirpodsMac: this.latestAirpodsConfig.phoneAirpodsMac || "",
      phoneAirpodsName: this.latestAirpodsConfig.phoneAirpodsName || "",
      phoneInCall: this.latestAirpodsConfig.phoneInCall,
    });
  }

  public async connectAirpodsToMac(targetMac?: string, timeoutMs = 23000): Promise<Record<string, any>> {
    if (!this.activeClient) {
      return { ok: false, message: "oms-bt-bridge 未连接" };
    }
    const reqId = `ap_conn_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
    return await new Promise<Record<string, any>>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingCmdWaiters.delete(reqId);
        this.sendToBridge({ type: "airpods_cancel", reqId });
        resolve({ ok: false, message: "连接 AirPods 等待超时" });
      }, timeoutMs);
      this.pendingCmdWaiters.set(reqId, { resolve, timer });
      this.sendToBridge({
        type: "airpods_connect_mac",
        reqId,
        deadlineMs: Date.now() + Math.max(0, Math.min(22000, timeoutMs - 1000)),
        targetMac: targetMac || undefined,
      });
    });
  }

  public async releaseAirpodsFromMac(opts?: {
    pauseMedia?: boolean;
    targetMac?: string;
    timeoutMs?: number;
  }): Promise<Record<string, any>> {
    if (!this.activeClient) {
      return { ok: false, message: "oms-bt-bridge 未连接" };
    }
    const reqId = `ap_rel_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
    const timeoutMs = opts?.timeoutMs ?? 8000;
    return await new Promise<Record<string, any>>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingCmdWaiters.delete(reqId);
        this.sendToBridge({ type: "airpods_cancel", reqId });
        resolve({ ok: false, message: "释放 AirPods 等待确认超时" });
      }, timeoutMs);
      this.pendingCmdWaiters.set(reqId, { resolve, timer });
      this.sendToBridge({
        type: "airpods_release_mac",
        reqId,
        deadlineMs: Date.now() + Math.max(0, Math.min(7000, timeoutMs - 1000)),
        pauseMedia: opts?.pauseMedia !== false,
        targetMac: opts?.targetMac || undefined,
      });
    });
  }

  public suppressAirpodsAutoGrab(durationMs = 2500): void {
    this.sendToBridge({
      type: "airpods_suppress_grab",
      durationMs,
    });
  }

  public requestAirpodsStatusRefresh(): void {
    this.sendToBridge({ type: "airpods_get_status" });
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
      case "airpods_status": {
        const pairedRaw = Array.isArray(msg.pairedAirpods) ? msg.pairedAirpods : [];
        const pairedAirpods: PairedAirPodsDevice[] = pairedRaw.map((item: any) => ({
          name: String(item?.name || "AirPods"),
          mac: String(item?.mac || ""),
          connected: Boolean(item?.connected),
        }));
        this.latestAirpodsStatus = {
          connected: Boolean(msg.connected),
          isDefaultOutput: Boolean(msg.isDefaultOutput),
          macAudioRunning: Boolean(msg.macAudioRunning),
          defaultOutputName: msg.defaultOutputName ? String(msg.defaultOutputName) : undefined,
          deviceName: msg.deviceName ? String(msg.deviceName) : undefined,
          deviceMac: msg.deviceMac ? String(msg.deviceMac) : undefined,
          pairedAirpods,
        };
        this.emit("airpodsStatus", this.latestAirpodsStatus);
        break;
      }
      case "airpods_mac_claim_triggered":
        this.emit("airpodsMacClaimTriggered", {
          targetMac: msg.targetMac ? String(msg.targetMac) : undefined,
          targetName: msg.targetName ? String(msg.targetName) : undefined,
          reason: String(msg.reason || "mac_audio_started"),
        });
        break;
      case "airpods_diagnostics":
        if (msg.diagnostics && typeof msg.diagnostics === "object" && !Array.isArray(msg.diagnostics)) {
          this.emit("airpodsDiagnostics", msg.diagnostics);
        }
        break;
      case "airpods_cmd_result": {
        const reqId = String(msg.reqId || "");
        const waiter = this.pendingCmdWaiters.get(reqId);
        if (waiter) {
          clearTimeout(waiter.timer);
          this.pendingCmdWaiters.delete(reqId);
          waiter.resolve(msg);
        }
        break;
      }
    }
  }

  public async stop(): Promise<void> {
    for (const [reqId, w] of this.pendingCmdWaiters) {
      this.sendToBridge({ type: "airpods_cancel", reqId });
      clearTimeout(w.timer);
      w.resolve({ ok: false, message: "stopped" });
    }
    this.pendingCmdWaiters.clear();
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
