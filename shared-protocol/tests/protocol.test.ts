import { describe, expect, test } from "bun:test";
import {
  buildPairingUri,
  computePairingHmac,
  createEphemeralPairingCredentials,
  decodeJsonRpcPayload,
  decodeStreamChunkPayload,
  encodeJsonRpcFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  extractOtpFromText,
  FrameStreamDecoder,
  FrameType,
  generateEd25519Identity,
  generateUuidV7,
  parsePairingUri,
  PriorityFrameQueue,
  signEd25519,
  verifyEd25519,
} from "../src/index.js";

describe("Module 01: Shared Protocol & Pairing", () => {
  test("Ed25519 key generation, signing, and verification", () => {
    const macId = generateEd25519Identity("mac");
    const phoneId = generateEd25519Identity("xiaomi");

    const challenge = "nonce_123456:mac_abc";
    const signature = signEd25519(phoneId.privateKeyPem, challenge);

    expect(verifyEd25519(phoneId.publicKeyBase64Url, challenge, signature)).toBe(true);
    expect(verifyEd25519(macId.publicKeyBase64Url, challenge, signature)).toBe(false);
    expect(verifyEd25519(phoneId.publicKeyBase64Url, "tampered", signature)).toBe(false);
  });

  test("Pairing URI encode/decode and HMAC verification", () => {
    const mac = generateEd25519Identity("mac");
    const creds = createEphemeralPairingCredentials();
    const uri = buildPairingUri({
      v: 1,
      id: mac.deviceId,
      name: "MacBook-Pro",
      ips: ["192.168.1.10", "10.0.0.5"],
      port: 19527,
      btUuid: "FD9A",
      pubKeyBase64Url: mac.publicKeyBase64Url,
      pinCode: creds.pinCode,
      ephemeralToken: creds.ephemeralToken,
      expiresAtMs: creds.expiresAtMs,
    });

    const parsed = parsePairingUri(uri);
    expect(parsed.id).toBe(mac.deviceId);
    expect(parsed.ips).toEqual(["192.168.1.10", "10.0.0.5"]);
    expect(parsed.pinCode).toBe(creds.pinCode);

    const hmac = computePairingHmac(
      parsed.ephemeralToken,
      parsed.pinCode,
      "phone_01",
      "pub_key_xyz"
    );
    expect(hmac.length).toBe(64);
  });

  test("Streaming 8-byte frame decoder handles fragmented and sticky packets (RFCOMM simulation)", () => {
    const decoder = new FrameStreamDecoder();
    const streamId = generateUuidV7();

    const frame1 = encodeJsonRpcFrame({
      jsonrpc: "2.0",
      id: "req_1",
      method: "alarm.set",
      params: { hour: 8, minute: 15 },
    });
    const chunkData = new Uint8Array([10, 20, 30, 40, 50]);
    const frame2 = encodeStreamChunkFrame(streamId, 3, chunkData);

    // Combine frame1 + frame2 and slice into arbitrary small fragments (simulating Bluetooth RFCOMM stream)
    const combined = new Uint8Array(frame1.byteLength + frame2.byteLength);
    combined.set(frame1, 0);
    combined.set(frame2, frame1.byteLength);

    const outFrames = [];
    for (let i = 0; i < combined.byteLength; i += 7) {
      const slice = combined.subarray(i, Math.min(i + 7, combined.byteLength));
      outFrames.push(...decoder.push(slice));
    }

    expect(outFrames.length).toBe(2);
    expect(outFrames[0].frameType).toBe(FrameType.CONTROL_JSON_RPC);
    const rpc = decodeJsonRpcPayload<{ id: string; method: string }>(outFrames[0].payload);
    expect(rpc.method).toBe("alarm.set");

    expect(outFrames[1].frameType).toBe(FrameType.STREAM_CHUNK);
    const decodedChunk = decodeStreamChunkPayload(outFrames[1].payload);
    expect(decodedChunk.streamId).toBe(streamId);
    expect(decodedChunk.chunkIndex).toBe(3);
    expect(Array.from(decodedChunk.data)).toEqual([10, 20, 30, 40, 50]);
  });

  test("P0/P1/P2 PriorityFrameQueue prevents Head-of-Line blocking by bulk chunks", () => {
    const queue = new PriorityFrameQueue();
    const streamId = generateUuidV7();

    const bulkChunk1 = encodeStreamChunkFrame(streamId, 0, new Uint8Array(1024));
    const bulkChunk2 = encodeStreamChunkFrame(streamId, 1, new Uint8Array(1024));
    const streamMeta = encodeStreamStartFrame({
      streamId,
      fileName: "test.png",
      mimeType: "image/png",
      totalBytes: 2048,
      chunkSize: 1024,
      totalChunks: 2,
      sha256: "abc",
    });
    const urgentRpc = encodeJsonRpcFrame({
      jsonrpc: "2.0",
      method: "gui.abort",
    });

    // Enqueue bulk chunks first, then stream meta, then urgent RPC
    queue.enqueue(bulkChunk1, FrameType.STREAM_CHUNK);
    queue.enqueue(bulkChunk2, FrameType.STREAM_CHUNK);
    queue.enqueue(streamMeta, FrameType.STREAM_START);
    queue.enqueue(urgentRpc, FrameType.CONTROL_JSON_RPC);

    // Dequeue order MUST be: urgentRpc (P0) -> streamMeta (P1) -> bulkChunk1 (P2) -> bulkChunk2 (P2)
    const first = queue.dequeue()!;
    const second = queue.dequeue()!;
    const third = queue.dequeue()!;
    const fourth = queue.dequeue()!;

    expect(first[2]).toBe(FrameType.CONTROL_JSON_RPC);
    expect(second[2]).toBe(FrameType.STREAM_START);
    expect(third[2]).toBe(FrameType.STREAM_CHUNK);
    expect(fourth[2]).toBe(FrameType.STREAM_CHUNK);
  });

  test("OTP extraction accurately extracts codes and filters out interfering numbers", () => {
    const aliyun = extractOtpFromText(
      "【阿里云】验证码 839201，您正在进行登录操作，切勿将验证码泄露于他人，5分钟内有效。"
    );
    expect(aliyun?.code).toBe("839201");
    expect(aliyun?.sender).toBe("阿里云");

    const bank = extractOtpFromText(
      "【招商银行】您尾号1234的账户正在发生人民币5000.00元消费，动态码为 654321，请勿泄露。"
    );
    expect(bank?.code).toBe("654321");
    expect(bank?.sender).toBe("招商银行");

    const railway = extractOtpFromText(
      "【12306】您的登录校验码是 492018，请于3分钟内输入。"
    );
    expect(railway?.code).toBe("492018");
    expect(railway?.sender).toBe("12306");

    const github = extractOtpFromText(
      "[GitHub] Your verification code is 719304. Do not share it."
    );
    expect(github?.code).toBe("719304");
    expect(github?.sender).toBe("GitHub");

    const google = extractOtpFromText(
      "882910 is your Google verification code.",
      "Google"
    );
    expect(google?.code).toBe("882910");
    expect(google?.sender).toBe("Google");

    // Non-OTP promotional SMS should return null
    const promo = extractOtpFromText(
      "【京东】双11大促开启，尾号8848用户专享满300减50优惠券，退订回T"
    );
    expect(promo).toBeNull();

    // Travel/itinerary SMS with anti-fraud warning ("请勿提供验证码") and flight number ("ZH9949") must not false-trigger
    const flight = extractOtpFromText(
      "【阿里商旅】出票成功：杨**等1人10月31日合肥新桥国际机场 21:05降落 ZH9949；机票退改应通过原购票渠道办理，切勿轻信航班变动电话或短信通知，请勿提供验证码等信息。"
    );
    expect(flight).toBeNull();
  });
});

test("decoder rejects unsupported versions and oversized frames, then recovers", () => {
  const decoder = new FrameStreamDecoder(1024);
  const good = encodeJsonRpcFrame({ jsonrpc: "2.0", method: "test" });
  const wrongVersion = good.slice(); wrongVersion[1] = 2;
  expect(() => decoder.push(wrongVersion)).toThrow("version");
  expect(decoder.push(good)).toHaveLength(1);
  const oversized = good.slice(0, 8); new DataView(oversized.buffer).setUint32(4, 1025);
  expect(() => decoder.push(oversized)).toThrow("limit");
  expect(() => decoder.push(new Uint8Array(2065))).toThrow("buffer");
  expect(decoder.push(good)).toHaveLength(1);
});
