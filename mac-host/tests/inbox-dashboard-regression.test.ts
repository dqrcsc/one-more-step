import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { generateUuidV7, type InboxSubmitItemParams } from "@oms/shared-protocol";
import { InboxManager } from "../src/inbox/inbox-manager.js";
import { handleDashboardHttpRequest } from "../src/inbox/inbox-web-dashboard.js";
import type { CompletedFileTransfer } from "../src/transport/session-manager.js";

// Fixtures are retained: the workspace forbids recursive file deletion.
function fixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-inbox-regression-"));
  const root = path.join(dir, "inbox");
  return { dir, root, inbox: new InboxManager(root) };
}
function item(overrides: Partial<InboxSubmitItemParams> = {}): InboxSubmitItemParams {
  return { clientItemId: generateUuidV7(), intentTag: "default_inbox", createdAtMs: Date.now(), ...overrides };
}
function completed(dir: string, content = "verified attachment", fileName = "note.txt") {
  const bytes = Buffer.from(content);
  const streamId = generateUuidV7();
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  const savedAbsPath = path.join(dir, streamId);
  fs.writeFileSync(savedAbsPath, bytes);
  const transfer: CompletedFileTransfer = {
    header: { streamId, fileName, mimeType: "text/plain", totalBytes: bytes.length, chunkSize: bytes.length, totalChunks: 1, sha256 },
    savedAbsPath, sha256Actual: sha256,
  };
  const attachment = { streamId, fileName, mimeType: "text/plain", totalBytes: bytes.length, sha256 };
  return { transfer, attachment };
}
function jsonLines(file: string) {
  return fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map((line) => JSON.parse(line));
}

describe("inbox durable commit and attachment validation", () => {
  test("rejects non-UUID IDs before resolving streams or creating item directories", () => {
    const { inbox, root } = fixture();
    const before = fs.readdirSync(root).sort();
    let resolved = false;
    for (const id of ["../../escape", "", "plain-id", "00000000-0000-0000-0000-000000000000"]) {
      expect(() => inbox.storeSubmittedItem(item({ clientItemId: id }), () => { resolved = true; return undefined; })).toThrow("UUID");
    }
    expect(resolved).toBe(false);
    expect(fs.readdirSync(root).sort()).toEqual(before);
    expect(inbox.queryLatest()).toEqual([]);
  });

  test("missing attachment leaves no committed ID and a complete retry preserves colliding files", () => {
    const { inbox, root, dir } = fixture();
    const a = completed(dir, "first", "note.txt");
    const b = completed(dir, "second", "note.txt");
    const meta = completed(dir, "third", "meta.json");
    const request = item({ attachments: [a.attachment, b.attachment, meta.attachment] });
    expect(() => inbox.storeSubmittedItem(request, (id) => id === a.attachment.streamId ? a.transfer : undefined)).toThrow("not complete");
    expect(inbox.getItem(request.clientItemId)).toBeUndefined();
    expect(fs.existsSync(path.join(root, "items_index.jsonl"))).toBe(false);
    expect(fs.readdirSync(root).filter((name) => /^\d{4}-/.test(name))).toEqual([]);
    const transfers = new Map([a, b, meta].map((x) => [x.attachment.streamId, x.transfer]));
    const stored = inbox.storeSubmittedItem(request, (id) => transfers.get(id));
    expect(stored.files.map((file) => file.fileName)).toEqual(["note.txt", "note_2.txt", "meta_2.json"]);
    expect(stored.files.map((file) => fs.readFileSync(file.absPath, "utf8"))).toEqual(["first", "second", "third"]);
    const metadata = JSON.parse(fs.readFileSync(path.join(path.dirname(stored.files[0].absPath), "meta.json"), "utf8"));
    expect(metadata.itemId).toBe(request.clientItemId);
    expect(inbox.storeSubmittedItem(request, () => { throw new Error("duplicate must not re-resolve"); })).toEqual(stored);
  });

  test("rejects corrupted bytes and inconsistent header, size or SHA metadata", () => {
    const { inbox, dir } = fixture();
    for (const variant of ["bytes", "size", "headerHash", "actualHash", "streamId"]) {
      const { transfer, attachment } = completed(dir);
      if (variant === "bytes") fs.writeFileSync(transfer.savedAbsPath, "corrupted");
      if (variant === "size") attachment.totalBytes += 1;
      if (variant === "headerHash") transfer.header.sha256 = "0".repeat(64);
      if (variant === "actualHash") transfer.sha256Actual = "0".repeat(64);
      if (variant === "streamId") transfer.header.streamId = generateUuidV7();
      const request = item({ attachments: [attachment] });
      expect(() => inbox.storeSubmittedItem(request, () => transfer)).toThrow("mismatch");
      expect(inbox.getItem(request.clientItemId)).toBeUndefined();
    }
  });

  test("mutations rebuild pending queues/latest and restart recovers views without deleting attachment files", () => {
    const { inbox, root, dir } = fixture();
    const file = completed(dir);
    const old = inbox.storeSubmittedItem(item({ intentTag: "pkb_vault", createdAtMs: 1, attachments: [file.attachment] }), () => file.transfer);
    const recent = inbox.storeSubmittedItem(item({ intentTag: "ui_bug", createdAtMs: 2 }), () => undefined);
    const voice = inbox.storeSubmittedItem(item({ intentTag: "voice_idea", createdAtMs: 3 }), () => undefined);
    inbox.updateItemTranscript(old.itemId, "new transcript for old item");
    expect(JSON.parse(fs.readFileSync(path.join(root, "latest.json"), "utf8")).itemId).toBe(voice.itemId);
    expect(jsonLines(path.join(root, "pkb_pending.jsonl")).find((x) => x.itemId === old.itemId).voiceTranscript).toBe("new transcript for old item");
    inbox.markProcessed(old.itemId);
    expect(jsonLines(path.join(root, "pkb_pending.jsonl")).map((x) => x.itemId)).toEqual([voice.itemId]);
    inbox.queryLatest({ tag: "ui_bug", markAsProcessed: true });
    expect(jsonLines(path.join(root, "ui_bug_pending.jsonl"))).toEqual([]);
    expect(inbox.clearProcessed()).toBe(2);
    expect(fs.existsSync(old.files[0].absPath)).toBe(true);
    expect(inbox.getItem(recent.itemId)).toBeUndefined();
    fs.writeFileSync(path.join(root, "latest.json"), "stale");
    fs.writeFileSync(path.join(root, "pkb_pending.jsonl"), "stale");
    fs.appendFileSync(path.join(root, "items_index.jsonl"), JSON.stringify({ itemId: generateUuidV7() }) + "\n");
    const restored = new InboxManager(root);
    expect(restored.queryLatest().map((x) => x.itemId)).toEqual([voice.itemId]);
    expect(JSON.parse(fs.readFileSync(path.join(root, "latest.json"), "utf8")).itemId).toBe(voice.itemId);
    expect(jsonLines(path.join(root, "pkb_pending.jsonl")).map((x) => x.itemId)).toEqual([voice.itemId]);
    restored.deleteItem(voice.itemId);
    expect(JSON.parse(fs.readFileSync(path.join(root, "latest.json"), "utf8"))).toBeNull();
    expect(jsonLines(path.join(root, "pkb_pending.jsonl"))).toEqual([]);
    expect(new InboxManager(root).queryLatest()).toEqual([]);
    expect(fs.existsSync(old.files[0].absPath)).toBe(true);
  });

  test("failed authoritative index replacement keeps both committed disk and in-memory state", () => {
    const { inbox, root } = fixture();
    const first = inbox.storeSubmittedItem(item(), () => undefined);
    const indexPath = path.join(root, "items_index.jsonl");
    const before = fs.readFileSync(indexPath, "utf8");
    const originalRename = fs.renameSync;
    fs.renameSync = ((from: any, to: any) => {
      if (String(to) === indexPath) throw new Error("simulated replacement failure");
      return originalRename(from, to);
    }) as typeof fs.renameSync;
    try {
      expect(() => inbox.markProcessed(first.itemId)).toThrow("replacement failure");
      expect(inbox.getItem(first.itemId)?.processed).toBe(false);
      expect(fs.readFileSync(indexPath, "utf8")).toBe(before);
    } finally {
      fs.renameSync = originalRename;
    }
    expect(new InboxManager(root).getItem(first.itemId)?.processed).toBe(false);
  });

  test("same-ID retry repairs derived views after the authoritative index was committed", () => {
    const { inbox, root } = fixture();
    const request = item({ intentTag: "pkb_vault" });
    const queuePath = path.join(root, "pkb_pending.jsonl");
    const originalRename = fs.renameSync;
    fs.renameSync = ((from: any, to: any) => {
      if (String(to) === queuePath) throw new Error("simulated derived view failure");
      return originalRename(from, to);
    }) as typeof fs.renameSync;
    try {
      expect(() => inbox.storeSubmittedItem(request, () => undefined)).toThrow("derived view failure");
      expect(jsonLines(path.join(root, "items_index.jsonl")).map((x) => x.itemId)).toEqual([request.clientItemId]);
      expect(inbox.getItem(request.clientItemId)?.itemId).toBe(request.clientItemId);
      expect(jsonLines(queuePath)).toEqual([]);
    } finally {
      fs.renameSync = originalRename;
    }
    const retried = inbox.storeSubmittedItem(request, () => { throw new Error("retry must not resend attachments"); });
    expect(retried.itemId).toBe(request.clientItemId);
    expect(jsonLines(queuePath).map((x) => x.itemId)).toEqual([request.clientItemId]);
    expect(jsonLines(path.join(root, "items_index.jsonl"))).toHaveLength(1);
    expect(JSON.parse(fs.readFileSync(path.join(root, "latest.json"), "utf8")).itemId).toBe(request.clientItemId);
  });
});

function dashboardFixture() {
  const { dir, root, inbox } = fixture();
  const file = completed(dir);
  const stored = inbox.storeSubmittedItem(item({ attachments: [file.attachment] }), () => file.transfer);
  const opened: { filePath: string; reveal: boolean }[] = [];
  const clipboard: string[] = [];
  const daemon: any = { inbox, openFileOnMac: (filePath: string, reveal: boolean) => opened.push({ filePath, reveal }), copyTextToMacClipboard: (text: string) => clipboard.push(text) };
  async function action(body: any, headers: Record<string, string> = {}, origin = "http://127.0.0.1:19527") {
    const req = new Request(`${origin}/api/inbox/action`, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify(body) });
    return (await handleDashboardHttpRequest(daemon, req, new URL(req.url), "127.0.0.1"))!;
  }
  async function getFile(fileName = stored.files[0].fileName) {
    const req = new Request(`http://127.0.0.1:19527/api/inbox/file?itemId=${stored.itemId}&file=${encodeURIComponent(fileName)}`);
    return (await handleDashboardHttpRequest(daemon, req, new URL(req.url), "127.0.0.1"))!;
  }
  return { dir, root, inbox, stored, daemon, opened, clipboard, action, getFile };
}

describe("dashboard request and canonical attachment boundaries", () => {
  test("rejects cross-origin/site, rebinding hosts and non-JSON mutation requests before side effects", async () => {
    const { action, clipboard } = dashboardFixture();
    const body = { action: "copy_mac_clipboard", text: "authorized" };
    expect((await action(body, { Origin: "https://untrusted.example" })).status).toBe(403);
    expect((await action(body, { "Sec-Fetch-Site": "cross-site" })).status).toBe(403);
    expect((await action(body, { "Sec-Fetch-Site": "same-site" })).status).toBe(403);
    expect((await action(body, {}, "http://rebind.example:19527")).status).toBe(403);
    expect((await action(body, { Host: "rebind.example:19527" })).status).toBe(403);
    expect((await action(body, { "Content-Type": "text/plain" })).status).toBe(415);
    expect(clipboard).toEqual([]);
    expect((await action(body, { Origin: "http://127.0.0.1:19527", "Sec-Fetch-Site": "same-origin" })).status).toBe(200);
    expect(clipboard).toEqual(["authorized"]);
  });

  test("preview and reveal require registered files, and valid registered attachment reads still work", async () => {
    const { dir, stored, action, opened, getFile } = dashboardFixture();
    const arbitrary = path.join(dir, "arbitrary.txt");
    fs.writeFileSync(arbitrary, "outside attachment list");
    for (const command of ["open_preview", "reveal_in_finder"]) {
      expect((await action({ action: command, itemId: stored.itemId, absPath: arbitrary })).status).toBe(403);
      expect((await action({ action: command, itemId: "unknown", absPath: stored.files[0].absPath })).status).toBe(403);
      expect((await action({ action: command, itemId: stored.itemId, absPath: stored.files[0].absPath })).status).toBe(200);
    }
    expect(opened.map((x) => x.reveal)).toEqual([false, true]);
    const served = await getFile();
    expect(served.status).toBe(200);
    expect(await served.text()).toBe("verified attachment");
    expect((await getFile("not-registered.txt")).status).toBe(404);
  });

  test("registered prefix-sibling and symlink escape paths are rejected for serving and preview", async () => {
    const { dir, root, stored, action, getFile, opened } = dashboardFixture();
    const sibling = `${root}-outside`;
    fs.mkdirSync(sibling);
    const outside = path.join(sibling, "secret.txt");
    fs.writeFileSync(outside, "secret");
    const symlink = path.join(root, "escape.txt");
    fs.symlinkSync(outside, symlink);
    for (const candidate of [outside, symlink]) {
      stored.files[0].absPath = candidate;
      expect((await getFile()).status).toBe(403);
      expect((await action({ action: "open_preview", itemId: stored.itemId, absPath: candidate })).status).toBe(403);
    }
    expect(opened).toEqual([]);
    expect(fs.existsSync(path.join(dir, "inbox"))).toBe(true);
  });

  test("dashboard HTML sets Content-Security-Policy and uses data-item-action delegation instead of inline item onclick", async () => {
    const { daemon } = dashboardFixture();
    const req = new Request("http://127.0.0.1:19527/inbox");
    const res = (await handleDashboardHttpRequest(daemon, req, new URL(req.url), "127.0.0.1"))!;
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Security-Policy")).toContain("default-src 'self'");
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
    const html = await res.text();
    expect(html).toContain("data-item-action=");
    expect(html).not.toContain("onclick=\"doAction('open_preview'");
    expect(html).not.toContain("onclick=\"doAction('copy_mac_clipboard'");
  });
});
