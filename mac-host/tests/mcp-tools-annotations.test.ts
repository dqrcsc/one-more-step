import { describe, expect, test } from "bun:test";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  decodeJsonRpcPayload,
  decodeStreamChunkPayload,
  encodeJsonRpcFrame,
  encodeStreamAckResumeFrame,
  encodeStreamChunkFrame,
  encodeStreamStartFrame,
  FrameType,
  generateEd25519Identity,
  generateUuidV7,
  type JsonRpcMessage,
  parsePairingUri,
  type StreamStartHeader,
} from "@oms/shared-protocol";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { OMS_MCP_TOOLS, OmsMcpServer } from "../src/mcp/server.js";
import { connectSecurePhone } from "./secure-phone-helper.js";

const EXPECTED_TOOL_NAMES = [
  "phone_inbox_latest",
  "phone_media_recent",
  "phone_file_pull",
  "phone_file_push",
  "phone_clipboard_sync",
  "phone_alarm_set",
  "phone_calendar_manage",
  "phone_notify",
  "phone_request_approval",
  "phone_presence_status",
  "phone_device_control",
  "phone_gui_observe",
  "phone_gui_action",
] as const;

describe("MCP ToolAnnotations & Full 13-Tool Coverage", () => {
  test("tools/list declares explicit MCP annotations and protocol negotiation for all 13 tools", async () => {
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "oms-mcp-spec-"));
    const mcp = new OmsMcpServer(tmpHome);

    // Protocol version negotiation
    const initDefault = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
    });
    expect(initDefault.result.protocolVersion).toBe("2025-03-26");

    const initLegacy = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 2,
      method: "initialize",
      params: { protocolVersion: "2024-11-05" },
    });
    expect(initLegacy.result.protocolVersion).toBe("2024-11-05");

    const initModern = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 3,
      method: "initialize",
      params: { protocolVersion: "2025-06-18" },
    });
    expect(initModern.result.protocolVersion).toBe("2025-06-18");

    // Verify all 13 tools and all 4 boolean hints
    const listResp = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/list",
    });
    const tools = listResp.result.tools as typeof OMS_MCP_TOOLS;
    expect(tools.map((t) => t.name)).toEqual([...EXPECTED_TOOL_NAMES]);

    for (const tool of tools) {
      expect(tool.annotations).toBeDefined();
      expect(typeof tool.annotations.title).toBe("string");
      expect(tool.annotations.title.trim().length).toBeGreaterThan(0);

      expect(typeof tool.annotations.readOnlyHint).toBe("boolean");
      expect(typeof tool.annotations.destructiveHint).toBe("boolean");
      expect(typeof tool.annotations.idempotentHint).toBe("boolean");
      expect(typeof tool.annotations.openWorldHint).toBe("boolean");

      // MCP semantic consistency rules
      if (tool.annotations.readOnlyHint) {
        expect(tool.annotations.destructiveHint).toBe(false);
        expect(tool.annotations.idempotentHint).toBe(true);
      }
      if (tool.annotations.destructiveHint) {
        expect(tool.annotations.readOnlyHint).toBe(false);
      }
    }

    // Read-only tools check
    const byName = new Map(tools.map((t) => [t.name, t.annotations]));
    expect(byName.get("phone_media_recent")?.readOnlyHint).toBe(true);
    expect(byName.get("phone_presence_status")?.readOnlyHint).toBe(true);
    expect(byName.get("phone_gui_observe")?.readOnlyHint).toBe(true);

    // Destructive-capable tools check
    expect(byName.get("phone_calendar_manage")?.destructiveHint).toBe(true);
    expect(byName.get("phone_device_control")?.destructiveHint).toBe(true);
    expect(byName.get("phone_gui_action")?.destructiveHint).toBe(true);

    // Unknown tool rejection
    const unknownResp = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 5,
      method: "tools/call",
      params: { name: "phone_nonexistent_tool", arguments: {} },
    });
    expect(unknownResp.result.isError).toBe(true);
  });

  test("All 13 MCP tools execute end-to-end via tools/call against paired daemon", async () => {
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "oms-mcp-all13-"));
    fs.writeFileSync(
      path.join(tmpHome, "config.json"),
      JSON.stringify({
        lanPort: 0,
        enableBluetoothBridge: false,
        dingtalk: { enabled: false },
      }),
      "utf8"
    );

    const daemon = new OmsDaemon(tmpHome);
    await daemon.start({ startTransport: true });
    const port = (daemon.session as any).wsServer.port as number;
    const mcp = new OmsMcpServer(tmpHome);

    let phoneClipboard = "initial-clip";
    const incomingPushStreams = new Map<string, { header: StreamStartHeader; receivedChunks: number }>();

    const pairInfo = await daemon.handleIpcAction("daemon.pair", {});
    const parsedUri = parsePairingUri(pairInfo.pairingUri);
    const phoneIdentity = generateEd25519Identity("xiaomi");

    const ws = await connectSecurePhone(`ws://127.0.0.1:${port}/?tier=lan`, {
      phoneId: phoneIdentity.deviceId,
      macId: parsedUri.id,
      pskHex: parsedUri.ephemeralToken,
      onFrame(frame, phone) {
        if (frame.frameType === FrameType.STREAM_START) {
          const header = decodeJsonRpcPayload<StreamStartHeader>(frame.payload);
          incomingPushStreams.set(header.streamId, { header, receivedChunks: 0 });
          return;
        }
        if (frame.frameType === FrameType.STREAM_CHUNK) {
          const chunk = decodeStreamChunkPayload(frame.payload);
          const state = incomingPushStreams.get(chunk.streamId);
          if (state) {
            state.receivedChunks += 1;
            if (state.receivedChunks >= state.header.totalChunks) {
              phone.send(
                encodeStreamAckResumeFrame({
                  streamId: chunk.streamId,
                  status: "COMPLETED",
                  nextExpectedChunkIndex: state.header.totalChunks,
                  savedPath: `/sdcard/OneMoreStep/${state.header.fileName}`,
                })
              );
            }
          }
          return;
        }
        if (frame.frameType !== FrameType.CONTROL_JSON_RPC) return;

        const msg = decodeJsonRpcPayload<JsonRpcMessage>(frame.payload);
        if (!("method" in msg) || !msg.id) return;
        const params = (msg.params ?? {}) as Record<string, any>;

        switch (msg.method) {
          case "sys.getStatus":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: {
                  batteryPct: 92,
                  isCharging: true,
                  screenInteractive: true,
                  screenLocked: false,
                  foregroundPackage: "com.oms.client",
                  foregroundAppLabel: "青简",
                  lanIps: ["127.0.0.1"],
                  permissions: {},
                },
              })
            );
            break;

          case "storage.listRecentMedia":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: {
                  mediaType: params.mediaType,
                  items: [
                    {
                      remotePath: "content://media/external/images/media/9001",
                      fileName: "shot_01.png",
                      sizeBytes: 18,
                      takenAtMs: Date.now(),
                    },
                  ],
                },
              })
            );
            break;

          case "storage.pullFile": {
            const pullStreamId = generateUuidV7();
            const fileBytes = new TextEncoder().encode("PULLED_IMAGE_BYTES");
            const sha256 = crypto.createHash("sha256").update(fileBytes).digest("hex");
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: {
                  streamId: pullStreamId,
                  fileName: "pulled_shot.png",
                  totalBytes: fileBytes.byteLength,
                  sha256,
                },
              })
            );
            setTimeout(() => {
              phone.send(
                encodeStreamStartFrame({
                  streamId: pullStreamId,
                  fileName: "pulled_shot.png",
                  mimeType: "image/png",
                  totalBytes: fileBytes.byteLength,
                  chunkSize: 1024,
                  totalChunks: 1,
                  sha256,
                })
              );
              phone.send(encodeStreamChunkFrame(pullStreamId, 0, fileBytes));
            }, 10);
            break;
          }

          case "clipboard.get":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { ok: true, text: phoneClipboard },
              })
            );
            break;

          case "clipboard.set":
            phoneClipboard = String(params.text ?? "");
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { ok: true, length: phoneClipboard.length },
              })
            );
            break;

          case "alarm.set":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: {
                  ok: true,
                  hour: params.hour,
                  minute: params.minute,
                  label: params.label,
                },
              })
            );
            break;

          case "calendar.list":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { events: [{ eventId: 42, title: "架构评审", startMs: params.startMs }] },
              })
            );
            break;

          case "calendar.insert":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { ok: true, eventId: 108, title: params.title },
              })
            );
            break;

          case "calendar.delete":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { ok: true, deletedEventId: params.eventId },
              })
            );
            break;

          case "notify.send":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { ok: true, deliveredLevel: params.level, title: params.title },
              })
            );
            break;

          case "notify.requestApproval":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { received: true },
              })
            );
            setTimeout(() => {
              phone.send(
                encodeJsonRpcFrame({
                  jsonrpc: "2.0",
                  id: "apr_resp_all13",
                  method: "approval.respond",
                  params: {
                    approvalId: params.approvalId,
                    approved: true,
                    selectedOption: "批准继续",
                    userComment: "同意发布",
                    decidedAtMs: Date.now(),
                  },
                })
              );
            }, 20);
            break;

          case "sys.control":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: { ok: true, action: params.action, volumePct: params.volumePct ?? 50 },
              })
            );
            break;

          case "gui.observe":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: {
                  observationId: "obs_test_001",
                  packageName: "com.oms.client",
                  activityName: ".MainActivity",
                  isSecureWindow: false,
                  screenWidth: 1220,
                  screenHeight: 2712,
                  nodes: [
                    {
                      id: "#1",
                      role: "Button",
                      label: "发送",
                      clickable: true,
                      editable: false,
                      scrollable: false,
                      bounds: [100, 200, 300, 280],
                      center: [200, 240],
                    },
                  ],
                },
              })
            );
            break;

          case "gui.act":
            phone.send(
              encodeJsonRpcFrame({
                jsonrpc: "2.0",
                id: msg.id,
                result: {
                  ok: true,
                  action: params.action,
                  targetNodeId: params.targetNodeId,
                  observationId: params.observationId,
                },
              })
            );
            break;
        }
      },
    });

    let rpcId = 100;
    const callTool = async (name: (typeof EXPECTED_TOOL_NAMES)[number], args: Record<string, unknown> = {}) => {
      const resp = await mcp.handleRpcRequest({
        jsonrpc: "2.0",
        id: ++rpcId,
        method: "tools/call",
        params: { name, arguments: args },
      });
      expect(resp.result.isError).toBeUndefined();
      return JSON.parse(resp.result.content[0].text);
    };

    try {
      // Seed an inbox item from the phone
      ws.send(
        encodeJsonRpcFrame({
          jsonrpc: "2.0",
          id: "seed_inbox_1",
          method: "inbox.submitItem",
          params: {
            clientItemId: generateUuidV7(),
            intentTag: "default_inbox",
            title: "测试笔记",
            textContent: "来自手机的测试内容",
            createdAtMs: Date.now(),
          },
        })
      );
      await Bun.sleep(30);

      // 1. phone_inbox_latest
      const inboxRes = await callTool("phone_inbox_latest", {
        tag: "default_inbox",
        onlyUnprocessed: true,
        markAsProcessed: true,
      });
      expect(inboxRes.count).toBe(1);
      expect(inboxRes.items[0].processed).toBe(true);

      // 2. phone_media_recent
      const mediaRes = await callTool("phone_media_recent", { mediaType: "screenshot", limit: 5 });
      expect(mediaRes.items[0].fileName).toBe("shot_01.png");

      // 3. phone_file_pull
      const pullDir = path.join(tmpHome, "custom_pulls");
      const pullRes = await callTool("phone_file_pull", {
        sourceUriOrPath: "content://media/external/images/media/9001",
        saveToDir: pullDir,
      });
      expect(pullRes.ok).toBe(true);
      expect(fs.readFileSync(pullRes.localAbsPath, "utf8")).toBe("PULLED_IMAGE_BYTES");

      // 4. phone_file_push
      const pushRes = await callTool("phone_file_push", {
        title: "每日简报",
        markdownOrText: "# 今日进展\n全部通过",
        enableCommuteTts: true,
      });
      expect(pushRes.ok).toBe(true);
      expect(pushRes.bytesSent).toBeGreaterThan(0);

      // 5. phone_clipboard_sync
      const clipSetRes = await callTool("phone_clipboard_sync", {
        action: "set_phone_clipboard",
        textToSet: "oms-clipboard-2026",
      });
      expect(clipSetRes.ok).toBe(true);
      const clipGetRes = await callTool("phone_clipboard_sync", {
        action: "get_phone_clipboard",
      });
      expect(clipGetRes.text).toBe("oms-clipboard-2026");

      // 6. phone_alarm_set
      const alarmRes = await callTool("phone_alarm_set", {
        hour: 8,
        minute: 30,
        label: "晨会提醒",
        daysOfWeek: [1, 2, 3, 4, 5],
      });
      expect(alarmRes.ok).toBe(true);
      expect(alarmRes.hour).toBe(8);

      // 7. phone_calendar_manage
      const calListRes = await callTool("phone_calendar_manage", { action: "list_events" });
      expect(calListRes.events.length).toBe(1);
      const calAddRes = await callTool("phone_calendar_manage", {
        action: "add_event",
        title: "版本发布评审",
      });
      expect(calAddRes.eventId).toBe(108);
      const calDelRes = await callTool("phone_calendar_manage", {
        action: "delete_event",
        eventId: 108,
      });
      expect(calDelRes.deletedEventId).toBe(108);

      // 8. phone_notify
      const notifyRes = await callTool("phone_notify", {
        title: "构建完成",
        body: "所有测试已通过",
        level: "silent",
      });
      expect(notifyRes.ok).toBe(true);

      // 9. phone_request_approval
      const approvalRes = await callTool("phone_request_approval", {
        title: "确认发布",
        summary: "即将发布新版本",
        timeoutSec: 10,
      });
      expect(approvalRes.approved).toBe(true);
      expect(approvalRes.selectedOption).toBe("批准继续");

      // 10. phone_presence_status
      const statusRes = await callTool("phone_presence_status", { force: true });
      expect(statusRes.activeTransportTier).toBe("LAN_WS");
      expect(statusRes.phoneStatus.batteryPct).toBe(92);

      // 11. phone_device_control
      const ctrlRes = await callTool("phone_device_control", {
        action: "set_volume",
        stream: "music",
        volumePct: 45,
      });
      expect(ctrlRes.ok).toBe(true);
      expect(ctrlRes.volumePct).toBe(45);

      // 12. phone_gui_observe
      const guiObsRes = await callTool("phone_gui_observe", { includeScreenshot: false });
      expect(guiObsRes.observationId).toBe("obs_test_001");
      expect(guiObsRes.nodes[0].id).toBe("#1");

      // 13. phone_gui_action
      const guiActRes = await callTool("phone_gui_action", {
        action: "click",
        targetNodeId: "#1",
        observationId: guiObsRes.observationId,
      });
      expect(guiActRes.ok).toBe(true);
      expect(guiActRes.targetNodeId).toBe("#1");
    } finally {
      ws.close();
      await daemon.stop();
    }
  });
});
