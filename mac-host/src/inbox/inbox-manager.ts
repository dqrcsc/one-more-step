import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { extractOtpFromText, InboxSubmitItemParams, IntentTag } from "@oms/shared-protocol";
import { CompletedFileTransfer } from "../transport/session-manager.js";

const MAX_INBOX_ITEMS = 500;
const MAX_INDEXED_ATTACHMENT_BYTES = 2 * 1024 * 1024 * 1024;

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
  otpCode?: string;
  otpSender?: string;
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

    fs.mkdirSync(this.inboxRootDir, { recursive: true, mode: 0o700 });
    fs.mkdirSync(path.join(this.inboxRootDir, "pulled_cache"), { recursive: true, mode: 0o700 });
    try {
      fs.chmodSync(this.inboxRootDir, 0o700);
      fs.chmodSync(path.join(this.inboxRootDir, "pulled_cache"), 0o700);
    } catch {}
    this.loadExistingIndex();
    this.refreshDerivedViews();
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

  private sha256FileSync(filePath: string): { sha256: string; sizeBytes: number } {
    const hash = crypto.createHash("sha256");
    const fd = fs.openSync(filePath, "r");
    try {
      const buf = Buffer.allocUnsafe(65536);
      let total = 0;
      while (true) {
        const read = fs.readSync(fd, buf, 0, buf.length, null);
        if (read <= 0) break;
        total += read;
        hash.update(read === buf.length ? buf : buf.subarray(0, read));
      }
      return { sha256: hash.digest("hex"), sizeBytes: total };
    } finally {
      fs.closeSync(fd);
    }
  }

  public pruneStagingCache(maxAgeMs = 7 * 24 * 3600 * 1000, maxFiles = 200): number {
    const cacheDir = path.join(this.inboxRootDir, "pulled_cache");
    if (!fs.existsSync(cacheDir)) return 0;
    let removed = 0;
    try {
      const now = Date.now();
      const entries = fs
        .readdirSync(cacheDir)
        .map((name) => {
          const full = path.join(cacheDir, name);
          try {
            const st = fs.statSync(full);
            return st.isFile() ? { full, mtimeMs: st.mtimeMs } : null;
          } catch {
            return null;
          }
        })
        .filter((e): e is { full: string; mtimeMs: number } => e !== null)
        .sort((a, b) => b.mtimeMs - a.mtimeMs);

      entries.forEach((entry, idx) => {
        if (idx >= maxFiles || now - entry.mtimeMs > maxAgeMs) {
          try {
            fs.unlinkSync(entry.full);
            removed++;
          } catch {}
        }
      });
    } catch {}
    return removed;
  }

  /**
   * Detect delayed duplicate OTP submissions (e.g. phone unlock notification re-post or truncated ticker
   * arriving with a fresh clientItemId minutes after lockscreen SMS capture).
   */
  public findRecentDuplicateOtp(
    params: InboxSubmitItemParams,
    windowMs = 30 * 60 * 1000
  ): StoredInboxItem | undefined {
    if (params.attachments && params.attachments.length > 0) return undefined;
    const extracted =
      !params.otpCode && params.textContent
        ? extractOtpFromText(params.textContent, params.otpSender || params.title || params.sourceApp)
        : null;
    const otpCode = (params.otpCode || extracted?.code || "").trim();
    if (!otpCode) return undefined;

    const normalizeBrand = (s: string | undefined): string => {
      const clean = (s || "").trim().toLowerCase();
      return /^\+?\d[\d\s-]{2,}$/.test(clean) ? "" : clean;
    };
    const otpSender = normalizeBrand(params.otpSender || extracted?.sender);
    const sourceApp = (params.sourceApp || "").trim().toLowerCase();
    const nowMs = Date.now();
    const incomingAtMs = params.createdAtMs || nowMs;

    const normalizeTail = (text: string | undefined, code: string): string => {
      if (!text) return "";
      const idx = text.toLowerCase().indexOf(code.toLowerCase());
      const slice = idx >= 0 ? text.slice(idx) : text;
      return slice
        .replace(/【[^【】]{1,24}】/g, "")
        .replace(/[^\p{L}\p{N}]+/gu, "")
        .toLowerCase()
        .slice(0, 28);
    };
    const candTail = normalizeTail(params.textContent, otpCode);

    for (const item of this.itemsMap.values()) {
      if (item.itemId === params.clientItemId) continue;
      if (!item.otpCode || item.otpCode.toLowerCase() !== otpCode.toLowerCase()) continue;

      const itemReceivedMs = Date.parse(item.receivedAt) || item.createdAtMs;
      const ageMs =
        params.createdAtMs && item.createdAtMs
          ? Math.abs(incomingAtMs - item.createdAtMs)
          : Math.abs(nowMs - itemReceivedMs);
      if (ageMs > windowMs) continue;

      const prevTail = normalizeTail(item.textContent, item.otpCode);
      const prevSender = normalizeBrand(item.otpSender);
      const prevApp = (item.sourceApp || "").trim().toLowerCase();

      if (
        prevSender &&
        otpSender &&
        prevSender !== otpSender &&
        !prevSender.includes(otpSender) &&
        !otpSender.includes(prevSender) &&
        (candTail.length < 6 || prevTail !== candTail)
      ) {
        continue;
      }

      if (ageMs < 60_000) return item;
      if (candTail.length >= 6 && prevTail === candTail) return item;
      if (prevSender && otpSender) return item;
      if (prevApp && sourceApp && prevApp === sourceApp) return item;
      if (!prevSender && !otpSender && (!prevApp || !sourceApp)) return item;
    }
    return undefined;
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
      this.refreshDerivedViews([existing.itemId]);
      return existing;
    }

    if (this.itemsMap.size >= MAX_INBOX_ITEMS) {
      throw new Error("ERR_INBOX_QUOTA: 收件箱已达500条，请先整理历史记录后重试；现有资料不会自动删除");
    }

    // Validate every attachment before creating an item or publishing its ID.
    const attachments = (params.attachments ?? []).map((att) => {
      const completed = resolveStream(att.streamId);
      if (!completed || !fs.existsSync(completed.savedAbsPath)) {
        throw new Error(`Attachment stream is not complete: ${att.streamId}`);
      }
      const { sha256, sizeBytes } = this.sha256FileSync(completed.savedAbsPath);
      const declaredSha = String(att.sha256 || "").toLowerCase();
      if (
        completed.header.streamId !== att.streamId ||
        sizeBytes !== att.totalBytes ||
        sizeBytes !== completed.header.totalBytes ||
        !/^[0-9a-f]{64}$/.test(declaredSha) ||
        sha256 !== declaredSha ||
        sha256 !== completed.sha256Actual.toLowerCase() ||
        sha256 !== completed.header.sha256.toLowerCase()
      ) {
        throw new Error(`Attachment size or SHA-256 mismatch: ${att.streamId}`);
      }
      return { att, srcAbsPath: completed.savedAbsPath, sha256, sizeBytes };
    });

    const indexedBytes = Array.from(this.itemsMap.values()).reduce(
      (sum, item) => sum + item.files.reduce((bytes, file) => bytes + Math.max(0, file.sizeBytes || 0), 0), 0
    );
    const incomingBytes = attachments.reduce((sum, file) => sum + file.sizeBytes, 0);
    if (indexedBytes + incomingBytes > MAX_INDEXED_ATTACHMENT_BYTES) {
      throw new Error("ERR_INBOX_QUOTA: 已索引附件将超过2GiB，请先整理历史记录后重试；现有资料不会自动删除");
    }

    const now = new Date();
    const dateFolder = now.toISOString().slice(0, 10); // YYYY-MM-DD
    const itemDir = path.join(this.inboxRootDir, dateFolder, params.clientItemId);
    fs.mkdirSync(itemDir, { recursive: true, mode: 0o700 });
    try {
      fs.chmodSync(path.join(this.inboxRootDir, dateFolder), 0o700);
      fs.chmodSync(itemDir, 0o700);
    } catch {}

    const pulledCacheDir = path.resolve(path.join(this.inboxRootDir, "pulled_cache"));
    const storedFiles: StoredInboxFile[] = [];
    const usedNames = new Set(["meta.json"]);
    for (const { att, srcAbsPath, sha256 } of attachments) {
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
      // Keep the verified staging source until the authoritative index commits.
      fs.copyFileSync(srcAbsPath, destAbsPath);
      fs.chmodSync(destAbsPath, 0o600);
      const fd = fs.openSync(destAbsPath, "r");
      try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
      storedFiles.push({
        fileName: safeName,
        absPath: destAbsPath,
        mimeType: att.mimeType,
        sizeBytes: att.totalBytes,
        sha256,
      });
    }

    const extractedOtp =
      !params.otpCode && params.textContent
        ? extractOtpFromText(
            params.textContent,
            params.otpSender || params.title || params.sourceApp
          )
        : null;

    const otpCode = params.otpCode || extractedOtp?.code;
    const otpSender = params.otpSender || extractedOtp?.sender;

    const stored: StoredInboxItem = {
      itemId: params.clientItemId,
      receivedAt: now.toISOString(),
      createdAtMs: params.createdAtMs || Date.now(),
      intentTag: params.intentTag || (otpCode ? "sms_otp" : "default_inbox"),
      title: params.title,
      textContent: params.textContent,
      voiceTranscript: params.voiceTranscript,
      sourceApp: params.sourceApp,
      ...(otpCode ? { otpCode } : {}),
      ...(otpSender ? { otpSender } : {}),
      processed: false,
      files: storedFiles,
    };

    const next = new Map(this.itemsMap);
    next.set(stored.itemId, stored);
    this.rewriteIndex(next, [stored.itemId]);
    for (const source of new Set(attachments.map((file) => path.resolve(file.srcAbsPath)))) {
      if (path.dirname(source) === pulledCacheDir && !source.endsWith(".partial")) {
        try { fs.unlinkSync(source); } catch { /* Committed data is safe; staging cleanup can retry later. */ }
      }
    }

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
          item.otpCode,
          item.otpSender,
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
      this.rewriteIndex(
        next,
        result.map((item) => item.itemId)
      );
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
    this.rewriteIndex(next, [itemId]);
    return true;
  }

  public updateItemTranscript(itemId: string, voiceTranscript: string): StoredInboxItem | undefined {
    const item = this.itemsMap.get(itemId);
    if (!item) return undefined;
    const updated = { ...item, voiceTranscript, textContent: item.textContent || voiceTranscript };
    const next = new Map(this.itemsMap);
    next.set(itemId, updated);
    this.rewriteIndex(next, [itemId]);
    return updated;
  }

  public deleteItem(itemId: string): boolean {
    const next = new Map(this.itemsMap);
    const existed = next.delete(itemId);
    if (existed) {
      this.rewriteIndex(next, []);
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
      this.rewriteIndex(next, []);
    }
    return removed;
  }

  private atomicWrite(filePath: string, content: string): void {
    const tempPath = `${filePath}.${crypto.randomUUID()}.tmp`;
    const fd = fs.openSync(tempPath, "wx", 0o600);
    try {
      fs.writeFileSync(fd, content, { encoding: "utf8" });
      fs.fsyncSync(fd);
    } finally {
      fs.closeSync(fd);
    }
    fs.renameSync(tempPath, filePath);
    try {
      fs.chmodSync(filePath, 0o600);
    } catch {}
  }

  private rewriteIndex(next: Map<string, StoredInboxItem>, changedItemIds?: Iterable<string>): void {
    const lines = Array.from(next.values())
      .map((i) => JSON.stringify(i))
      .join("\n");
    // This index is authoritative. Views can be rebuilt after an interrupted write.
    this.atomicWrite(this.indexJsonlPath, lines ? lines + "\n" : "");
    this.itemsMap.clear();
    for (const [id, item] of next) this.itemsMap.set(id, item);
    this.refreshDerivedViews(changedItemIds);
  }

  private refreshDerivedViews(changedItemIds?: Iterable<string>): void {
    try {
      this.writeDerivedViews(changedItemIds);
    } catch (error) {
      // The index has committed. A view failure must not cause a false delivery failure.
      console.warn("[Inbox] Committed index; derived views need rebuilding:", error);
    }
  }

  private writeDerivedViews(changedItemIds?: Iterable<string>): void {
    const items = Array.from(this.itemsMap.values());
    const pending = items.filter((item) => !item.processed);
    const jsonLines = (list: StoredInboxItem[]) => list.map((item) => JSON.stringify(item) + "\n").join("");
    const latest = items.slice().sort((a, b) => b.createdAtMs - a.createdAtMs)[0] ?? null;
    this.atomicWrite(this.latestJsonPath, JSON.stringify(latest, null, 2));
    this.atomicWrite(this.pkbQueuePath, jsonLines(pending.filter((item) => item.intentTag === "pkb_vault" || item.intentTag === "voice_idea")));
    this.atomicWrite(this.uiBugQueuePath, jsonLines(pending.filter((item) => item.intentTag === "ui_bug")));
    const targetItems =
      changedItemIds !== undefined
        ? Array.from(changedItemIds)
            .map((id) => this.itemsMap.get(id))
            .filter((item): item is StoredInboxItem => Boolean(item))
        : items;
    for (const item of targetItems) {
      const dateFolder = item.receivedAt.slice(0, 10);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(dateFolder)) continue;
      const itemDir = path.join(this.inboxRootDir, dateFolder, item.itemId);
      if (fs.existsSync(itemDir)) this.atomicWrite(path.join(itemDir, "meta.json"), JSON.stringify(item, null, 2));
    }
  }
}
