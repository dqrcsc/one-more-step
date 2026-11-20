import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { WeChatBridgeSupervisor } from "../src/wechat/wechat-supervisor.js";

describe("WeChatBridgeSupervisor mobile Chisu RPC helpers", () => {
  test("fetches articles overview, resolves detail by id or title/url, and serves raster assets as base64", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-chisu-rpc-"));
    const noteFile = path.join(tmpDir, "a1_note.md");
    const sourceFile = path.join(tmpDir, "a1.md");
    const mediaCaptureFile = path.join(tmpDir, "2026_11_01_wechat_media_capture_test.md");
    const videoTranscriptFile = path.join(tmpDir, "2026_11_04_wechat_video_transcript_test.md");
    fs.writeFileSync(noteFile, "---\ntype: source_note\n---\n\n## 脱水干货\n分析报告内容", "utf8");
    fs.writeFileSync(sourceFile, "---\ntype: source\n---\n\n保存的原文内容", "utf8");
    fs.writeFileSync(
      mediaCaptureFile,
      "---\ntype: inbox_capture\ntitle: \"微信多媒体采集\"\n---\n\n- **关联附件 / 图片**：[pic.jpg](assets/pic.jpg)\n\n### 硬件原型验证笔记\nESP32 屏幕点屏实测记录",
      "utf8"
    );
    fs.writeFileSync(
      videoTranscriptFile,
      "---\ntype: source\nsource_type: wechat_video\n---\n\n视频口播完整转写正文内容",
      "utf8"
    );

    const tinyPng = Buffer.from("89504e470d0a1a0a0000000d49484452", "hex");

    const server = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch(req) {
        const u = new URL(req.url);
        if (u.pathname === "/api/overview") {
          return Response.json({
            ok: true,
            articles: [
              {
                id: 7,
                title: "上下文工程深读",
                author: "技术周刊",
                url: "https://mp.weixin.qq.com/s/test7",
                publish_date: "2026-11-19",
                created_at: "2026-11-20T09:00:00Z",
                status: "light_done",
                process_level: "light",
                triage: "process_now",
                user_notes: "关注评估框架",
                short_summary: "简要初筛摘要",
                digest: "原文导语",
                note_md_path: noteFile,
                source_md_path: sourceFile,
                isFallbackNote: false,
              },
            ],
            turns: [
              {
                id: 204,
                intent: "multimodal_ingest",
                user_text: "[多媒体消息: image]",
                assistant_reply: "🖼️ 已解密并归档媒体文件：`01_inputs/inbox/test.md`\n\n📋 内容提炼：\n### 硬件原型与屏幕驱动验证笔记\n核心结论：杜邦线较长需降频测试",
                media_summary: "image:unnamed",
                deposited_path: JSON.stringify([mediaCaptureFile]),
                created_at: "2026-11-20T10:00:00Z",
              },
              {
                id: 227,
                intent: "multimodal_ingest",
                user_text: "[多媒体消息: video]",
                assistant_reply: "🎬 已归档视频口播采集记录\n### 视频核心提炼\n关于 Agent 记忆架构的要点",
                media_summary: "video:unnamed",
                deposited_path: JSON.stringify([videoTranscriptFile]),
                created_at: "2026-11-20T09:30:00Z",
              },
            ],
          });
        }
        if (u.pathname === "/api/article" && u.searchParams.get("turnId") === "204") {
          return Response.json({
            ok: true,
            turn: {
              id: 204,
              intent: "multimodal_ingest",
              user_text: "[多媒体消息: image]",
              assistant_reply: "🖼️ 已解密并归档媒体文件：`01_inputs/inbox/test.md`\n\n📋 内容提炼：\n### 硬件原型与屏幕驱动验证笔记\n核心结论：杜邦线较长需降频测试",
              media_summary: "image:unnamed",
              deposited_path: JSON.stringify([mediaCaptureFile]),
              created_at: "2026-11-20T10:00:00Z",
            },
            depositedMarkdown: fs.readFileSync(mediaCaptureFile, "utf8"),
            depositedTruncated: false,
          });
        }
        if (u.pathname === "/api/article" && u.searchParams.get("id") === "7") {
          return Response.json({
            ok: true,
            article: {
              id: 7,
              title: "上下文工程深读",
              author: "技术周刊",
              url: "https://mp.weixin.qq.com/s/test7",
              publish_date: "2026-11-19",
              created_at: "2026-11-20T09:00:00Z",
              status: "light_done",
              process_level: "light",
              triage: "process_now",
              user_notes: "关注评估框架",
              short_summary: "简要初筛摘要",
              digest: "原文导语",
              note_md_path: noteFile,
              source_md_path: sourceFile,
            },
            noteMarkdown: fs.readFileSync(noteFile, "utf8"),
            sourceMarkdown: fs.readFileSync(sourceFile, "utf8"),
            noteTruncated: false,
            sourceTruncated: false,
          });
        }
        if (
          u.pathname === "/api/asset" &&
          ((u.searchParams.get("articleId") === "7" && u.searchParams.get("path") === "assets/fig1.png") ||
            (u.searchParams.get("turnId") === "204" && u.searchParams.get("path") === "assets/pic.jpg"))
        ) {
          return new Response(tinyPng, {
            status: 200,
            headers: { "Content-Type": "image/png" },
          });
        }
        return new Response("Not found", { status: 404 });
      },
    });

    try {
      const sup = new WeChatBridgeSupervisor({
        omsHomeDir: tmpDir,
        serverEntryPath: path.join(tmpDir, "noop.ts"),
        internalPort: server.port,
        logFilePath: path.join(tmpDir, "wx.log"),
      });
      // Mark worker as running against our local mock HTTP server
      (sup as any).isRunning = () => true;

      const list = await sup.fetchArticlesOverview(20);
      expect(list.ok).toBe(true);
      expect(list.articles).toHaveLength(3);
      expect(list.articles[0].entryKind).toBe("turn");
      expect(list.articles[0].turnId).toBe(204);
      expect(list.articles[0].mediaKind).toBe("image_note");
      expect(list.articles[0].title).toBe("硬件原型与屏幕驱动验证笔记");
      expect(list.articles[1].turnId).toBe(227);
      expect(list.articles[1].mediaKind).toBe("video");
      expect(list.articles[2].id).toBe(7);
      expect(list.articles[2].hasNote).toBe(true);
      expect(list.articles[2].hasSource).toBe(true);
      expect(list.articles[2].userNotes).toBe("关注评估框架");

      // Direct lookup by articleId
      const byId = await sup.fetchArticleDetail({ articleId: 7 });
      expect(byId.ok).toBe(true);
      expect(byId.noteMarkdown).toContain("分析报告内容");
      expect(byId.sourceMarkdown).toContain("保存的原文内容");
      expect(byId.summaryMarkdown).toBe("简要初筛摘要");

      // Multimodal image note detail by turnId promotes relative image link into inline Markdown image
      const byTurn = await sup.fetchArticleDetail({ turnId: 204 });
      expect(byTurn.ok).toBe(true);
      expect(byTurn.article?.turnId).toBe(204);
      expect(byTurn.article?.mediaKind).toBe("image_note");
      expect(byTurn.noteMarkdown).toContain("杜邦线较长需降频测试");
      expect(byTurn.sourceMarkdown).toContain("![原图附件](assets/pic.jpg)");
      expect(byTurn.sourceMarkdown).toContain("ESP32 屏幕点屏实测记录");

      // Fallback lookup by title for legacy phone items without articleId
      const byTitle = await sup.fetchArticleDetail({ title: "💬 上下文工程深读（初筛伴读笔记）" });
      expect(byTitle.ok).toBe(true);
      expect(byTitle.article?.id).toBe(7);
      expect(byTitle.sourceMarkdown).toContain("保存的原文内容");

      // Inline raster asset fetch (articleId + turnId)
      const asset = await sup.fetchArticleAsset({ articleId: 7, doc: "source", path: "assets/fig1.png" });
      expect(asset.ok).toBe(true);
      expect(asset.mimeType).toBe("image/png");
      expect(Buffer.from(asset.dataBase64 || "", "base64").equals(tinyPng)).toBe(true);

      const turnAsset = await sup.fetchArticleAsset({ turnId: 204, path: "assets/pic.jpg" });
      expect(turnAsset.ok).toBe(true);
      expect(Buffer.from(turnAsset.dataBase64 || "", "base64").equals(tinyPng)).toBe(true);
    } finally {
      server.stop(true);
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});
