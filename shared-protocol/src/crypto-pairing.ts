import crypto from "node:crypto";
import { PairingQrPayload } from "./types.js";

export interface Ed25519Identity {
  deviceId: string;
  publicKeyBase64Url: string; // Raw 32-byte Ed25519 public key in base64url
  privateKeyPem: string;
  publicKeyPem: string;
}

const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

/**
 * Generate RFC 9562 UUIDv7 (time-ordered UUID with millisecond precision)
 */
export function generateUuidV7(nowMs = Date.now()): string {
  const bytes = crypto.randomBytes(16);
  const ts = BigInt(nowMs);
  bytes[0] = Number((ts >> 40n) & 0xffn);
  bytes[1] = Number((ts >> 32n) & 0xffn);
  bytes[2] = Number((ts >> 24n) & 0xffn);
  bytes[3] = Number((ts >> 16n) & 0xffn);
  bytes[4] = Number((ts >> 8n) & 0xffn);
  bytes[5] = Number(ts & 0xffn);

  // Version 7 (0111)
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  // Variant 10xx
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

/**
 * Generate a new Ed25519长期身份密钥对
 */
export function generateEd25519Identity(devicePrefix = "mac"): Ed25519Identity {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
  const spkiDer = publicKey.export({ type: "spki", format: "der" });
  const rawPub32 = spkiDer.subarray(spkiDer.byteLength - 32);
  const pubKeyBase64Url = rawPub32.toString("base64url");
  const shortHash = crypto
    .createHash("sha256")
    .update(rawPub32)
    .digest("hex")
    .slice(0, 12);

  return {
    deviceId: `${devicePrefix}_${shortHash}`,
    publicKeyBase64Url: pubKeyBase64Url,
    privateKeyPem: privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
    publicKeyPem: publicKey.export({ type: "spki", format: "pem" }).toString(),
  };
}

/**
 * Sign arbitrary message string or buffer with Ed25519 private key PEM
 */
export function signEd25519(privateKeyPem: string, data: string | Uint8Array): string {
  const privKey = crypto.createPrivateKey(privateKeyPem);
  const buf = typeof data === "string" ? Buffer.from(data, "utf8") : Buffer.from(data);
  return crypto.sign(null, buf, privKey).toString("base64url");
}

/**
 * Verify Ed25519 signature using raw 32-byte base64url public key
 */
export function verifyEd25519(
  rawPubKeyBase64Url: string,
  data: string | Uint8Array,
  signatureBase64Url: string
): boolean {
  try {
    const rawPub = Buffer.from(rawPubKeyBase64Url, "base64url");
    if (rawPub.byteLength !== 32) return false;
    const spkiDer = Buffer.concat([ED25519_SPKI_PREFIX, rawPub]);
    const pubKey = crypto.createPublicKey({
      key: spkiDer,
      format: "der",
      type: "spki",
    });
    const buf = typeof data === "string" ? Buffer.from(data, "utf8") : Buffer.from(data);
    const sig = Buffer.from(signatureBase64Url, "base64url");
    return crypto.verify(null, buf, pubKey, sig);
  } catch {
    return false;
  }
}

/**
 * Generate a 6-digit pairing PIN and ephemeral token
 */
export function createEphemeralPairingCredentials(ttlMs = 5 * 60 * 1000): {
  pinCode: string;
  ephemeralToken: string;
  expiresAtMs: number;
} {
  const pinNum = crypto.randomInt(100000, 999999);
  const ephemeralToken = crypto.randomBytes(16).toString("base64url");
  return {
    pinCode: String(pinNum),
    ephemeralToken,
    expiresAtMs: Date.now() + ttlMs,
  };
}

/**
 * Compute HMAC-SHA256 for initial pairing verification
 */
export function computePairingHmac(
  ephemeralToken: string,
  pinCode: string,
  phoneId: string,
  phonePubKeyBase64Url: string
): string {
  return crypto
    .createHmac("sha256", `${ephemeralToken}:${pinCode}`)
    .update(`${phoneId}:${phonePubKeyBase64Url}`)
    .digest("hex");
}

/**
 * Compute HMAC-SHA256 for mutual challenge-response authentication (Phone <-> Mac)
 */
export function computeAuthHmac(secretKey: string, message: string): string {
  return crypto
    .createHmac("sha256", secretKey)
    .update(message, "utf8")
    .digest("hex");
}

/**
 * Derive a deterministic shared WAN signaling room ID from Mac and Phone public keys
 */
export function deriveWanRoomId(
  macPubKeyBase64Url: string,
  phonePubKeyBase64Url: string
): string {
  return crypto
    .createHash("sha256")
    .update(`oms-wan-v1:${macPubKeyBase64Url}:${phonePubKeyBase64Url}`)
    .digest("hex")
    .slice(0, 32);
}

/**
 * Build compact pairing URI for QR code:
 * oms://pair?v=1&id=...&name=...&ip=...&port=19527&bt=FD9A&pk=...&pin=...&token=...&exp=...
 */
export function buildPairingUri(payload: PairingQrPayload): string {
  const params = new URLSearchParams({
    v: String(payload.v),
    id: payload.id,
    name: payload.name,
    ip: payload.ips.join(","),
    port: String(payload.port),
    bt: payload.btUuid,
    pk: payload.pubKeyBase64Url,
    pin: payload.pinCode,
    token: payload.ephemeralToken,
    exp: String(payload.expiresAtMs),
  });
  return `oms://pair?${params.toString()}`;
}

export function parsePairingUri(uri: string): PairingQrPayload {
  const url = new URL(uri);
  if (url.protocol !== "oms:" || (url.hostname !== "pair" && url.pathname !== "//pair")) {
    throw new Error(`Invalid OMS pairing URI scheme: ${uri}`);
  }
  const q = url.searchParams;
  return {
    v: Number(q.get("v") || "1"),
    id: q.get("id") || "",
    name: q.get("name") || "Mac",
    ips: (q.get("ip") || "").split(",").filter(Boolean),
    port: Number(q.get("port") || "19527"),
    btUuid: q.get("bt") || "FD9A",
    pubKeyBase64Url: q.get("pk") || "",
    pinCode: q.get("pin") || "",
    ephemeralToken: q.get("token") || "",
    expiresAtMs: Number(q.get("exp") || "0"),
  };
}
