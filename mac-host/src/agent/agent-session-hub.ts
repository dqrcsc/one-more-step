import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcess } from "node:child_process";
import readline from "node:readline";
import type {
  AgentBackendType,
  AgentProjectItem,
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

    // 1. Mobile-dispatched / continued sessions tracked in memory
    for (const [, st] of this.managedTasks) {
      byKey.set(`${st.summary.backend}:${st.summary.sessionId}`, { ...st.summary });
    }

    // 2. Discover Codex desktop/CLI sessions from `~/.codex/sessions`
    for (const s of this.scanCodexSessions(32)) {
      const key = `codex:${s.sessionId}`;
      const existing = byKey.get(key);
      if (!existing || (existing.status !== "running" && s.updatedAtMs > existing.updatedAtMs)) {
        byKey.set(key, existing ? { ...s, origin: existing.origin } : s);
      }
    }

    // 3. Discover Antigravity (`agy`) desktop/CLI conversations from `~/.gemini/antigravity/brain`
    for (const s of this.scanAgySessions(32)) {
      const key = `agy:${s.sessionId}`;
      const existing = byKey.get(key);
      if (!existing || (existing.status !== "running" && s.updatedAtMs > existing.updatedAtMs)) {
        byKey.set(key, existing ? { ...s, origin: existing.origin } : s);
      }
    }

    return Array.from(byKey.values())
      .sort((a, b) => {
        if (a.status === "running" && b.status !== "running") return -1;
        if (b.status === "running" && a.status !== "running") return 1;
        return b.updatedAtMs - a.updatedAtMs;
      })
      .slice(0, Math.max(1, Math.min(80, limit)));
  }

  public getWorkspaceCatalog(sessionLimit = 48): AgentWorkspaceCatalogResult {
    const sessions = this.listSessions(sessionLimit);
    const projectsMap = new Map<string, AgentProjectItem>();
    const home = os.homedir();

    const registerDir = (absPath: string, groupHint: string, fallbackMtimeMs = 0) => {
      const normalized = absPath.trim().replace(/\/+$/, "");
      if (!normalized || !fs.existsSync(normalized)) return;
      try {
        if (!fs.statSync(normalized).isDirectory()) return;
      } catch {
        return;
      }
      if (projectsMap.has(normalized)) return;
      const base = path.basename(normalized) || normalized;
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

    // 1. Default workspace + core user roots (`~/work`, `~/entropy`)
    registerDir(this.defaultWorkspacePath, "当前主项目");
    registerDir(path.join(home, "work"), "核心工作区");
    registerDir(path.join(home, "entropy"), "个人知识库");

    // 2. Scan `~/x_project/*` subdirectories automatically
    const xProjectRoot = path.join(home, "x_project");
    if (fs.existsSync(xProjectRoot)) {
      try {
        for (const entry of fs.readdirSync(xProjectRoot, { withFileTypes: true })) {
          if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
          registerDir(path.join(xProjectRoot, entry.name), "x_project");
        }
      } catch {
        // ignore
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

    // 4. Fold all discovered Codex & Antigravity sessions into project stats (and add any custom session cwds)
    for (const s of sessions) {
      const ws = (s.workspacePath || "").trim().replace(/\/+$/, "");
      if (!ws) continue;
      if (!projectsMap.has(ws)) {
        registerDir(ws, ws.includes("/x_project/") ? "x_project" : "近期会话目录", s.updatedAtMs);
      }
      const item = projectsMap.get(ws);
      if (item) {
        item.sessionCount += 1;
        if (s.status === "running") item.runningCount += 1;
        if (s.updatedAtMs > item.lastActiveAtMs) item.lastActiveAtMs = s.updatedAtMs;
      }
    }

    const projects = Array.from(projectsMap.values()).sort((a, b) => {
      if (a.runningCount !== b.runningCount) return b.runningCount - a.runningCount;
      if ((a.sessionCount > 0) !== (b.sessionCount > 0)) return a.sessionCount > 0 ? -1 : 1;
      return b.lastActiveAtMs - a.lastActiveAtMs;
    });

    return { projects: projects.slice(0, 60), sessions };
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

  private scanCodexSessions(maxCount: number): AgentSessionSummary[] {
    if (!fs.existsSync(this.codexSessionsDir)) return [];
    const files: Array<{ filePath: string; mtimeMs: number }> = [];
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
            files.push({ filePath: full, mtimeMs: fs.statSync(full).mtimeMs });
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
      const parsed = this.parseCodexRolloutFile(f.filePath, f.mtimeMs, false);
      if (parsed) out.push(parsed.session);
    }
    return out;
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
    return this.parseCodexRolloutFile((targetFile as { filePath: string; mtimeMs: number }).filePath, (targetFile as { filePath: string; mtimeMs: number }).mtimeMs, true);
  }

  private parseCodexRolloutFile(
    filePath: string,
    mtimeMs: number,
    includeSteps: boolean
  ): AgentSessionDetailResult | null {
    try {
      const raw = fs.readFileSync(filePath, "utf8");
      const lines = raw.split(/\r?\n/).filter(Boolean);
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
        } else if (obj.type === "event_msg" && obj.payload) {
          const pt = String(obj.payload.type || "");
          if (pt === "user_message" && obj.payload.message) {
            const msg = this.cleanBoilerplatePrompt(String(obj.payload.message));
            if (msg && (!title || title.startsWith("# AGENTS") || title.startsWith("<"))) {
              title = msg.slice(0, 64);
            }
            if (includeSteps && msg) {
              steps.push({ stepIndex: steps.length + 1, kind: "user", title: "👤 用户指令", detail: msg.slice(0, 600), timestampMs: ts });
            }
          } else if (pt === "agent_message" && obj.payload.message) {
            const msg = String(obj.payload.message).trim();
            lastSummary = msg.replace(/\s+/g, " ").slice(0, 140);
            if (includeSteps) {
              steps.push({ stepIndex: steps.length + 1, kind: "assistant", title: "🤖 Codex", detail: msg.slice(0, 1200), timestampMs: ts });
            }
          } else if (pt === "agent_reasoning" && obj.payload.text && includeSteps) {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "thinking",
              title: "💭 推理摘要",
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
              title: `🔧 ${p.name || "tool"}`,
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
    if (!fs.existsSync(this.agyBrainDir)) return [];
    let entries: fs.Dirent[] = [];
    try {
      entries = fs.readdirSync(this.agyBrainDir, { withFileTypes: true });
    } catch {
      return [];
    }
    const candidates: Array<{ convId: string; transcriptPath: string; mtimeMs: number }> = [];
    for (const e of entries) {
      if (!e.isDirectory() || e.name.startsWith(".")) continue;
      const tp = path.join(this.agyBrainDir, e.name, ".system_generated", "logs", "transcript.jsonl");
      if (fs.existsSync(tp)) {
        try {
          candidates.push({ convId: e.name, transcriptPath: tp, mtimeMs: fs.statSync(tp).mtimeMs });
        } catch {
          // ignore
        }
      }
    }
    candidates.sort((a, b) => b.mtimeMs - a.mtimeMs);
    const out: AgentSessionSummary[] = [];
    for (const c of candidates.slice(0, maxCount)) {
      const parsed = this.parseAgyTranscript(c.convId, c.transcriptPath, c.mtimeMs, false);
      if (parsed) out.push(parsed.session);
    }
    return out;
  }

  private readAgySessionDetail(conversationId: string): AgentSessionDetailResult | null {
    const tp = path.join(this.agyBrainDir, conversationId, ".system_generated", "logs", "transcript.jsonl");
    if (!fs.existsSync(tp)) return null;
    try {
      const mtimeMs = fs.statSync(tp).mtimeMs;
      return this.parseAgyTranscript(conversationId, tp, mtimeMs, true);
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
      const raw = fs.readFileSync(transcriptPath, "utf8");
      const lines = raw.split(/\r?\n/).filter(Boolean);
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

        if (!workspacePath || workspacePath === this.defaultWorkspacePath) {
          const wsMatch = content.match(/Command Working Directory:\s*([^\n<]+)/);
          if (wsMatch?.[1]) workspacePath = wsMatch[1].trim();
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
                title: "👤 用户请求",
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
              title: "💭 Antigravity 思考",
              detail: obj.thinking.trim().slice(0, 400),
              timestampMs: ts,
            });
          }
          if (Array.isArray(obj.tool_calls) && obj.tool_calls.length > 0) {
            const toolNames = obj.tool_calls
              .map((tc: any) => tc?.name || tc?.tool_name || "tool")
              .join(", ");
            lastSummary = `🔧 调用工具: ${toolNames}`;
            if (includeSteps) {
              steps.push({
                stepIndex: steps.length + 1,
                kind: "tool",
                title: "🔧 调用工具",
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
                title: "🌌 Antigravity 回复",
                detail: content.trim().slice(0, 1200),
                timestampMs: ts,
              });
            }
          }
        }
      }

      // Skip internal Codex Guardian / safety-classifier ephemeral sub-sessions
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
