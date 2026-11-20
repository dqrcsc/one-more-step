import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { loadConfig } from "../src/config.ts";
import { ContextDatabase } from "../src/db.ts";
import { WeChatContextPipeline } from "../src/pipeline.ts";

describe("Edge Standby Atomic Handoff & Context Capsule Export", () => {
  test("commits phone standby handoff atomically to Vault without duplicate WeChat reply and queues deferred tasks", async () => {
    const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "oms-wx-handoff-"));
    try {
      const vaultDir = path.join(tmpRoot, "vault");
      const dataDir = path.join(tmpRoot, "data");
      fs.mkdirSync(path.join(vaultDir, "00_home"), { recursive: true });
      fs.writeFileSync(path.join(vaultDir, "00_home", "now.md"), "# 当前主线\n构建咫尺 v2.0 认知座舱\n");
      fs.writeFileSync(
        path.join(tmpRoot, ".env"),
        [
          "IGNORE_HOME_DOTENV=true",
          `VAULT_ROOT=${vaultDir}`,
          `DATA_DIR=${dataDir}`,
        ].join("\n")
      );

      const cfg = loadConfig(undefined, tmpRoot);
      const db = new ContextDatabase(cfg.dbPath);
      const pipeline = new WeChatContextPipeline(cfg, db);
      pipeline.clawbot.saveCredentials({
        botToken: "tok_test",
        baseUrl: "https://ilinkai.weixin.qq.com",
        botId: "bot_test",
        userId: "wx_user@im.wechat",
        savedAt: new Date().toISOString(),
      });
      db.setState("clawbot_updates_buf", "cursor_mac_v1");

      const snap = pipeline.exportEdgeStandbySnapshot();
      expect(snap.cursorCheckpoint.updatesBuf).toBe("cursor_mac_v1");
      expect(snap.contextCapsule.nowSummary).toContain("构建咫尺 v2.0");

      const handoffRes = await pipeline.commitEdgeStandbyHandoff({
        handoffId: "hoff_001",
        latestUpdatesBuf: "cursor_phone_v2",
        recipientUserId: "wx_user@im.wechat",
        latestContextToken: "ctx_phone_fresh",
        contextSourceTimeMs: 1763600000000,
        cursorAdvancedByPhone: true,
        processedArticles: [
          {
            messageKey: "bot_test:90001",
            senderId: "wx_user@im.wechat",
            url: "https://mp.weixin.qq.com/s/edge_night_article",
            title: "晨间手机直连初筛测试文章",
            author: "架构周刊",
            publishDate: "2026-11-20",
            digest: "探讨移动端随身认知座舱设计",
            userNotes: "早起阅读批注",
            englishSlug: "edge_cognitive_cockpit",
            triage: "process_later",
            extractionQualityStatus: "complete",
            rawMarkdownBody: "这是凌晨在手机端直连抓取的公众号完整正文内容。".repeat(6),
            wechatReplyText: "💧 脱水干货：移动端离线初筛闭环。\n🚦 分诊与下一步：process_later",
            companionNoteMarkdown: "## 💧 脱水干货\n移动端离线初筛闭环。\n\n## 🚦 分诊与下一步\nprocess_later",
            replyDeliveredToWeChat: true,
            modelUsed: "gpt-6.2-sol",
            createdAtMs: Date.now(),
          },
        ],
        deferredMessages: [
          {
            messageKey: "bot_test:90002",
            rawPayloadJson: JSON.stringify({
              message_id: "90002",
              from_user_id: "wx_user@im.wechat",
              message_type: 1,
              item_list: [{ type: 1, text_item: { text: "想法：散步时想到可以把代理做成免TUN模块" } }],
            }),
          },
        ],
      });

      expect(handoffRes.ok).toBe(true);
      expect(handoffRes.importedArticlesCount).toBe(1);
      expect(handoffRes.queuedDeferredCount).toBe(1);
      expect(handoffRes.cursorUpdated).toBe(true);
      expect(db.getState("clawbot_updates_buf")).toBe("cursor_phone_v2");
      expect(pipeline.clawbot.getContextToken("wx_user@im.wechat")).toBe("ctx_phone_fresh");

      const articles = db.listArticles({ limit: 10 });
      expect(articles.length).toBe(1);
      expect(articles[0].status).toBe("light_done");
      expect(articles[0].process_level).toBe("light");
      expect(fs.existsSync(articles[0].source_md_path)).toBe(true);
      expect(fs.existsSync(articles[0].note_md_path)).toBe(true);

      const outboxRows = db.db.query("SELECT id, text FROM reply_outbox").all() as Array<{ id: string; text: string }>;
      const agentTaskRows = db.db.query("SELECT message_key, status FROM agent_tasks").all() as Array<{ message_key: string; status: string }>;
      expect(outboxRows.some((r) => r.id.includes("edge_handoff"))).toBe(false);
      expect(
        outboxRows.some((r) => r.text.includes("想法已保存")) ||
          agentTaskRows.some((t) => t.message_key === "bot_test:90002")
      ).toBe(true);

      db.db.close();
    } finally {
      fs.rmSync(tmpRoot, { recursive: true, force: true });
    }
  });
});
