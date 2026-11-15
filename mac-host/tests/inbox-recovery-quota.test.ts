import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { InboxManager, type StoredInboxItem } from "../src/inbox/inbox-manager.js";
import type { InboxSubmitItemParams } from "@oms/shared-protocol";
import type { CompletedFileTransfer } from "../src/transport/session-manager.js";
// Retain isolated fixtures: no recursive cleanup.
function request(): InboxSubmitItemParams {
  return { clientItemId: crypto.randomUUID(), createdAtMs: Date.now(), intentTag: "default_inbox" };
}
function fixture(count = 0, bytes = 0) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "oms-inbox-recovery-"));
  const records: StoredInboxItem[] = Array.from({ length: count }, (_, i) => ({
    itemId: crypto.randomUUID(), receivedAt: new Date().toISOString(), createdAtMs: i,
    intentTag: "default_inbox", processed: false,
    files: i === 0 && bytes > 0 ? [{ fileName: "indexed.bin", absPath: path.join(root, "indexed.bin"), mimeType: "application/octet-stream", sizeBytes: bytes, sha256: "0".repeat(64) }] : [],
  }));
  fs.writeFileSync(path.join(root, "items_index.jsonl"), records.map(x => JSON.stringify(x) + "\n").join(""));
  return { root, records, inbox: new InboxManager(root) };
}
function staged(root: string) {
  const streamId = crypto.randomUUID(), bytes = Buffer.from("recoverable attachment");
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  const savedAbsPath = path.join(root, "pulled_cache", streamId);
  fs.writeFileSync(savedAbsPath, bytes);
  const attachment = { streamId, fileName: "note.txt", mimeType: "text/plain", totalBytes: bytes.length, sha256 };
  const transfer: CompletedFileTransfer = { header: { ...attachment, chunkSize: bytes.length, totalChunks: 1 }, savedAbsPath, sha256Actual: sha256 };
  return { attachment, transfer, bytes };
}
describe("inbox bounded admission and recoverable commits", () => {
  test("admits record 500, rejects 501, deduplicates at capacity", () => {
    const { root, inbox } = fixture(499), req = request();
    const stored = inbox.storeSubmittedItem(req, () => undefined);
    expect(inbox.queryLatest({ limit: 1000 })).toHaveLength(500);
    expect(() => inbox.storeSubmittedItem(request(), () => undefined)).toThrow("ERR_INBOX_QUOTA");
    expect(inbox.storeSubmittedItem(req, () => { throw new Error("must not re-resolve"); })).toEqual(stored);
    expect(new InboxManager(root).queryLatest({ limit: 1000 })).toHaveLength(500);
  });
  test("preexisting over-limit records remain readable and unchanged", () => {
    const { root, records, inbox } = fixture(501);
    const before = fs.readFileSync(path.join(root, "items_index.jsonl"), "utf8");
    expect(inbox.getItem(records[500].itemId)).toEqual(records[500]);
    expect(() => inbox.storeSubmittedItem(request(), () => undefined)).toThrow("ERR_INBOX_QUOTA");
    expect(fs.readFileSync(path.join(root, "items_index.jsonl"), "utf8")).toBe(before);
  });
  test("exact byte budget is admitted; next attachment rejected before copying", () => {
    const { root, inbox } = fixture(1, 2 * 1024 * 1024 * 1024 - Buffer.byteLength("recoverable attachment"));
    const file = staged(root);
    const stored = inbox.storeSubmittedItem({ ...request(), attachments: [file.attachment] }, () => file.transfer);
    expect(fs.readFileSync(stored.files[0].absPath)).toEqual(file.bytes);
    const next = staged(root);
    expect(() => inbox.storeSubmittedItem({ ...request(), attachments: [next.attachment] }, () => next.transfer)).toThrow("2GiB");
    expect(fs.existsSync(next.transfer.savedAbsPath)).toBe(true);
  });
  test("index failure retains staging; same stream retry recovers duplicated attachments", () => {
    const { root, inbox } = fixture(), file = staged(root);
    const req = { ...request(), attachments: [file.attachment, file.attachment] };
    const indexPath = path.join(root, "items_index.jsonl"), original = fs.renameSync;
    fs.renameSync = ((from: any, to: any) => {
      if (String(to) === indexPath) throw new Error("injected ENOSPC at index commit");
      return original(from, to);
    }) as typeof fs.renameSync;
    try {
      expect(() => inbox.storeSubmittedItem(req, () => file.transfer)).toThrow("ENOSPC");
      expect(inbox.getItem(req.clientItemId)).toBeUndefined();
      expect(fs.readFileSync(file.transfer.savedAbsPath)).toEqual(file.bytes);
    } finally { fs.renameSync = original; }
    const stored = inbox.storeSubmittedItem(req, () => file.transfer);
    expect(stored.files.map(x => x.fileName)).toEqual(["note.txt", "note_2.txt"]);
    for (const entry of stored.files) expect(fs.readFileSync(entry.absPath)).toEqual(file.bytes);
    expect(fs.existsSync(file.transfer.savedAbsPath)).toBe(false);
    expect(new InboxManager(root).getItem(req.clientItemId)).toEqual(stored);
  });
});
