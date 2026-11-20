import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { Database } from "bun:sqlite";
import type {
  AgentSessionSummary,
  SecretaryBriefSnapshot,
  SecretaryCommitmentItem,
  SecretaryNudgeItem,
} from "@oms/shared-protocol";
import { listImportedBooks } from "../knowledge/epub-importer.js";

interface PersistedSecretaryState {
  profileItems: string[];
  commitments: SecretaryCommitmentItem[];
  dismissedNudges: Record<string, number>;
  updatedAtIso: string;
}

export interface SecretaryBriefBuildContext {
  vaultRoot: string;
  wechatDbPath: string;
  agentSessions: AgentSessionSummary[];
  nowMs?: number;
}

/**
 * Executive Chief-of-Staff core for OneMoreStep:
 * Maintains transparent, user-editable Living Memory (Profile Preferences + Open Loops / Commitments)
 * and synthesizes a calm, actionable Today Brief + Proactive Nudges from Chisu, Vault, Books, and Mac Agents.
 */
export class SecretaryCore {
  private readonly statePath: string;

  constructor(omsHomeDir: string) {
    const chatDir = path.join(omsHomeDir, "chat");
    fs.mkdirSync(chatDir, { recursive: true, mode: 0o700 });
    this.statePath = path.join(chatDir, "secretary_state.json");
  }

  private loadState(): PersistedSecretaryState {
    try {
      if (fs.existsSync(this.statePath)) {
        const parsed = JSON.parse(fs.readFileSync(this.statePath, "utf8"));
        return {
          profileItems: Array.isArray(parsed?.profileItems)
            ? parsed.profileItems.map((x: unknown) => String(x || "").trim()).filter(Boolean).slice(0, 24)
            : [],
          commitments: Array.isArray(parsed?.commitments) ? parsed.commitments.slice(0, 80) : [],
          dismissedNudges:
            parsed?.dismissedNudges && typeof parsed.dismissedNudges === "object"
              ? parsed.dismissedNudges
              : {},
          updatedAtIso: String(parsed?.updatedAtIso || ""),
        };
      }
    } catch {}
    return {
      profileItems: [],
      commitments: [],
      dismissedNudges: {},
      updatedAtIso: "",
    };
  }

  private saveState(state: PersistedSecretaryState): void {
    state.updatedAtIso = new Date().toISOString();
    const tmp = `${this.statePath}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2), "utf8");
    fs.renameSync(tmp, this.statePath);
  }

  public getProfileItems(): string[] {
    return this.loadState().profileItems;
  }

  public saveProfileItems(items: string[]): string[] {
    const state = this.loadState();
    state.profileItems = Array.from(
      new Set(items.map((x) => String(x || "").trim()).filter(Boolean))
    ).slice(0, 24);
    this.saveState(state);
    return state.profileItems;
  }

  public listCommitments(includeDone = true): SecretaryCommitmentItem[] {
    const all = this.loadState().commitments;
    const filtered = includeDone ? all : all.filter((c) => c.status === "open");
    return filtered.sort((a, b) => {
      if ((a.status === "open") !== (b.status === "open")) return a.status === "open" ? -1 : 1;
      return b.updatedAtMs - a.updatedAtMs;
    });
  }

  public upsertCommitment(params: {
    id?: string;
    title: string;
    dueHint?: string;
    source?: string;
    status?: "open" | "done" | "snoozed";
  }): SecretaryCommitmentItem {
    const cleanTitle = String(params.title || "").trim().slice(0, 160);
    if (!cleanTitle) throw new Error("Commitment title is required");
    const state = this.loadState();
    const now = Date.now();
    const existingIdx = params.id
      ? state.commitments.findIndex((c) => c.id === params.id)
      : state.commitments.findIndex(
          (c) => c.status === "open" && c.title.toLowerCase() === cleanTitle.toLowerCase()
        );

    if (existingIdx >= 0) {
      const prev = state.commitments[existingIdx];
      const updated: SecretaryCommitmentItem = {
        ...prev,
        title: cleanTitle,
        dueHint: params.dueHint !== undefined ? params.dueHint.trim() || undefined : prev.dueHint,
        source: params.source || prev.source,
        status: params.status || prev.status,
        updatedAtMs: now,
      };
      state.commitments[existingIdx] = updated;
      this.saveState(state);
      return updated;
    }

    const item: SecretaryCommitmentItem = {
      id: params.id || `cmt_${crypto.randomBytes(6).toString("hex")}`,
      title: cleanTitle,
      dueHint: params.dueHint?.trim() || undefined,
      source: params.source || "mobile",
      status: params.status || "open",
      createdAtMs: now,
      updatedAtMs: now,
    };
    state.commitments.unshift(item);
    state.commitments = state.commitments.slice(0, 80);
    this.saveState(state);
    return item;
  }

  public toggleCommitment(id: string): SecretaryCommitmentItem | null {
    const state = this.loadState();
    const idx = state.commitments.findIndex((c) => c.id === id);
    if (idx < 0) return null;
    const prev = state.commitments[idx];
    const nextStatus = prev.status === "done" ? "open" : "done";
    const updated: SecretaryCommitmentItem = {
      ...prev,
      status: nextStatus,
      updatedAtMs: Date.now(),
    };
    state.commitments[idx] = updated;
    this.saveState(state);
    return updated;
  }

  public deleteCommitment(id: string): boolean {
    const state = this.loadState();
    const before = state.commitments.length;
    state.commitments = state.commitments.filter((c) => c.id !== id);
    if (state.commitments.length === before) return false;
    this.saveState(state);
    return true;
  }

  public dismissNudge(nudgeId: string): void {
    const clean = String(nudgeId || "").trim();
    if (!clean) return;
    const state = this.loadState();
    state.dismissedNudges[clean] = Date.now();
    // Prune dismissals older than 7 days
    const cutoff = Date.now() - 7 * 86400_000;
    for (const [k, ts] of Object.entries(state.dismissedNudges)) {
      if (ts < cutoff) delete state.dismissedNudges[k];
    }
    this.saveState(state);
  }

  public buildTodayBrief(ctx: SecretaryBriefBuildContext): SecretaryBriefSnapshot {
    const nowMs = ctx.nowMs ?? Date.now();
    const now = new Date(nowMs);
    const hour = now.getHours();
    const greeting =
      hour < 6
        ? "夜深了"
        : hour < 11
          ? "早安"
          : hour < 14
            ? "午安"
            : hour < 19
              ? "下午好"
              : "晚上好";

    const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
    const dateLabel = `${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`;

    const state = this.loadState();
    const openLoops = state.commitments
      .filter((c) => c.status === "open")
      .sort((a, b) => b.updatedAtMs - a.updatedAtMs)
      .slice(0, 10);

    // Inspect recent 48h WeChat Chisu items (articles + multimodal turns)
    let recentChisuCount = 0;
    let topChisuTitle = "";
    if (ctx.wechatDbPath && fs.existsSync(ctx.wechatDbPath)) {
      let wxDb: Database | null = null;
      try {
        wxDb = new Database(ctx.wechatDbPath, { readonly: true });
        const cutoffIso = new Date(nowMs - 48 * 3600_000).toISOString();
        const rows = wxDb
          .query(
            `SELECT id, title, triage, created_at
             FROM articles
             WHERE created_at >= $cutoff
             ORDER BY CASE WHEN triage = 'process_now' THEN 0 ELSE 1 END, id DESC
             LIMIT 10`
          )
          .all({ $cutoff: cutoffIso }) as Array<{ id: number; title: string; triage: string }>;
        recentChisuCount = rows.length;
        if (rows[0]?.title) topChisuTitle = rows[0].title;
      } catch {} finally {
        try {
          wxDb?.close();
        } catch {}
      }
    }

    // Inspect recent Vault Focus (recent 04_topics & 03_ideas)
    const vaultFocusTitles: string[] = [];
    for (const subDir of ["04_topics", "03_ideas/developed", "03_ideas/seeds"]) {
      const absDir = path.join(ctx.vaultRoot, subDir);
      if (!fs.existsSync(absDir)) continue;
      try {
        const files = fs
          .readdirSync(absDir, { withFileTypes: true })
          .filter((e) => e.isFile() && e.name.endsWith(".md") && e.name !== "instruction.md")
          .map((e) => {
            const p = path.join(absDir, e.name);
            return { name: path.basename(e.name, ".md"), mtime: fs.statSync(p).mtimeMs };
          })
          .sort((a, b) => b.mtime - a.mtime)
          .slice(0, 2);
        for (const f of files) {
          if (!vaultFocusTitles.includes(f.name)) vaultFocusTitles.push(f.name);
        }
      } catch {}
    }

    const activeAgents = (ctx.agentSessions || []).filter((s) => s.status === "running");
    const recentCompletedAgents = (ctx.agentSessions || []).filter(
      (s) => s.status === "completed" && nowMs - s.updatedAtMs <= 24 * 3600_000
    );

    const importedBooks = listImportedBooks(ctx.vaultRoot);

    // Build calm, high-signal Proactive Nudges (filtered by 24h dismissal)
    const nudges: SecretaryNudgeItem[] = [];
    const isDismissed = (id: string) => {
      const ts = state.dismissedNudges[id];
      return Boolean(ts && nowMs - ts < 24 * 3600_000);
    };

    if (recentChisuCount > 0 && !isDismissed("nudge_commute_chisu")) {
      nudges.push({
        id: "nudge_commute_chisu",
        kind: "commute_chisu",
        title: `近 48 小时新收 ${recentChisuCount} 篇尺素资料`,
        subtitle: topChisuTitle ? `推荐先看：《${topChisuTitle.slice(0, 34)}》` : "可一键开启耳机通勤联播或双文档精读",
        actionLabel: "去尺素听读",
        actionType: "open_chisu",
        actionPayload: "",
      });
    }

    if (recentCompletedAgents.length > 0 && !isDismissed(`nudge_agent_${recentCompletedAgents[0].sessionId}`)) {
      const latest = recentCompletedAgents[0];
      nudges.push({
        id: `nudge_agent_${latest.sessionId}`,
        kind: "agent_review",
        title: `Mac 协同任务已完成：${latest.title.slice(0, 28)}`,
        subtitle: `${latest.backend === "codex" ? "Codex" : "Antigravity"} · ${latest.projectDisplayName || "工作区"}，可随时验收或追问`,
        actionLabel: "查看结果",
        actionType: "open_missions",
        actionPayload: latest.sessionId,
      });
    }

    if (openLoops.length > 0 && !isDismissed("nudge_open_loops")) {
      const top = openLoops[0];
      nudges.push({
        id: "nudge_open_loops",
        kind: "open_loop",
        title: `还有 ${openLoops.length} 项未闭环事项待推进`,
        subtitle: `优先关注：${top.title}${top.dueHint ? `（${top.dueHint}）` : ""}`,
        actionLabel: "梳理推进方案",
        actionType: "ask_chat",
        actionPayload: `请作为我的随身秘书，帮我把当前未闭环事项梳理成今天最省力的执行顺序，优先推进「${top.title}」。`,
      });
    }

    if (importedBooks.length > 0 && !isDismissed(`nudge_book_${importedBooks[0].slug}`)) {
      const b = importedBooks[0];
      nudges.push({
        id: `nudge_book_${b.slug}`,
        kind: "book_reading",
        title: `知识库书架：《${b.title.slice(0, 24)}》`,
        subtitle: `${b.author} · 已拆章入库 ${b.chapterCount} 章，随时可按章伴读或跨书检索`,
        actionLabel: "打开目录",
        actionType: "open_vault_doc",
        actionPayload: b.indexRelPath,
      });
    }

    if (nudges.length < 3 && vaultFocusTitles.length > 0 && !isDismissed("nudge_vault_focus")) {
      nudges.push({
        id: "nudge_vault_focus",
        kind: "vault_synthesis",
        title: "延续近期思考脉络",
        subtitle: `近期活跃主题：${vaultFocusTitles.slice(0, 2).join(" · ")}`,
        actionLabel: "综合回顾",
        actionType: "ask_chat",
        actionPayload: `请检索我个人知识库里最近活跃的主题（${vaultFocusTitles.slice(0, 3).join("、")}），帮我回顾核心观点、待解张力和下一步值得产出的方向。`,
      });
    }

    const parts: string[] = [];
    if (openLoops.length > 0) parts.push(`${openLoops.length} 项待办`);
    if (recentChisuCount > 0) parts.push(`${recentChisuCount} 篇新尺素`);
    if (activeAgents.length > 0) parts.push(`${activeAgents.length} 个 Mac 任务运行中`);
    const headline =
      parts.length > 0 ? `今日状态：${parts.join(" · ")}` : "今日日程与待办清爽，随时告诉我你想推进的事";

    return {
      greeting,
      dateLabel,
      headline,
      profileItems: state.profileItems,
      openLoops,
      nudges: nudges.slice(0, 4),
      recentChisuCount,
      activeAgentCount: activeAgents.length,
      vaultFocusTitles: vaultFocusTitles.slice(0, 6),
      generatedAtMs: nowMs,
    };
  }
}
