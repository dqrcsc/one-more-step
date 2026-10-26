import fs from "node:fs";
import path from "node:path";
import type { OmsDaemon } from "../daemon/oms-daemon.js";
import { PhoneSessionManager } from "../transport/session-manager.js";
import type { IntentTag } from "@oms/shared-protocol";

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
  const isDashboardRoute =
    url.pathname === "/inbox" ||
    url.pathname === "/dashboard" ||
    url.pathname.startsWith("/api/inbox");

  if (isDashboardRoute && !PhoneSessionManager.isLoopbackAddress(clientIp)) {
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

  if (url.pathname === "/inbox" || url.pathname === "/dashboard") {
    return new Response(renderDashboardHtml(), {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store, no-cache, must-revalidate",
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

    return new Response(
      JSON.stringify({
        ok: true,
        items,
        status,
        preferences: hostPrefs,
      }),
      {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "no-store",
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
    const fileEntry =
      item.files.find((f) => f.fileName === fileName) || item.files[0];
    if (!fileEntry || !fs.existsSync(fileEntry.absPath)) {
      return new Response("Attachment file not found", { status: 404 });
    }

    // Security check: ensure file is inside inboxRootDir
    const resolved = path.resolve(fileEntry.absPath);
    const rootResolved = path.resolve(daemon.inbox.inboxRootDir);
    if (!resolved.startsWith(rootResolved)) {
      return new Response("Forbidden path", { status: 403 });
    }

    return new Response(Bun.file(resolved), {
      headers: {
        "Content-Type": fileEntry.mimeType || "application/octet-stream",
        "Cache-Control": "private, max-age=3600",
      },
    });
  }

  if (url.pathname === "/api/inbox/action" && req.method === "POST") {
    try {
      const body = (await req.json()) as Record<string, any>;
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
        case "open_preview": {
          const item = daemon.inbox.getItem(String(body.itemId || ""));
          const targetPath = body.absPath || item?.files?.[0]?.absPath;
          if (!targetPath || !fs.existsSync(targetPath)) {
            return Response.json(
              { ok: false, error: "文件不存在" },
              { status: 404 }
            );
          }
          daemon.openFileOnMac(targetPath, false);
          return Response.json({ ok: true });
        }
        case "reveal_in_finder": {
          const item = daemon.inbox.getItem(String(body.itemId || ""));
          const targetPath = body.absPath || item?.files?.[0]?.absPath;
          if (!targetPath || !fs.existsSync(targetPath)) {
            return Response.json(
              { ok: false, error: "文件不存在" },
              { status: 404 }
            );
          }
          daemon.openFileOnMac(targetPath, true);
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
          });
          return Response.json({ ok: true, preferences: updated });
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
      <h1>📬 OneMoreStep · Mac 实时收件箱看板</h1>
      <div class="status-line" id="statusLine">正在同步连接状态...</div>
    </div>
    <div class="toggles-box">
      <label class="toggle-label" title="手机发来文字或语音转写时，自动复制进 Mac 系统剪贴板，直接 Cmd+V 即可粘贴">
        <input type="checkbox" id="chkAutoCopy" onchange="savePrefs()"/>
        📋 收到文字自动进 Mac 剪贴板
      </label>
      <label class="toggle-label" title="手机发来截图或照片时，自动在 Mac 预览 (Preview.app) 中弹窗打开">
        <input type="checkbox" id="chkAutoPreview" onchange="savePrefs()"/>
        🖼️ 收到图片自动弹窗预览
      </label>
      <a class="btn btn-secondary" href="/install">📲 无线安装/配对页</a>
    </div>
  </div>

  <div class="composer-card" id="devicePanel">
    <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:10px;">
      <div style="font-size:14px;font-weight:700;color:#38BDF8;">
        📱 小米手机实时状态与远程操控台
      </div>
      <div style="display:flex;gap:6px;flex-wrap:wrap;">
        <button class="btn btn-secondary" onclick="ctrlPhone({action:'refresh'})">🔄 刷新实时状态</button>
        <button class="btn" onclick="takePhoneScreenshot()">📸 立即截屏回传 Mac</button>
        <button class="btn btn-secondary" onclick="pullPhoneClip()">📥 读手机剪贴板</button>
        <button class="btn btn-secondary" onclick="findPhone()">🔔 响铃找手机</button>
        <button class="btn btn-green" onclick="triggerOta()">📲 推送APK升级</button>
      </div>
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
      🚀 从 Mac 反向发送消息 / 笔记 / 剪贴板到小米手机
    </div>
    <div class="composer-row">
      <input class="input" id="pushTitle" placeholder="标题 (可选)" style="width:180px;" />
      <textarea class="textarea" id="pushText" placeholder="输入文字、链接或 Markdown 笔记，一键推送至手机时间流或手机剪贴板..."></textarea>
      <div style="display:flex;flex-direction:column;gap:6px;">
        <button class="btn" onclick="pushToPhone()">📤 推送到手机时间流</button>
        <button class="btn btn-secondary" onclick="syncPhoneClip()">📋 写入手机剪贴板</button>
      </div>
    </div>
  </div>

  <div class="filter-bar">
    <div class="tabs" id="tagTabs">
      <button class="tab active" data-tag="all" onclick="setTag('all')">全部</button>
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
    .replace(/"/g, '&quot;');
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
  const autoCopyTextToClipboard = document.getElementById('chkAutoCopy').checked;
  const autoPreviewImageOnMac = document.getElementById('chkAutoPreview').checked;
  await callAction({
    action: 'update_preferences',
    autoCopyTextToClipboard,
    autoPreviewImageOnMac
  });
  showToast('✅ 已保存 Mac 自动联动设置');
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

  grid.innerHTML = [
    { label: '📱 设备型号与系统', value: escapeHtml(devModel + ' · ' + appVer) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml([osVer, uptimeStr].filter(Boolean).join(' · ')) + '</span>' },
    { label: '🔋 电池与温度状态', value: escapeHtml(battPct + '% · ' + chargeText) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml([tempText, voltText, ps.powerSaveMode ? '省电模式开启' : '全性能模式'].filter(Boolean).join(' · ')) + '</span>' },
    { label: '🖥️ 屏幕与当前前台应用', value: escapeHtml(screenState) + '<br/><span style="font-size:11px;color:#38BDF8;">前台: ' + escapeHtml(fgApp) + '</span>' },
    { label: '🌐 网络与局域网 IP', value: escapeHtml('IP: ' + ips) + '<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml([wifiInfo, ps.bluetoothEnabled !== undefined ? (ps.bluetoothEnabled ? '蓝牙已开' : '蓝牙已关') : '', 'RTT ' + (st.lastRttMs || 0) + 'ms'].filter(Boolean).join(' · ')) + '</span>' },
    { label: '🔊 音量与响铃模式', value: escapeHtml(audioInfo) },
    { label: '💾 存储与运行内存', value: escapeHtml(storageInfo) + (ramInfo ? ('<br/><span style="font-size:11px;color:var(--muted);">' + escapeHtml(ramInfo) + '</span>') : '') }
  ].map(t => '<div class="stat-tile"><div class="stat-label">' + t.label + '</div><div class="stat-value">' + t.value + '</div></div>').join('');

  const p = ps.permissions || {};
  const perms = [
    ['后台弹出', p.xiaomiBackgroundPopupAllowed],
    ['锁屏显示', p.xiaomiLockScreenDisplayAllowed],
    ['电池无限制', p.batteryOptimizationIgnored],
    ['悬浮窗', p.overlayAllowed],
    ['无障碍GUI', p.accessibilityEnabled],
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

    renderDevicePanel(st);

    if (data.preferences && !window.__prefsLoaded) {
      document.getElementById('chkAutoCopy').checked = data.preferences.autoCopyTextToClipboard !== false;
      document.getElementById('chkAutoPreview').checked = data.preferences.autoPreviewImageOnMac !== false;
      window.__prefsLoaded = true;
    }

    const items = data.items || [];
    const sig = JSON.stringify(items.map(i => i.itemId + ':' + i.processed + ':' + (i.voiceTranscript || ''))) + '|' + items.length;
    if (sig === lastRenderSignature) return;
    lastRenderSignature = sig;

    const container = document.getElementById('itemsContainer');
    if (items.length === 0) {
      container.innerHTML = '<div class="empty-state">📭 当前筛选条件下暂无手机投递记录<br/><span style="font-size:12px;">在小米手机上发送消息、语音、截屏或分享文件后，这里会自动实时显示</span></div>';
      return;
    }

    container.innerHTML = items.map(item => {
      const timeStr = new Date(item.createdAtMs).toLocaleString('zh-CN', { hour12: false });
      const contentToCopy = item.textContent || item.voiceTranscript || item.title || '';
      const firstFilePath = (item.files && item.files[0]) ? item.files[0].absPath : '';
      const aiPrompt = firstFilePath
        ? ('请查看我从手机投递的文件 "' + firstFilePath + '"' + (contentToCopy ? ('，附言：' + contentToCopy) : ''))
        : ('请处理我从手机发来的内容：' + contentToCopy);

      const filesHtml = (item.files || []).map(f => {
        const isImg = (f.mimeType || '').startsWith('image/') || /\\.(png|jpe?g|webp|gif)$/i.test(f.fileName);
        const isAudio = (f.mimeType || '').startsWith('audio/') || /\\.(m4a|mp3|wav|ogg|aac)$/i.test(f.fileName);
        const fileUrl = '/api/inbox/file?itemId=' + encodeURIComponent(item.itemId) + '&file=' + encodeURIComponent(f.fileName);
        const kb = Math.max(1, Math.round((f.sizeBytes || 0) / 1024));
        return '<div class="attachment-box">' +
          (isImg ? ('<img class="attachment-img" src="' + fileUrl + '" onclick="openLightbox(\\'' + fileUrl + '\\')" title="点击放大查看原图"/>') : '') +
          (isAudio ? ('<audio controls src="' + fileUrl + '" style="width:100%;margin-bottom:8px;"></audio>') : '') +
          '<div class="attachment-meta">📎 ' + escapeHtml(f.fileName) + ' (' + kb + ' KB)<br/><code>' + escapeHtml(f.absPath) + '</code></div>' +
          '<div class="action-row">' +
            (isAudio ? ('<button class="btn" onclick="transcribeItem(\\'' + item.itemId + '\\')">🎙️ 离线语音转写</button>') : '') +
            '<button class="btn" onclick="openOnMac(\\'' + item.itemId + '\\', ' + JSON.stringify(f.absPath).replace(/"/g, '&quot;') + ', false)">👁️ Mac 打开</button>' +
            '<button class="btn btn-secondary" onclick="openOnMac(\\'' + item.itemId + '\\', ' + JSON.stringify(f.absPath).replace(/"/g, '&quot;') + ', true)">📂 Finder 定位</button>' +
            '<button class="btn btn-secondary" onclick="copyText(' + JSON.stringify(f.absPath).replace(/"/g, '&quot;') + ', \\'文件路径\\')">📋 复制路径</button>' +
          '</div>' +
        '</div>';
      }).join('');

      return '<div class="item-card ' + (item.processed ? '' : 'unprocessed') + '">' +
        '<div class="item-header">' +
          '<div class="item-meta">' +
            '<span class="tag-pill">#' + escapeHtml(item.intentTag) + '</span>' +
            '<span>🕒 ' + escapeHtml(timeStr) + '</span>' +
            '<span>' + (item.processed ? '✅ 已处理' : '🔵 未处理') + '</span>' +
          '</div>' +
          '<div class="action-row" style="margin:0;">' +
            (contentToCopy ? '<button class="btn btn-secondary" onclick="copyText(' + JSON.stringify(contentToCopy).replace(/"/g, '&quot;') + ', \\'正文内容\\')">📋 复制内容</button>' : '') +
            '<button class="btn btn-secondary" onclick="copyText(' + JSON.stringify(aiPrompt).replace(/"/g, '&quot;') + ', \\'AI 提示词\\')">🤖 复制 AI 提示词</button>' +
            '<button class="btn btn-secondary" onclick="markItem(\\'' + item.itemId + '\\', ' + (!item.processed) + ')">' + (item.processed ? '设为未读' : '✓ 标为已处理') + '</button>' +
            '<button class="btn btn-danger" onclick="deleteItem(\\'' + item.itemId + '\\')">🗑️</button>' +
          '</div>' +
        '</div>' +
        (item.title ? '<div class="item-title">' + escapeHtml(item.title) + '</div>' : '') +
        (item.textContent ? '<div class="item-text">' + escapeHtml(item.textContent) + '</div>' : '') +
        (item.voiceTranscript ? '<div class="item-voice">🎙️ 语音附言: ' + escapeHtml(item.voiceTranscript) + '</div>' : '') +
        (filesHtml ? '<div class="attachments-grid">' + filesHtml + '</div>' : '') +
      '</div>';
    }).join('');
  } catch (e) {
    // ignore transient poll error
  }
}

fetchInbox();
setInterval(fetchInbox, 2000);
</script>
</body>
</html>`;
}
