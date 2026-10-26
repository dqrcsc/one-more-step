import { describe, expect, test } from "bun:test";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import {
  computePairingHmac,
  decodeJsonRpcPayload,
  encodeJsonRpcFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  FrameStreamDecoder,
  FrameType,
  generateEd25519Identity,
  generateUuidV7,
  JsonRpcMessage,
  parsePairingUri,
} from "@oms/shared-protocol";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { OmsMcpServer } from "../src/mcp/server.js";
import { handleDashboardHttpRequest } from "../src/inbox/inbox-web-dashboard.js";

describe("Modules 06 & 07: OmsDaemon + Inbox + MCP Server End-to-End", () => {
  test("Full E2E lifecycle: MCP tools/list, Pairing, Screenshot/PKB Inbox, Remote Approval, Alarm & GUI RPC", async () => {
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "oms-e2e-home-"));
    const customPort = 29627;

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

    const ws = new WebSocket(`ws://127.0.0.1:${customPort}/?tier=lan`);
    ws.binaryType = "arraybuffer";
    const decoder = new FrameStreamDecoder();

    let pairedDone!: () => void;
    const pairedPromise = new Promise<void>((r) => (pairedDone = r));

    ws.onmessage = (ev) => {
      const frames = decoder.push(new Uint8Array(ev.data as ArrayBuffer));
      for (const f of frames) {
        if (f.frameType !== FrameType.CONTROL_JSON_RPC) continue;
        const msg = decodeJsonRpcPayload<JsonRpcMessage>(f.payload);

        if ("method" in msg && msg.method === "auth.helloChallenge") {
          const hmacHex = computePairingHmac(
            parsedUri.ephemeralToken,
            parsedUri.pinCode,
            phoneIdentity.deviceId,
            phoneIdentity.publicKeyBase64Url
          );
          ws.send(
            encodeJsonRpcFrame({
              jsonrpc: "2.0",
              id: "pair_e2e",
              method: "pair.request",
              params: {
                phoneId: phoneIdentity.deviceId,
                phoneModel: "Xiaomi 15",
                phoneName: "Xiaomi",
                phonePubKeyBase64Url: phoneIdentity.publicKeyBase64Url,
                pinCode: parsedUri.pinCode,
                hmacHex,
                timestampMs: Date.now(),
              },
            })
          );
        } else if ("id" in msg && msg.id === "pair_e2e") {
          pairedDone();
        } else if ("method" in msg && msg.method === "notify.requestApproval") {
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
        }
      }
    };

    await pairedPromise;

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

    // 8. Verify LAN isolation: non-loopback IPs (e.g. another computer on the same LAN) are blocked with 403 Forbidden
    const lanAttackerInboxResp = await handleDashboardHttpRequest(
      daemon,
      new Request(`http://192.168.1.50:${customPort}/inbox`),
      new URL(`http://192.168.1.50:${customPort}/inbox`),
      "192.168.1.99"
    );
    expect(lanAttackerInboxResp?.status).toBe(403);

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
    fs.rmSync(tmpHome, { recursive: true, force: true });
  });
});
