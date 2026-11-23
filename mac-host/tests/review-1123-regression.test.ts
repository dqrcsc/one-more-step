import { test, expect } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { MacOsController } from "../src/daemon/mac-os-controller.js";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { MobileMemoryMirror } from "../src/memory/mobile-memory-mirror.js";
import { AgentSessionHub } from "../src/agent/agent-session-hub.js";
import { WeChatBridgeSupervisor } from "../src/wechat/wechat-supervisor.js";
import { readResponseBounded } from "../src/io/bounded-read.js";

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "oms-review-fix-"));
test("WAN ignores forged hints and consumes only current connection's unexpired unlock challenge", async () => {
  const old = process.env.OMS_TEST_ISOLATION;
  process.env.OMS_TEST_ISOLATION = "1";
  try {
    const ctl = new MacOsController(tmp(), tmp());
    ctl.setUnlockPassword("fixture");
    const context = { macId: "m", macName: "M", activeTier: "WAN_WEBRTC", requestConnectionId: "wan1",
      stayAwakeEnabled: false, onSetStayAwake: (v: boolean) => v };
    await ctl.execute({ action: "lock" }, context);
    for (const hint of ["LAN_WS", "BT_RFCOMM", "LOCAL_IPC", "invalid", ""]) {
      expect((await ctl.execute({ action: "unlock", transportTierHint: hint, biometricVerified: true }, context)).requiresBiometric).toBe(true);
    }
    let challenge = (await ctl.execute({ action: "unlock" }, context)).unlockChallenge;
    expect((await ctl.execute({ action: "unlock", biometricVerified: true, unlockChallenge: challenge },
      { ...context, requestConnectionId: "wan2" })).ok).toBe(false);
    (ctl as any).unlockChallenges.get("wan1").expiresAt = Date.now() - 1;
    const expired = await ctl.execute({ action: "unlock", biometricVerified: true, unlockChallenge: challenge }, context);
    expect(expired.requiresBiometric).toBe(true);
    challenge = expired.unlockChallenge;
    const approved = { action: "unlock" as const, biometricVerified: true, unlockChallenge: challenge };
    expect((await ctl.execute(approved, context)).ok).toBe(true);
    await ctl.execute({ action: "lock" }, context);
    expect((await ctl.execute(approved, context)).ok).toBe(false);
    expect((await ctl.execute({ action: "unlock" }, { ...context, activeTier: "LAN_WS" })).ok).toBe(true);
  } finally { if (old === undefined) delete process.env.OMS_TEST_ISOLATION; else process.env.OMS_TEST_ISOLATION = old; }
});

test("phone request origin overrides the daemon's preferred LAN tier", async () => {
  const old = process.env.OMS_TEST_ISOLATION;
  process.env.OMS_TEST_ISOLATION = "1";
  const home = tmp();
  fs.writeFileSync(path.join(home, "config.json"), JSON.stringify({enableBluetoothBridge: false, dingtalk: {enabled: false}}));
  const daemon = new OmsDaemon(home);
  try {
    (daemon.session as any).getActiveTier = () => "LAN_WS";
    const res: any = await new Promise(resolve => daemon.session.emit("phoneRequest", {
      method: "mac.control.execute", params: {action: "unlock", transportTierHint: "LAN_WS"}
    }, resolve, {tier: "WAN_WEBRTC", connectionId: "fixture"}));
    expect(res.result.requiresBiometric).toBe(true);

    // Local Unix-socket IPC (`oms mac-ctl unlock`) remains trusted even when phone is DISCONNECTED or on WAN.
    (daemon.session as any).getActiveTier = () => "DISCONNECTED";
    await daemon.handleIpcAction("mac.control.set_unlock_password", { password: "fixture-password" });
    await daemon.handleIpcAction("mac.control.execute", { action: "lock" });
    const localUnlock = await daemon.handleIpcAction("mac.control.execute", { action: "unlock" });
    expect(localUnlock.ok).toBe(true);
    expect(localUnlock.requiresBiometric).toBeUndefined();

    // Deterministic vault promotion rejection propagates JSON-RPC code -32602 over phoneRequest.
    const badProm: any = await new Promise(resolve => daemon.session.emit("phoneRequest", {
      method: "memory.promote_vault", params: { itemId: "", kind: "chat_highlight", title: "T", markdownContent: "M" }
    }, resolve, { tier: "LAN_WS", connectionId: "fixture" }));
    expect(badProm.error?.code).toBe(-32602);
  } finally { await daemon.stop(); if (old === undefined) delete process.env.OMS_TEST_ISOLATION; else process.env.OMS_TEST_ISOLATION = old; }
});

function queueFixture(send: (p: any) => Promise<unknown>) {
  const daemon: any = Object.create(OmsDaemon.prototype);
  daemon.wechatPendingPhoneQueuePath = path.join(tmp(), "pending.json");
  daemon.session = { getActiveTier: () => "LAN_WS", pushStreamToPhone: send };
  return daemon;
}
const item = (key: string, markdown = key) => ({ key, title: key, markdown, createdAtMs: 1 });

test("queue keeps concurrent additions and replacements; concurrent flush joins same task", async () => {
  let release!: () => void;
  const gate = new Promise<void>(r => release = r);
  const sent: string[] = [];
  const d = queueFixture(async p => { sent.push(new TextDecoder().decode(p.data)); if (sent.length === 1) await gate; });
  d.enqueuePendingWeChatPhoneItem(item("A"));
  const first = d.flushPendingWeChatPhoneQueue();
  expect(d.flushPendingWeChatPhoneQueue()).toBe(first);
  d.enqueuePendingWeChatPhoneItem(item("B"));
  d.enqueuePendingWeChatPhoneItem(item("A", "new A"));
  release(); await first;
  expect(sent).toEqual(["A", "B", "new A"]);
  expect(JSON.parse(fs.readFileSync(d.wechatPendingPhoneQueuePath, "utf8"))).toEqual([]);
});

test("failed queue entries stay on disk and retry once on next flush", async () => {
  let fail = true;
  const d = queueFixture(async () => { if (fail) throw new Error("offline"); });
  d.enqueuePendingWeChatPhoneItem(item("A"));
  await d.flushPendingWeChatPhoneQueue();
  expect(JSON.parse(fs.readFileSync(d.wechatPendingPhoneQueuePath, "utf8"))).toHaveLength(1);
  fail = false; await d.flushPendingWeChatPhoneQueue();
  expect(JSON.parse(fs.readFileSync(d.wechatPendingPhoneQueuePath, "utf8"))).toHaveLength(0);
});

test("vault promotion is durable/idempotent, repairs reserved files and refuses conflicting retry", () => {
  const root = tmp();
  const options = {omsHomeDir: path.join(root, "oms"), vaultRootOverride: path.join(root, "vault")};
  let mirror = new MobileMemoryMirror(options);
  const input = {itemId: "same", kind: "botanical_specimen" as const, title: "Title", markdownContent: "Body", jpegBase64: "aW1hZ2U=", createdAtMs: 1};
  const first = mirror.promoteToVault(input);
  expect(mirror.promoteToVault(input)).toEqual(first);
  // Re-tapping save on the same specimen card with a newer timestamp stays idempotent.
  expect(mirror.promoteToVault({...input, createdAtMs: 999_999})).toEqual(first);
  expect(() => mirror.promoteToVault({...input, markdownContent: "changed"})).toThrow("different content");
  mirror.close();
  // Simulate a crash after reservation/image but before the note is committed.
  fs.unlinkSync(first.savedNotePath);
  mirror = new MobileMemoryMirror(options);
  expect(mirror.promoteToVault(input)).toEqual(first);
  expect(fs.readFileSync(first.savedNotePath, "utf8")).toContain("Body");
  expect(mirror.promoteToVault({...input, itemId: "different"}).savedNotePath).not.toBe(first.savedNotePath);
  fs.writeFileSync(first.savedNotePath, "User edited this");
  expect(() => mirror.promoteToVault(input)).toThrow("refusing to overwrite");
  expect(fs.readFileSync(first.savedNotePath, "utf8")).toBe("User edited this");
  mirror.close();
});

test("vault reader rejects external file and directory symlinks but permits internal links", () => {
  const root = tmp(), vault = path.join(root, "vault"); fs.mkdirSync(vault);
  const m = new MobileMemoryMirror({omsHomeDir: path.join(root, "oms"), vaultRootOverride: vault});
  fs.writeFileSync(path.join(root, "secret.md"), "fixture");
  fs.writeFileSync(path.join(vault, "ok.md"), "safe");
  fs.symlinkSync(path.join(root, "secret.md"), path.join(vault, "external.md"));
  fs.symlinkSync(root, path.join(vault, "escape"));
  fs.symlinkSync(path.join(vault, "ok.md"), path.join(vault, "internal.md"));
  expect(m.readVaultDocument("external.md").ok).toBe(false);
  expect(m.readVaultDocument("escape/secret.md").ok).toBe(false);
  expect(m.readVaultDocument("internal.md").markdown).toBe("safe");
  m.close();
});

test("article matching prioritizes exact URL and refuses missing/ambiguous fallback", async () => {
  const wx: any = Object.create(WeChatBridgeSupervisor.prototype);
  wx.isRunning = () => true; wx.internalPort = 1;
  wx.fetchArticlesOverview = async () => ({ok:true,articles:[{id:1,title:"Same",url:"https://fixture/1"},{id:2,title:"Same",url:"https://fixture/2"}]});
  const original = globalThis.fetch;
  globalThis.fetch = (async (url: any) => Response.json({ok:true,article:{id:Number(new URL(String(url)).searchParams.get("id")),title:"Same"}})) as any;
  try {
    expect((await wx.fetchArticleDetail({title:"Same",sourceUrl:"https://fixture/2"})).article.id).toBe(2);
    expect((await wx.fetchArticleDetail({title:"Same",sourceUrl:"https://fixture/missing"})).ok).toBe(false);
    expect((await wx.fetchArticleDetail({title:"Same"})).error).toContain("Ambiguous");
  } finally { globalThis.fetch = original; }
});

test("quota checks age and resets again on cache hit, without changing sample timestamp", () => {
  const hub: any = Object.create(AgentSessionHub.prototype);
  hub.codexSessionsDir = tmp();
  const now = Date.now();
  const event = (at: number) => JSON.stringify({timestamp:new Date(at).toISOString(),type:"event_msg",payload:{type:"token_count",rate_limits:{primary:{used_percent:99,window_minutes:300,resets_at:Math.floor((at+3600000)/1000)}}}});
  const file = path.join(hub.codexSessionsDir, "rollout-test.jsonl");
  fs.writeFileSync(file, event(now));
  const fresh = hub.collectCodexQuota();
  expect(fresh.available).toBe(true);
  expect(fresh.statusNote).toContain("非官方实时");
  hub.cachedCodexQuota.status.capturedAtMs = now - 30 * 86400000;
  const old = hub.collectCodexQuota();
  expect(old.available).toBe(false); expect(old.windows).toHaveLength(0);
  expect(old.capturedAtMs).toBe(now - 30 * 86400000);
  hub.cachedCodexQuota.status.capturedAtMs = now;
  hub.cachedCodexQuota.status.windows[0].resetsAtMs = now - 1;
  expect(hub.collectCodexQuota().available).toBe(false);
  hub.cachedCodexQuota = null;
  fs.writeFileSync(file, JSON.stringify({type:"event_msg",payload:{type:"token_count",rate_limits:{primary:{used_percent:1}}}}));
  expect(hub.collectCodexQuota().available).toBe(false);
});

test("bounded responses cancel oversized declared/chunked bodies, normal bodies remain intact", async () => {
  let cancelled = false;
  const stream = () => new ReadableStream<Uint8Array>({pull(c) { c.enqueue(new Uint8Array(8)); },cancel() { cancelled = true; }});
  await expect(readResponseBounded(new Response(stream(), {headers:{"content-length":"100"}}), 10)).rejects.toThrow("exceeds");
  expect(cancelled).toBe(true); cancelled = false;
  await expect(readResponseBounded(new Response(stream()), 10)).rejects.toThrow("exceeds");
  expect(cancelled).toBe(true);
  expect((await readResponseBounded(new Response("valid"), 10)).toString()).toBe("valid");
});
