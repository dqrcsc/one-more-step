import fs from "node:fs";
import path from "node:path";

const EVENTS = ["OPENED", "AUTHENTICATED", "CLOSED", "WS_CLOSED", "HANDSHAKE_TIMEOUT", "PROTOCOL_REJECTED", "SEND_FAILED"] as const;
export type ConnectionEvent = typeof EVENTS[number];
export interface Entry { time: string; event: ConnectionEvent; tier: string; connection: string; closeCode?: number }

/** Persist only a fixed schema; no remote reason strings, payloads or device identifiers. */
export class ConnectionDiagnostics {
  private entries: Entry[] = [];
  public persistenceAvailable = true;
  constructor(private file?: string) {
    try {
      if (file && fs.existsSync(file) && fs.statSync(file).size <= 65536) {
        for (const line of fs.readFileSync(file, "utf8").split("\n").slice(-201)) {
          try {
            const value = JSON.parse(line);
            const entry = this.validate(value);
            if (entry) this.entries.push(entry);
          } catch { /* A truncated line does not hide the remaining history. */ }
        }
        this.entries = this.entries.slice(-200);
      }
    } catch { this.persistenceAvailable = false; }
  }
  private validate(value: any): Entry | null {
    if (!value || !EVENTS.includes(value.event) || !["LAN_WS", "BT_RFCOMM", "WAN_WEBRTC"].includes(value.tier)
      || typeof value.connection !== "string" || !/^[a-f0-9]{12}$/.test(value.connection)
      || typeof value.time !== "string" || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(value.time)) return null;
    return { time: value.time, event: value.event, tier: value.tier, connection: value.connection,
      ...(Number.isInteger(value.closeCode) && value.closeCode >= 0 && value.closeCode <= 65535 ? { closeCode: value.closeCode } : {}) };
  }
  record(event: ConnectionEvent, tier: string, connection: string, closeCode?: number): void {
    const entry = this.validate({ time: new Date().toISOString(), event, tier, connection, closeCode });
    if (!entry) return;
    this.entries.push(entry);
    this.entries = this.entries.slice(-200);
    try {
      if (this.file) {
        fs.mkdirSync(path.dirname(this.file), { recursive: true });
        fs.writeFileSync(this.file, this.entries.map(e => JSON.stringify(e)).join("\n") + "\n", { mode: 0o600 });
      }
    } catch { this.persistenceAvailable = false; }
  }
  snapshot(): Entry[] { return this.entries.map(e => ({ ...e })); }
}
