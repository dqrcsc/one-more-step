import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  buildEdgeSyncPayloadFromHost,
  parseBootstrapSubscriptionText,
} from "../src/wechat/edge-proxy-bootstrap.js";

describe("Milestone 1: Mac Edge Proxy Bootstrap & Dual-Engine Sync Payload", () => {
  test("parses SS-2022/Trojan subscriptions and resolves GPT-6.2 & Gemini 4/3.8 configs in isolation", async () => {
    const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "oms-edge-boot-"));
    try {
      const omsHome = path.join(tmpRoot, "oms");
      const wxHome = path.join(omsHome, "wechat");
      fs.mkdirSync(wxHome, { recursive: true });

      const k1 = Buffer.alloc(32, 0x31).toString("base64");
      const uinfo = Buffer.from(`2022-blake3-aes-256-gcm:${k1}`).toString("base64url");
      const rawSub = Buffer.from(
        `ss://${uinfo}@us1.example.com:8388#US-Node\ntrojan://tpwd@sg1.example.com:443?sni=cdn.example.com#SG-Node\n`
      ).toString("base64");
      const nodes = parseBootstrapSubscriptionText(rawSub, "SUB_1");
      expect(nodes.length).toBe(2);
      expect(nodes[0].protocol).toBe("ss");
      expect(nodes[1].protocol).toBe("trojan");

      fs.mkdirSync(path.join(omsHome, "cache"), { recursive: true });
      fs.writeFileSync(
        path.join(omsHome, "cache", "edge_proxy_nodes.json"),
        JSON.stringify({ fetchedAtMs: Date.now(), nodes })
      );
      fs.writeFileSync(
        path.join(wxHome, ".env"),
        [
          "SUB_VPN_1=https://sub.example.invalid/clash",
          "OPENAI_API_KEY=sk-isolated-test",
          "GEMINI_API_KEY=gm-isolated-test",
        ].join("\n")
      );

      const syncPayload = await buildEdgeSyncPayloadFromHost({
        omsHomeDir: omsHome,
        wechatHomeDir: wxHome,
        repoRootDir: tmpRoot,
        allowExternalUserEnv: false,
        wechatWorkerSnapshot: {
          clawbotAuth: {
            botToken: "bot-tok-123",
            baseUrl: "https://ilinkai.weixin.qq.com",
            botId: "bot_1",
            userId: "user_1@im.wechat",
          },
        },
      });

      expect(syncPayload.bootstrapNodes.length).toBe(2);
      expect(syncPayload.openai?.fastModel).toBe("gpt-6.2-sol");
      expect(syncPayload.openai?.reasoningModel).toBe("gpt-6.2-astra");
      expect(syncPayload.openai?.liveModel).toBe("gpt-realtime-2.1");
      expect(syncPayload.gemini?.fastModel).toBe("gemini-3.8-flash");
      expect(syncPayload.gemini?.flagshipModel).toBe("gemini-4-argon");
      expect(syncPayload.gemini?.liveModel).toBe("gemini-3.8-live");
      expect(syncPayload.clawbotAuth?.botId).toBe("bot_1");
    } finally {
      fs.rmSync(tmpRoot, { recursive: true, force: true });
    }
  });
});
