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
    fs.writeFileSync(noteFile, "---\ntype: source_note\n---\n\n## 脱水干货\n分析报告内容", "utf8");
    fs.writeFileSync(sourceFile, "---\ntype: source\n---\n\n保存的原文内容", "utf8");

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
          u.searchParams.get("articleId") === "7" &&
          u.searchParams.get("path") === "assets/fig1.png"
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
      expect(list.articles).toHaveLength(1);
      expect(list.articles[0].id).toBe(7);
      expect(list.articles[0].hasNote).toBe(true);
      expect(list.articles[0].hasSource).toBe(true);
      expect(list.articles[0].userNotes).toBe("关注评估框架");

      // Direct lookup by articleId
      const byId = await sup.fetchArticleDetail({ articleId: 7 });
      expect(byId.ok).toBe(true);
      expect(byId.noteMarkdown).toContain("分析报告内容");
      expect(byId.sourceMarkdown).toContain("保存的原文内容");
      expect(byId.summaryMarkdown).toBe("简要初筛摘要");

      // Fallback lookup by title for legacy phone items without articleId
      const byTitle = await sup.fetchArticleDetail({ title: "💬 上下文工程深读（初筛伴读笔记）" });
      expect(byTitle.ok).toBe(true);
      expect(byTitle.article?.id).toBe(7);
      expect(byTitle.sourceMarkdown).toContain("保存的原文内容");

      // Inline raster asset fetch
      const asset = await sup.fetchArticleAsset({ articleId: 7, doc: "source", path: "assets/fig1.png" });
      expect(asset.ok).toBe(true);
      expect(asset.mimeType).toBe("image/png");
      expect(Buffer.from(asset.dataBase64 || "", "base64").equals(tinyPng)).toBe(true);
    } finally {
      server.stop(true);
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});
