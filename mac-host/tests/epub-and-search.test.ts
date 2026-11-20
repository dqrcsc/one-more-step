import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { importEpubToVault, listImportedBooks } from "../src/knowledge/epub-importer.js";
import { MobileMemoryMirror, segmentTextForFts } from "../src/memory/mobile-memory-mirror.js";
import { SecretaryCore } from "../src/memory/secretary-core.js";

function createTestEpub(epubOutPath: string): void {
  const srcDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-epub-fixture-"));
  try {
    fs.mkdirSync(path.join(srcDir, "META-INF"), { recursive: true });
    fs.mkdirSync(path.join(srcDir, "OEBPS", "images"), { recursive: true });

    fs.writeFileSync(path.join(srcDir, "mimetype"), "application/epub+zip", "utf8");
    fs.writeFileSync(
      path.join(srcDir, "META-INF", "container.xml"),
      `<?xml version="1.0"?>
      <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles>
          <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
        </rootfiles>
      </container>`,
      "utf8"
    );

    // 1x1 PNG fixture
    fs.writeFileSync(
      path.join(srcDir, "OEBPS", "images", "diagram.png"),
      Buffer.from("89504e470d0a1a0a0000000d49484452", "hex")
    );

    fs.writeFileSync(
      path.join(srcDir, "OEBPS", "nav.xhtml"),
      `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <body>
          <nav epub:type="toc"><ol>
            <li><a href="ch1.xhtml">第一章 上下文工程的本质</a></li>
            <li><a href="ch2.xhtml">第二章 个人随身秘书与主动记忆</a></li>
          </ol></nav>
        </body>
      </html>`,
      "utf8"
    );

    fs.writeFileSync(
      path.join(srcDir, "OEBPS", "ch1.xhtml"),
      `<html xmlns="http://www.w3.org/1999/xhtml">
        <head><title>Chapter 1</title></head>
        <body>
          <h1>第一章 上下文工程的本质</h1>
          <p>把个人知识库、阅读摘录和日常决策组织成结构化上下文，比单纯堆叠提示词有效得多。</p>
          <img src="images/diagram.png" alt="架构示意图" />
        </body>
      </html>`,
      "utf8"
    );

    fs.writeFileSync(
      path.join(srcDir, "OEBPS", "ch2.xhtml"),
      `<html xmlns="http://www.w3.org/1999/xhtml">
        <head><title>Chapter 2</title></head>
        <body>
          <h1>第二章 个人随身秘书与主动记忆</h1>
          <blockquote>真正的智能助理应当具备持续记忆、未闭环事项追踪与克制的主动提醒。</blockquote>
          <p>关键词测试：量子记忆拓扑与跨端同步闭环。</p>
        </body>
      </html>`,
      "utf8"
    );

    fs.writeFileSync(
      path.join(srcDir, "OEBPS", "content.opf"),
      `<?xml version="1.0" encoding="utf-8"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>上下文工程实战指南</dc:title>
          <dc:creator>青简作者</dc:creator>
          <dc:language>zh-CN</dc:language>
          <dc:description>一本关于构建个人第二大脑与主动式 AI 随身秘书的电子书。</dc:description>
        </metadata>
        <manifest>
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
          <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
          <item id="img1" href="images/diagram.png" media-type="image/png"/>
        </manifest>
        <spine>
          <itemref idref="ch1"/>
          <itemref idref="ch2"/>
        </spine>
      </package>`,
      "utf8"
    );

    const zipRes = spawnSync("zip", ["-rq", epubOutPath, "."], { cwd: srcDir });
    if (zipRes.status !== 0) {
      throw new Error(`Failed creating test EPUB: ${zipRes.stderr?.toString()}`);
    }
  } finally {
    fs.rmSync(srcDir, { recursive: true, force: true });
  }
}

describe("EPUB Importer, Full-Vault Chinese FTS5 Search & Executive Secretary Core", () => {
  test("imports EPUB into chapterized vault Markdown, registers bookshelf, and indexes for Chinese FTS5 search", () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-epub-search-"));
    const vaultRoot = path.join(tmpDir, "vault");
    const omsHome = path.join(tmpDir, "oms_home");
    fs.mkdirSync(path.join(vaultRoot, "01_inputs", "books", "notes"), { recursive: true });
    fs.mkdirSync(path.join(vaultRoot, "04_topics"), { recursive: true });
    fs.writeFileSync(
      path.join(vaultRoot, "01_inputs", "books", "bookshelf.md"),
      `# bookshelf\n\n## reading\n\n| 书名 | 作者 | 阅读状态 | 最近同步 | 原始记录 | 阅读整理 |\n| --- | --- | --- | --- | --- | --- |\n`,
      "utf8"
    );
    fs.writeFileSync(
      path.join(vaultRoot, "04_topics", "agent_memory.md"),
      `---\ntitle: "Agent 记忆系统主题综述"\n---\n\n# Agent 记忆系统主题综述\n\n探讨工作记忆、长期知识沉淀与主动式随身秘书架构。\n`,
      "utf8"
    );

    const epubPath = path.join(tmpDir, "context_engineering.epub");
    createTestEpub(epubPath);

    try {
      const imp = importEpubToVault({ epubPath, vaultRoot });
      expect(imp.ok).toBe(true);
      expect(imp.title).toBe("上下文工程实战指南");
      expect(imp.author).toBe("青简作者");
      expect(imp.chapters).toHaveLength(2);
      expect(imp.assetsCopied).toBe(1);
      expect(imp.bookshelfUpdated).toBe(true);

      const ch1Content = fs.readFileSync(path.join(vaultRoot, imp.chapters[0].relPath), "utf8");
      expect(ch1Content).toContain("![架构示意图](assets/diagram.png)");
      expect(fs.existsSync(path.join(vaultRoot, imp.bookDirRelPath, "assets", "diagram.png"))).toBe(true);
      expect(fs.existsSync(path.join(vaultRoot, imp.noteRelPath))).toBe(true);

      const shelf = fs.readFileSync(path.join(vaultRoot, "01_inputs", "books", "bookshelf.md"), "utf8");
      expect(shelf).toContain("上下文工程实战指南");

      const books = listImportedBooks(vaultRoot);
      expect(books).toHaveLength(1);
      expect(books[0].chapterCount).toBe(2);

      // Verify Chinese segmentation & full-vault FTS5 BM25 search finds deep chapter 2 terms
      expect(segmentTextForFts("个人随身秘书与主动记忆")).toContain("随身");
      const mirror = new MobileMemoryMirror({ omsHomeDir: omsHome, vaultRootOverride: vaultRoot });
      try {
        const res = mirror.searchContext("量子记忆拓扑", 6);
        expect(res.hits.length).toBeGreaterThanOrEqual(1);
        expect(res.hits[0].source).toBe("vault");
        expect(res.hits[0].category).toBe("书库");
        expect(res.hits[0].snippet).toContain("量子记忆拓扑");
        expect((res.indexedVaultFiles || 0)).toBeGreaterThanOrEqual(4);

        // Verify safe document reading & traversal block
        const doc = mirror.readVaultDocument(res.hits[0].pathOrRef);
        expect(doc.ok).toBe(true);
        expect(doc.markdown).toContain("个人随身秘书与主动记忆");
        expect(mirror.readVaultDocument("../../etc/passwd").ok).toBe(false);
      } finally {
        mirror.close();
      }
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  test("SecretaryCore persists living profile & open loops and builds Today Brief with proactive nudges", () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-secretary-"));
    const vaultRoot = path.join(tmpDir, "vault");
    fs.mkdirSync(path.join(vaultRoot, "04_topics"), { recursive: true });
    fs.writeFileSync(path.join(vaultRoot, "04_topics", "chief_of_staff.md"), "# Chief of Staff\n", "utf8");

    try {
      const sec = new SecretaryCore(tmpDir);
      sec.saveProfileItems(["偏好直接给可执行方案", "通勤时喜欢听语音简报"]);
      const c1 = sec.upsertCommitment({ title: "本周整理 EPUB 电子书库", dueHint: "周五前" });
      expect(c1.status).toBe("open");

      const brief = sec.buildTodayBrief({
        vaultRoot,
        wechatDbPath: path.join(tmpDir, "nonexistent.db"),
        agentSessions: [
          {
            sessionId: "sess-1",
            backend: "codex",
            origin: "mobile",
            title: "优化检索索引",
            workspacePath: "/tmp/ws",
            projectDisplayName: "OneMoreStep",
            status: "completed",
            lastSummary: "已完成 FTS5 索引升级",
            updatedAtMs: Date.now() - 1000,
            canContinue: true,
          },
        ],
      });

      expect(brief.profileItems).toHaveLength(2);
      expect(brief.openLoops).toHaveLength(1);
      expect(brief.nudges.some((n) => n.kind === "agent_review")).toBe(true);
      expect(brief.nudges.some((n) => n.kind === "open_loop")).toBe(true);
      expect(brief.vaultFocusTitles).toContain("chief_of_staff");

      // Toggle commitment done & dismiss a nudge
      const toggled = sec.toggleCommitment(c1.id);
      expect(toggled?.status).toBe("done");
      sec.dismissNudge("nudge_agent_sess-1");

      const brief2 = sec.buildTodayBrief({
        vaultRoot,
        wechatDbPath: path.join(tmpDir, "nonexistent.db"),
        agentSessions: [],
      });
      expect(brief2.openLoops).toHaveLength(0);

      // Verify slow continuous living-memory evolution: deduplicates, reinforces, and records implicit commitments
      const ev1 = sec.evolveMemoryFromDialogue({
        profileAdditions: ["偏好直接给可执行方案", "习惯在手机端完成网页阅读与待办闭环"],
        implicitCommitments: [{ title: "下周二前完成 Agent 权限设计", dueHint: "下周二前" }],
      });
      expect(ev1.reinforcedProfile).toBe(1);
      expect(ev1.addedProfile).toBe(1);
      expect(ev1.addedCommitments).toBe(1);
      expect(sec.getProfileItems()).toContain("习惯在手机端完成网页阅读与待办闭环");
      expect(sec.listCommitments(false).some((c) => c.title === "下周二前完成 Agent 权限设计")).toBe(true);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});
