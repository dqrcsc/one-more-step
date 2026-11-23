import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { Database } from "bun:sqlite";
import type {
  AgentSessionSummary,
  LivingMemoryEntry,
  MemoryCenterSnapshot,
  SecretaryBriefSnapshot,
  SecretaryCommitmentItem,
  SecretaryNudgeItem,
} from "@oms/shared-protocol";
import { listImportedBooks } from "../knowledge/epub-importer.js";

export interface ProfileMemoryEntry {
  id: string;
  text: string;
  source: "manual" | "auto";
  originDetail?: string;
  observations: number;
  createdAtMs: number;
  lastSeenAtMs: number;
}

interface PersistedSecretaryState {
  profileItems: string[];
  profileEntries?: ProfileMemoryEntry[];
  forgottenFingerprints?: Record<string, number>;
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

function normalizeMemoryText(s: string): string {
  return String(s || "")
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}]+/gu, "")
    .trim();
}

function stableMemoryId(text: string): string {
  const hash = crypto.createHash("sha1").update(normalizeMemoryText(text) || text).digest("hex").slice(0, 12);
  return `mem_${hash}`;
}

function agentDisplayLabel(backend: string): string {
  switch (backend) {
    case "codex":
      return "Codex";
    case "agy":
      return "Antigravity";
    case "workbuddy":
      return "WorkBuddy";
    case "opencode":
      return "OpenCode";
    default:
      return backend;
  }
}

/**
 * Personal Assistant ("随身助理") Living Memory & Today Brief core:
 * Maintains transparent, user-editable Living Memory (Profile Preferences + Working Memory + Vault Anchors + Open Loops)
 * with anti-resurrection tombstones (`forgottenFingerprints`) so deleted auto-extracted memories never come back.
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
        const rawItems: string[] = Array.isArray(parsed?.profileItems)
          ? parsed.profileItems.map((x: unknown) => String(x || "").trim()).filter(Boolean).slice(0, 48)
          : [];
        const rawEntries: ProfileMemoryEntry[] = Array.isArray(parsed?.profileEntries)
          ? parsed.profileEntries
              .filter((e: any) => e && typeof e.text === "string" && e.text.trim())
              .map((e: any) => {
                const cleanText = String(e.text).trim();
                const src: "manual" | "auto" = e.source === "auto" ? "auto" : "manual";
                return {
                  id: typeof e.id === "string" && e.id.trim() ? e.id.trim() : stableMemoryId(cleanText),
                  text: cleanText,
                  source: src,
                  originDetail:
                    typeof e.originDetail === "string" && e.originDetail.trim()
                      ? e.originDetail.trim()
                      : src === "auto"
                        ? "历史对话自动提取"
                        : "手动添加",
                  observations: Math.max(1, Number(e.observations) || 1),
                  createdAtMs: Number(e.createdAtMs) || Date.now(),
                  lastSeenAtMs: Number(e.lastSeenAtMs) || Date.now(),
                };
              })
          : rawItems.map((text) => ({
              id: stableMemoryId(text),
              text,
              source: "manual" as const,
              originDetail: "手动添加",
              observations: 1,
              createdAtMs: Date.now(),
              lastSeenAtMs: Date.now(),
            }));
        return {
          profileItems: rawEntries.map((e) => e.text).slice(0, 48),
          profileEntries: rawEntries.slice(0, 48),
          forgottenFingerprints:
            parsed?.forgottenFingerprints && typeof parsed.forgottenFingerprints === "object"
              ? parsed.forgottenFingerprints
              : {},
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
      profileEntries: [],
      forgottenFingerprints: {},
      commitments: [],
      dismissedNudges: {},
      updatedAtIso: "",
    };
  }

  private saveState(state: PersistedSecretaryState): void {
    state.updatedAtIso = new Date().toISOString();
    if (state.profileEntries) {
      state.profileItems = state.profileEntries.map((e) => e.text).slice(0, 48);
    }
    const tmp = `${this.statePath}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2), "utf8");
    fs.renameSync(tmp, this.statePath);
  }

  public getProfileItems(): string[] {
    return this.loadState().profileItems;
  }

  public getProfileEntries(): ProfileMemoryEntry[] {
    return this.loadState().profileEntries || [];
  }

  public getLivingProfileEntries(): LivingMemoryEntry[] {
    return this.getProfileEntries().map((e) => ({
      id: e.id,
      text: e.text,
      source: e.source,
      category: "profile",
      originDetail: e.originDetail || (e.source === "auto" ? "历史对话自动提取" : "手动添加"),
      observations: e.observations,
      createdAtMs: e.createdAtMs,
      lastSeenAtMs: e.lastSeenAtMs,
    }));
  }

  private isForgottenNormalized(state: PersistedSecretaryState, candidateNorm: string): boolean {
    if (!candidateNorm) return false;
    const forgotten = state.forgottenFingerprints || {};
    for (const key of Object.keys(forgotten)) {
      if (!key) continue;
      if (key === candidateNorm) return true;
      if (key.length >= 6 && candidateNorm.length >= 6 && (key.includes(candidateNorm) || candidateNorm.includes(key))) {
        return true;
      }
    }
    return false;
  }

  public addOrUpdateMemoryEntry(params: {
    id?: string;
    text: string;
    source?: "manual" | "auto";
    originDetail?: string;
  }): LivingMemoryEntry {
    const cleanText = String(params.text || "").trim().slice(0, 160);
    if (!cleanText) throw new Error("记忆内容不能为空");
    const state = this.loadState();
    const entries = [...(state.profileEntries || [])];
    const now = Date.now();
    const norm = normalizeMemoryText(cleanText);
    if (state.forgottenFingerprints && norm) {
      for (const k of Object.keys(state.forgottenFingerprints)) {
        if (k === norm || (k.length >= 6 && norm.length >= 6 && (k.includes(norm) || norm.includes(k)))) {
          delete state.forgottenFingerprints[k];
        }
      }
    }

    const src: "manual" | "auto" = params.source === "auto" ? "auto" : "manual";
    const existingIdx = params.id
      ? entries.findIndex((e) => e.id === params.id)
      : entries.findIndex((e) => normalizeMemoryText(e.text) === norm);

    let target: ProfileMemoryEntry;
    if (existingIdx >= 0) {
      const prev = entries[existingIdx];
      target = {
        ...prev,
        id: prev.id || stableMemoryId(cleanText),
        text: cleanText,
        source: src === "manual" ? "manual" : prev.source,
        originDetail: params.originDetail || (src === "manual" ? "手动编辑" : prev.originDetail),
        lastSeenAtMs: now,
      };
      entries[existingIdx] = target;
    } else {
      target = {
        id: params.id || stableMemoryId(cleanText),
        text: cleanText,
        source: src,
        originDetail: params.originDetail || (src === "auto" ? "历史对话自动提取" : "手动添加"),
        observations: 1,
        createdAtMs: now,
        lastSeenAtMs: now,
      };
      entries.unshift(target);
    }

    state.profileEntries = entries.slice(0, 48);
    this.saveState(state);
    return {
      id: target.id,
      text: target.text,
      source: target.source,
      category: "profile",
      originDetail: target.originDetail,
      observations: target.observations,
      createdAtMs: target.createdAtMs,
      lastSeenAtMs: target.lastSeenAtMs,
    };
  }

  public deleteMemoryEntry(idOrText: string, rememberTombstone = true): boolean {
    const clean = String(idOrText || "").trim();
    if (!clean) return false;
    const state = this.loadState();
    const entries = state.profileEntries || [];
    const targetNorm = normalizeMemoryText(clean);
    const removed: ProfileMemoryEntry[] = [];
    const kept = entries.filter((e) => {
      const matched = e.id === clean || e.text === clean || (targetNorm && normalizeMemoryText(e.text) === targetNorm);
      if (matched) removed.push(e);
      return !matched;
    });
    if (removed.length === 0) return false;

    if (rememberTombstone) {
      state.forgottenFingerprints = state.forgottenFingerprints || {};
      for (const r of removed) {
        const fp = normalizeMemoryText(r.text);
        if (fp) state.forgottenFingerprints[fp] = Date.now();
      }
      // Cap tombstones at 200 most recent
      const fpEntries = Object.entries(state.forgottenFingerprints).sort((a, b) => b[1] - a[1]).slice(0, 200);
      state.forgottenFingerprints = Object.fromEntries(fpEntries);
    }
    state.profileEntries = kept;
    this.saveState(state);
    return true;
  }

  public saveProfileItems(items: string[]): string[] {
    const state = this.loadState();
    const prevByText = new Map((state.profileEntries || []).map((e) => [e.text, e]));
    const now = Date.now();
    const cleaned = Array.from(
      new Set(items.map((x) => String(x || "").trim()).filter(Boolean))
    ).slice(0, 48);
    const nextSetNorm = new Set(cleaned.map(normalizeMemoryText));

    // Record removed items into tombstones so auto-distiller won't re-add them
    state.forgottenFingerprints = state.forgottenFingerprints || {};
    for (const old of state.profileEntries || []) {
      const oldNorm = normalizeMemoryText(old.text);
      if (oldNorm && !nextSetNorm.has(oldNorm)) {
        state.forgottenFingerprints[oldNorm] = now;
      }
    }

    state.profileEntries = cleaned.map((text) => {
      const existing = prevByText.get(text);
      const norm = normalizeMemoryText(text);
      if (norm && state.forgottenFingerprints) {
        delete state.forgottenFingerprints[norm];
      }
      return existing
        ? { ...existing, lastSeenAtMs: now }
        : {
            id: stableMemoryId(text),
            text,
            source: "manual",
            originDetail: "手动添加",
            observations: 1,
            createdAtMs: now,
            lastSeenAtMs: now,
          };
    });
    state.profileItems = cleaned;
    this.saveState(state);
    return state.profileItems;
  }

  /**
   * Slow, conservative living-memory evolution from everyday conversations:
   * - Skips any trait matching `forgottenFingerprints` (user-deleted memories never resurrect!)
   * - Deduplicates & reinforces matching memories (`observations += 1`)
   * - Appends high-signal new traits (`source: "auto"`), capping total at 36
   *   while NEVER auto-evicting user-edited manual memories.
   */
  public evolveMemoryFromDialogue(params: {
    profileAdditions?: string[];
    implicitCommitments?: Array<{ title: string; dueHint?: string }>;
  }): { addedProfile: number; reinforcedProfile: number; addedCommitments: number; profileItems: string[] } {
    const state = this.loadState();
    const entries = [...(state.profileEntries || [])];
    const now = Date.now();
    let addedProfile = 0;
    let reinforcedProfile = 0;

    for (const raw of (params.profileAdditions || []).slice(0, 3)) {
      const text = String(raw || "").trim().slice(0, 100);
      if (text.length < 4) continue;
      const norm = normalizeMemoryText(text);
      if (!norm || this.isForgottenNormalized(state, norm)) continue;

      const matchIdx = entries.findIndex((e) => {
        const en = normalizeMemoryText(e.text);
        return en === norm || (norm.length >= 6 && (en.includes(norm) || norm.includes(en)));
      });
      if (matchIdx >= 0) {
        entries[matchIdx] = {
          ...entries[matchIdx],
          observations: entries[matchIdx].observations + 1,
          lastSeenAtMs: now,
        };
        reinforcedProfile += 1;
      } else {
        entries.push({
          id: stableMemoryId(text),
          text,
          source: "auto",
          originDetail: "历史对话自动提取",
          observations: 1,
          createdAtMs: now,
          lastSeenAtMs: now,
        });
        addedProfile += 1;
      }
    }

    if (entries.length > 36) {
      const manual = entries.filter((e) => e.source === "manual");
      const auto = entries
        .filter((e) => e.source === "auto")
        .sort((a, b) => b.observations - a.observations || b.lastSeenAtMs - a.lastSeenAtMs);
      const keepAuto = Math.max(0, 36 - manual.length);
      state.profileEntries = [...manual, ...auto.slice(0, keepAuto)].slice(0, 36);
    } else {
      state.profileEntries = entries;
    }
    this.saveState(state);

    let addedCommitments = 0;
    for (const c of (params.implicitCommitments || []).slice(0, 2)) {
      const title = String(c?.title || "").trim();
      if (title.length < 3) continue;
      const alreadyOpen = this.listCommitments(false).some(
        (o) => normalizeMemoryText(o.title) === normalizeMemoryText(title)
      );
      if (!alreadyOpen) {
        this.upsertCommitment({
          title,
          dueHint: c.dueHint,
          source: "auto_distill",
        });
        addedCommitments += 1;
      }
    }

    return {
      addedProfile,
      reinforcedProfile,
      addedCommitments,
      profileItems: this.getProfileItems(),
    };
  }

  public buildMemoryCenterSnapshot(params: {
    workingMemoryItems: string[];
    vaultRoot: string;
  }): MemoryCenterSnapshot {
    const state = this.loadState();
    const now = Date.now();
    const profileEntries = this.getLivingProfileEntries();

    const workingEntries: LivingMemoryEntry[] = (params.workingMemoryItems || [])
      .map((raw) => String(raw || "").trim())
      .filter(Boolean)
      .map((text) => ({
        id: `wm_${crypto.createHash("sha1").update(normalizeMemoryText(text) || text).digest("hex").slice(0, 12)}`,
        text,
        source: "auto" as const,
        category: "working" as const,
        originDetail: "近期对话自动蒸馏 (L1 工作记忆)",
        observations: 1,
        createdAtMs: now,
        lastSeenAtMs: now,
      }));

    const vaultAnchors: LivingMemoryEntry[] = [];
    const scanDirs: Array<{ sub: string; label: string }> = [
      { sub: "04_topics", label: "知识库主题" },
      { sub: "03_ideas/developed", label: "深度思考沉淀" },
      { sub: "03_ideas/seeds", label: "灵感种子" },
    ];
    for (const { sub, label } of scanDirs) {
      const absDir = path.join(params.vaultRoot, sub);
      if (!fs.existsSync(absDir)) continue;
      try {
        const files = fs
          .readdirSync(absDir, { withFileTypes: true })
          .filter((e) => e.isFile() && e.name.endsWith(".md") && e.name !== "instruction.md")
          .map((e) => {
            const p = path.join(absDir, e.name);
            const st = fs.statSync(p);
            return { relPath: `${sub}/${e.name}`, absPath: p, name: path.basename(e.name, ".md"), mtimeMs: st.mtimeMs };
          })
          .sort((a, b) => b.mtimeMs - a.mtimeMs)
          .slice(0, 6);

        for (const f of files) {
          let summaryLine = "";
          try {
            const head = fs.readFileSync(f.absPath, "utf8").slice(0, 1200);
            const stripped = head
              .replace(/^---[\s\S]*?---\s*/, "")
              .split(/\r?\n/)
              .map((l) => l.replace(/^#+\s*/, "").trim())
              .filter((l) => l.length >= 8 && !l.startsWith("![[") && !l.startsWith("> "));
            summaryLine = stripped[0] ? `${f.name}：${stripped[0].slice(0, 72)}` : f.name;
          } catch {
            summaryLine = f.name;
          }
          const vId = `vault_${crypto.createHash("sha1").update(f.relPath).digest("hex").slice(0, 12)}`;
          const vNorm = normalizeMemoryText(summaryLine);
          if (this.isForgottenNormalized(state, vNorm)) continue;
          vaultAnchors.push({
            id: vId,
            text: summaryLine,
            source: "auto",
            category: "vault",
            originDetail: `${label} · ${f.relPath}`,
            observations: 1,
            createdAtMs: Math.floor(f.mtimeMs),
            lastSeenAtMs: Math.floor(f.mtimeMs),
          });
        }
      } catch {}
    }

    return {
      profileEntries,
      workingEntries,
      vaultAnchors: vaultAnchors.slice(0, 14),
      forgottenCount: Object.keys(state.forgottenFingerprints || {}).length,
      updatedAtMs: now,
    };
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
        subtitle: topChisuTitle ? `推荐先看：《${topChisuTitle.slice(0, 34)}》` : "可一键开启耳机通勤联播或精读",
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
        subtitle: `${agentDisplayLabel(latest.backend)} · ${latest.projectDisplayName || "工作区"}，可随时验收或追问`,
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
        actionPayload: `请作为我的随身助理，帮我把当前未闭环事项梳理成今天最省力的执行顺序，优先推进「${top.title}」。`,
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
      profileEntries: this.getLivingProfileEntries(),
      openLoops,
      nudges: nudges.slice(0, 4),
      recentChisuCount,
      activeAgentCount: activeAgents.length,
      vaultFocusTitles: vaultFocusTitles.slice(0, 6),
      generatedAtMs: nowMs,
    };
  }
}
