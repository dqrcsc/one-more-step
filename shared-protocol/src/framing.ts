import {
  FramePriority,
  FrameType,
  JsonRpcMessage,
  OMS_MAGIC_BYTE,
  OMS_PROTOCOL_VERSION,
  StreamAckResumeHeader,
  StreamStartHeader,
} from "./types.js";

export const FRAME_HEADER_BYTES = 8;
export const STREAM_CHUNK_PREFIX_BYTES = 20; // 16B UUID + 4B uint32 chunkIndex

export interface DecodedFrame {
  version: number;
  frameType: FrameType;
  flags: number;
  payload: Uint8Array;
}

export interface DecodedStreamChunk {
  streamId: string;
  chunkIndex: number;
  data: Uint8Array;
}

/**
 * Convert standard 36-char UUID string to 16 raw bytes
 */
export function uuidToBytes(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, "");
  if (hex.length !== 32) {
    throw new Error(`Invalid UUID length for binary packing: ${uuid}`);
  }
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

/**
 * Convert 16 raw bytes back to canonical 36-char UUID string
 */
export function bytesToUuid(bytes: Uint8Array): string {
  if (bytes.byteLength !== 16) {
    throw new Error(`Expected 16 bytes for UUID, got ${bytes.byteLength}`);
  }
  const hex = Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

/**
 * Pack an 8-byte OMS protocol frame
 */
export function encodeFrame(
  frameType: FrameType,
  payload: Uint8Array,
  flags = 0
): Uint8Array {
  const out = new Uint8Array(FRAME_HEADER_BYTES + payload.byteLength);
  const view = new DataView(out.buffer, out.byteOffset, out.byteLength);
  view.setUint8(0, OMS_MAGIC_BYTE);
  view.setUint8(1, OMS_PROTOCOL_VERSION);
  view.setUint8(2, frameType);
  view.setUint8(3, flags);
  view.setUint32(4, payload.byteLength, false); // Big-Endian uint32
  out.set(payload, FRAME_HEADER_BYTES);
  return out;
}

export function encodeJsonRpcFrame(msg: JsonRpcMessage): Uint8Array {
  const bytes = new TextEncoder().encode(JSON.stringify(msg));
  return encodeFrame(FrameType.CONTROL_JSON_RPC, bytes);
}

export function decodeJsonRpcPayload<T = JsonRpcMessage>(payload: Uint8Array): T {
  const text = new TextDecoder().decode(payload);
  return JSON.parse(text) as T;
}

export function encodeStreamStartFrame(header: StreamStartHeader): Uint8Array {
  const bytes = new TextEncoder().encode(JSON.stringify(header));
  return encodeFrame(FrameType.STREAM_START, bytes);
}

export function encodeStreamAckResumeFrame(header: StreamAckResumeHeader): Uint8Array {
  const bytes = new TextEncoder().encode(JSON.stringify(header));
  return encodeFrame(FrameType.STREAM_ACK_RESUME, bytes);
}

/**
 * Pack a binary file chunk: [16B streamId][4B chunkIndex][raw_data]
 */
export function encodeStreamChunkFrame(
  streamId: string,
  chunkIndex: number,
  chunkData: Uint8Array
): Uint8Array {
  const payload = new Uint8Array(STREAM_CHUNK_PREFIX_BYTES + chunkData.byteLength);
  payload.set(uuidToBytes(streamId), 0);
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  view.setUint32(16, chunkIndex, false);
  payload.set(chunkData, STREAM_CHUNK_PREFIX_BYTES);
  return encodeFrame(FrameType.STREAM_CHUNK, payload);
}

export function decodeStreamChunkPayload(payload: Uint8Array): DecodedStreamChunk {
  if (payload.byteLength < STREAM_CHUNK_PREFIX_BYTES) {
    throw new Error(`STREAM_CHUNK payload too short: ${payload.byteLength}`);
  }
  const streamId = bytesToUuid(payload.subarray(0, 16));
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const chunkIndex = view.getUint32(16, false);
  const data = payload.subarray(STREAM_CHUNK_PREFIX_BYTES);
  return { streamId, chunkIndex, data };
}

export function encodePingFrame(timestampMs = Date.now()): Uint8Array {
  const buf = new Uint8Array(8);
  const view = new DataView(buf.buffer);
  view.setBigUint64(0, BigInt(timestampMs), false);
  return encodeFrame(FrameType.PING, buf);
}

export function encodePongFrame(
  sentTimestampMs: number,
  batteryPct = 100,
  isCharging = false
): Uint8Array {
  const buf = new Uint8Array(10);
  const view = new DataView(buf.buffer);
  view.setBigUint64(0, BigInt(sentTimestampMs), false);
  view.setUint8(8, batteryPct);
  view.setUint8(9, isCharging ? 1 : 0);
  return encodeFrame(FrameType.PONG, buf);
}

/**
 * Stateful streaming decoder for byte-oriented channels (Bluetooth RFCOMM / TCP)
 * as well as frame-oriented channels (WebSocket / WebRTC DataChannel).
 */
export class FrameStreamDecoder {
  private buffer = new Uint8Array(0);
  private readonly maxFramePayloadBytes: number;

  constructor(maxFramePayloadBytes = 16 * 1024 * 1024) {
    this.maxFramePayloadBytes = maxFramePayloadBytes;
  }

  public push(chunk: Uint8Array): DecodedFrame[] {
    if (chunk.byteLength === 0) return [];

    if (this.buffer.byteLength + chunk.byteLength > 2 * (this.maxFramePayloadBytes + FRAME_HEADER_BYTES)) {
      this.reset();
      throw new Error("OMS decoder buffer exceeds limit");
    }
    const combined = new Uint8Array(this.buffer.byteLength + chunk.byteLength);
    combined.set(this.buffer, 0);
    combined.set(chunk, this.buffer.byteLength);
    this.buffer = combined;

    const frames: DecodedFrame[] = [];

    while (this.buffer.byteLength >= FRAME_HEADER_BYTES) {
      const view = new DataView(
        this.buffer.buffer,
        this.buffer.byteOffset,
        this.buffer.byteLength
      );
      const magic = view.getUint8(0);
      if (magic !== OMS_MAGIC_BYTE) {
        this.reset();
        throw new Error(
          `OMS Frame magic mismatch: expected 0x${OMS_MAGIC_BYTE.toString(16)}, got 0x${magic.toString(16)}`
        );
      }
      const version = view.getUint8(1);
      if (version !== OMS_PROTOCOL_VERSION) {
        this.reset();
        throw new Error(`Unsupported OMS protocol version: ${version}`);
      }
      const frameType = view.getUint8(2) as FrameType;
      const flags = view.getUint8(3);
      const payloadLength = view.getUint32(4, false);

      if (payloadLength > this.maxFramePayloadBytes) {
        this.reset();
        throw new Error(`OMS Frame payload exceeds limit: ${payloadLength} bytes`);
      }

      const totalFrameBytes = FRAME_HEADER_BYTES + payloadLength;
      if (this.buffer.byteLength < totalFrameBytes) {
        break; // Wait for more bytes
      }

      const payload = this.buffer.slice(FRAME_HEADER_BYTES, totalFrameBytes);
      frames.push({ version, frameType, flags, payload });
      this.buffer = this.buffer.slice(totalFrameBytes);
    }

    return frames;
  }

  public reset(): void {
    this.buffer = new Uint8Array(0);
  }
}

/**
 * P0 / P1 / P2 Priority Frame Scheduler to prevent Head-of-Line blocking
 * during large file transfers.
 */
export class PriorityFrameQueue {
  private readonly queues: Map<FramePriority, Uint8Array[]> = new Map([
    [FramePriority.P0_URGENT_CONTROL, []],
    [FramePriority.P1_STREAM_META, []],
    [FramePriority.P2_BULK_DATA, []],
  ]);

  public static priorityForType(frameType: FrameType): FramePriority {
    switch (frameType) {
      case FrameType.CONTROL_JSON_RPC:
      case FrameType.PING:
      case FrameType.PONG:
        return FramePriority.P0_URGENT_CONTROL;
      case FrameType.STREAM_START:
      case FrameType.STREAM_ACK_RESUME:
        return FramePriority.P1_STREAM_META;
      case FrameType.STREAM_CHUNK:
      default:
        return FramePriority.P2_BULK_DATA;
    }
  }

  public enqueue(rawFrame: Uint8Array, frameType: FrameType): void {
    const priority = PriorityFrameQueue.priorityForType(frameType);
    this.queues.get(priority)!.push(rawFrame);
  }

  public dequeue(): Uint8Array | undefined {
    for (const p of [
      FramePriority.P0_URGENT_CONTROL,
      FramePriority.P1_STREAM_META,
      FramePriority.P2_BULK_DATA,
    ]) {
      const q = this.queues.get(p)!;
      if (q.length > 0) {
        return q.shift();
      }
    }
    return undefined;
  }

  public get size(): number {
    let total = 0;
    for (const q of this.queues.values()) {
      total += q.length;
    }
    return total;
  }
}
