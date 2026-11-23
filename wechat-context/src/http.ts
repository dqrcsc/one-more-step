import { existsSync, readFileSync, statSync } from "node:fs";
import { ScheduleRunRejected } from "./scheduler/engine.ts";
import { ContextDatabase, ScheduledTaskKind, ScheduledTaskStatus } from "./db.ts";
import { WeChatContextPipeline, normalizeWeChatUrl } from "./pipeline.ts";
import { isWeChatArticleUrl, normalizeExternalWebUrl } from "./extractor/web.ts";
import { isFallbackLightNote } from "./vault/analyzer.ts";
import { decodeArtifactPaths } from "./storage.ts";
import { scanLocalAgentClis } from "./agent/subagent_cli.ts";
import { detectRequestedFormats } from "./agent/doc_exporter.ts";
import { parseNaturalScheduleSpec } from "./scheduler/time_parser.ts";
import { getNextCronRunMs, isValidCronExpression } from "./scheduler/cron.ts";
import { syncScheduledTasksToVaultQueue } from "./scheduler/vault_sync.ts";
import {
  buildConfigApiPayload,
  handleFetchProviderModels,
  handleInitVaultRequest,
  handlePickDirectoryRequest,
  handleSaveConfigRequest,
  handleTestLlmConnection,
  renderConfigPageHtml,
} from "./web_config.ts";
import {
  renderWeChatMarkdownReaderHtml,
  resolveMarkdownRelativeAsset,
} from "./reader_renderer.ts";

export const LOCAL_HOST = "127.0.0.1";

export function canonicalizeYouTubeOrWebUrl(rawUrl: string): string {
  try {
    const u = new URL(rawUrl);
    const host = u.hostname.toLowerCase();
    if (host === "youtu.be") {
      const vid = u.pathname.replace(/^\/+/, "").split("/")[0]?.trim();
      if (vid && /^[\w-]{6,20}$/.test(vid)) {
        return `https://www.youtube.com/watch?v=${vid}`;
      }
    }
    if (host === "youtube.com" || host.endsWith(".youtube.com")) {
      if (u.pathname === "/watch") {
        const vid = u.searchParams.get("v")?.trim();
        if (vid && /^[\w-]{6,20}$/.test(vid)) {
          return `https://www.youtube.com/watch?v=${vid}`;
        }
      }
      const shortsMatch = u.pathname.match(/^\/(shorts|live)\/([\w-]{6,20})/);
      if (shortsMatch) {
        return `https://www.youtube.com/${shortsMatch[1]}/${shortsMatch[2]}`;
      }
    }
    return rawUrl;
  } catch {
    return rawUrl;
  }
}

export function createHttpHandler(db: ContextDatabase, pipeline: WeChatContextPipeline) {
  return async (req: Request): Promise<Response> => {
    const url = new URL(req.url);
    // Loopback only, plus Host/Origin checks against browser-origin requests and DNS rebinding.
    if (!["127.0.0.1", "localhost"].includes(url.hostname)) return new Response("Forbidden host", { status: 403 });
    const origin = req.headers.get("origin");
    if (origin && origin !== url.origin) return new Response("Forbidden origin", { status: 403 });
    if (url.pathname === "/health" && req.method === "GET") {
      const activeAgentTasks = db.getActiveAgentTasks().map((t) => ({
        id: t.id,
        status: t.status,
        stage: t.stage,
        startedAt: t.started_at,
        heartbeatAt: t.heartbeat_at,
      }));
      return Response.json({
        ok: true,
        service: "wechat-as-context",
        credentialsConfigured: pipeline.clawbot.isConfigured(),
        connectionVerified: false,
        lastPoll: db.getState("clawbot_last_poll"),
        delivery: db.deliveryStatus(),
        pendingMessages: db.pendingMessages().length,
        activeAgentTasks,
      });
    }
    if (url.pathname === "/" && req.method === "GET") {
      return new Response(null, {
        status: 302,
        headers: { Location: "/config" },
      });
    }
    if (url.pathname === "/config" && req.method === "GET") {
      return new Response(renderConfigPageHtml(), {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8" },
      });
    }
    if (url.pathname === "/api/config" && req.method === "GET") {
      return Response.json(buildConfigApiPayload(db, pipeline));
    }
    if (url.pathname === "/api/config" && req.method === "POST") {
      return handleSaveConfigRequest(req, db, pipeline);
    }
    if (url.pathname === "/api/config/scan-cli" && req.method === "GET") {
      return Response.json({
        ok: true,
        discoveredClis: scanLocalAgentClis(pipeline.config),
        cliRoutingChain: pipeline.config.llm.cliRoutingChain,
      });
    }
    if (url.pathname === "/api/config/test-llm" && req.method === "POST") {
      return handleTestLlmConnection(req, pipeline);
    }
    if (url.pathname === "/api/config/fetch-models" && req.method === "POST") {
      return handleFetchProviderModels(req, pipeline);
    }
    if (url.pathname === "/api/config/pick-directory" && req.method === "POST") {
      return handlePickDirectoryRequest(req, pipeline);
    }
    if (url.pathname === "/api/config/init-vault" && req.method === "POST") {
      return handleInitVaultRequest(req, pipeline);
    }
    if (url.pathname === "/api/ingest" && req.method === "POST") {
      let body: {
        url: string;
        note?: string;
        title?: string;
        author?: string;
        fallbackMarkdown?: string;
      };
      try {
        if (!req.headers.get("content-type")?.startsWith("application/json")) return new Response("Expected application/json", { status: 415 });
        body = (await req.json()) as typeof body;
        if (body && "annotation" in body) throw new Error("Use note instead of annotation");
        if (
          !body ||
          typeof body.url !== "string" ||
          (body.note !== undefined && typeof body.note !== "string") ||
          (body.title !== undefined && typeof body.title !== "string") ||
          (body.author !== undefined && typeof body.author !== "string") ||
          (body.fallbackMarkdown !== undefined && typeof body.fallbackMarkdown !== "string")
        ) {
          throw new Error("Invalid article payload");
        }
        const rawTrimmed = body.url.trim();
        const parsedHost = new URL(rawTrimmed.replace(/&amp;/g, "&")).hostname.toLowerCase();
        const normalized =
          parsedHost === "mp.weixin.qq.com" || isWeChatArticleUrl(rawTrimmed)
            ? normalizeWeChatUrl(rawTrimmed)
            : normalizeExternalWebUrl(rawTrimmed);
        body.url = canonicalizeYouTubeOrWebUrl(normalized);
      } catch (error: any) { return Response.json({ ok: false, error: error.message }, { status: 400 }); }
      try {
        const { record } = db.enqueueArticle({
          msgid: `api_${crypto.randomUUID()}`,
          url: body.url,
          title: body.title,
          author: body.author,
          user_notes: body.note,
          channel: "cli",
          waitSeconds: 0,
        });
        const article = await pipeline.processArticleById(record.id, {
          sendWeChatReply: false,
          fallbackTitle: body.title,
          fallbackAuthor: body.author,
          fallbackMarkdown: body.fallbackMarkdown,
        });
        return Response.json({ ok: true, article });
      } catch (error: any) { return Response.json({ ok: false, error: error.message }, { status: 500 }); }
    }
    if (url.pathname === "/api/push" && req.method === "POST") {
      try {
        if (!req.headers.get("content-type")?.startsWith("application/json")) {
          return new Response("Expected application/json", { status: 415 });
        }
        const body = (await req.json()) as {
          text?: string;
          title?: string;
          files?: string[];
          formats?: string[];
          articleId?: number;
          includeArticleFile?: boolean;
          useSourceFile?: boolean;
          recipient?: string;
          queueOnly?: boolean;
        };
        if (body && ("docPath" in body || "exportFormats" in body)) {
          throw new Error("Use files and formats instead of docPath and exportFormats");
        }
        const formats = Array.isArray(body?.formats)
          ? detectRequestedFormats("", body.formats)
          : [];
        const result = await pipeline.pushToWeChat({
          text: typeof body?.text === "string" ? body.text : undefined,
          title: typeof body?.title === "string" ? body.title : undefined,
          files: Array.isArray(body?.files) ? body.files.map(String) : undefined,
          formats,
          articleId: typeof body?.articleId === "number" ? body.articleId : undefined,
          includeArticleFile: Boolean(body?.includeArticleFile),
          useSourceFile: Boolean(body?.useSourceFile),
          recipient: typeof body?.recipient === "string" ? body.recipient : undefined,
          queueOnly: Boolean(body?.queueOnly),
        });
        return Response.json(result, { status: result.ok ? 200 : 502 });
      } catch (error: any) {
        return Response.json(
          { ok: false, error: error?.message || String(error) },
          { status: 400 }
        );
      }
    }
    if (url.pathname === "/api/schedules" && req.method === "GET") {
      const statusParam = url.searchParams.get("status") || undefined;
      const tasks = db.listScheduledTasks({
        status: statusParam as ScheduledTaskStatus | undefined,
        limit: 100,
      });
      return Response.json({ ok: true, tasks });
    }
    if (url.pathname === "/api/schedules" && req.method === "POST") {
      try {
        if (!req.headers.get("content-type")?.startsWith("application/json")) {
          return new Response("Expected application/json", { status: 415 });
        }
        const body = (await req.json()) as {
          action?: "create" | "complete" | "cancel" | "run";
          id?: number;
          text?: string;
          title?: string;
          taskKind?: ScheduledTaskKind;
          cronExpr?: string;
          nextRunAt?: number;
        };
        const action = body?.action || "create";

        if (action === "complete" || action === "cancel") {
          const id = Number(body?.id);
          if (!Number.isFinite(id) || id <= 0) {
            throw new Error("Valid task id is required");
          }
          const existing = db.getScheduledTaskById(id);
          if (!existing) {
            return Response.json(
              { ok: false, error: `Scheduled task #${id} not found` },
              { status: 404 }
            );
          }
          const updated = db.updateScheduledTask(id, {
            status: action === "complete" ? "completed" : "cancelled",
            next_run_at: null,
            ...(action === "complete"
              ? { last_run_at: new Date().toISOString() }
              : {}),
          });
          syncScheduledTasksToVaultQueue(pipeline.config, db);
          return Response.json({ ok: true, task: updated });
        }

        if (action === "run") {
          const id = Number(body?.id);
          if (!Number.isFinite(id) || id <= 0) {
            throw new Error("Valid task id is required");
          }
          const outcomes = await pipeline.drainScheduledTasks({
            forceTaskId: id,
          });
          if (pipeline.clawbot.isConfigured()) {
            await pipeline.flushReplies({ ignoreBusy: true });
          }
          const ok = outcomes.length > 0 && outcomes.every(outcome => outcome.status === "succeeded");
          return Response.json({ ok, outcomes }, { status: ok ? 200 : 409 });
        }

        const rawText = String(body?.text || body?.title || "").trim();
        if (!rawText) {
          throw new Error("Schedule text or title is required");
        }
        const spec = parseNaturalScheduleSpec(rawText);
        let scheduleType = spec.scheduleType;
        let cronExpr = spec.cronExpr;
        let nextRunAt = spec.nextRunAt;
        let isDefaultTime = spec.isDefaultTime;

        if (body?.cronExpr && isValidCronExpression(body.cronExpr)) {
          scheduleType = "cron";
          cronExpr = body.cronExpr.trim();
          nextRunAt = getNextCronRunMs(cronExpr, Date.now(), "Asia/Shanghai");
          isDefaultTime = false;
        } else if (typeof body?.nextRunAt === "number" && body.nextRunAt > 0) {
          scheduleType = "once";
          nextRunAt = body.nextRunAt;
          isDefaultTime = false;
        }

        const taskKind = body?.taskKind || spec.taskKind;
        const created = db.createScheduledTask({
          senderId: pipeline.clawbot.resolveDefaultRecipient() || "",
          title: (body?.title || spec.cleanedTitle || rawText).slice(0, 80),
          taskKind,
          scheduleType,
          cronExpr,
          timezone: "Asia/Shanghai",
          nextRunAt,
          isDefaultTime,
          actionPayload: {
            executor:
              taskKind === "digest" ? "web_search_digest" : "reminder_push",
            prompt: rawText,
            includeVaultNow: true,
            includeRecentArticlesHours: 24,
            archiveToVault: true,
          },
        });
        syncScheduledTasksToVaultQueue(pipeline.config, db);
        return Response.json({ ok: true, task: created });
      } catch (error: any) {
        return Response.json(
          { ok: false, error: error?.message || String(error), ...(error instanceof ScheduleRunRejected ? { code: error.code } : {}) },
          { status: error instanceof ScheduleRunRejected ? (error.code === "not_found" ? 404 : 409) : 400 }
        );
      }
    }
    if (url.pathname === "/api/overview" && req.method === "GET") {
      const limit = Math.min(200, Math.max(1, Number(url.searchParams.get("limit") || 50)));
      const triage = url.searchParams.get("triage") || undefined;
      const status = url.searchParams.get("status") || undefined;
      const creds = pipeline.clawbot.getCredentials();
      const rawArticles = db.listArticles({ limit, triage, status });
      const articles = rawArticles.map((a) => ({
        ...a,
        isFallbackNote: isFallbackLightNote(a.short_summary || ""),
      }));
      const turns = db.listConversationTurns({ limit: Math.min(60, limit) });
      const schedules = db.listScheduledTasks({ limit: 50 });
      const agentTasks = db.getRecentAgentTasks(undefined, 25);
      const activeAgentTasks = db.getActiveAgentTasks().map((t) => ({
        id: t.id,
        status: t.status,
        stage: t.stage,
        startedAt: t.started_at,
        heartbeatAt: t.heartbeat_at,
      }));
      return Response.json({
        ok: true,
        status: {
          credentialsConfigured: pipeline.clawbot.isConfigured(),
          botId: creds?.botId || "",
          lastPoll: db.getState("clawbot_last_poll"),
          delivery: db.deliveryStatus(),
          pendingMessages: db.pendingMessages().length,
          activeAgentTasks,
          vaultMode: pipeline.config.vaultMode || "standalone",
          vaultRoot: pipeline.config.vaultRoot,
          dataDir: pipeline.config.dataDir,
        },
        articles,
        turns,
        schedules,
        agentTasks,
      });
    }
    if ((url.pathname === "/api/asset" || url.pathname === "/api/wechat/asset") && req.method === "GET") {
      const relPath = url.searchParams.get("path") || "";
      if (!relPath || relPath.includes("..") || relPath.startsWith("/") || relPath.startsWith("~") || relPath.includes("\\")) {
        return Response.json({ ok: false, error: "Forbidden asset path" }, { status: 403 });
      }
      let docPath: string | null = null;
      const turnIdParam = url.searchParams.get("turnId");
      if (turnIdParam) {
        const turnId = Number(turnIdParam);
        const turn = Number.isFinite(turnId) && turnId > 0
          ? (db.db.query("SELECT * FROM conversation_turns WHERE id = ?").get(turnId) as any)
          : null;
        if (!turn) return Response.json({ ok: false, error: "Turn not found" }, { status: 404 });
        const artifactPaths = decodeArtifactPaths(turn.deposited_path || "");
        docPath = artifactPaths.find((p) => /\.(md|markdown|txt)$/i.test(p)) || artifactPaths[0] || null;
      } else {
        const articleId = Number(url.searchParams.get("articleId"));
        if (!Number.isFinite(articleId) || articleId <= 0) {
          return Response.json({ ok: false, error: "Valid articleId required" }, { status: 400 });
        }
        const article = db.getArticleById(articleId);
        if (!article) return Response.json({ ok: false, error: "Article not found" }, { status: 404 });
        const docKind = url.searchParams.get("doc");
        docPath = docKind === "note"
          ? (article.note_md_path || article.source_md_path)
          : (article.source_md_path || article.note_md_path);
      }

      const resolved = resolveMarkdownRelativeAsset(docPath, relPath);
      if (!resolved) {
        return Response.json({ ok: false, error: "Asset not found or outside document directory" }, { status: 404 });
      }
      const bytes = readFileSync(resolved.realAbsPath);
      return new Response(bytes, {
        status: 200,
        headers: {
          "Content-Type": resolved.mimeType,
          "Cache-Control": "private, max-age=300",
          "X-Content-Type-Options": "nosniff",
          "Cross-Origin-Resource-Policy": "same-origin",
          "Content-Security-Policy": "default-src 'none'; img-src 'self'",
        },
      });
    }
    if (url.pathname === "/api/article" && req.method === "GET") {
      const readBoundedUtf8 = (filePath?: string | null, maxBytes = 250_000): { content: string; truncated: boolean } | null => {
        if (!filePath || !existsSync(filePath)) return null;
        try {
          const st = statSync(filePath);
          if (!st.isFile()) return null;
          const raw = readFileSync(filePath, "utf8");
          if (raw.length > maxBytes) {
            return { content: raw.slice(0, maxBytes), truncated: true };
          }
          return { content: raw, truncated: false };
        } catch {
          return null;
        }
      };

      const turnIdParam = url.searchParams.get("turnId");
      if (turnIdParam) {
        const turnId = Number(turnIdParam);
        const turn = Number.isFinite(turnId) && turnId > 0
          ? (db.db.query("SELECT * FROM conversation_turns WHERE id = ?").get(turnId) as any)
          : null;
        if (!turn) return Response.json({ ok: false, error: "Turn not found" }, { status: 404 });
        const artifactPaths = decodeArtifactPaths(turn.deposited_path || "");
        const primaryMdPath = artifactPaths.find((p) => /\.(md|markdown|txt)$/i.test(p)) || artifactPaths[0] || "";
        const deposited = readBoundedUtf8(primaryMdPath);
        const depositedRendered = deposited
          ? renderWeChatMarkdownReaderHtml({
              markdown: deposited.content,
              docPath: primaryMdPath,
              turnId,
              docKind: "turn",
            })
          : null;
        return Response.json({
          ok: true,
          turn,
          artifactPaths,
          depositedMarkdown: deposited?.content ?? null,
          depositedHtml: depositedRendered?.html ?? null,
          resolvedImages: depositedRendered?.resolvedImages ?? {},
          depositedTruncated: Boolean(deposited?.truncated),
        });
      }

      const id = Number(url.searchParams.get("id"));
      if (!Number.isFinite(id) || id <= 0) {
        return Response.json({ ok: false, error: "Valid article id required" }, { status: 400 });
      }
      const article = db.getArticleById(id);
      if (!article) {
        return Response.json({ ok: false, error: `Article #${id} not found` }, { status: 404 });
      }
      const noteDoc = readBoundedUtf8(article.note_md_path);
      const sourceDoc = readBoundedUtf8(article.source_md_path);
      const noteRendered = noteDoc
        ? renderWeChatMarkdownReaderHtml({
            markdown: noteDoc.content,
            docPath: article.note_md_path || article.source_md_path,
            articleId: article.id,
            docKind: "note",
          })
        : null;
      const sourceRendered = sourceDoc
        ? renderWeChatMarkdownReaderHtml({
            markdown: sourceDoc.content,
            docPath: article.source_md_path || article.note_md_path,
            articleId: article.id,
            docKind: "source",
          })
        : null;
      const summaryText = article.short_summary || article.digest || "";
      const summaryRendered = summaryText
        ? renderWeChatMarkdownReaderHtml({
            markdown: summaryText,
            docPath: article.note_md_path || article.source_md_path,
            articleId: article.id,
            docKind: "summary",
            stripHeaderBlock: false,
          })
        : null;
      return Response.json({
        ok: true,
        article: {
          ...article,
          isFallbackNote: isFallbackLightNote(noteDoc?.content || article.short_summary || ""),
        },
        noteMarkdown: noteDoc?.content ?? null,
        noteHtml: noteRendered?.html ?? null,
        noteTruncated: Boolean(noteDoc?.truncated),
        sourceMarkdown: sourceDoc?.content ?? null,
        sourceHtml: sourceRendered?.html ?? null,
        sourceTruncated: Boolean(sourceDoc?.truncated),
        summaryHtml: summaryRendered?.html ?? null,
        resolvedImages: {
          ...(sourceRendered?.resolvedImages ?? {}),
          ...(noteRendered?.resolvedImages ?? {}),
          ...(summaryRendered?.resolvedImages ?? {}),
        },
      });
    }
    if (url.pathname === "/internal/edge/snapshot" && req.method === "GET") {
      return Response.json(pipeline.exportEdgeStandbySnapshot());
    }
    if (url.pathname === "/internal/edge/pause-poll" && req.method === "POST") {
      try {
        const body = (await req.json()) as { paused?: boolean };
        pipeline.standbyPaused = Boolean(body?.paused);
        return Response.json({ ok: true, standbyPaused: pipeline.standbyPaused });
      } catch (err: any) {
        return Response.json({ ok: false, error: err?.message || String(err) }, { status: 400 });
      }
    }
    if (url.pathname === "/internal/edge/handoff" && req.method === "POST") {
      try {
        if (!req.headers.get("content-type")?.startsWith("application/json")) {
          return new Response("Expected application/json", { status: 415 });
        }
        const body = (await req.json()) as any;
        const result = await pipeline.commitEdgeStandbyHandoff(body);
        pipeline.standbyPaused = false;
        if (result.queuedDeferredCount > 0) {
          void (async () => {
            await pipeline.drainAgentQueue().catch(() => {});
            await pipeline.drainReadyQueue().catch(() => {});
            if (pipeline.clawbot.isConfigured()) {
              await pipeline.flushReplies({ ignoreBusy: true }).catch(() => {});
            }
          })();
        }
        return Response.json(result);
      } catch (err: any) {
        return Response.json({ ok: false, error: err?.message || String(err) }, { status: 500 });
      }
    }
    return new Response("Not found", { status: 404 });
  };
}
