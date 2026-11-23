import fs from "node:fs";
import crypto from "node:crypto";
import path from "node:path";
import { Database } from "bun:sqlite";
import type {
  ChatSyncBatchParams,
  CurateBrowserUrlParams,
  CurateBrowserUrlResult,
  MemoryPromoteVaultParams,
  MemoryPromoteVaultResult,
  MemoryReadVaultDocResult,
  MemorySearchHit,
  MemorySearchVaultResult,
  RecentCuratedContextItem,
} from "@oms/shared-protocol";
import { parseDotEnvSafe } from "../wechat/edge-proxy-bootstrap.js";

const zhSegmenter =
  typeof Intl !== "undefined" && typeof (Intl as any).Segmenter === "function"
    ? new (Intl as any).Segmenter("zh-CN", { granularity: "word" })
    : null;

/**
 * Segments Chinese & Latin text into space-separated searchable tokens for SQLite FTS5.
 * Uses native `Intl.Segmenter("zh-CN")` so Chinese compounds match accurately in BM25.
 */
export function segmentTextForFts(raw: string): string {
  const text = String(raw || "").trim();
  if (!text) return "";
  const tokens: string[] = [];
  if (zhSegmenter) {
    for (const seg of zhSegmenter.segment(text)) {
      const piece = String(seg.segment || "").trim();
      if (!piece) continue;
      if (seg.isWordLike) {
        const lower = piece.toLowerCase();
        tokens.push(lower);
        // Also add CJK bigrams for long unsegmented Chinese compounds (>3 chars)
        if (/^[\u4e00-\u9fff]{4,}$/.test(lower)) {
          for (let i = 0; i < lower.length - 1; i++) {
            tokens.push(lower.slice(i, i + 2));
          }
        }
      }
    }
  } else {
    const fallback = text
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\s]+/gu, " ")
      .split(/\s+/)
      .filter(Boolean);
    tokens.push(...fallback);
  }
  return tokens.join(" ");
}

export function extractSearchTokens(query: string): string[] {
  const raw = String(query || "").trim();
  if (!raw) return [];
  const seen = new Set<string>();
  const add = (t: string) => {
    const clean = t.trim().toLowerCase();
    if (clean.length >= 1 && !seen.has(clean)) seen.add(clean);
  };
  if (zhSegmenter) {
    for (const seg of zhSegmenter.segment(raw)) {
      if (seg.isWordLike) add(String(seg.segment || ""));
    }
  }
  for (const part of raw.replace(/[^\p{L}\p{N}\s]+/gu, " ").split(/\s+/)) {
    if (part.trim()) add(part);
  }
  return Array.from(seen).slice(0, 10);
}

export interface MobileMemoryMirrorOptions {
  omsHomeDir: string;
  wechatEnvPath?: string;
  vaultRootOverride?: string;
}

/**
 * Milestone 2 (v2.0): Mac-side mirror for the 3-Tier Mobile Memory Funnel.
 * - L0: Mirrors non-incognito mobile chat threads & messages into `~/.one_more_step/chat/mobile_chat.db` (SQLite + FTS5)
 *       without polluting `VAULT_ROOT`.
 * - L1: Persists distilled `working_memory.json` so `ContextCapsule` carries cross-device continuity.
 * - L2: Promotes explicit highlights & Botanical Glass specimens into `VAULT_ROOT` with collision-safe paths (never overwrites).
 */
export class MobileMemoryMirror {
  private readonly omsHomeDir: string;
  private readonly chatDir: string;
  private readonly dbPath: string;
  private readonly workingMemoryPath: string;
  private readonly recentCuratedPath: string;
  private readonly wechatEnvPath: string;
  private readonly vaultRootOverride?: string;
  private db: Database;
  private lastVaultSyncMs = 0;

  constructor(opts: MobileMemoryMirrorOptions) {
    this.omsHomeDir = opts.omsHomeDir;
    this.chatDir = path.join(opts.omsHomeDir, "chat");
    fs.mkdirSync(this.chatDir, { recursive: true, mode: 0o700 });
    this.dbPath = path.join(this.chatDir, "mobile_chat.db");
    this.workingMemoryPath = path.join(this.chatDir, "working_memory.json");
    this.recentCuratedPath = path.join(this.chatDir, "recent_curated.json");
    this.wechatEnvPath = opts.wechatEnvPath || path.join(opts.omsHomeDir, "wechat", ".env");
    this.vaultRootOverride = opts.vaultRootOverride;

    this.db = new Database(this.dbPath, { create: true });
    this.initSchema();
  }

  private initSchema(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS vault_promotions (
        item_id TEXT PRIMARY KEY,
        digest TEXT NOT NULL,
        result_json TEXT NOT NULL
      );
      PRAGMA journal_mode = WAL;
      CREATE TABLE IF NOT EXISTS mobile_threads (
        thread_id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        pinned INTEGER NOT NULL DEFAULT 0,
        updated_at_ms INTEGER NOT NULL,
        created_at_ms INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS mobile_messages (
        message_id TEXT PRIMARY KEY,
        thread_id TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        reasoning TEXT NOT NULL DEFAULT '',
        model_used TEXT NOT NULL DEFAULT '',
        sources_json TEXT NOT NULL DEFAULT '[]',
        created_at_ms INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_mobile_messages_thread ON mobile_messages(thread_id, created_at_ms ASC);
      CREATE VIRTUAL TABLE IF NOT EXISTS mobile_messages_fts USING fts5(
        message_id UNINDEXED,
        thread_id UNINDEXED,
        title,
        content
      );
      CREATE TABLE IF NOT EXISTS vault_files_meta (
        rel_path TEXT PRIMARY KEY,
        mtime_ms INTEGER NOT NULL,
        size_bytes INTEGER NOT NULL,
        title TEXT NOT NULL,
        category TEXT NOT NULL,
        weight REAL NOT NULL
      );
      CREATE VIRTUAL TABLE IF NOT EXISTS vault_chunks_fts USING fts5(
        rel_path UNINDEXED,
        heading UNINDEXED,
        category UNINDEXED,
        title,
        segmented_title,
        segmented_body,
        raw_excerpt UNINDEXED
      );
    `);
  }

  public syncBatch(params: ChatSyncBatchParams): { syncedThreads: number; syncedMessages: number } {
    const incognitoThreadIds = new Set<string>();
    const threadTitleMap = new Map<string, string>();
    for (const t of params.threads || []) {
      if (t.incognito) {
        incognitoThreadIds.add(t.threadId);
      } else {
        threadTitleMap.set(t.threadId, t.title || "随身对话");
      }
    }

    const upsertThread = this.db.prepare(`
      INSERT INTO mobile_threads (thread_id, title, pinned, updated_at_ms, created_at_ms)
      VALUES ($id, $title, $pinned, $updated, $created)
      ON CONFLICT(thread_id) DO UPDATE SET
        title = excluded.title,
        pinned = excluded.pinned,
        updated_at_ms = MAX(mobile_threads.updated_at_ms, excluded.updated_at_ms)
    `);
    const checkMsg = this.db.prepare(`SELECT 1 FROM mobile_messages WHERE message_id = $id LIMIT 1`);
    const insertMsg = this.db.prepare(`
      INSERT INTO mobile_messages (message_id, thread_id, role, content, reasoning, model_used, sources_json, created_at_ms)
      VALUES ($id, $tid, $role, $content, $reasoning, $model, $sources, $created)
      ON CONFLICT(message_id) DO UPDATE SET
        content = excluded.content,
        reasoning = excluded.reasoning,
        model_used = excluded.model_used,
        sources_json = excluded.sources_json
    `);
    const deleteFts = this.db.prepare(`DELETE FROM mobile_messages_fts WHERE message_id = $id`);
    const insertFts = this.db.prepare(`
      INSERT INTO mobile_messages_fts (message_id, thread_id, title, content)
      VALUES ($id, $tid, $title, $content)
    `);
    const getTitle = this.db.prepare(`SELECT title FROM mobile_threads WHERE thread_id = $tid LIMIT 1`);

    let syncedThreads = 0;
    let syncedMessages = 0;

    const tx = this.db.transaction(() => {
      for (const t of params.threads || []) {
        if (t.incognito || !t.threadId) continue;
        upsertThread.run({
          $id: t.threadId,
          $title: t.title || "随身对话",
          $pinned: t.pinned ? 1 : 0,
          $updated: Number(t.updatedAtMs) || Date.now(),
          $created: Number(t.createdAtMs) || Date.now(),
        });
        syncedThreads++;
      }

      for (const m of params.messages || []) {
        if (!m.messageId || !m.threadId || incognitoThreadIds.has(m.threadId)) continue;
        const alreadyExists = Boolean(checkMsg.get({ $id: m.messageId }));
        insertMsg.run({
          $id: m.messageId,
          $tid: m.threadId,
          $role: m.role || "user",
          $content: m.content || "",
          $reasoning: m.reasoning || "",
          $model: m.modelUsed || "",
          $sources: m.sourcesJson || "[]",
          $created: Number(m.createdAtMs) || Date.now(),
        });
        const resolvedTitle =
          threadTitleMap.get(m.threadId) ||
          ((getTitle.get({ $tid: m.threadId }) as { title?: string } | null)?.title ?? "随身对话");
        if (alreadyExists) {
          deleteFts.run({ $id: m.messageId });
        }
        if ((m.content || "").trim()) {
          insertFts.run({
            $id: m.messageId,
            $tid: m.threadId,
            $title: resolvedTitle,
            $content: m.content,
          });
        }
        syncedMessages++;
      }
    });
    tx();

    if (Array.isArray(params.workingMemoryItems) && params.workingMemoryItems.length > 0) {
      this.saveWorkingMemory(params.workingMemoryItems);
    }

    return { syncedThreads, syncedMessages };
  }

  public loadWorkingMemory(): string[] {
    try {
      if (!fs.existsSync(this.workingMemoryPath)) return [];
      const parsed = JSON.parse(fs.readFileSync(this.workingMemoryPath, "utf8"));
      if (Array.isArray(parsed?.items)) {
        return parsed.items.map((x: unknown) => String(x || "").trim()).filter(Boolean).slice(0, 12);
      }
    } catch {
      // ignore
    }
    return [];
  }

  public saveWorkingMemory(items: string[]): void {
    const cleaned = Array.from(new Set(items.map((s) => s.trim()).filter(Boolean))).slice(0, 16);
    const tmp = `${this.workingMemoryPath}.tmp`;
    fs.writeFileSync(
      tmp,
      JSON.stringify({ updatedAtIso: new Date().toISOString(), items: cleaned }, null, 2),
      "utf8"
    );
    fs.renameSync(tmp, this.workingMemoryPath);
  }

  public prependWorkingMemoryObservation(entry: string, dedupeKeyHint?: string): void {
    const clean = String(entry || "").trim();
    if (!clean) return;
    const current = this.loadWorkingMemory();
    const hint = String(dedupeKeyHint || "").trim().toLowerCase();
    const filtered = current.filter((item) => {
      if (item === clean) return false;
      if (hint && hint.length >= 6 && item.toLowerCase().includes(hint)) return false;
      return true;
    });
    this.saveWorkingMemory([clean, ...filtered]);
  }

  public getRecentCuratedContext(limit = 8): RecentCuratedContextItem[] {
    try {
      if (!fs.existsSync(this.recentCuratedPath)) return [];
      const parsed = JSON.parse(fs.readFileSync(this.recentCuratedPath, "utf8"));
      if (Array.isArray(parsed?.items)) {
        return parsed.items.slice(0, Math.max(1, limit));
      }
    } catch {
      // ignore
    }
    return [];
  }

  public recordCuratedContent(item: RecentCuratedContextItem): void {
    const existing = this.getRecentCuratedContext(30).filter((x) => x.url !== item.url);
    const next = [item, ...existing].slice(0, 24);
    const tmp = `${this.recentCuratedPath}.tmp`;
    fs.writeFileSync(
      tmp,
      JSON.stringify({ updatedAtIso: new Date().toISOString(), items: next }, null, 2),
      "utf8"
    );
    fs.renameSync(tmp, this.recentCuratedPath);

    const vaultRoot = this.resolveVaultRoot();
    const relNote =
      item.relNotePath ||
      (item.notePath && item.notePath.startsWith(vaultRoot)
        ? path.relative(vaultRoot, item.notePath).replace(/\\/g, "/")
        : undefined);
    const kindLabel = item.mediaKind === "video" ? "视频" : "网页";
    const briefTakeaway = item.shortSummary
      .replace(/\s+/g, " ")
      .replace(/^📋\s*内容提炼[:：]?\s*/u, "")
      .trim()
      .slice(0, 110);
    const wmText = `[伴读沉淀·${kindLabel}] 《${item.title.slice(0, 48)}》${briefTakeaway ? `：${briefTakeaway}` : ""}${relNote ? ` (知识库: ${relNote})` : ""}`;
    this.prependWorkingMemoryObservation(wmText, item.title.slice(0, 24));
  }

  public curateDirectToVaultFallback(params: CurateBrowserUrlParams): CurateBrowserUrlResult {
    const vaultRoot = this.resolveVaultRoot();
    const rawUrl = String(params.url || "").trim();
    const isVideo = /(?:youtube\.com\/(?:watch|shorts|live)|youtu\.be\/|bilibili\.com\/video|douyin\.com\/)/i.test(rawUrl);
    const mediaKind: "video" | "article" = isVideo ? "video" : "article";
    const subDir = isVideo
      ? path.join(vaultRoot, "01_inputs", "video")
      : path.join(vaultRoot, "01_inputs", "articles", "web");
    fs.mkdirSync(subDir, { recursive: true });

    const dateStr = new Date().toISOString().slice(0, 10);
    const title = String(params.title || rawUrl).trim();
    const asciiSlug = title
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "")
      .slice(0, 40);
    const urlHash = Buffer.from(rawUrl).toString("hex").slice(0, 10);
    const baseName = `${isVideo ? "video_source" : "web_article"}_${dateStr.replace(/-/g, "_")}_${asciiSlug || urlHash}`;
    const sourcePath = path.join(subDir, `${baseName}.md`);
    const notePath = path.join(subDir, `${baseName}_note.md`);
    const bodyText = String(params.extractedText || "").trim();
    const summaryText = bodyText
      ? bodyText.replace(/\s+/g, " ").slice(0, 260)
      : `已从应用舱收录${isVideo ? "视频" : "网页"}《${title}》`;

    if (!fs.existsSync(sourcePath)) {
      const rawDoc = [
        "---",
        `title: ${JSON.stringify(title)}`,
        `source_url: ${JSON.stringify(rawUrl)}`,
        ...(params.author ? [`author: ${JSON.stringify(params.author)}`] : []),
        `captured_at: ${dateStr}`,
        "process_level: raw",
        "triage: reference",
        "---",
        "",
        `# ${title}`,
        "",
        `- 来源链接：${rawUrl}`,
        ...(params.author ? [`- 作者/频道：${params.author}`] : []),
        "",
        bodyText || "（待联网补充完整字幕或正文）",
        "",
      ].join("\n");
      fs.writeFileSync(sourcePath, rawDoc, "utf8");
    }

    const relSource = path.relative(vaultRoot, sourcePath).replace(/\\/g, "/");
    const relNote = path.relative(vaultRoot, notePath).replace(/\\/g, "/");
    if (!fs.existsSync(notePath)) {
      const noteDoc = [
        "---",
        `title: ${JSON.stringify(`${title}（伴读整理）`)}`,
        `source_path: ${JSON.stringify(relSource)}`,
        `source_url: ${JSON.stringify(rawUrl)}`,
        `captured_at: ${dateStr}`,
        "process_level: light",
        "triage: reference",
        "---",
        "",
        `# ${title} · 伴读提炼`,
        "",
        "## 核心摘要",
        "",
        summaryText,
        ...(params.userNote ? ["", "## 用户关注点", "", params.userNote] : []),
        "",
      ].join("\n");
      fs.writeFileSync(notePath, noteDoc, "utf8");
    }

    return {
      ok: true,
      url: rawUrl,
      title,
      mediaKind,
      shortSummary: summaryText,
      triage: "reference",
      sourcePath,
      notePath,
      relNotePath: relNote,
    };
  }

  public deleteWorkingMemoryItem(textOrId: string): boolean {
    const clean = String(textOrId || "").trim();
    if (!clean) return false;
    const current = this.loadWorkingMemory();
    const norm = (s: string) => s.toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, "").trim();
    const targetNorm = norm(clean);
    const filtered = current.filter((item) => {
      if (item === clean) return false;
      if (targetNorm && norm(item) === targetNorm) return false;
      return true;
    });
    if (filtered.length === current.length) return false;
    this.saveWorkingMemory(filtered);
    return true;
  }

  public resolveVaultRoot(): string {
    if (this.vaultRootOverride) return this.vaultRootOverride;
    const env = parseDotEnvSafe(this.wechatEnvPath);
    const configured = (env.VAULT_ROOT || "").trim();
    if (configured && fs.existsSync(configured)) {
      return configured;
    }
    const staging = path.join(path.dirname(this.chatDir), "vault_staging");
    fs.mkdirSync(staging, { recursive: true, mode: 0o700 });
    return staging;
  }

  public promoteToVault(params: MemoryPromoteVaultParams): MemoryPromoteVaultResult {
    const vaultRoot = this.resolveVaultRoot();
    const subFolder =
      params.kind === "botanical_specimen"
        ? path.join(vaultRoot, "00_Inbox", "FieldGuide")
        : path.join(vaultRoot, "00_Inbox", "MobileHighlights");
    fs.mkdirSync(subFolder, { recursive: true });

    const dateStr = new Date(params.createdAtMs || Date.now()).toISOString().slice(0, 10);
    const safeStem = this.sanitizeFileName(params.slugHint || params.title || params.kind);

    if (!params.itemId?.trim()) throw new Error("Promotion itemId is required");
    const image = params.jpegBase64?.trim() ? Buffer.from(params.jpegBase64, "base64") : undefined;
    if (image && image.length > 4 * 1024 * 1024) throw new Error("Promotion image exceeds 4 MiB");
    const digest = crypto.createHash("sha256").update(JSON.stringify([
      vaultRoot, params.kind, params.title, params.slugHint || "", params.markdownContent || "",
      params.createdAtMs || 0, params.imageFileName || "", image?.toString("base64") || ""
    ])).digest("hex");
    const previous = this.db.query("SELECT digest, result_json FROM vault_promotions WHERE item_id = ?")
      .get(params.itemId) as {digest: string; result_json: string} | null;
    if (previous && previous.digest !== digest) throw new Error("Promotion itemId already used with different content");
    let result: MemoryPromoteVaultResult;
    if (previous) {
      result = JSON.parse(previous.result_json);
    } else {
      let savedImagePath: string | undefined;
      if (image) {
        const assetsDir = path.join(subFolder, "assets");
        fs.mkdirSync(assetsDir, { recursive: true });
        const stem = this.sanitizeFileName((params.imageFileName || `${dateStr}_${safeStem}`).replace(/\.(jpe?g|png)$/i, ""));
        // Include the operation digest so even unfinished reservations cannot collide.
        savedImagePath = this.allocateCollisionFreePath(assetsDir, `${stem}_${crypto.createHash("sha256").update(params.itemId).digest("hex").slice(0, 16)}`, ".jpg");
      }
      const noteStem = `${dateStr}_${safeStem}_${crypto.createHash("sha256").update(params.itemId).digest("hex").slice(0, 16)}`;
      result = { ok: true, savedNotePath: this.allocateCollisionFreePath(subFolder, noteStem, ".md"), savedImagePath };
      // Persist the reservation BEFORE either file: retries after a crash recover the same paths.
      this.db.query("INSERT INTO vault_promotions (item_id, digest, result_json) VALUES (?, ?, ?)")
        .run(params.itemId, digest, JSON.stringify(result));
    }
    const writeOnce = (target: string, bytes: Buffer) => {
      if (fs.existsSync(target)) {
        if (!fs.readFileSync(target).equals(bytes)) throw new Error("Promoted file was modified; refusing to overwrite");
        return;
      }
      const tmp = `${target}.${crypto.randomUUID()}.tmp`;
      const fd = fs.openSync(tmp, "wx", 0o600);
      try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
      try { fs.linkSync(tmp, target); } finally { fs.unlinkSync(tmp); }
    };
    if (result.savedImagePath && image) writeOnce(result.savedImagePath, image);
    let body = params.markdownContent || "";
    if (result.savedImagePath && !body.includes(path.basename(result.savedImagePath))) {
      body = `![[assets/${path.basename(result.savedImagePath)}]]\n\n${body}`;
    }
    writeOnce(result.savedNotePath, Buffer.from(body.endsWith("\n") ? body : `${body}\n`));
    return result;
  }

  private classifyVaultCategory(relPath: string): { category: string; weight: number } {
    const p = relPath.replace(/\\/g, "/").toLowerCase();
    if (p.startsWith("04_topics/")) return { category: "主题", weight: 1.45 };
    if (p.startsWith("03_ideas/")) return { category: "想法", weight: 1.35 };
    if (p.startsWith("05_outputs/")) return { category: "产出", weight: 1.25 };
    if (p.startsWith("01_inputs/books/")) return { category: "书库", weight: 1.2 };
    if (p.startsWith("01_inputs/papers/")) return { category: "论文", weight: 1.12 };
    if (p.startsWith("01_inputs/video/")) return { category: "视频", weight: 1.05 };
    if (p.startsWith("01_inputs/") || p.startsWith("00_inbox/")) return { category: "资料", weight: 1.0 };
    if (p.startsWith("00_home/")) return { category: "主页", weight: 1.15 };
    if (p.startsWith("02_conversations/")) return { category: "对话归档", weight: 0.9 };
    if (p.startsWith("06_archive/")) return { category: "归档", weight: 0.65 };
    if (p.startsWith("_system/")) return { category: "系统", weight: 0.35 };
    return { category: "知识库", weight: 1.0 };
  }

  private extractDocTitle(relPath: string, rawMarkdown: string): string {
    const fmMatch = rawMarkdown.match(/^\uFEFF?\s*---\r?\n([\s\S]*?)\r?\n---/);
    if (fmMatch) {
      const titleLine = fmMatch[1].match(/^title:\s*(.+)$/m);
      if (titleLine?.[1]) {
        const cleaned = titleLine[1].trim().replace(/^["']|["']$/g, "").trim();
        if (cleaned) return cleaned;
      }
    }
    const h1 = rawMarkdown.match(/^#\s+(.+)$/m);
    if (h1?.[1]?.trim()) return h1[1].trim().slice(0, 120);
    return path.basename(relPath, ".md");
  }

  private chunkMarkdownForIndex(rawMarkdown: string): Array<{ heading: string; body: string }> {
    const stripped = rawMarkdown.replace(/^\uFEFF?\s*---\r?\n[\s\S]*?\r?\n---\r?\n*/, "").trim();
    if (!stripped) return [];
    const lines = stripped.split(/\r?\n/);
    const chunks: Array<{ heading: string; body: string }> = [];
    let currentHeading = "";
    let buf: string[] = [];
    let bufLen = 0;

    const flush = () => {
      const text = buf.join("\n").trim();
      if (text) {
        chunks.push({ heading: currentHeading, body: text });
      }
      buf = [];
      bufLen = 0;
    };

    for (const line of lines) {
      const hMatch = line.match(/^(#{1,3})\s+(.+)$/);
      if (hMatch) {
        if (bufLen > 120) flush();
        currentHeading = hMatch[2].trim().slice(0, 100);
      }
      buf.push(line);
      bufLen += line.length + 1;
      if (bufLen >= 1600) {
        flush();
      }
      if (chunks.length >= 40) break;
    }
    if (chunks.length < 40) flush();
    return chunks;
  }

  public syncVaultIndex(force = false): { indexedFiles: number; indexedChunks: number; updatedFiles: number } {
    const now = Date.now();
    const vaultRoot = this.resolveVaultRoot();
    if (!fs.existsSync(vaultRoot)) {
      return { indexedFiles: 0, indexedChunks: 0, updatedFiles: 0 };
    }
    if (!force && this.lastVaultSyncMs > 0 && now - this.lastVaultSyncMs < 30_000) {
      const fileCount = (this.db.query("SELECT COUNT(*) AS c FROM vault_files_meta").get() as { c: number })?.c ?? 0;
      const chunkCount = (this.db.query("SELECT COUNT(*) AS c FROM vault_chunks_fts").get() as { c: number })?.c ?? 0;
      return { indexedFiles: fileCount, indexedChunks: chunkCount, updatedFiles: 0 };
    }

    const diskFiles = this.collectAllVaultMarkdownFiles(vaultRoot, 2500);
    const existingRows = this.db
      .query("SELECT rel_path, mtime_ms, size_bytes FROM vault_files_meta")
      .all() as Array<{ rel_path: string; mtime_ms: number; size_bytes: number }>;
    const existingMap = new Map(existingRows.map((r) => [r.rel_path, r]));
    const seenRel = new Set<string>();

    const toUpsert: Array<{
      relPath: string;
      absPath: string;
      mtimeMs: number;
      sizeBytes: number;
    }> = [];

    for (const f of diskFiles) {
      seenRel.add(f.relPath);
      const prev = existingMap.get(f.relPath);
      if (!prev || prev.mtime_ms !== f.mtimeMs || prev.size_bytes !== f.sizeBytes) {
        toUpsert.push(f);
      }
    }

    const toDelete: string[] = [];
    for (const rel of existingMap.keys()) {
      if (!seenRel.has(rel)) toDelete.push(rel);
    }

    if (toUpsert.length > 0 || toDelete.length > 0) {
      const delMeta = this.db.prepare("DELETE FROM vault_files_meta WHERE rel_path = $rel");
      const delChunks = this.db.prepare("DELETE FROM vault_chunks_fts WHERE rel_path = $rel");
      const upsertMeta = this.db.prepare(`
        INSERT OR REPLACE INTO vault_files_meta (rel_path, mtime_ms, size_bytes, title, category, weight)
        VALUES ($rel, $mtime, $size, $title, $cat, $weight)
      `);
      const insChunk = this.db.prepare(`
        INSERT INTO vault_chunks_fts (rel_path, heading, category, title, segmented_title, segmented_body, raw_excerpt)
        VALUES ($rel, $heading, $cat, $title, $segTitle, $segBody, $rawExcerpt)
      `);

      const tx = this.db.transaction(() => {
        for (const rel of toDelete) {
          delMeta.run({ $rel: rel });
          delChunks.run({ $rel: rel });
        }
        for (const item of toUpsert) {
          let raw = "";
          try {
            raw = fs.readFileSync(item.absPath, "utf8").slice(0, 120_000);
          } catch {
            continue;
          }
          const title = this.extractDocTitle(item.relPath, raw);
          const { category, weight } = this.classifyVaultCategory(item.relPath);
          const segTitle = segmentTextForFts(`${title} ${path.basename(item.relPath, ".md")}`);
          delChunks.run({ $rel: item.relPath });
          upsertMeta.run({
            $rel: item.relPath,
            $mtime: item.mtimeMs,
            $size: item.sizeBytes,
            $title: title,
            $cat: category,
            $weight: weight,
          });
          const chunks = this.chunkMarkdownForIndex(raw);
          if (chunks.length === 0) {
            insChunk.run({
              $rel: item.relPath,
              $heading: "",
              $cat: category,
              $title: title,
              $segTitle: segTitle,
              $segBody: segTitle,
              $rawExcerpt: title,
            });
          } else {
            for (const ch of chunks) {
              const segBody = segmentTextForFts(`${ch.heading} ${ch.body}`);
              const cleanExcerpt = ch.body.replace(/\s+/g, " ").trim().slice(0, 420);
              insChunk.run({
                $rel: item.relPath,
                $heading: ch.heading,
                $cat: category,
                $title: title,
                $segTitle: segTitle,
                $segBody: segBody,
                $rawExcerpt: cleanExcerpt,
              });
            }
          }
        }
      });
      tx();
    }

    this.lastVaultSyncMs = now;
    const fileCount = (this.db.query("SELECT COUNT(*) AS c FROM vault_files_meta").get() as { c: number })?.c ?? 0;
    const chunkCount = (this.db.query("SELECT COUNT(*) AS c FROM vault_chunks_fts").get() as { c: number })?.c ?? 0;
    return { indexedFiles: fileCount, indexedChunks: chunkCount, updatedFiles: toUpsert.length };
  }

  public readVaultDocument(relPath: string, maxChars = 120_000): MemoryReadVaultDocResult {
    const cleanRel = String(relPath || "").trim().replace(/^\/+/, "");
    if (!cleanRel || cleanRel.includes("..") || cleanRel.includes("\\")) {
      return { ok: false, error: "Invalid vault relative path" };
    }
    const vaultRoot = path.resolve(this.resolveVaultRoot());
    const targetAbs = path.resolve(vaultRoot, cleanRel);
    if (!targetAbs.startsWith(`${vaultRoot}${path.sep}`) && targetAbs !== vaultRoot) {
      return { ok: false, error: "Path escapes vault root" };
    }
    if (!fs.existsSync(targetAbs)) {
      return { ok: false, error: "Vault document not found" };
    }
    try {
      const realRoot = fs.realpathSync(vaultRoot);
      const realTarget = fs.realpathSync(targetAbs);
      if (!realTarget.startsWith(`${realRoot}${path.sep}`)) return { ok: false, error: "Path escapes vault root" };
      const fd = fs.openSync(realTarget, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
      let raw: string;
      try {
        const st = fs.fstatSync(fd);
        if (!st.isFile()) return { ok: false, error: "Not a file" };
        const cap = 4 * 1024 * 1024;
        if (st.size > cap) return { ok: false, error: "Vault document exceeds 4 MiB" };
        const bytes = Buffer.alloc(cap + 1);
        let size = 0;
        while (size < bytes.length) {
          const n = fs.readSync(fd, bytes, size, bytes.length - size, null);
          if (!n) break;
          size += n;
        }
        if (size > cap) return { ok: false, error: "Vault document exceeds 4 MiB" };
        raw = bytes.subarray(0, size).toString("utf8");
      } finally { fs.closeSync(fd); }
      const charLimit = Number.isFinite(maxChars) ? Math.max(1, Math.min(120_000, maxChars)) : 120_000;
      const truncated = raw.length > charLimit;
      const markdown = truncated ? raw.slice(0, charLimit) : raw;
      const { category } = this.classifyVaultCategory(cleanRel);
      return {
        ok: true,
        relPath: cleanRel,
        title: this.extractDocTitle(cleanRel, raw),
        category,
        markdown,
        truncated,
      };
    } catch (err: any) {
      return { ok: false, error: err?.message || String(err) };
    }
  }

  public searchContext(query: string, limit = 8): MemorySearchVaultResult {
    const q = (query || "").trim();
    if (!q) return { hits: [] };
    const maxHits = Math.max(1, Math.min(24, limit));
    const tokens = extractSearchTokens(q);
    if (tokens.length === 0) return { hits: [] };

    const syncStats = this.syncVaultIndex(false);
    const ftsExpr = tokens.map((t) => `"${t.replace(/"/g, '""')}"`).join(" OR ");
    const lowerQ = q.toLowerCase();

    const scoredHits: Array<MemorySearchHit & { _rank: number }> = [];

    // 1. Full-Vault Incremental FTS5 Search with Chinese Segmentation + BM25 + Category Weighting
    try {
      const rows = this.db
        .query(
          `SELECT
             v.rel_path,
             v.heading,
             v.category,
             v.title,
             v.raw_excerpt,
             m.weight AS cat_weight,
             bm25(vault_chunks_fts, 4.0, 3.0, 1.0) AS raw_bm25
           FROM vault_chunks_fts v
           LEFT JOIN vault_files_meta m ON m.rel_path = v.rel_path
           WHERE vault_chunks_fts MATCH $expr
           ORDER BY raw_bm25 ASC
           LIMIT $lim`
        )
        .all({ $expr: ftsExpr, $lim: maxHits * 4 }) as Array<{
        rel_path: string;
        heading: string;
        category: string;
        title: string;
        raw_excerpt: string;
        cat_weight: number | null;
        raw_bm25: number;
      }>;

      const bestByRelPath = new Map<string, MemorySearchHit & { _rank: number }>();
      for (const r of rows) {
        const weight = Number(r.cat_weight || 1.0);
        // bm25 is negative (more negative = stronger match)
        let score = Math.max(0.1, -Number(r.raw_bm25 || -0.5)) * weight;
        const lowerTitle = (r.title || "").toLowerCase();
        const lowerExcerpt = (r.raw_excerpt || "").toLowerCase();
        if (lowerTitle.includes(lowerQ)) score *= 1.8;
        if (lowerExcerpt.includes(lowerQ)) score *= 1.35;
        const matchedTokenCount = tokens.filter((t) => lowerTitle.includes(t) || lowerExcerpt.includes(t)).length;
        score *= 1 + matchedTokenCount * 0.18;

        const snippet = this.extractFocusedSnippet(r.raw_excerpt || "", tokens, lowerQ);
        const candidate: MemorySearchHit & { _rank: number } = {
          source: "vault",
          title: r.title || path.basename(r.rel_path, ".md"),
          pathOrRef: r.rel_path,
          snippet,
          heading: r.heading || undefined,
          category: r.category || "知识库",
          score: Number(score.toFixed(2)),
          _rank: score,
        };
        const prev = bestByRelPath.get(r.rel_path);
        if (!prev || candidate._rank > prev._rank) {
          bestByRelPath.set(r.rel_path, candidate);
        }
      }
      scoredHits.push(...bestByRelPath.values());
    } catch {
      // ignore FTS syntax edge cases
    }

    // 2. L0 Mobile Chat SQLite FTS5 + LIKE fallback (covers CJK substrings cleanly)
    const seenChatThreads = new Set<string>();
    try {
      const rows = this.db
        .query(
          `SELECT title, thread_id, snippet(mobile_messages_fts, 3, '[', ']', '…', 36) AS snip, bm25(mobile_messages_fts) AS raw_bm25
           FROM mobile_messages_fts
           WHERE mobile_messages_fts MATCH $expr
           ORDER BY raw_bm25 ASC
           LIMIT $lim`
        )
        .all({ $expr: ftsExpr, $lim: maxHits }) as Array<{
        title: string;
        thread_id: string;
        snip: string;
        raw_bm25: number;
      }>;
      for (const r of rows) {
        if (seenChatThreads.has(r.thread_id)) continue;
        seenChatThreads.add(r.thread_id);
        const score = Math.max(0.2, -Number(r.raw_bm25 || -0.5)) * 1.1;
        scoredHits.push({
          source: "mobile_chat",
          title: r.title || "手机历史对话",
          pathOrRef: `thread:${r.thread_id}`,
          snippet: r.snip || "",
          category: "手机对话",
          score: Number(score.toFixed(2)),
          _rank: score,
        });
      }
    } catch {}

    if (seenChatThreads.size < 3) {
      try {
        const rows = this.db
          .query(
            `SELECT m.thread_id, COALESCE(t.title, '手机历史对话') AS title, m.content
             FROM mobile_messages m
             LEFT JOIN mobile_threads t ON t.thread_id = m.thread_id
             WHERE m.content LIKE $pat OR t.title LIKE $pat
             ORDER BY m.created_at_ms DESC
             LIMIT $lim`
          )
          .all({ $pat: `%${q}%`, $lim: Math.min(5, maxHits) }) as Array<{
          thread_id: string;
          title: string;
          content: string;
        }>;
        for (const r of rows) {
          if (seenChatThreads.has(r.thread_id)) continue;
          seenChatThreads.add(r.thread_id);
          scoredHits.push({
            source: "mobile_chat",
            title: r.title,
            pathOrRef: `thread:${r.thread_id}`,
            snippet: this.extractFocusedSnippet(r.content || "", tokens, lowerQ),
            category: "手机对话",
            score: 1.15,
            _rank: 1.15,
          });
        }
      } catch {}
    }

    // 3. WeChat Chisu SQLite search (articles + multimodal image/video/idea turns)
    const wechatDbPath = path.join(this.omsHomeDir, "wechat", "data", "wechat_context.db");
    if (fs.existsSync(wechatDbPath)) {
      let wxDb: Database | null = null;
      try {
        wxDb = new Database(wechatDbPath, { readonly: true });
        const likePat = `%${q}%`;
        const artRows = wxDb
          .query(
            `SELECT id, title, author, short_summary, digest, user_notes
             FROM articles
             WHERE title LIKE $pat OR short_summary LIKE $pat OR user_notes LIKE $pat OR author LIKE $pat
             ORDER BY id DESC
             LIMIT 6`
          )
          .all({ $pat: likePat }) as Array<{
          id: number;
          title: string;
          author: string;
          short_summary: string;
          digest: string;
          user_notes: string;
        }>;
        for (const a of artRows) {
          const text = a.short_summary || a.digest || a.user_notes || "";
          const titleBoost = (a.title || "").toLowerCase().includes(lowerQ) ? 1.6 : 1.05;
          scoredHits.push({
            source: "chisu",
            title: a.title || `尺素文章 #${a.id}`,
            pathOrRef: `chisu:article:${a.id}`,
            snippet: this.extractFocusedSnippet(text, tokens, lowerQ),
            category: a.author ? `尺素 · ${a.author}` : "尺素文章",
            articleId: a.id,
            score: Number((1.3 * titleBoost).toFixed(2)),
            _rank: 1.3 * titleBoost,
          });
        }

        const turnRows = wxDb
          .query(
            `SELECT id, intent, user_text, assistant_reply, media_summary
             FROM conversation_turns
             WHERE intent IN ('multimodal_ingest', 'capture_idea', 'capture_inbox')
               AND (user_text LIKE $pat OR assistant_reply LIKE $pat)
             ORDER BY id DESC
             LIMIT 4`
          )
          .all({ $pat: likePat }) as Array<{
          id: number;
          intent: string;
          user_text: string;
          assistant_reply: string;
          media_summary: string;
        }>;
        for (const t of turnRows) {
          const headingMatch = String(t.assistant_reply || "").match(/^#{1,4}\s+(.+)$/m);
          const title = headingMatch?.[1]?.trim() || (t.intent === "capture_idea" ? "微信灵感速记" : "微信多媒体笔记");
          const snippet = this.extractFocusedSnippet(t.assistant_reply || t.user_text || "", tokens, lowerQ);
          scoredHits.push({
            source: "chisu",
            title,
            pathOrRef: `chisu:turn:${t.id}`,
            snippet,
            category: t.intent === "capture_idea" ? "尺素 · 灵感" : "尺素 · 多媒体",
            turnId: t.id,
            score: 1.25,
            _rank: 1.25,
          });
        }
      } catch {
        // ignore readonly open errors
      } finally {
        try {
          wxDb?.close();
        } catch {}
      }
    }

    scoredHits.sort((a, b) => b._rank - a._rank);
    return {
      hits: scoredHits.slice(0, maxHits).map(({ _rank, ...rest }) => rest),
      indexedVaultFiles: syncStats.indexedFiles,
      indexedVaultChunks: syncStats.indexedChunks,
    };
  }

  private extractFocusedSnippet(text: string, tokens: string[], lowerFullQuery: string): string {
    const clean = text.replace(/\s+/g, " ").trim();
    if (!clean) return "";
    const lower = clean.toLowerCase();
    let idx = lower.indexOf(lowerFullQuery);
    if (idx < 0) {
      for (const tok of tokens) {
        const pos = lower.indexOf(tok);
        if (pos >= 0) {
          idx = pos;
          break;
        }
      }
    }
    const start = Math.max(0, (idx >= 0 ? idx : 0) - 48);
    const slice = clean.slice(start, start + 210).trim();
    return `${start > 0 ? "…" : ""}${slice}${start + 210 < clean.length ? "…" : ""}`;
  }

  public close(): void {
    try {
      this.db.close();
    } catch {
      // ignore
    }
  }

  private collectAllVaultMarkdownFiles(
    rootDir: string,
    maxFiles: number
  ): Array<{ relPath: string; absPath: string; mtimeMs: number; sizeBytes: number }> {
    const collected: Array<{ relPath: string; absPath: string; mtimeMs: number; sizeBytes: number }> = [];
    const walk = (dir: string, depth: number) => {
      if (depth > 6 || collected.length >= maxFiles) return;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of entries) {
        if (collected.length >= maxFiles) break;
        if (entry.name.startsWith(".") || entry.name === "node_modules" || entry.name === "assets") continue;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full, depth + 1);
        } else if (entry.isFile() && entry.name.endsWith(".md") && !entry.name.includes("_export_")) {
          try {
            const st = fs.statSync(full);
            collected.push({
              relPath: path.relative(rootDir, full).replace(/\\/g, "/"),
              absPath: full,
              mtimeMs: Math.floor(st.mtimeMs),
              sizeBytes: st.size,
            });
          } catch {
            // ignore
          }
        }
      }
    };
    walk(rootDir, 0);
    return collected;
  }

  private sanitizeFileName(raw: string): string {
    const cleaned = raw
      .trim()
      .replace(/[\\/:*?"<>|#^\[\]]+/g, "_")
      .replace(/\s+/g, "_")
      .replace(/^_+|_+$/g, "")
      .slice(0, 64);
    return cleaned || "note";
  }

  private allocateCollisionFreePath(dir: string, stem: string, ext: string): string {
    let candidate = path.join(dir, `${stem}${ext}`);
    let suffix = 2;
    while (fs.existsSync(candidate)) {
      candidate = path.join(dir, `${stem}_${suffix}${ext}`);
      suffix++;
    }
    return candidate;
  }
}
