import { describe, expect, test, beforeEach, afterEach } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { Database } from "bun:sqlite";
import { SecretaryCore } from "../src/memory/secretary-core.js";
import { MobileMemoryMirror } from "../src/memory/mobile-memory-mirror.js";
import { AgentSessionHub } from "../src/agent/agent-session-hub.js";
import { MacOsController } from "../src/daemon/mac-os-controller.js";

describe("v3.6 Redesign: Memory Center Tombstones, 4-Agent Hub & Mac Remote Control", () => {
  let tmpDir: string;
  const prevIsolation = process.env.OMS_TEST_ISOLATION;

  beforeEach(() => {
    process.env.OMS_TEST_ISOLATION = "1";
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-v36-test-"));
  });

  afterEach(() => {
    if (prevIsolation === undefined) delete process.env.OMS_TEST_ISOLATION;
    else process.env.OMS_TEST_ISOLATION = prevIsolation;
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  test("SecretaryCore tracks manual vs auto memories and blocks resurrection after forget", () => {
    const core = new SecretaryCore(tmpDir);
    const manual = core.addOrUpdateMemoryEntry({
      text: "偏好先给结论再展开技术细节",
      source: "manual",
    });
    expect(manual.source).toBe("manual");

    const evolved1 = core.evolveMemoryFromDialogue({
      profileAdditions: ["习惯通勤时听尺素摘要播报", "偏好先给结论再展开技术细节"],
    });
    expect(evolved1.addedProfile).toBe(1);
    expect(evolved1.reinforcedProfile).toBe(1);

    const vaultRoot = path.join(tmpDir, "vault");
    fs.mkdirSync(path.join(vaultRoot, "04_topics"), { recursive: true });
    fs.writeFileSync(
      path.join(vaultRoot, "04_topics", "agent_memory.md"),
      "# Agent 记忆架构\n持续演化的双速记忆需要遗忘墓碑防止噪音复活。\n",
      "utf8"
    );

    const snap1 = core.buildMemoryCenterSnapshot({
      workingMemoryItems: ["正在重构青简咫尺 v3.6"],
      vaultRoot,
    });
    expect(snap1.profileEntries.length).toBe(2);
    expect(snap1.workingEntries.length).toBe(1);
    expect(snap1.vaultAnchors.length).toBe(1);

    const autoItem = snap1.profileEntries.find((e) => e.source === "auto")!;
    expect(autoItem).toBeDefined();
    expect(core.deleteMemoryEntry(autoItem.id, true)).toBe(true);

    // Attempting to auto-distill the forgotten memory again must NOT resurrect it!
    const evolvedAfterForget = core.evolveMemoryFromDialogue({
      profileAdditions: ["习惯通勤时听尺素摘要播报"],
    });
    expect(evolvedAfterForget.addedProfile).toBe(0);
    expect(core.getProfileItems()).toEqual(["偏好先给结论再展开技术细节"]);
  });

  test("AgentSessionHub discovers WorkBuddy and OpenCode SQLite sessions and reads details", () => {
    const wbDir = path.join(tmpDir, "workbuddy");
    const ocDir = path.join(tmpDir, "opencode");
    fs.mkdirSync(path.join(wbDir, "projects", "proj1"), { recursive: true });
    fs.mkdirSync(ocDir, { recursive: true });

    const wbDb = new Database(path.join(wbDir, "workbuddy.db"), { create: true });
    wbDb.exec(`
      CREATE TABLE sessions (
        id TEXT PRIMARY KEY,
        cwd TEXT NOT NULL,
        title TEXT,
        custom_title TEXT,
        status TEXT NOT NULL DEFAULT 'completed',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        last_activity_at INTEGER,
        deleted_at INTEGER,
        model TEXT
      );
    `);
    wbDb
      .prepare(
        `INSERT INTO sessions (id, cwd, title, status, created_at, updated_at, model) VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run("wb_sess_1", tmpDir, "测试 WorkBuddy 会话", "completed", Date.now(), Date.now(), "glm-5.1");
    wbDb.close();
    fs.writeFileSync(
      path.join(wbDir, "projects", "proj1", "wb_sess_1.jsonl"),
      JSON.stringify({
        type: "message",
        role: "user",
        timestamp: Date.now(),
        content: [{ type: "input_text", text: "<system-reminder>ignore</system-reminder>帮我检查构建配置" }],
      }) + "\n",
      "utf8"
    );

    const ocDb = new Database(path.join(ocDir, "opencode.db"), { create: true });
    ocDb.exec(`
      CREATE TABLE project (id TEXT PRIMARY KEY, worktree TEXT NOT NULL, name TEXT, time_created INTEGER, time_updated INTEGER, sandboxes TEXT);
      CREATE TABLE session (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, slug TEXT, directory TEXT NOT NULL, title TEXT NOT NULL, version TEXT, time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, time_archived INTEGER, model TEXT);
      CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, data TEXT NOT NULL);
      CREATE TABLE part (id TEXT PRIMARY KEY, message_id TEXT NOT NULL, session_id TEXT NOT NULL, time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, data TEXT NOT NULL);
    `);
    ocDb
      .prepare(`INSERT INTO project (id, worktree, name, time_created, time_updated, sandboxes) VALUES (?, ?, ?, ?, ?, ?)`)
      .run("p1", tmpDir, "MyDSN", Date.now(), Date.now(), "[]");
    ocDb
      .prepare(`INSERT INTO session (id, project_id, slug, directory, title, version, time_created, time_updated, model) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run("ses_oc_1", "p1", "s1", tmpDir, "OpenCode 架构调试", "1.0", Date.now(), Date.now(), '{"id":"glm-5.3"}');
    ocDb
      .prepare(`INSERT INTO message (id, session_id, time_created, time_updated, data) VALUES (?, ?, ?, ?, ?)`)
      .run("m1", "ses_oc_1", Date.now(), Date.now(), '{"role":"assistant"}');
    ocDb
      .prepare(`INSERT INTO part (id, message_id, session_id, time_created, time_updated, data) VALUES (?, ?, ?, ?, ?, ?)`)
      .run("pt1", "m1", "ses_oc_1", Date.now(), Date.now(), '{"type":"text","text":"已完成 OpenCode 模块验证"}');
    ocDb.close();

    const hub = new AgentSessionHub({
      omsHomeDir: tmpDir,
      codexSessionsDir: path.join(tmpDir, "codex_empty"),
      agyBrainDir: path.join(tmpDir, "agy_empty"),
      agyCliDir: path.join(tmpDir, "agy_cli_empty"),
      workbuddyDir: wbDir,
      opencodeDataDir: ocDir,
      defaultWorkspacePath: tmpDir,
    });

    const sessions = hub.listSessions(20);
    expect(sessions.some((s) => s.backend === "workbuddy" && s.sessionId === "wb_sess_1")).toBe(true);
    expect(sessions.some((s) => s.backend === "opencode" && s.sessionId === "ses_oc_1")).toBe(true);

    const wbDetail = hub.getSessionDetail("workbuddy", "wb_sess_1");
    expect(wbDetail.steps[0]?.detail).toBe("帮我检查构建配置");

    const ocDetail = hub.getSessionDetail("opencode", "ses_oc_1");
    expect(ocDetail.steps[0]?.detail).toBe("已完成 OpenCode 模块验证");
  });

  test("MacOsController enforces Policy B: LAN instant unlock vs WAN biometric requirement", async () => {
    const ctl = new MacOsController(process.cwd());
    ctl.setUnlockPassword("mock_secret_123");

    let stayAwake = false;
    const baseCtx = {
      macId: "mac_studio_1",
      macName: "Mac Studio",
      activeTier: "LAN_WS",
      stayAwakeEnabled: stayAwake,
      onSetStayAwake: (en: boolean) => {
        stayAwake = en;
        return stayAwake;
      },
    };

    const lockRes = await ctl.execute({ action: "lock" }, baseCtx);
    expect(lockRes.ok).toBe(true);
    expect(lockRes.status?.screenLocked).toBe(true);

    // 1. WAN without biometricVerified must be gated with requiresBiometric: true
    const wanBlocked = await ctl.execute(
      { action: "unlock", transportTierHint: "WAN_WEBRTC", biometricVerified: false },
      { ...baseCtx, activeTier: "WAN_WEBRTC" }
    );
    expect(wanBlocked.ok).toBe(false);
    expect(wanBlocked.requiresBiometric).toBe(true);

    // 2. WAN with biometricVerified = true succeeds
    const wanPassed = await ctl.execute(
      { action: "unlock", transportTierHint: "WAN_WEBRTC", biometricVerified: true },
      { ...baseCtx, activeTier: "WAN_WEBRTC" }
    );
    expect(wanPassed.ok).toBe(true);
    expect(wanPassed.status?.screenLocked).toBe(false);

    // 3. LAN_WS unlocks immediately without biometricVerified!
    await ctl.execute({ action: "lock" }, baseCtx);
    const lanUnlock = await ctl.execute({ action: "unlock" }, baseCtx);
    expect(lanUnlock.ok).toBe(true);
    expect(lanUnlock.status?.screenLocked).toBe(false);
  });

  test("Browser Companion Auto-Curate deposits Vault source + companion note and syncs working memory", () => {
    const vaultDir = path.join(tmpDir, "vault");
    fs.mkdirSync(vaultDir, { recursive: true });

    const mirror = new MobileMemoryMirror({
      omsHomeDir: tmpDir,
      vaultRootOverride: vaultDir,
    });

    const res = mirror.curateDirectToVaultFallback({
      url: "https://www.youtube.com/watch?v=agentic2026",
      title: "Building Reliable Agent Memory Systems",
      author: "DeepMind Research",
      extractedText: "Long-term memory requires separating raw evidence from distilled working observations.",
      captureMode: "auto_dwell",
    });

    expect(res.ok).toBe(true);
    expect(res.mediaKind).toBe("video");
    expect(res.sourcePath && fs.existsSync(res.sourcePath)).toBe(true);
    expect(res.notePath && fs.existsSync(res.notePath)).toBe(true);
    expect(res.relNotePath?.startsWith("01_inputs/video/")).toBe(true);

    mirror.recordCuratedContent({
      url: res.url,
      title: res.title,
      mediaKind: res.mediaKind,
      shortSummary: res.shortSummary,
      sourcePath: res.sourcePath,
      notePath: res.notePath,
      relNotePath: res.relNotePath,
      captureMode: "auto_dwell",
      curatedAtMs: Date.now(),
    });

    const recent = mirror.getRecentCuratedContext(5);
    expect(recent.length).toBe(1);
    expect(recent[0].title).toBe("Building Reliable Agent Memory Systems");

    const wm = mirror.loadWorkingMemory();
    expect(wm.some((item) => item.includes("伴读沉淀·视频") && item.includes("Building Reliable Agent Memory"))).toBe(
      true
    );
    mirror.close();
  });
});
