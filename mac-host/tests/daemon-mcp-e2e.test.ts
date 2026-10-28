import { describe, expect, test } from "bun:test";
import { connectSecurePhone } from "./secure-phone-helper.js";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import {
  decodeJsonRpcPayload,
  decodeScreenFramePayload,
  encodeJsonRpcFrame,
  encodeScreenFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  FrameType,
  generateEd25519Identity,
  generateUuidV7,
  JsonRpcMessage,
  parsePairingUri,
  ScreenFrameCodec,
  ScreenFrameFlags,
} from "@oms/shared-protocol";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { OmsMcpServer } from "../src/mcp/server.js";
import { handleDashboardHttpRequest } from "../src/inbox/inbox-web-dashboard.js";

describe("Modules 06 & 07: OmsDaemon + Inbox + MCP Server End-to-End", () => {
  test("Full E2E lifecycle: MCP tools/list, Pairing, Screenshot/PKB Inbox, Remote Approval, Alarm & GUI RPC", async () => {
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "oms-e2e-home-"));
    let customPort = 0;

    fs.mkdirSync(tmpHome, { recursive: true });
    fs.writeFileSync(
      path.join(tmpHome, "config.json"),
      JSON.stringify({
        lanPort: customPort,
        enableBluetoothBridge: false,
        dingtalk: { enabled: false },
      }),
      "utf8"
    );

    const daemon = new OmsDaemon(tmpHome);
    await daemon.start({ startTransport: true });
    customPort = (daemon.session as any).wsServer.port;

    const mcp = new OmsMcpServer(tmpHome);

    // 1. Verify MCP tools/list exposes all 13 tools
    const listResp = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 1,
      method: "tools/list",
    });
    expect(listResp.result.tools.length).toBe(13);

    // 2. Initiate pairing via daemon IPC and connect simulated Xiaomi phone
    const pairInfo = await daemon.handleIpcAction("daemon.pair", {});
    const parsedUri = parsePairingUri(pairInfo.pairingUri);
    const phoneIdentity = generateEd25519Identity("xiaomi");

    const ws = await connectSecurePhone(`ws://127.0.0.1:${customPort}/?tier=lan`, {
      phoneId: phoneIdentity.deviceId, macId: parsedUri.id, pskHex: parsedUri.ephemeralToken,
      onFrame(f, ws) {
        if (f.frameType !== FrameType.CONTROL_JSON_RPC) return;
        const msg = decodeJsonRpcPayload<JsonRpcMessage>(f.payload);
        if ("method" in msg && msg.method === "notify.requestApproval") {
          const params = msg.params as { approvalId: string };
          // Reply to RPC immediately, then simulate user tapping "批准继续" on phone 50ms later
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: msg.id!,
              result: { received: true },
            })
          );
          setTimeout(() => {
            ws.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: "apr_cb_1",
                method: "approval.respond",
                params: {
                  approvalId: params.approvalId,
                  approved: true,
                  selectedOption: "批准继续",
                  userComment: "在地铁上确认通过，请继续部署",
                  decidedAtMs: Date.now(),
                },
              })
            );
          }, 50);
        } else if ("method" in msg && msg.method === "sys.control") {
          const params = msg.params as { action: string; pattern?: string };
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: msg.id!,
              result: {
                ok: true,
                action: params.action,
                unlocked: true,
                method: "pattern",
                patternCells: params.pattern,
                screenLocked: false,
                screenInteractive: true,
              },
            })
          );
        } else if ("method" in msg && msg.method === "gui.observe") {
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: msg.id!,
              result: {
                packageName: "com.alibaba.android.rimet",
                activityName: ".biz.LaunchHomeActivity",
                isSecureWindow: false,
                screenWidth: 1080,
                screenHeight: 2400,
                nodes: [
                  {
                    id: "#1",
                    role: "Button",
                    label: "工作台 · 日报填报",
                    clickable: true,
                    editable: false,
                    scrollable: false,
                    bounds: [48, 420, 510, 680],
                    center: [279, 550],
                  },
                ],
              },
            })
          );
        } else if ("method" in msg && msg.method === "mirror.start") {
          const params = (msg.params || {}) as Record<string, any>;
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: msg.id!,
              result: {
                ok: true,
                active: true,
                mode: "l1_accessibility",
                targetMaxWidth: params.maxWidth ?? 720,
                targetQuality: params.quality ?? 72,
                targetMaxFps: params.maxFps ?? 12,
              },
            })
          );
          setTimeout(() => {
            ws.send(
              encodeScreenFrame(
                {
                  codec: ScreenFrameCodec.WEBP,
                  flags: 0,
                  width: 1080,
                  height: 2400,
                  seq: 1,
                  timestampMs: Date.now(),
                },
                new TextEncoder().encode("FAKE_WEBP_MIRROR_FRAME_01")
              )
            );
          }, 20);
        } else if ("method" in msg && msg.method === "mirror.input") {
          const params = (msg.params || {}) as Record<string, any>;
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: msg.id!,
              result: {
                ok: true,
                action: params.action || params.type,
                x: params.x,
                y: params.y,
                text: params.text,
              },
            })
          );
        } else if ("method" in msg && msg.method === "mirror.stop") {
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: msg.id!,
              result: { ok: true, active: false },
            })
          );
        }
      },
    });

    // 3. Simulate Xiaomi phone submitting a Screenshot + Voice Note (#ui_bug)
    const streamId = generateUuidV7();
    const imgBytes = new TextEncoder().encode("FAKE_PNG_BYTES_FROM_XIAOMI");
    const sha256 = crypto.createHash("sha256").update(imgBytes).digest("hex");

    ws.send(
      encodeStreamStartFrame({
        streamId,
        fileName: "bug_shot.png",
        mimeType: "image/png",
        totalBytes: imgBytes.byteLength,
        chunkSize: 1024,
        totalChunks: 1,
        sha256,
        intentTag: "ui_bug",
      })
    );
    ws.send(encodeStreamChunkFrame(streamId, 0, imgBytes));

    // Wait briefly for stream assembly, then submit inbox item
    await new Promise((r) => setTimeout(r, 40));

    const itemId = generateUuidV7();
    ws.send(
      encodeJsonRpcFrame({
        jsonrpc: "2.0",
        id: "sub_1",
        method: "inbox.submitItem",
        params: {
          clientItemId: itemId,
          intentTag: "ui_bug",
          title: "手机截屏",
          voiceTranscript: "底部按钮边距歪了，按这个截图改一下",
          createdAtMs: Date.now(),
          attachments: [
            {
              streamId,
              fileName: "bug_shot.png",
              mimeType: "image/png",
              totalBytes: imgBytes.byteLength,
              sha256,
            },
          ],
        },
      })
    );

    await new Promise((r) => setTimeout(r, 50));

    // 4. Call MCP tool `phone_inbox_latest` and verify file & voice transcript
    const inboxToolCall = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/call",
      params: {
        name: "phone_inbox_latest",
        arguments: { tag: "ui_bug", limit: 5 },
      },
    });
    const inboxData = JSON.parse(inboxToolCall.result.content[0].text);
    expect(inboxData.count).toBe(1);
    expect(inboxData.items[0].voiceTranscript).toBe("底部按钮边距歪了，按这个截图改一下");
    expect(fs.existsSync(inboxData.items[0].files[0].absPath)).toBe(true);

    // 5. Call MCP tool `phone_request_approval` and verify blocking human-in-the-loop callback
    const approvalToolCall = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "phone_request_approval",
        arguments: {
          title: "是否执行数据库迁移？",
          summary: "共修改 3 个表结构",
        },
      },
    });
    const approvalData = JSON.parse(approvalToolCall.result.content[0].text);
    expect(approvalData.approved).toBe(true);
    expect(approvalData.selectedOption).toBe("批准继续");
    expect(approvalData.userComment).toBe("在地铁上确认通过，请继续部署");

    // 6. Call MCP tool `phone_gui_observe`
    const guiToolCall = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "phone_gui_observe",
        arguments: { includeScreenshot: false },
      },
    });
    const guiData = JSON.parse(guiToolCall.result.content[0].text);
    expect(guiData.packageName).toBe("com.alibaba.android.rimet");
    expect(guiData.nodes[0].id).toBe("#1");

    // 7. Verify Mac Web Inbox Dashboard (/inbox, /api/inbox, /api/inbox/file, /api/inbox/action) & /apk/version
    const dashHtmlResp = await fetch(`http://127.0.0.1:${customPort}/inbox`);
    expect(dashHtmlResp.status).toBe(200);
    const dashHtml = await dashHtmlResp.text();
    expect(dashHtml).toContain("Mac 实时收件箱看板");

    const apiInboxResp = await fetch(`http://127.0.0.1:${customPort}/api/inbox?tag=ui_bug`);
    const apiInboxJson = (await apiInboxResp.json()) as any;
    expect(apiInboxJson.ok).toBe(true);
    expect(apiInboxJson.items.length).toBe(1);
    expect(apiInboxJson.items[0].itemId).toBe(itemId);

    const fileResp = await fetch(
      `http://127.0.0.1:${customPort}/api/inbox/file?itemId=${encodeURIComponent(itemId)}&file=bug_shot.png`
    );
    expect(fileResp.status).toBe(200);
    expect(await fileResp.text()).toBe("FAKE_PNG_BYTES_FROM_XIAOMI");

    const markResp = await fetch(`http://127.0.0.1:${customPort}/api/inbox/action`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: "mark_processed", itemId, processed: true }),
    });
    expect(((await markResp.json()) as any).ok).toBe(true);

    const apkVerResp = await fetch(`http://127.0.0.1:${customPort}/apk/version`);
    expect(apkVerResp.status).toBe(200);
    const apkVerJson = (await apkVerResp.json()) as any;
    expect(typeof apkVerJson.versionCode).toBe("number");
    expect(typeof apkVerJson.sha256).toBe("string");

    // 7b. Simulate real-time SMS / App OTP synchronization (#sms_otp)
    const otpItemId = generateUuidV7();
    ws.send(
      encodeJsonRpcFrame({
        jsonrpc: "2.0",
        id: "sub_otp_1",
        method: "inbox.submitItem",
        params: {
          clientItemId: otpItemId,
          intentTag: "sms_otp",
          title: "🔑 【阿里云】验证码: 839201",
          textContent: "【阿里云】您的登录验证码为 839201，请在5分钟内使用，切勿泄露。",
          otpCode: "839201",
          otpSender: "阿里云",
          sourceApp: "com.android.mms",
          createdAtMs: Date.now(),
        },
      })
    );
    await new Promise((r) => setTimeout(r, 40));

    const otpToolCall = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 5,
      method: "tools/call",
      params: {
        name: "phone_inbox_latest",
        arguments: { tag: "sms_otp", limit: 3 },
      },
    });
    const otpData = JSON.parse(otpToolCall.result.content[0].text);
    expect(otpData.count).toBe(1);
    expect(otpData.items[0].otpCode).toBe("839201");
    expect(otpData.items[0].otpSender).toBe("阿里云");

    // 7c. Test remote pattern screen unlock & saved pattern persistence
    const unlockCall1 = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 6,
      method: "tools/call",
      params: {
        name: "phone_device_control",
        arguments: { action: "unlock_screen", pattern: "14789", savePattern: true },
      },
    });
    const unlockRes1 = JSON.parse(unlockCall1.result.content[0].text);
    expect(unlockRes1.ok).toBe(true);
    expect(unlockRes1.unlocked).toBe(true);
    expect(unlockRes1.patternCells).toBe("14789");
    expect(unlockRes1.savedPatternToMacConfig).toBe(true);

    // Subsequent call without pattern should automatically use saved pattern from Mac config
    const unlockCall2 = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 7,
      method: "tools/call",
      params: {
        name: "phone_device_control",
        arguments: { action: "unlock_screen" },
      },
    });
    const unlockRes2 = JSON.parse(unlockCall2.result.content[0].text);
    expect(unlockRes2.ok).toBe(true);
    expect(unlockRes2.patternCells).toBe("14789");

    // 7d. Verify Live Screen Mirroring (/mirror, /ws/mirror, mirror_start, SCREEN_FRAME ingestion, mirror_input)
    const mirrorHtmlResp = await fetch(`http://127.0.0.1:${customPort}/mirror`);
    expect(mirrorHtmlResp.status).toBe(200);
    const mirrorHtml = await mirrorHtmlResp.text();
    expect(mirrorHtml).toContain("OneMoreStep · 手机实时同屏与键鼠直控台");

    const mirrorStartResp = await fetch(`http://127.0.0.1:${customPort}/api/inbox/action`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: "mirror_start",
        params: { maxWidth: 720, quality: 72, maxFps: 12, wakeScreen: false },
      }),
    });
    const mirrorStartJson = (await mirrorStartResp.json()) as any;
    expect(mirrorStartJson.ok).toBe(true);
    expect(mirrorStartJson.status.running).toBe(true);
    expect(mirrorStartJson.status.mode).toBe("native_stream");

    await new Promise((r) => setTimeout(r, 60));

    const mirrorFrameResp = await fetch(`http://127.0.0.1:${customPort}/api/inbox/mirror/frame`);
    expect(mirrorFrameResp.status).toBe(200);
    expect(mirrorFrameResp.headers.get("X-Oms-Width")).toBe("1080");
    expect(mirrorFrameResp.headers.get("X-Oms-Height")).toBe("2400");
    expect(await mirrorFrameResp.text()).toBe("FAKE_WEBP_MIRROR_FRAME_01");

    // Connect a local viewer WebSocket to /ws/mirror and verify cached binary frame delivery
    const receivedViewerFrames: Uint8Array[] = [];
    const viewerWs = new WebSocket(`ws://127.0.0.1:${customPort}/ws/mirror`);
    viewerWs.binaryType = "arraybuffer";
    viewerWs.onmessage = (ev) => {
      if (ev.data instanceof ArrayBuffer) {
        receivedViewerFrames.push(new Uint8Array(ev.data));
      }
    };
    await new Promise((r) => setTimeout(r, 50));
    expect(receivedViewerFrames.length).toBeGreaterThanOrEqual(1);
    const decodedViewerFrame = decodeScreenFramePayload(receivedViewerFrames[0]);
    expect(decodedViewerFrame.width).toBe(1080);
    expect(decodedViewerFrame.height).toBe(2400);
    expect(new TextDecoder().decode(decodedViewerFrame.data)).toBe("FAKE_WEBP_MIRROR_FRAME_01");
    viewerWs.close();

    const mirrorInputResp = await fetch(`http://127.0.0.1:${customPort}/api/inbox/action`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: "mirror_input",
        params: { action: "tap", x: 540, y: 1200 },
      }),
    });
    const mirrorInputJson = (await mirrorInputResp.json()) as any;
    expect(mirrorInputJson.ok).toBe(true);
    expect(mirrorInputJson.result.action).toBe("tap");
    expect(mirrorInputJson.result.x).toBe(540);
    expect(mirrorInputJson.result.y).toBe(1200);

    const mirrorStopResp = await fetch(`http://127.0.0.1:${customPort}/api/inbox/action`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: "mirror_stop" }),
    });
    expect(((await mirrorStopResp.json()) as any).ok).toBe(true);

    // 8. Verify LAN isolation: non-loopback IPs (e.g. another computer on the same LAN) are blocked with 403 Forbidden
    const lanAttackerInboxResp = await handleDashboardHttpRequest(
      daemon,
      new Request(`http://192.168.1.50:${customPort}/inbox`),
      new URL(`http://192.168.1.50:${customPort}/inbox`),
      "192.168.1.99"
    );
    expect(lanAttackerInboxResp?.status).toBe(403);

    const lanAttackerMirrorResp = await handleDashboardHttpRequest(
      daemon,
      new Request(`http://192.168.1.50:${customPort}/mirror`),
      new URL(`http://192.168.1.50:${customPort}/mirror`),
      "192.168.1.99"
    );
    expect(lanAttackerMirrorResp?.status).toBe(403);

    const lanAttackerControlResp = await handleDashboardHttpRequest(
      daemon,
      new Request(`http://192.168.1.50:${customPort}/api/inbox/action`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "phone_control", params: { action: "wake_and_unlock" } }),
      }),
      new URL(`http://192.168.1.50:${customPort}/api/inbox/action`),
      "10.60.110.88"
    );
    expect(lanAttackerControlResp?.status).toBe(403);

    ws.close();
    await daemon.stop();
    // Retain this isolated fixture for inspection; never recursively delete user files.
    console.log(`Retained test fixture: ${tmpHome}`);
  });
});
