import crypto from "node:crypto";

export interface SecureHello { v: 2; macId: string; nonceMacHex: string; publicKeyHex: string }
export interface SecureStart {
  v: 2; mode: "pair" | "auth"; phoneId: string; noncePhoneHex: string;
  publicKeyHex: string; proofHex: string;
}
export interface SecureEphemeral { privateKeyHex: string; publicKeyHex: string }
const RECORD_TYPE = 0x7e;
export const SECURE_MAX_INNER_BYTES = 16 * 1024 * 1024 - 24;
const MAX_RECORDS = 2 ** 20;
const MAX_BYTES = 1024 ** 3;
const DOMAIN = "oms-secure-v2";
const SUITE = "P256-HKDF-SHA256-AES256GCM";

function requireHex(value: unknown, bytes: number): Buffer {
  if (typeof value !== "string" || value.length !== bytes * 2 || !/^[a-f0-9]+$/.test(value)) {
    throw new Error("Invalid canonical secure-session hex");
  }
  return Buffer.from(value, "hex");
}
function requireId(value: unknown): asserts value is string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_.-]{1,128}$/.test(value)) throw new Error("Invalid secure-session identity");
}
function fields(...values: string[]): Buffer {
  return Buffer.concat(values.map(value => {
    const bytes = Buffer.from(value, "utf8");
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    return Buffer.concat([length, bytes]);
  }));
}
function publicKey(value: unknown): Buffer {
  const bytes = requireHex(value, 65);
  if (bytes[0] !== 4) throw new Error("Invalid P-256 public point");
  // OpenSSL validates curve membership, not merely the encoded length.
  const canonical = crypto.ECDH.convertKey(bytes, "prime256v1", undefined, undefined, "uncompressed") as Buffer;
  if (!canonical.equals(bytes)) throw new Error("Noncanonical P-256 public point");
  return bytes;
}
function validateHello(hello: SecureHello): void {
  if (!hello || hello.v !== 2) throw new Error("Secure transport v2 required");
  requireId(hello.macId);
  requireHex(hello.nonceMacHex, 32);
  publicKey(hello.publicKeyHex);
}
function transcript(hello: SecureHello, start: SecureStart): Buffer {
  validateHello(hello);
  if (!start || start.v !== 2 || !["pair", "auth"].includes(start.mode)) throw new Error("Invalid secure start");
  requireId(start.phoneId);
  requireHex(start.noncePhoneHex, 32);
  publicKey(start.publicKeyHex);
  return fields(DOMAIN, SUITE, start.mode, hello.macId, start.phoneId,
    hello.nonceMacHex, start.noncePhoneHex, hello.publicKeyHex, start.publicKeyHex);
}
function hmac(key: Uint8Array, bytes: Uint8Array): Buffer {
  return crypto.createHmac("sha256", key).update(bytes).digest();
}
export function hkdfSha256(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, size: number): Buffer {
  return Buffer.from(crypto.hkdfSync("sha256", ikm, salt, info, size));
}
export function secureRandomHex(): string { return crypto.randomBytes(32).toString("hex"); }
export function generateSecureEphemeral(): SecureEphemeral {
  const pair = crypto.createECDH("prime256v1");
  pair.generateKeys();
  return { privateKeyHex: pair.getPrivateKey("hex").padStart(64, "0"), publicKeyHex: pair.getPublicKey("hex", "uncompressed") };
}
export function secureEqualHex(expected: string, actual: unknown): boolean {
  if (!/^[a-f0-9]{64}$/.test(expected) || typeof actual !== "string" || !/^[a-f0-9]{64}$/.test(actual)) return false;
  return crypto.timingSafeEqual(Buffer.from(expected, "hex"), Buffer.from(actual, "hex"));
}
export function phoneStart(
  hello: SecureHello, mode: "pair" | "auth", phoneId: string, pskHex: string,
  ephemeral: SecureEphemeral, noncePhoneHex = secureRandomHex(),
): SecureStart {
  const psk = requireHex(pskHex, 32);
  const local = crypto.createECDH("prime256v1");
  local.setPrivateKey(requireHex(ephemeral.privateKeyHex, 32));
  if (local.getPublicKey("hex", "uncompressed") !== ephemeral.publicKeyHex) throw new Error("Mismatched ephemeral key");
  const start: SecureStart = { v: 2, mode, phoneId, noncePhoneHex, publicKeyHex: ephemeral.publicKeyHex, proofHex: "" };
  const hash = crypto.createHash("sha256").update(transcript(hello, start)).digest("hex");
  start.proofHex = hmac(psk, fields("oms-v2-phone-proof", hash)).toString("hex");
  return start;
}
export function establishSecureSession(
  role: "mac" | "phone", hello: SecureHello, start: SecureStart, pskHex: string, privateKeyHex: string,
): { session: SecureSession; pairSecretHex: string; macProofHex: string } {
  if (role !== "mac" && role !== "phone") throw new Error("Invalid secure-session role");
  const psk = requireHex(pskHex, 32);
  const hash = crypto.createHash("sha256").update(transcript(hello, start)).digest();
  const hashHex = hash.toString("hex");
  const proof = hmac(psk, fields("oms-v2-phone-proof", hashHex)).toString("hex");
  if (!secureEqualHex(proof, start.proofHex)) throw new Error("Secure handshake authentication failed");
  const local = crypto.createECDH("prime256v1");
  local.setPrivateKey(requireHex(privateKeyHex, 32));
  const ownPublic = role === "mac" ? hello.publicKeyHex : start.publicKeyHex;
  if (local.getPublicKey("hex", "uncompressed") !== ownPublic) throw new Error("Mismatched local ephemeral key");
  const shared = local.computeSecret(publicKey(role === "mac" ? start.publicKeyHex : hello.publicKeyHex));
  const root = hkdfSha256(shared, psk, fields("oms-v2-root", hashHex), 32);
  shared.fill(0);
  const derive = (label: string, size: number) => hkdfSha256(root, hash, fields("oms-v2", label), size);
  try {
    const session = new SecureSession(role, derive("mac-key", 32), derive("phone-key", 32),
      derive("mac-iv", 4), derive("phone-iv", 4), hash.subarray(0, 16));
    return { session, pairSecretHex: derive("pair-secret", 32).toString("hex"),
      macProofHex: hmac(psk, fields("oms-v2-mac-proof", hashHex)).toString("hex") };
  } finally { root.fill(0); psk.fill(0); }
}

function validateInner(frame: Buffer): void {
  if (frame.length < 8 || frame.length > SECURE_MAX_INNER_BYTES || frame[0] !== 0x4f || frame[1] !== 1 ||
    frame[2] < 1 || frame[2] > 7 || frame[3] !== 0 || frame.readUInt32BE(4) !== frame.length - 8) {
    throw new Error("Invalid encrypted business frame");
  }
}
/** One instance per physical connection. Any failure permanently invalidates it. */
export class SecureSession {
  private sendSequence = 0;
  private receiveSequence = 0;
  private sentBytes = 0;
  private receivedBytes = 0;
  private closed = false;
  private readonly sendKey: Buffer;
  private readonly receiveKey: Buffer;
  private readonly sendIV: Buffer;
  private readonly receiveIV: Buffer;
  private readonly sessionId: Buffer;
  private readonly sendDirection: number;
  constructor(role: "mac" | "phone", macKey: Buffer, phoneKey: Buffer, macIV: Buffer, phoneIV: Buffer, sessionId: Uint8Array) {
    this.sendDirection = role === "mac" ? 0 : 1;
    this.sendKey = role === "mac" ? macKey : phoneKey;
    this.receiveKey = role === "mac" ? phoneKey : macKey;
    this.sendIV = role === "mac" ? macIV : phoneIV;
    this.receiveIV = role === "mac" ? phoneIV : macIV;
    this.sessionId = Buffer.from(sessionId);
  }
  seal(inner: Uint8Array): Uint8Array {
    try {
      if (this.closed) throw new Error("Secure session closed");
      const plaintext = Buffer.from(inner);
      validateInner(plaintext);
      if (this.sendSequence >= MAX_RECORDS || this.sentBytes + plaintext.length > MAX_BYTES) throw new Error("Secure session limit; reconnect required");
      const header = Buffer.from([0x4f, 1, RECORD_TYPE, 0, 0, 0, 0, 0]);
      header.writeUInt32BE(8 + plaintext.length + 16, 4);
      const sequence = Buffer.alloc(8);
      sequence.writeBigUInt64BE(BigInt(this.sendSequence));
      const cipher = crypto.createCipheriv("aes-256-gcm", this.sendKey, Buffer.concat([this.sendIV, sequence]), { authTagLength: 16 });
      cipher.setAAD(Buffer.concat([header, sequence, this.sessionId, Buffer.from([this.sendDirection])]));
      const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
      const record = Buffer.concat([header, sequence, ciphertext, cipher.getAuthTag()]);
      this.sendSequence++;
      this.sentBytes += plaintext.length;
      return record;
    } catch (error) { this.destroy(); throw error; }
  }
  open(record: Uint8Array): Uint8Array {
    try {
      if (this.closed) throw new Error("Secure session closed");
      const data = Buffer.from(record);
      if (data.length < 40 || data.length > 16 * 1024 * 1024 + 8 || data[0] !== 0x4f || data[1] !== 1 ||
        data[2] !== RECORD_TYPE || data[3] !== 0 || data.readUInt32BE(4) !== data.length - 8) throw new Error("Invalid secure record");
      const sequence = data.subarray(8, 16);
      if (this.receiveSequence >= MAX_RECORDS || sequence.readBigUInt64BE() !== BigInt(this.receiveSequence)) throw new Error("Replayed or out-of-order secure record");
      const plaintextLength = data.length - 32;
      if (this.receivedBytes + plaintextLength > MAX_BYTES) throw new Error("Secure session limit; reconnect required");
      const cipher = crypto.createDecipheriv("aes-256-gcm", this.receiveKey, Buffer.concat([this.receiveIV, sequence]), { authTagLength: 16 });
      cipher.setAAD(Buffer.concat([data.subarray(0, 8), sequence, this.sessionId, Buffer.from([1 - this.sendDirection])]));
      cipher.setAuthTag(data.subarray(data.length - 16));
      const plaintext = Buffer.concat([cipher.update(data.subarray(16, data.length - 16)), cipher.final()]);
      validateInner(plaintext);
      this.receiveSequence++;
      this.receivedBytes += plaintext.length;
      return plaintext;
    } catch (error) { this.destroy(); throw error; }
  }
  destroy(): void {
    this.closed = true;
    this.sendKey.fill(0); this.receiveKey.fill(0); this.sendIV.fill(0); this.receiveIV.fill(0);
  }
}
