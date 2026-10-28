import {
  decodeJsonRpcPayload, encodeFrame, encodeJsonRpcFrame, establishSecureSession,
  FrameStreamDecoder, FrameType, generateSecureEphemeral, phoneStart, secureEqualHex,
  type DecodedFrame, type SecureHello, type SecureSession,
} from "@oms/shared-protocol";

export const simulatedStatus = {
  batteryPct: 88,
  isCharging: true,
  screenLocked: false,
  lanIps: ["127.0.0.1"],
  pairedAirpods: [{ name: "AirPods", mac: "B4:59:76:DB:BF:81", connected: false }],
  permissions: {},
};
export const simulatedFinish = { phoneName: "Simulated phone", phoneModel: "Test model", deviceSnapshot: simulatedStatus };
export interface SimulatedPhoneOptions {
  phoneId: string;
  macId: string;
  pskHex: string;
  mode?: "pair" | "auth";
  finish?: typeof simulatedFinish;
  onFrame?: (frame: DecodedFrame, phone: SecureSimulatedPhone) => void;
}
export class SecureSimulatedPhone {
  readonly ws: WebSocket;
  readonly wire: Uint8Array[] = [];
  readonly frames: DecodedFrame[] = [];
  readonly ready: Promise<void>;
  readonly closed: Promise<void>;
  pairSecretHex = "";
  hello?: SecureHello;
  private session?: SecureSession;
  private resolveReady!: () => void;
  private rejectReady!: (error: Error) => void;
  private resolveClosed!: () => void;
  private completed = false;

  constructor(url: string, options: SimulatedPhoneOptions) {
    this.ready = new Promise((resolve, reject) => { this.resolveReady = resolve; this.rejectReady = reject; });
    this.closed = new Promise((resolve) => { this.resolveClosed = resolve; });
    this.ws = new WebSocket(url);
    this.ws.binaryType = "arraybuffer";
    const decoder = new FrameStreamDecoder();
    this.ws.onmessage = (event) => {
      try {
        const raw = new Uint8Array(event.data as ArrayBuffer);
        this.wire.push(raw.slice());
        for (const outer of decoder.push(raw)) {
          if (outer.frameType === FrameType.SECURE_RECORD) {
            if (!this.session) throw new Error("No simulated secure session");
            const inner = this.session.open(encodeFrame(outer.frameType, outer.payload, outer.flags));
            const frame = new FrameStreamDecoder().push(inner)[0];
            if (frame.frameType === FrameType.CONTROL_JSON_RPC) {
              const message = decodeJsonRpcPayload<any>(frame.payload);
              if (message.id === "secure_finish") {
                if (!message.result?.ok || message.result.pairingVersion !== 2) throw new Error("Finish rejected");
                this.completed = true;
                this.resolveReady();
                continue;
              }
            }
            this.frames.push(frame);
            options.onFrame?.(frame, this);
          } else {
            if (outer.frameType !== FrameType.CONTROL_JSON_RPC || this.completed) throw new Error("Unexpected plaintext");
            const message = decodeJsonRpcPayload<any>(outer.payload);
            if (message.method === "secure.hello") {
              this.hello = message.params;
              if (this.hello!.macId !== options.macId) throw new Error("Wrong Mac identity");
              const ephemeral = generateSecureEphemeral();
              const start = phoneStart(this.hello!, options.mode ?? "pair", options.phoneId, options.pskHex, ephemeral);
              const established = establishSecureSession("phone", this.hello!, start, options.pskHex, ephemeral.privateKeyHex);
              this.session = established.session;
              this.pairSecretHex = options.mode === "auth" ? options.pskHex : established.pairSecretHex;
              (this as any).expectedProof = established.macProofHex;
              this.sendPlain(encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", method: "secure.start", params: start }));
            } else if (message.id === "secure_start") {
              if (!secureEqualHex((this as any).expectedProof, message.result?.proofHex)) throw new Error("Invalid Mac proof");
              this.send(encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_finish", method: "secure.finish", params: options.finish ?? simulatedFinish }));
            } else throw new Error("Unexpected plaintext handshake");
          }
        }
      } catch (error) {
        this.rejectReady(error as Error);
        this.ws.close();
      }
    };
    this.ws.onclose = () => {
      this.session?.destroy();
      if (!this.completed) this.rejectReady(new Error("Secure handshake closed before confirmation"));
      this.resolveClosed();
    };
    this.ws.onerror = () => this.rejectReady(new Error("Simulated secure connection failed"));
  }
  sendPlain(bytes: Uint8Array): void {
    this.wire.push(bytes.slice());
    this.ws.send(bytes);
  }
  send(inner: Uint8Array): void {
    if (!this.session) throw new Error("Simulated secure session missing");
    this.sendPlain(this.session.seal(inner));
  }
  close(): void { this.ws.close(); }
}
export async function connectSecurePhone(url: string, options: SimulatedPhoneOptions): Promise<SecureSimulatedPhone> {
  const phone = new SecureSimulatedPhone(url, options);
  await phone.ready;
  return phone;
}

// Synchronous byte-level adapter for unit tests; calls the actual receive entry point.
export function secureMemoryPhone(manager: any, options: SimulatedPhoneOptions & { tier?: string; autoFinish?: boolean }) {
  const frames: any[] = [];
  const wire: Uint8Array[] = [];
  let session: SecureSession | undefined;
  const pipe: any = {
    tier: options.tier ?? "LAN_WS", authenticated: false, decoder: new FrameStreamDecoder(), close: () => {},
    sendRaw: (bytes: Uint8Array) => {
      wire.push(bytes.slice());
      for (const outer of new FrameStreamDecoder().push(bytes)) {
        const frame = outer.frameType === FrameType.SECURE_RECORD
          ? new FrameStreamDecoder().push(session!.open(bytes))[0] : outer;
        frames.push({ frameType: frame.frameType, frame, value: frame.frameType === FrameType.STREAM_CHUNK || frame.frameType === FrameType.PING || frame.frameType === FrameType.PONG ? undefined : decodeJsonRpcPayload(frame.payload) });
      }
      return true;
    },
  };
  manager.onPipeOpened(pipe);
  const hello = frames.at(-1)!.value.params as SecureHello;
  const ephemeral = generateSecureEphemeral();
  const start = phoneStart(hello, options.mode ?? "pair", options.phoneId, options.pskHex, ephemeral);
  const established = establishSecureSession("phone", hello, start, options.pskHex, ephemeral.privateKeyHex);
  session = established.session;
  manager.onRawBytesReceived(pipe, encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", method: "secure.start", params: start }));
  if (!secureEqualHex(established.macProofHex, frames.at(-1)!.value.result?.proofHex)) throw new Error("Invalid simulated Mac proof");
  const send = (inner: Uint8Array) => manager.onRawBytesReceived(pipe, session!.seal(inner));
  const finish = () => send(encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_finish", method: "secure.finish", params: options.finish ?? simulatedFinish }));
  if (options.autoFinish !== false) finish();
  frames.length = 0;
  return { pipe, messages: frames, wire, session, hello, start, pairSecretHex: options.mode === "auth" ? options.pskHex : established.pairSecretHex, send, finish };
}
