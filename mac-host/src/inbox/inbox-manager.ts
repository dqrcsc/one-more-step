import fs from "node:fs";
import path from "node:path";
import {
  extractOtpFromText,
  InboxSubmitItemParams,
  IntentTag,
} from "@oms/shared-protocol";
import { CompletedFileTransfer } from "../transport/session-manager.js";

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

    fs.mkdirSync(this.inboxRootDir, { recursive: true });
    fs.mkdirSync(path.join(this.inboxRootDir, "pulled_cache"), { recursive: true });
    this.loadExistingIndex();
  }

  private loadExistingIndex(): void {
    if (!fs.existsSync(this.indexJsonlPath)) return;
    const lines = fs.readFileSync(this.indexJsonlPath, "utf8").split("\n").filter(Boolean);
    for (const line of lines) {
      try {
        const item = JSON.parse(line) as StoredInboxItem;
        this.itemsMap.set(item.itemId, item);
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
    // UUIDv7 idempotent deduplication check
    const existing = this.itemsMap.get(params.clientItemId);
    if (existing) {
      return existing;
    }

    const now = new Date();
    const dateFolder = now.toISOString().slice(0, 10); // YYYY-MM-DD
    const itemDir = path.join(this.inboxRootDir, dateFolder, params.clientItemId);
    fs.mkdirSync(itemDir, { recursive: true });

    const storedFiles: StoredInboxFile[] = [];
    for (const att of params.attachments ?? []) {
      const completed = resolveStream(att.streamId);
      if (completed && fs.existsSync(completed.savedAbsPath)) {
        const safeName = path.basename(att.fileName || "attachment.bin");
        const destAbsPath = path.join(itemDir, safeName);
        fs.copyFileSync(completed.savedAbsPath, destAbsPath);
        storedFiles.push({
          fileName: safeName,
          absPath: destAbsPath,
          mimeType: att.mimeType,
          sizeBytes: att.totalBytes,
          sha256: completed.sha256Actual,
        });
      }
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

    // Save per-item meta.json
    fs.writeFileSync(
      path.join(itemDir, "meta.json"),
      JSON.stringify(stored, null, 2),
      "utf8"
    );

    // Update latest.json snapshot
    fs.writeFileSync(this.latestJsonPath, JSON.stringify(stored, null, 2), "utf8");

    // Append to chronological index
    this.itemsMap.set(stored.itemId, stored);
    fs.appendFileSync(this.indexJsonlPath, JSON.stringify(stored) + "\n", "utf8");

    // Route to specialized staging queues by intentTag
    if (stored.intentTag === "pkb_vault" || stored.intentTag === "voice_idea") {
      fs.appendFileSync(this.pkbQueuePath, JSON.stringify(stored) + "\n", "utf8");
    } else if (stored.intentTag === "ui_bug") {
      fs.appendFileSync(this.uiBugQueuePath, JSON.stringify(stored) + "\n", "utf8");
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
      for (const item of result) {
        item.processed = true;
      }
      this.rewriteIndex();
    }

    return result;
  }

  public getItem(itemId: string): StoredInboxItem | undefined {
    return this.itemsMap.get(itemId);
  }

  public markProcessed(itemId: string, processed = true): boolean {
    const item = this.itemsMap.get(itemId);
    if (!item) return false;
    item.processed = processed;
    this.rewriteIndex();
    return true;
  }

  public updateItemTranscript(itemId: string, voiceTranscript: string): StoredInboxItem | undefined {
    const item = this.itemsMap.get(itemId);
    if (!item) return undefined;
    item.voiceTranscript = voiceTranscript;
    if (!item.textContent) {
      item.textContent = voiceTranscript;
    }
    const dateFolder = (item.receivedAt || new Date().toISOString()).slice(0, 10);
    const metaPath = path.join(this.inboxRootDir, dateFolder, item.itemId, "meta.json");
    if (fs.existsSync(path.dirname(metaPath))) {
      fs.writeFileSync(metaPath, JSON.stringify(item, null, 2), "utf8");
    }
    fs.writeFileSync(this.latestJsonPath, JSON.stringify(item, null, 2), "utf8");
    this.rewriteIndex();
    return item;
  }

  public deleteItem(itemId: string): boolean {
    const existed = this.itemsMap.delete(itemId);
    if (existed) {
      this.rewriteIndex();
    }
    return existed;
  }

  public clearProcessed(): number {
    let removed = 0;
    for (const [id, item] of this.itemsMap.entries()) {
      if (item.processed) {
        this.itemsMap.delete(id);
        removed++;
      }
    }
    if (removed > 0) {
      this.rewriteIndex();
    }
    return removed;
  }

  private rewriteIndex(): void {
    const lines = Array.from(this.itemsMap.values())
      .map((i) => JSON.stringify(i))
      .join("\n");
    fs.writeFileSync(this.indexJsonlPath, lines ? lines + "\n" : "", "utf8");
  }
}
