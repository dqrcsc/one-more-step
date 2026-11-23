import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { EventEmitter } from "node:events";
import { spawn, spawnSync, type ChildProcess } from "node:child_process";
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
  env?: Record<string, string>;
}

export type CliSpawnerFn = (spec: SpawnedCliSpec) => ChildProcess;

export interface AgentSessionHubOptions {
  omsHomeDir: string;
  codexSessionsDir?: string;
  agyBrainDir?: string;
  agyCliDir?: string;
  workbuddyDir?: string;
  opencodeDataDir?: string;
  defaultWorkspacePath?: string;
  cliSpawner?: CliSpawnerFn;
  appServerSpawner?: () => ChildProcess;
}

interface ManagedTaskState {
  summary: AgentSessionSummary;
  steps: AgentStepItem[];
  child: ChildProcess | null;
  cancelHook?: (() => void) | null;
}

/**
 * Milestone 3 (v2.0+): Mac Heavy-Brain Hub (`AgentSessionHub`).
 *
 * 1) Discovers & parses live desktop/CLI trajectories for BOTH:
 *    - OpenAI Codex (`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` + `state_*.sqlite`)
 *    - Google Antigravity (`~/.gemini/antigravity/brain/<id>` + `~/.gemini/antigravity-cli/brain/<id>`)
 * 2) Dispatches new tasks & continues existing sessions:
 *    - Codex via `codex app-server` (`project/list` + `project/create` + `thread/queue/add` + `codex://threads/wake/<id>`
 *      when Codex Desktop is live so turns execute in-place inside `ChatGPT.app`, falling back to direct `turn/start` / `thread/fork`)
 *    - Antigravity via `agy --project <projectId> --add-dir <workspace>` with Desktop trajectory metadata normalization,
 *      `annotations/<id>.pbtxt` title preservation, lock-safe active-pane fork promotion, and CLI duplicate cleanup so
 *      mobile-created & mobile-continued sessions appear natively in `Antigravity.app`.
 */
export class AgentSessionHub extends EventEmitter {
  private readonly hubDir: string;
  private readonly statePath: string;
  private readonly codexSessionsDir: string;
  private readonly agyBrainDir: string;
  private readonly agyCliDir: string;
  private readonly workbuddyDir: string;
  private readonly opencodeDataDir: string;
  private readonly defaultWorkspacePath: string;
  private readonly hasCustomCliSpawner: boolean;
  private readonly cliSpawner: CliSpawnerFn;
  private readonly appServerSpawner?: () => ChildProcess;
  private readonly managedTasks = new Map<string, ManagedTaskState>();
  private readonly sessionAliases = new Map<string, string>();
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
    this.agyCliDir = opts.agyCliDir || path.join(os.homedir(), ".gemini", "antigravity-cli");
    this.workbuddyDir =
      opts.workbuddyDir ||
      (process.env.OMS_TEST_ISOLATION === "1"
        ? path.join(opts.omsHomeDir, "mock_workbuddy")
        : path.join(os.homedir(), ".workbuddy"));
    this.opencodeDataDir =
      opts.opencodeDataDir ||
      (process.env.OMS_TEST_ISOLATION === "1"
        ? path.join(opts.omsHomeDir, "mock_opencode")
        : path.join(os.homedir(), ".local", "share", "opencode"));
    this.defaultWorkspacePath = opts.defaultWorkspacePath || process.cwd();
    this.hasCustomCliSpawner = Boolean(opts.cliSpawner);
    this.appServerSpawner = opts.appServerSpawner;
    this.cliSpawner =
      opts.cliSpawner ||
      ((spec) =>
        spawn(spec.command, spec.args, {
          cwd: spec.cwd,
          env: { ...this.buildSpawnEnv(), ...(spec.env || {}) },
          stdio: ["ignore", "pipe", "pipe"],
        }));
    this.loadPersistedTasks();
    if (
      process.env.OMS_TEST_ISOLATION !== "1" &&
      !this.hasCustomCliSpawner &&
      !this.appServerSpawner
    ) {
      try {
        this.reconcileDesktopVisibility();
      } catch {
        // ignore startup repair errors
      }
    }
  }

  private buildSpawnEnv(): NodeJS.ProcessEnv {
    const home = os.homedir();
    const extraPaths = [
      path.join(home, ".local", "bin"),
      path.join(home, ".opencode", "bin"),
      path.join(home, ".bun", "bin"),
      "/opt/homebrew/bin",
      "/usr/local/bin",
      "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS",
      "/Applications/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin",
      "/usr/bin",
      "/bin",
    ];
    return {
      ...process.env,
      PATH: `${extraPaths.join(":")}:${process.env.PATH || ""}`,
    };
  }

  public listSessions(limit = 48): AgentSessionSummary[] {
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

    // 4. Discover WorkBuddy (`~/.workbuddy/workbuddy.db`) sessions
    for (const s of this.scanWorkBuddySessions(36)) {
      const key = `workbuddy:${s.sessionId}`;
      const existing = byKey.get(key);
      if (!existing || (existing.status !== "running" && s.updatedAtMs >= existing.updatedAtMs)) {
        byKey.set(key, existing ? { ...s, origin: existing.origin } : s);
      }
    }

    // 5. Discover OpenCode (`~/.local/share/opencode/opencode.db`) sessions
    for (const s of this.scanOpenCodeSessions(36)) {
      const key = `opencode:${s.sessionId}`;
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

    return sorted.slice(0, Math.max(1, Math.min(160, limit)));
  }

  public getWorkspaceCatalog(sessionLimit = 96): AgentWorkspaceCatalogResult {
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
      workbuddy: this.collectWorkBuddyQuota(),
      opencode: this.collectOpenCodeQuota(),
      fetchedAtMs: Date.now(),
    };
  }

  private resolveAliasedSessionId(backend: AgentBackendType, sessionId: string): string {
    let cur = sessionId;
    for (let i = 0; i < 4; i++) {
      const next = this.sessionAliases.get(`${backend}:${cur}`);
      if (!next || next === cur) break;
      cur = next;
    }
    return cur;
  }

  /**
   * Preserves all recent User & Assistant conversation turns (up to `maxDialogueTurns`)
   * while capping consecutive intermediate tool/thinking steps between turns (`maxToolsPerTurn`),
   * ensuring long tool-heavy sessions never evict historical dialogue messages on mobile!
   */
  public compactTrajectorySteps(
    rawSteps: AgentStepItem[],
    maxDialogueTurns = 60,
    maxToolsPerTurn = 6
  ): AgentStepItem[] {
    if (rawSteps.length === 0) return [];
    const dialogueIndices: number[] = [];
    for (let i = 0; i < rawSteps.length; i++) {
      const k = rawSteps[i].kind;
      if (k === "user" || k === "assistant" || k === "status") {
        dialogueIndices.push(i);
      }
    }

    const startIdx =
      dialogueIndices.length > maxDialogueTurns
        ? dialogueIndices[dialogueIndices.length - maxDialogueTurns]
        : 0;

    const out: AgentStepItem[] = [];
    let toolBuf: AgentStepItem[] = [];
    const flushTools = () => {
      if (toolBuf.length === 0) return;
      if (toolBuf.length > maxToolsPerTurn) {
        const omitted = toolBuf.length - maxToolsPerTurn;
        const firstTs = toolBuf[0].timestampMs;
        out.push({
          stepIndex: 0,
          kind: "tool",
          title: `已折叠早期步骤`,
          detail: `省略前 ${omitted} 步中间工具调用与思考过程`,
          timestampMs: firstTs,
        });
        for (const t of toolBuf.slice(-maxToolsPerTurn)) {
          out.push({ ...t });
        }
      } else {
        for (const t of toolBuf) {
          out.push({ ...t });
        }
      }
      toolBuf = [];
    };

    for (let i = startIdx; i < rawSteps.length; i++) {
      const st = rawSteps[i];
      if (st.kind === "tool" || st.kind === "thinking") {
        toolBuf.push(st);
      } else {
        flushTools();
        out.push({ ...st });
      }
    }
    flushTools();

    return out.map((s, idx) => ({
      ...s,
      stepIndex: idx + 1,
    }));
  }

  public getSessionDetail(backend: AgentBackendType, rawSessionId: string): AgentSessionDetailResult {
    const sessionId = this.resolveAliasedSessionId(backend, rawSessionId);
    const managed = this.managedTasks.get(`${backend}:${sessionId}`);
    const diskDetail =
      backend === "codex"
        ? this.readCodexSessionDetail(sessionId)
        : backend === "agy"
          ? this.readAgySessionDetail(sessionId)
          : backend === "workbuddy"
            ? this.readWorkBuddySessionDetail(sessionId)
            : this.readOpenCodeSessionDetail(sessionId);

    if (managed && diskDetail) {
      const lastDiskTs = diskDetail.steps.length > 0 ? diskDetail.steps[diskDetail.steps.length - 1].timestampMs : 0;
      const liveTail =
        managed.child || managed.cancelHook
          ? managed.steps.filter((s) => s.timestampMs > lastDiskTs)
          : [];
      const merged =
        diskDetail.steps.some((s) => s.kind === "user" || s.kind === "assistant")
          ? [...diskDetail.steps, ...liveTail]
          : managed.steps.length >= diskDetail.steps.length
            ? managed.steps
            : diskDetail.steps;

      return {
        session: {
          ...diskDetail.session,
          status:
            managed.summary.status === "running"
              ? "running"
              : managed.summary.status === "failed" && managed.summary.updatedAtMs >= diskDetail.session.updatedAtMs
                ? "failed"
                : diskDetail.session.status,
          origin: managed.summary.origin,
          lastSummary: managed.summary.lastSummary || diskDetail.session.lastSummary,
        },
        steps: this.compactTrajectorySteps(merged),
      };
    }
    if (managed) {
      return {
        session: { ...managed.summary },
        steps: this.compactTrajectorySteps(managed.steps),
      };
    }
    if (diskDetail) {
      return {
        session: diskDetail.session,
        steps: this.compactTrajectorySteps(diskDetail.steps),
      };
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

    if (params.backend === "codex" && (!this.hasCustomCliSpawner || this.appServerSpawner)) {
      return this.launchCodexAppServerSession(summary, [initialStep], {
        mode: "start",
        workspace,
        prompt: params.prompt,
      });
    }

    const agyProjectId =
      params.backend === "agy" && !this.hasCustomCliSpawner
        ? this.resolveOrCreateAgyProjectForWorkspace(workspace)
        : "";

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
        : params.backend === "workbuddy"
          ? {
              command: this.resolveWorkBuddyCliCommand(),
              args: ["-p", "--output-format", "stream-json", "-y", params.prompt],
              cwd: workspace,
              env: {
                CODEBUDDY_FORCE_LITE_WB_BUNDLE: "1",
                CODEBUDDY_CONFIG_DIR: this.workbuddyDir,
                CODEBUDDY_DATA_ROOT: this.workbuddyDir,
              },
            }
          : params.backend === "opencode"
            ? {
                command: this.resolveOpenCodeCliCommand(),
                args: [
                  "run",
                  "--format",
                  "json",
                  "--auto",
                  "--dir",
                  workspace,
                  "--title",
                  summary.title,
                  params.prompt,
                ],
                cwd: workspace,
              }
            : {
                command: "agy",
                args: this.hasCustomCliSpawner
                  ? ["--print", params.prompt, "--output-format", "stream-json"]
                  : [
                      "--dangerously-skip-permissions",
                      ...(agyProjectId ? ["--project", agyProjectId] : []),
                      "--add-dir",
                      workspace,
                      "--print",
                      params.prompt,
                      "--output-format",
                      "stream-json",
                    ],
                cwd: workspace,
              };

    return this.launchManagedProcess(summary, [initialStep], spec);
  }

  public continueSession(params: AgentSessionContinueParams): AgentSessionSummary {
    const targetSessionId = this.resolveAliasedSessionId(params.backend, params.sessionId);
    const existingDetail = (() => {
      try {
        return this.getSessionDetail(params.backend, targetSessionId);
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
      sessionId: targetSessionId,
      backend: params.backend,
      origin: existingDetail?.session.origin || "desktop",
      title: existingDetail?.session.title || params.prompt.slice(0, 60),
      workspacePath: workspace,
      status: "running",
      lastSummary: `🔄 正在接续会话：${params.prompt.slice(0, 48)}`,
      updatedAtMs: now,
      canContinue: true,
    };

    if (params.backend === "codex" && (!this.hasCustomCliSpawner || this.appServerSpawner)) {
      return this.launchCodexAppServerSession(summary, priorSteps, {
        mode: "resume",
        resumeThreadId: targetSessionId,
        workspace,
        prompt: params.prompt,
      });
    }

    let agyProjectId = "";
    if (params.backend === "agy" && !this.hasCustomCliSpawner) {
      agyProjectId = this.resolveOrCreateAgyProjectForWorkspace(workspace);
      this.prepareAgySessionForCliResume(targetSessionId);
    }

    const spec: SpawnedCliSpec =
      params.backend === "codex"
        ? {
            command: "codex",
            args: ["exec", "resume", targetSessionId, "--skip-git-repo-check", "--json", params.prompt],
            cwd: workspace,
          }
        : params.backend === "workbuddy"
          ? {
              command: this.resolveWorkBuddyCliCommand(),
              args: ["-p", "--output-format", "stream-json", "-y", "-r", targetSessionId, params.prompt],
              cwd: workspace,
              env: {
                CODEBUDDY_FORCE_LITE_WB_BUNDLE: "1",
                CODEBUDDY_CONFIG_DIR: this.workbuddyDir,
                CODEBUDDY_DATA_ROOT: this.workbuddyDir,
              },
            }
          : params.backend === "opencode"
            ? {
                command: this.resolveOpenCodeCliCommand(),
                args: [
                  "run",
                  "--format",
                  "json",
                  "--auto",
                  "--dir",
                  workspace,
                  "--session",
                  targetSessionId,
                  params.prompt,
                ],
                cwd: workspace,
              }
            : {
                command: "agy",
                args: this.hasCustomCliSpawner
                  ? [
                      "--conversation",
                      targetSessionId,
                      "--print",
                      params.prompt,
                      "--output-format",
                      "stream-json",
                    ]
                  : [
                      "--dangerously-skip-permissions",
                      ...(agyProjectId ? ["--project", agyProjectId] : []),
                      "--add-dir",
                      workspace,
                      "--conversation",
                      targetSessionId,
                      "--print",
                      params.prompt,
                      "--output-format",
                      "stream-json",
                    ],
                cwd: workspace,
              };

    return this.launchManagedProcess(summary, priorSteps, spec);
  }

  public cancelSession(backend: AgentBackendType, rawSessionId: string): boolean {
    const sessionId = this.resolveAliasedSessionId(backend, rawSessionId);
    const key = `${backend}:${sessionId}`;
    const state = this.managedTasks.get(key);
    if (!state || (!state.child && !state.cancelHook)) return false;
    if (state.cancelHook) {
      try {
        state.cancelHook();
      } catch {
        // ignore
      }
      state.cancelHook = null;
    }
    if (state.child) {
      try {
        state.child.kill("SIGTERM");
      } catch {
        // ignore
      }
      state.child = null;
    }
    state.summary.status = "failed";
    state.summary.lastSummary = "⏹ 已由手机端中止任务";
    state.summary.updatedAtMs = Date.now();
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
      if (st.cancelHook) {
        try {
          st.cancelHook();
        } catch {
          // ignore
        }
        st.cancelHook = null;
      }
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

  /**
   * Prefers Codex Desktop's bundled CLI (`ChatGPT.app` / `Codex.app`) when installed on macOS
   * so schema columns (`thread_source`, paginated history, etc.) stay 100% aligned with `Codex.app`,
   * falling back to `codex` on PATH.
   */
  public resolveCodexCliExecutable(): string {
    const envBin = (process.env.OMS_CODEX_BIN || "").trim();
    if (envBin && fs.existsSync(envBin)) return envBin;
    if (process.platform === "darwin" && process.env.OMS_TEST_ISOLATION !== "1") {
      const desktopCandidates = [
        "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex",
        "/Applications/Codex.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex",
        path.join(os.homedir(), "Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"),
        path.join(os.homedir(), "Applications/Codex.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"),
      ];
      for (const binPath of desktopCandidates) {
        try {
          if (fs.existsSync(binPath)) return binPath;
        } catch {
          // ignore
        }
      }
    }
    return "codex";
  }

  private formatMobileForkTitle(baseTitle: string, fallbackLabel = "会话"): string {
    const cleaned = baseTitle
      .trim()
      .replace(/\s*[（(]手机接续[）)]\s*$/u, "")
      .trim();
    const core = (cleaned || fallbackLabel).slice(0, 72);
    return `${core}（手机接续）`;
  }

  private openSqliteReadonlyWithWal(dbPath: string): Database {
    try {
      return new Database(dbPath, { readonly: true });
    } catch {
      return new Database(`file:${dbPath}?immutable=1`, { readonly: true });
    }
  }

  private isCodexDesktopAppRunning(): boolean {
    if (
      process.platform !== "darwin" ||
      process.env.OMS_TEST_ISOLATION === "1" ||
      this.hasCustomCliSpawner ||
      Boolean(this.appServerSpawner)
    ) {
      return false;
    }
    try {
      const r = spawnSync("pgrep", ["-x", "ChatGPT"], { encoding: "utf8", timeout: 800 });
      if (r.status === 0 && (r.stdout || "").trim().length > 0) return true;
      const r2 = spawnSync("pgrep", ["-x", "Codex"], { encoding: "utf8", timeout: 800 });
      return r2.status === 0 && (r2.stdout || "").trim().length > 0;
    } catch {
      return false;
    }
  }

  private wakeCodexDesktopThread(threadId: string): void {
    if (process.platform !== "darwin" || process.env.OMS_TEST_ISOLATION === "1") return;
    try {
      const p = spawn("open", ["-g", `codex://threads/wake/${encodeURIComponent(threadId)}`], {
        stdio: "ignore",
        detached: true,
      });
      p.unref();
    } catch {
      // ignore
    }
  }

  private ensureCodexWorkspaceRegisteredWithDesktop(workspacePath: string): void {
    if (process.platform !== "darwin" || process.env.OMS_TEST_ISOLATION === "1") return;
    const normWs = workspacePath.trim().replace(/\/+$/, "");
    if (!normWs || !fs.existsSync(normWs)) return;
    const codexRoot = path.dirname(this.codexSessionsDir);
    const gsPath = path.join(codexRoot, ".codex-global-state.json");
    let alreadyRegistered = false;
    try {
      if (fs.existsSync(gsPath)) {
        const gs = JSON.parse(fs.readFileSync(gsPath, "utf8")) as Record<string, any>;
        const localProjects = gs["local-projects"];
        if (localProjects && typeof localProjects === "object") {
          for (const lpVal of Object.values(localProjects)) {
            const roots = Array.isArray((lpVal as any)?.rootPaths) ? (lpVal as any).rootPaths : [];
            if (roots.some((r: unknown) => typeof r === "string" && r.trim().replace(/\/+$/, "") === normWs)) {
              alreadyRegistered = true;
              break;
            }
          }
        }
      }
    } catch {
      // ignore
    }

    if (!alreadyRegistered && this.isCodexDesktopAppRunning() && fs.existsSync("/Applications/ChatGPT.app")) {
      try {
        const p = spawn("open", ["-g", "-a", "/Applications/ChatGPT.app", normWs], {
          stdio: "ignore",
          detached: true,
        });
        p.unref();
      } catch {
        // ignore
      }
    }
  }

  private findCodexRolloutFilePath(sessionId: string): string | null {
    if (!sessionId || !fs.existsSync(this.codexSessionsDir)) return null;
    let found: string | null = null;
    const walk = (dir: string, depth: number) => {
      if (depth > 4 || found) return;
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
          found = full;
          return;
        }
      }
    };
    walk(this.codexSessionsDir, 0);
    return found;
  }

  private isCodexQueuedItemPending(threadId: string, queuedSubmissionId: string): boolean {
    const codexRoot = path.dirname(this.codexSessionsDir);
    const qDbPath = path.join(codexRoot, "queue_1.sqlite");
    if (!fs.existsSync(qDbPath)) return false;
    try {
      const db = this.openSqliteReadonlyWithWal(qDbPath);
      try {
        const row = db
          .query("SELECT 1 AS ok FROM queued_items WHERE thread_id = ? AND id = ? LIMIT 1")
          .get(threadId, queuedSubmissionId);
        return Boolean(row);
      } finally {
        db.close();
      }
    } catch {
      return false;
    }
  }

  private watchWokenCodexDesktopTurn(
    state: ManagedTaskState,
    threadId: string,
    queuedSubmissionId: string,
    initialRolloutOffset: number,
    lastPreviousSessionId: string | undefined,
    opts: {
      mode: "start" | "resume";
      workspace: string;
      prompt: string;
    },
    pushAndEmitStep: (step: AgentStepItem) => void
  ): void {
    const watchStartedAt = Date.now();
    let rolloutPath = this.findCodexRolloutFilePath(threadId);
    let readOffset = initialRolloutOffset;
    let wokenConfirmed = false;
    let finished = false;
    let lastEmittedAssistant = "";

    const cleanup = () => {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
      state.cancelHook = null;
    };

    const finalize = (status: "completed" | "failed", summaryOverride?: string) => {
      if (finished) return;
      finished = true;
      cleanup();
      state.summary.status = status;
      if (summaryOverride) {
        state.summary.lastSummary = summaryOverride;
      } else if (status === "completed") {
        const lastAssistant = [...state.steps].reverse().find((s) => s.kind === "assistant");
        if (lastAssistant) {
          state.summary.lastSummary = lastAssistant.detail.slice(0, 160);
        }
      }
      this.linkCodexThreadToWorkspaceProject(threadId, opts.workspace, state.summary.title);
      state.summary.updatedAtMs = Date.now();
      this.persistTasks();
      this.emitStreamEvent({
        backend: "codex",
        sessionId: state.summary.sessionId,
        previousSessionId: lastPreviousSessionId,
        status,
        finalSummary: state.summary.lastSummary,
        updatedAtMs: state.summary.updatedAtMs,
      });
    };

    let timer: ReturnType<typeof setInterval> | null = setInterval(() => {
      if (finished) return;
      try {
        if (!rolloutPath) {
          rolloutPath = this.findCodexRolloutFilePath(threadId);
        }
        if (rolloutPath && fs.existsSync(rolloutPath)) {
          const st = fs.statSync(rolloutPath);
          if (st.size > readOffset) {
            wokenConfirmed = true;
            const fd = fs.openSync(rolloutPath, "r");
            try {
              const toRead = Math.min(st.size - readOffset, 1024 * 1024);
              const buf = Buffer.allocUnsafe(toRead);
              const n = fs.readSync(fd, buf, 0, toRead, readOffset);
              if (n > 0) {
                const slice = buf.subarray(0, n);
                const lastNl = slice.lastIndexOf(0x0a);
                if (lastNl >= 0) {
                  readOffset += lastNl + 1;
                  const lines = slice
                    .subarray(0, lastNl)
                    .toString("utf8")
                    .split(/\r?\n/)
                    .filter(Boolean);
                  for (const line of lines) {
                    let obj: any;
                    try {
                      obj = JSON.parse(line);
                    } catch {
                      continue;
                    }
                    const now = Date.now();
                    if (obj.type === "event_msg" && obj.payload) {
                      const pt = String(obj.payload.type || "");
                      if (pt === "agent_reasoning" && obj.payload.text) {
                        const txt = String(obj.payload.text).trim();
                        if (txt) {
                          pushAndEmitStep({
                            stepIndex: state.steps.length + 1,
                            kind: "thinking",
                            title: "思考推演",
                            detail: txt.slice(0, 500),
                            timestampMs: now,
                          });
                        }
                      } else if (pt === "agent_message" && obj.payload.message) {
                        const txt = String(obj.payload.message).trim();
                        if (txt && txt !== lastEmittedAssistant) {
                          lastEmittedAssistant = txt;
                          pushAndEmitStep({
                            stepIndex: state.steps.length + 1,
                            kind: "assistant",
                            title: "Codex 回复",
                            detail: txt.slice(0, 2400),
                            timestampMs: now,
                          });
                        }
                      } else if (pt === "task_complete") {
                        const finalMsg =
                          typeof obj.payload.last_agent_message === "string"
                            ? obj.payload.last_agent_message.trim()
                            : "";
                        if (finalMsg && finalMsg !== lastEmittedAssistant) {
                          lastEmittedAssistant = finalMsg;
                          pushAndEmitStep({
                            stepIndex: state.steps.length + 1,
                            kind: "assistant",
                            title: "Codex 回复",
                            detail: finalMsg.slice(0, 2400),
                            timestampMs: now,
                          });
                        }
                        finalize("completed");
                        return;
                      } else if (pt === "turn_aborted") {
                        finalize(
                          "failed",
                          `Codex 轮次已中止: ${String(obj.payload.reason || "interrupted").slice(0, 100)}`
                        );
                        return;
                      }
                    } else if (obj.type === "response_item" && obj.payload) {
                      const p = obj.payload;
                      if (p.type === "function_call" || p.type === "custom_tool_call") {
                        pushAndEmitStep({
                          stepIndex: state.steps.length + 1,
                          kind: "tool",
                          title: String(p.name || p.type || "调用工具"),
                          detail: String(p.arguments || p.input || "").slice(0, 320),
                          timestampMs: now,
                        });
                      } else if (p.type === "message" && p.role === "assistant" && Array.isArray(p.content)) {
                        const txt = p.content
                          .filter((c: any) => typeof c?.text === "string" && c.text.trim())
                          .map((c: any) => String(c.text).trim())
                          .join("\n\n")
                          .trim();
                        if (txt && txt !== lastEmittedAssistant) {
                          lastEmittedAssistant = txt;
                          pushAndEmitStep({
                            stepIndex: state.steps.length + 1,
                            kind: "assistant",
                            title: "Codex 回复",
                            detail: txt.slice(0, 2400),
                            timestampMs: now,
                          });
                        }
                      }
                    }
                  }
                }
              }
            } finally {
              try {
                fs.closeSync(fd);
              } catch {}
            }
          }
        }

        if (!wokenConfirmed) {
          const stillQueued = this.isCodexQueuedItemPending(threadId, queuedSubmissionId);
          if (!stillQueued) {
            wokenConfirmed = true;
          } else if (Date.now() - watchStartedAt >= 4500) {
            // Desktop app did not dequeue within 4.5s — seamlessly fall back to direct app-server execution
            finished = true;
            cleanup();
            this.launchCodexAppServerSession(state.summary, state.steps, {
              mode: "resume",
              resumeThreadId: threadId,
              workspace: opts.workspace,
              prompt: opts.prompt,
              allowDesktopWake: false,
              clearQueuedSubmissionId: queuedSubmissionId,
            });
            return;
          }
        }
      } catch {
        // ignore transient file race
      }
    }, 350);

    state.cancelHook = () => {
      finished = true;
      cleanup();
    };
  }

  private launchCodexAppServerSession(
    initialSummary: AgentSessionSummary,
    initialSteps: AgentStepItem[],
    opts: {
      mode: "start" | "resume";
      resumeThreadId?: string;
      workspace: string;
      prompt: string;
      allowDesktopWake?: boolean;
      clearQueuedSubmissionId?: string;
    }
  ): AgentSessionSummary {
    const initialId = initialSummary.sessionId;
    let currentKey = `codex:${initialId}`;
    const prev = this.managedTasks.get(currentKey);
    if (prev?.child || prev?.cancelHook) {
      throw new Error(`该会话正在运行中 (${initialId})，请等待当前轮次完成或先停止`);
    }

    const useDesktopLiveWake =
      opts.allowDesktopWake !== false && this.isCodexDesktopAppRunning();

    const preWakeRolloutPath =
      opts.mode === "resume" && opts.resumeThreadId
        ? this.findCodexRolloutFilePath(opts.resumeThreadId)
        : null;
    const preWakeRolloutOffset =
      preWakeRolloutPath && fs.existsSync(preWakeRolloutPath)
        ? (() => {
            try {
              return fs.statSync(preWakeRolloutPath).size;
            } catch {
              return 0;
            }
          })()
        : 0;

    const state: ManagedTaskState = {
      summary: initialSummary,
      steps: initialSteps,
      child: null,
      cancelHook: null,
    };
    this.managedTasks.set(currentKey, state);

    let child: ChildProcess;
    try {
      const codexBin = this.resolveCodexCliExecutable();
      child = this.appServerSpawner
        ? this.appServerSpawner()
        : spawn(codexBin, ["app-server"], {
            cwd: opts.workspace,
            env: this.buildSpawnEnv(),
            stdio: ["pipe", "pipe", "pipe"],
          });
    } catch (err) {
      state.summary.status = "failed";
      state.summary.lastSummary = `启动 codex app-server 失败: ${(err as Error).message}`;
      this.persistTasks();
      return { ...state.summary };
    }
    state.child = child;

    let lastPreviousSessionId: string | undefined;
    const rekeySessionId = (realId: string) => {
      const trimmed = realId.trim();
      if (!trimmed || trimmed === state.summary.sessionId) return;
      const oldId = state.summary.sessionId;
      lastPreviousSessionId = oldId;
      this.sessionAliases.set(`codex:${oldId}`, trimmed);
      this.managedTasks.delete(currentKey);
      state.summary.sessionId = trimmed;
      currentKey = `codex:${trimmed}`;
      this.managedTasks.set(currentKey, state);
    };

    const sendRpc = (payload: Record<string, any>) => {
      if (child.stdin && !child.stdin.destroyed) {
        try {
          child.stdin.write(JSON.stringify(payload) + "\n");
        } catch {
          // ignore broken pipe
        }
      }
    };

    const pushAndEmitStep = (step: AgentStepItem) => {
      state.steps.push(step);
      state.summary.lastSummary = `${step.title}: ${step.detail}`.slice(0, 140);
      state.summary.updatedAtMs = Date.now();
      this.emitStreamEvent({
        backend: "codex",
        sessionId: state.summary.sessionId,
        previousSessionId: lastPreviousSessionId,
        status: "running",
        step,
        updatedAtMs: state.summary.updatedAtMs,
      });
    };

    let turnCompletedHandled = false;
    let handedOffToDesktopWake = false;
    let queuedWakeSubmissionId = "";
    let wakeStartRolloutOffset = preWakeRolloutOffset;
    let activeThreadId = opts.resumeThreadId || "";
    let forkedFromLockedDesktop = false;
    let resolvedProjectId: string | null = null;
    const normWs = opts.workspace.trim().replace(/\/+$/, "");

    const dispatchAfterProjectReady = () => {
      if (opts.clearQueuedSubmissionId && opts.resumeThreadId) {
        sendRpc({
          id: 19,
          method: "thread/queue/delete",
          params: {
            threadId: opts.resumeThreadId,
            queuedSubmissionId: opts.clearQueuedSubmissionId,
          },
        });
      }

      if (useDesktopLiveWake && opts.mode === "resume" && opts.resumeThreadId) {
        activeThreadId = opts.resumeThreadId;
        rekeySessionId(opts.resumeThreadId);
        if (resolvedProjectId) {
          sendRpc({
            id: 13,
            method: "thread/metadata/update",
            params: { threadId: opts.resumeThreadId, projectId: resolvedProjectId },
          });
        }
        sendRpc({
          id: 20,
          method: "thread/queue/add",
          params: {
            threadId: opts.resumeThreadId,
            input: [{ type: "text", text: opts.prompt, text_elements: [] }],
            clientUserMessageId: crypto.randomUUID(),
          },
        });
        return;
      }

      if (opts.mode === "start") {
        const startParams: Record<string, any> = {
          cwd: opts.workspace,
          approvalPolicy: "never",
          sandbox: "workspace-write",
        };
        if (!this.appServerSpawner) {
          startParams.historyMode = "paginated";
          startParams.threadSource = "user";
          if (resolvedProjectId) startParams.projectId = resolvedProjectId;
        }
        sendRpc({
          id: 2,
          method: "thread/start",
          params: startParams,
        });
      } else {
        sendRpc({
          id: 2,
          method: "thread/resume",
          params: {
            threadId: opts.resumeThreadId,
            cwd: opts.workspace,
            approvalPolicy: "never",
            sandbox: "workspace-write",
            excludeTurns: true,
          },
        });
      }
    };

    const startTurnOnThread = (threadId: string) => {
      activeThreadId = threadId;
      rekeySessionId(threadId);
      if (!this.appServerSpawner) {
        if (resolvedProjectId) {
          sendRpc({
            id: 13,
            method: "thread/metadata/update",
            params: { threadId, projectId: resolvedProjectId },
          });
        }
        if (opts.mode === "start" || forkedFromLockedDesktop) {
          sendRpc({
            id: 12,
            method: "thread/name/set",
            params: { threadId, name: state.summary.title.slice(0, 120) },
          });
        }
      }
      // Eagerly link desktop metadata & fork display title so Codex.app shows the right name/project even mid-run
      this.linkCodexThreadToWorkspaceProject(
        threadId,
        opts.workspace,
        state.summary.title,
        forkedFromLockedDesktop
      );

      if (useDesktopLiveWake && opts.mode === "start") {
        const createdRollout = this.findCodexRolloutFilePath(threadId);
        if (createdRollout && fs.existsSync(createdRollout)) {
          try {
            wakeStartRolloutOffset = fs.statSync(createdRollout).size;
          } catch {}
        }
        sendRpc({
          id: 20,
          method: "thread/queue/add",
          params: {
            threadId,
            input: [{ type: "text", text: opts.prompt, text_elements: [] }],
            clientUserMessageId: crypto.randomUUID(),
          },
        });
        return;
      }

      sendRpc({
        id: 4,
        method: "turn/start",
        params: {
          threadId,
          cwd: opts.workspace,
          approvalPolicy: "never",
          input: [{ type: "text", text: opts.prompt, text_elements: [] }],
        },
      });
    };

    if (child.stdout) {
      const rl = readline.createInterface({ input: child.stdout });
      rl.on("line", (line) => {
        const trimmed = line.trim();
        if (!trimmed) return;
        let msg: Record<string, any>;
        try {
          msg = JSON.parse(trimmed);
        } catch {
          return;
        }

        // 1. JSON-RPC Responses
        if (typeof msg.id === "number") {
          if (msg.id === 1) {
            if (msg.error) {
              state.summary.status = "failed";
              state.summary.lastSummary = `Codex 初始化失败: ${msg.error.message || "unknown"}`;
              try { child.kill("SIGTERM"); } catch {}
              return;
            }
            sendRpc({ method: "initialized" });
            if (this.appServerSpawner) {
              dispatchAfterProjectReady();
            } else {
              sendRpc({
                id: 10,
                method: "project/list",
                params: { cursor: null, limit: 100 },
              });
            }
            return;
          }

          if (msg.id === 10) {
            if (!msg.error && Array.isArray(msg.result?.data)) {
              for (const proj of msg.result.data) {
                const roots = Array.isArray(proj?.roots) ? proj.roots : [];
                const matched = roots.some((r: any) => {
                  const pStr = typeof r === "string" ? r : r?.path;
                  return typeof pStr === "string" && pStr.trim().replace(/\/+$/, "") === normWs;
                });
                if (matched && proj?.id) {
                  resolvedProjectId = String(proj.id);
                  break;
                }
              }
            }
            if (!resolvedProjectId && normWs) {
              sendRpc({
                id: 11,
                method: "project/create",
                params: {
                  name: path.basename(normWs) || "workspace",
                  roots: [{ path: normWs }],
                  idempotencyKey: `oms-proj:${normWs}`,
                },
              });
              return;
            }
            dispatchAfterProjectReady();
            return;
          }

          if (msg.id === 11) {
            const createdId = msg.result?.project?.id || msg.result?.id;
            if (typeof createdId === "string" && createdId) {
              resolvedProjectId = createdId;
            }
            dispatchAfterProjectReady();
            return;
          }

          if (msg.id === 20) {
            if (msg.error || !msg.result?.queuedSubmission?.id) {
              // Queueing unsupported or failed — fall back immediately to direct execution
              if (opts.mode === "start" && activeThreadId) {
                sendRpc({
                  id: 4,
                  method: "turn/start",
                  params: {
                    threadId: activeThreadId,
                    cwd: opts.workspace,
                    approvalPolicy: "never",
                    input: [{ type: "text", text: opts.prompt, text_elements: [] }],
                  },
                });
              } else {
                sendRpc({
                  id: 2,
                  method: "thread/resume",
                  params: {
                    threadId: opts.resumeThreadId,
                    cwd: opts.workspace,
                    approvalPolicy: "never",
                    sandbox: "workspace-write",
                    excludeTurns: true,
                  },
                });
              }
              return;
            }

            queuedWakeSubmissionId = String(msg.result.queuedSubmission.id);
            handedOffToDesktopWake = true;
            this.ensureCodexWorkspaceRegisteredWithDesktop(opts.workspace);
            this.linkCodexThreadToWorkspaceProject(
              activeThreadId,
              opts.workspace,
              state.summary.title,
              false
            );
            try { child.stdin?.end(); } catch {}
            setTimeout(() => {
              try { child.kill("SIGTERM"); } catch {}
            }, 60);
            return;
          }

          if (msg.id === 2) {
            if (msg.error) {
              const errMsg = String(msg.error.message || "");
              // If the session is currently held open by another non-Desktop writer (`already has an active writer`),
              // automatically fork with full conversation history into a new `source = 'vscode'` Desktop thread!
              if (opts.mode === "resume" && opts.resumeThreadId) {
                pushAndEmitStep({
                  stepIndex: state.steps.length + 1,
                  kind: "status",
                  title: "活跃会话派生接续",
                  detail: "原会话正在其他终端占用写入锁，已自动携带完整上下文派生接续会话",
                  timestampMs: Date.now(),
                });
                sendRpc({
                  id: 3,
                  method: "thread/fork",
                  params: {
                    threadId: opts.resumeThreadId,
                    cwd: opts.workspace,
                    approvalPolicy: "never",
                    sandbox: "workspace-write",
                    ...(this.appServerSpawner ? {} : { threadSource: "user", excludeTurns: true }),
                  },
                });
                return;
              }
              state.summary.status = "failed";
              state.summary.lastSummary = `Codex 启动会话失败: ${errMsg}`;
              try { child.kill("SIGTERM"); } catch {}
              return;
            }
            const tId = String(msg.result?.thread?.id || opts.resumeThreadId || "");
            if (tId) startTurnOnThread(tId);
            return;
          }

          if (msg.id === 3) {
            if (msg.error) {
              state.summary.status = "failed";
              state.summary.lastSummary = `Codex 派生会话失败: ${msg.error.message || "unknown"}`;
              try { child.kill("SIGTERM"); } catch {}
              return;
            }
            const forkedId = String(msg.result?.thread?.id || "");
            if (forkedId) {
              forkedFromLockedDesktop = true;
              state.summary.title = this.formatMobileForkTitle(state.summary.title, "Codex 会话");
              pushAndEmitStep({
                stepIndex: state.steps.length + 1,
                kind: "status",
                title: "已创建手机接续会话",
                detail: `「${state.summary.title}」已在桌面库建立；打开 codex://threads/${forkedId} 即可同步查看`,
                timestampMs: Date.now(),
              });
              startTurnOnThread(forkedId);
            }
            return;
          }

          if (msg.id === 4 && msg.error) {
            state.summary.status = "failed";
            state.summary.lastSummary = `Codex 提交轮次失败: ${msg.error.message || "unknown"}`;
            try { child.kill("SIGTERM"); } catch {}
            return;
          }
          return;
        }

        // 2. JSON-RPC Streaming Server Notifications
        const method = String(msg.method || "");
        const p = (msg.params || {}) as Record<string, any>;
        if (typeof p.threadId === "string" && p.threadId.length >= 8) {
          rekeySessionId(p.threadId);
          activeThreadId = p.threadId;
        }

        if (method === "item/started" && p.item) {
          const item = p.item as Record<string, any>;
          if (item.type === "commandExecution") {
            pushAndEmitStep({
              stepIndex: state.steps.length + 1,
              kind: "tool",
              title: "执行终端命令",
              detail: String(item.command || "").slice(0, 320),
              timestampMs: Date.now(),
            });
          } else if (item.type === "mcpToolCall") {
            pushAndEmitStep({
              stepIndex: state.steps.length + 1,
              kind: "tool",
              title: `MCP · ${item.server || ""}/${item.tool || ""}`,
              detail: JSON.stringify(item.arguments || {}).slice(0, 300),
              timestampMs: Date.now(),
            });
          }
        } else if (method === "item/completed" && p.item) {
          const item = p.item as Record<string, any>;
          if (item.type === "agentMessage") {
            const text = String(item.text || "").trim();
            if (text) {
              pushAndEmitStep({
                stepIndex: state.steps.length + 1,
                kind: "assistant",
                title: "Codex 回复",
                detail: text.slice(0, 2400),
                timestampMs: Date.now(),
              });
            }
          } else if (item.type === "reasoning") {
            const parts = Array.isArray(item.summary) && item.summary.length > 0 ? item.summary : item.content;
            const text = Array.isArray(parts) ? parts.join("\n").trim() : String(parts || "").trim();
            if (text) {
              pushAndEmitStep({
                stepIndex: state.steps.length + 1,
                kind: "thinking",
                title: "思考推演",
                detail: text.slice(0, 500),
                timestampMs: Date.now(),
              });
            }
          } else if (item.type === "commandExecution") {
            const out = String(item.aggregatedOutput || item.command || "").trim();
            pushAndEmitStep({
              stepIndex: state.steps.length + 1,
              kind: "tool",
              title: `命令完成 (exit=${item.exitCode ?? 0})`,
              detail: out.slice(0, 400),
              timestampMs: Date.now(),
            });
          } else if (item.type === "fileChange" && Array.isArray(item.changes)) {
            const files = item.changes.map((c: any) => c?.path || "").filter(Boolean).join(", ");
            if (files) {
              pushAndEmitStep({
                stepIndex: state.steps.length + 1,
                kind: "tool",
                title: "代码修改",
                detail: files.slice(0, 360),
                timestampMs: Date.now(),
              });
            }
          }
        } else if (method === "turn/completed") {
          turnCompletedHandled = true;
          const turnErr = p.turn?.error;
          if (turnErr) {
            state.summary.status = "failed";
            state.summary.lastSummary = `Codex 执行中止: ${turnErr.message || JSON.stringify(turnErr).slice(0, 120)}`;
          } else {
            state.summary.status = "completed";
            const lastAssistant = [...state.steps].reverse().find((s) => s.kind === "assistant");
            if (lastAssistant) {
              state.summary.lastSummary = lastAssistant.detail.slice(0, 160);
            }
          }
          try { child.stdin?.end(); } catch {}
          setTimeout(() => {
            try { child.kill("SIGTERM"); } catch {}
          }, 180);
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
      if (handedOffToDesktopWake) return;
      state.child = null;
      state.summary.status = "failed";
      state.summary.lastSummary = `Codex 执行异常: ${err.message}`;
      state.summary.updatedAtMs = Date.now();
      this.persistTasks();
      this.emitStreamEvent({
        backend: "codex",
        sessionId: state.summary.sessionId,
        previousSessionId: lastPreviousSessionId,
        status: "failed",
        finalSummary: state.summary.lastSummary,
        updatedAtMs: state.summary.updatedAtMs,
      });
    });

    child.on("close", (code) => {
      state.child = null;
      if (handedOffToDesktopWake && activeThreadId && queuedWakeSubmissionId) {
        // Writer lock released — wake ChatGPT.app's live app-server to run the queued turn in-place!
        this.linkCodexThreadToWorkspaceProject(
          activeThreadId,
          opts.workspace,
          state.summary.title,
          false
        );
        this.wakeCodexDesktopThread(activeThreadId);
        this.watchWokenCodexDesktopTurn(
          state,
          activeThreadId,
          queuedWakeSubmissionId,
          wakeStartRolloutOffset,
          lastPreviousSessionId,
          { mode: opts.mode, workspace: opts.workspace, prompt: opts.prompt },
          pushAndEmitStep
        );
        this.persistTasks();
        return;
      }

      if (!turnCompletedHandled && state.summary.status === "running") {
        const ok = code === 0;
        state.summary.status = ok ? "completed" : "failed";
        if (!ok && stderrBuf.trim()) {
          state.summary.lastSummary = `退出码 ${code}: ${stderrBuf.trim().split("\n").pop()?.slice(0, 140)}`;
        }
      }
      if (activeThreadId) {
        this.linkCodexThreadToWorkspaceProject(
          activeThreadId,
          opts.workspace,
          state.summary.title,
          forkedFromLockedDesktop
        );
      }
      state.summary.updatedAtMs = Date.now();
      this.persistTasks();
      this.emitStreamEvent({
        backend: "codex",
        sessionId: state.summary.sessionId,
        previousSessionId: lastPreviousSessionId,
        status: state.summary.status === "completed" ? "completed" : "failed",
        finalSummary: state.summary.lastSummary,
        updatedAtMs: state.summary.updatedAtMs,
      });
    });

    // Kick off JSON-RPC handshake identifying as `vscode` so Codex Desktop (`ChatGPT.app`) displays the thread in its sidebar!
    sendRpc({
      id: 1,
      method: "initialize",
      params: {
        clientInfo: { name: "vscode", title: "Codex Desktop", version: "1.0.0" },
        capabilities: { experimentalApi: true },
      },
    });

    this.persistTasks();
    return { ...state.summary };
  }

  /**
   * Ensures a mobile-created, mobile-continued, or mobile-forked Codex thread has:
   * - a registered workspace project in `projects` + `project_roots` (`state_*.sqlite`)
   * - `source = 'vscode'`, `thread_source = 'user'`, `originator = 'Codex Desktop'`, matching `project_id`, and clean `name`
   * - `.codex-global-state.json` `local-projects` + `thread-project-assignments` when Codex Desktop is offline
   */
  public linkCodexThreadToWorkspaceProject(
    threadId: string,
    workspacePath: string,
    titleHint: string,
    forceNameUpdate = false
  ): void {
    const codexRoot = path.dirname(this.codexSessionsDir);
    if (!fs.existsSync(codexRoot)) return;
    const normWs = workspacePath.trim().replace(/\/+$/, "");
    const cleanTitle = titleHint.trim().slice(0, 120);
    const projDisplayName = path.basename(normWs) || "workspace";
    let sqliteProjId: string | null = null;

    // 1. Update `~/.codex/state_*.sqlite` (ensuring `projects` + `project_roots` entry exists!)
    try {
      const stateDbs = fs
        .readdirSync(codexRoot)
        .filter((n) => /^state_\d+\.sqlite$/i.test(n))
        .sort((a, b) => Number(b.match(/\d+/)?.[0] || 0) - Number(a.match(/\d+/)?.[0] || 0));
      if (stateDbs.length > 0) {
        const dbPath = path.join(codexRoot, stateDbs[0]);
        const db = new Database(dbPath);
        try {
          db.exec("PRAGMA busy_timeout = 1500;");
          if (normWs) {
            try {
              const row = db
                .query("SELECT project_id FROM project_roots WHERE rtrim(path, '/') = ? LIMIT 1")
                .get(normWs) as { project_id?: string } | null;
              sqliteProjId = row?.project_id || null;
              if (!sqliteProjId) {
                const pCols = new Set(
                  (db.query("PRAGMA table_info(projects)").all() as Array<{ name?: string }>).map((c) =>
                    String(c.name || "")
                  )
                );
                const rCols = new Set(
                  (db.query("PRAGMA table_info(project_roots)").all() as Array<{ name?: string }>).map((c) =>
                    String(c.name || "")
                  )
                );
                if (pCols.has("id") && pCols.has("name") && rCols.has("project_id") && rCols.has("path")) {
                  const newPid = crypto.randomUUID();
                  const nowMs = Date.now();
                  if (pCols.has("position") && pCols.has("created_at_ms") && pCols.has("updated_at_ms")) {
                    const maxPosRow = db.query("SELECT COALESCE(MAX(position), -1) + 1 AS nextPos FROM projects").get() as {
                      nextPos?: number;
                    } | null;
                    const nextPos = Number(maxPosRow?.nextPos ?? 0);
                    db.query(
                      "INSERT INTO projects (id, name, metadata, position, created_at_ms, updated_at_ms) VALUES (?, ?, '{}', ?, ?, ?)"
                    ).run(newPid, projDisplayName, nextPos, nowMs, nowMs);
                  } else {
                    db.query("INSERT INTO projects (id, name) VALUES (?, ?)").run(newPid, projDisplayName);
                  }
                  if (rCols.has("position")) {
                    db.query("INSERT INTO project_roots (project_id, position, path) VALUES (?, 0, ?)").run(newPid, normWs);
                  } else {
                    db.query("INSERT INTO project_roots (project_id, path) VALUES (?, ?)").run(newPid, normWs);
                  }
                  sqliteProjId = newPid;
                }
              }
            } catch {
              // projects table optional on minimal schemas
            }
          }

          const colRows = db.query("PRAGMA table_info(threads)").all() as Array<{ name?: string }>;
          const cols = new Set(colRows.map((c) => String(c.name || "")));

          const setClauses: string[] = [
            "source = CASE WHEN source = 'exec' OR COALESCE(source, '') = '' THEN 'vscode' ELSE source END",
          ];
          const params: any[] = [];

          if (cols.has("project_id")) {
            setClauses.push("project_id = COALESCE(project_id, ?)");
            params.push(sqliteProjId);
          }
          if (cols.has("title")) {
            setClauses.push("title = CASE WHEN COALESCE(title, '') = '' THEN ? ELSE title END");
            params.push(cleanTitle);
          }
          if (cols.has("name") && cleanTitle) {
            if (forceNameUpdate) {
              setClauses.push("name = ?");
              params.push(cleanTitle);
            } else {
              setClauses.push("name = CASE WHEN COALESCE(name, '') = '' THEN ? ELSE name END");
              params.push(cleanTitle);
            }
          }
          if (cols.has("thread_source")) {
            setClauses.push("thread_source = CASE WHEN COALESCE(thread_source, '') = '' THEN 'user' ELSE thread_source END");
          }
          if (cols.has("originator")) {
            setClauses.push(
              "originator = CASE WHEN originator IN ('vscode', 'exec', 'codex_exec') OR COALESCE(originator, '') = '' THEN 'Codex Desktop' ELSE originator END"
            );
          }

          params.push(threadId);
          db.query(`UPDATE threads SET ${setClauses.join(", ")} WHERE id = ?`).run(...params);
        } finally {
          db.close();
        }
      }
    } catch {
      // ignore if locked
    }

    // 2. Register project & thread membership in `~/.codex/.codex-global-state.json` when Codex Desktop is not actively owning it in RAM
    if (this.isCodexDesktopAppRunning()) return;
    try {
      const globalStatePath = path.join(codexRoot, ".codex-global-state.json");
      if (!fs.existsSync(globalStatePath) || !normWs) return;
      const raw = fs.readFileSync(globalStatePath, "utf8");
      const stateObj = JSON.parse(raw) as Record<string, any>;
      const localProjects =
        stateObj["local-projects"] && typeof stateObj["local-projects"] === "object"
          ? stateObj["local-projects"]
          : {};

      let matchedLocalProjId: string | null = null;
      let changed = false;
      for (const [lpId, lpVal] of Object.entries(localProjects)) {
        const roots = Array.isArray((lpVal as any)?.rootPaths) ? (lpVal as any).rootPaths : [];
        if (roots.some((r: unknown) => typeof r === "string" && r.trim().replace(/\/+$/, "") === normWs)) {
          matchedLocalProjId = lpId;
          break;
        }
      }

      if (!matchedLocalProjId) {
        const hashHex = crypto.createHash("md5").update(normWs).digest("hex");
        matchedLocalProjId = `local-${hashHex}`;
        const nowMs = Date.now();
        localProjects[matchedLocalProjId] = {
          id: matchedLocalProjId,
          name: projDisplayName,
          rootPaths: [normWs],
          createdAt: nowMs,
          updatedAt: nowMs,
        };
        stateObj["local-projects"] = localProjects;
        if (Array.isArray(stateObj["project-order"]) && !stateObj["project-order"].includes(matchedLocalProjId)) {
          stateObj["project-order"] = [matchedLocalProjId, ...stateObj["project-order"]];
        }
        if (sqliteProjId) {
          const mapByHost =
            stateObj["app-server-project-id-by-legacy-project-id-by-host"] &&
            typeof stateObj["app-server-project-id-by-legacy-project-id-by-host"] === "object"
              ? stateObj["app-server-project-id-by-legacy-project-id-by-host"]
              : {};
          const hostKey = `local:${codexRoot}`;
          mapByHost[hostKey] = { ...(mapByHost[hostKey] || {}), [matchedLocalProjId]: sqliteProjId };
          stateObj["app-server-project-id-by-legacy-project-id-by-host"] = mapByHost;
        }
        const owner = stateObj["electron-renderer-content-owner"];
        if (typeof owner === "string" && owner) {
          const wsOptKey = `electron-owner-value:electron-saved-workspace-roots:${owner}`;
          if (Array.isArray(stateObj[wsOptKey]) && !stateObj[wsOptKey].includes(normWs)) {
            stateObj[wsOptKey] = [...stateObj[wsOptKey], normWs];
          }
        }
        changed = true;
      }

      const assignments =
        stateObj["thread-project-assignments"] && typeof stateObj["thread-project-assignments"] === "object"
          ? stateObj["thread-project-assignments"]
          : {};
      const memberships =
        stateObj["thread-project-membership-host-ids"] &&
        typeof stateObj["thread-project-membership-host-ids"] === "object"
          ? stateObj["thread-project-membership-host-ids"]
          : {};

      if (!assignments[threadId]) {
        assignments[threadId] = { projectKind: "local", projectId: matchedLocalProjId };
        stateObj["thread-project-assignments"] = assignments;
        changed = true;
      }
      if (!memberships[threadId]) {
        memberships[threadId] = "local";
        stateObj["thread-project-membership-host-ids"] = memberships;
        changed = true;
      }
      if (changed) {
        const tmpPath = `${globalStatePath}.oms_${process.pid}_${Date.now()}.tmp`;
        fs.writeFileSync(tmpPath, JSON.stringify(stateObj), "utf8");
        fs.renameSync(tmpPath, globalStatePath);
      }
    } catch {
      // ignore global state race
    }
  }

  private copyDirRecursiveNewest(srcDir: string, dstDir: string): void {
    if (!fs.existsSync(srcDir)) return;
    fs.mkdirSync(dstDir, { recursive: true });
    for (const entry of fs.readdirSync(srcDir, { withFileTypes: true })) {
      const sPath = path.join(srcDir, entry.name);
      const dPath = path.join(dstDir, entry.name);
      if (entry.isDirectory()) {
        this.copyDirRecursiveNewest(sPath, dPath);
      } else if (entry.isFile()) {
        try {
          const sStat = fs.statSync(sPath);
          const dExists = fs.existsSync(dPath);
          if (!dExists || sStat.mtimeMs >= fs.statSync(dPath).mtimeMs || sStat.size !== fs.statSync(dPath).size) {
            fs.copyFileSync(sPath, dPath);
          }
        } catch {
          // ignore individual file race
        }
      }
    }
  }

  private snapshotSqliteDatabaseSafely(srcDbPath: string, dstDbPath: string): boolean {
    if (!fs.existsSync(srcDbPath)) return false;
    const dstDir = path.dirname(dstDbPath);
    fs.mkdirSync(dstDir, { recursive: true });
    const tmpPath = path.join(dstDir, `.${path.basename(dstDbPath)}.oms_${Date.now()}.tmp`);
    try {
      const escapedTmp = tmpPath.replace(/'/g, "''");
      const srcDb = new Database(srcDbPath, { readonly: true });
      try {
        srcDb.exec("PRAGMA busy_timeout = 1500;");
        srcDb.exec(`VACUUM INTO '${escapedTmp}';`);
      } finally {
        srcDb.close();
      }
      for (const sfx of ["-wal", "-shm"]) {
        try {
          if (fs.existsSync(dstDbPath + sfx)) fs.unlinkSync(dstDbPath + sfx);
        } catch {}
      }
      fs.renameSync(tmpPath, dstDbPath);
      return true;
    } catch {
      try {
        if (fs.existsSync(tmpPath)) fs.unlinkSync(tmpPath);
      } catch {}
      try {
        fs.copyFileSync(srcDbPath, dstDbPath);
        return true;
      } catch {
        return false;
      }
    }
  }

  /**
   * Resolves (or creates) an Antigravity project JSON in `<geminiRoot>/config/projects/<uuid>.json`
   * matching `workspacePath`, ensuring every mobile Antigravity conversation binds to a valid `projectId`
   * in `Antigravity.app`'s project-grouped sidebar.
   */
  public resolveOrCreateAgyProjectForWorkspace(workspacePath: string): string {
    const normWs = (workspacePath || this.defaultWorkspacePath).trim().replace(/\/+$/, "");
    if (!normWs || !path.isAbsolute(normWs)) return "";
    const targetUri = pathToFileURL(normWs).href.replace(/\/+$/, "");
    const desktopRoot = path.dirname(this.agyBrainDir);
    const geminiRoot = path.dirname(desktopRoot);
    const projectsDir = path.join(geminiRoot, "config", "projects");

    try {
      if (fs.existsSync(projectsDir)) {
        for (const f of fs.readdirSync(projectsDir)) {
          if (!f.endsWith(".json") || f === "default-cli-project.json" || f === "outside-of-project.json") {
            continue;
          }
          try {
            const parsed = JSON.parse(fs.readFileSync(path.join(projectsDir, f), "utf8"));
            const resources = Array.isArray(parsed?.projectResources?.resources)
              ? parsed.projectResources.resources
              : [];
            const matched = resources.some(
              (r: any) =>
                typeof r?.folderUri === "string" && r.folderUri.trim().replace(/\/+$/, "") === targetUri
            );
            if (matched && typeof parsed?.id === "string" && parsed.id) {
              return parsed.id;
            }
          } catch {}
        }
      }

      fs.mkdirSync(projectsDir, { recursive: true });
      const newProjId = crypto.randomUUID();
      const projDoc = {
        id: newProjId,
        name: path.basename(normWs) || "workspace",
        projectResources: {
          resources: [{ folderUri: targetUri }],
        },
        permissionGrants: {
          v2Migrated: true,
        },
        settings: {},
        isWorkspaceOnly: false,
      };
      const targetFile = path.join(projectsDir, `${newProjId}.json`);
      const tmpFile = `${targetFile}.oms_${process.pid}_${Date.now()}.tmp`;
      fs.writeFileSync(tmpFile, JSON.stringify(projDoc, null, 2) + "\n", "utf8");
      fs.renameSync(tmpFile, targetFile);
      return newProjId;
    } catch {
      return "";
    }
  }

  private encodeProtobufVarint(val: bigint | number): Buffer {
    let n = typeof val === "bigint" ? val : BigInt(Math.max(0, Math.floor(val)));
    const bytes: number[] = [];
    while (n >= 0x80n) {
      bytes.push(Number(n & 0x7fn) | 0x80);
      n >>= 7n;
    }
    bytes.push(Number(n));
    return Buffer.from(bytes);
  }

  private encodeProtobufBytesField(fieldNo: number, payload: Buffer | string): Buffer {
    const buf = typeof payload === "string" ? Buffer.from(payload, "utf8") : payload;
    const tag = this.encodeProtobufVarint((fieldNo << 3) | 2);
    const len = this.encodeProtobufVarint(buf.length);
    return Buffer.concat([tag, len, buf]);
  }

  private encodeAgyTrajectoryMetadataProto(opts: {
    conversationId: string;
    workspacePath: string;
    projectId: string;
    createdAtMs: number;
  }): Buffer {
    const normWs = opts.workspacePath.trim().replace(/\/+$/, "");
    const folderUri = pathToFileURL(normWs).href.replace(/\/+$/, "");
    const wsParts: Buffer[] = [this.encodeProtobufBytesField(1, folderUri)];
    if (fs.existsSync(path.join(normWs, ".git"))) {
      wsParts.push(this.encodeProtobufBytesField(2, folderUri));
    }
    const wsInfoMsg = Buffer.concat(wsParts);

    const secs = BigInt(Math.floor(opts.createdAtMs / 1000));
    const nanos = BigInt((opts.createdAtMs % 1000) * 1_000_000);
    const tsMsg = Buffer.concat([
      this.encodeProtobufVarint((1 << 3) | 0),
      this.encodeProtobufVarint(secs),
      ...(nanos > 0n
        ? [this.encodeProtobufVarint((2 << 3) | 0), this.encodeProtobufVarint(nanos)]
        : []),
    ]);

    const fields: Buffer[] = [
      this.encodeProtobufBytesField(1, wsInfoMsg),
      this.encodeProtobufBytesField(2, tsMsg),
      this.encodeProtobufBytesField(3, crypto.randomUUID()),
      this.encodeProtobufBytesField(6, opts.conversationId),
      this.encodeProtobufBytesField(7, folderUri),
    ];
    if (opts.projectId) {
      fields.push(this.encodeProtobufBytesField(18, opts.projectId));
    }
    return Buffer.concat(fields);
  }

  private normalizeAgyTrajectoryDatabase(
    dbPath: string,
    sourceConversationId: string,
    targetConversationId: string,
    workspacePath: string,
    projectId: string
  ): void {
    if (!fs.existsSync(dbPath)) return;
    const normWs = workspacePath.trim().replace(/\/+$/, "");
    const folderUri = normWs && path.isAbsolute(normWs) ? pathToFileURL(normWs).href.replace(/\/+$/, "") : "";

    const db = new Database(dbPath);
    try {
      db.exec("PRAGMA busy_timeout = 1500;");
      const tmCols = new Set(
        (db.query("PRAGMA table_info(trajectory_meta)").all() as Array<{ name?: string }>).map((c) =>
          String(c.name || "")
        )
      );
      if (tmCols.has("cascade_id") && tmCols.has("trajectory_type") && tmCols.has("source")) {
        db.query("UPDATE trajectory_meta SET cascade_id = ?, trajectory_type = 4, source = 1").run(
          targetConversationId
        );
      } else if (tmCols.has("trajectory_id") && targetConversationId !== sourceConversationId) {
        db.query("UPDATE trajectory_meta SET trajectory_id = ?").run(targetConversationId);
      }

      db.exec(
        'CREATE TABLE IF NOT EXISTS trajectory_metadata_blob (id TEXT DEFAULT "main", data BLOB, PRIMARY KEY (id));'
      );
      const existingRow = db
        .query("SELECT data FROM trajectory_metadata_blob WHERE id = 'main'")
        .get() as { data?: Uint8Array | null } | null;
      let blobBuf: Buffer = existingRow?.data ? Buffer.from(existingRow.data) : Buffer.alloc(0);

      if (
        blobBuf.length > 0 &&
        targetConversationId !== sourceConversationId &&
        sourceConversationId.length === targetConversationId.length
      ) {
        const replacedStr = blobBuf.toString("latin1").split(sourceConversationId).join(targetConversationId);
        blobBuf = Buffer.from(replacedStr, "latin1");
      }

      const hasWorkspace = folderUri ? blobBuf.includes(folderUri) : true;
      const hasProject = projectId ? blobBuf.includes(projectId) : true;
      if (blobBuf.length === 0 || !hasWorkspace || !hasProject) {
        blobBuf = this.encodeAgyTrajectoryMetadataProto({
          conversationId: targetConversationId,
          workspacePath: normWs || this.defaultWorkspacePath,
          projectId,
          createdAtMs: Date.now(),
        });
      }

      db.query("INSERT OR REPLACE INTO trajectory_metadata_blob (id, data) VALUES ('main', ?)").run(blobBuf);
    } finally {
      db.close();
    }
  }

  private readAgyAnnotationTitle(desktopRoot: string, conversationId: string): string {
    const pbtxtPath = path.join(desktopRoot, "annotations", `${conversationId}.pbtxt`);
    if (!fs.existsSync(pbtxtPath)) return "";
    try {
      const raw = fs.readFileSync(pbtxtPath, "utf8");
      const m = raw.match(/title:\s*"((?:\\.|[^"\\])*)"/);
      return m?.[1] ? m[1].replace(/\\"/g, '"').replace(/\\\\/g, "\\").trim() : "";
    } catch {
      return "";
    }
  }

  private ensureAgyAnnotationPbtxt(
    desktopRoot: string,
    conversationId: string,
    titleHint: string,
    overwriteExisting = false
  ): void {
    const cleanTitle = this.compactHumanTitle(titleHint) || titleHint.trim().slice(0, 64);
    if (!cleanTitle) return;
    const annDir = path.join(desktopRoot, "annotations");
    const pbtxtPath = path.join(annDir, `${conversationId}.pbtxt`);
    try {
      fs.mkdirSync(annDir, { recursive: true });
      if (fs.existsSync(pbtxtPath) && !overwriteExisting) {
        // Touch mtime so Antigravity's annotations watcher re-applies preserved Desktop title after CLI DB removal
        const nowDate = new Date();
        fs.utimesSync(pbtxtPath, nowDate, nowDate);
        return;
      }
      const escaped = cleanTitle
        .replace(/\\/g, "\\\\")
        .replace(/"/g, '\\"')
        .replace(/[\r\n]+/g, " ")
        .trim();
      const secs = Math.floor(Date.now() / 1000);
      const content = `title:"${escaped}" last_user_view_time:{seconds:${secs} nanos:0}`;
      const tmp = `${pbtxtPath}.oms_${process.pid}_${Date.now()}.tmp`;
      fs.writeFileSync(tmp, content, "utf8");
      fs.renameSync(tmp, pbtxtPath);
    } catch {
      // ignore annotation write race
    }
  }

  private removeAgyCliDuplicateDb(cliDbPath: string): void {
    for (const sfx of ["", "-wal", "-shm"]) {
      try {
        if (fs.existsSync(cliDbPath + sfx)) {
          fs.unlinkSync(cliDbPath + sfx);
        }
      } catch {
        // ignore
      }
    }
  }

  private isFileHeldOpenByProcess(filePath: string): boolean {
    if (!fs.existsSync(filePath) || process.env.OMS_TEST_ISOLATION === "1") return false;
    try {
      const candidates = [filePath];
      if (fs.existsSync(filePath + "-wal")) candidates.push(filePath + "-wal");
      const r = spawnSync("lsof", ["-t", ...candidates], { encoding: "utf8", timeout: 1500 });
      return r.status === 0 && (r.stdout || "").trim().length > 0;
    } catch {
      return false;
    }
  }

  /**
   * Before running `agy --conversation <id>`, ensures Desktop conversations living in
   * `~/.gemini/antigravity/{conversations,brain}` are mirrored into `~/.gemini/antigravity-cli/{conversations,brain}`
   * (capturing live `.db-wal` frames via `VACUUM INTO`) so `agy` CLI never fails with `trajectory not found`.
   */
  public prepareAgySessionForCliResume(conversationId: string): void {
    const desktopRoot = path.dirname(this.agyBrainDir);
    const desktopDb = path.join(desktopRoot, "conversations", `${conversationId}.db`);
    const cliDb = path.join(this.agyCliDir, "conversations", `${conversationId}.db`);

    if (fs.existsSync(desktopDb)) {
      const desktopWal = `${desktopDb}-wal`;
      const effectiveDesktopMtime = Math.max(
        fs.statSync(desktopDb).mtimeMs,
        fs.existsSync(desktopWal) ? fs.statSync(desktopWal).mtimeMs : 0
      );
      const needSync =
        !fs.existsSync(cliDb) || effectiveDesktopMtime >= fs.statSync(cliDb).mtimeMs;
      if (needSync) {
        this.snapshotSqliteDatabaseSafely(desktopDb, cliDb);
      }
    }

    const desktopBrain = path.join(this.agyBrainDir, conversationId);
    const cliBrain = path.join(this.agyCliDir, "brain", conversationId);
    if (fs.existsSync(desktopBrain)) {
      this.copyDirRecursiveNewest(desktopBrain, cliBrain);
    }
  }

  /**
   * After `agy` finishes (either `startSession` or `continueSession`), promotes the conversation
   * from `~/.gemini/antigravity-cli/` into `~/.gemini/antigravity/`:
   * - Binds valid `projectId` + `workspaceUris` + Desktop `trajectory_meta` (`trajectory_type = 4, source = 1`)
   * - Preserves or creates `annotations/<id>.pbtxt` so `Antigravity.app` displays the human title
   * - Forks cleanly to a fresh Desktop UUID if `<id>.db` is currently held open in an active `Antigravity.app` pane
   * - Removes the duplicate `<id>.db` from `antigravity-cli/conversations/` so `Antigravity.app`'s CLI directory watcher
   *   never overrides `source` back to `CORTEX_TRAJECTORY_SOURCE_CLI`!
   */
  public syncAgyCliSessionToDesktop(
    knownSessionId: string,
    startedAtMs: number,
    workspacePath: string,
    titleHint: string,
    forceDesktopFork = false
  ): string {
    let realId = knownSessionId;
    const cliConvDir = path.join(this.agyCliDir, "conversations");

    if ((!realId || realId.startsWith("pending_")) && fs.existsSync(cliConvDir)) {
      try {
        const recent = fs
          .readdirSync(cliConvDir)
          .filter((f) => f.endsWith(".db") && !f.startsWith("."))
          .map((f) => {
            const full = path.join(cliConvDir, f);
            return { id: path.basename(f, ".db"), mtimeMs: fs.statSync(full).mtimeMs };
          })
          .filter((x) => x.mtimeMs >= startedAtMs - 3000)
          .sort((a, b) => b.mtimeMs - a.mtimeMs);
        if (recent.length > 0) {
          realId = recent[0].id;
        }
      } catch {}
    }

    if (!realId || realId.startsWith("pending_")) return knownSessionId;

    const normWs = this.resolveWorkspace(workspacePath);
    const agyProjectId = this.resolveOrCreateAgyProjectForWorkspace(normWs);
    const desktopRoot = path.dirname(this.agyBrainDir);
    const cliBrain = path.join(this.agyCliDir, "brain", realId);
    const origDesktopBrain = path.join(this.agyBrainDir, realId);
    const cliDb = path.join(cliConvDir, `${realId}.db`);
    const desktopConvDir = path.join(desktopRoot, "conversations");
    const origDesktopDb = path.join(desktopConvDir, `${realId}.db`);

    const lockedByDesktop = forceDesktopFork || this.isFileHeldOpenByProcess(origDesktopDb);
    const finalId = lockedByDesktop && fs.existsSync(origDesktopDb) ? crypto.randomUUID() : realId;
    const targetDesktopDb = path.join(desktopConvDir, `${finalId}.db`);
    const targetDesktopBrain = path.join(this.agyBrainDir, finalId);

    if (finalId !== realId && fs.existsSync(origDesktopBrain)) {
      this.copyDirRecursiveNewest(origDesktopBrain, targetDesktopBrain);
    }
    if (fs.existsSync(cliBrain)) {
      this.copyDirRecursiveNewest(cliBrain, targetDesktopBrain);
    }

    const baseTitle =
      this.readAgyAnnotationTitle(desktopRoot, realId) ||
      this.compactHumanTitle(titleHint) ||
      titleHint.trim().slice(0, 64) ||
      `Antigravity · ${path.basename(normWs)}`;

    if (fs.existsSync(cliDb)) {
      try {
        this.normalizeAgyTrajectoryDatabase(cliDb, realId, finalId, normWs, agyProjectId);
      } catch {
        // ignore schema edge cases on minimal mock DBs
      }
      const snapOk = this.snapshotSqliteDatabaseSafely(cliDb, targetDesktopDb);
      if (snapOk) {
        this.removeAgyCliDuplicateDb(cliDb);
      }
    }

    if (finalId !== realId) {
      this.ensureAgyAnnotationPbtxt(
        desktopRoot,
        finalId,
        this.formatMobileForkTitle(baseTitle, "Antigravity 会话"),
        true
      );
    } else {
      this.ensureAgyAnnotationPbtxt(desktopRoot, finalId, baseTitle, false);
    }

    // Also update conversation_summaries.db when unlocked so offline/test metadata is immediate
    const summaryDbPath = path.join(desktopRoot, "conversation_summaries.db");
    if (fs.existsSync(summaryDbPath) && !this.isFileHeldOpenByProcess(summaryDbPath)) {
      try {
        const db = new Database(summaryDbPath);
        try {
          db.exec("PRAGMA busy_timeout = 1200;");
          const wsUri = JSON.stringify([pathToFileURL(normWs).href.replace(/\/+$/, "")]);
          const nowIso = new Date().toISOString().replace("T", " ").replace("Z", "+00:00");
          const sumCols = new Set(
            (db.query("PRAGMA table_info(conversation_summaries)").all() as Array<{ name?: string }>).map(
              (c) => String(c.name || "")
            )
          );
          const extraClauses = [
            sumCols.has("project_id")
              ? "project_id = CASE WHEN COALESCE(project_id, '') = '' THEN ? ELSE project_id END"
              : "",
            sumCols.has("source") ? "source = ''" : "",
          ].filter(Boolean);
          const sql = `UPDATE conversation_summaries
             SET last_modified_time = ?,
                 workspace_uris = CASE WHEN COALESCE(workspace_uris, '') IN ('', '[]') THEN ? ELSE workspace_uris END,
                 title = CASE WHEN COALESCE(title, '') = '' THEN ? ELSE title END
                 ${extraClauses.length > 0 ? ", " + extraClauses.join(", ") : ""}
             WHERE conversation_id = ?`;
          const runArgs: any[] = [nowIso, wsUri, baseTitle.slice(0, 80)];
          if (sumCols.has("project_id")) runArgs.push(agyProjectId);
          runArgs.push(finalId);
          db.query(sql).run(...runArgs);
        } finally {
          db.close();
        }
      } catch {
        // Antigravity Desktop's own summaries_store directory watcher reconciles automatically when running
      }
    }

    return finalId;
  }

  /**
   * Idempotently repairs historical mobile-created & mobile-continued sessions so both
   * `ChatGPT.app` (Codex Desktop) and `Antigravity.app` surface previously orphaned sessions immediately.
   */
  public reconcileDesktopVisibility(): { repairedCodex: number; repairedAgy: number } {
    let repairedCodex = 0;
    let repairedAgy = 0;
    let tasksDirty = false;

    const desktopRoot = path.dirname(this.agyBrainDir);
    const desktopConvDir = path.join(desktopRoot, "conversations");
    const cliConvDir = path.join(this.agyCliDir, "conversations");

    // 1. Reconcile tracked mobile tasks (Codex projects/originators/fork titles + Antigravity CLI->Desktop promotion + [object Object] cleanup)
    for (const [, st] of this.managedTasks) {
      const s = st.summary;
      if (!s.sessionId || s.sessionId.startsWith("pending_")) continue;

      if (s.backend === "codex") {
        let isForked = false;
        const rp = this.findCodexRolloutFilePath(s.sessionId);
        if (rp && fs.existsSync(rp)) {
          try {
            const firstLine = this.readJsonlBoundedLines(rp, 16 * 1024, 0)[0];
            if (firstLine) {
              const meta = JSON.parse(firstLine);
              if (meta?.type === "session_meta" && meta?.payload?.forked_from_id) {
                isForked = true;
              }
            }
          } catch {}
        }
        if (isForked && !/[（(]手机接续[）)]$/u.test(s.title)) {
          s.title = this.formatMobileForkTitle(s.title, "Codex 会话");
          tasksDirty = true;
        }
        this.linkCodexThreadToWorkspaceProject(s.sessionId, s.workspacePath, s.title, isForked);
        repairedCodex += 1;
      } else if (s.backend === "agy") {
        const syncedId = this.syncAgyCliSessionToDesktop(
          s.sessionId,
          0,
          s.workspacePath,
          s.title
        );
        if (syncedId && syncedId !== s.sessionId) {
          this.sessionAliases.set(`agy:${s.sessionId}`, syncedId);
          s.sessionId = syncedId;
          tasksDirty = true;
        }
        // Clean legacy `[object Object]` step/summary artifacts from disk transcript
        if (
          s.lastSummary.includes("[object Object]") ||
          st.steps.some((step) => step.detail.includes("[object Object]"))
        ) {
          const disk = this.readAgySessionDetail(s.sessionId);
          if (disk && disk.steps.length > 0) {
            st.steps = disk.steps;
            s.lastSummary = disk.session.lastSummary;
            tasksDirty = true;
          }
        }
        repairedAgy += 1;
      }
    }

    // 2. Also remove any leftover duplicate `<id>.db` in `antigravity-cli/conversations/` that already exists in `antigravity/conversations/`
    if (fs.existsSync(cliConvDir) && fs.existsSync(desktopConvDir)) {
      try {
        for (const f of fs.readdirSync(cliConvDir)) {
          if (!f.endsWith(".db") || f.startsWith(".")) continue;
          const convId = path.basename(f, ".db");
          const dDb = path.join(desktopConvDir, f);
          const cDb = path.join(cliConvDir, f);
          if (!fs.existsSync(dDb)) continue;
          const disk = this.readAgySessionDetail(convId);
          const ws = disk?.session.workspacePath || this.defaultWorkspacePath;
          const title = disk?.session.title || "";
          this.syncAgyCliSessionToDesktop(convId, 0, ws, title);
          if (!fs.existsSync(cDb)) repairedAgy += 1;
        }
      } catch {}
    }

    if (tasksDirty) {
      this.persistTasks();
    }
    return { repairedCodex, repairedAgy };
  }

  private launchManagedProcess(
    initialSummary: AgentSessionSummary,
    initialSteps: AgentStepItem[],
    spec: SpawnedCliSpec
  ): AgentSessionSummary {
    const startedAtMs = Date.now();
    let currentKey = `${initialSummary.backend}:${initialSummary.sessionId}`;
    const prev = this.managedTasks.get(currentKey);
    if (prev?.child || prev?.cancelHook) {
      throw new Error(`该会话正在运行中 (${initialSummary.sessionId})，请等待当前轮次完成或先停止`);
    }

    const state: ManagedTaskState = {
      summary: initialSummary,
      steps: initialSteps,
      child: null,
      cancelHook: null,
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

    let lastPreviousSessionId: string | undefined;
    const rekeySessionId = (realId: string) => {
      const trimmed = realId.trim();
      if (!trimmed || trimmed === state.summary.sessionId) return;
      const oldId = state.summary.sessionId;
      lastPreviousSessionId = oldId;
      this.sessionAliases.set(`${state.summary.backend}:${oldId}`, trimmed);
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
            previousSessionId: lastPreviousSessionId,
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
        previousSessionId: lastPreviousSessionId,
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
      if (state.summary.backend === "agy" && !this.hasCustomCliSpawner) {
        const beforeSyncId = state.summary.sessionId;
        const syncedId = this.syncAgyCliSessionToDesktop(
          state.summary.sessionId,
          startedAtMs,
          state.summary.workspacePath,
          state.summary.title
        );
        if (syncedId && syncedId !== beforeSyncId) {
          if (!beforeSyncId.startsWith("pending_")) {
            state.summary.title = this.formatMobileForkTitle(state.summary.title, "Antigravity 会话");
          }
          rekeySessionId(syncedId);
        }
      } else if (state.summary.backend === "codex" && !this.hasCustomCliSpawner && !state.summary.sessionId.startsWith("pending_")) {
        this.linkCodexThreadToWorkspaceProject(
          state.summary.sessionId,
          state.summary.workspacePath,
          state.summary.title
        );
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
        previousSessionId: lastPreviousSessionId,
        status: state.summary.status === "completed" ? "completed" : "failed",
        finalSummary: state.summary.lastSummary,
        updatedAtMs: state.summary.updatedAtMs,
      });
    });

    this.persistTasks();
    return { ...state.summary };
  }

  private extractAgyStreamText(obj: Record<string, any>): string {
    const unwrapVal = (val: unknown): string => {
      if (typeof val === "string") return val.trim();
      if (Array.isArray(val)) {
        return val
          .map((item) => {
            if (typeof item === "string") return item;
            if (item && typeof item === "object" && typeof (item as any).text === "string") {
              return (item as any).text;
            }
            return "";
          })
          .filter(Boolean)
          .join("\n")
          .trim();
      }
      if (val && typeof val === "object") {
        const rec = val as Record<string, unknown>;
        for (const k of ["text", "content", "result", "response", "output"]) {
          if (rec[k] !== undefined && rec[k] !== val) {
            const inner = unwrapVal(rec[k]);
            if (inner) return inner;
          }
        }
      }
      return "";
    };

    for (const candidate of [obj.content, obj.text, obj.message, obj.result]) {
      const extracted = unwrapVal(candidate);
      if (extracted) return extracted;
    }
    return "";
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
        obj.sessionID ||
        obj.sessionId ||
        obj.conversation_id ||
        obj.conversationId ||
        obj.part?.sessionID ||
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
        const text = this.extractAgyStreamText(obj);
        if (
          text &&
          (type.toLowerCase().includes("assistant") ||
            type.toUpperCase().includes("RESULT") ||
            type.toUpperCase().includes("PLANNER") ||
            type === "message" ||
            type === "text" ||
            !type)
        ) {
          const replyTitle =
            backend === "workbuddy"
              ? "🛠️ WorkBuddy 回复"
              : backend === "opencode"
                ? "⚡ OpenCode 回复"
                : "🌌 Antigravity 回复";
          return {
            stepIndex: nextIndex,
            kind: "assistant",
            title: replyTitle,
            detail: text.slice(0, 1200),
            timestampMs: now,
          };
        }
        // OpenCode `--format json` part event (`obj.part`)
        if (obj.part && typeof obj.part === "object") {
          const pType = String(obj.part.type || "");
          if (pType === "reasoning" && typeof obj.part.text === "string" && obj.part.text.trim()) {
            return {
              stepIndex: nextIndex,
              kind: "thinking",
              title: "💭 OpenCode 思考",
              detail: obj.part.text.trim().slice(0, 500),
              timestampMs: now,
            };
          }
          if ((pType === "tool" || pType === "tool-invocation") && obj.part.tool) {
            return {
              stepIndex: nextIndex,
              kind: "tool",
              title: "🔧 调用工具",
              detail: String(obj.part.tool || "tool").slice(0, 240),
              timestampMs: now,
            };
          }
          if (pType === "text" && typeof obj.part.text === "string" && obj.part.text.trim()) {
            return {
              stepIndex: nextIndex,
              kind: "assistant",
              title: "⚡ OpenCode 回复",
              detail: obj.part.text.trim().slice(0, 1200),
              timestampMs: now,
            };
          }
        }
      }
      return null;
    } catch {
      // Plain text line fallback
      const plainTitle =
        backend === "codex"
          ? "🤖 Codex"
          : backend === "workbuddy"
            ? "🛠️ WorkBuddy"
            : backend === "opencode"
              ? "⚡ OpenCode"
              : "🌌 Antigravity";
      return {
        stepIndex: nextIndex,
        kind: "assistant",
        title: plainTitle,
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
    tailBytes: number,
    preserveMiddleDialogue = false
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
      const middleDialogueLines: string[] = [];

      // When loading full conversation history (`includeSteps = true`), scan the skipped middle gap
      // in bounded 512KB chunks (up to 32MB) keeping ONLY user/assistant message lines so memory stays tiny!
      if (preserveMiddleDialogue && tailOffset > consumedHeadEnd && tailOffset - consumedHeadEnd <= 32 * 1024 * 1024) {
        const chunkBuf = Buffer.allocUnsafe(512 * 1024);
        let carry = "";
        let offset = consumedHeadEnd;
        let droppedFirstFragment = false;
        while (offset < tailOffset) {
          const toRead = Math.min(chunkBuf.length, tailOffset - offset);
          const n = fs.readSync(fd, chunkBuf, 0, toRead, offset);
          if (n <= 0) break;
          offset += n;
          const text = carry + chunkBuf.subarray(0, n).toString("utf8");
          const parts = text.split(/\r?\n/);
          carry = parts.pop() || "";
          for (const line of parts) {
            if (!droppedFirstFragment) {
              droppedFirstFragment = true;
              if (!line.startsWith("{")) continue;
            }
            if (
              line.includes('"USER_INPUT"') ||
              line.includes('"user_message"') ||
              line.includes('"agent_message"') ||
              (line.includes('"PLANNER_RESPONSE"') && line.includes('"content":"') && !line.includes('"content":""')) ||
              (line.includes('"type":"message"') && (line.includes('"role":"user"') || line.includes('"role":"assistant"')))
            ) {
              middleDialogueLines.push(line);
            }
          }
        }
      }

      const actualTailLen = Math.max(0, st.size - tailOffset);
      if (actualTailLen === 0) {
        return [...headLines, ...middleDialogueLines].filter(Boolean);
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
      for (const l of middleDialogueLines) {
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
        const db = this.openSqliteReadonlyWithWal(dbPath);
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
        const db = this.openSqliteReadonlyWithWal(dbPath);
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
        includeSteps ? 1536 * 1024 : 384 * 1024,
        includeSteps ? 4096 * 1024 : 384 * 1024,
        includeSteps
      );
      const fileMatch = path.basename(filePath).match(/([0-9a-f]{8}-[0-9a-f-]{27})/i);
      let sessionId = fileMatch ? fileMatch[1] : path.basename(filePath, ".jsonl");
      let workspacePath = this.defaultWorkspacePath;
      let title = "";
      let lastSummary = "";
      let lastPushedUser = "";
      let lastPushedAssistant = "";
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
            if (includeSteps && msg && msg !== lastPushedUser) {
              lastPushedUser = msg;
              steps.push({
                stepIndex: steps.length + 1,
                kind: "user",
                title: "用户指令",
                detail: msg.slice(0, 1200),
                timestampMs: ts,
              });
            }
          } else if (pt === "agent_message" && obj.payload.message) {
            const msg = String(obj.payload.message).trim();
            lastSummary = msg.replace(/\s+/g, " ").slice(0, 140);
            if (includeSteps && msg && msg !== lastPushedAssistant) {
              lastPushedAssistant = msg;
              steps.push({
                stepIndex: steps.length + 1,
                kind: "assistant",
                title: "Codex",
                detail: msg.slice(0, 2400),
                timestampMs: ts,
              });
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
          if ((p.type === "function_call" || p.type === "custom_tool_call") && includeSteps) {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "tool",
              title: `${p.name || p.type || "tool"}`,
              detail: String(p.arguments || p.input || "").slice(0, 300),
              timestampMs: ts,
            });
          } else if (p.type === "message" && p.role === "user" && Array.isArray(p.content)) {
            const userTexts: string[] = [];
            for (const c of p.content) {
              if (typeof c?.text !== "string") continue;
              const cleaned = this.cleanBoilerplatePrompt(c.text);
              if (cleaned) userTexts.push(cleaned);
            }
            const combinedUser = userTexts.join("\n").trim();
            if (combinedUser) {
              if (!title) title = combinedUser.slice(0, 64);
              if (includeSteps && combinedUser !== lastPushedUser) {
                lastPushedUser = combinedUser;
                steps.push({
                  stepIndex: steps.length + 1,
                  kind: "user",
                  title: "用户指令",
                  detail: combinedUser.slice(0, 1200),
                  timestampMs: ts,
                });
              }
            }
          } else if (p.type === "message" && p.role === "assistant" && Array.isArray(p.content)) {
            const assistantTexts = p.content
              .filter((c: any) => typeof c?.text === "string" && c.text.trim().length > 0)
              .map((c: any) => String(c.text).trim());
            const combinedAssistant = assistantTexts.join("\n\n").trim();
            if (combinedAssistant) {
              lastSummary = combinedAssistant.replace(/\s+/g, " ").slice(0, 140);
              if (includeSteps && combinedAssistant !== lastPushedAssistant) {
                lastPushedAssistant = combinedAssistant;
                steps.push({
                  stepIndex: steps.length + 1,
                  kind: "assistant",
                  title: "Codex",
                  detail: combinedAssistant.slice(0, 2400),
                  timestampMs: ts,
                });
              }
            }
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
      return { session: summary, steps: includeSteps ? this.compactTrajectorySteps(steps) : [] };
    } catch {
      return null;
    }
  }

  private getAgyBrainSearchDirs(): string[] {
    const dirs = [this.agyBrainDir];
    const cliBrain = path.join(this.agyCliDir, "brain");
    const isDefaultHome =
      process.env.OMS_TEST_ISOLATION !== "1" &&
      path.resolve(this.agyBrainDir) ===
        path.resolve(path.join(os.homedir(), ".gemini", "antigravity", "brain"));
    if ((isDefaultHome || !this.hasCustomCliSpawner) && path.resolve(cliBrain) !== path.resolve(this.agyBrainDir)) {
      if (fs.existsSync(cliBrain)) dirs.push(cliBrain);
    }
    return dirs;
  }

  private scanAgySessions(maxCount: number): AgentSessionSummary[] {
    const agyMetaMap = this.loadAgySummaryMetadata();
    const brainDirs = this.getAgyBrainSearchDirs();
    if (brainDirs.every((d) => !fs.existsSync(d)) && agyMetaMap.size === 0) return [];

    const bestByConvId = new Map<string, { convId: string; transcriptPath: string; mtimeMs: number; size: number }>();
    for (const bDir of brainDirs) {
      if (!fs.existsSync(bDir)) continue;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(bDir, { withFileTypes: true });
      } catch {
        continue;
      }
      for (const e of entries) {
        if (!e.isDirectory() || e.name.startsWith(".")) continue;
        const meta = agyMetaMap.get(e.name);
        if (meta && (meta.killed || meta.nestingDepth > 0)) {
          continue;
        }
        const tp = path.join(bDir, e.name, ".system_generated", "logs", "transcript.jsonl");
        if (fs.existsSync(tp)) {
          try {
            const st = fs.statSync(tp);
            const prev = bestByConvId.get(e.name);
            if (!prev || st.mtimeMs >= prev.mtimeMs) {
              bestByConvId.set(e.name, { convId: e.name, transcriptPath: tp, mtimeMs: st.mtimeMs, size: st.size });
            }
          } catch {
            // ignore
          }
        }
      }
    }

    const candidates = Array.from(bestByConvId.values()).sort((a, b) => b.mtimeMs - a.mtimeMs);
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
    const annTitle = this.readAgyAnnotationTitle(path.dirname(this.agyBrainDir), session.sessionId);
    const preferredTitle =
      meta?.title?.trim() ||
      annTitle ||
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
    let bestTp = "";
    let bestMtimeMs = -1;
    for (const bDir of this.getAgyBrainSearchDirs()) {
      const candidate = path.join(bDir, conversationId, ".system_generated", "logs", "transcript.jsonl");
      if (fs.existsSync(candidate)) {
        try {
          const st = fs.statSync(candidate);
          if (st.mtimeMs >= bestMtimeMs) {
            bestMtimeMs = st.mtimeMs;
            bestTp = candidate;
          }
        } catch {}
      }
    }
    if (!bestTp) return null;
    try {
      const detail = this.parseAgyTranscript(conversationId, bestTp, bestMtimeMs, true);
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
        includeSteps ? 1536 * 1024 : 384 * 1024,
        includeSteps ? 4096 * 1024 : 384 * 1024,
        includeSteps
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
                detail: cleanUser.slice(0, 1200),
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
                detail: content.trim().slice(0, 2400),
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
        steps: includeSteps ? this.compactTrajectorySteps(steps) : [],
      };
    } catch {
      return null;
    }
  }

  // ===========================================================================
  // Quota Remaining & Reset Timestamp Collectors (Codex + Antigravity)
  // ===========================================================================

  private withQuotaFreshness(status: AgentBackendQuotaStatus): AgentBackendQuotaStatus {
    const now = Date.now();
    const fresh = status.capturedAtMs > 0 && status.capturedAtMs <= now + 60_000 && now - status.capturedAtMs <= 15 * 60_000;
    const windows = fresh ? status.windows.filter(w => !w.resetsAtMs || w.resetsAtMs > now) : [];
    return { ...status, available: windows.length > 0, windows,
      creditsSummary: fresh ? status.creditsSummary : undefined,
      statusNote: windows.length ? "本地近期采样（非官方实时额度）" : "配额样本已过期，当前额度未知" };
  }

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
      return this.withQuotaFreshness(this.cachedCodexQuota.status);
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
            // A copied/touched log file is not a new quota sample.
            const evMs = Date.parse(String(obj.timestamp || ""));
            if (!Number.isFinite(evMs) || evMs <= 0 || evMs > Date.now() + 60_000) continue;
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
        : "本地近期采样（非官方实时额度）",
      capturedAtMs: Math.floor(bestAtMs || Date.now()),
    };
    this.cachedCodexQuota = { fingerprint, status };
    return this.withQuotaFreshness(status);
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
    let quotaHitError = "";
    const now = Date.now();
    const startOfToday = new Date();
    startOfToday.setHours(0, 0, 0, 0);
    const todayFloorMs = startOfToday.getTime();

    // 1. Read sanctioned CLI log / settings for active model label and recent 429/quota errors
    const cliLogPath = path.join(this.agyCliDir, "cli.log");
    if (fs.existsSync(cliLogPath)) {
      try {
        const logStat = fs.statSync(cliLogPath);
        const tailLines = this.readJsonlBoundedLines(cliLogPath, 4096, 96 * 1024);
        for (let i = tailLines.length - 1; i >= 0; i--) {
          const line = tailLines[i];
          if (!activeModel) {
            const m = line.match(/selected model override to backend:\s*label="([^"]+)"/);
            if (m?.[1]) activeModel = m[1];
          }
          if (!quotaHitError && now - logStat.mtimeMs <= 6 * 3600_000) {
            if (/RESOURCE_EXHAUSTED|quota.?exceeded|rate.?limit.?exceeded|too many requests/i.test(line)) {
              const resetMatch = line.match(/(?:reset|retry|try again)[^0-9]*(\d+\s*(?:m|min|h|hr|hour|s|sec))/i);
              quotaHitError = resetMatch?.[1]
                ? `近期触发配额上限（预计 ${resetMatch[1]} 后冷却恢复）`
                : "近期触发速率/配额上限，请稍候冷却";
            }
          }
        }
      } catch {}
    }
    if (!activeModel && isDefaultHome) {
      const cliSettingsPath = path.join(this.agyCliDir, "settings.json");
      if (fs.existsSync(cliSettingsPath)) {
        try {
          const s = JSON.parse(fs.readFileSync(cliSettingsPath, "utf8"));
          if (typeof s?.model === "string") activeModel = s.model;
        } catch {}
      }
    }

    // 2. Aggregate live today / 7-day step & token telemetry across BOTH Desktop & CLI brain directories
    let todaySteps = 0;
    let weekConvos = 0;
    let totalInputTok = 0;
    let totalCacheTok = 0;
    let totalOutputTok = 0;
    let latestStepAtMs = 0;

    const brainRoots = [this.agyBrainDir];
    const cliBrainDir = path.join(this.agyCliDir, "brain");
    if (path.resolve(cliBrainDir) !== path.resolve(this.agyBrainDir)) {
      brainRoots.push(cliBrainDir);
    }

    const dedupByConv = new Map<string, { tp: string; mtimeMs: number }>();
    for (const root of brainRoots) {
      if (!fs.existsSync(root)) continue;
      try {
        const entries = fs.readdirSync(root, { withFileTypes: true });
        for (const e of entries) {
          if (!e.isDirectory() || e.name.startsWith(".")) continue;
          const tp = path.join(root, e.name, ".system_generated", "logs", "transcript.jsonl");
          if (!fs.existsSync(tp)) continue;
          try {
            const st = fs.statSync(tp);
            if (now - st.mtimeMs <= 7 * 86400_000) {
              const prev = dedupByConv.get(e.name);
              if (!prev || st.mtimeMs > prev.mtimeMs) {
                dedupByConv.set(e.name, { tp, mtimeMs: st.mtimeMs });
              }
            }
          } catch {}
        }
      } catch {
        // ignore
      }
    }

    weekConvos = dedupByConv.size;
    const recentTranscripts = Array.from(dedupByConv.values()).sort((a, b) => b.mtimeMs - a.mtimeMs);
    for (const item of recentTranscripts.slice(0, 20)) {
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
          if (obj?.status === "ERROR" && ts >= now - 6 * 3600_000 && !quotaHitError) {
            const c = String(obj?.content || "");
            if (/RESOURCE_EXHAUSTED|quota.?exceeded|rate.?limit/i.test(c)) {
              quotaHitError = "近期触发速率/配额上限，请稍候冷却";
            }
          }
        } catch {}
      }
    }

    const totalPrompt = totalInputTok + totalCacheTok;
    const cacheHitPct = totalPrompt > 0 ? Math.round((totalCacheTok / totalPrompt) * 100) : 0;
    const outTokLabel =
      totalOutputTok >= 1000 ? `${(totalOutputTok / 1000).toFixed(1)}k` : `${totalOutputTok}`;
    const activitySummary =
      todaySteps > 0
        ? totalOutputTok > 0
          ? `今日 ${todaySteps} 轮推理 · 输出 ${outTokLabel} tok · 缓存命中 ${cacheHitPct}% · 近 7 日 ${weekConvos} 会话`
          : `今日 ${todaySteps} 轮推理 · 近 7 日活跃 ${weekConvos} 个会话`
        : weekConvos > 0
          ? `近 7 日活跃 ${weekConvos} 个会话 · 今日尚未消耗`
          : "暂无近期推理消耗";

    const status: AgentBackendQuotaStatus = {
      backend: "agy",
      available: true,
      planLabel: "Google AI · Antigravity",
      activeModel: activeModel || "Gemini",
      windows: [],
      creditsSummary: activitySummary,
      statusNote:
        quotaHitError ||
        "运行正常 · 动态云端配额（官方本地未开放剩余百分比接口）",
      capturedAtMs: Math.floor(latestStepAtMs || now),
    };
    this.cachedAgyQuota = { fetchedAtMs: now, status };
    return status;
  }

  private resolveWorkBuddyCliCommand(): string {
    if (this.hasCustomCliSpawner) return "codebuddy";
    const appBundled = "/Applications/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy";
    if (fs.existsSync(appBundled)) return appBundled;
    return "codebuddy";
  }

  private resolveOpenCodeCliCommand(): string {
    if (this.hasCustomCliSpawner) return "opencode";
    const homeBin = path.join(os.homedir(), ".opencode", "bin", "opencode");
    if (fs.existsSync(homeBin)) return homeBin;
    return "opencode";
  }

  private stripWorkBuddySystemReminder(raw: string): string {
    return String(raw || "")
      .replace(/<system-reminder[\s\S]*?<\/system-reminder>/gi, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  private scanWorkBuddySessions(limit: number): AgentSessionSummary[] {
    const dbPath = path.join(this.workbuddyDir, "workbuddy.db");
    if (!fs.existsSync(dbPath)) return [];
    let db: Database | null = null;
    try {
      db = new Database(dbPath, { readonly: true });
      const rows = db
        .query(
          `SELECT id, cwd, title, custom_title, status, created_at, updated_at, last_activity_at, model
           FROM sessions
           WHERE (deleted_at IS NULL OR deleted_at <= 0)
           ORDER BY COALESCE(last_activity_at, updated_at, created_at) DESC
           LIMIT $limit`
        )
        .all({ $limit: Math.max(1, limit) }) as Array<{
        id: string;
        cwd: string;
        title?: string | null;
        custom_title?: string | null;
        status?: string | null;
        created_at?: number | null;
        updated_at?: number | null;
        last_activity_at?: number | null;
        model?: string | null;
      }>;

      return rows
        .filter((r) => r && r.id)
        .map((r) => {
          const rawSt = String(r.status || "").toLowerCase();
          const normStatus: AgentSessionSummary["status"] =
            rawSt === "running" || rawSt === "in_progress"
              ? "running"
              : rawSt === "failed" || rawSt === "error"
                ? "failed"
                : rawSt === "completed" || rawSt === "done"
                  ? "completed"
                  : "idle";
          const tsRaw = Number(r.last_activity_at || r.updated_at || r.created_at || Date.now());
          const updatedAtMs = tsRaw < 1e11 ? tsRaw * 1000 : tsRaw;
          const ws = String(r.cwd || this.defaultWorkspacePath).trim();
          const title = String(r.custom_title || r.title || "WorkBuddy 会话").trim();
          return {
            sessionId: r.id,
            backend: "workbuddy" as const,
            origin: "desktop" as const,
            title,
            workspacePath: ws,
            status: normStatus,
            lastSummary: r.model ? `模型: ${r.model} · 状态: ${r.status || "idle"}` : title,
            updatedAtMs,
            canContinue: true,
          };
        });
    } catch {
      return [];
    } finally {
      try {
        db?.close();
      } catch {}
    }
  }

  private findWorkBuddyTranscriptFile(sessionId: string): string | null {
    const projectsRoot = path.join(this.workbuddyDir, "projects");
    if (!fs.existsSync(projectsRoot)) return null;
    try {
      for (const projEntry of fs.readdirSync(projectsRoot, { withFileTypes: true })) {
        if (!projEntry.isDirectory()) continue;
        const candidate = path.join(projectsRoot, projEntry.name, `${sessionId}.jsonl`);
        if (fs.existsSync(candidate)) return candidate;
      }
    } catch {}
    return null;
  }

  private readWorkBuddySessionDetail(sessionId: string): AgentSessionDetailResult | null {
    const scanned = this.scanWorkBuddySessions(80);
    const meta = scanned.find((s) => s.sessionId === sessionId);
    const jsonlPath = this.findWorkBuddyTranscriptFile(sessionId);
    if (!meta && !jsonlPath) return null;

    const steps: AgentStepItem[] = [];
    let lastSummary = meta?.lastSummary || "";
    if (jsonlPath && fs.existsSync(jsonlPath)) {
      const lines = this.readJsonlBoundedLines(jsonlPath, 64 * 1024, 384 * 1024, true);
      for (const line of lines) {
        try {
          const obj = JSON.parse(line);
          const tsRaw = Number(obj?.timestamp || Date.now());
          const ts = tsRaw < 1e11 ? tsRaw * 1000 : tsRaw;
          if (obj?.type === "message" && (obj.role === "user" || obj.role === "assistant")) {
            const parts = Array.isArray(obj.content) ? obj.content : [];
            const rawText = parts
              .map((p: any) => (typeof p === "string" ? p : typeof p?.text === "string" ? p.text : ""))
              .filter(Boolean)
              .join("\n");
            const cleaned =
              obj.role === "user" ? this.stripWorkBuddySystemReminder(rawText) : rawText.trim();
            if (!cleaned) continue;
            steps.push({
              stepIndex: steps.length + 1,
              kind: obj.role === "user" ? "user" : "assistant",
              title: obj.role === "user" ? "👤 用户输入" : "🛠️ WorkBuddy 回复",
              detail: cleaned.slice(0, 2400),
              timestampMs: ts,
            });
            if (obj.role === "assistant") {
              lastSummary = cleaned.slice(0, 160);
            }
          } else if (obj?.type === "tool_use" || obj?.type === "function_call") {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "tool",
              title: `🔧 ${String(obj.name || "工具调用")}`,
              detail: JSON.stringify(obj.input || obj.arguments || {}).slice(0, 320),
              timestampMs: ts,
            });
          }
        } catch {}
      }
    }

    const session: AgentSessionSummary = meta
      ? { ...meta, lastSummary: lastSummary || meta.lastSummary }
      : {
          sessionId,
          backend: "workbuddy",
          origin: "desktop",
          title: "WorkBuddy 会话",
          workspacePath: this.defaultWorkspacePath,
          status: "completed",
          lastSummary: lastSummary || "WorkBuddy 历史会话",
          updatedAtMs: Date.now(),
          canContinue: true,
        };

    return { session, steps };
  }

  private collectWorkBuddyQuota(): AgentBackendQuotaStatus {
    const now = Date.now();
    const cliBin = "/Applications/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy";
    const dbPath = path.join(this.workbuddyDir, "workbuddy.db");
    const available =
      fs.existsSync(cliBin) ||
      fs.existsSync("/Applications/WorkBuddy.app") ||
      fs.existsSync(dbPath);
    if (!available) {
      return {
        backend: "workbuddy",
        available: false,
        planLabel: "WorkBuddy",
        windows: [],
        statusNote: "本机未检测到 WorkBuddy",
        capturedAtMs: now,
      };
    }
    const sessions = this.scanWorkBuddySessions(30);
    let latestModel = "auto";
    if (fs.existsSync(dbPath)) {
      let db: Database | null = null;
      try {
        db = new Database(dbPath, { readonly: true });
        const row = db
          .query(`SELECT model FROM sessions WHERE model IS NOT NULL AND model != '' ORDER BY updated_at DESC LIMIT 1`)
          .get() as { model?: string } | null;
        if (row?.model) latestModel = row.model;
      } catch {} finally {
        try {
          db?.close();
        } catch {}
      }
    }
    return {
      backend: "workbuddy",
      available: true,
      planLabel: "WorkBuddy Desktop & CLI",
      activeModel: latestModel,
      windows: [],
      creditsSummary: `已同步 ${sessions.length} 个本地会话`,
      statusNote: "运行正常 · 支持新建与恢复会话",
      capturedAtMs: now,
    };
  }

  private parseOpenCodeModelLabel(rawModel: string | null | undefined): string {
    if (!rawModel) return "";
    try {
      const parsed = JSON.parse(rawModel);
      if (parsed && typeof parsed === "object") {
        return String(parsed.id || parsed.modelID || "").trim();
      }
    } catch {}
    return String(rawModel).trim();
  }

  private scanOpenCodeSessions(limit: number): AgentSessionSummary[] {
    const dbPath = path.join(this.opencodeDataDir, "opencode.db");
    if (!fs.existsSync(dbPath)) return [];
    let db: Database | null = null;
    try {
      db = new Database(dbPath, { readonly: true });
      const rows = db
        .query(
          `SELECT s.id, s.directory, s.title, s.time_updated, s.time_created, s.model, p.worktree, p.name AS project_name
           FROM session s
           LEFT JOIN project p ON p.id = s.project_id
           WHERE s.time_archived IS NULL
           ORDER BY s.time_updated DESC
           LIMIT $limit`
        )
        .all({ $limit: Math.max(1, limit) }) as Array<{
        id: string;
        directory: string;
        title: string;
        time_updated: number;
        time_created: number;
        model?: string | null;
        worktree?: string | null;
        project_name?: string | null;
      }>;

      return rows
        .filter((r) => r && r.id)
        .map((r) => {
          const rawDir = String(r.directory || r.worktree || "").trim();
          const ws = rawDir && rawDir !== "/" ? rawDir : this.defaultWorkspacePath;
          const modelLabel = this.parseOpenCodeModelLabel(r.model);
          const updatedAtMs = Number(r.time_updated || r.time_created || Date.now());
          const title = String(r.title || "OpenCode 会话").trim();
          return {
            sessionId: r.id,
            backend: "opencode" as const,
            origin: "desktop" as const,
            title,
            workspacePath: ws,
            projectDisplayName: r.project_name || undefined,
            status: "completed" as const,
            lastSummary: modelLabel ? `模型: ${modelLabel} · ${title}` : title,
            updatedAtMs: updatedAtMs < 1e11 ? updatedAtMs * 1000 : updatedAtMs,
            canContinue: true,
          };
        });
    } catch {
      return [];
    } finally {
      try {
        db?.close();
      } catch {}
    }
  }

  private readOpenCodeSessionDetail(sessionId: string): AgentSessionDetailResult | null {
    const dbPath = path.join(this.opencodeDataDir, "opencode.db");
    if (!fs.existsSync(dbPath)) return null;
    const scanned = this.scanOpenCodeSessions(80);
    const meta = scanned.find((s) => s.sessionId === sessionId);
    let db: Database | null = null;
    try {
      db = new Database(dbPath, { readonly: true });
      const sessionRow =
        meta ||
        (() => {
          const r = db!
            .query(`SELECT id, directory, title, time_updated, model FROM session WHERE id = $id LIMIT 1`)
            .get({ $id: sessionId }) as
            | { id: string; directory: string; title: string; time_updated: number; model?: string }
            | null;
          if (!r) return null;
          return {
            sessionId: r.id,
            backend: "opencode" as const,
            origin: "desktop" as const,
            title: r.title || "OpenCode 会话",
            workspacePath: r.directory && r.directory !== "/" ? r.directory : this.defaultWorkspacePath,
            status: "completed" as const,
            lastSummary: r.title || "",
            updatedAtMs: r.time_updated || Date.now(),
            canContinue: true,
          };
        })();
      if (!sessionRow) return null;

      const msgRows = db
        .query(
          `SELECT id, time_created, data FROM message WHERE session_id = $sid ORDER BY time_created ASC LIMIT 240`
        )
        .all({ $sid: sessionId }) as Array<{ id: string; time_created: number; data: string }>;
      const partRows = db
        .query(
          `SELECT message_id, time_created, data FROM part WHERE session_id = $sid ORDER BY time_created ASC, id ASC LIMIT 800`
        )
        .all({ $sid: sessionId }) as Array<{ message_id: string; time_created: number; data: string }>;

      const partsByMsg = new Map<string, Array<{ ts: number; obj: Record<string, any> }>>();
      for (const pr of partRows) {
        try {
          const obj = JSON.parse(pr.data);
          const arr = partsByMsg.get(pr.message_id) || [];
          arr.push({ ts: pr.time_created, obj });
          partsByMsg.set(pr.message_id, arr);
        } catch {}
      }

      const steps: AgentStepItem[] = [];
      let lastSummary = sessionRow.lastSummary;
      for (const mr of msgRows) {
        let mObj: Record<string, any> = {};
        try {
          mObj = JSON.parse(mr.data);
        } catch {}
        const role = String(mObj.role || "");
        const mParts = partsByMsg.get(mr.id) || [];

        if (role === "user") {
          const textParts = mParts
            .filter((p) => p.obj.type === "text" && typeof p.obj.text === "string")
            .map((p) => String(p.obj.text).trim())
            .filter(Boolean);
          const fallbackTitle = String(mObj.summary?.title || "").trim();
          const userText = textParts.join("\n") || fallbackTitle;
          if (userText) {
            steps.push({
              stepIndex: steps.length + 1,
              kind: "user",
              title: "👤 用户提问",
              detail: userText.slice(0, 2400),
              timestampMs: mr.time_created,
            });
          }
        } else {
          for (const p of mParts) {
            const pt = String(p.obj.type || "");
            if (pt === "reasoning" && typeof p.obj.text === "string" && p.obj.text.trim()) {
              steps.push({
                stepIndex: steps.length + 1,
                kind: "thinking",
                title: "💭 推理思考",
                detail: p.obj.text.trim().slice(0, 600),
                timestampMs: p.ts,
              });
            } else if (pt === "tool" || pt === "tool-invocation") {
              const tName = String(p.obj.tool || p.obj.name || "工具调用");
              steps.push({
                stepIndex: steps.length + 1,
                kind: "tool",
                title: `🔧 ${tName}`,
                detail: JSON.stringify(p.obj.state?.input || p.obj.input || {}).slice(0, 320),
                timestampMs: p.ts,
              });
            } else if (pt === "text" && typeof p.obj.text === "string" && p.obj.text.trim()) {
              const txt = p.obj.text.trim();
              steps.push({
                stepIndex: steps.length + 1,
                kind: "assistant",
                title: "⚡ OpenCode 回复",
                detail: txt.slice(0, 2400),
                timestampMs: p.ts,
              });
              lastSummary = txt.slice(0, 160);
            }
          }
        }
      }

      return {
        session: { ...sessionRow, lastSummary: lastSummary || sessionRow.lastSummary },
        steps,
      };
    } catch {
      return null;
    } finally {
      try {
        db?.close();
      } catch {}
    }
  }

  private collectOpenCodeQuota(): AgentBackendQuotaStatus {
    const now = Date.now();
    const homeBin = path.join(os.homedir(), ".opencode", "bin", "opencode");
    const dbPath = path.join(this.opencodeDataDir, "opencode.db");
    const available =
      fs.existsSync(homeBin) ||
      fs.existsSync("/Applications/OpenCode.app") ||
      fs.existsSync(dbPath);
    if (!available) {
      return {
        backend: "opencode",
        available: false,
        planLabel: "OpenCode",
        windows: [],
        statusNote: "本机未检测到 OpenCode",
        capturedAtMs: now,
      };
    }
    const sessions = this.scanOpenCodeSessions(30);
    let latestModel = "OpenCode";
    if (fs.existsSync(dbPath)) {
      let db: Database | null = null;
      try {
        db = new Database(dbPath, { readonly: true });
        const row = db
          .query(`SELECT model FROM session WHERE model IS NOT NULL AND model != '' ORDER BY time_updated DESC LIMIT 1`)
          .get() as { model?: string } | null;
        const parsed = this.parseOpenCodeModelLabel(row?.model);
        if (parsed) latestModel = parsed;
      } catch {} finally {
        try {
          db?.close();
        } catch {}
      }
    }
    return {
      backend: "opencode",
      available: true,
      planLabel: "OpenCode Desktop & CLI",
      activeModel: latestModel,
      windows: [],
      creditsSummary: `已同步 ${sessions.length} 个本地会话`,
      statusNote: "运行正常 · 支持 opencode run 新建与接续",
      capturedAtMs: now,
    };
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
        // Skip legacy failed resume attempts caused by pre-v3.2 CLI path/writer errors so clean desktop sessions aren't masked
        const oldLast = String(item.summary.lastSummary || "");
        if (
          item.summary.status === "failed" &&
          (oldLast.includes("trajectory not found") || oldLast.includes("already has an active writer"))
        ) {
          continue;
        }
        const summary: AgentSessionSummary = {
          ...item.summary,
          status: item.summary.status === "running" ? "idle" : item.summary.status,
        };
        const steps: AgentStepItem[] = Array.isArray(item.steps)
          ? this.compactTrajectorySteps(item.steps)
          : [];
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
          steps: this.compactTrajectorySteps(st.steps),
        }));
      const tmp = `${this.statePath}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify({ tasks }, null, 2), "utf8");
      fs.renameSync(tmp, this.statePath);
    } catch {
      // ignore
    }
  }
}
