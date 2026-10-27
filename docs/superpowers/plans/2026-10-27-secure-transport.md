# Secure transport v2 implementation plan

> Execution: use scoped subagents for independent Mac and Android integration, with parent-owned protocol and cross-runtime vectors. Review each result before deployment. The named superpowers execution skills are not installed; this plan is executed with the available collaboration tools.

**Goal:** Reject plaintext business traffic and protect first pairing without transmitting a long-term secret.
**Architecture:** A shared byte-level protocol specification drives pure TypeScript/JCA crypto helpers; existing transport adapters feed one secure session per physical connection.
**Tech Stack:** Bun node:crypto, Android JCA, existing WebSocket/GATT framing.
**Spec:** `docs/plans/2026-10-27-secure-transport-design.md` (user approved 2026-10-27).

## Global constraints

No bulk deletion. No business data migration. No plaintext fallback, including old pairing records. Existing installed service stays unchanged until tests pass. No GitHub publish. APK candidate 1.4.0/code14. BLE/WAN transport reliability remains outside scope.

## Frozen wire/API contract

All hex is lowercase canonical; secrets and nonces are exactly 32 bytes; P-256 public keys are 65-byte uncompressed SEC1 (04 prefix), on-curve validated by platform. IDs are 1..128 ASCII `[A-Za-z0-9_.-]`. Mode is `pair` or `auth`. Strings are encoded as UTF8 with uint32 BE length per field; no JSON canonicalization is used for authentication.

`SecureHello = { v:2, macId, nonceMacHex, publicKeyHex }`
`SecureStart = { v:2, mode, phoneId, noncePhoneHex, publicKeyHex, proofHex }`
Plain hello notification method `secure.hello`; start request method `secure.start`, id `secure_start`. Server start result `{v:2, proofHex}`. All other pre-session requests (including old pair.request/auth.response) fail closed. Handshake expires in 30 seconds.

Transcript bytes = length-prefixed fields in order:
`oms-secure-v2`, `P256-HKDF-SHA256-AES256GCM`, mode, macId, phoneId, nonceMacHex, noncePhoneHex, macPublicKeyHex, phonePublicKeyHex.
`H = SHA256(transcript)`.
Phone proof = HMAC-SHA256(PSK bytes, fields(`oms-v2-phone-proof`, hex(H))).
Mac proof = HMAC-SHA256(PSK bytes, fields(`oms-v2-mac-proof`, hex(H))).
`root = HKDF-SHA256(IKM=ECDH32, salt=PSK32, info=fields('oms-v2-root',hex(H)), L=32)`.
For each label, `derive(label,L)=HKDF-SHA256(root,H,fields('oms-v2',label),L)`.
Labels `mac-key`, `phone-key` (32 bytes), `mac-iv`, `phone-iv` (4 bytes), `pair-secret` (32 bytes). Authenticated initial pair uses token as PSK; reconnect uses saved pair-secret. Pair-secret output is saved only for pair mode. Never sent on wire. Session ID = first16 bytes H.

Record: existing OMS outer version1 frame, type0x7e, flags0, payload = seq(uint64BE) || AES-GCM(inner完整OMS帧) || tag16. Nonce = directionIV4 || seq8. AAD = outerHeader8 || seq8 || sessionId16 || directionByte (Mac=0, Phone=1). Each direction starts0 and must receive exact-next sequence. Maximum inner bytes=16MiB-24 (outer payload cap16MiB); inner minimum8, header length exact, types1..6 only, flags0/version1. Reject nested encrypted frames. Limit each direction to 1GiB plaintext and 2^20 records; close/reconnect at limit, never reset counters under same keys. Encrypt only after queue scheduling; lock allocation+encrypt+write on Android. Any record failure invalidates context and closes connection. No retry with same context after a send failure.

After plaintext Mac proof, phone sends first encrypted request `secure.finish` id `secure_finish`, params `{phoneName,phoneModel,deviceSnapshot}`. Mac accepts only this message while waiting for finish. Metadata length limits/name<=256/model<=256 and snapshot shape validated. Mac then sends first encrypted response `{ok:true,pairingVersion:2,macId,wanRoomId}`. Only then register usable pipe/flush business queue; phone persists new config and enables business after verifying/decrypting finish ACK. Reserve one active token to valid start; release reservation on failed close/timeout, consume only successful pair. Existing pairing retained until successful finish. Re-pair revokes all old pipes/waiters.

TypeScript pure API (parent-owned `shared-protocol/src/secure-session.ts`):
```ts
export interface SecureHello { v:2; macId:string; nonceMacHex:string; publicKeyHex:string }
export interface SecureStart { v:2; mode:'pair'|'auth'; phoneId:string; noncePhoneHex:string; publicKeyHex:string; proofHex:string }
export function generateSecureEphemeral(): { privateKeyHex:string; publicKeyHex:string };
export function secureRandomHex(): string;
export function phoneStart(hello:SecureHello, mode:'pair'|'auth', phoneId:string, pskHex:string, ephemeral:{privateKeyHex:string;publicKeyHex:string}, noncePhoneHex?:string):SecureStart;
export function establishSecureSession(role:'mac'|'phone', hello:SecureHello, start:SecureStart, pskHex:string, privateKeyHex:string): {session:SecureSession; pairSecretHex:string; macProofHex:string};
// establish validates phone proof and all inputs; phone must also verify macProofHex before finish.
export function secureEqualHex(expected:string, actual:unknown):boolean;
export class SecureSession { seal(inner:Uint8Array):Uint8Array; open(record:Uint8Array):Uint8Array; destroy():void }
```
JCA pure API mirrors via `object SecureChannel` and nested `Hello`, `Start`, `Ephemeral`, `Established`, `Session`; string role/mode, `ByteArray` seal/open, `generateEphemeral()`, `randomHex()`, `phoneStart(...)`, `establish(...)`, `equalHex(...)`. Hex helpers `hex(ByteArray)`/`unhex(String)` available. No Android/JSON dependency in pure helper. Integration reconstructs complete received outer frame from DecodedFrame, then single-frame decode of opened inner frame (reject inner validation in helper).

## Task 1: shared crypto + vectors (parent)

Files: new `shared-protocol/src/secure-session.ts`, export in index.ts, new `shared-protocol/tests/secure-session.test.ts`, `shared-protocol/test-vectors/secure-v2.json`.
- [ ] Assert RFC5869 known output before crypto implementation.
- [ ] Implement exact API above with node:crypto platform operations.
- [ ] Fixed private scalars1/2, PSK=32 bytes 0x11, nonces32 bytes0x22/0x33, IDs mac_test/phone_test, mode pair. Export independently checked keys/proofs and bidirectional ciphertexts.
- [ ] Test `expect(()=>receiver.open(tampered)).toThrow()`; fresh receivers per negative case; repeat sequence, reflection, different transcript, wrong PSK, invalid point, malformed inner, closed context, bounds.
- [ ] Commit only parent files after integration agreement.

## Task 2: Android pure helper (Android crypto subagent)

Files: new `SecureChannel.kt`, `SecureChannelTest.kt` only.
- [ ] Implement JCA contract above, byte-for-byte parity; no dependency on Android JSONObject.
- [ ] Consume same static JSON vector values in JVM test (embedded constants or classpath fixture, no org.json mock dependence).
- [ ] Test JCA RFC5869 known vector and GCM known ciphertext; verify TypeScript-produced ciphertext/proofs and vice versa.
- [ ] Compile/test through parent after coordination; report any wire ambiguity before altering contract.

## Task 3: Mac integration (Mac subagent)

Files: session-manager.ts, daemon pairing generation/CLI copy, Mac tests + test helper. Modify trusted record with `pairingVersion?:number`; old missing version fails.
- [ ] Replace pre-auth handler with secure.hello/start/finish state machine and one-token reservation; retain old trust until finish.
- [ ] Route all business sendRaw sites to one `sendSecure` entry; raw send only handshake and already-sealed bytes. Decrypt before dispatch and bind context to pipe. Reject parser errors/unknown plaintext after establishment.
- [ ] Remove `/pair/resolve` secret resolution and install-page PIN actions. Generate token32hex, URIv2; no PIN prompt in CLI. Move telemetry into encrypted finish.
- [ ] Rewrite WebSocket client test helper using shared API. Exercise full pair/auth, plaintext rejection, stale trust, reservation/timeout, forged proof, crosspipe ACK, encrypted streams and receive byte scan. Keep existing substantive transport coverage.
- [ ] Run Mac tests/typecheck when parent helper ready, report changed paths and unresolved test failures.

## Task 4: Android integration (parent after crypto helper, or next scoped subagent turn)

Files: OmsTransportClient.kt, paired config storage/model, MainActivity copy.
- [ ] Reject old URI/PIN and insecure stored record with actionable re-pair message; no automatic clear of data.
- [ ] Add per-source secure pending/active session; existing temporary pairing WebSocket reuses common dispatcher and current-source lifecycle.
- [ ] Validate hello Mac identity from trusted URI/config; create start proof, verify Mac proof, send encrypted finish; only ACK activates/persists pairing and flushes queue.
- [ ] Every business write uses synchronized seal+send; failed write/decoder/AEAD closes and rejects waiting operations. Ensure BLE callbacks identify current GATT and stale sockets cannot reactivate.
- [ ] Validate state timeout/close cleanup and plaintext-negative paths; no raw telemetry before finish.

## Task 5: integration/review/package (parent)

- [ ] `bun run typecheck`, `bun test`, Android `:app:testDebugUnitTest :app:assembleDebug --offline --no-daemon`.
- [ ] Cross-runtime fixture exact match, review all raw/socket/GATT sends and one-time token handling, no secret logs.
- [ ] Update versions1.4.0/code14, docs/changelog/global skill template statements. Check APK same certificate and SHA256.
- [ ] Save old APK/metadata plus private pairing config backup without printing secrets. Deploy candidate APK under old daemon; manual OTA install. Then deploy host, generate new token only on local review surface; never transmit it over old channel.
- [ ] User completes new pairing; live filtered status confirms secure v2 then synthetic bidirectional file/ACK tests + reconnect + GUI confirmation. Record precisely what was/was not exercised.


## Execution evidence (2026-10-27)

Tasks 1–4 implemented. Shared TypeScript 8 new tests / 55 assertions; independent JCA 9 new tests; fixed public vectors also independently verified with Python cryptography. Mac full suite 49 pass / 391 assertions, two-workspace typecheck pass. Android full JVM suite 32 pass and APK build pass; final frozen-source build check passed before OTA. Review corrected discovery overwriting fresh credentials, stale-socket activation, GATT asynchronous write failure handling, handshake redial, and Bun queued-backpressure return semantics. Runtime deployment and real-device acceptance remain pending.
