import { expect, test } from "bun:test";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { AddressInfo } from "node:net";
import {
  decodeJsonRpcPayload,
  deriveWanRoomId,
  encodeFrame,
  encodeJsonRpcFrame,
  establishSecureSession,
  FrameStreamDecoder,
  FrameType,
  generateEd25519Identity,
  generateSecureEphemeral,
  phoneStart,
} from "@oms/shared-protocol";
import { PhoneSessionManager } from "../src/transport/session-manager.js";
import { simulatedFinish } from "./secure-phone-helper.js";
import { startBunWanRelayServer } from "../../wan-relay/server.mjs";

function waitFor(predicate: () => boolean, timeoutMs = 4000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (predicate()) {
        resolve();
        return;
      }
      if (Date.now() >= deadline) {
        reject(new Error("Timed out waiting for condition"));
        return;
      }
      setTimeout(tick, 20);
    };
    tick();
  });
}

test("oms-wan-relay rejects unauthorized requests and pairs host + phone with full v2 AEAD session", async () => {
  const authToken = crypto.randomBytes(24).toString("hex");
  const relay = startBunWanRelayServer({ authToken });
  const port = relay.port;
  const baseWsUrl = `ws://127.0.0.1:${port}/oms-wan/v1`;

  try {
    // 1. Health endpoint works without leaking secrets
    const healthRes = await fetch(`http://127.0.0.1:${port}/oms-wan/health`);
    expect(healthRes.status).toBe(200);
    const healthJson = (await healthRes.json()) as any;
    expect(healthJson.ok).toBe(true);
    expect(healthJson.service).toBe("oms-wan-relay");

    // 2. Unauthorized WebSocket upgrade is rejected
    const badClosed = await new Promise<boolean>((resolve) => {
      const badWs = new WebSocket(`${baseWsUrl}?room=${"a".repeat(32)}&role=phone`, {
        headers: { Authorization: "Bearer wrong-token" },
      } as any);
      badWs.onopen = () => resolve(false);
      badWs.onerror = () => resolve(true);
      badWs.onclose = () => resolve(true);
    });
    expect(badClosed).toBe(true);

    // 3. Setup PhoneSessionManager with a pre-trusted v2 phone device
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-wan-relay-test-"));
    const hostIdentity = generateEd25519Identity("mac");
    const phoneId = "phone_wan_e2e_01";
    const pairSecretHex = crypto.randomBytes(32).toString("hex");
    const wanRoomId = deriveWanRoomId(hostIdentity.publicKeyBase64Url, phoneId);

    const manager = new PhoneSessionManager({
      hostIdentity,
      lanPort: 0,
      streamDownloadDir: tmpDir,
      trustedDevices: [
        {
          phoneId,
          phoneName: "Redmi Test",
          phoneModel: "24115RA8EC",
          phonePubKeyBase64Url: "",
          wanRoomId,
          pairingVersion: 2,
          pairSecretHex,
          pairedAtMs: Date.now() - 10_000,
          lastSeenMs: Date.now() - 5_000,
          lastKnownLanIps: [],
        },
      ],
    });

    manager.configureWanRelay({
      enabled: true,
      wssUrl: baseWsUrl,
      authToken,
    });

    await waitFor(() => manager.getWanRelayStatus().standbyConnected);
    expect(manager.getWanRelayStatus().peerPaired).toBe(false);
    expect(manager.getActiveTier()).toBe("DISCONNECTED");

    // Helper to connect a simulated v2 phone client over the relay
    const connectPhoneOnce = async () => {
      let phoneSession: any = null;
      let pairCompleted = false;
      const decoder = new FrameStreamDecoder();
      let ephemeral = generateSecureEphemeral();
      let expectedMacProof = "";
      const receivedRequests: any[] = [];

      const ws = new WebSocket(`${baseWsUrl}?room=${wanRoomId}&role=phone`, {
        headers: { Authorization: `Bearer ${authToken}` },
      } as any);
      ws.binaryType = "arraybuffer";

      ws.onmessage = (ev) => {
        if (typeof ev.data === "string") {
          const ctrl = JSON.parse(ev.data);
          if (ctrl.omsRelay === 1 && ctrl.event === "peer_paired") {
            ephemeral = generateSecureEphemeral();
          }
          return;
        }
        const rawBytes = new Uint8Array(ev.data as ArrayBuffer);
        for (const outer of decoder.push(rawBytes)) {
          if (!phoneSession) {
            const msg = decodeJsonRpcPayload<any>(outer.payload);
            if (msg.method === "secure.hello") {
              const start = phoneStart(msg.params, "auth", phoneId, pairSecretHex, ephemeral);
              const est = establishSecureSession("phone", msg.params, start, pairSecretHex, ephemeral.privateKeyHex);
              expectedMacProof = est.macProofHex;
              phoneSession = { pending: est.session };
              ws.send(encodeJsonRpcFrame({ jsonrpc: "2.0", id: "secure_start", method: "secure.start", params: start }));
            }
          } else if (phoneSession.pending) {
            const msg = decodeJsonRpcPayload<any>(outer.payload);
            if (msg.id === "secure_start" && msg.result?.proofHex === expectedMacProof) {
              const activeSess = phoneSession.pending;
              phoneSession = activeSess;
              ws.send(
                activeSess.seal(
                  encodeJsonRpcFrame({
                    jsonrpc: "2.0",
                    id: "secure_finish",
                    method: "secure.finish",
                    params: simulatedFinish,
                  })
                )
              );
            }
          } else {
            const plain = phoneSession.open(encodeFrame(outer.frameType, outer.payload, outer.flags));
            for (const inner of new FrameStreamDecoder().push(plain)) {
              if (inner.frameType === FrameType.CONTROL_JSON_RPC) {
                const rpc = decodeJsonRpcPayload<any>(inner.payload);
                if (rpc.id === "secure_finish" && rpc.result?.ok) {
                  pairCompleted = true;
                } else if (rpc.method) {
                  receivedRequests.push(rpc);
                  ws.send(
                    phoneSession.seal(
                      encodeJsonRpcFrame({
                        jsonrpc: "2.0",
                        id: rpc.id,
                        result: { echoedMethod: rpc.method, overWan: true },
                      })
                    )
                  );
                }
              }
            }
          }
        }
      };

      await waitFor(() => pairCompleted && manager.getActiveTier() === "WAN_WEBRTC");
      return { ws, receivedRequests };
    };

    // First remote session
    const first = await connectPhoneOnce();
    const rpcRes1 = await manager.callPhone<any>("sys.getStatus", {}, 3000);
    expect(rpcRes1).toEqual({ echoedMethod: "sys.getStatus", overWan: true });
    expect(manager.getWanRelayStatus().authenticated).toBe(true);

    // Phone disconnects -> Mac stays parked on standby and cleanly returns to DISCONNECTED
    first.ws.close();
    await waitFor(() => manager.getActiveTier() === "DISCONNECTED" && !manager.getWanRelayStatus().peerPaired);
    expect(manager.getWanRelayStatus().standbyConnected).toBe(true);

    // Second remote session reconnects without Mac restarting anything
    const second = await connectPhoneOnce();
    const rpcRes2 = await manager.callPhone<any>("agent.ping", {}, 3000);
    expect(rpcRes2).toEqual({ echoedMethod: "agent.ping", overWan: true });
    second.ws.close();

    await manager.stop();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  } finally {
    relay.stop();
  }
});
