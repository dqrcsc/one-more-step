import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import { EventEmitter } from "node:events";
import type { ChildProcess } from "node:child_process";
import { MobileMemoryMirror } from "../src/memory/mobile-memory-mirror.js";
import { AgentSessionHub, type SpawnedCliSpec } from "../src/agent/agent-session-hub.js";

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
});
