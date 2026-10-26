import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { InboxSubmitItemParams, IntentTag } from "@oms/shared-protocol";
import { CompletedFileTransfer } from "../transport/session-manager.js";

const ITEM_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface StoredInboxFile {
  fileName: string;
  absPath: string;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
}

export interface StoredInboxItem {
  itemId: string;
  receivedAt: string;
  createdAtMs: number;
  intentTag: IntentTag;
  title?: string;
  textContent?: string;
  voiceTranscript?: string;
  sourceApp?: string;
  processed: boolean;
  files: StoredInboxFile[];
}

/**
 * Module 06: Local Inbox & Personal Knowledge Base (PKB) Staging Queue Manager
 * Manages ~/.one_more_step/inbox/
 */
export class InboxManager {
  public readonly inboxRootDir: string;
  private readonly latestJsonPath: string;
  private readonly indexJsonlPath: string;
  private readonly pkbQueuePath: string;
  private readonly uiBugQueuePath: string;
  private readonly itemsMap = new Map<string, StoredInboxItem>();

  constructor(inboxRootDir: string) {
    this.inboxRootDir = inboxRootDir;
    this.latestJsonPath = path.join(inboxRootDir, "latest.json");
    this.indexJsonlPath = path.join(inboxRootDir, "items_index.jsonl");
    this.pkbQueuePath = path.join(inboxRootDir, "pkb_pending.jsonl");
    this.uiBugQueuePath = path.join(inboxRootDir, "ui_bug_pending.jsonl");

    fs.mkdirSync(this.inboxRootDir, { recursive: true });
    fs.mkdirSync(path.join(this.inboxRootDir, "pulled_cache"), { recursive: true });
    this.loadExistingIndex();
    this.writeDerivedViews();
  }

  private loadExistingIndex(): void {
    if (!fs.existsSync(this.indexJsonlPath)) return;
    const lines = fs.readFileSync(this.indexJsonlPath, "utf8").split("\n").filter(Boolean);
    for (const line of lines) {
      try {
        const item = JSON.parse(line) as StoredInboxItem;
        if (
          ITEM_ID_PATTERN.test(item.itemId) &&
          typeof item.receivedAt === "string" &&
          /^\d{4}-\d{2}-\d{2}T/.test(item.receivedAt) &&
          Number.isFinite(item.createdAtMs) &&
          Array.isArray(item.files)
        ) {
          this.itemsMap.set(item.itemId, item);
        }
      } catch {
        // ignore corrupted line
      }
    }
  }

  /**
   * Idempotently store a submitted item from the Xiaomi phone
   */
  public storeSubmittedItem(
    params: InboxSubmitItemParams,
    resolveStream: (streamId: string) => CompletedFileTransfer | undefined
  ): StoredInboxItem {
    if (!ITEM_ID_PATTERN.test(params.clientItemId)) {
      throw new Error("clientItemId must be a canonical UUID");
    }
    // UUIDv7 idempotent deduplication check
    const existing = this.itemsMap.get(params.clientItemId);
    if (existing) {
      this.writeDerivedViews();
      return existing;
    }

    // Validate every attachment before creating an item or publishing its ID.
    const attachments = (params.attachments ?? []).map((att) => {
      const completed = resolveStream(att.streamId);
      if (!completed || !fs.existsSync(completed.savedAbsPath)) {
        throw new Error(`Attachment stream is not complete: ${att.streamId}`);
      }
      const bytes = fs.readFileSync(completed.savedAbsPath);
      const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
      const declaredSha = String(att.sha256 || "").toLowerCase();
      if (
        completed.header.streamId !== att.streamId ||
        bytes.byteLength !== att.totalBytes ||
        bytes.byteLength !== completed.header.totalBytes ||
        !/^[0-9a-f]{64}$/.test(declaredSha) ||
        sha256 !== declaredSha ||
        sha256 !== completed.sha256Actual.toLowerCase() ||
        sha256 !== completed.header.sha256.toLowerCase()
      ) {
        throw new Error(`Attachment size or SHA-256 mismatch: ${att.streamId}`);
      }
      return { att, bytes, sha256 };
    });

    const now = new Date();
    const dateFolder = now.toISOString().slice(0, 10); // YYYY-MM-DD
    const itemDir = path.join(this.inboxRootDir, dateFolder, params.clientItemId);
    fs.mkdirSync(itemDir, { recursive: true });

    const storedFiles: StoredInboxFile[] = [];
    const usedNames = new Set(["meta.json"]);
    for (const { att, bytes, sha256 } of attachments) {
      const basename = path.basename(att.fileName || "attachment.bin");
      const safeBase = !basename || basename === "." || basename === ".." ? "attachment.bin" : basename;
      const extension = path.extname(safeBase);
      const stem = safeBase.slice(0, safeBase.length - extension.length);
      let safeName = safeBase;
      let suffix = 2;
      while (usedNames.has(safeName.toLowerCase())) {
        safeName = `${stem}_${suffix++}${extension}`;
      }
      usedNames.add(safeName.toLowerCase());
      const destAbsPath = path.join(itemDir, safeName);
      fs.writeFileSync(destAbsPath, bytes);
      storedFiles.push({
        fileName: safeName,
        absPath: destAbsPath,
        mimeType: att.mimeType,
        sizeBytes: att.totalBytes,
        sha256,
      });
    }

    const stored: StoredInboxItem = {
      itemId: params.clientItemId,
      receivedAt: now.toISOString(),
      createdAtMs: params.createdAtMs || Date.now(),
      intentTag: params.intentTag || "default_inbox",
      title: params.title,
      textContent: params.textContent,
      voiceTranscript: params.voiceTranscript,
      sourceApp: params.sourceApp,
      processed: false,
      files: storedFiles,
    };

    const next = new Map(this.itemsMap);
    next.set(stored.itemId, stored);
    this.rewriteIndex(next);

    return stored;
  }

  public queryLatest(opts: {
    tag?: "all" | IntentTag;
    limit?: number;
    onlyUnprocessed?: boolean;
    markAsProcessed?: boolean;
    searchQuery?: string;
  } = {}): StoredInboxItem[] {
    const tag = opts.tag ?? "all";
    const limit = opts.limit ?? 5;
    const onlyUnprocessed = opts.onlyUnprocessed ?? false;
    const q = (opts.searchQuery ?? "").trim().toLowerCase();

    const allDesc = Array.from(this.itemsMap.values()).sort(
      (a, b) => b.createdAtMs - a.createdAtMs
    );

    const filtered = allDesc.filter((item) => {
      if (tag !== "all" && item.intentTag !== tag) return false;
      if (onlyUnprocessed && item.processed) return false;
      if (q) {
        const hay = [
          item.title,
          item.textContent,
          item.voiceTranscript,
          item.intentTag,
          ...item.files.map((f) => f.fileName),
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });

    const result = filtered.slice(0, limit);
    if (opts.markAsProcessed && result.length > 0) {
      const next = new Map(this.itemsMap);
      for (const item of result) {
        next.set(item.itemId, { ...item, processed: true });
      }
      this.rewriteIndex(next);
      return result.map((item) => this.itemsMap.get(item.itemId)!);
    }

    return result;
  }

  public getItem(itemId: string): StoredInboxItem | undefined {
    return this.itemsMap.get(itemId);
  }

  public markProcessed(itemId: string, processed = true): boolean {
    const item = this.itemsMap.get(itemId);
    if (!item) return false;
    const next = new Map(this.itemsMap);
    next.set(itemId, { ...item, processed });
    this.rewriteIndex(next);
    return true;
  }

  public updateItemTranscript(itemId: string, voiceTranscript: string): StoredInboxItem | undefined {
    const item = this.itemsMap.get(itemId);
    if (!item) return undefined;
    const updated = { ...item, voiceTranscript, textContent: item.textContent || voiceTranscript };
    const next = new Map(this.itemsMap);
    next.set(itemId, updated);
    this.rewriteIndex(next);
    return updated;
  }

  public deleteItem(itemId: string): boolean {
    const next = new Map(this.itemsMap);
    const existed = next.delete(itemId);
    if (existed) {
      this.rewriteIndex(next);
    }
    return existed;
  }

  public clearProcessed(): number {
    let removed = 0;
    const next = new Map(this.itemsMap);
    for (const [id, item] of this.itemsMap.entries()) {
      if (item.processed) {
        next.delete(id);
        removed++;
      }
    }
    if (removed > 0) {
      this.rewriteIndex(next);
    }
    return removed;
  }

  private atomicWrite(filePath: string, content: string): void {
    const tempPath = `${filePath}.${crypto.randomUUID()}.tmp`;
    fs.writeFileSync(tempPath, content, { encoding: "utf8", mode: 0o600 });
    fs.renameSync(tempPath, filePath);
  }

  private rewriteIndex(next: Map<string, StoredInboxItem>): void {
    const lines = Array.from(next.values())
      .map((i) => JSON.stringify(i))
      .join("\n");
    // This index is authoritative. Views can be rebuilt after an interrupted write.
    this.atomicWrite(this.indexJsonlPath, lines ? lines + "\n" : "");
    this.itemsMap.clear();
    for (const [id, item] of next) this.itemsMap.set(id, item);
    this.writeDerivedViews();
  }

  private writeDerivedViews(): void {
    const items = Array.from(this.itemsMap.values());
    const pending = items.filter((item) => !item.processed);
    const jsonLines = (list: StoredInboxItem[]) => list.map((item) => JSON.stringify(item) + "\n").join("");
    const latest = items.slice().sort((a, b) => b.createdAtMs - a.createdAtMs)[0] ?? null;
    this.atomicWrite(this.latestJsonPath, JSON.stringify(latest, null, 2));
    this.atomicWrite(this.pkbQueuePath, jsonLines(pending.filter((item) => item.intentTag === "pkb_vault" || item.intentTag === "voice_idea")));
    this.atomicWrite(this.uiBugQueuePath, jsonLines(pending.filter((item) => item.intentTag === "ui_bug")));
    for (const item of items) {
      const dateFolder = item.receivedAt.slice(0, 10);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(dateFolder)) continue;
      const itemDir = path.join(this.inboxRootDir, dateFolder, item.itemId);
      if (fs.existsSync(itemDir)) this.atomicWrite(path.join(itemDir, "meta.json"), JSON.stringify(item, null, 2));
    }
  }
}
