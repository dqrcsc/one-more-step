import fs from "node:fs";
import path from "node:path";
import { Database } from "bun:sqlite";
import type {
  ChatSyncBatchParams,
  MemoryPromoteVaultParams,
  MemoryPromoteVaultResult,
  MemorySearchVaultResult,
} from "@oms/shared-protocol";
import { parseDotEnvSafe } from "../wechat/edge-proxy-bootstrap.js";

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
  private readonly chatDir: string;
  private readonly dbPath: string;
  private readonly workingMemoryPath: string;
  private readonly wechatEnvPath: string;
  private readonly vaultRootOverride?: string;
  private db: Database;

  constructor(opts: MobileMemoryMirrorOptions) {
    this.chatDir = path.join(opts.omsHomeDir, "chat");
    fs.mkdirSync(this.chatDir, { recursive: true, mode: 0o700 });
    this.dbPath = path.join(this.chatDir, "mobile_chat.db");
    this.workingMemoryPath = path.join(this.chatDir, "working_memory.json");
    this.wechatEnvPath = opts.wechatEnvPath || path.join(opts.omsHomeDir, "wechat", ".env");
    this.vaultRootOverride = opts.vaultRootOverride;

    this.db = new Database(this.dbPath, { create: true });
    this.initSchema();
  }

  private initSchema(): void {
    this.db.exec(`
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
    const cleaned = Array.from(new Set(items.map((s) => s.trim()).filter(Boolean))).slice(0, 12);
    const tmp = `${this.workingMemoryPath}.tmp`;
    fs.writeFileSync(
      tmp,
      JSON.stringify({ updatedAtIso: new Date().toISOString(), items: cleaned }, null, 2),
      "utf8"
    );
    fs.renameSync(tmp, this.workingMemoryPath);
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

    let savedImagePath: string | undefined;
    let savedImageFileName: string | undefined;
    if (params.jpegBase64 && params.jpegBase64.trim()) {
      const assetsDir = path.join(subFolder, "assets");
      fs.mkdirSync(assetsDir, { recursive: true });
      const rawImgName = this.sanitizeFileName(
        (params.imageFileName || `${dateStr}_${safeStem}`).replace(/\.(jpe?g|png)$/i, "")
      );
      savedImagePath = this.allocateCollisionFreePath(assetsDir, rawImgName, ".jpg");
      savedImageFileName = path.basename(savedImagePath);
      fs.writeFileSync(savedImagePath, Buffer.from(params.jpegBase64, "base64"));
    }

    let mdBody = params.markdownContent || "";
    if (savedImageFileName && !mdBody.includes(savedImageFileName)) {
      mdBody = `![[assets/${savedImageFileName}]]\n\n${mdBody}`;
    }

    const notePath = this.allocateCollisionFreePath(subFolder, `${dateStr}_${safeStem}`, ".md");
    fs.writeFileSync(notePath, mdBody.endsWith("\n") ? mdBody : `${mdBody}\n`, "utf8");

    return {
      ok: true,
      savedNotePath: notePath,
      savedImagePath,
    };
  }

  public searchContext(query: string, limit = 6): MemorySearchVaultResult {
    const q = (query || "").trim();
    if (!q) return { hits: [] };
    const maxHits = Math.max(1, Math.min(15, limit));
    const hits: MemorySearchVaultResult["hits"] = [];

    // 1. Search L0 Mobile Chat SQLite FTS5
    try {
      const tokens = q
        .replace(/[^\p{L}\p{N}\s]+/gu, " ")
        .split(/\s+/)
        .filter((t) => t.length >= 1)
        .slice(0, 5);
      if (tokens.length > 0) {
        const ftsExpr = tokens.map((t) => `"${t.replace(/"/g, '""')}"`).join(" OR ");
        const rows = this.db
          .query(
            `SELECT title, thread_id, snippet(mobile_messages_fts, 3, '[', ']', '…', 36) AS snip
             FROM mobile_messages_fts
             WHERE mobile_messages_fts MATCH $expr
             LIMIT $lim`
          )
          .all({ $expr: ftsExpr, $lim: maxHits }) as Array<{
          title: string;
          thread_id: string;
          snip: string;
        }>;
        for (const r of rows) {
          hits.push({
            source: "mobile_chat",
            title: r.title || "手机历史对话",
            pathOrRef: `thread:${r.thread_id}`,
            snippet: r.snip || "",
          });
        }
      }
    } catch {
      // Fallback LIKE search if FTS query syntax rejects special input
      try {
        const rows = this.db
          .query(
            `SELECT m.thread_id, COALESCE(t.title, '手机历史对话') AS title, substr(m.content, 1, 180) AS snip
             FROM mobile_messages m
             LEFT JOIN mobile_threads t ON t.thread_id = m.thread_id
             WHERE m.content LIKE $pat
             ORDER BY m.created_at_ms DESC
             LIMIT $lim`
          )
          .all({ $pat: `%${q}%`, $lim: maxHits }) as Array<{
          thread_id: string;
          title: string;
          snip: string;
        }>;
        for (const r of rows) {
          hits.push({
            source: "mobile_chat",
            title: r.title,
            pathOrRef: `thread:${r.thread_id}`,
            snippet: r.snip,
          });
        }
      } catch {
        // ignore
      }
    }

    // 2. Lightweight Vault Markdown scan (bounded to keep latency < 40ms)
    const vaultRoot = this.resolveVaultRoot();
    if (fs.existsSync(vaultRoot) && hits.length < maxHits) {
      const lowerTerms = q
        .toLowerCase()
        .split(/\s+/)
        .filter(Boolean);
      const mdFiles = this.collectRecentMarkdownFiles(vaultRoot, 180);
      for (const filePath of mdFiles) {
        if (hits.length >= maxHits) break;
        try {
          const baseName = path.basename(filePath, ".md");
          const content = fs.readFileSync(filePath, "utf8").slice(0, 12000);
          const hay = `${baseName}\n${content}`.toLowerCase();
          if (lowerTerms.some((term) => hay.includes(term))) {
            const firstMatchIdx = Math.max(0, hay.indexOf(lowerTerms[0]) - 50);
            const excerpt = content
              .slice(firstMatchIdx, firstMatchIdx + 200)
              .replace(/\s+/g, " ")
              .trim();
            hits.push({
              source: "vault",
              title: baseName,
              pathOrRef: path.relative(vaultRoot, filePath),
              snippet: excerpt,
            });
          }
        } catch {
          // ignore unreadable file
        }
      }
    }

    return { hits: hits.slice(0, maxHits) };
  }

  public close(): void {
    try {
      this.db.close();
    } catch {
      // ignore
    }
  }

  private collectRecentMarkdownFiles(rootDir: string, maxFiles: number): string[] {
    const collected: Array<{ filePath: string; mtimeMs: number }> = [];
    const walk = (dir: string, depth: number) => {
      if (depth > 4 || collected.length >= maxFiles * 2) return;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of entries) {
        if (entry.name.startsWith(".") || entry.name === "node_modules" || entry.name === "_system") continue;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full, depth + 1);
        } else if (entry.isFile() && entry.name.endsWith(".md") && !entry.name.includes("_export_")) {
          try {
            const st = fs.statSync(full);
            collected.push({ filePath: full, mtimeMs: st.mtimeMs });
          } catch {
            // ignore
          }
        }
      }
    };
    walk(rootDir, 0);
    collected.sort((a, b) => b.mtimeMs - a.mtimeMs);
    return collected.slice(0, maxFiles).map((x) => x.filePath);
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
