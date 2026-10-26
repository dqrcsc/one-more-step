import { EventEmitter } from "node:events";
import { PresenceState } from "@oms/shared-protocol";

export interface PresenceEngineOptions {
  emaAlpha?: number; // Default 0.3
  arriveThresholdDbm?: number; // Default -68 dBm
  leaveThresholdDbm?: number; // Default -82 dBm
  arriveHoldMs?: number; // Default 3000 ms
  leaveHoldMs?: number; // Default 20000 ms
  disconnectGraceMs?: number; // Default 15000 ms
}

export interface PresenceSnapshot {
  state: PresenceState;
  bleConnected: boolean;
  rssiRaw?: number;
  rssiEma?: number;
  lastUpdatedMs: number;
  lastTransitionMs: number;
}

/**
 * Module 02: Bluetooth Proximity & Spatial Presence Engine
 * Implements Exponential Moving Average (EMA) filtering + Dual-Threshold Time-Window Hysteresis
 */
export class PresenceEngine extends EventEmitter {
  private state: PresenceState = "AWAY";
  private bleConnected = false;
  private rssiRaw?: number;
  private rssiEma?: number;
  private lastUpdatedMs = 0;
  private lastTransitionMs = Date.now();

  private candidateAboveSinceMs: number | null = null;
  private candidateBelowSinceMs: number | null = null;
  private disconnectedSinceMs: number | null = null;

  private readonly emaAlpha: number;
  private readonly arriveThresholdDbm: number;
  private readonly leaveThresholdDbm: number;
  private readonly arriveHoldMs: number;
  private readonly leaveHoldMs: number;
  private readonly disconnectGraceMs: number;

  constructor(opts: PresenceEngineOptions = {}) {
    super();
    this.emaAlpha = opts.emaAlpha ?? 0.3;
    this.arriveThresholdDbm = opts.arriveThresholdDbm ?? -68;
    this.leaveThresholdDbm = opts.leaveThresholdDbm ?? -82;
    this.arriveHoldMs = opts.arriveHoldMs ?? 3000;
    this.leaveHoldMs = opts.leaveHoldMs ?? 20000;
    this.disconnectGraceMs = opts.disconnectGraceMs ?? 15000;
  }

  public getSnapshot(): PresenceSnapshot {
    return {
      state: this.state,
      bleConnected: this.bleConnected,
      rssiRaw: this.rssiRaw,
      rssiEma: this.rssiEma !== undefined ? Math.round(this.rssiEma * 10) / 10 : undefined,
      lastUpdatedMs: this.lastUpdatedMs,
      lastTransitionMs: this.lastTransitionMs,
    };
  }

  /**
   * Feed a new BLE RSSI sample (in dBm, e.g. -58)
   */
  public onBleRssiSample(rssiDbm: number, nowMs = Date.now()): PresenceState {
    this.bleConnected = true;
    this.disconnectedSinceMs = null;
    this.rssiRaw = rssiDbm;
    this.lastUpdatedMs = nowMs;

    if (this.rssiEma === undefined) {
      this.rssiEma = rssiDbm;
    } else {
      this.rssiEma = this.emaAlpha * rssiDbm + (1 - this.emaAlpha) * this.rssiEma;
    }

    if (this.state === "AWAY") {
      if (this.rssiEma >= this.arriveThresholdDbm) {
        if (this.candidateAboveSinceMs === null) {
          this.candidateAboveSinceMs = nowMs;
        }
        if (nowMs - this.candidateAboveSinceMs >= this.arriveHoldMs) {
          this.transitionTo("AT_DESK", nowMs, "ble_rssi_arrive");
        }
      } else {
        this.candidateAboveSinceMs = null;
      }
    } else if (this.state === "AT_DESK") {
      if (this.rssiEma <= this.leaveThresholdDbm) {
        if (this.candidateBelowSinceMs === null) {
          this.candidateBelowSinceMs = nowMs;
        }
        if (nowMs - this.candidateBelowSinceMs >= this.leaveHoldMs) {
          this.transitionTo("AWAY", nowMs, "ble_rssi_far");
        }
      } else {
        this.candidateBelowSinceMs = null;
      }
    }

    return this.state;
  }

  /**
   * Notify when BLE link drops or evaluates periodic timeout
   */
  public onBleDisconnected(nowMs = Date.now()): PresenceState {
    if (this.bleConnected) {
      this.bleConnected = false;
      this.disconnectedSinceMs = nowMs;
      this.candidateAboveSinceMs = null;
    }
    this.evaluateTimeouts(nowMs);
    return this.state;
  }

  /**
   * Force AT_DESK when user actively interacts on same LAN with low RTT (< 5ms)
   */
  public onLanDirectActive(rttMs: number, nowMs = Date.now()): void {
    this.disconnectedSinceMs = null;
    if (rttMs <= 8 && this.state === "AWAY" && !this.bleConnected) {
      this.transitionTo("AT_DESK", nowMs, "lan_low_latency");
    }
  }

  /**
   * Notify when all transport tiers drop to DISCONNECTED
   */
  public onAllTransportsDisconnected(nowMs = Date.now()): PresenceState {
    if (!this.bleConnected && this.state === "AT_DESK") {
      this.transitionTo("AWAY", nowMs, "all_transports_disconnected");
    }
    return this.state;
  }

  public evaluateTimeouts(nowMs = Date.now()): PresenceState {
    if (
      !this.bleConnected &&
      this.state === "AT_DESK" &&
      this.disconnectedSinceMs !== null &&
      nowMs - this.disconnectedSinceMs >= this.disconnectGraceMs
    ) {
      this.transitionTo("AWAY", nowMs, "ble_disconnect_timeout");
    }
    return this.state;
  }

  private transitionTo(next: PresenceState, nowMs: number, reason: string): void {
    if (this.state === next) return;
    const prev = this.state;
    this.state = next;
    this.lastTransitionMs = nowMs;
    this.candidateAboveSinceMs = null;
    this.candidateBelowSinceMs = null;

    const payload = {
      previous: prev,
      current: next,
      reason,
      rssiEma: this.rssiEma,
      timestampMs: nowMs,
    };
    this.emit("change", payload);
    if (next === "AT_DESK") {
      this.emit("arriveDesk", payload);
    } else {
      this.emit("leaveDesk", payload);
    }
  }
}
