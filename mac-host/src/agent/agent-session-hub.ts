import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcess } from "node:child_process";
import readline from "node:readline";
import { Database } from "bun:sqlite";
import type {
  AgentBackendQuotaStatus,
  AgentBackendType,
  AgentProjectItem,
  AgentQuotasSnapshot,
  AgentQuotaWindowItem,
  AgentSessionContinueParams,
  AgentSessionDetailResult,
  AgentSessionStartParams,
  AgentSessionStreamEventParams,
  AgentSessionSummary,
  AgentStepItem,
  AgentWorkspaceCatalogResult,
} from "@oms/shared-protocol";

export interface SpawnedCliSpec {
  command: string;
  args: string[];
  cwd: string;
}

export type CliSpawnerFn = (spec: SpawnedCliSpec) => ChildProcess;

export interface AgentSessionHubOptions {
  omsHomeDir: string;
  codexSessionsDir?: string;
  agyBrainDir?: string;
  defaultWorkspacePath?: string;
  cliSpawner?: CliSpawnerFn;
}

interface ManagedTaskState {
  summary: AgentSessionSummary;
  steps: AgentStepItem[];
  child: ChildProcess | null;
}

/**
 * Milestone 3 (v2.0): Mac Heavy-Brain Hub (`AgentSessionHub`).
 *
 * 1) Discovers & parses live desktop/CLI trajectories for BOTH:
 *    - OpenAI Codex (`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`)
 *    - Google Antigravity (`~/.gemini/antigravity/brain/<id>/.system_generated/logs/transcript.jsonl`)
 * 2) Dispatches new tasks & continues existing sessions (`codex exec resume` / `agy --conversation`)
 *    streaming structured `AgentSessionStreamEventParams` back to the Xiaomi phone in real time!
 */
export class AgentSessionHub extends EventEmitter {
  private readonly hubDir: string;
  private readonly statePath: string;
  private readonly codexSessionsDir: string;
  private readonly agyBrainDir: string;
  private readonly defaultWorkspacePath: string;
  private readonly cliSpawner: CliSpawnerFn;
  private readonly managedTasks = new Map<string, ManagedTaskState>();
  private readonly fileSummaryCache = new Map<
    string,
    { mtimeMs: number; size: number; summary: AgentSessionSummary | null }
  >();
  private cachedCodexQuota: { fingerprint: string; status: AgentBackendQuotaStatus } | null = null;
  private cachedAgyQuota: { fetchedAtMs: number; status: AgentBackendQuotaStatus } | null = null;

  constructor(opts: AgentSessionHubOptions) {
    super();
    this.hubDir = path.join(opts.omsHomeDir, "agent_hub");
    fs.mkdirSync(this.hubDir, { recursive: true, mode: 0o700 });
    this.statePath = path.join(this.hubDir, "mobile_sessions.json");
    this.codexSessionsDir = opts.codexSessionsDir || path.join(os.homedir(), ".codex", "sessions");
    this.agyBrainDir = opts.agyBrainDir || path.join(os.homedir(), ".gemini", "antigravity", "brain");
    this.defaultWorkspacePath = opts.defaultWorkspacePath || process.cwd();
    this.cliSpawner =
      opts.cliSpawner ||
      ((spec) =>
        spawn(spec.command, spec.args, {
          cwd: spec.cwd,
          env: {
            ...process.env,
            PATH: `${os.homedir()}/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:${process.env.PATH || ""}`,
          },
          stdio: ["ignore", "pipe", "pipe"],
        }));
    this.loadPersistedTasks();
  }

  public listSessions(limit = 24): AgentSessionSummary[] {
    const byKey = new Map<string, AgentSessionSummary>();
    const codexMeta = this.loadCodexIndexMetadata();

    // 1. Mobile-dispatched / continued sessions tracked in memory
    for (const [, st] of this.managedTasks) {
      byKey.set(`${st.summary.backend}:${st.summary.sessionId}`, { ...st.summary });
    }

    // 2. Discover Codex desktop/CLI sessions from `~/.codex/sessions` + `state_*.sqlite`
    for (const s of this.scanCodexSessions(40, codexMeta)) {
      const key = `codex:${s.sessionId}`;
      const existing = byKey.get(key);
      if (!existing || (existing.status !== "running" && s.updatedAtMs >= existing.updatedAtMs)) {
        byKey.set(key, existing ? { ...s, origin: existing.origin } : s);
      }
    }

    // 3. Discover Antigravity (`agy`) desktop/CLI conversations from `conversation_summaries.db` + `brain/`
    for (const s of this.scanAgySessions(40)) {
      const key = `agy:${s.sessionId}`;
      const existing = byKey.get(key);
      if (!existing || (existing.status !== "running" && s.updatedAtMs >= existing.updatedAtMs)) {
        byKey.set(key, existing ? { ...s, origin: existing.origin } : s);
      }
    }

    const sorted = Array.from(byKey.values())
      .map((s) => {
        const normWs = (s.workspacePath || "").trim().replace(/\/+$/, "");
        const customName = codexMeta.projectNamesByPath.get(normWs);
        const fallbackName = normWs ? path.basename(normWs) || normWs : "工作区";
        return {
          ...s,
          projectDisplayName: s.projectDisplayName || customName || fallbackName,
        };
      })
      .sort((a, b) => {
        if (a.status === "running" && b.status !== "running") return -1;
        if (b.status === "running" && a.status !== "running") return 1;
        return b.updatedAtMs - a.updatedAtMs;
      });

    return sorted.slice(0, Math.max(1, Math.min(80, limit)));
  }

  public getWorkspaceCatalog(sessionLimit = 48): AgentWorkspaceCatalogResult {
    const codexMeta = this.loadCodexIndexMetadata();
    const sessions = this.listSessions(sessionLimit);
    const projectsMap = new Map<string, AgentProjectItem>();
    const home = os.homedir();
    const isDefaultHome =
      process.env.OMS_TEST_ISOLATION !== "1" &&
      path.resolve(this.codexSessionsDir) === path.resolve(path.join(home, ".codex", "sessions"));

    const registerDir = (absPath: string, groupHint: string, fallbackMtimeMs = 0, explicitDisplayName?: string) => {
      const normalized = absPath.trim().replace(/\/+$/, "");
      if (!normalized || !path.isAbsolute(normalized) || !fs.existsSync(normalized)) return;
      try {
        if (!fs.statSync(normalized).isDirectory()) return;
      } catch {
        return;
      }
      const mappedCustom = explicitDisplayName || codexMeta.projectNamesByPath.get(normalized);
      if (projectsMap.has(normalized)) {
        if (mappedCustom) {
          const cur = projectsMap.get(normalized)!;
          cur.displayName = mappedCustom;
        }
        return;
      }
      const base = mappedCustom || path.basename(normalized) || normalized;
      let mtime = fallbackMtimeMs;
      if (!mtime) {
        try {
          mtime = Math.floor(fs.statSync(normalized).mtimeMs);
        } catch {
          mtime = 0;
        }
      }
      projectsMap.set(normalized, {
        projectPath: normalized,
        displayName: base,
        group: groupHint,
        sessionCount: 0,
        runningCount: 0,
        lastActiveAtMs: mtime,
      });
    };

    // 1. Default workspace + Codex explicit named projects (`projects` + `project_roots`)
    registerDir(this.defaultWorkspacePath, "当前主项目");
    for (const [projPath, projName] of codexMeta.projectNamesByPath.entries()) {
      registerDir(
        projPath,
        projPath.includes("/codex/") ? "Codex 工作区" : "命名项目",
        0,
        projName
      );
    }

    if (isDefaultHome) {
      registerDir(path.join(home, "work"), "核心工作区");
      registerDir(path.join(home, "entropy"), "个人知识库");
      registerDir(path.join(home, "entropy", "thoughts"), "个人知识库", 0, "个人知识库 · thoughts");

      // 2. Scan `~/x_project/*` and `~/codex/*` subdirectories automatically
      for (const [subFolder, grp] of [
        ["x_project", "x_project"],
        ["codex", "Codex 工作区"],
      ] as const) {
        const root = path.join(home, subFolder);
        if (fs.existsSync(root)) {
          try {
            for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
              if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
              registerDir(path.join(root, entry.name), grp);
            }
          } catch {
            // ignore
          }
        }
      }

      // 3. Scan top-level git / agent-enabled directories in `~` (depth 1, bounded)
      try {
        for (const entry of fs.readdirSync(home, { withFileTypes: true })) {
          if (!entry.isDirectory() || entry.name.startsWith(".") || entry.name === "Library") continue;
          const full = path.join(home, entry.name);
          if (
            fs.existsSync(path.join(full, ".git")) ||
            fs.existsSync(path.join(full, "AGENTS.md")) ||
            fs.existsSync(path.join(full, "CLAUDE.md"))
          ) {
            registerDir(full, "Home 项目");
          }
        }
      } catch {
        // ignore
      }
    }

    // 4. Fold all discovered Codex & Antigravity sessions into project stats
    for (const s of sessions) {
      const ws = (s.workspacePath || "").trim().replace(/\/+$/, "");
      if (!ws || !path.isAbsolute(ws)) continue;
      if (!projectsMap.has(ws)) {
        const grp = ws.includes("/x_project/")
          ? "x_project"
          : ws.includes("/codex/")
            ? "Codex 工作区"
            : "近期会话目录";
        registerDir(ws, grp, s.updatedAtMs);
      }
      const item = projectsMap.get(ws);
      if (item) {
        item.sessionCount += 1;
        if (s.status === "running") item.runningCount += 1;
        if (s.updatedAtMs > item.lastActiveAtMs) item.lastActiveAtMs = s.updatedAtMs;
        s.projectDisplayName = item.displayName;
      }
    }

    const projects = Array.from(projectsMap.values()).sort((a, b) => {
      if (a.runningCount !== b.runningCount) return b.runningCount - a.runningCount;
      if ((a.sessionCount > 0) !== (b.sessionCount > 0)) return a.sessionCount > 0 ? -1 : 1;
      return b.lastActiveAtMs - a.lastActiveAtMs;
    });

    return {
      projects: projects.slice(0, 60),
      sessions,
      quotas: this.getQuotasSnapshot(),
    };
  }

  public getQuotasSnapshot(): AgentQuotasSnapshot {
    return {
      codex: this.collectCodexQuota(),
      agy: this.collectAgyQuota(),
      fetchedAtMs: Date.now(),
    };
  }

  public getSessionDetail(backend: AgentBackendType, sessionId: string): AgentSessionDetailResult {
    const managed = this.managedTasks.get(`${backend}:${sessionId}`);
    const diskDetail =
      backend === "codex"
        ? this.readCodexSessionDetail(sessionId)
        : this.readAgySessionDetail(sessionId);

    if (managed && diskDetail) {
      // Merge disk trajectory steps if richer, while preserving live running status from managed child
      const useSteps = diskDetail.steps.length >= managed.steps.length ? diskDetail.steps : managed.steps;
      return {
        session: {
          ...diskDetail.session,
          status: managed.summary.status === "running" ? "running" : diskDetail.session.status,
          origin: managed.summary.origin,
          lastSummary: managed.summary.lastSummary || diskDetail.session.lastSummary,
        },
        steps: useSteps.slice(-80),
      };
    }
    if (managed) {
      return {
        session: { ...managed.summary },
        steps: managed.steps.slice(-80),
      };
    }
    if (diskDetail) {
      return diskDetail;
    }
    throw new Error(`未找到会话 ${backend}:${sessionId}`);
  }

  public startSession(params: AgentSessionStartParams): AgentSessionSummary {
    const workspace = this.resolveWorkspace(params.workspacePath);
    const provisionalId = `pending_${params.clientRequestId.replace(/[^a-zA-Z0-9_-]/g, "").slice(-16) || Date.now()}`;
    const now = Date.now();
    const summary: AgentSessionSummary = {
      sessionId: provisionalId,
      backend: params.backend,
      origin: "mobile",
      title: (params.titleHint || params.prompt || "新建远程任务").trim().slice(0, 64),
      workspacePath: workspace,
      status: "running",
      lastSummary: "🚀 已在 Mac 启动任务，等待首条执行流…",
      updatedAtMs: now,
      canContinue: true,
    };
    const initialStep: AgentStepItem = {
      stepIndex: 1,
      kind: "user",
      title: "📱 手机远程派单",
      detail: params.prompt,
      timestampMs: now,
    };

    const spec: SpawnedCliSpec =
      params.backend === "codex"
        ? {
            command: "codex",
            args: [
              "exec",
              "--skip-git-repo-check",
              "--sandbox",
              "workspace-write",
              "--json",
              "-C",
              workspace,
              params.prompt,
            ],
            cwd: workspace,
          }
        : {
            command: "agy",
            args: ["--print", params.prompt, "--output-format", "stream-json"],
            cwd: workspace,
          };

    return this.launchManagedProcess(summary, [initialStep], spec);
  }

  public continueSession(params: AgentSessionContinueParams): AgentSessionSummary {
    const existingDetail = (() => {
      try {
        return this.getSessionDetail(params.backend, params.sessionId);
      } catch {
        return null;
      }
    })();

    const workspace = this.resolveWorkspace(
      params.workspacePath || existingDetail?.session.workspacePath
    );
    const now = Date.now();
    const priorSteps = existingDetail ? [...existingDetail.steps] : [];
    const userStep: AgentStepItem = {
      stepIndex: priorSteps.length + 1,
      kind: "user",
      title: "📱 手机跨端接续追问",
      detail: params.prompt,
      timestampMs: now,
    };
    priorSteps.push(userStep);

    const summary: AgentSessionSummary = {
      sessionId: params.sessionId,
      backend: params.backend,
      origin: existingDetail?.session.origin || "desktop",
      title: existingDetail?.session.title || params.prompt.slice(0, 60),
      workspacePath: workspace,
      status: "running",
      lastSummary: `🔄 正在接续会话：${params.prompt.slice(0, 48)}`,
      updatedAtMs: now,
      canContinue: true,
    };

    const spec: SpawnedCliSpec =
      params.backend === "codex"
        ? {
            command: "codex",
            args: ["exec", "resume", params.sessionId, "--skip-git-repo-check", "--json", params.prompt],
            cwd: workspace,
          }
        : {
            command: "agy",
            args: [
              "--conversation",
              params.sessionId,
              "--print",
              params.prompt,
              "--output-format",
              "stream-json",
            ],
            cwd: workspace,
          };

    return this.launchManagedProcess(summary, priorSteps, spec);
  }

  public cancelSession(backend: AgentBackendType, sessionId: string): boolean {
    const key = `${backend}:${sessionId}`;
    const state = this.managedTasks.get(key);
    if (!state || !state.child) return false;
    try {
      state.child.kill("SIGTERM");
    } catch {
      // ignore
    }
    state.summary.status = "failed";
    state.summary.lastSummary = "⏹ 已由手机端中止任务";
    state.summary.updatedAtMs = Date.now();
    state.child = null;
    this.persistTasks();
    this.emitStreamEvent({
      backend,
      sessionId,
      status: "failed",
      finalSummary: state.summary.lastSummary,
      updatedAtMs: state.summary.updatedAtMs,
    });
    return true;
  }

  public stopAll(): void {
    for (const [, st] of this.managedTasks) {
      if (st.child) {
        try {
          st.child.kill("SIGTERM");
        } catch {
          // ignore
        }
        st.child = null;
      }
    }
  }

  private launchManagedProcess(
    initialSummary: AgentSessionSummary,
    initialSteps: AgentStepItem[],
    spec: SpawnedCliSpec
  ): AgentSessionSummary {
    let currentKey = `${initialSummary.backend}:${initialSummary.sessionId}`;
    const prev = this.managedTasks.get(currentKey);
    if (prev?.child) {
      throw new Error(`该会话正在运行中 (${initialSummary.sessionId})，请等待当前轮次完成或先停止`);
    }

    const state: ManagedTaskState = {
      summary: initialSummary,
      steps: initialSteps,
      child: null,
    };
    this.managedTasks.set(currentKey, state);

    let child: ChildProcess;
    try {
      child = this.cliSpawner(spec);
    } catch (err) {
      state.summary.status = "failed";
      state.summary.lastSummary = `启动 ${spec.command} 失败: ${(err as Error).message}`;
      this.persistTasks();
      return { ...state.summary };
    }
    state.child = child;

    const rekeySessionId = (realId: string) => {
      const trimmed = realId.trim();
      if (!trimmed || trimmed === state.summary.sessionId) return;
      this.managedTasks.delete(currentKey);
      state.summary.sessionId = trimmed;
      currentKey = `${state.summary.backend}:${trimmed}`;
      this.managedTasks.set(currentKey, state);
    };

    if (child.stdout) {
      const rl = readline.createInterface({ input: child.stdout });
      rl.on("line", (line) => {
        const trimmed = line.trim();
        if (!trimmed) return;
        const parsedStep = this.parseCliStreamLine(state.summary.backend, trimmed, state.steps.length + 1, rekeySessionId);
        if (parsedStep) {
          state.steps.push(parsedStep);
          state.summary.lastSummary = `${parsedStep.title}: ${parsedStep.detail}`.slice(0, 140);
          state.summary.updatedAtMs = Date.now();
          this.emitStreamEvent({
            backend: state.summary.backend,
            sessionId: state.summary.sessionId,
            status: "running",
            step: parsedStep,
            updatedAtMs: state.summary.updatedAtMs,
          });
        }
      });
    }

    let stderrBuf = "";
    if (child.stderr) {
      child.stderr.on("data", (chunk: Buffer) => {
        stderrBuf = (stderrBuf + chunk.toString("utf8")).slice(-2000);
      });
    }

    child.on("error", (err) => {
      state.child = null;
      state.summary.status = "failed";
      state.summary.lastSummary = `执行异常: ${err.message}`;
      state.summary.updatedAtMs = Date.now();
      this.persistTasks();
      this.emitStreamEvent({
        backend: state.summary.backend,
        sessionId: state.summary.sessionId,
        status: "failed",
        finalSummary: state.summary.lastSummary,
        updatedAtMs: state.summary.updatedAtMs,
      });
    });

    child.on("close", (code) => {
      state.child = null;
      const ok = code === 0;
      if (state.summary.status === "running") {
        state.summary.status = ok ? "completed" : "failed";
      }
      if (!ok && stderrBuf.trim()) {
        state.summary.lastSummary = `退出码 ${code}: ${stderrBuf.trim().split("\n").pop()?.slice(0, 140)}`;
      } else if (ok && state.steps.length > 0) {
        const lastAssistant = [...state.steps].reverse().find((s) => s.kind === "assistant");
        if (lastAssistant) {
          state.summary.lastSummary = lastAssistant.detail.slice(0, 160);
        }
      }
      state.summary.updatedAtMs = Date.now();
      this.persistTasks();
      this.emitStreamEvent({
        backend: state.summary.backend,
        sessionId: state.summary.sessionId,
        status: state.summary.status === "completed" ? "completed" : "failed",
        finalSummary: state.summary.lastSummary,
        updatedAtMs: state.summary.updatedAtMs,
      });
    });

    this.persistTasks();
    return { ...state.summary };
  }

  private parseCliStreamLine(
    backend: AgentBackendType,
    line: string,
    nextIndex: number,
    onDiscoveredSessionId: (id: string) => void
  ): AgentStepItem | null {
    const now = Date.now();
    try {
      const obj = JSON.parse(line) as Record<string, any>;
      // Discover real thread_id / session_id / conversation_id if emitted by `codex exec --json` or `agy --output-format stream-json`
      const realId =
        obj.thread_id ||
        obj.session_id ||
        obj.conversation_id ||
        obj.conversationId ||
        obj.payload?.id;
      if (typeof realId === "string" && realId.length >= 8) {
        onDiscoveredSessionId(realId);
      }

      if (backend === "codex") {
        const type = String(obj.type || "");
        const item = obj.item as Record<string, any> | undefined;
        if (type === "thread.started") {
          return {
            stepIndex: nextIndex,
            kind: "status",
            title: "会话就绪",
            detail: `Codex 会话 ID: ${obj.thread_id || "active"}`,
            timestampMs: now,
          };
        }
        if (item && (type === "item.completed" || type === "item.started")) {
          const itemType = String(item.type || "");
          if (itemType === "reasoning") {
            const txt = String(item.text || item.summary || "").trim();
            if (!txt) return null;
            return { stepIndex: nextIndex, kind: "thinking", title: "💭 思考推演", detail: txt.slice(0, 500), timestampMs: now };
          }
          if (itemType === "command_execution" || itemType === "tool_call") {
            const cmd = String(item.command || item.name || "tool").trim();
            if (type === "item.started") {
              return { stepIndex: nextIndex, kind: "tool", title: "🔧 执行命令", detail: cmd.slice(0, 300), timestampMs: now };
            }
            const out = String(item.aggregated_output || item.output || "").trim();
            return {
              stepIndex: nextIndex,
              kind: "tool",
              title: `✅ 命令完成 (exit=${item.exit_code ?? 0})`,
              detail: (out || cmd).slice(0, 400),
              timestampMs: now,
            };
          }
          if (itemType === "agent_message" || itemType === "message") {
            const txt = String(item.text || item.content || "").trim();
            if (!txt) return null;
            return { stepIndex: nextIndex, kind: "assistant", title: "🤖 Codex 回复", detail: txt.slice(0, 1200), timestampMs: now };
          }
        }
      } else {
        // Antigravity (`agy --output-format stream-json`)
        const type = String(obj.type || obj.role || "");
        if (typeof obj.thinking === "string" && obj.thinking.trim()) {
          return {
            stepIndex: nextIndex,
            kind: "thinking",
            title: "💭 Antigravity 思考",
            detail: obj.thinking.trim().slice(0, 500),
            timestampMs: now,
          };
        }
        if (Array.isArray(obj.tool_calls) && obj.tool_calls.length > 0) {
          const names = obj.tool_calls.map((tc: any) => tc?.name || tc?.function?.name || "tool").join(", ");
          return {
            stepIndex: nextIndex,
            kind: "tool",
            title: "🔧 调用工具",
            detail: names,
            timestampMs: now,
          };
        }
        const text = String(obj.content || obj.text || obj.result || "").trim();
        if (text && (type.includes("assistant") || type.includes("RESULT") || type.includes("PLANNER") || type === "message" || !type)) {
          return {
            stepIndex: nextIndex,
            kind: "assistant",
            title: "🌌 Antigravity 回复",
            detail: text.slice(0, 1200),
            timestampMs: now,
          };
        }
      }
      return null;
    } catch {
      // Plain text line fallback
      return {
        stepIndex: nextIndex,
        kind: "assistant",
        title: backend === "codex" ? "🤖 Codex" : "🌌 Antigravity",
        detail: line.slice(0, 800),
        timestampMs: now,
      };
    }
  }

  private emitStreamEvent(evt: AgentSessionStreamEventParams): void {
    this.emit("session_event", evt);
  }

  /**
   * Bounded JSONL head + tail line reader.
   * Prevents event-loop stalls and WebKit/JSC `WTF::StringImpl` >2GB SIGTRAP crashes
   * when long-lived Codex or Antigravity rollout transcripts grow to hundreds of MB or multi-GB.
   */
  private readJsonlBoundedLines(
    filePath: string,
    headBytes: number,
    tailBytes: number
  ): string[] {
    const st = fs.statSync(filePath);
    if (st.size <= 0) return [];
    if (st.size <= headBytes + tailBytes) {
      return fs.readFileSync(filePath, "utf8").split(/\r?\n/).filter(Boolean);
    }

    const fd = fs.openSync(filePath, "r");
    try {
      const headBuf = Buffer.allocUnsafe(headBytes);
      const headRead = fs.readSync(fd, headBuf, 0, headBytes, 0);
      let headLines = headBuf.subarray(0, headRead).toString("utf8").split(/\r?\n/);
      let consumedHeadEnd = headRead;

      if (headLines.length > 1) {
        // Last line of head slice may be cut mid-UTF8 or mid-JSON
        headLines.pop();
      } else {
        // Line 1 (system instructions / skill catalog) exceeded headBytes:
        // scan forward up to 2.5MB to locate the first newline and read 256KB of real user/event lines after it.
        headLines = [];
        const scanCap = Math.min(st.size, 2560 * 1024);
        const stepBuf = Buffer.allocUnsafe(256 * 1024);
        let pos = headRead;
        let firstNewlineOffset = -1;
        while (pos < scanCap) {
          const n = fs.readSync(fd, stepBuf, 0, Math.min(stepBuf.length, scanCap - pos), pos);
          if (n <= 0) break;
          const nlIdx = stepBuf.subarray(0, n).indexOf(0x0a);
          if (nlIdx >= 0) {
            firstNewlineOffset = pos + nlIdx + 1;
            break;
          }
          pos += n;
        }
        if (firstNewlineOffset > 0 && firstNewlineOffset < st.size) {
          const followLen = Math.min(256 * 1024, st.size - firstNewlineOffset);
          const followBuf = Buffer.allocUnsafe(followLen);
          const followRead = fs.readSync(fd, followBuf, 0, followLen, firstNewlineOffset);
          consumedHeadEnd = firstNewlineOffset + followRead;
          headLines = followBuf.subarray(0, followRead).toString("utf8").split(/\r?\n/);
          if (headLines.length > 1) headLines.pop();
        }
      }

      const tailOffset = Math.max(consumedHeadEnd, st.size - tailBytes);
      const actualTailLen = Math.max(0, st.size - tailOffset);
      if (actualTailLen === 0) {
        return headLines.filter(Boolean);
      }

      const tailBuf = Buffer.allocUnsafe(actualTailLen);
      const tailRead = fs.readSync(fd, tailBuf, 0, actualTailLen, tailOffset);
      const tailLines = tailBuf.subarray(0, tailRead).toString("utf8").split(/\r?\n/);
      if (tailOffset > 0 && tailLines.length > 0) {
        // First line of tail slice starts mid-record
        tailLines.shift();
      }

      const combined: string[] = [];
      for (const l of headLines) {
        if (l) combined.push(l);
      }
      for (const l of tailLines) {
        if (l) combined.push(l);
      }
      return combined;
    } finally {
      try {
        fs.closeSync(fd);
      } catch {
        // ignore
      }
    }
  }

  private putSummaryCache(
    filePath: string,
    mtimeMs: number,
    size: number,
    summary: AgentSessionSummary | null
  ): void {
    if (this.fileSummaryCache.size >= 256 && !this.fileSummaryCache.has(filePath)) {
      const oldestKey = this.fileSummaryCache.keys().next().value;
      if (oldestKey !== undefined) {
        this.fileSummaryCache.delete(oldestKey);
      }
    }
    this.fileSummaryCache.set(filePath, { mtimeMs, size, summary });
  }

  private loadCodexIndexMetadata(): {
    projectNamesByPath: Map<string, string>;
    threadsById: Map<
      string,
      {
        name: string;
        title: string;
        cwd: string;
        preview: string;
        updatedAtMs: number;
        archived: boolean;
        agentRole: string;
      }
    >;
  } {
    const projectNamesByPath = new Map<string, string>();
    const threadsById = new Map<
      string,
      {
        name: string;
        title: string;
        cwd: string;
        preview: string;
        updatedAtMs: number;
        archived: boolean;
        agentRole: string;
      }
    >();
    const codexRoot = path.dirname(this.codexSessionsDir);
    if (!fs.existsSync(codexRoot)) {
      return { projectNamesByPath, threadsById };
    }

    // 1. Read `~/.codex/state_*.sqlite` (authoritative Codex Desktop projects + thread names)
    try {
      const stateDbs = fs
        .readdirSync(codexRoot)
        .filter((n) => /^state_\d+\.sqlite$/i.test(n))
        .sort((a, b) => {
          const na = Number(a.match(/\d+/)?.[0] || 0);
          const nb = Number(b.match(/\d+/)?.[0] || 0);
          return nb - na;
        });
      if (stateDbs.length > 0) {
        const dbPath = path.join(codexRoot, stateDbs[0]);
        const db = new Database(`file:${dbPath}?immutable=1`, { readonly: true });
        try {
          const pRows = db
            .query(
              `SELECT p.name AS name, r.path AS rootPath
               FROM projects p
               JOIN project_roots r ON p.id = r.project_id`
            )
            .all() as Array<{ name?: string; rootPath?: string }>;
          for (const r of pRows) {
            const pPath = String(r.rootPath || "").trim().replace(/\/+$/, "");
            const pName = String(r.name || "").trim();
            if (pPath && pName) {
              projectNamesByPath.set(pPath, pName);
            }
          }
        } catch {
          // projects table optional on older CLI versions
        }

        try {
          const tRows = db
            .query(
              `SELECT id, COALESCE(name, '') AS name, COALESCE(title, '') AS title,
                      COALESCE(cwd, '') AS cwd, COALESCE(preview, '') AS preview,
                      updated_at, archived, COALESCE(agent_role, '') AS agent_role
               FROM threads
               ORDER BY updated_at DESC
               LIMIT 160`
            )
            .all() as Array<Record<string, any>>;
          for (const r of tRows) {
            const id = String(r.id || "").trim();
            if (!id) continue;
            const rawUpdated = Number(r.updated_at || 0);
            const updatedAtMs = rawUpdated > 1e12 ? rawUpdated : rawUpdated * 1000;
            threadsById.set(id, {
              name: String(r.name || "").trim(),
              title: String(r.title || "").trim(),
              cwd: String(r.cwd || "").trim(),
              preview: String(r.preview || "").trim(),
              updatedAtMs,
              archived: Boolean(r.archived),
              agentRole: String(r.agent_role || "").trim(),
            });
          }
        } catch {
          // threads table optional
        } finally {
          db.close();
        }
      }
    } catch {
      // ignore sqlite open issues
    }

    // 2. Also merge `~/.codex/session_index.jsonl` short thread_name entries
    const sessionIdxPath = path.join(codexRoot, "session_index.jsonl");
    if (fs.existsSync(sessionIdxPath)) {
      try {
        const lines = this.readJsonlBoundedLines(sessionIdxPath, 128 * 1024, 256 * 1024);
        for (const line of lines) {
          try {
            const obj = JSON.parse(line);
            const id = String(obj?.id || "").trim();
            const threadName = String(obj?.thread_name || "").trim();
            if (!id || !threadName) continue;
            const prev = threadsById.get(id);
            if (prev) {
              if (!prev.name) prev.name = threadName;
            } else {
              threadsById.set(id, {
                name: threadName,
                title: threadName,
                cwd: "",
                preview: "",
                updatedAtMs: obj?.updated_at ? Date.parse(String(obj.updated_at)) || 0 : 0,
                archived: false,
                agentRole: "",
              });
            }
          } catch {
            // ignore line
          }
        }
      } catch {
        // ignore
      }
    }

    return { projectNamesByPath, threadsById };
  }

  private loadAgySummaryMetadata(): Map<
    string,
    {
      title: string;
      preview: string;
      workspacePath: string;
      status: "running" | "idle";
      nestingDepth: number;
      killed: boolean;
      updatedAtMs: number;
    }
  > {
    const out = new Map<
      string,
      {
        title: string;
        preview: string;
        workspacePath: string;
        status: "running" | "idle";
        nestingDepth: number;
        killed: boolean;
        updatedAtMs: number;
      }
    >();

    const dbCandidates = [path.join(path.dirname(this.agyBrainDir), "conversation_summaries.db")];
    const isDefaultHome =
      process.env.OMS_TEST_ISOLATION !== "1" &&
      path.resolve(this.agyBrainDir) ===
        path.resolve(path.join(os.homedir(), ".gemini", "antigravity", "brain"));
    if (isDefaultHome) {
      dbCandidates.push(path.join(os.homedir(), ".gemini", "antigravity-cli", "conversation_summaries.db"));
    }

    for (const dbPath of dbCandidates) {
      if (!fs.existsSync(dbPath)) continue;
      try {
        const db = new Database(`file:${dbPath}?immutable=1`, { readonly: true });
        try {
          const rows = db
            .query(
              `SELECT conversation_id, title, preview, workspace_uris, status,
                      nesting_depth, killed, last_modified_time
               FROM conversation_summaries
               ORDER BY last_modified_time DESC
               LIMIT 140`
            )
            .all() as Array<Record<string, any>>;
          for (const r of rows) {
            const convId = String(r.conversation_id || "").trim();
            if (!convId || out.has(convId)) continue;
            let wsPath = "";
            try {
              const uris = JSON.parse(String(r.workspace_uris || "[]"));
              if (Array.isArray(uris) && typeof uris[0] === "string" && uris[0].startsWith("file://")) {
                wsPath = fileURLToPath(uris[0]).replace(/\/+$/, "");
              }
            } catch {
              // ignore uri parse
            }
            const modStr = String(r.last_modified_time || "").trim();
            const parsedMs = modStr ? Date.parse(modStr.replace(" ", "T")) || 0 : 0;
            const rawStatus = String(r.status || "");
            out.set(convId, {
              title: String(r.title || "").trim(),
              preview: String(r.preview || "").trim(),
              workspacePath: wsPath,
              status: rawStatus.includes("RUNNING") && Date.now() - parsedMs < 180_000 ? "running" : "idle",
              nestingDepth: Number(r.nesting_depth || 0),
              killed: Boolean(r.killed),
              updatedAtMs: parsedMs,
            });
          }
        } finally {
          db.close();
        }
      } catch {
        // ignore sqlite open issues
      }
    }

    return out;
  }

  private scanCodexSessions(
    maxCount: number,
    codexMeta = this.loadCodexIndexMetadata()
  ): AgentSessionSummary[] {
    if (!fs.existsSync(this.codexSessionsDir)) return [];
    const files: Array<{ filePath: string; mtimeMs: number; size: number }> = [];
    const walk = (dir: string, depth: number) => {
      if (depth > 4) return;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full, depth + 1);
        else if (e.isFile() && e.name.endsWith(".jsonl")) {
          try {
            const st = fs.statSync(full);
            files.push({ filePath: full, mtimeMs: st.mtimeMs, size: st.size });
          } catch {
            // ignore
          }
        }
      }
    };
    walk(this.codexSessionsDir, 0);
    files.sort((a, b) => b.mtimeMs - a.mtimeMs);

    const out: AgentSessionSummary[] = [];
    for (const f of files.slice(0, maxCount)) {
      const cached = this.fileSummaryCache.get(f.filePath);
      let baseSummary: AgentSessionSummary | null = null;
      if (cached && cached.mtimeMs === f.mtimeMs && cached.size === f.size) {
        baseSummary = cached.summary
          ? {
              ...cached.summary,
              status: Date.now() - f.mtimeMs < 20_000 ? "running" : "idle",
            }
          : null;
      } else {
        const parsed = this.parseCodexRolloutFile(f.filePath, f.mtimeMs, false);
        this.putSummaryCache(f.filePath, f.mtimeMs, f.size, parsed ? parsed.session : null);
        baseSummary = parsed ? parsed.session : null;
      }
      if (!baseSummary) continue;

      const meta = codexMeta.threadsById.get(baseSummary.sessionId);
      if (
        meta?.archived ||
        meta?.agentRole === "guardian" ||
        meta?.name?.startsWith("Guardian review") ||
        meta?.title?.startsWith("Guardian review")
      ) {
        continue;
      }
      const enriched = this.enrichCodexSessionWithMeta(baseSummary, meta, codexMeta.projectNamesByPath);
      out.push(enriched);
    }
    return out;
  }

  private enrichCodexSessionWithMeta(
    session: AgentSessionSummary,
    meta:
      | {
          name: string;
          title: string;
          cwd: string;
          preview: string;
          updatedAtMs: number;
          archived: boolean;
          agentRole: string;
        }
      | undefined,
    projectNamesByPath: Map<string, string>
  ): AgentSessionSummary {
    const ws = (meta?.cwd || session.workspacePath || this.defaultWorkspacePath).trim().replace(/\/+$/, "");
    const projName = projectNamesByPath.get(ws) || (ws ? path.basename(ws) : "工作区");
    const cleanMetaTitle = meta?.title ? this.cleanBoilerplatePrompt(meta.title) : "";
    const preferredTitle =
      meta?.name?.trim() ||
      this.compactHumanTitle(cleanMetaTitle) ||
      this.compactHumanTitle(session.title) ||
      `Codex · ${projName}`;

    return {
      ...session,
      title: preferredTitle,
      workspacePath: ws || session.workspacePath,
      projectDisplayName: projName,
      updatedAtMs: Math.max(session.updatedAtMs, meta?.updatedAtMs || 0),
    };
  }

  private compactHumanTitle(raw: string): string {
    const cleaned = this.cleanBoilerplatePrompt(raw)
      .replace(/^\[[^\]]+\]\s*/, "")
      .replace(/\s+/g, " ")
      .trim();
    if (!cleaned || cleaned.startsWith("Codex · ") || cleaned.startsWith("Antigravity · ")) {
      return "";
    }
    return cleaned.length > 54 ? `${cleaned.slice(0, 54)}…` : cleaned;
  }

  private readCodexSessionDetail(sessionId: string): AgentSessionDetailResult | null {
    if (!fs.existsSync(this.codexSessionsDir)) return null;
    let targetFile: { filePath: string; mtimeMs: number } | null = null;
    const walk = (dir: string, depth: number) => {
      if (depth > 4 || targetFile) return;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full, depth + 1);
        else if (e.isFile() && e.name.includes(sessionId) && e.name.endsWith(".jsonl")) {
          try {
            targetFile = { filePath: full, mtimeMs: fs.statSync(full).mtimeMs };
            return;
          } catch {
            // ignore
          }
        }
      }
    };
    walk(this.codexSessionsDir, 0);
    if (!targetFile) return null;
    const detail = this.parseCodexRolloutFile(
      (targetFile as { filePath: string; mtimeMs: number }).filePath,
      (targetFile as { filePath: string; mtimeMs: number }).mtimeMs,
      true
    );
    if (!detail) return null;
    const codexMeta = this.loadCodexIndexMetadata();
    detail.session = this.enrichCodexSessionWithMeta(
      detail.session,
      codexMeta.threadsById.get(detail.session.sessionId),
      codexMeta.projectNamesByPath
    );
    return detail;
  }

  private parseCodexRolloutFile(
    filePath: string,
    mtimeMs: number,
    includeSteps: boolean
  ): AgentSessionDetailResult | null {
    try {
      const lines = this.readJsonlBoundedLines(
        filePath,
        includeSteps ? 512 * 1024 : 384 * 1024,
        includeSteps ? 1536 * 1024 : 384 * 1024
      );
      const fileMatch = path.basename(filePath).match(/([0-9a-f]{8}-[0-9a-f-]{27})/i);
      let sessionId = fileMatch ? fileMatch[1] : path.basename(filePath, ".jsonl");
      let workspacePath = this.defaultWorkspacePath;
      let title = "";
      let lastSummary = "";
      const steps: AgentStepItem[] = [];

      for (const line of lines) {
        let obj: any;
        try {
          obj = JSON.parse(line);
        } catch {
          continue;
        }
        const ts = obj.timestamp ? Date.parse(obj.timestamp) || mtimeMs : mtimeMs;
        if (obj.type === "session_meta" && obj.payload) {
          if (obj.payload.id) sessionId = String(obj.payload.id);
          if (obj.payload.cwd) workspacePath = String(obj.payload.cwd);
        } else if (obj.type === "turn_context" && obj.payload?.cwd && workspacePath === this.defaultWorkspacePath) {
          workspacePath = String(obj.payload.cwd);
        } else if (obj.type === "event_msg" && obj.payload) {
          const pt = String(obj.payload.type || "");
          if (pt === "user_message" && obj.payload.message) {
            const msg = this.cleanBoilerplatePrompt(String(obj.payload.message));
            if (msg && (!title || title.startsWith("# AGENTS") || title.startsWith("<"))) {
              title = msg.slice(0, 64);
            }
            if (includeSteps && msg) {
              steps.push({ stepIndex: steps.length + 1, kind: "user", title: "用户指令", detail: msg.slice(0, 600), timestampMs: ts });
            }
          } else if (pt === "agent_message" && obj.payload.message) {
            const msg = String(obj.payload.message).trim();
            lastSummary = msg.replace(/\s+/g, " ").slice(0, 140);
            if (includeSteps) {
              steps.push({ stepIndex: steps.length + 1, kind: "assistant", title: "Codex", detail: msg.slice(0, 1200), timestampMs: ts });
            }
          } else if (pt === "agent_reasoning" && obj.payload.text && includeSteps) {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "thinking",
              title: "推理摘要",
              detail: String(obj.payload.text).trim().slice(0, 400),
              timestampMs: ts,
            });
          }
        } else if (obj.type === "response_item" && obj.payload) {
          const p = obj.payload;
          if (p.type === "function_call" && includeSteps) {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "tool",
              title: `${p.name || "tool"}`,
              detail: String(p.arguments || "").slice(0, 300),
              timestampMs: ts,
            });
          } else if (p.type === "message" && p.role === "user" && Array.isArray(p.content) && !title) {
            for (const c of p.content) {
              if (typeof c?.text !== "string") continue;
              const cleaned = this.cleanBoilerplatePrompt(c.text);
              if (cleaned) {
                title = cleaned.slice(0, 64);
                break;
              }
            }
          } else if (p.type === "message" && p.role === "assistant" && Array.isArray(p.content)) {
            const textPart = p.content.find((c: any) => typeof c?.text === "string");
            if (textPart?.text) lastSummary = String(textPart.text).replace(/\s+/g, " ").trim().slice(0, 140);
          }
        }
      }

      // Skip internal Codex Guardian / safety-classifier ephemeral sub-sessions
      if (
        title.startsWith("The following is the Codex agent history") ||
        title.startsWith("Guardian review:") ||
        (lastSummary.startsWith('{"risk_level":') && lastSummary.includes('"outcome":'))
      ) {
        return null;
      }

      const recentlyModified = Date.now() - mtimeMs < 20_000;
      const summary: AgentSessionSummary = {
        sessionId,
        backend: "codex",
        origin: "desktop",
        title: title || `Codex · ${path.basename(workspacePath)}`,
        workspacePath,
        status: recentlyModified ? "running" : "idle",
        lastSummary: lastSummary || "可点击查看执行轨迹或直接追问接续",
        updatedAtMs: Math.floor(mtimeMs),
        canContinue: true,
      };
      return { session: summary, steps: steps.slice(-80) };
    } catch {
      return null;
    }
  }

  private scanAgySessions(maxCount: number): AgentSessionSummary[] {
    const agyMetaMap = this.loadAgySummaryMetadata();
    if (!fs.existsSync(this.agyBrainDir) && agyMetaMap.size === 0) return [];
    let entries: fs.Dirent[] = [];
    try {
      if (fs.existsSync(this.agyBrainDir)) {
        entries = fs.readdirSync(this.agyBrainDir, { withFileTypes: true });
      }
    } catch {
      entries = [];
    }
    const candidates: Array<{ convId: string; transcriptPath: string; mtimeMs: number; size: number }> = [];
    for (const e of entries) {
      if (!e.isDirectory() || e.name.startsWith(".")) continue;
      const meta = agyMetaMap.get(e.name);
      if (meta && (meta.killed || meta.nestingDepth > 0)) {
        continue;
      }
      const tp = path.join(this.agyBrainDir, e.name, ".system_generated", "logs", "transcript.jsonl");
      if (fs.existsSync(tp)) {
        try {
          const st = fs.statSync(tp);
          candidates.push({ convId: e.name, transcriptPath: tp, mtimeMs: st.mtimeMs, size: st.size });
        } catch {
          // ignore
        }
      }
    }
    candidates.sort((a, b) => b.mtimeMs - a.mtimeMs);
    const out: AgentSessionSummary[] = [];
    for (const c of candidates.slice(0, maxCount)) {
      const cached = this.fileSummaryCache.get(c.transcriptPath);
      let baseSummary: AgentSessionSummary | null = null;
      if (cached && cached.mtimeMs === c.mtimeMs && cached.size === c.size) {
        baseSummary = cached.summary
          ? {
              ...cached.summary,
              status: Date.now() - c.mtimeMs < 20_000 ? "running" : "idle",
            }
          : null;
      } else {
        const parsed = this.parseAgyTranscript(c.convId, c.transcriptPath, c.mtimeMs, false);
        this.putSummaryCache(c.transcriptPath, c.mtimeMs, c.size, parsed ? parsed.session : null);
        baseSummary = parsed ? parsed.session : null;
      }
      if (!baseSummary) continue;

      const meta = agyMetaMap.get(c.convId);
      out.push(this.enrichAgySessionWithMeta(baseSummary, meta));
    }
    return out;
  }

  private enrichAgySessionWithMeta(
    session: AgentSessionSummary,
    meta:
      | {
          title: string;
          preview: string;
          workspacePath: string;
          status: "running" | "idle";
          nestingDepth: number;
          killed: boolean;
          updatedAtMs: number;
        }
      | undefined
  ): AgentSessionSummary {
    const validDiskWs =
      session.workspacePath && path.isAbsolute(session.workspacePath) && !session.workspacePath.includes("\\")
        ? session.workspacePath
        : "";
    const ws = (meta?.workspacePath || validDiskWs || this.defaultWorkspacePath).trim().replace(/\/+$/, "");
    const preferredTitle =
      meta?.title?.trim() ||
      this.compactHumanTitle(session.title) ||
      `Antigravity · ${ws ? path.basename(ws) : session.sessionId.slice(0, 8)}`;

    return {
      ...session,
      title: preferredTitle,
      workspacePath: ws,
      projectDisplayName: ws ? path.basename(ws) || ws : "工作区",
      status: session.status === "running" || meta?.status === "running" ? "running" : session.status,
      updatedAtMs: Math.max(session.updatedAtMs, meta?.updatedAtMs || 0),
    };
  }

  private readAgySessionDetail(conversationId: string): AgentSessionDetailResult | null {
    const tp = path.join(this.agyBrainDir, conversationId, ".system_generated", "logs", "transcript.jsonl");
    if (!fs.existsSync(tp)) return null;
    try {
      const mtimeMs = fs.statSync(tp).mtimeMs;
      const detail = this.parseAgyTranscript(conversationId, tp, mtimeMs, true);
      if (!detail) return null;
      const agyMetaMap = this.loadAgySummaryMetadata();
      detail.session = this.enrichAgySessionWithMeta(detail.session, agyMetaMap.get(conversationId));
      return detail;
    } catch {
      return null;
    }
  }

  private parseAgyTranscript(
    convId: string,
    transcriptPath: string,
    mtimeMs: number,
    includeSteps: boolean
  ): AgentSessionDetailResult | null {
    try {
      const lines = this.readJsonlBoundedLines(
        transcriptPath,
        includeSteps ? 512 * 1024 : 384 * 1024,
        includeSteps ? 1536 * 1024 : 384 * 1024
      );
      let title = "";
      let lastSummary = "";
      let workspacePath = this.defaultWorkspacePath;
      const steps: AgentStepItem[] = [];

      for (const line of lines) {
        let obj: any;
        try {
          obj = JSON.parse(line);
        } catch {
          continue;
        }
        const ts = obj.created_at ? Date.parse(obj.created_at) || mtimeMs : mtimeMs;
        const stepType = String(obj.type || "");
        const content = typeof obj.content === "string" ? obj.content : "";

        // Strictly anchor inside `<user_information>` block and validate absolute path so source-code tool outputs never match!
        if (!workspacePath || workspacePath === this.defaultWorkspacePath) {
          const infoMatch = content.match(
            /<user_information>[\s\S]*?Command Working Directory:\s*([^\r\n<]+)[\s\S]*?<\/user_information>/
          );
          const candidate = infoMatch?.[1]?.trim() || "";
          if (candidate.startsWith("/") && !candidate.includes("\\")) {
            workspacePath = candidate.replace(/\/+$/, "");
          }
        }

        if (stepType === "USER_INPUT") {
          const reqMatch = content.match(/<USER_REQUEST>\s*([\s\S]*?)\s*<\/USER_REQUEST>/);
          const cleanUser = (reqMatch ? reqMatch[1] : content).replace(/\s+/g, " ").trim();
          if (cleanUser && !cleanUser.startsWith("<CONTEXT_SUMMARY>")) {
            if (!title) title = cleanUser.slice(0, 64);
            if (includeSteps) {
              steps.push({
                stepIndex: steps.length + 1,
                kind: "user",
                title: "用户请求",
                detail: cleanUser.slice(0, 600),
                timestampMs: ts,
              });
            }
          }
        } else if (stepType === "PLANNER_RESPONSE") {
          if (includeSteps && typeof obj.thinking === "string" && obj.thinking.trim()) {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "thinking",
              title: "Antigravity 推演",
              detail: obj.thinking.trim().slice(0, 400),
              timestampMs: ts,
            });
          }
          if (Array.isArray(obj.tool_calls) && obj.tool_calls.length > 0) {
            const toolNames = obj.tool_calls
              .map((tc: any) => tc?.name || tc?.tool_name || "tool")
              .join(", ");
            lastSummary = `调用工具: ${toolNames}`;
            if (includeSteps) {
              steps.push({
                stepIndex: steps.length + 1,
                kind: "tool",
                title: "调用工具",
                detail: toolNames,
                timestampMs: ts,
              });
            }
          }
          if (content.trim()) {
            lastSummary = content.replace(/\s+/g, " ").trim().slice(0, 150);
            if (includeSteps) {
              steps.push({
                stepIndex: steps.length + 1,
                kind: "assistant",
                title: "Antigravity 回复",
                detail: content.trim().slice(0, 1200),
                timestampMs: ts,
              });
            }
          }
        }
      }

      if (
        title.startsWith("The following is the Codex agent history") ||
        (lastSummary.startsWith('{"risk_level":') && lastSummary.includes('"outcome":'))
      ) {
        return null;
      }

      const recentlyModified = Date.now() - mtimeMs < 20_000;
      return {
        session: {
          sessionId: convId,
          backend: "agy",
          origin: "desktop",
          title: title || `Antigravity · ${convId.slice(0, 8)}`,
          workspacePath,
          status: recentlyModified ? "running" : "idle",
          lastSummary: lastSummary || "可点击查看轨迹或发送新指令继续执行",
          updatedAtMs: Math.floor(mtimeMs),
          canContinue: true,
        },
        steps: steps.slice(-80),
      };
    } catch {
      return null;
    }
  }

  // ===========================================================================
  // Quota Remaining & Reset Timestamp Collectors (Codex + Antigravity)
  // ===========================================================================

  private collectCodexQuota(): AgentBackendQuotaStatus {
    const emptyStatus: AgentBackendQuotaStatus = {
      backend: "codex",
      available: false,
      planLabel: "Codex",
      windows: [],
      statusNote: "暂未在本地会话中采样到配额窗口",
      capturedAtMs: Date.now(),
    };
    if (!fs.existsSync(this.codexSessionsDir)) return emptyStatus;

    const files: Array<{ filePath: string; mtimeMs: number; size: number }> = [];
    const walk = (dir: string, depth: number) => {
      if (depth > 4) return;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full, depth + 1);
        else if (e.isFile() && e.name.startsWith("rollout-") && e.name.endsWith(".jsonl")) {
          try {
            const st = fs.statSync(full);
            if (st.size > 0) files.push({ filePath: full, mtimeMs: st.mtimeMs, size: st.size });
          } catch {
            // ignore
          }
        }
      }
    };
    walk(this.codexSessionsDir, 0);
    if (files.length === 0) return emptyStatus;

    files.sort((a, b) => b.mtimeMs - a.mtimeMs);
    const candidates = files.slice(0, 18);
    const fingerprint = candidates.map((c) => `${c.filePath}:${Math.floor(c.mtimeMs)}:${c.size}`).join("|");
    if (this.cachedCodexQuota && this.cachedCodexQuota.fingerprint === fingerprint) {
      return this.cachedCodexQuota.status;
    }

    let bestRateLimits: Record<string, any> | null = null;
    let bestAtMs = 0;
    let latestModel = "";

    for (const c of candidates) {
      let fd = -1;
      try {
        fd = fs.openSync(c.filePath, "r");
        const readLen = Math.min(c.size, 256 * 1024);
        const offset = c.size - readLen;
        const buf = Buffer.allocUnsafe(readLen);
        const n = fs.readSync(fd, buf, 0, readLen, offset);
        const lines = buf.subarray(0, n).toString("utf8").split(/\r?\n/);
        if (offset > 0 && lines.length > 0) lines.shift();

        for (let i = lines.length - 1; i >= 0; i--) {
          const line = lines[i];
          if (!line) continue;
          if (!latestModel && line.includes('"turn_context"')) {
            try {
              const tc = JSON.parse(line);
              if (tc?.type === "turn_context" && tc?.payload?.model) {
                latestModel = String(tc.payload.model);
              }
            } catch {}
          }
          if (!line.includes('"token_count"') || !line.includes('"rate_limits"')) continue;
          try {
            const obj = JSON.parse(line);
            if (obj?.type !== "event_msg" || obj?.payload?.type !== "token_count" || !obj?.payload?.rate_limits) {
              continue;
            }
            const evMs = obj.timestamp ? Date.parse(String(obj.timestamp)) || c.mtimeMs : c.mtimeMs;
            if (evMs >= bestAtMs) {
              bestAtMs = evMs;
              bestRateLimits = obj.payload.rate_limits as Record<string, any>;
            }
            break;
          } catch {
            // ignore malformed line
          }
        }
      } catch {
        // ignore file error
      } finally {
        if (fd >= 0) {
          try {
            fs.closeSync(fd);
          } catch {}
        }
      }
    }

    if (!bestRateLimits) {
      return emptyStatus;
    }

    const formatCodexWindowLabel = (mins: number, fallbackKey: string): string => {
      if (mins === 10080) return "7 天周期配额";
      if (mins === 300) return "5 小时窗口";
      if (mins === 1440) return "24 小时配额";
      if (mins > 0 && mins % 1440 === 0) return `${mins / 1440} 天周期配额`;
      if (mins > 0 && mins % 60 === 0) return `${mins / 60} 小时窗口`;
      return fallbackKey === "primary" ? "主配额窗口" : "次级窗口";
    };

    const windows: AgentQuotaWindowItem[] = [];
    for (const k of ["primary", "secondary"] as const) {
      const w = bestRateLimits[k];
      if (!w || typeof w !== "object" || typeof w.used_percent !== "number") continue;
      const used = Math.max(0, Math.min(100, Math.round(Number(w.used_percent))));
      const rem = Math.max(0, Math.min(100, 100 - used));
      const mins = typeof w.window_minutes === "number" ? Number(w.window_minutes) : undefined;
      const rawReset = Number(w.resets_at || 0);
      const resetsAtMs = rawReset > 1e12 ? rawReset : rawReset > 0 ? rawReset * 1000 : undefined;
      windows.push({
        key: k,
        label: formatCodexWindowLabel(mins || 0, k),
        usedPercent: used,
        remainingPercent: rem,
        windowMinutes: mins,
        resetsAtMs,
      });
    }

    const rawPlan = String(bestRateLimits.plan_type || "").toLowerCase();
    const planLabel =
      rawPlan === "prolite"
        ? "Pro Lite"
        : rawPlan === "pro"
          ? "ChatGPT Pro"
          : rawPlan === "plus"
            ? "ChatGPT Plus"
            : rawPlan === "team"
              ? "Team"
              : rawPlan === "enterprise"
                ? "Enterprise"
                : rawPlan
                  ? rawPlan.toUpperCase()
                  : "Codex";

    const credits = bestRateLimits.credits;
    let creditsSummary: string | undefined;
    if (credits?.unlimited === true) {
      creditsSummary = "扩展积分：无上限";
    } else if (credits?.has_credits === true && credits?.balance) {
      creditsSummary = `扩展积分余额：${credits.balance}`;
    }

    const status: AgentBackendQuotaStatus = {
      backend: "codex",
      available: windows.length > 0,
      planLabel,
      activeModel: latestModel || undefined,
      windows,
      creditsSummary,
      statusNote: bestRateLimits.rate_limit_reached_type
        ? `已触发限流 (${bestRateLimits.rate_limit_reached_type})`
        : "实时同步自 Codex 本地会话遥测",
      capturedAtMs: Math.floor(bestAtMs || Date.now()),
    };
    this.cachedCodexQuota = { fingerprint, status };
    return status;
  }

  private collectAgyQuota(): AgentBackendQuotaStatus {
    if (this.cachedAgyQuota && Date.now() - this.cachedAgyQuota.fetchedAtMs < 15_000) {
      return this.cachedAgyQuota.status;
    }

    const isDefaultHome =
      process.env.OMS_TEST_ISOLATION !== "1" &&
      path.resolve(this.agyBrainDir) ===
        path.resolve(path.join(os.homedir(), ".gemini", "antigravity", "brain"));

    let activeModel = "";
    const freshIdeWindows: AgentQuotaWindowItem[] = [];

    // 1. Check Antigravity IDE `state.vscdb` (`userStatus`) if a fresh non-expired quota window is present
    if (isDefaultHome) {
      const vscdbPath = path.join(
        os.homedir(),
        "Library",
        "Application Support",
        "Antigravity",
        "User",
        "globalStorage",
        "state.vscdb"
      );
      if (fs.existsSync(vscdbPath)) {
        try {
          const parsedWindows = this.tryDecodeFreshAgyVscdbQuota(vscdbPath);
          freshIdeWindows.push(...parsedWindows);
        } catch {
          // ignore
        }
      }

      // Check CLI log / settings for active model label (sanctioned read-only local log, no tokens read)
      const cliLogPath = path.join(os.homedir(), ".gemini", "antigravity-cli", "cli.log");
      if (fs.existsSync(cliLogPath)) {
        try {
          const tailLines = this.readJsonlBoundedLines(cliLogPath, 4096, 65536);
          for (let i = tailLines.length - 1; i >= 0; i--) {
            const m = tailLines[i].match(/selected model override to backend:\s*label="([^"]+)"/);
            if (m?.[1]) {
              activeModel = m[1];
              break;
            }
          }
        } catch {}
      }
      if (!activeModel) {
        const cliSettingsPath = path.join(os.homedir(), ".gemini", "antigravity-cli", "settings.json");
        if (fs.existsSync(cliSettingsPath)) {
          try {
            const s = JSON.parse(fs.readFileSync(cliSettingsPath, "utf8"));
            if (typeof s?.model === "string") activeModel = s.model;
          } catch {}
        }
      }
    }

    // 2. Aggregate live today / 7-day step & token telemetry from `transcript.jsonl`
    let todaySteps = 0;
    let weekConvos = 0;
    let totalInputTok = 0;
    let totalCacheTok = 0;
    let totalOutputTok = 0;
    let latestStepAtMs = 0;
    let quotaHitError = "";
    const now = Date.now();
    const startOfToday = new Date();
    startOfToday.setHours(0, 0, 0, 0);
    const todayFloorMs = startOfToday.getTime();

    if (fs.existsSync(this.agyBrainDir)) {
      try {
        const entries = fs.readdirSync(this.agyBrainDir, { withFileTypes: true });
        const recentTranscripts: Array<{ tp: string; mtimeMs: number }> = [];
        for (const e of entries) {
          if (!e.isDirectory() || e.name.startsWith(".")) continue;
          const tp = path.join(this.agyBrainDir, e.name, ".system_generated", "logs", "transcript.jsonl");
          if (!fs.existsSync(tp)) continue;
          try {
            const st = fs.statSync(tp);
            if (now - st.mtimeMs <= 7 * 86400_000) {
              weekConvos += 1;
              recentTranscripts.push({ tp, mtimeMs: st.mtimeMs });
            }
          } catch {}
        }
        recentTranscripts.sort((a, b) => b.mtimeMs - a.mtimeMs);
        for (const item of recentTranscripts.slice(0, 12)) {
          if (item.mtimeMs > latestStepAtMs) latestStepAtMs = item.mtimeMs;
          const lines = this.readJsonlBoundedLines(item.tp, 16 * 1024, 192 * 1024);
          for (const line of lines) {
            try {
              const obj = JSON.parse(line);
              const ts = obj?.created_at ? Date.parse(String(obj.created_at)) || 0 : 0;
              if (obj?.type === "PLANNER_RESPONSE") {
                if (!activeModel && typeof obj.model === "string" && obj.model) {
                  activeModel = obj.model;
                }
                if (ts >= todayFloorMs) {
                  todaySteps += 1;
                  totalInputTok += Number(obj.input_tokens || 0);
                  totalCacheTok += Number(obj.cache_read_tokens || 0);
                  totalOutputTok += Number(obj.output_tokens || 0);
                }
              }
              if (obj?.status === "ERROR" && ts >= now - 6 * 3600_000) {
                const c = String(obj?.content || "");
                if (/RESOURCE_EXHAUSTED|quota|rate.?limit/i.test(c)) {
                  quotaHitError = "近期曾触发速率/配额上限，请稍候冷却";
                }
              }
            } catch {}
          }
        }
      } catch {
        // ignore
      }
    }

    const totalPrompt = totalInputTok + totalCacheTok;
    const cacheHitPct = totalPrompt > 0 ? Math.round((totalCacheTok / totalPrompt) * 100) : 0;
    const outTokLabel =
      totalOutputTok >= 1000 ? `${(totalOutputTok / 1000).toFixed(1)}k` : `${totalOutputTok}`;
    const activitySummary =
      todaySteps > 0
        ? `今日 ${todaySteps} 轮推理 · 输出 ${outTokLabel} tok · 缓存命中 ${cacheHitPct}%`
        : weekConvos > 0
          ? `近 7 日活跃 ${weekConvos} 个会话 · 今日尚未消耗`
          : "暂无近期推理消耗";

    const status: AgentBackendQuotaStatus = {
      backend: "agy",
      available: true,
      planLabel: "Google AI · Antigravity",
      activeModel: activeModel || "Gemini",
      windows: freshIdeWindows,
      creditsSummary: activitySummary,
      statusNote:
        quotaHitError ||
        (freshIdeWindows.length > 0
          ? "实时同步自 Antigravity 本地配额快照"
          : "状态正常 · 滚动动态配额（未触发限流）"),
      capturedAtMs: Math.floor(latestStepAtMs || now),
    };
    this.cachedAgyQuota = { fetchedAtMs: now, status };
    return status;
  }

  private tryDecodeFreshAgyVscdbQuota(vscdbPath: string): AgentQuotaWindowItem[] {
    const readVarint = (buf: Buffer, offset: number): [bigint, number] => {
      let res = 0n;
      let shift = 0n;
      let pos = offset;
      while (pos < buf.length) {
        const b = buf[pos++];
        res |= BigInt(b & 0x7f) << shift;
        if ((b & 0x80) === 0) break;
        shift += 7n;
      }
      return [res, pos];
    };

    const parseFields = (buf: Buffer): Array<{ field: number; wire: number; val: any }> => {
      const out: Array<{ field: number; wire: number; val: any }> = [];
      let pos = 0;
      while (pos < buf.length) {
        const [tag, p1] = readVarint(buf, pos);
        pos = p1;
        const field = Number(tag >> 3n);
        const wire = Number(tag & 7n);
        if (field === 0) break;
        if (wire === 0) {
          const [v, p2] = readVarint(buf, pos);
          pos = p2;
          out.push({ field, wire, val: v });
        } else if (wire === 1) {
          if (pos + 8 > buf.length) break;
          out.push({ field, wire, val: buf.subarray(pos, pos + 8) });
          pos += 8;
        } else if (wire === 2) {
          const [len, p2] = readVarint(buf, pos);
          const n = Number(len);
          if (p2 + n > buf.length) break;
          out.push({ field, wire, val: buf.subarray(p2, p2 + n) });
          pos = p2 + n;
        } else if (wire === 5) {
          if (pos + 4 > buf.length) break;
          out.push({ field, wire, val: buf.readFloatLE(pos) });
          pos += 4;
        } else {
          break;
        }
      }
      return out;
    };

    const db = new Database(`file:${vscdbPath}?immutable=1`, { readonly: true });
    let rawVal = "";
    try {
      const row = db
        .query("SELECT value FROM ItemTable WHERE key = ?")
        .get("antigravityUnifiedStateSync.userStatus") as { value?: string } | null;
      rawVal = String(row?.value || "");
    } finally {
      db.close();
    }
    if (!rawVal) return [];

    const nowMs = Date.now();
    const windows: AgentQuotaWindowItem[] = [];
    for (const f1 of parseFields(Buffer.from(rawVal, "base64"))) {
      if (f1.wire !== 2) continue;
      for (const f2 of parseFields(f1.val)) {
        if (f2.wire !== 2) continue;
        for (const f3 of parseFields(f2.val)) {
          if (f3.wire !== 2) continue;
          const usFields = parseFields(Buffer.from(f3.val.toString("utf8"), "base64"));
          for (const uf of usFields) {
            if (uf.wire !== 2 || uf.val.length < 40) continue;
            for (const sf of parseFields(uf.val)) {
              if (sf.wire !== 2) continue;
              const mFields = parseFields(sf.val);
              const labelF = mFields.find((x) => x.field === 1 && x.wire === 2);
              const quotaF = mFields.find((x) => x.field === 15 && x.wire === 2);
              if (!labelF || !quotaF) continue;
              const label = labelF.val.toString("utf8").trim();
              const qFields = parseFields(quotaF.val);
              const remFrac = Number(qFields.find((x) => x.field === 1 && x.wire === 5)?.val ?? 1);
              const tsBuf = qFields.find((x) => x.field === 2 && x.wire === 2)?.val as Buffer | undefined;
              const tsSec = tsBuf ? Number(parseFields(tsBuf).find((x) => x.field === 1)?.val ?? 0n) : 0;
              const resetsAtMs = tsSec * 1000;
              // Only surface non-stale windows whose reset timestamp is still upcoming or within the last 6h
              if (resetsAtMs > nowMs - 6 * 3600_000) {
                const remPct = Math.max(0, Math.min(100, Math.round(remFrac * 100)));
                windows.push({
                  key: label,
                  label,
                  usedPercent: 100 - remPct,
                  remainingPercent: remPct,
                  resetsAtMs,
                });
              }
            }
          }
        }
      }
    }
    return windows.slice(0, 4);
  }

  private cleanBoilerplatePrompt(raw: string): string {
    const trimmed = raw.trim();
    if (!trimmed) return "";
    if (
      trimmed.startsWith("# AGENTS.md instructions") ||
      trimmed.startsWith("<environment_context") ||
      trimmed.startsWith("<permissions instructions") ||
      trimmed.startsWith("<collaboration_mode>") ||
      trimmed.startsWith("<recommended_plugins>") ||
      trimmed.startsWith("<external_codex_")
    ) {
      // Strip system XML tags and see if trailing user text remains
      const stripped = trimmed
        .replace(/<INSTRUCTIONS>[\s\S]*?<\/INSTRUCTIONS>/gi, "")
        .replace(/<environment_context>[\s\S]*?<\/environment_context>/gi, "")
        .replace(/<permissions[\s\S]*?<\/permissions[\s\S]*?>/gi, "")
        .replace(/<collaboration_mode>[\s\S]*?<\/collaboration_mode>/gi, "")
        .replace(/<recommended_plugins>[\s\S]*?<\/recommended_plugins>/gi, "")
        .replace(/<external_codex_[^>]*>[\s\S]*?<\/external_codex_[^>]*>/gi, "")
        .replace(/^#\s*AGENTS\.md[^\n]*\n?/i, "")
        .replace(/\s+/g, " ")
        .trim();
      return stripped;
    }
    return trimmed.replace(/\s+/g, " ").trim();
  }

  private resolveWorkspace(candidate?: string): string {
    const trimmed = (candidate || "").trim();
    if (trimmed) {
      const expanded = trimmed.startsWith("~/") ? path.join(os.homedir(), trimmed.slice(2)) : trimmed;
      if (fs.existsSync(expanded)) return expanded;
    }
    return this.defaultWorkspacePath;
  }

  private loadPersistedTasks(): void {
    try {
      if (!fs.existsSync(this.statePath)) return;
      const parsed = JSON.parse(fs.readFileSync(this.statePath, "utf8"));
      if (!Array.isArray(parsed?.tasks)) return;
      for (const item of parsed.tasks) {
        if (!item?.summary?.sessionId || !item?.summary?.backend) continue;
        const summary: AgentSessionSummary = {
          ...item.summary,
          status: item.summary.status === "running" ? "idle" : item.summary.status,
        };
        const steps: AgentStepItem[] = Array.isArray(item.steps) ? item.steps.slice(-60) : [];
        this.managedTasks.set(`${summary.backend}:${summary.sessionId}`, {
          summary,
          steps,
          child: null,
        });
      }
    } catch {
      // ignore
    }
  }

  private persistTasks(): void {
    try {
      const tasks = Array.from(this.managedTasks.values())
        .slice(-30)
        .map((st) => ({
          summary: st.summary,
          steps: st.steps.slice(-50),
        }));
      const tmp = `${this.statePath}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify({ tasks }, null, 2), "utf8");
      fs.renameSync(tmp, this.statePath);
    } catch {
      // ignore
    }
  }
}
