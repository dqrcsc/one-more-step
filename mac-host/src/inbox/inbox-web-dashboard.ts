import fs from "node:fs";
import path from "node:path";
import type { OmsDaemon } from "../daemon/oms-daemon.js";
import { PhoneSessionManager } from "../transport/session-manager.js";
import type { IntentTag } from "@oms/shared-protocol";

function isLoopbackHost(hostname: string): boolean {
  return PhoneSessionManager.isLoopbackAddress(hostname.replace(/^\[|\]$/g, ""));
}

function containedFile(root: string, filePath: string): string | null {
  try {
    const canonicalRoot = fs.realpathSync(root);
    const canonicalFile = fs.realpathSync(filePath);
    const relative = path.relative(canonicalRoot, canonicalFile);
    if (!relative || relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) return null;
    return fs.statSync(canonicalFile).isFile() ? canonicalFile : null;
  } catch {
    return null;
  }
}

const SAFE_INLINE_MIME_TYPES = new Set([
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/gif",
  "audio/mp4",
  "audio/mpeg",
  "audio/wav",
  "audio/ogg",
  "audio/aac",
  "audio/x-m4a",
  "video/mp4",
  "video/webm",
  "video/quicktime",
  "application/pdf",
]);

function encodeContentDispositionFilename(rawName: string): string {
  const base = path.basename(rawName || "attachment.bin").replace(/[\r\n"]/g, "_");
  const asciiFallback = base.replace(/[^\x20-\x7E]/g, "_") || "attachment.bin";
  return `filename="${asciiFallback}"; filename*=UTF-8''${encodeURIComponent(base)}`;
}

function resolveAttachmentResponseHeaders(fileName: string, rawMime?: string): Record<string, string> {
  const normalizedMime = (rawMime || "").split(";")[0].trim().toLowerCase();
  const ext = path.extname(fileName || "").toLowerCase();
  const inferredMime =
    normalizedMime ||
    (ext === ".png"
      ? "image/png"
      : ext === ".jpg" || ext === ".jpeg"
      ? "image/jpeg"
      : ext === ".webp"
      ? "image/webp"
      : ext === ".gif"
      ? "image/gif"
      : ext === ".m4a"
      ? "audio/mp4"
      : ext === ".mp3"
      ? "audio/mpeg"
      : ext === ".wav"
      ? "audio/wav"
      : ext === ".ogg"
      ? "audio/ogg"
      : ext === ".aac"
      ? "audio/aac"
      : ext === ".mp4"
      ? "video/mp4"
      : ext === ".webm"
      ? "video/webm"
      : ext === ".pdf"
      ? "application/pdf"
      : "application/octet-stream");

  // Never render HTML, SVG, XML, or scriptable documents inline under the dashboard origin.
  const allowInline = SAFE_INLINE_MIME_TYPES.has(inferredMime) && ext !== ".svg" && ext !== ".html" && ext !== ".htm" && ext !== ".xhtml" && ext !== ".xml";
  const safeMime = allowInline ? inferredMime : "application/octet-stream";
  const dispositionType = allowInline ? "inline" : "attachment";

  return {
    "Content-Type": safeMime,
    "Content-Disposition": `${dispositionType}; ${encodeContentDispositionFilename(fileName)}`,
    "Content-Security-Policy":
      "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'self'",
    "X-Content-Type-Options": "nosniff",
    "Cross-Origin-Resource-Policy": "same-origin",
    "Cache-Control": "private, max-age=3600",
  };
}

/**
 * Module 06+: Mac Real-Time Web Inbox & Bidirectional Collaboration Dashboard
 * Served strictly on localhost: http://127.0.0.1:19527/inbox (and /dashboard)
 */
export async function handleDashboardHttpRequest(
  daemon: OmsDaemon,
  req: Request,
  url: URL,
  clientIp = "127.0.0.1"
): Promise<Response | null> {
  const isWeChatRoute =
    url.pathname === "/wechat" ||
    url.pathname.startsWith("/wechat/") ||
    url.pathname === "/config" ||
    url.pathname.startsWith("/api/wechat") ||
    url.pathname.startsWith("/api/config") ||
    url.pathname === "/api/schedules" ||
    url.pathname === "/api/ingest" ||
    url.pathname === "/api/push";

  const isDashboardRoute =
    isWeChatRoute ||
    url.pathname === "/inbox" ||
    url.pathname === "/dashboard" ||
    url.pathname === "/mirror" ||
    url.pathname.startsWith("/api/inbox");

  const requestHost = req.headers.get("host");
  let validHost = isLoopbackHost(url.hostname);
  if (requestHost) {
    try {
      validHost = validHost && isLoopbackHost(new URL(`http://${requestHost}`).hostname);
    } catch {
      validHost = false;
    }
  }
  if (isDashboardRoute && (!PhoneSessionManager.isLoopbackAddress(clientIp) || !validHost)) {
    return new Response(
      JSON.stringify({
        ok: false,
        error:
          "Forbidden: Mac Inbox Dashboard and Control API are strictly restricted to localhost (127.0.0.1 / ::1).",
      }),
      {
        status: 403,
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "no-store",
        },
      }
    );
  }

  if (isDashboardRoute) {
    const fetchSite = req.headers.get("sec-fetch-site");
    if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") {
      return new Response(
        JSON.stringify({
          ok: false,
          error: "Forbidden cross-site request to localhost dashboard.",
        }),
        {
          status: 403,
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-store",
          },
        }
      );
    }
  }

  const dashboardCspBase =
    "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; frame-src 'self'; object-src 'none'; base-uri 'none'";

  if (isWeChatRoute) {
    if (url.pathname === "/wechat") {
      return new Response(null, {
        status: 302,
        headers: { Location: "/inbox?module=wechat" },
      });
    }
    if (url.pathname === "/api/wechat/to-phone" && req.method === "POST") {
      try {
        const body = (await req.json()) as Record<string, any>;
        const res = await daemon.pushWeChatNoteToPhone({
          articleId: body.articleId !== undefined ? Number(body.articleId) : undefined,
          title: typeof body.title === "string" ? body.title : undefined,
          markdown: typeof body.markdown === "string" ? body.markdown : undefined,
          queueIfOffline: true,
        });
        return Response.json(res, { status: res.ok ? 200 : 400 });
      } catch (err: any) {
        return Response.json({ ok: false, error: err?.message || String(err) }, { status: 400 });
      }
    }

    let upstreamPath = url.pathname;
    if (url.pathname === "/wechat/config" || url.pathname === "/config") {
      upstreamPath = "/config";
    } else if (url.pathname === "/api/wechat/health") {
      upstreamPath = "/health";
    } else if (url.pathname.startsWith("/api/wechat/")) {
      upstreamPath = "/api/" + url.pathname.slice("/api/wechat/".length);
    }

    const proxied = await daemon.wechat.proxyLoopbackRequest(req, upstreamPath, url.search);
    if (upstreamPath === "/config" && proxied.status === 200) {
      const headers = new Headers(proxied.headers);
      headers.set("X-Frame-Options", "SAMEORIGIN");
      return new Response(await proxied.arrayBuffer(), {
        status: proxied.status,
        headers,
      });
    }
    return proxied;
  }

  if (url.pathname === "/inbox" || url.pathname === "/dashboard") {
    return new Response(renderDashboardHtml(), {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store, no-cache, must-revalidate",
        "Content-Security-Policy": `${dashboardCspBase}; frame-ancestors 'none'`,
        "X-Frame-Options": "DENY",
        "X-Content-Type-Options": "nosniff",
        "Cross-Origin-Resource-Policy": "same-origin",
      },
    });
  }

  if (url.pathname === "/mirror") {
    const mirrorCsp = dashboardCspBase.replace("connect-src 'self' ws: wss:", `connect-src 'self' ${url.protocol === "https:" ? "wss" : "ws"}://${url.host}`);
    return new Response(renderMirrorStandaloneHtml(), {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store, no-cache, must-revalidate",
        "Content-Security-Policy": `${mirrorCsp}; frame-ancestors 'self'`,
        "X-Frame-Options": "SAMEORIGIN",
        "X-Content-Type-Options": "nosniff",
        "Cross-Origin-Resource-Policy": "same-origin",
      },
    });
  }

  if (url.pathname === "/api/inbox/mirror/frame" && req.method === "GET") {
    const latest = daemon.session.getLatestScreenFrame();
    if (!latest || latest.decoded.data.byteLength === 0) {
      return new Response(null, {
        status: 204,
        headers: {
          "Cache-Control": "no-store",
          "X-Content-Type-Options": "nosniff",
          "Cross-Origin-Resource-Policy": "same-origin",
          "X-Oms-Flags": String(latest?.decoded.flags ?? 0),
          "X-Oms-Seq": String(latest?.decoded.seq ?? 0),
        },
      });
    }
    const mime = latest.decoded.codec === 2 ? "image/jpeg" : "image/webp";
    return new Response(Buffer.from(latest.decoded.data), {
      headers: {
        "Content-Type": mime,
        "Cache-Control": "no-store, no-cache, must-revalidate",
        "X-Content-Type-Options": "nosniff",
        "Cross-Origin-Resource-Policy": "same-origin",
        "X-Oms-Seq": String(latest.decoded.seq),
        "X-Oms-Width": String(latest.decoded.width),
        "X-Oms-Height": String(latest.decoded.height),
        "X-Oms-Flags": String(latest.decoded.flags),
        "X-Oms-Timestamp": String(latest.decoded.timestampMs),
      },
    });
  }

  if (url.pathname === "/api/inbox" && req.method === "GET") {
    const tag = (url.searchParams.get("tag") || "all") as "all" | IntentTag;
    const onlyUnprocessed = url.searchParams.get("unprocessed") === "1";
    const searchQuery = url.searchParams.get("q") || "";
    const limit = Number(url.searchParams.get("limit") || 100);

    const items = daemon.inbox.queryLatest({
      tag,
      onlyUnprocessed,
      searchQuery,
      limit,
    });
    const status = await daemon.handleIpcAction("phone_presence_status", {});
    const hostPrefs = daemon.getHostPreferences();
    const mirror = daemon.getMirrorStatus();

    return new Response(
      JSON.stringify({
        ok: true,
        items,
        status,
        preferences: hostPrefs,
        mirror,
      }),
      {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "no-store",
          "X-Content-Type-Options": "nosniff",
          "Cross-Origin-Resource-Policy": "same-origin",
        },
      }
    );
  }

  if (url.pathname === "/api/inbox/file" && req.method === "GET") {
    const itemId = url.searchParams.get("itemId") || "";
    const fileName = url.searchParams.get("file") || "";
    const item = daemon.inbox.getItem(itemId);
    if (!item) {
      return new Response("Item not found", { status: 404 });
    }
    const fileEntry = fileName
      ? item.files.find((f) => f.fileName === fileName)
      : item.files[0];
    if (!fileEntry || !fs.existsSync(fileEntry.absPath)) {
      return new Response("Attachment file not found", { status: 404 });
    }

    // Security check: ensure file is inside inboxRootDir
    const resolved = containedFile(daemon.inbox.inboxRootDir, fileEntry.absPath);
    if (!resolved) {
      return new Response("Forbidden path", { status: 403 });
    }

    return new Response(Bun.file(resolved), {
      headers: resolveAttachmentResponseHeaders(fileEntry.fileName, fileEntry.mimeType),
    });
  }

  if (url.pathname === "/api/inbox/action" && req.method === "POST") {
    const origin = req.headers.get("origin");
    const fetchSite = req.headers.get("sec-fetch-site");
    if ((origin && origin !== url.origin) || (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none")) {
      return Response.json({ ok: false, error: "Forbidden request origin" }, { status: 403 });
    }
    if (req.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json") {
      return Response.json({ ok: false, error: "Content-Type must be application/json" }, { status: 415 });
    }
    try {
      const body = (await req.json()) as Record<string, any>;
      if (!body || typeof body !== "object" || Array.isArray(body)) {
        return Response.json({ ok: false, error: "Expected a JSON object" }, { status: 400 });
      }
      const action = String(body.action || "");

      switch (action) {
        case "mark_processed": {
          const ok = daemon.inbox.markProcessed(
            String(body.itemId || ""),
            body.processed !== false
          );
          return Response.json({ ok });
        }
        case "delete_item": {
          const ok = daemon.inbox.deleteItem(String(body.itemId || ""));
          return Response.json({ ok });
        }
        case "clear_processed": {
          const removedCount = daemon.inbox.clearProcessed();
          return Response.json({ ok: true, removedCount });
        }
        case "open_preview":
        case "reveal_in_finder": {
          const item = daemon.inbox.getItem(String(body.itemId || ""));
          const fileEntry = body.absPath
            ? item?.files.find((file) => path.resolve(file.absPath) === path.resolve(String(body.absPath)))
            : item?.files[0];
          if (!fileEntry) {
            return Response.json({ ok: false, error: "Attachment is not registered to this item" }, { status: 403 });
          }
          const targetPath = containedFile(daemon.inbox.inboxRootDir, fileEntry.absPath);
          if (!targetPath) {
            return Response.json({ ok: false, error: "Forbidden attachment path" }, { status: 403 });
          }
          daemon.openFileOnMac(targetPath, action === "reveal_in_finder");
          return Response.json({ ok: true });
        }
        case "copy_mac_clipboard": {
          const text = String(body.text || "");
          daemon.copyTextToMacClipboard(text);
          return Response.json({ ok: true });
        }
        case "update_preferences": {
          const updated = daemon.updateHostPreferences({
            autoCopyTextToClipboard: body.autoCopyTextToClipboard,
            autoPreviewImageOnMac: body.autoPreviewImageOnMac,
            autoSyncOtpToMac: body.autoSyncOtpToMac,
            autoSyncWeChatToPhone: body.autoSyncWeChatToPhone,
            autoSwitchAirpods: body.autoSwitchAirpods,
            pauseOtherOnAirpodsSwitch: body.pauseOtherOnAirpodsSwitch,
            preferredAirpodsMac: body.preferredAirpodsMac,
          });
          return Response.json({ ok: true, preferences: updated });
        }
        case "airpods_switch_mac": {
          const res = await daemon.switchAirpodsToMac(
            body.targetMac ? String(body.targetMac) : undefined,
            "web_dashboard"
          );
          return Response.json({ ok: res.ok, result: res });
        }
        case "airpods_switch_phone": {
          const res = await daemon.switchAirpodsToPhone(
            body.targetMac ? String(body.targetMac) : undefined,
            "web_dashboard"
          );
          return Response.json({ ok: res.ok, result: res });
        }
        case "push_to_phone": {
          const text = String(body.text || "").trim();
          const title = String(body.title || "Mac 工作台推送").trim();
          const enableTts = Boolean(body.enableTts);
          if (!text) {
            return Response.json(
              { ok: false, error: "发送内容不能为空" },
              { status: 400 }
            );
          }
          const res = await daemon.handleIpcAction("phone_file_push", {
            markdownOrText: text,
            title,
            enableCommuteTts: enableTts,
          });
          return Response.json({ ok: true, result: res });
        }
        case "set_phone_clipboard": {
          const text = String(body.text || "").trim();
          if (!text) {
            return Response.json(
              { ok: false, error: "剪贴板内容不能为空" },
              { status: 400 }
            );
          }
          const res = await daemon.handleIpcAction("phone_clipboard_sync", {
            action: "set_phone_clipboard",
            textToSet: text,
          });
          return Response.json({ ok: true, result: res });
        }
        case "get_phone_clipboard": {
          const res = await daemon.handleIpcAction("phone_clipboard_sync", {
            action: "get_phone_clipboard",
            includeRecentOtp: true,
          });
          if (res?.text && body.copyToMac !== false) {
            daemon.copyTextToMacClipboard(String(res.text));
          }
          return Response.json({ ok: true, result: res });
        }
        case "phone_control": {
          const ctrlParams = body.params || body;
          const res = await daemon.handleIpcAction("phone_device_control", ctrlParams);
          return Response.json({ ok: true, result: res });
        }
        case "transcribe_item": {
          const itemId = String(body.itemId || "");
          const res = await daemon.handleIpcAction("inbox.transcribe_item", { itemId });
          return Response.json({ ok: res.ok, result: res });
        }
        case "ota_update": {
          const res = await daemon.handleIpcAction("phone_ota_update", {});
          return Response.json({ ok: true, result: res });
        }
        case "mirror_start": {
          const status = await daemon.startMirror(body.params || body);
          return Response.json({ ok: true, status });
        }
        case "mirror_stop": {
          const status = await daemon.stopMirror();
          return Response.json({ ok: true, status });
        }
        case "mirror_status": {
          return Response.json({ ok: true, status: daemon.getMirrorStatus() });
        }
        case "mirror_input": {
          const result = await daemon.sendMirrorInput(body.params || body);
          return Response.json({ ok: true, result });
        }
        default:
          return Response.json(
            { ok: false, error: `Unknown action: ${action}` },
            { status: 400 }
          );
      }
    } catch (err: any) {
      return Response.json(
        { ok: false, error: err?.message || String(err) },
        { status: 500 }
      );
    }
  }

  return null;
}

function renderDashboardHtml(): string {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>OneMoreStep · Mac 实时收件箱与协同看板</title>
<style>
  :root {
    --bg: #0B1120;
    --panel: #1E293B;
    --panel-hover: #263449;
    --border: #334155;
    --text: #F8FAFC;
    --muted: #94A3B8;
    --accent: #38BDF8;
    --green: #4ADE80;
    --amber: #FBBF24;
    --red: #F87171;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    padding: 0;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", sans-serif;
  }
  .container {
    max-width: 1080px;
    margin: 0 auto;
    padding: 20px;
  }
  .header-bar {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 16px 20px;
    margin-bottom: 16px;
  }
  .title-block h1 {
    margin: 0 0 6px 0;
    font-size: 20px;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .status-line {
    font-size: 13px;
    color: var(--muted);
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
    align-items: center;
  }
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 3px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    background: #0F172A;
    border: 1px solid var(--border);
  }
  .toggles-box {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
  }
  .toggle-label {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: #CBD5E1;
    cursor: pointer;
    background: #0F172A;
    padding: 6px 10px;
    border-radius: 8px;
    border: 1px solid var(--border);
    user-select: none;
  }
  .composer-card {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 14px 18px;
    margin-bottom: 16px;
  }
  .status-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    gap: 10px;
    margin-bottom: 12px;
  }
  .stat-tile {
    background: #0F172A;
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 10px 12px;
  }
  .stat-label {
    font-size: 11px;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .stat-value {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
    line-height: 1.4;
    word-break: break-word;
  }
  .ctrl-row {
    display: flex;
    flex-wrap: wrap;
    gap: 7px;
    align-items: center;
    padding-top: 8px;
    border-top: 1px solid var(--border);
  }
  .perm-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 8px;
  }
  .perm-chip {
    font-size: 11px;
    padding: 2px 8px;
    border-radius: 6px;
    background: #0F172A;
    border: 1px solid var(--border);
  }
  .composer-row {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .input, .textarea {
    background: #0F172A;
    border: 1px solid var(--border);
    color: var(--text);
    border-radius: 10px;
    padding: 10px 12px;
    font-size: 13px;
    outline: none;
    font-family: inherit;
  }
  .input:focus, .textarea:focus {
    border-color: var(--accent);
  }
  .textarea {
    flex: 1;
    min-width: 260px;
    min-height: 42px;
    resize: vertical;
  }
  .btn {
    background: #0284C7;
    color: #fff;
    border: none;
    border-radius: 9px;
    padding: 8px 13px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    text-decoration: none;
    transition: opacity 0.15s;
  }
  .btn:hover { opacity: 0.9; }
  .btn-secondary {
    background: #0F172A;
    border: 1px solid var(--border);
    color: #E2E8F0;
  }
  .btn-green { background: #16A34A; }
  .btn-danger { background: #991B1B; }
  .filter-bar {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
  }
  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .tab {
    background: var(--panel);
    border: 1px solid var(--border);
    color: #CBD5E1;
    padding: 6px 12px;
    border-radius: 999px;
    font-size: 12px;
    cursor: pointer;
    font-weight: 500;
  }
  .tab.active {
    background: #0284C7;
    border-color: #38BDF8;
    color: #fff;
    font-weight: 600;
  }
  .items-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .item-card {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 16px;
    transition: border-color 0.15s;
  }
  .item-card.unprocessed {
    border-left: 4px solid var(--accent);
  }
  .item-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 8px;
  }
  .item-meta {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--muted);
  }
  .tag-pill {
    padding: 2px 8px;
    border-radius: 6px;
    font-weight: 600;
    font-size: 11px;
    background: #0F172A;
    color: var(--accent);
    border: 1px solid #0369A1;
  }
  .item-title {
    font-size: 15px;
    font-weight: 700;
    margin: 4px 0 8px 0;
    color: #fff;
  }
  .item-text {
    background: #0F172A;
    border: 1px solid #1E293B;
    border-radius: 10px;
    padding: 12px;
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
    color: #E2E8F0;
    margin-bottom: 10px;
  }
  .item-voice {
    background: rgba(251, 191, 36, 0.1);
    border: 1px solid rgba(251, 191, 36, 0.3);
    color: var(--amber);
    padding: 8px 12px;
    border-radius: 8px;
    font-size: 13px;
    margin-bottom: 10px;
  }
  .attachments-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin: 10px 0;
  }
  .attachment-box {
    background: #0F172A;
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 10px;
    max-width: 360px;
  }
  .attachment-img {
    display: block;
    max-width: 100%;
    max-height: 260px;
    border-radius: 8px;
    cursor: zoom-in;
    object-fit: contain;
    background: #000;
    margin-bottom: 8px;
  }
  .attachment-meta {
    font-size: 11px;
    color: var(--muted);
    margin-bottom: 8px;
    word-break: break-all;
  }
  .action-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 8px;
  }
  .empty-state {
    text-align: center;
    padding: 60px 20px;
    color: var(--muted);
    background: var(--panel);
    border: 1px dashed var(--border);
    border-radius: 16px;
  }
  .lightbox {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.88);
    display: none;
    align-items: center;
    justify-content: center;
    z-index: 9999;
    padding: 24px;
    cursor: zoom-out;
  }
  .lightbox.open { display: flex; }
  .lightbox img {
    max-width: 95vw;
    max-height: 92vh;
    border-radius: 10px;
    box-shadow: 0 20px 60px rgba(0,0,0,0.6);
  }
  .toast {
    position: fixed;
    bottom: 24px;
    right: 24px;
    background: #0284C7;
    color: #fff;
    padding: 10px 18px;
    border-radius: 10px;
    font-size: 13px;
    font-weight: 600;
    display: none;
    z-index: 10000;
    box-shadow: 0 8px 24px rgba(0,0,0,0.4);
  }
</style>
</head>
<body>
<div class="container">
  <div class="header-bar">
    <div class="title-block">
      <h1>⚡ 青简 · Mac 实时收件箱看板 & 微信尺素总控台</h1>
      <div class="status-line" id="statusLine">正在同步连接状态...</div>
    </div>
    <div class="toggles-box">
      <label class="toggle-label" title="手机收到短信/应用验证码时，实时弹出 Mac 通知并将纯数字验证码写入 Mac 剪贴板">
        <input type="checkbox" id="chkAutoOtp" onchange="savePrefs()"/>
        🔑 验证码实时通知 & 复制
      </label>
      <label class="toggle-label" title="手机发来文字或语音转写时，自动复制进 Mac 系统剪贴板，直接 Cmd+V 即可粘贴">
        <input type="checkbox" id="chkAutoCopy" onchange="savePrefs()"/>
        📋 收到文字自动进 Mac 剪贴板
      </label>
      <label class="toggle-label" title="手机发来截图或照片时，自动在 Mac 预览 (Preview.app) 中弹窗打开">
        <input type="checkbox" id="chkAutoPreview" onchange="savePrefs()"/>
        🖼️ 收到图片自动弹窗预览
      </label>
      <label class="toggle-label" title="微信尺素完成新文章初筛或定时早报时，静默同步伴读卡片至手机时间流「微信尺素」Tab 供通勤朗读">
        <input type="checkbox" id="chkAutoWxPhone" onchange="savePrefs()"/>
        💬 微信伴读自动入手机流
      </label>
      <label class="toggle-label" title="识别到 Mac 或小米手机开始输出音频时，自动把已配对的 AirPods 切换到发声设备并暂停对端播放">
        <input type="checkbox" id="chkAutoAirpods" onchange="savePrefs()"/>
        🎧 AirPods 自动切换 & 暂停对端
      </label>
      <a class="btn btn-secondary" href="/install">📲 无线安装/配对页</a>
    </div>
  </div>

  <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px;padding:8px 12px;background:#0F172A;border:1px solid #1E293B;border-radius:12px;flex-wrap:wrap;">
    <div class="tabs" id="topModuleTabs">
      <button class="tab active" data-mod="phone" onclick="switchTopModule('phone')">📬 手机协同流 (咫尺)</button>
      <button class="tab" data-mod="wechat" onclick="switchTopModule('wechat')">💬 微信尺素 (文章 · 速记 · 定时任务)</button>
      <button class="tab" data-mod="wxconfig" onclick="switchTopModule('wxconfig')">⚙️ 尺素模型与路由配置</button>
    </div>
    <div id="wxQuickBadge" style="font-size:12px;color:#94A3B8;">微信尺素状态同步中...</div>
  </div>

  <div id="modulePhoneWrap">
  <div class="composer-card" id="devicePanel">
    <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:10px;">
      <div style="font-size:14px;font-weight:700;color:#38BDF8;">
        📱 小米手机实时状态与远程操控台
      </div>
      <div style="display:flex;gap:6px;flex-wrap:wrap;">
        <button class="btn" style="background:#0EA5E9;" onclick="switchAirpodsToMac()">💻 耳机切到 Mac</button>
        <button class="btn" style="background:#14B8A6;" onclick="switchAirpodsToPhone()">📱 耳机切到手机</button>
        <button class="btn" style="background:#7C3AED;" onclick="toggleInlineMirror()" id="btnToggleMirror">📺 开启实时同屏</button>
        <button class="btn btn-secondary" onclick="openStandaloneMirror()">🗗 独立同屏小窗 (/mirror)</button>
        <button class="btn btn-secondary" onclick="ctrlPhone({action:'refresh'})">🔄 刷新实时状态</button>
        <button class="btn" onclick="takePhoneScreenshot()">📸 立即截屏回传 Mac</button>
        <button class="btn btn-secondary" onclick="pullPhoneClip()">📥 读手机剪贴板</button>
        <button class="btn btn-secondary" onclick="findPhone()">🔔 响铃找手机</button>
        <button class="btn btn-green" onclick="triggerOta()">📲 推送APK升级</button>
      </div>
    </div>
    <div id="inlineMirrorDrawer" style="display:none;margin-bottom:12px;border:1px solid #334155;border-radius:12px;overflow:hidden;background:#090D16;">
      <iframe id="inlineMirrorFrame" src="about:blank" style="width:100%;height:720px;border:none;display:block;"></iframe>
    </div>
    <div class="status-grid" id="deviceStatsGrid">
      <div class="stat-tile"><div class="stat-label">正在读取手机状态...</div><div class="stat-value">--</div></div>
    </div>
    <div class="perm-chips" id="permChips"></div>
    <div class="ctrl-row" style="margin-top:10px;">
      <span style="font-size:12px;color:var(--muted);">🎮 快捷操作：</span>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'system_action',target:'home'})">🏠 桌面</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'system_action',target:'back'})">🔙 返回</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'system_action',target:'recents'})">🗂️ 多任务</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'system_action',target:'lock_screen'})">🔒 锁屏</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'set_volume',stream:'music',direction:'down'})">🔉 音量-</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'set_volume',stream:'music',direction:'up'})">🔊 音量+</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'set_volume',stream:'music',direction:'mute'})">🔇 静音</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'launch_app',packageOrAlias:'wechat'})">💬 开微信</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'launch_app',packageOrAlias:'lark'})">🐦 开飞书</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'launch_app',packageOrAlias:'小红书'})">📕 开小红书</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'launch_app',packageOrAlias:'camera'})">📷 开相机</button>
      <button class="btn btn-secondary" onclick="ctrlPhone({action:'launch_app',packageOrAlias:'settings'})">⚙️ 开设置</button>
      <button class="btn btn-secondary" onclick="promptLaunchAppPhone()">🚀 打开任意App</button>
      <button class="btn btn-secondary" onclick="promptSpeakPhone()">🗣️ 手机语音播报</button>
      <button class="btn btn-secondary" onclick="promptOpenUrlPhone()">🌐 手机打开网址</button>
    </div>
  </div>

  <div class="composer-card">
    <div style="font-size:13px;font-weight:600;margin-bottom:8px;color:#38BDF8;">
      🚀 从 Mac 反向发送消息 / 笔记 / 剪贴板到小米手机或手机微信
    </div>
    <div class="composer-row">
      <input class="input" id="pushTitle" placeholder="标题 (可选)" style="width:180px;" />
      <textarea class="textarea" id="pushText" placeholder="输入文字、链接或 Markdown 笔记，一键推送至手机时间流、手机微信或手机剪贴板..."></textarea>
      <div style="display:flex;flex-direction:column;gap:6px;">
        <button class="btn" onclick="pushToPhone()">📤 推送到手机时间流</button>
        <button class="btn btn-green" onclick="pushToWeChat()">💬 推送到手机微信</button>
        <button class="btn btn-secondary" onclick="syncPhoneClip()">📋 写入手机剪贴板</button>
      </div>
    </div>
  </div>

  <div class="filter-bar">
    <div class="tabs" id="tagTabs">
      <button class="tab active" data-tag="all" onclick="setTag('all')">全部</button>
      <button class="tab" data-tag="sms_otp" onclick="setTag('sms_otp')">🔑 验证码</button>
      <button class="tab" data-tag="default_inbox" onclick="setTag('default_inbox')">📥 默认Inbox</button>
      <button class="tab" data-tag="pkb_vault" onclick="setTag('pkb_vault')">📚 存知识库</button>
      <button class="tab" data-tag="voice_idea" onclick="setTag('voice_idea')">💡 我有想法</button>
      <button class="tab" data-tag="ui_bug" onclick="setTag('ui_bug')">🐛 修UI报错</button>
      <button class="tab" data-tag="unprocessed" onclick="toggleUnprocessed()" id="btnUnprocessed">⏳ 仅看未处理</button>
    </div>
    <div style="display:flex;gap:8px;align-items:center;">
      <input class="input" id="searchInput" placeholder="🔍 搜索文字、文件名..." oninput="onSearchInput()" style="width:200px;padding:7px 11px;"/>
      <button class="btn btn-secondary" onclick="clearProcessed()">🧹 清理已处理</button>
    </div>
  </div>

  <div id="itemsContainer" class="items-list"></div>
  </div><!-- /modulePhoneWrap -->

  <div id="moduleWeChatWrap" style="display:none;">
    <div class="composer-card" style="margin-bottom:14px;">
      <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;">
        <div style="font-size:14px;font-weight:700;color:#4ADE80;">💬 青简·尺素 — 微信文章收件箱、伴读精粹与定时任务</div>
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
          <button class="btn btn-secondary" onclick="fetchWeChatOverview(true)">🔄 刷新尺素列表</button>
          <button class="btn btn-secondary" onclick="switchTopModule('wxconfig')">⚙️ 模型与路由设置</button>
        </div>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;">
        <input class="input" id="wxIngestUrl" placeholder="粘贴微信公众号 / 小红书 / YouTube / 网页链接手动抓取归档..." style="flex:1;min-width:260px;" />
        <input class="input" id="wxIngestNote" placeholder="关注点批注 (可选)" style="width:220px;" />
        <button class="btn btn-green" onclick="ingestWeChatUrl()">📥 抓取并生成伴读</button>
      </div>
    </div>

    <div class="filter-bar" style="margin-bottom:12px;">
      <div class="tabs" id="wxSubTabs">
        <button class="tab active" data-wxsub="articles" onclick="setWxSubTab('articles')">📄 文章收件箱</button>
        <button class="tab" data-wxsub="turns" onclick="setWxSubTab('turns')">📝 微信速记与对话</button>
        <button class="tab" data-wxsub="schedules" onclick="setWxSubTab('schedules')">⏰ 定时提醒与早报</button>
      </div>
      <input class="input" id="wxSearchInput" placeholder="🔍 搜索文章标题、摘要或批注..." oninput="renderWeChatList()" style="width:240px;padding:7px 11px;"/>
    </div>

    <div style="display:grid;grid-template-columns:360px 1fr;gap:14px;align-items:start;">
      <div id="wxListPane" style="background:#111827;border:1px solid #1F2937;border-radius:12px;max-height:72vh;overflow-y:auto;padding:8px;display:flex;flex-direction:column;gap:6px;"></div>
      <div id="wxDetailPane" style="background:#111827;border:1px solid #1F2937;border-radius:12px;min-height:480px;max-height:72vh;overflow-y:auto;padding:18px;">
        <div style="color:#94A3B8;font-size:13px;">👈 从左侧选择一篇文章、速记或定时任务查看详情</div>
      </div>
    </div>
  </div>

  <div id="moduleWxConfigWrap" style="display:none;">
    <div style="background:#111827;border:1px solid #1F2937;border-radius:12px;overflow:hidden;">
      <iframe id="wxConfigFrame" src="about:blank" style="width:100%;height:82vh;border:none;display:block;"></iframe>
    </div>
  </div>
</div>

<div class="lightbox" id="lightbox" onclick="closeLightbox()">
  <img id="lightboxImg" src="" alt="Preview"/>
</div>
<div class="toast" id="toast"></div>

<script>
let currentTag = 'all';
let onlyUnprocessed = false;
let searchQuery = '';
let lastRenderSignature = '';

function showToast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.display = 'block';
  clearTimeout(window.__toastTimer);
  window.__toastTimer = setTimeout(() => { t.style.display = 'none'; }, 2400);
}

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function encodeUrlParam(str) {
  return encodeURIComponent(String(str || '')).replace(/'/g, '%27');
}

function setTag(tag) {
  currentTag = tag;
  document.querySelectorAll('#tagTabs .tab[data-tag]').forEach(btn => {
    if (btn.dataset.tag === 'unprocessed') return;
    btn.classList.toggle('active', btn.dataset.tag === tag);
  });
  lastRenderSignature = '';
  fetchInbox();
}

function toggleUnprocessed() {
  onlyUnprocessed = !onlyUnprocessed;
  document.getElementById('btnUnprocessed').classList.toggle('active', onlyUnprocessed);
  lastRenderSignature = '';
  fetchInbox();
}

function onSearchInput() {
  searchQuery = document.getElementById('searchInput').value.trim();
  lastRenderSignature = '';
  fetchInbox();
}

function openLightbox(src) {
  document.getElementById('lightboxImg').src = src;
  document.getElementById('lightbox').classList.add('open');
}

function closeLightbox() {
  document.getElementById('lightbox').classList.remove('open');
}

async function callAction(payload) {
  const res = await fetch('/api/inbox/action', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  return await res.json();
}

async function savePrefs() {
  const autoSyncOtpToMac = document.getElementById('chkAutoOtp').checked;
  const autoCopyTextToClipboard = document.getElementById('chkAutoCopy').checked;
  const autoPreviewImageOnMac = document.getElementById('chkAutoPreview').checked;
  const autoSyncWeChatToPhone = document.getElementById('chkAutoWxPhone').checked;
  const autoSwitchAirpods = document.getElementById('chkAutoAirpods').checked;
  await callAction({
    action: 'update_preferences',
    autoSyncOtpToMac,
    autoCopyTextToClipboard,
    autoPreviewImageOnMac,
    autoSyncWeChatToPhone,
    autoSwitchAirpods,
    pauseOtherOnAirpodsSwitch: autoSwitchAirpods
  });
  showToast('✅ 已保存 Mac 自动联动、微信伴读入流与 AirPods 自动切换设置');
}

async function switchAirpodsToMac() {
  showToast('🎧 正在将 AirPods 切换到 Mac 并暂停手机播放...');
  const res = await callAction({ action: 'airpods_switch_mac' });
  if (res.ok) {
    showToast('✅ AirPods 已切换到 Mac！');
  } else {
    showToast('⚠️ 切换未成功: ' + ((res.result && res.result.message) || res.error || '请确认耳机在盒外'));
  }
  lastRenderSignature = '';
  fetchInbox();
}

async function switchAirpodsToPhone() {
  showToast('🎧 正在释放 Mac 蓝牙并将 AirPods 切换到小米手机...');
  const res = await callAction({ action: 'airpods_switch_phone' });
  if (res.ok) {
    showToast('✅ AirPods 已切换到小米手机！');
  } else {
    showToast('⚠️ 切换提示: ' + ((res.result && res.result.phoneResult && res.result.phoneResult.error) || res.error || '已从 Mac 释放耳机'));
  }
  lastRenderSignature = '';
  fetchInbox();
}

async function copyText(text, label) {
  await callAction({ action: 'copy_mac_clipboard', text });
  showToast('✅ 已复制到 Mac 剪贴板: ' + (label || '可直接 Cmd+V'));
}

async function markItem(itemId, processed) {
  await callAction({ action: 'mark_processed', itemId, processed });
  lastRenderSignature = '';
  fetchInbox();
}

async function deleteItem(itemId) {
  await callAction({ action: 'delete_item', itemId });
  lastRenderSignature = '';
  fetchInbox();
  showToast('🗑️ 已删除该条记录');
}

async function clearProcessed() {
  const res = await callAction({ action: 'clear_processed' });
  lastRenderSignature = '';
  fetchInbox();
  showToast('🧹 已清理 ' + (res.removedCount || 0) + ' 条已处理记录');
}

async function openOnMac(itemId, absPath, reveal) {
  const res = await callAction({
    action: reveal ? 'reveal_in_finder' : 'open_preview',
    itemId,
    absPath
  });
  if (res.ok) {
    showToast(reveal ? '📂 已在 Finder 中定位' : '👁️ 已在 Mac 预览中打开');
  } else {
    showToast('⚠️ ' + (res.error || '打开失败'));
  }
}

async function pushToPhone() {
  const title = document.getElementById('pushTitle').value.trim();
  const text = document.getElementById('pushText').value.trim();
  if (!text) return showToast('⚠️ 请先输入要发送的内容');
  const res = await callAction({ action: 'push_to_phone', title, text, enableTts: true });
  if (res.ok) {
    document.getElementById('pushText').value = '';
    showToast('🚀 已推送到小米手机时间流！');
  } else {
    showToast('⚠️ 推送失败: ' + (res.error || '手机未连接'));
  }
}

async function pushToWeChat() {
  const title = document.getElementById('pushTitle').value.trim();
  const text = document.getElementById('pushText').value.trim();
  if (!text) return showToast('⚠️ 请先输入要发送到手机微信的内容');
  showToast('💬 正在推送到手机微信...');
  try {
    const res = await fetch('/api/wechat/push', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: title || undefined, text })
    });
    const data = await res.json();
    if (data.ok && data.failedCount === 0) {
      document.getElementById('pushText').value = '';
      showToast('✅ 已发送到手机微信 (' + (data.sentCount || 1) + ' 条)！');
    } else {
      showToast('⚠️ 微信发送状态: sent=' + (data.sentCount || 0) + ', pending=' + (data.pendingCount || 0) + ', failed=' + (data.failedCount || 0) + (data.error ? (' · ' + data.error) : ''));
    }
  } catch (e) {
    showToast('⚠️ 微信推送失败: ' + (e.message || e));
  }
}

async function syncPhoneClip() {
  const text = document.getElementById('pushText').value.trim();
  if (!text) return showToast('⚠️ 请先输入要写入手机剪贴板的内容');
  const res = await callAction({ action: 'set_phone_clipboard', text });
  if (res.ok) {
    showToast('📋 已写入小米手机剪贴板！');
  } else {
    showToast('⚠️ 写入失败: ' + (res.error || '手机未连接'));
  }
}

async function pullPhoneClip() {
  showToast('📥 正在读取小米手机剪贴板...');
  const res = await callAction({ action: 'get_phone_clipboard', copyToMac: true });
  if (res.ok && res.result) {
    const txt = res.result.text || '';
    if (txt) {
      document.getElementById('pushText').value = txt;
      showToast('✅ 已读取手机剪贴板并复制到 Mac (可直接 Cmd+V)');
    } else {
      showToast('ℹ️ 手机剪贴板当前为空');
    }
  } else {
    showToast('⚠️ 读取失败: ' + (res.error || '手机未连接'));
  }
}

async function ctrlPhone(params) {
  const res = await callAction({ action: 'phone_control', params });
  if (res.ok) {
    const r = res.result || {};
    if (r.ok === false) {
      showToast('⚠️ ' + (r.error || '操作未成功'));
    } else if (r.hint) {
      showToast('ℹ️ ' + r.hint);
    } else if (r.appLabel) {
      showToast('🚀 已在手机上打开「' + r.appLabel + '」');
    } else {
      showToast('✅ 指令已执行: ' + (params.action || 'refresh'));
    }
    lastRenderSignature = '';
    fetchInbox();
  } else {
    showToast('⚠️ 操作失败: ' + (res.error || '手机未连接'));
  }
}

async function takePhoneScreenshot() {
  showToast('📸 正在抓取小米手机当前屏幕...');
  const res = await callAction({ action: 'phone_control', params: { action: 'take_screenshot', saveToInbox: true } });
  if (res.ok) {
    showToast('✅ 手机截屏已回传至收件箱！');
    lastRenderSignature = '';
    fetchInbox();
  } else {
    showToast('⚠️ 截屏失败: ' + (res.error || '请确认已开启手机无障碍服务'));
  }
}

async function findPhone() {
  showToast('🔔 正在触发手机闹钟声道强响铃与语音播报...');
  const res = await callAction({
    action: 'phone_control',
    params: { action: 'find_phone', text: '正在寻找手机，我在这里！请查看青简咫尺。' }
  });
  if (res.ok) {
    showToast('🔔 已触发手机响铃与震动！');
  } else {
    showToast('⚠️ 触发失败: ' + (res.error || '手机未连接'));
  }
}

async function triggerOta() {
  showToast('📲 正在向手机推送最新 APK 下载安装页...');
  const res = await callAction({ action: 'ota_update' });
  if (res.ok) {
    showToast('📲 已在手机上唤起 APK 更新下载！');
  } else {
    showToast('⚠️ 推送失败: ' + (res.error || '手机未连接'));
  }
}

async function promptLaunchAppPhone() {
  const app = window.prompt('请输入要在手机上打开的应用名称（中文名/英文名/包名均可，如：微信、小红书、支付宝、高德地图）：', '小红书');
  if (!app || !app.trim()) return;
  await ctrlPhone({ action: 'launch_app', packageOrAlias: app.trim() });
}

async function promptSpeakPhone() {
  const text = window.prompt('请输入要让手机直接开口朗读的语音内容：', '你好，Mac 端任务已完成，请查看。');
  if (!text || !text.trim()) return;
  await ctrlPhone({ action: 'tts_speak', text: text.trim(), useAlarmStream: true });
}

async function promptOpenUrlPhone() {
  const url = window.prompt('请输入要在手机浏览器打开的网址：', 'https://');
  if (!url || !url.trim() || url.trim() === 'https://') return;
  await ctrlPhone({ action: 'open_url', url: url.trim() });
}

async function transcribeItem(itemId) {
  showToast('🎙️ 正在调用本地 SenseVoice 离线模型转写语音...');
  const res = await callAction({ action: 'transcribe_item', itemId });
  if (res.ok && res.result && res.result.transcript) {
    showToast('✅ 语音转写完成并已复制到剪贴板！');
    lastRenderSignature = '';
    fetchInbox();
  } else {
    showToast('⚠️ 未能识别出有效语音内容');
  }
}

function renderDevicePanel(st) {
  const ps = st.phoneStatus;
  const grid = document.getElementById('deviceStatsGrid');
  const permContainer = document.getElementById('permChips');
  if (!ps) {
    grid.innerHTML = '<div class="stat-tile"><div class="stat-label">设备状态</div><div class="stat-value" style="color:var(--amber);">⚪ 手机当前未连接，连接后自动展示实时遥测数据</div></div>';
    permContainer.innerHTML = '';
    return;
  }

  const devModel = (ps.deviceBrand ? (ps.deviceBrand + ' ') : '') + (ps.deviceModel || (st.trustedDevices && st.trustedDevices[0] ? st.trustedDevices[0].phoneModel : 'Xiaomi'));
  const osVer = ps.androidVersion ? ('Android ' + ps.androidVersion + (ps.romVersion ? (' · ' + ps.romVersion) : '')) : 'HyperOS';
  const appVer = ps.appVersionName ? ('v' + ps.appVersionName) : 'v1.3.x';
  const uptimeStr = ps.uptimeMinutes ? ('已运行 ' + Math.floor(ps.uptimeMinutes / 60) + 'h ' + (ps.uptimeMinutes % 60) + 'm') : '';

  const battPct = (ps.batteryPct !== undefined ? ps.batteryPct : ps.batteryPercent) ?? '--';
  const chargeText = ps.isCharging ? ('⚡ 充电中' + (ps.chargePlug && ps.chargePlug !== 'NONE' ? (' (' + ps.chargePlug + ')') : '')) : '🔋 未充电';
  const tempText = ps.temperatureC ? (ps.temperatureC + '°C') : '';
  const voltText = ps.voltageMv ? ((ps.voltageMv / 1000).toFixed(2) + 'V') : '';

  const screenState = (ps.screenInteractive === false ? '🌙 息屏' : '☀️ 亮屏') + ' · ' + (ps.screenLocked ? '🔒 已锁屏' : '🔓 已解锁');
  const fgApp = (ps.foregroundAppLabel || ps.foregroundPackage || '桌面/待机') +
    (ps.foregroundPackage && ps.foregroundAppLabel && ps.foregroundAppLabel !== ps.foregroundPackage ? (' (' + ps.foregroundPackage + ')') : '');

  const ips = (ps.lanIps && ps.lanIps.length > 0) ? ps.lanIps.join(', ') : '局域网直连';
  const wifiInfo = [
    ps.wifiSsid || '',
    ps.wifiRssiDbm ? (ps.wifiRssiDbm + ' dBm') : '',
    ps.wifiLinkSpeedMbps ? (ps.wifiLinkSpeedMbps + ' Mbps') : ''
  ].filter(Boolean).join(' · ');

  const ringerMap = { NORMAL: '🔔 正常响铃', VIBRATE: '📳 仅震动', SILENT: '🔇 静音' };
  const audioInfo = (ringerMap[ps.ringerMode] || '🔔 正常') +
    (ps.volumeMusicPct !== undefined ? (' · 媒体 ' + ps.volumeMusicPct + '% / 铃声 ' + (ps.volumeRingPct ?? '--') + '% / 闹钟 ' + (ps.volumeAlarmPct ?? '--') + '%') : '');

  const storageInfo = ps.storageTotalGb
    ? ('存储可用 ' + ps.storageAvailableGb + ' / ' + ps.storageTotalGb + ' GB (' + ps.storageUsedPct + '%)')
    : '存储正常';
  const ramInfo = ps.ramTotalMb
    ? ('内存可用 ' + (ps.ramAvailableMb / 1024).toFixed(1) + ' / ' + (ps.ramTotalMb / 1024).toFixed(1) + ' GB (' + ps.ramUsedPct + '%)')
    : '';

  const ap = st.airpodsStatus || {};
  const apOwnerMap = { MAC: '💻 已连 Mac', PHONE: '📱 已连手机', NONE: '⚪ 待机/在盒中' };
  const apOwnerText = apOwnerMap[ap.activeOwner] || '⚪ 待机';
  const apNameText = ap.activeDeviceName || 'AirPods';
  const apSubText = [
    ap.autoSwitchEnabled !== false ? '自动无感切换已开' : '自动切换已关',
    ap.mac && ap.mac.audioRunning ? 'Mac发声中' : '',
    (ap.phone && ap.phone.mediaPlaying) || ps.mediaPlaying ? '手机播放中' : ''
  ].filter(Boolean).join(' · ');

  grid.innerHTML = [
    { label: '📱 设备型号与系统', value: escapeHtml(devModel + ' · ' + appVer) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml([osVer, uptimeStr].filter(Boolean).join(' · ')) + '</span>' },
    { label: '🔋 电池与温度状态', value: escapeHtml(battPct + '% · ' + chargeText) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml([tempText, voltText, ps.powerSaveMode ? '省电模式开启' : '全性能模式'].filter(Boolean).join(' · ')) + '</span>' },
    { label: '🖥️ 屏幕与当前前台应用', value: escapeHtml(screenState) + '<br/><span style="font-size:11px;color:#38BDF8;">前台: ' + escapeHtml(fgApp) + '</span>' },
    { label: '🌐 网络与局域网 IP', value: escapeHtml('IP: ' + ips) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml([wifiInfo, ps.bluetoothEnabled !== undefined ? (ps.bluetoothEnabled ? '蓝牙已开' : '蓝牙已关') : '', 'RTT ' + (st.lastRttMs || 0) + 'ms'].filter(Boolean).join(' · ')) + '</span>' },
    { label: '🎧 AirPods 双端连接状态', value: escapeHtml(apNameText + ' · ' + apOwnerText) + '<br/><span style="font-size:11px;color:#38BDF8;">' + escapeHtml(apSubText) + '</span>' },
    { label: '🔊 音量与存储状态', value: escapeHtml(audioInfo) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml(storageInfo) + '</span>' }
  ].map(t => '<div class="stat-tile"><div class="stat-label">' + t.label + '</div><div class="stat-value">' + t.value + '</div></div>').join('');

  const p = ps.permissions || {};
  const otpReady = Boolean(p.notificationListenerEnabled || (p.smsReadGranted && p.xiaomiNotificationSmsAllowed !== false));
  const perms = [
    ['后台弹出', p.xiaomiBackgroundPopupAllowed],
    ['锁屏显示', p.xiaomiLockScreenDisplayAllowed],
    ['电池无限制', p.batteryOptimizationIgnored],
    ['悬浮窗', p.overlayAllowed],
    ['无障碍GUI', p.accessibilityEnabled],
    ['验证码同步', otpReady],
    ['日历读写', p.calendarGranted],
    ['相册读取', p.mediaReadGranted],
    ['麦克风录音', p.recordAudioGranted !== false]
  ];
  permContainer.innerHTML = perms.map(([name, ok]) =>
    '<span class="perm-chip" style="color:' + (ok ? 'var(--green)' : 'var(--amber)') + ';">' + (ok ? '✅ ' : '⚠️ ') + name + '</span>'
  ).join('');
}

async function fetchInbox() {
  try {
    const params = new URLSearchParams({
      tag: currentTag,
      unprocessed: onlyUnprocessed ? '1' : '0',
      q: searchQuery,
      limit: '100'
    });
    const res = await fetch('/api/inbox?' + params.toString());
    const data = await res.json();
    if (!data.ok) return;

    // Update status bar & device panel
    const st = data.status || {};
    const tierMap = {
      LAN_WS: '🟢 局域网高速直连 (Tier 1)',
      BT_RFCOMM: '🔵 近场蓝牙连接 (Tier 2)',
      WAN_WEBRTC: '🟣 跨网远程连接 (Tier 3)',
      DISCONNECTED: '⚪ 手机未连接'
    };
    const presenceText = st.presenceState === 'AT_DESK' ? '👨‍💻 在电脑旁 (AT_DESK)' : '🚶 离开工位 (AWAY)';
    const pctVal = st.phoneStatus ? (st.phoneStatus.batteryPct ?? st.phoneStatus.batteryPercent) : undefined;
    const batt = pctVal !== undefined ? ('🔋 ' + pctVal + '%' + (st.phoneStatus.isCharging ? ' ⚡' : '')) : '';
    const fgBadge = (st.phoneStatus && (st.phoneStatus.foregroundAppLabel || st.phoneStatus.foregroundPackage))
      ? ('📱 ' + escapeHtml(st.phoneStatus.foregroundAppLabel || st.phoneStatus.foregroundPackage))
      : '';
    document.getElementById('statusLine').innerHTML =
      '<span class="badge">' + (tierMap[st.activeTransportTier] || st.activeTransportTier) + '</span>' +
      '<span class="badge">' + presenceText + '</span>' +
      (st.lastRttMs ? '<span class="badge">⚡ RTT ' + st.lastRttMs + 'ms</span>' : '') +
      (batt ? '<span class="badge">' + batt + '</span>' : '') +
      (fgBadge ? '<span class="badge">' + fgBadge + '</span>' : '');

    const wxSt = st.wechatStatus || {};
    const wxBadgeEl = document.getElementById('wxQuickBadge');
    if (wxBadgeEl) {
      if (wxSt.running && wxSt.credentialsConfigured) {
        wxBadgeEl.innerHTML = '🟢 微信尺素：ClawBot 长轮询在线' + (wxSt.activeAgentTasksCount > 0 ? (' · 🤖 运行中任务 ' + wxSt.activeAgentTasksCount) : '');
        wxBadgeEl.style.color = '#4ADE80';
      } else if (wxSt.running) {
        wxBadgeEl.innerHTML = '🟠 微信尺素：服务已启动 (待扫码绑定)';
        wxBadgeEl.style.color = '#FBBF24';
      } else {
        wxBadgeEl.innerHTML = '⚪ 微信尺素：受管子进程待命';
        wxBadgeEl.style.color = '#94A3B8';
      }
    }

    renderDevicePanel(st);

    if (data.preferences && !window.__prefsLoaded) {
      document.getElementById('chkAutoOtp').checked = data.preferences.autoSyncOtpToMac !== false;
      document.getElementById('chkAutoCopy').checked = data.preferences.autoCopyTextToClipboard !== false;
      document.getElementById('chkAutoPreview').checked = data.preferences.autoPreviewImageOnMac !== false;
      document.getElementById('chkAutoWxPhone').checked = data.preferences.autoSyncWeChatToPhone !== false;
      document.getElementById('chkAutoAirpods').checked = data.preferences.autoSwitchAirpods !== false;
      window.__prefsLoaded = true;
    }

    const items = data.items || [];
    const sig = JSON.stringify(items.map(i => i.itemId + ':' + i.processed + ':' + (i.voiceTranscript || '') + ':' + (i.otpCode || ''))) + '|' + items.length;
    if (sig === lastRenderSignature) return;
    lastRenderSignature = sig;

    const container = document.getElementById('itemsContainer');
    window.__inboxItemsById = new Map(items.map(i => [String(i.itemId || ''), i]));
    if (items.length === 0) {
      container.innerHTML = '<div class="empty-state">📭 当前筛选条件下暂无手机投递记录<br/><span style="font-size:12px;">在小米手机上发送消息、语音、截屏、分享文件或收到验证码后，这里会自动实时显示</span></div>';
      return;
    }

    container.innerHTML = items.map(item => {
      const safeItemId = escapeHtml(item.itemId);
      const timeStr = new Date(item.createdAtMs).toLocaleString('zh-CN', { hour12: false });
      const contentToCopy = item.textContent || item.voiceTranscript || item.title || '';
      const otpCode = item.otpCode || '';
      const otpSender = item.otpSender || '';

      const filesHtml = (item.files || []).map((f, fileIdx) => {
        const isImg = (f.mimeType || '').startsWith('image/') || /\\.(png|jpe?g|webp|gif)$/i.test(f.fileName);
        const isAudio = (f.mimeType || '').startsWith('audio/') || /\\.(m4a|mp3|wav|ogg|aac)$/i.test(f.fileName);
        const fileUrl = '/api/inbox/file?itemId=' + encodeUrlParam(item.itemId) + '&file=' + encodeUrlParam(f.fileName);
        const safeFileUrl = escapeHtml(fileUrl);
        const kb = Math.max(1, Math.round((f.sizeBytes || 0) / 1024));
        return '<div class="attachment-box">' +
          (isImg ? ('<img class="attachment-img" src="' + safeFileUrl + '" data-item-action="lightbox" data-file-url="' + safeFileUrl + '" title="点击放大查看原图"/>') : '') +
          (isAudio ? ('<audio controls src="' + safeFileUrl + '" style="width:100%;margin-bottom:8px;"></audio>') : '') +
          '<div class="attachment-meta">📎 ' + escapeHtml(f.fileName) + ' (' + kb + ' KB)<br/><code>' + escapeHtml(f.absPath) + '</code></div>' +
          '<div class="action-row">' +
            (isAudio ? ('<button class="btn" data-item-action="transcribe" data-item-id="' + safeItemId + '">🎙️ 离线语音转写</button>') : '') +
            '<button class="btn" data-item-action="open-mac" data-item-id="' + safeItemId + '" data-file-idx="' + fileIdx + '" data-reveal="0">👁️ Mac 打开</button>' +
            '<button class="btn btn-secondary" data-item-action="open-mac" data-item-id="' + safeItemId + '" data-file-idx="' + fileIdx + '" data-reveal="1">📂 Finder 定位</button>' +
            '<button class="btn btn-secondary" data-item-action="copy-file-path" data-item-id="' + safeItemId + '" data-file-idx="' + fileIdx + '">📋 复制路径</button>' +
          '</div>' +
        '</div>';
      }).join('');

      const otpBannerHtml = otpCode
        ? ('<div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;background:rgba(22,163,74,0.14);border:1px solid rgba(74,222,128,0.45);border-radius:10px;padding:10px 14px;margin-bottom:10px;">' +
            '<div style="display:flex;align-items:center;gap:10px;">' +
              '<span style="font-size:18px;font-weight:800;color:#4ADE80;letter-spacing:1.5px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;">🔑 ' + escapeHtml(otpCode) + '</span>' +
              (otpSender ? ('<span class="badge" style="color:#FBBF24;border-color:rgba(251,191,36,0.4);">【' + escapeHtml(otpSender) + '】</span>') : '') +
            '</div>' +
            '<button class="btn btn-green" data-item-action="copy-otp" data-item-id="' + safeItemId + '">🔑 复制纯验证码 (' + escapeHtml(otpCode) + ')</button>' +
          '</div>')
        : '';

      return '<div class="item-card ' + (item.processed ? '' : 'unprocessed') + '">' +
        '<div class="item-header">' +
          '<div class="item-meta">' +
            '<span class="tag-pill">#' + escapeHtml(item.intentTag) + '</span>' +
            '<span>🕒 ' + escapeHtml(timeStr) + '</span>' +
            '<span>' + (item.processed ? '✅ 已处理' : '🔵 未处理') + '</span>' +
          '</div>' +
          '<div class="action-row" style="margin:0;">' +
            (otpCode ? '<button class="btn btn-green" data-item-action="copy-otp" data-item-id="' + safeItemId + '">🔑 复制验证码</button>' : '') +
            (contentToCopy ? '<button class="btn btn-secondary" data-item-action="copy-content" data-item-id="' + safeItemId + '">📋 复制全文</button>' : '') +
            '<button class="btn btn-secondary" data-item-action="copy-prompt" data-item-id="' + safeItemId + '">🤖 复制 AI 提示词</button>' +
            '<button class="btn btn-secondary" data-item-action="mark-item" data-item-id="' + safeItemId + '">' + (item.processed ? '设为未读' : '✓ 标为已处理') + '</button>' +
            '<button class="btn btn-danger" data-item-action="delete-item" data-item-id="' + safeItemId + '">🗑️</button>' +
          '</div>' +
        '</div>' +
        (item.title ? '<div class="item-title">' + escapeHtml(item.title) + '</div>' : '') +
        otpBannerHtml +
        (item.textContent ? '<div class="item-text">' + escapeHtml(item.textContent) + '</div>' : '') +
        (item.voiceTranscript ? '<div class="item-voice">🎙️ 语音附言: ' + escapeHtml(item.voiceTranscript) + '</div>' : '') +
        (filesHtml ? '<div class="attachments-grid">' + filesHtml + '</div>' : '') +
      '</div>';
    }).join('');
  } catch (e) {
    // ignore transient poll error
  }
}

document.getElementById('itemsContainer').addEventListener('click', (e) => {
  const el = e.target && e.target.closest ? e.target.closest('[data-item-action]') : null;
  if (!el) return;
  const act = el.getAttribute('data-item-action');
  if (act === 'lightbox') {
    const fileUrl = el.getAttribute('data-file-url') || el.getAttribute('src');
    if (fileUrl) openLightbox(fileUrl);
    return;
  }
  const itemId = el.getAttribute('data-item-id') || '';
  const item = window.__inboxItemsById ? window.__inboxItemsById.get(itemId) : null;
  if (!item) return;
  const fileIdx = Number(el.getAttribute('data-file-idx') || '0');
  const file = (item.files && item.files[fileIdx]) ? item.files[fileIdx] : null;
  const contentToCopy = item.textContent || item.voiceTranscript || item.title || '';

  switch (act) {
    case 'transcribe':
      transcribeItem(item.itemId);
      break;
    case 'open-mac':
      if (file) openOnMac(item.itemId, file.absPath, el.getAttribute('data-reveal') === '1');
      break;
    case 'copy-file-path':
      if (file) copyText(file.absPath, '文件路径');
      break;
    case 'copy-otp':
      if (item.otpCode) copyText(item.otpCode, '验证码 ' + item.otpCode);
      break;
    case 'copy-content':
      if (contentToCopy) copyText(contentToCopy, '正文内容');
      break;
    case 'copy-prompt': {
      const firstFilePath = (item.files && item.files[0]) ? item.files[0].absPath : '';
      const aiPrompt = firstFilePath
        ? ('请查看我从手机投递的文件 "' + firstFilePath + '"' + (contentToCopy ? ('，附言：' + contentToCopy) : ''))
        : ('请处理我从手机发来的内容：' + contentToCopy);
      copyText(aiPrompt, 'AI 提示词');
      break;
    }
    case 'mark-item':
      markItem(item.itemId, !item.processed);
      break;
    case 'delete-item':
      deleteItem(item.itemId);
      break;
  }
});

fetchInbox();
setInterval(() => {
  if (!document.hidden) fetchInbox();
}, 2000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) fetchInbox();
});

function openStandaloneMirror() {
  window.open('/mirror', 'oms_live_mirror', 'width=440,height=880,menubar=no,toolbar=no,location=no,status=no');
}

function toggleInlineMirror() {
  const drawer = document.getElementById('inlineMirrorDrawer');
  const frame = document.getElementById('inlineMirrorFrame');
  const btn = document.getElementById('btnToggleMirror');
  if (drawer.style.display === 'none') {
    drawer.style.display = 'block';
    frame.src = '/mirror?embed=1';
    btn.textContent = '⏹️ 收起实时同屏';
    btn.style.background = '#DC2626';
  } else {
    drawer.style.display = 'none';
    frame.src = 'about:blank';
    btn.textContent = '📺 开启实时同屏';
    btn.style.background = '#7C3AED';
    callAction({ action: 'mirror_stop' }).catch(() => {});
  }
}

let activeTopModule = 'phone';
let activeWxSubTab = 'articles';
let wxOverviewData = { articles: [], turns: [], schedules: [], agentTasks: [], status: {} };
let selectedWxArticleId = null;
let selectedWxTurnId = null;
let selectedWxDocMode = 'note';

function switchTopModule(mod) {
  activeTopModule = mod;
  document.querySelectorAll('#topModuleTabs .tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.mod === mod);
  });
  document.getElementById('modulePhoneWrap').style.display = mod === 'phone' ? 'block' : 'none';
  document.getElementById('moduleWeChatWrap').style.display = mod === 'wechat' ? 'block' : 'none';
  document.getElementById('moduleWxConfigWrap').style.display = mod === 'wxconfig' ? 'block' : 'none';
  if (mod === 'wechat') {
    fetchWeChatOverview(true);
  } else if (mod === 'wxconfig') {
    const f = document.getElementById('wxConfigFrame');
    if (f.src === 'about:blank' || !f.src.includes('/wechat/config')) {
      f.src = '/wechat/config';
    }
  }
}

function setWxSubTab(sub) {
  activeWxSubTab = sub;
  document.querySelectorAll('#wxSubTabs .tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.wxsub === sub);
  });
  renderWeChatList();
}

async function fetchWeChatOverview(autoSelectFirst) {
  try {
    const res = await fetch('/api/wechat/overview?limit=80');
    const data = await res.json();
    if (!data.ok) return;
    wxOverviewData = data;
    renderWeChatList();
    if (autoSelectFirst && activeWxSubTab === 'articles' && !selectedWxArticleId && (data.articles || []).length > 0) {
      selectWxArticle(data.articles[0].id);
    }
  } catch (e) {
    // ignore transient load error
  }
}

function renderWeChatList() {
  const pane = document.getElementById('wxListPane');
  const q = (document.getElementById('wxSearchInput').value || '').trim().toLowerCase();
  if (!pane) return;

  if (activeWxSubTab === 'articles') {
    const list = (wxOverviewData.articles || []).filter(a => {
      if (!q) return true;
      const hay = ((a.title || '') + ' ' + (a.short_summary || '') + ' ' + (a.user_notes || '') + ' ' + (a.author || '')).toLowerCase();
      return hay.includes(q);
    });
    if (list.length === 0) {
      pane.innerHTML = '<div style="padding:18px;color:#94A3B8;font-size:12px;text-align:center;">暂无匹配的微信文章记录</div>';
      return;
    }
    pane.innerHTML = list.map(a => {
      const active = Number(selectedWxArticleId) === Number(a.id);
      const triageBadge = a.process_level === 'deep' ? '💎 精读' : a.triage === 'deep' ? '🔥 建议深读' : a.triage === 'reference' ? '📎 备查' : '📌 待处理';
      const timeStr = (a.created_at || '').slice(0, 16).replace('T', ' ');
      return '<div onclick="selectWxArticle(' + Number(a.id) + ')" style="cursor:pointer;padding:10px 12px;border-radius:10px;border:1px solid ' + (active ? '#4ADE80' : '#1F2937') + ';background:' + (active ? 'rgba(74,222,128,0.10)' : '#0B1120') + ';">' +
        '<div style="display:flex;justify-content:space-between;gap:6px;font-size:11px;color:#94A3B8;margin-bottom:4px;">' +
          '<span>#' + Number(a.id) + ' · ' + escapeHtml(triageBadge) + '</span>' +
          '<span>' + escapeHtml(timeStr) + '</span>' +
        '</div>' +
        '<div style="font-size:13px;font-weight:600;color:#F8FAFC;line-height:1.35;">' + escapeHtml(a.title || a.url || '未命名文章') + '</div>' +
        (a.user_notes ? '<div style="margin-top:4px;font-size:11px;color:#FBBF24;">💬 批注: ' + escapeHtml(a.user_notes.slice(0, 60)) + '</div>' : '') +
      '</div>';
    }).join('');
    return;
  }

  if (activeWxSubTab === 'turns') {
    const turns = (wxOverviewData.turns || []).filter(t => !q || ((t.user_text || '') + ' ' + (t.assistant_reply || '')).toLowerCase().includes(q));
    if (turns.length === 0) {
      pane.innerHTML = '<div style="padding:18px;color:#94A3B8;font-size:12px;text-align:center;">暂无微信对话与速记</div>';
      return;
    }
    pane.innerHTML = turns.map(t => {
      const active = Number(selectedWxTurnId) === Number(t.id);
      const timeStr = (t.created_at || '').slice(0, 16).replace('T', ' ');
      return '<div onclick="selectWxTurn(' + Number(t.id) + ')" style="cursor:pointer;padding:10px 12px;border-radius:10px;border:1px solid ' + (active ? '#38BDF8' : '#1F2937') + ';background:' + (active ? 'rgba(56,189,248,0.10)' : '#0B1120') + ';">' +
        '<div style="display:flex;justify-content:space-between;font-size:11px;color:#94A3B8;margin-bottom:4px;">' +
          '<span>#' + Number(t.id) + ' · ' + escapeHtml(t.intent || 'chat') + '</span>' +
          '<span>' + escapeHtml(timeStr) + '</span>' +
        '</div>' +
        '<div style="font-size:12.5px;color:#F8FAFC;line-height:1.35;">' + escapeHtml((t.user_text || '').slice(0, 80)) + '</div>' +
      '</div>';
    }).join('');
    return;
  }

  const tasks = wxOverviewData.schedules || [];
  if (tasks.length === 0) {
    pane.innerHTML = '<div style="padding:18px;color:#94A3B8;font-size:12px;text-align:center;">暂无定时提醒与早报任务</div>';
    return;
  }
  pane.innerHTML = tasks.map(s => {
    const nextStr = s.next_run_at ? new Date(s.next_run_at).toLocaleString('zh-CN', { hour12: false }) : '已结束';
    return '<div onclick="renderWxScheduleDetail(' + Number(s.id) + ')" style="cursor:pointer;padding:10px 12px;border-radius:10px;border:1px solid #1F2937;background:#0B1120;">' +
      '<div style="display:flex;justify-content:space-between;font-size:11px;color:#94A3B8;margin-bottom:4px;">' +
        '<span>#' + Number(s.id) + ' · ' + escapeHtml(s.schedule_type === 'cron' ? '🔄 周期' : '⏰ 单次') + ' (' + escapeHtml(s.status) + ')</span>' +
        '<span>下次: ' + escapeHtml(nextStr) + '</span>' +
      '</div>' +
      '<div style="font-size:13px;font-weight:600;color:#F8FAFC;">' + escapeHtml(s.title) + '</div>' +
    '</div>';
  }).join('');
}

async function selectWxArticle(id) {
  selectedWxArticleId = id;
  renderWeChatList();
  const detail = document.getElementById('wxDetailPane');
  detail.innerHTML = '<div style="color:#94A3B8;font-size:13px;">正在读取文章伴读详情...</div>';
  try {
    const res = await fetch('/api/wechat/article?id=' + encodeURIComponent(id));
    const data = await res.json();
    if (!data.ok || !data.article) {
      detail.innerHTML = '<div style="color:#F87171;">读取失败: ' + escapeHtml(data.error || '未知错误') + '</div>';
      return;
    }
    window.__currentWxArticleDetail = data;
    renderCurrentWxArticleDetail();
  } catch (e) {
    detail.innerHTML = '<div style="color:#F87171;">读取异常: ' + escapeHtml(e.message || e) + '</div>';
  }
}

function setWxDocMode(mode) {
  selectedWxDocMode = mode;
  renderCurrentWxArticleDetail();
}

function renderCurrentWxArticleDetail() {
  const data = window.__currentWxArticleDetail;
  if (!data || !data.article) return;
  const a = data.article;
  const detail = document.getElementById('wxDetailPane');
  const bodyText = selectedWxDocMode === 'source'
    ? (data.sourceMarkdown || '（本地原文文件不存在或未抓取）')
    : selectedWxDocMode === 'summary'
      ? (a.short_summary || a.digest || '（暂无初筛摘要）')
      : (data.noteMarkdown || a.short_summary || '（暂无伴读笔记）');

  detail.innerHTML =
    '<div style="display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap;margin-bottom:12px;">' +
      '<div>' +
        '<div style="font-size:17px;font-weight:700;color:#F8FAFC;margin-bottom:6px;">#' + Number(a.id) + ' ' + escapeHtml(a.title || '未命名文章') + '</div>' +
        '<div style="font-size:12px;color:#94A3B8;display:flex;gap:10px;flex-wrap:wrap;">' +
          (a.author ? ('<span>✍️ ' + escapeHtml(a.author) + '</span>') : '') +
          '<span>📊 状态: ' + escapeHtml(a.status) + ' (' + escapeHtml(a.process_level) + ')</span>' +
          (a.url ? ('<a href="' + escapeHtml(a.url) + '" target="_blank" rel="noreferrer" style="color:#38BDF8;">🔗 查看源网页</a>') : '') +
        '</div>' +
      '</div>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap;">' +
        '<button class="btn btn-green" onclick="syncWxArticleToPhone(' + Number(a.id) + ')">📱 推到手机「微信尺素」通勤朗读</button>' +
        '<button class="btn btn-secondary" onclick="copyCurrentWxDoc()">📋 复制当前内容</button>' +
      '</div>' +
    '</div>' +
    (a.user_notes ? ('<div style="background:rgba(251,191,36,0.12);border:1px solid rgba(251,191,36,0.35);border-radius:8px;padding:8px 12px;margin-bottom:12px;font-size:12.5px;color:#FDE68A;">💬 你的批注：' + escapeHtml(a.user_notes) + '</div>') : '') +
    '<div style="display:flex;gap:6px;margin-bottom:12px;">' +
      '<button class="tab ' + (selectedWxDocMode === 'note' ? 'active' : '') + '" onclick="setWxDocMode(\'note\')">📒 伴读笔记 (_note.md)</button>' +
      '<button class="tab ' + (selectedWxDocMode === 'summary' ? 'active' : '') + '" onclick="setWxDocMode(\'summary\')">💡 微信初筛摘要</button>' +
      '<button class="tab ' + (selectedWxDocMode === 'source' ? 'active' : '') + '" onclick="setWxDocMode(\'source\')">📄 归档原文 (_source.md)</button>' +
    '</div>' +
    '<pre style="white-space:pre-wrap;word-break:break-word;background:#090D16;border:1px solid #1E293B;border-radius:10px;padding:14px;font-size:13px;line-height:1.65;color:#E2E8F0;margin:0;font-family:-apple-system,BlinkMacSystemFont,\'PingFang SC\',sans-serif;">' + escapeHtml(bodyText) + '</pre>';
}

function copyCurrentWxDoc() {
  const data = window.__currentWxArticleDetail;
  if (!data || !data.article) return;
  const txt = selectedWxDocMode === 'source'
    ? (data.sourceMarkdown || '')
    : selectedWxDocMode === 'summary'
      ? (data.article.short_summary || '')
      : (data.noteMarkdown || data.article.short_summary || '');
  if (txt) copyText(txt, '微信尺素内容');
}

async function syncWxArticleToPhone(articleId) {
  showToast('📱 正在同步伴读笔记至小米手机「微信尺素」时间流...');
  try {
    const res = await fetch('/api/wechat/to-phone', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ articleId })
    });
    const data = await res.json();
    if (data.ok) {
      showToast(data.queuedOffline ? '📥 手机当前离线，已加入待同步队列（连上后自动送达）' : '✅ 已送达小米手机「微信尺素」Tab，可直接通勤朗读！');
    } else {
      showToast('⚠️ 同步失败: ' + (data.error || '未知错误'));
    }
  } catch (e) {
    showToast('⚠️ 同步异常: ' + (e.message || e));
  }
}

async function selectWxTurn(turnId) {
  selectedWxTurnId = turnId;
  renderWeChatList();
  const detail = document.getElementById('wxDetailPane');
  try {
    const res = await fetch('/api/wechat/article?turnId=' + encodeURIComponent(turnId));
    const data = await res.json();
    if (!data.ok || !data.turn) return;
    const t = data.turn;
    detail.innerHTML =
      '<div style="font-size:15px;font-weight:700;margin-bottom:8px;">📝 微信对话 / 速记 #' + Number(t.id) + ' (' + escapeHtml(t.intent || 'chat') + ')</div>' +
      '<div style="font-size:12px;color:#94A3B8;margin-bottom:12px;">🕒 ' + escapeHtml(t.created_at || '') + (t.model_used ? (' · 🤖 ' + escapeHtml(t.model_used)) : '') + '</div>' +
      '<div style="background:#090D16;border:1px solid #1E293B;border-radius:10px;padding:12px;margin-bottom:12px;white-space:pre-wrap;font-size:13px;">👤 发送内容：\n' + escapeHtml(t.user_text || '') + '</div>' +
      (t.assistant_reply ? ('<div style="background:#0B1526;border:1px solid #1E3A5F;border-radius:10px;padding:12px;margin-bottom:12px;white-space:pre-wrap;font-size:13px;">🤖 尺素回复：\n' + escapeHtml(t.assistant_reply) + '</div>') : '') +
      (data.depositedMarkdown ? ('<div style="font-size:12px;color:#4ADE80;margin-bottom:6px;">📂 归档文件内容：</div><pre style="white-space:pre-wrap;background:#090D16;border:1px solid #1E293B;border-radius:10px;padding:12px;font-size:12.5px;line-height:1.6;">' + escapeHtml(data.depositedMarkdown) + '</pre>') : '');
  } catch {}
}

function renderWxScheduleDetail(id) {
  const s = (wxOverviewData.schedules || []).find(x => Number(x.id) === Number(id));
  if (!s) return;
  const detail = document.getElementById('wxDetailPane');
  detail.innerHTML =
    '<div style="font-size:16px;font-weight:700;margin-bottom:8px;">⏰ #' + Number(s.id) + ' ' + escapeHtml(s.title) + '</div>' +
    '<div style="font-size:12.5px;color:#94A3B8;margin-bottom:12px;">类型: ' + escapeHtml(s.task_kind) + ' · 规则: ' + escapeHtml(s.cron_expr || '单次触发') + ' · 状态: ' + escapeHtml(s.status) + ' · 已执行 ' + Number(s.run_count || 0) + ' 次</div>' +
    (s.last_result_summary ? ('<pre style="white-space:pre-wrap;background:#090D16;border:1px solid #1E293B;border-radius:10px;padding:14px;font-size:13px;line-height:1.6;">' + escapeHtml(s.last_result_summary) + '</pre>') : '<div style="color:#94A3B8;font-size:13px;">尚未产生执行摘要记录</div>');
}

async function ingestWeChatUrl() {
  const url = document.getElementById('wxIngestUrl').value.trim();
  const note = document.getElementById('wxIngestNote').value.trim();
  if (!url) return showToast('⚠️ 请先粘贴需要归档的链接');
  showToast('📥 正在抓取并生成伴读笔记，请稍候...');
  try {
    const res = await fetch('/api/wechat/ingest', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, note: note || undefined })
    });
    const data = await res.json();
    if (data.ok && data.article) {
      document.getElementById('wxIngestUrl').value = '';
      document.getElementById('wxIngestNote').value = '';
      showToast('✅ 抓取归档完成：#' + data.article.id);
      await fetchWeChatOverview(false);
      selectWxArticle(data.article.id);
    } else {
      showToast('⚠️ 抓取失败: ' + (data.error || '请检查链接'));
    }
  } catch (e) {
    showToast('⚠️ 请求异常: ' + (e.message || e));
  }
}

(function initModuleFromQuery() {
  try {
    const q = new URLSearchParams(location.search);
    const m = q.get('module');
    if (m === 'wechat' || m === 'wx') switchTopModule('wechat');
    else if (m === 'wxconfig' || m === 'config') switchTopModule('wxconfig');
  } catch {}
})();
</script>
</body>
</html>`;
}

function renderMirrorStandaloneHtml(): string {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>OneMoreStep · 手机实时同屏与键鼠直控台</title>
<style>
  :root {
    --bg: #070B14;
    --panel: #111827;
    --border: #1F2937;
    --text: #F8FAFC;
    --muted: #94A3B8;
    --accent: #38BDF8;
    --purple: #8B5CF6;
    --green: #4ADE80;
    --amber: #FBBF24;
    --red: #F87171;
  }
  * { box-sizing: border-box; user-select: none; }
  body {
    margin: 0;
    padding: 0;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", sans-serif;
    height: 100vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 8px 12px;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    flex-wrap: wrap;
  }
  .topbar-left {
    min-width: 0;
    max-width: 100%;
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .pill {
    min-width: 0;
    max-width: 100%;
    overflow-wrap: anywhere;
    font-size: 11px;
    font-weight: 600;
    padding: 3px 8px;
    border-radius: 999px;
    background: #0F172A;
    border: 1px solid #334155;
    color: #E2E8F0;
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .select, .btn-sm, .text-in {
    background: #0F172A;
    color: #F8FAFC;
    border: 1px solid #334155;
    border-radius: 8px;
    padding: 5px 9px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    outline: none;
  }
  .btn-sm:hover { border-color: var(--accent); }
  .btn-primary { background: #7C3AED; border-color: #8B5CF6; }
  .btn-danger { background: #991B1B; border-color: #DC2626; }
  .workspace {
    flex: 1;
    min-height: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding: 10px;
    overflow: hidden;
    position: relative;
  }
  .phone-stage {
    flex: 1;
    min-width: 0;
    height: 100%;
    container-type: size;
    display: grid;
    place-items: center;
  }
  .phone-shell {
    --phone-ratio: 0.45;
    position: relative;
    box-sizing: content-box;
    width: min(calc(100cqw - 6px), calc((100cqh - 6px) * var(--phone-ratio)));
    height: min(calc(100cqh - 6px), calc((100cqw - 6px) / var(--phone-ratio)));
    aspect-ratio: var(--phone-ratio);
    background: #000;
    border-radius: 26px;
    border: 3px solid #334155;
    box-shadow: 0 20px 50px rgba(0,0,0,0.75);
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  canvas#mirrorCanvas {
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: block;
    cursor: crosshair;
    touch-action: none;
  }
  canvas#fxCanvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }
  .overlay-banner {
    position: absolute;
    inset: 0;
    background: rgba(7, 11, 20, 0.86);
    backdrop-filter: blur(6px);
    display: none;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 24px;
    text-align: center;
    gap: 12px;
  }
  .overlay-banner.show { display: flex; }
  button:disabled { opacity: .45; cursor: default; }
  .side-dock {
    flex-shrink: 0;
    overflow-y: auto;
    max-height: 100%;
    display: flex;
    flex-direction: column;
    gap: 7px;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 8px;
  }
  .dock-btn {
    background: #0F172A;
    border: 1px solid #334155;
    color: #E2E8F0;
    border-radius: 9px;
    padding: 8px 10px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    text-align: left;
    display: flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
  }
  .dock-btn:hover { background: #1E293B; border-color: var(--accent); }
  .bottombar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    background: var(--panel);
    border-top: 1px solid var(--border);
  }
  .bottombar { flex-wrap: wrap; }
  .topbar, .bottombar { flex-shrink: 0; }
  #resultText { padding: 4px 12px; min-height: 24px; max-height: 60px; flex-shrink: 0; overflow-y: auto; overflow-wrap: anywhere; color: var(--muted); font-size: 12px; }
  .text-in {
    min-width: 140px;
    flex: 1;
    cursor: text;
    user-select: text;
    padding: 7px 11px;
  }
  .nav-group {
    display: flex;
    gap: 6px;
  }
  .toast {
    position: fixed;
    bottom: 56px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(2, 132, 199, 0.95);
    color: #fff;
    padding: 6px 14px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    display: none;
    z-index: 999;
  }
</style>
</head>
<body>
  <div class="topbar">
    <div class="topbar-left">
      <span class="pill" id="liveBadge">🟢 正在连接手机画面...</span>
      <span class="pill" id="fpsBadge">⚡ -- FPS</span>
      <span class="pill" id="resBadge">📐 -- × --</span>
      <span class="pill" id="appBadge">📱 --</span>
    </div>
    <div style="display:flex;gap:6px;align-items:center;">
      <select class="select" id="presetSelect" onchange="onPresetChange()">
        <option value="540,68,8">⚡ 540p 极速流畅</option>
        <option value="640,72,8" selected>✨ 640p 高清均衡</option>
        <option value="860,78,8">💎 860p 超清细节</option>
      </select>
      <button class="btn-sm btn-primary" id="btnStartStop" onclick="toggleStartStop()">⏸️ 暂停</button>
    </div>
  </div>

  <div class="workspace">
    <div class="phone-stage">
    <div class="phone-shell" id="phoneShell">
      <canvas id="mirrorCanvas" width="640" height="1422" tabindex="0"></canvas>
      <canvas id="fxCanvas" width="640" height="1422"></canvas>
      <div class="overlay-banner" id="stateOverlay">
        <div style="font-size:28px;" id="overlayIcon">🌙</div>
        <div style="font-size:14px;font-weight:700;" id="overlayTitle">手机当前处于息屏状态</div>
        <div style="font-size:12px;color:var(--muted);line-height:1.5;" id="overlaySub">点击下方按钮立即点亮屏幕并自动解锁</div>
        <button class="btn-sm btn-primary" style="padding:9px 16px;font-size:13px;" onclick="wakeAndUnlock()">☀️ 点亮并解锁屏幕</button>
      </div>
    </div>
    </div>

    <div class="side-dock">
      <button class="dock-btn" onclick="sendNav('back')" title="手机返回键">◀ 返回</button>
      <button class="dock-btn" onclick="sendNav('home')" title="回到手机桌面">● 桌面</button>
      <button class="dock-btn" onclick="sendNav('recents')" title="多任务视图">■ 多任务</button>
      <hr style="width:100%;border:0;border-top:1px solid #1F2937;margin:2px 0;"/>
      <button class="dock-btn" onclick="wakeAndUnlock()" title="点亮并自动绘制解锁图案">🔓 唤醒解锁</button>
      <button class="dock-btn" onclick="sendNav('lock_screen')" title="立即锁屏">🔒 锁屏</button>
      <button class="dock-btn" onclick="sendNav('notifications')" title="下拉通知栏">🔔 通知栏</button>
      <button class="dock-btn" onclick="sendNav('quick_settings')" title="下拉控制中心">🎛️ 控制中心</button>
      <hr style="width:100%;border:0;border-top:1px solid #1F2937;margin:2px 0;"/>
      <button class="dock-btn" onclick="adjustVol('up')">🔊 音量+</button>
      <button class="dock-btn" onclick="adjustVol('down')">🔉 音量-</button>
      <button class="dock-btn" onclick="saveCurrentScreenshot()">📸 截屏存Inbox</button>
    </div>
  </div>

  <div class="bottombar">
    <div class="nav-group">
      <button class="btn-sm" onclick="sendNav('back')">◀</button>
      <button class="btn-sm" onclick="sendNav('home')">●</button>
      <button class="btn-sm" onclick="sendNav('recents')">■</button>
    </div>
    <input class="text-in" id="mirrorTextInput" placeholder="⌨️ 在此输入中文/英文按回车，直接填入手机当前焦点输入框..." onkeydown="onTextInputKey(event)" />
    <button class="btn-sm btn-primary" onclick="submitMirrorText(false)">填入</button>
    <button class="btn-sm" onclick="submitMirrorText(true)">填入并回车</button>
  </div>

  <div id="resultText" role="status" aria-live="polite"></div>
  <div class="toast" id="toast"></div>

<script>
const canvas = document.getElementById('mirrorCanvas');
const ctx = canvas.getContext('2d');
const fxCanvas = document.getElementById('fxCanvas');
const fxCtx = fxCanvas.getContext('2d');
const shell = document.getElementById('phoneShell');

let ws = null;
const hudMode = new URLSearchParams(location.search).get('hud') === '1';
let active = !hudMode;
let isRunning = false;
let generation = 0;
let frameEpoch = 0;
let lastFrameAt = 0;
let blockedFrame = true;
let reconnectTimer = null;
let connectTimer = null;
let staleTimer = null;
let reconnectAttempts = 0;
let requestSequence = 0;
let latestFrame = null;
let decoding = false;
let rippleAnimation = null;
const pendingInputs = new Map();
let textSubmission = null;
const pendingHttp = new Map();
let phoneWidth = 1080;
let phoneHeight = 2400;
let clientFrameTimes = [];
let pointerState = null;
let lastWheelSentAt = 0;
let ripples = [];

function showToast(msg) {
  document.getElementById('resultText').textContent = msg;
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.display = 'block';
  clearTimeout(window.__mirrorToast);
  window.__mirrorToast = setTimeout(() => { t.style.display = 'none'; }, 1800);
}

function addRipple(normX, normY, normX2, normY2) {
  ripples.push({
    x1: normX * fxCanvas.width,
    y1: normY * fxCanvas.height,
    x2: normX2 !== undefined ? normX2 * fxCanvas.width : undefined,
    y2: normY2 !== undefined ? normY2 * fxCanvas.height : undefined,
    born: performance.now()
  });
  if (!rippleAnimation) rippleAnimation = requestAnimationFrame(renderRipples);
}

function renderRipples(now) {
  rippleAnimation = null;
  fxCtx.clearRect(0, 0, fxCanvas.width, fxCanvas.height);
  ripples = ripples.filter(r => now - r.born < 420);
  for (const r of ripples) {
    const p = Math.min(1, (now - r.born) / 420);
    const alpha = 1 - p;
    if (r.x2 !== undefined && r.y2 !== undefined) {
      fxCtx.strokeStyle = 'rgba(56, 189, 248, ' + (alpha * 0.85) + ')';
      fxCtx.lineWidth = 5;
      fxCtx.lineCap = 'round';
      fxCtx.beginPath();
      fxCtx.moveTo(r.x1, r.y1);
      fxCtx.lineTo(r.x2, r.y2);
      fxCtx.stroke();
    }
    fxCtx.strokeStyle = 'rgba(139, 92, 246, ' + alpha + ')';
    fxCtx.fillStyle = 'rgba(56, 189, 248, ' + (alpha * 0.35) + ')';
    fxCtx.lineWidth = 3;
    fxCtx.beginPath();
    fxCtx.arc(r.x2 ?? r.x1, r.y2 ?? r.y1, 10 + p * 22, 0, Math.PI * 2);
    fxCtx.fill();
    fxCtx.stroke();
  }
  if (ripples.length > 0) rippleAnimation = requestAnimationFrame(renderRipples);
}

function clearFrame() {
  frameEpoch++;
  latestFrame = null;
  lastFrameAt = 0;
  blockedFrame = true;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  fxCtx.clearRect(0, 0, fxCanvas.width, fxCanvas.height);
  clientFrameTimes = [];
  document.getElementById('fpsBadge').textContent = '⚡ -- FPS';
  if (pointerState) clearTimeout(pointerState.timer);
  pointerState = null;
  refreshControls();
}

function canControl(unlock = false) {
  const connected = active && isRunning && ws && ws.readyState === WebSocket.OPEN;
  return Boolean(connected && (unlock || (!blockedFrame && lastFrameAt > 0 && performance.now() - lastFrameAt < 5000)));
}

function refreshControls() {
  document.querySelectorAll('button[onclick]').forEach(button => {
    const action = button.getAttribute('onclick') || '';
    if (action.includes('toggleStartStop')) return;
    button.disabled = !canControl(action.includes('wakeAndUnlock')) || (action.includes('submitMirrorText') && textSubmission !== null);
  });
  document.getElementById('mirrorTextInput').disabled = !canControl();
  document.getElementById('presetSelect').disabled = !active || !isRunning;
}

function setOverlay(title, detail, unlock = false) {
  document.getElementById('stateOverlay').classList.add('show');
  document.getElementById('overlayTitle').textContent = title;
  document.getElementById('overlaySub').textContent = detail;
  document.querySelector('#stateOverlay button').hidden = !unlock;
}

function updateOverlayFlags(flags) {
  if (flags & 7) {
    clearFrame();
    if (flags & 1) setOverlay('安全隐私窗口', '画面已遮蔽，请在手机上离开受保护页面');
    else setOverlay(flags & 4 ? '手机已锁屏' : '手机已息屏', '可明确点击唤醒解锁；不会自动输入解锁凭据', true);
    return false;
  }
  return true;
}

function handleBinaryScreenFrame(buf) {
  if (!active || !isRunning || buf.byteLength < 16) return;
  const view = new DataView(buf);
  if (!updateOverlayFlags(view.getUint8(1))) return;
  if (view.getUint8(0) === 0 || buf.byteLength <= 16) return;
  latestFrame = { buf, epoch: frameEpoch, session: generation };
  void drainFrames();
}

async function drainFrames() {
  if (decoding) return;
  decoding = true;
  try {
    while (latestFrame) {
      const frame = latestFrame;
      latestFrame = null;
      const view = new DataView(frame.buf);
      const mime = view.getUint8(0) === 2 ? 'image/jpeg' : 'image/webp';
      let bmp;
      try {
        bmp = await createImageBitmap(new Blob([new Uint8Array(frame.buf, 16)], { type: mime }));
        if (!active || !isRunning || frame.session !== generation || frame.epoch !== frameEpoch) continue;
        phoneWidth = view.getUint16(2, false) || bmp.width;
        phoneHeight = view.getUint16(4, false) || bmp.height;
        shell.style.aspectRatio = phoneWidth + ' / ' + phoneHeight;
        if (shell.style.setProperty) shell.style.setProperty('--phone-ratio', String(phoneWidth / phoneHeight));
        canvas.width = fxCanvas.width = bmp.width;
        canvas.height = fxCanvas.height = bmp.height;
        ctx.drawImage(bmp, 0, 0);
        lastFrameAt = performance.now();
        reconnectAttempts = 0;
        blockedFrame = false;
        document.getElementById('stateOverlay').classList.remove('show');
        document.getElementById('resBadge').textContent = '📐 ' + phoneWidth + '×' + phoneHeight;
        clientFrameTimes.push(lastFrameAt);
        while (clientFrameTimes.length && lastFrameAt - clientFrameTimes[0] > 2500) clientFrameTimes.shift();
        const fps = clientFrameTimes.length < 2 ? '1.0' : ((clientFrameTimes.length - 1) * 1000 / Math.max(150, lastFrameAt - clientFrameTimes[0])).toFixed(1);
        document.getElementById('fpsBadge').textContent = '⚡ ' + fps + ' FPS';
        refreshControls();
      } catch (_) {
        if (active && frame.session === generation) showToast('画面解码失败，正在等待下一帧');
      } finally { if (bmp) bmp.close(); }
    }
  } finally { decoding = false; }
}

function applyMirrorStatus(st) {
  if (!st) return;
  isRunning = Boolean(st.running);
  const badge = document.getElementById('liveBadge');
  const btn = document.getElementById('btnStartStop');
  if (hudMode) {
    btn.textContent = active ? '结束观看' : '开始镜像';
    btn.className = 'btn-sm btn-primary';
  }
  if (st.running) {
    badge.textContent = st.mode === 'native_stream' ? '🟢 实时同屏中 (原生流)' : '🟢 实时同屏中 (兼容轮询)';
    if (!hudMode) btn.textContent = '⏸️ 暂停';
    btn.className = 'btn-sm btn-primary';
  } else {
    badge.textContent = '⚪ 已暂停同屏';
    if (!hudMode) btn.textContent = '▶️ 继续同屏';
    clearFrame();
    setOverlay('镜像已停止', '结束当前观看后可重新开始');
    btn.className = 'btn-sm';
  }
  refreshControls();
  if (st.width && st.height) {
    phoneWidth = st.width;
    phoneHeight = st.height;
    document.getElementById('resBadge').textContent = '📐 ' + st.width + '×' + st.height;
  }
  if (st.foregroundAppLabel || st.foregroundPackage) {
    document.getElementById('appBadge').textContent = '📱 ' + (st.foregroundAppLabel || st.foregroundPackage);
  }
}

function settleInput(reqId, ok, message) {
  const entry = pendingInputs.get(reqId);
  if (!entry) return;
  clearTimeout(entry.timer);
  pendingInputs.delete(reqId);
  showToast(message);
  entry.resolve(ok);
}

function cancelInputs() {
  for (const id of [...pendingInputs.keys()]) settleInput(id, false, '连接已结束，未确认操作的结果未知；不会自动重发');
}

function sendCommand(payload) {
  const input = payload.type === 'input';
  if (!active || !ws || ws.readyState !== WebSocket.OPEN || (input && !canControl(payload.inputType === 'unlock'))) {
    showToast('当前没有可操作的新鲜画面，请先连接镜像');
    return Promise.resolve(false);
  }
  if (!input) {
    try { ws.send(JSON.stringify(payload)); } catch (_) { showToast('连接已断开'); }
    return Promise.resolve(true);
  }
  if (pendingInputs.size >= 32) { showToast('操作仍在确认中，请稍后'); return Promise.resolve(false); }
  const reqId = 'view-' + generation + '-' + (++requestSequence);
  return new Promise(resolve => {
    const wait = ['tap', 'long_press'].includes(payload.inputType) ? 68000 : 8000;
    const timer = setTimeout(() => settleInput(reqId, false, '操作确认超时，结果未知；请核对手机后再操作'), wait);
    pendingInputs.set(reqId, { resolve, timer });
    showToast('已发送，等待手机确认');
    try { ws.send(JSON.stringify({ ...payload, reqId })); }
    catch (_) { settleInput(reqId, false, '发送失败，未获手机确认'); }
  });
}

function connectMirrorWs() {
  if (!active) return;
  const session = ++generation;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(proto + '//' + location.host + '/ws/mirror' + (hudMode ? '?manual=1' : ''));
  ws = socket;
  socket.binaryType = 'arraybuffer';
  let initialStatus = hudMode;
  const current = () => active && session === generation && ws === socket;
  clearTimeout(connectTimer);
  connectTimer = setTimeout(() => {
    if (current()) { showToast('镜像连接或启动超时'); socket.close(); }
  }, 10000);
  socket.onopen = () => {
    if (!current()) { socket.close(); return; }
    if (!hudMode) {
      const [maxWidth, quality, maxFps] = document.getElementById('presetSelect').value.split(',').map(Number);
      socket.send(JSON.stringify({ type: 'start', maxWidth, quality, maxFps, autoWake: true }));
    }
  };
  socket.onmessage = ev => {
    if (!current()) return;
    if (ev.data instanceof ArrayBuffer) handleBinaryScreenFrame(ev.data);
    else if (typeof ev.data === 'string') {
      try {
        const msg = JSON.parse(ev.data);
        if (msg.type === 'mirror_status') {
          applyMirrorStatus(msg.status);
          if (!initialStatus && !msg.status.starting) clearTimeout(connectTimer);
          if (msg.status.running) {
            clearTimeout(connectTimer);
            if (initialStatus && msg.status.maxWidth) {
              const preset = [msg.status.maxWidth, msg.status.quality, msg.status.maxFps].join(',');
              const select = document.getElementById('presetSelect');
              if (![...select.options].some(o => o.value === preset)) select.add(new Option('当前共享画质', preset));
              select.value = preset;
            }
          }
          if (initialStatus) {
            initialStatus = false;
            if (!msg.status.running && !msg.status.starting) {
              const [maxWidth, quality, maxFps] = document.getElementById('presetSelect').value.split(',').map(Number);
              socket.send(JSON.stringify({ type: 'start', maxWidth, quality, maxFps, autoWake: false }));
              setOverlay('正在开始镜像', '锁屏或息屏时不会自动解锁');
            }
          }
        } else if (msg.type === 'input_ack') {
          const ok = msg.ok !== false && msg.result?.ok !== false;
          settleInput(msg.reqId, ok, ok ? '手机已确认操作' : ('手机拒绝操作：' + (msg.result?.error || msg.result?.message || '未执行')));
        } else if (msg.type === 'mirror_error') {
          if (msg.reqId) settleInput(msg.reqId, false, '操作失败：' + msg.error);
          else { hostDeactivate(); showToast('镜像失败：' + msg.error); }
        }
      } catch (_) { showToast('镜像响应格式异常'); }
    }
  };
  socket.onclose = () => {
    if (!current()) return;
    clearTimeout(connectTimer);
    ws = null;
    isRunning = false;
    clearFrame();
    cancelInputs();
    if (++reconnectAttempts > 5) {
      hostDeactivate();
      showToast('连接中断，请检查 Mac 与手机连接后重新开始');
      return;
    }
    setOverlay('连接中断', '正在重连；不会重发旧操作');
    document.getElementById('liveBadge').textContent = '正在重连 ' + reconnectAttempts + '/5';
    reconnectTimer = setTimeout(connectMirrorWs, Math.min(10000, 1000 * Math.pow(2, reconnectAttempts - 1)));
  };
}

function beginWatching() {
  if (active && ws) return;
  active = true;
  reconnectAttempts = 0;
  document.getElementById('btnStartStop').textContent = hudMode ? '结束观看' : '⏸️ 暂停';
  setOverlay('正在连接', '等待手机画面');
  clearInterval(staleTimer);
  staleTimer = setInterval(() => {
    if (lastFrameAt > 0 && performance.now() - lastFrameAt >= 5000) {
      clearFrame();
      setOverlay('画面暂未更新', '操作已暂停，等待新画面');
    }
  }, 1000);
  connectMirrorWs();
}

function hostDeactivate() {
  active = false;
  generation++;
  textSubmission = null;
  isRunning = false;
  clearTimeout(reconnectTimer);
  clearTimeout(connectTimer);
  clearInterval(staleTimer);
  clearTimeout(window.__mirrorToast);
  cancelAnimationFrame(rippleAnimation);
  rippleAnimation = null;
  ripples = [];
  const old = ws;
  ws = null;
  if (old) old.close();
  clearFrame();
  cancelInputs();
  clearTimeout(window.__mirrorToast);
  for (const [abort, timer] of pendingHttp) { abort.abort(); clearTimeout(timer); }
  pendingHttp.clear();
  document.getElementById('btnStartStop').textContent = '开始镜像';
  document.getElementById('liveBadge').textContent = '未开始观看';
  setOverlay('手机镜像', '手动开始观看；结束后不影响其他观看窗口');
}
window.hostDeactivate = hostDeactivate;
window.addEventListener('pagehide', hostDeactivate);

function normCoords(e) {
  const rect = canvas.getBoundingClientRect();
  const normX = Math.max(0, Math.min(1, (e.clientX - rect.left) / Math.max(1, rect.width)));
  const normY = Math.max(0, Math.min(1, (e.clientY - rect.top) / Math.max(1, rect.height)));
  return { normX, normY, pxX: e.clientX - rect.left, pxY: e.clientY - rect.top };
}

canvas.addEventListener('pointerdown', (e) => {
  if (e.button !== 0 || !canControl()) return;
  canvas.focus();
  canvas.setPointerCapture(e.pointerId);
  const c = normCoords(e);
  const state = {
    pointerId: e.pointerId,
    startX: c.normX,
    startY: c.normY,
    startPxX: c.pxX,
    startPxY: c.pxY,
    curX: c.normX,
    curY: c.normY,
    curPxX: c.pxX,
    curPxY: c.pxY,
    downAt: performance.now(),
    longPressed: false,
    timer: setTimeout(() => {
      if (!pointerState || pointerState !== state) return;
      const dist = Math.hypot(state.curPxX - state.startPxX, state.curPxY - state.startPxY);
      if (dist < 12) {
        state.longPressed = true;
        addRipple(state.startX, state.startY);
        sendCommand({ type: 'input', inputType: 'long_press', normX: state.startX, normY: state.startY, durationMs: 650 });

      }
    }, 520)
  };
  pointerState = state;
});

canvas.addEventListener('pointermove', (e) => {
  if (!pointerState || pointerState.pointerId !== e.pointerId) return;
  const c = normCoords(e);
  pointerState.curX = c.normX;
  pointerState.curY = c.normY;
  pointerState.curPxX = c.pxX;
  pointerState.curPxY = c.pxY;
});

canvas.addEventListener('pointerup', (e) => {
  if (!pointerState || pointerState.pointerId !== e.pointerId) return;
  const st = pointerState;
  pointerState = null;
  clearTimeout(st.timer);
  if (st.longPressed) return;

  const c = normCoords(e);
  const dist = Math.hypot(c.pxX - st.startPxX, c.pxY - st.startPxY);
  const dur = Math.max(80, Math.min(700, Math.round(performance.now() - st.downAt)));

  if (dist < 12) {
    addRipple(c.normX, c.normY);
    sendCommand({ type: 'input', inputType: 'tap', normX: c.normX, normY: c.normY });
  } else {
    addRipple(st.startX, st.startY, c.normX, c.normY);
    sendCommand({
      type: 'input',
      inputType: 'swipe',
      normX1: st.startX,
      normY1: st.startY,
      normX2: c.normX,
      normY2: c.normY,
      durationMs: Math.max(140, Math.min(520, dur))
    });
  }
});

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  const now = performance.now();
  if (now - lastWheelSentAt < 180) return;
  lastWheelSentAt = now;
  const c = normCoords(e);
  sendCommand({
    type: 'input',
    inputType: 'scroll',
    normX: c.normX,
    normY: c.normY,
    deltaX: e.deltaX,
    deltaY: e.deltaY
  });
}, { passive: false });

canvas.addEventListener('pointercancel', () => { if (pointerState) clearTimeout(pointerState.timer); pointerState = null; });

canvas.addEventListener('keydown', (e) => {
  if (e.isComposing || e.keyCode === 229) return;
  if (e.key === 'Backspace') {
    e.preventDefault();
    sendCommand({ type: 'input', inputType: 'backspace' });
  } else if (e.key === 'Enter') {
    e.preventDefault();
    sendCommand({ type: 'input', inputType: 'enter' });
  } else if (e.key === 'Escape' && !hudMode) {
    e.preventDefault();
    sendNav('back');
  }
});

window.addEventListener('paste', (e) => {
  if (document.activeElement !== canvas || !canControl()) return;
  const text = (e.clipboardData || window.clipboardData)?.getData('text');
  if (text) {
    e.preventDefault();
    sendCommand({ type: 'input', inputType: 'text', text, append: true });

  }
});

function onTextInputKey(e) {
  if (e.isComposing || e.keyCode === 229) return;
  if (e.key === 'Enter') {
    e.preventDefault();
    submitMirrorText(true);
  }
}

async function submitMirrorText(submitEnter) {
  if (textSubmission !== null) { showToast('文字仍在确认中，请稍后'); return; }
  const input = document.getElementById('mirrorTextInput');
  const text = input.value;
  if (!text) return;
  const token = {};
  const session = generation;
  textSubmission = token;
  refreshControls();
  try {
    const ok = await sendCommand({ type: 'input', inputType: 'text', text, append: false, submit: Boolean(submitEnter) });
    if (ok && active && session === generation && textSubmission === token && input.value === text) input.value = '';
  } finally {
    if (textSubmission === token) { textSubmission = null; refreshControls(); }
  }
}

function sendNav(target) {
  sendCommand({ type: 'input', inputType: 'key', key: target });
}

function wakeAndUnlock() {
  showToast('☀️ 正在点亮并解锁手机...');
  sendCommand({ type: 'input', inputType: 'unlock' });
}

async function phoneControl(params) {
  if (!canControl()) { showToast('当前画面不可操作'); return; }
  const session = generation;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 8000);
  pendingHttp.set(abort, timer);
  showToast('已发送，等待手机确认');
  try {
    const r = await fetch('/api/inbox/action', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'phone_control', params }), signal: abort.signal
    });
    const d = await r.json();
    if (session !== generation || !active) return;
    showToast(r.ok && d.ok && d.result?.ok !== false ? '手机已确认操作' : ('操作失败：' + (d.error || d.result?.error || '未获确认')));
  } catch (_) { if (session === generation) showToast('操作未获确认，请核对手机状态'); }
  finally { clearTimeout(timer); pendingHttp.delete(abort); }
}
function adjustVol(direction) { void phoneControl({ action: 'set_volume', stream: 'music', direction }); }
function saveCurrentScreenshot() { void phoneControl({ action: 'take_screenshot', saveToInbox: true }); }

function onPresetChange() {
  if (!active || !isRunning) return;
  const [maxWidth, quality, maxFps] = document.getElementById('presetSelect').value.split(',').map(Number);
  sendCommand({ type: 'start', maxWidth, quality, maxFps, autoWake: !hudMode });
  showToast('已请求修改共享画质，其他观看窗口也会受影响');
}

function toggleStartStop() {
  if (hudMode) { if (active) hostDeactivate(); else beginWatching(); return; }
  if (isRunning) {
    sendCommand({ type: 'stop' });
  } else {
    const [maxWidth, quality, maxFps] = document.getElementById('presetSelect').value.split(',').map(Number);
    sendCommand({ type: 'start', maxWidth, quality, maxFps, autoWake: true });
  }
}

if (hudMode) hostDeactivate(); else beginWatching();
</script>
</body>
</html>`;
}
