import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import { EventEmitter } from "node:events";
import type { ChildProcess } from "node:child_process";
import { MobileMemoryMirror } from "../src/memory/mobile-memory-mirror.js";
import { AgentSessionHub, type SpawnedCliSpec } from "../src/agent/agent-session-hub.js";
import { OmsIpcClient } from "../src/daemon/ipc-client.js";

describe("Milestone 2 & 3: MobileMemoryMirror + AgentSessionHub", () => {
  test("3-tier memory mirror indexes non-incognito chats in FTS5, skips incognito, and promotes safely to Vault", () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-mem-test-"));
    const vaultRoot = path.join(tmpDir, "vault");
    fs.mkdirSync(vaultRoot, { recursive: true });

    const mirror = new MobileMemoryMirror({
      omsHomeDir: path.join(tmpDir, "oms"),
      vaultRootOverride: vaultRoot,
    });

    try {
      const syncRes = mirror.syncBatch({
        threads: [
          {
            threadId: "t_public",
            title: "咫尺双擎路由架构讨论",
            pinned: true,
            incognito: false,
            createdAtMs: 1000,
            updatedAtMs: 2000,
          },
          {
            threadId: "t_secret",
            title: "无痕私密提问",
            pinned: false,
            incognito: true,
            createdAtMs: 1500,
            updatedAtMs: 2500,
          },
        ],
        messages: [
          {
            messageId: "m_1",
            threadId: "t_public",
            role: "assistant",
            content: "我们采用 Shadowsocks-2022 免 TUN 本地网关与 GPT-6.2 Responses API。",
            createdAtMs: 2000,
          },
          {
            messageId: "m_secret",
            threadId: "t_secret",
            role: "user",
            content: "这条无痕记录绝不能写入数据库",
            createdAtMs: 2500,
          },
        ],
        workingMemoryItems: ["正在推进咫尺 v2.0 双脑协同架构", "偏好按需工具化召回避免日常串戏"],
        syncedAtMs: 3000,
      });

      expect(syncRes.syncedThreads).toBe(1);
      expect(syncRes.syncedMessages).toBe(1);
      expect(mirror.loadWorkingMemory()).toHaveLength(2);

      // Promote a Botanical Specimen card twice to verify collision-free non-overwriting filenames
      const firstPromote = mirror.promoteToVault({
        itemId: "spec_1",
        kind: "botanical_specimen",
        title: "染井吉野樱",
        slugHint: "prunus_yedoensis",
        markdownContent: "# 🌿 染井吉野樱\n\n蔷薇科李属代表性春季花卉。",
        jpegBase64: Buffer.from("fake-jpeg-bytes").toString("base64"),
        createdAtMs: Date.parse("2026-11-20T10:00:00Z"),
      });
      expect(firstPromote.ok).toBe(true);
      expect(fs.existsSync(firstPromote.savedNotePath)).toBe(true);
      expect(firstPromote.savedImagePath && fs.existsSync(firstPromote.savedImagePath)).toBe(true);

      const secondPromote = mirror.promoteToVault({
        itemId: "spec_2",
        kind: "botanical_specimen",
        title: "染井吉野樱",
        slugHint: "prunus_yedoensis",
        markdownContent: "# 🌿 染井吉野樱（第二株标本）",
        createdAtMs: Date.parse("2026-11-20T10:00:00Z"),
      });
      expect(secondPromote.savedNotePath).not.toBe(firstPromote.savedNotePath);
      expect(fs.readFileSync(firstPromote.savedNotePath, "utf8")).toContain("代表性春季花卉");

      // Search both FTS5 mobile chat and promoted Vault note
      const chatSearch = mirror.searchContext("Shadowsocks", 5);
      expect(chatSearch.hits.some((h) => h.source === "mobile_chat" && h.pathOrRef === "thread:t_public")).toBe(true);

      const secretSearch = mirror.searchContext("无痕记录", 5);
      expect(secretSearch.hits).toHaveLength(0);

      const vaultSearch = mirror.searchContext("蔷薇科", 5);
      expect(vaultSearch.hits.some((h) => h.source === "vault")).toBe(true);
    } finally {
      mirror.close();
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  test("AgentSessionHub discovers Codex & Antigravity desktop trajectories and resumes sessions via CLI stream-json", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-agent-hub-"));
    const codexDir = path.join(tmpDir, "codex_sessions", "2026", "11", "20");
    const agyConvDir = path.join(tmpDir, "agy_brain", "conv-uuid-1234", ".system_generated", "logs");
    const workspaceDir = path.join(tmpDir, "workspace");
    fs.mkdirSync(codexDir, { recursive: true });
    fs.mkdirSync(agyConvDir, { recursive: true });
    fs.mkdirSync(workspaceDir, { recursive: true });

    // 1. Seed a real-format Codex rollout JSONL
    const codexRollout = path.join(codexDir, "rollout-2026-11-20T09-00-00-019aa111-2222-7333-8444-555566667777.jsonl");
    fs.writeFileSync(
      codexRollout,
      [
        JSON.stringify({
          timestamp: "2026-11-20T01:00:00Z",
          type: "session_meta",
          payload: { id: "019aa111-2222-7333-8444-555566667777", cwd: workspaceDir },
        }),
        JSON.stringify({
          timestamp: "2026-11-20T01:00:01Z",
          type: "event_msg",
          payload: { type: "user_message", message: "检查重构后的代理网关延迟选路逻辑" },
        }),
        JSON.stringify({
          timestamp: "2026-11-20T01:00:05Z",
          type: "event_msg",
          payload: { type: "agent_message", message: "已完成美国与新加坡节点并发 TCP 测速验证。" },
        }),
      ].join("\n") + "\n",
      "utf8"
    );

    // 2. Seed a real-format Antigravity transcript JSONL
    fs.writeFileSync(
      path.join(agyConvDir, "transcript.jsonl"),
      [
        JSON.stringify({
          step_index: 1,
          type: "USER_INPUT",
          created_at: "2026-11-20T01:10:00Z",
          content: `Command Working Directory: ${workspaceDir}\n<USER_REQUEST>设计咫尺 v2.0 工程方案</USER_REQUEST>`,
        }),
        JSON.stringify({
          step_index: 2,
          type: "PLANNER_RESPONSE",
          created_at: "2026-11-20T01:11:00Z",
          thinking: "梳理四阶段里程碑与双擎模型矩阵",
          tool_calls: [{ name: "write_file" }],
          content: "工程设计方案已生成完毕。",
        }),
      ].join("\n") + "\n",
      "utf8"
    );

    const spawnedSpecs: SpawnedCliSpec[] = [];
    const hub = new AgentSessionHub({
      omsHomeDir: path.join(tmpDir, "oms"),
      codexSessionsDir: path.join(tmpDir, "codex_sessions"),
      agyBrainDir: path.join(tmpDir, "agy_brain"),
      defaultWorkspacePath: workspaceDir,
      cliSpawner: (spec) => {
        spawnedSpecs.push(spec);
        const emitter = new EventEmitter() as ChildProcess;
        const stdout = new PassThrough();
        const stderr = new PassThrough();
        (emitter as any).stdout = stdout;
        (emitter as any).stderr = stderr;
        (emitter as any).kill = () => true;

        queueMicrotask(() => {
          if (spec.command === "codex") {
            stdout.write(
              JSON.stringify({
                type: "item.completed",
                item: { type: "agent_message", text: "已按你的追问补充单元测试并通过。" },
              }) + "\n"
            );
          } else {
            stdout.write(
              JSON.stringify({
                type: "PLANNER_RESPONSE",
                conversation_id: "conv-uuid-1234",
                content: "Antigravity 已接续完成修改。",
              }) + "\n"
            );
          }
          stdout.end();
          emitter.emit("close", 0);
        });
        return emitter;
      },
    });

    try {
      const sessions = hub.listSessions();
      expect(sessions.some((s) => s.backend === "codex" && s.sessionId === "019aa111-2222-7333-8444-555566667777")).toBe(true);
      expect(sessions.some((s) => s.backend === "agy" && s.sessionId === "conv-uuid-1234")).toBe(true);

      const catalog = hub.getWorkspaceCatalog();
      expect(catalog.projects.some((p) => p.projectPath === workspaceDir && p.sessionCount >= 2)).toBe(true);

      const agyDetail = hub.getSessionDetail("agy", "conv-uuid-1234");
      expect(agyDetail.session.title).toContain("设计咫尺 v2.0 工程方案");
      expect(agyDetail.steps.some((st) => st.kind === "tool" && st.detail.includes("write_file"))).toBe(true);

      const events: any[] = [];
      hub.on("session_event", (e) => events.push(e));

      // Resume existing Codex session
      hub.continueSession({
        clientRequestId: "req_1",
        backend: "codex",
        sessionId: "019aa111-2222-7333-8444-555566667777",
        prompt: "再加一个超时边界用例",
      });

      // Resume existing Antigravity conversation
      hub.continueSession({
        clientRequestId: "req_2",
        backend: "agy",
        sessionId: "conv-uuid-1234",
        prompt: "同步检查一下类型定义",
      });

      await new Promise((r) => setTimeout(r, 40));

      expect(spawnedSpecs).toHaveLength(2);
      expect(spawnedSpecs[0].command).toBe("codex");
      expect(spawnedSpecs[0].args).toEqual([
        "exec",
        "resume",
        "019aa111-2222-7333-8444-555566667777",
        "--skip-git-repo-check",
        "--json",
        "再加一个超时边界用例",
      ]);
      expect(spawnedSpecs[1].command).toBe("agy");
      expect(spawnedSpecs[1].args).toEqual([
        "--conversation",
        "conv-uuid-1234",
        "--print",
        "同步检查一下类型定义",
        "--output-format",
        "stream-json",
      ]);
      expect(events.some((e) => e.backend === "codex" && e.status === "completed")).toBe(true);
      expect(events.some((e) => e.backend === "agy" && e.status === "completed")).toBe(true);
    } finally {
      hub.stopAll();
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  test("AgentSessionHub safely parses oversized multi-MB rollout JSONL via bounded head+tail slices and OmsIpcClient fails fast on socket EOF", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-large-jsonl-"));
    const codexDir = path.join(tmpDir, "codex_sessions", "2026", "05", "26");
    const workspaceDir = path.join(tmpDir, "workspace");
    const omsHome = path.join(tmpDir, "oms");
    fs.mkdirSync(codexDir, { recursive: true });
    fs.mkdirSync(workspaceDir, { recursive: true });
    fs.mkdirSync(path.join(omsHome, "run"), { recursive: true });

    const rolloutPath = path.join(
      codexDir,
      "rollout-2026-05-26T13-07-07-019e62ad-f37c-72c1-8446-7aa6d9bd6508.jsonl"
    );
    const fd = fs.openSync(rolloutPath, "w");
    try {
      fs.writeSync(
        fd,
        JSON.stringify({
          timestamp: "2026-05-26T05:07:07Z",
          type: "session_meta",
          payload: { id: "019e62ad-f37c-72c1-8446-7aa6d9bd6508", cwd: workspaceDir },
        }) + "\n"
      );
      fs.writeSync(
        fd,
        JSON.stringify({
          timestamp: "2026-05-26T05:07:08Z",
          type: "event_msg",
          payload: { type: "user_message", message: "超长历史会话首条标题验证" },
        }) + "\n"
      );
      // Write ~3 MB of middle tool output lines so both summary (768 KB) and detail (2 MB) hit bounded head+tail path
      const fillerLine =
        JSON.stringify({
          timestamp: "2026-08-01T00:00:00Z",
          type: "response_item",
          payload: { type: "function_call", name: "shell", arguments: "x".repeat(4096) },
        }) + "\n";
      for (let i = 0; i < 750; i++) {
        fs.writeSync(fd, fillerLine);
      }
      fs.writeSync(
        fd,
        JSON.stringify({
          timestamp: "2026-11-20T11:48:00Z",
          type: "event_msg",
          payload: { type: "agent_message", message: "尾部最新回复摘要已成功提取" },
        }) + "\n"
      );
    } finally {
      fs.closeSync(fd);
    }

    const hub = new AgentSessionHub({
      omsHomeDir: omsHome,
      codexSessionsDir: path.join(tmpDir, "codex_sessions"),
      agyBrainDir: path.join(tmpDir, "agy_brain"),
      defaultWorkspacePath: workspaceDir,
    });

    const sockPath = path.join(omsHome, "run", "omsd.sock");
    let dropNextRequest = false;
    const dummyServer = net.createServer((sock) => {
      sock.on("data", () => {
        if (dropNextRequest) {
          sock.destroy();
        }
      });
    });
    await new Promise<void>((resolve) => dummyServer.listen(sockPath, resolve));

    try {
      const list = hub.listSessions();
      const found = list.find((s) => s.sessionId === "019e62ad-f37c-72c1-8446-7aa6d9bd6508");
      expect(found).toBeDefined();
      expect(found?.title).toContain("超长历史会话首条标题验证");
      expect(found?.lastSummary).toContain("尾部最新回复摘要已成功提取");

      const detail = hub.getSessionDetail("codex", "019e62ad-f37c-72c1-8446-7aa6d9bd6508");
      expect(detail.session.title).toContain("超长历史会话首条标题验证");
      expect(detail.steps.some((st) => st.detail.includes("尾部最新回复摘要已成功提取"))).toBe(true);

      // Verify OmsIpcClient rejects immediately on abrupt server EOF instead of waiting 30s
      dropNextRequest = true;
      const ipc = new OmsIpcClient(omsHome);
      const t0 = performance.now();
      await expect(ipc.call("phone_presence_status", {}, 15_000)).rejects.toThrow(
        /closed unexpectedly|ECONNRESET/i
      );
      expect(performance.now() - t0).toBeLessThan(1500);
    } finally {
      hub.stopAll();
      await new Promise<void>((resolve) => dummyServer.close(() => resolve()));
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  test("AgentSessionHub enriches titles/projects from Codex & Antigravity SQLite DBs, blocks regex path pollution, and extracts quotas", () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-sqlite-meta-"));
    const codexRoot = path.join(tmpDir, "codex");
    const codexSessions = path.join(codexRoot, "sessions", "2026", "11", "20");
    const agyRoot = path.join(tmpDir, "antigravity");
    const agyBrain = path.join(agyRoot, "brain");
    const projDir = path.join(tmpDir, "ai_research");
    const omsHome = path.join(tmpDir, "oms");
    fs.mkdirSync(codexSessions, { recursive: true });
    fs.mkdirSync(path.join(agyBrain, "conv-agy-001", ".system_generated", "logs"), { recursive: true });
    fs.mkdirSync(projDir, { recursive: true });
    fs.mkdirSync(omsHome, { recursive: true });

    // 1. Seed Codex `state_5.sqlite` + rollout with `token_count.rate_limits`
    const { Database } = require("bun:sqlite");
    const cdb = new Database(path.join(codexRoot, "state_5.sqlite"));
    cdb.exec(`
      CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT NOT NULL);
      CREATE TABLE project_roots (project_id TEXT NOT NULL, path TEXT NOT NULL);
      CREATE TABLE threads (
        id TEXT PRIMARY KEY,
        name TEXT,
        title TEXT,
        cwd TEXT,
        preview TEXT,
        updated_at INTEGER,
        archived INTEGER DEFAULT 0,
        agent_role TEXT DEFAULT ''
      );
    `);
    cdb.run("INSERT INTO projects VALUES (?, ?)", ["p1", "AI调研分析"]);
    cdb.run("INSERT INTO project_roots VALUES (?, ?)", ["p1", projDir]);
    cdb.run("INSERT INTO threads VALUES (?, ?, ?, ?, ?, ?, ?, ?)", [
      "01a1f8c8-bb49-7b10-bcd3-3c74e878f6f0",
      "学习笔记卡片",
      "原始长提示词",
      projDir,
      "预览文本",
      1795178000,
      0,
      "",
    ]);
    cdb.close();

    fs.writeFileSync(
      path.join(codexSessions, "rollout-2026-11-20T19-47-47-01a1f8c8-bb49-7b10-bcd3-3c74e878f6f0.jsonl"),
      [
        JSON.stringify({ timestamp: "2026-11-20T11:47:47Z", type: "session_meta", payload: { id: "01a1f8c8-bb49-7b10-bcd3-3c74e878f6f0", cwd: projDir } }),
        JSON.stringify({ timestamp: "2026-11-20T11:48:00Z", type: "turn_context", payload: { model: "gpt-5.4", cwd: projDir } }),
        JSON.stringify({
          timestamp: "2026-11-20T11:49:00Z",
          type: "event_msg",
          payload: {
            type: "token_count",
            rate_limits: {
              plan_type: "prolite",
              primary: { used_percent: 82, window_minutes: 10080, resets_at: 1795217109 },
              secondary: null,
            },
          },
        }),
      ].join("\n") + "\n"
    );

    // 2. Seed Antigravity `conversation_summaries.db` + transcript containing regex-like source code output
    const adb = new Database(path.join(agyRoot, "conversation_summaries.db"));
    adb.exec(`
      CREATE TABLE conversation_summaries (
        conversation_id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        preview TEXT NOT NULL,
        workspace_uris TEXT NOT NULL,
        status TEXT NOT NULL,
        nesting_depth INTEGER NOT NULL DEFAULT 0,
        killed NUMERIC NOT NULL DEFAULT 0,
        last_modified_time DATETIME NOT NULL
      );
    `);
    adb.run("INSERT INTO conversation_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?)", [
      "conv-agy-001",
      "OMS App Redesign Proposal",
      "Preview",
      JSON.stringify([`file://${projDir}`]),
      "CASCADE_RUN_STATUS_IDLE",
      0,
      0,
      "2026-11-20 12:15:33+00:00",
    ]);
    adb.close();

    fs.writeFileSync(
      path.join(agyBrain, "conv-agy-001", ".system_generated", "logs", "transcript.jsonl"),
      [
        JSON.stringify({
          type: "GENERIC",
          created_at: "2026-11-20T12:10:00Z",
          content: "read_file output: content.match(/Command Working Directory:\\s*([^\\n<]+)/)",
        }),
        JSON.stringify({
          type: "PLANNER_RESPONSE",
          created_at: "2026-11-20T12:15:00Z",
          model: "Gemini 4 Neon (High)",
          input_tokens: 1000,
          cache_read_tokens: 9000,
          output_tokens: 2000,
          content: "已完成体验升级。",
        }),
      ].join("\n") + "\n"
    );

    const hub = new AgentSessionHub({
      omsHomeDir: omsHome,
      codexSessionsDir: path.join(codexRoot, "sessions"),
      agyBrainDir: agyBrain,
      defaultWorkspacePath: tmpDir,
    });

    try {
      const cat = hub.getWorkspaceCatalog(20);
      const codexSess = cat.sessions.find((s) => s.backend === "codex");
      const agySess = cat.sessions.find((s) => s.backend === "agy");

      expect(codexSess?.title).toBe("学习笔记卡片");
      expect(codexSess?.projectDisplayName).toBe("AI调研分析");
      expect(agySess?.title).toBe("OMS App Redesign Proposal");
      expect(agySess?.workspacePath).toBe(projDir);
      expect(agySess?.projectDisplayName).toBe("AI调研分析");

      expect(cat.quotas?.codex.available).toBe(true);
      expect(cat.quotas?.codex.planLabel).toBe("Pro Lite");
      expect(cat.quotas?.codex.windows[0]?.remainingPercent).toBe(18);
      expect(cat.quotas?.codex.windows[0]?.resetsAtMs).toBe(1795217109000);
      expect(cat.quotas?.agy.activeModel).toBe("Gemini 4 Neon (High)");
    } finally {
      hub.stopAll();
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  test("Codex response_item history, step compaction, active-writer auto-fork, and Antigravity Desktop<->CLI sync", async () => {
    const { Database } = await import("bun:sqlite");
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-hub-v32-"));
    const omsHome = path.join(tmpDir, "oms");
    const codexRoot = path.join(tmpDir, "codex");
    const codexSessDir = path.join(codexRoot, "sessions", "2026", "11", "20");
    const agyDesktopRoot = path.join(tmpDir, "agy_desktop");
    const agyDesktopBrain = path.join(agyDesktopRoot, "brain");
    const agyDesktopConv = path.join(agyDesktopRoot, "conversations");
    const agyCliRoot = path.join(tmpDir, "agy_cli");
    const projDir = path.join(tmpDir, "work_repo");

    for (const d of [omsHome, codexSessDir, agyDesktopBrain, agyDesktopConv, agyCliRoot, projDir]) {
      fs.mkdirSync(d, { recursive: true });
    }

    // 1. Seed Codex rollout using `response_item` user/assistant messages + 30 tool calls (verifying history isn't evicted!)
    const rolloutLines: string[] = [
      JSON.stringify({ type: "session_meta", payload: { id: "019e-codex-locked", cwd: projDir } }),
      JSON.stringify({
        type: "response_item",
        timestamp: "2026-11-20T10:00:00Z",
        payload: { type: "message", role: "user", content: [{ type: "input_text", text: "第一轮桌面端提问：分析订单模块" }] },
      }),
      JSON.stringify({
        type: "response_item",
        timestamp: "2026-11-20T10:00:05Z",
        payload: { type: "message", role: "assistant", content: [{ type: "output_text", text: "第一轮回复：已完成订单模块梳理。" }] },
      }),
    ];
    for (let i = 0; i < 35; i++) {
      rolloutLines.push(
        JSON.stringify({
          type: "response_item",
          timestamp: "2026-11-20T10:01:00Z",
          payload: { type: "function_call", name: `read_file_${i}`, arguments: "{}" },
        })
      );
    }
    rolloutLines.push(
      JSON.stringify({
        type: "response_item",
        timestamp: "2026-11-20T10:02:00Z",
        payload: { type: "message", role: "assistant", content: [{ type: "output_text", text: "第二轮回复：共检查 35 个文件。" }] },
      })
    );
    fs.writeFileSync(path.join(codexSessDir, "rollout-019e-codex-locked.jsonl"), rolloutLines.join("\n") + "\n");

    // Seed Codex state_5.sqlite + .codex-global-state.json so Desktop metadata & sidebar project sync can be verified
    const cdb = new Database(path.join(codexRoot, "state_5.sqlite"));
    cdb.exec(`
      CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT NOT NULL);
      CREATE TABLE project_roots (project_id TEXT NOT NULL, path TEXT NOT NULL);
      CREATE TABLE threads (id TEXT PRIMARY KEY, name TEXT, title TEXT, cwd TEXT, source TEXT, thread_source TEXT, originator TEXT, project_id TEXT, preview TEXT, updated_at INTEGER, archived INTEGER DEFAULT 0, agent_role TEXT);
    `);
    cdb.run("INSERT INTO projects VALUES ('proj_work', '核心业务库')");
    cdb.run("INSERT INTO project_roots VALUES ('proj_work', ?)", [projDir]);
    cdb.run("INSERT INTO threads VALUES ('019e-codex-forked', '订单模块分析', '', ?, 'exec', NULL, NULL, NULL, '', 1795180000, 0, NULL)", [projDir]);
    cdb.close();

    fs.writeFileSync(
      path.join(codexRoot, ".codex-global-state.json"),
      JSON.stringify({
        "local-projects": {
          "local-proj-work-001": {
            id: "local-proj-work-001",
            name: "核心业务库",
            rootPaths: [projDir],
          },
        },
        "thread-project-assignments": {},
        "thread-project-membership-host-ids": {},
      }),
      "utf8"
    );

    // 2. Seed Antigravity Desktop `.db` + `brain` conversation (`conv-desktop-99`)
    const ddb = new Database(path.join(agyDesktopConv, "conv-desktop-99.db"));
    ddb.exec("CREATE TABLE trajectory_meta (trajectory_id TEXT PRIMARY KEY); INSERT INTO trajectory_meta VALUES ('conv-desktop-99');");
    ddb.close();
    const dLogs = path.join(agyDesktopBrain, "conv-desktop-99", ".system_generated", "logs");
    fs.mkdirSync(dLogs, { recursive: true });
    fs.writeFileSync(
      path.join(dLogs, "transcript.jsonl"),
      JSON.stringify({
        type: "USER_INPUT",
        created_at: "2026-11-20T11:00:00Z",
        content: `<user_information>\nCommand Working Directory: ${projDir}\n</user_information>\n<USER_REQUEST>桌面端 Antigravity 原始提问</USER_REQUEST>`,
      }) + "\n"
    );

    const rpcMethodsReceived: string[] = [];
    const hub = new AgentSessionHub({
      omsHomeDir: omsHome,
      codexSessionsDir: path.join(codexRoot, "sessions"),
      agyBrainDir: agyDesktopBrain,
      agyCliDir: agyCliRoot,
      defaultWorkspacePath: projDir,
      appServerSpawner: () => {
        const emitter = new EventEmitter() as ChildProcess;
        const stdin = new PassThrough();
        const stdout = new PassThrough();
        const stderr = new PassThrough();
        (emitter as any).stdin = stdin;
        (emitter as any).stdout = stdout;
        (emitter as any).stderr = stderr;
        (emitter as any).kill = () => {
          stdout.end();
          emitter.emit("close", 0);
          return true;
        };

        const rl = (awaitImportReadline => awaitImportReadline)(stdin);
        let buf = "";
        stdin.on("data", (chunk: Buffer) => {
          buf += chunk.toString("utf8");
          const lines = buf.split("\n");
          buf = lines.pop() || "";
          for (const l of lines) {
            if (!l.trim()) continue;
            const req = JSON.parse(l);
            if (req.method) rpcMethodsReceived.push(req.method);
            if (req.method === "initialize") {
              expect(req.params?.clientInfo?.name).toBe("vscode");
              stdout.write(JSON.stringify({ id: req.id, result: {} }) + "\n");
            } else if (req.method === "thread/resume") {
              // Simulate Codex Desktop active writer lock (-32600)!
              stdout.write(
                JSON.stringify({
                  id: req.id,
                  error: { code: -32600, message: "thread 019e-codex-locked already has an active writer" },
                }) + "\n"
              );
            } else if (req.method === "thread/fork") {
              stdout.write(JSON.stringify({ id: req.id, result: { thread: { id: "019e-codex-forked", source: "vscode" } } }) + "\n");
            } else if (req.method === "turn/start") {
              stdout.write(JSON.stringify({ id: req.id, result: {} }) + "\n");
              stdout.write(
                JSON.stringify({
                  method: "item/completed",
                  params: {
                    threadId: "019e-codex-forked",
                    item: { type: "agentMessage", id: "item_1", text: "已通过自动派生会话完成接续修改！" },
                  },
                }) + "\n"
              );
              stdout.write(
                JSON.stringify({
                  method: "turn/completed",
                  params: { threadId: "019e-codex-forked", turn: { id: "turn_1", status: "completed", error: null } },
                }) + "\n"
              );
            }
          }
        });
        void rl;
        return emitter;
      },
    });

    try {
      // Verify 3a: Codex response_item messages & compaction
      const codexDetail = hub.getSessionDetail("codex", "019e-codex-locked");
      expect(codexDetail.steps.some((s) => s.kind === "user" && s.detail.includes("第一轮桌面端提问"))).toBe(true);
      expect(codexDetail.steps.some((s) => s.kind === "assistant" && s.detail.includes("第一轮回复"))).toBe(true);
      expect(codexDetail.steps.some((s) => s.kind === "assistant" && s.detail.includes("第二轮回复"))).toBe(true);
      expect(codexDetail.steps.some((s) => s.title === "已折叠早期步骤")).toBe(true);

      // Verify 3b & 3c (Codex): Resume locked desktop thread -> auto-forks & links to project with source = 'vscode'
      const streamEvents: any[] = [];
      hub.on("session_event", (ev) => streamEvents.push(ev));

      hub.continueSession({
        clientRequestId: "req_lock_test",
        backend: "codex",
        sessionId: "019e-codex-locked",
        prompt: "继续生成小红书配图",
        workspacePath: projDir,
      });

      await new Promise((r) => setTimeout(r, 260));
      expect(rpcMethodsReceived).toEqual(["initialize", "initialized", "thread/resume", "thread/fork", "turn/start"]);
      expect(streamEvents.some((e) => e.sessionId === "019e-codex-forked" && e.status === "completed")).toBe(true);

      // Alias lookup: querying the old sessionId resolves seamlessly to the forked thread!
      const aliasedDetail = hub.getSessionDetail("codex", "019e-codex-locked");
      expect(aliasedDetail.session.sessionId).toBe("019e-codex-forked");
      expect(aliasedDetail.steps.some((s) => s.detail.includes("已通过自动派生会话完成接续修改"))).toBe(true);

      const checkDb = new Database(path.join(codexRoot, "state_5.sqlite"), { readonly: true });
      const row = checkDb
        .query("SELECT source, thread_source, originator, name, project_id FROM threads WHERE id = '019e-codex-forked'")
        .get() as any;
      checkDb.close();
      expect(row?.source).toBe("vscode");
      expect(row?.thread_source).toBe("user");
      expect(row?.originator).toBe("Codex Desktop");
      expect(row?.name).toContain("（手机接续）");
      expect(row?.project_id).toBe("proj_work");

      const globalState = JSON.parse(fs.readFileSync(path.join(codexRoot, ".codex-global-state.json"), "utf8"));
      expect(globalState["thread-project-assignments"]?.["019e-codex-forked"]).toEqual({
        projectKind: "local",
        projectId: "local-proj-work-001",
      });
      expect(globalState["thread-project-membership-host-ids"]?.["019e-codex-forked"]).toBe("local");

      // Verify 3b & 3c (Antigravity): Desktop -> CLI snapshot before resume, and CLI -> Desktop sync after new conversation!
      hub.prepareAgySessionForCliResume("conv-desktop-99");
      expect(fs.existsSync(path.join(agyCliRoot, "conversations", "conv-desktop-99.db"))).toBe(true);
      expect(fs.existsSync(path.join(agyCliRoot, "brain", "conv-desktop-99", ".system_generated", "logs", "transcript.jsonl"))).toBe(true);

      // Simulate `agy` creating a brand-new conversation `conv-mobile-new-77` in `agy_cli`
      const startedMs = Date.now();
      fs.mkdirSync(path.join(agyCliRoot, "conversations"), { recursive: true });
      const newCliDb = new Database(path.join(agyCliRoot, "conversations", "conv-mobile-new-77.db"));
      newCliDb.exec("CREATE TABLE trajectory_meta (trajectory_id TEXT PRIMARY KEY); INSERT INTO trajectory_meta VALUES ('conv-mobile-new-77');");
      newCliDb.close();
      const newCliLogs = path.join(agyCliRoot, "brain", "conv-mobile-new-77", ".system_generated", "logs");
      fs.mkdirSync(newCliLogs, { recursive: true });
      fs.writeFileSync(path.join(newCliLogs, "transcript.jsonl"), JSON.stringify({ type: "USER_INPUT", content: "<USER_REQUEST>手机新建 Antigravity 会话</USER_REQUEST>" }) + "\n");

      const syncedUuid = hub.syncAgyCliSessionToDesktop("pending_1234", startedMs, projDir, "手机新建会话");
      expect(syncedUuid).toBe("conv-mobile-new-77");
      expect(fs.existsSync(path.join(agyDesktopConv, "conv-mobile-new-77.db"))).toBe(true);
      expect(fs.existsSync(path.join(agyDesktopBrain, "conv-mobile-new-77", ".system_generated", "logs", "transcript.jsonl"))).toBe(true);
      expect(fs.existsSync(path.join(agyCliRoot, "conversations", "conv-mobile-new-77.db"))).toBe(false);
    } finally {
      hub.stopAll();
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  test("Desktop visibility regression: unregistered workspace auto-project creation, Antigravity metadata/pbtxt/CLI-duplicate cleanup, lock-safe fork, and structured stream-json parsing", async () => {
    const { Database } = await import("bun:sqlite");
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-desktop-vis-"));
    const omsHome = path.join(tmpDir, "oms");
    const codexRoot = path.join(tmpDir, "codex");
    const codexSessDir = path.join(codexRoot, "sessions", "2026", "11", "22");
    const geminiRoot = path.join(tmpDir, "gemini");
    const agyDesktopRoot = path.join(geminiRoot, "antigravity");
    const agyDesktopBrain = path.join(agyDesktopRoot, "brain");
    const agyDesktopConv = path.join(agyDesktopRoot, "conversations");
    const agyDesktopAnn = path.join(agyDesktopRoot, "annotations");
    const agyCliRoot = path.join(geminiRoot, "antigravity-cli");
    const unregisteredRepo = path.join(tmpDir, "x_project", "poem");

    for (const d of [
      omsHome,
      codexSessDir,
      agyDesktopBrain,
      agyDesktopConv,
      agyDesktopAnn,
      path.join(agyCliRoot, "conversations"),
      unregisteredRepo,
      path.join(unregisteredRepo, ".git"),
    ]) {
      fs.mkdirSync(d, { recursive: true });
    }

    // 1. Seed Codex `state_5.sqlite` where `originator` is `'vscode'` and `poem` has NO project yet
    const cdb = new Database(path.join(codexRoot, "state_5.sqlite"));
    cdb.exec(`
      CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT NOT NULL, metadata TEXT NOT NULL DEFAULT '{}', position INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL);
      CREATE TABLE project_roots (project_id TEXT NOT NULL, position INTEGER NOT NULL, path TEXT NOT NULL);
      CREATE TABLE threads (id TEXT PRIMARY KEY, name TEXT, title TEXT, cwd TEXT, source TEXT, thread_source TEXT, originator TEXT, project_id TEXT, updated_at INTEGER, archived INTEGER DEFAULT 0, agent_role TEXT);
    `);
    cdb.run(
      "INSERT INTO threads VALUES ('01a1-poem-new', '', '获取最新代码', ?, 'vscode', NULL, 'vscode', NULL, 1795216469, 0, NULL)",
      [unregisteredRepo]
    );
    cdb.close();

    fs.writeFileSync(
      path.join(codexRoot, ".codex-global-state.json"),
      JSON.stringify({
        "electron-renderer-content-owner": "owner123",
        "electron-owner-value:electron-saved-workspace-roots:owner123": [],
        "local-projects": {},
        "project-order": [],
        "thread-project-assignments": {},
        "thread-project-membership-host-ids": {},
      }),
      "utf8"
    );

    const hub = new AgentSessionHub({
      omsHomeDir: omsHome,
      codexSessionsDir: path.join(codexRoot, "sessions"),
      agyBrainDir: agyDesktopBrain,
      agyCliDir: agyCliRoot,
      defaultWorkspacePath: unregisteredRepo,
      cliSpawner: () => {
        const emitter = new EventEmitter() as ChildProcess;
        const stdout = new PassThrough();
        const stderr = new PassThrough();
        (emitter as any).stdout = stdout;
        (emitter as any).stderr = stderr;
        (emitter as any).kill = () => true;
        queueMicrotask(() => {
          stdout.write(
            JSON.stringify({
              type: "assistant",
              conversation_id: "b7a4b44b-5a7c-45e2-866e-fa9ca48acbbb",
              content: [{ type: "text", text: "结构化数组消息已正确提取，完整保留 Markdown 正文！" }],
            }) + "\n"
          );
          stdout.write(
            JSON.stringify({
              type: "RESULT",
              conversation_id: "b7a4b44b-5a7c-45e2-866e-fa9ca48acbbb",
              result: { usage: { output_tokens: 42 } },
            }) + "\n"
          );
          stdout.end();
          emitter.emit("close", 0);
        });
        return emitter;
      },
    });

    try {
      // Test A: Codex unregistered workspace auto-creates project & overwrites originator 'vscode' -> 'Codex Desktop'
      hub.linkCodexThreadToWorkspaceProject("01a1-poem-new", unregisteredRepo, "获取最新代码优化项");
      const verifyCdb = new Database(path.join(codexRoot, "state_5.sqlite"), { readonly: true });
      const createdProj = verifyCdb
        .query("SELECT p.id, p.name, r.path FROM projects p JOIN project_roots r ON p.id = r.project_id")
        .get() as any;
      const updatedThread = verifyCdb
        .query("SELECT originator, thread_source, project_id, name FROM threads WHERE id = '01a1-poem-new'")
        .get() as any;
      verifyCdb.close();

      expect(createdProj?.name).toBe("poem");
      expect(createdProj?.path).toBe(unregisteredRepo);
      expect(updatedThread?.originator).toBe("Codex Desktop");
      expect(updatedThread?.thread_source).toBe("user");
      expect(updatedThread?.project_id).toBe(createdProj?.id);
      expect(updatedThread?.name).toBe("获取最新代码优化项");

      const gs = JSON.parse(fs.readFileSync(path.join(codexRoot, ".codex-global-state.json"), "utf8"));
      expect(gs["electron-owner-value:electron-saved-workspace-roots:owner123"]).toContain(unregisteredRepo);
      expect(gs["thread-project-assignments"]?.["01a1-poem-new"]?.projectKind).toBe("local");

      // Test B: Antigravity new CLI conversation with empty trajectory_metadata_blob & source=0 gets normalized, titled, and CLI duplicate removed
      const newConvId = "b7a4b44b-5a7c-45e2-866e-fa9ca48acbbb";
      const cliDbPath = path.join(agyCliRoot, "conversations", `${newConvId}.db`);
      const cliDb = new Database(cliDbPath);
      cliDb.exec(`
        CREATE TABLE trajectory_meta (trajectory_id TEXT PRIMARY KEY, cascade_id TEXT, trajectory_type INTEGER, source INTEGER);
        CREATE TABLE trajectory_metadata_blob (id TEXT PRIMARY KEY, data BLOB);
        CREATE TABLE steps (idx INTEGER PRIMARY KEY);
        INSERT INTO trajectory_meta VALUES ('traj-1', '${newConvId}', 0, 0);
        INSERT INTO trajectory_metadata_blob VALUES ('main', X'');
        INSERT INTO steps VALUES (1), (2), (3);
      `);
      cliDb.close();

      const syncedId = hub.syncAgyCliSessionToDesktop(newConvId, Date.now(), unregisteredRepo, "移动端布局升级方案");
      expect(syncedId).toBe(newConvId);
      expect(fs.existsSync(cliDbPath)).toBe(false);

      const agyProjectsDir = path.join(geminiRoot, "config", "projects");
      const projFiles = fs.readdirSync(agyProjectsDir).filter((f) => f.endsWith(".json"));
      expect(projFiles.length).toBe(1);
      const createdAgyProj = JSON.parse(fs.readFileSync(path.join(agyProjectsDir, projFiles[0]), "utf8"));
      expect(createdAgyProj.name).toBe("poem");

      const desktopDb = new Database(path.join(agyDesktopConv, `${newConvId}.db`), { readonly: true });
      const tm = desktopDb.query("SELECT * FROM trajectory_meta").get() as any;
      const blobRow = desktopDb.query("SELECT data FROM trajectory_metadata_blob WHERE id = 'main'").get() as any;
      desktopDb.close();
      expect(tm?.trajectory_type).toBe(4);
      expect(tm?.source).toBe(1);
      const blobBuf = Buffer.from(blobRow.data);
      expect(blobBuf.includes(createdAgyProj.id)).toBe(true);
      expect(blobBuf.includes("poem")).toBe(true);

      const pbtxt = fs.readFileSync(path.join(agyDesktopAnn, `${newConvId}.pbtxt`), "utf8");
      expect(pbtxt).toContain('title:"移动端布局升级方案"');

      // Test C: Antigravity locked Desktop conversation forks cleanly without overwriting original Desktop DB or losing original title
      const lockedConvId = "80c08884-6fc4-4b73-8547-b51e68ca1d1d";
      fs.writeFileSync(path.join(agyDesktopAnn, `${lockedConvId}.pbtxt`), 'title:"OMS App Redesign Proposal" last_user_view_time:{seconds:1795000000 nanos:0}', "utf8");
      const origDesktopDbPath = path.join(agyDesktopConv, `${lockedConvId}.db`);
      const origDb = new Database(origDesktopDbPath);
      origDb.exec("CREATE TABLE steps (idx INTEGER PRIMARY KEY); INSERT INTO steps VALUES (1);");
      origDb.close();

      const resumedCliDbPath = path.join(agyCliRoot, "conversations", `${lockedConvId}.db`);
      const resDb = new Database(resumedCliDbPath);
      resDb.exec(`
        CREATE TABLE trajectory_meta (trajectory_id TEXT PRIMARY KEY, cascade_id TEXT, trajectory_type INTEGER, source INTEGER);
        CREATE TABLE trajectory_metadata_blob (id TEXT PRIMARY KEY, data BLOB);
        CREATE TABLE steps (idx INTEGER PRIMARY KEY);
        INSERT INTO trajectory_meta VALUES ('traj-locked', '${lockedConvId}', 4, 17);
        INSERT INTO trajectory_metadata_blob VALUES ('main', X'');
        INSERT INTO steps VALUES (1), (2), (3), (4);
      `);
      resDb.close();

      const forkedAgyId = hub.syncAgyCliSessionToDesktop(lockedConvId, Date.now(), unregisteredRepo, "继续追问", true);
      expect(forkedAgyId).not.toBe(lockedConvId);
      expect(fs.existsSync(resumedCliDbPath)).toBe(false);
      const forkedPbtxt = fs.readFileSync(path.join(agyDesktopAnn, `${forkedAgyId}.pbtxt`), "utf8");
      expect(forkedPbtxt).toContain("OMS App Redesign Proposal（手机接续）");
      const forkedDb = new Database(path.join(agyDesktopConv, `${forkedAgyId}.db`), { readonly: true });
      expect((forkedDb.query("SELECT count(*) AS c FROM steps").get() as any)?.c).toBe(4);
      expect((forkedDb.query("SELECT cascade_id, source FROM trajectory_meta").get() as any)).toEqual({
        cascade_id: forkedAgyId,
        source: 1,
      });
      forkedDb.close();

      // Test D: Structured stream-json content extracts clean text (never `[object Object]`)
      hub.startSession({
        clientRequestId: "req_obj_test",
        backend: "agy",
        prompt: "测试结构化输出",
      });
      await new Promise((r) => setTimeout(r, 40));
      const detail = hub.getSessionDetail("agy", newConvId);
      expect(detail.session.lastSummary).toContain("结构化数组消息已正确提取");
      expect(detail.session.lastSummary).not.toContain("[object Object]");
    } finally {
      hub.stopAll();
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});
