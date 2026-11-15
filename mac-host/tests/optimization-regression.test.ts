import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { extractOtpFromText, generateUuidV7, type InboxSubmitItemParams } from "@oms/shared-protocol";
import { InboxManager } from "../src/inbox/inbox-manager.js";
import { handleDashboardHttpRequest } from "../src/inbox/inbox-web-dashboard.js";
import { ipcTimeoutForAction } from "../src/daemon/request-timeouts.js";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { PresenceEngine } from "../src/transport/presence-engine.js";
import { OmsMcpServer } from "../src/mcp/server.js";
import type { CompletedFileTransfer } from "../src/transport/session-manager.js";

// Fixtures are retained: the workspace forbids recursive file deletion.
function createInboxFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-opt-regression-"));
  const root = path.join(dir, "inbox");
  return { dir, root, inbox: new InboxManager(root) };
}

function makeAttachment(
  dir: string,
  fileName: string,
  mimeType: string,
  content: string | Uint8Array
): { transfer: CompletedFileTransfer; attachment: NonNullable<InboxSubmitItemParams["attachments"]>[number] } {
  const bytes = typeof content === "string" ? Buffer.from(content) : Buffer.from(content);
  const streamId = generateUuidV7();
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  const savedAbsPath = path.join(dir, streamId);
  fs.writeFileSync(savedAbsPath, bytes);
  return {
    transfer: {
      header: {
        streamId,
        fileName,
        mimeType,
        totalBytes: bytes.length,
        chunkSize: Math.max(1, bytes.length),
        totalChunks: 1,
        sha256,
      },
      savedAbsPath,
      sha256Actual: sha256,
    },
    attachment: {
      streamId,
      fileName,
      mimeType,
      totalBytes: bytes.length,
      sha256,
    },
  };
}

describe("P0 security regressions: /api/inbox/file XSS defense & Mac preview allowlist", () => {
  test("forces attachment download + sandbox CSP + octet-stream for HTML/SVG/JS while keeping raster images inline", async () => {
    const { dir, inbox } = createInboxFixture();
    const htmlAtt = makeAttachment(dir, "payload.html", "text/html", "<script>alert(1)</script>");
    const svgAtt = makeAttachment(dir, "vector.svg", "image/svg+xml", "<svg onload='alert(1)'/>");
    const spoofAtt = makeAttachment(dir, "spoof.html", "image/png", "<script>alert(2)</script>");
    const pngAtt = makeAttachment(dir, "screen.png", "image/png", new Uint8Array([137, 80, 78, 71]));

    const stored = inbox.storeSubmittedItem(
      {
        clientItemId: generateUuidV7(),
        intentTag: "default_inbox",
        createdAtMs: Date.now(),
        attachments: [htmlAtt.attachment, svgAtt.attachment, spoofAtt.attachment, pngAtt.attachment],
      },
      (id) =>
        [htmlAtt, svgAtt, spoofAtt, pngAtt].find((x) => x.attachment.streamId === id)?.transfer
    );

    const daemon: any = { inbox };

    for (const unsafeName of ["payload.html", "vector.svg", "spoof.html"]) {
      const req = new Request(
        `http://127.0.0.1:19527/api/inbox/file?itemId=${stored.itemId}&file=${encodeURIComponent(unsafeName)}`
      );
      const res = (await handleDashboardHttpRequest(daemon, req, new URL(req.url), "127.0.0.1"))!;
      expect(res.status).toBe(200);
      expect(res.headers.get("Content-Type")).toBe("application/octet-stream");
      expect(res.headers.get("Content-Disposition")).toContain("attachment;");
      expect(res.headers.get("Content-Security-Policy")).toContain("sandbox;");
      expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
      expect(res.headers.get("Cross-Origin-Resource-Policy")).toBe("same-origin");
    }

    const pngReq = new Request(
      `http://127.0.0.1:19527/api/inbox/file?itemId=${stored.itemId}&file=screen.png`
    );
    const pngRes = (await handleDashboardHttpRequest(daemon, pngReq, new URL(pngReq.url), "127.0.0.1"))!;
    expect(pngRes.status).toBe(200);
    expect(pngRes.headers.get("Content-Type")).toBe("image/png");
    expect(pngRes.headers.get("Content-Disposition")).toContain("inline;");
    expect(pngRes.headers.get("Content-Security-Policy")).toContain("sandbox;");
    expect(pngRes.headers.get("X-Content-Type-Options")).toBe("nosniff");
  });

  test("OmsDaemon.openFileOnMac downgrades executable/bundle/unknown extensions to Finder reveal (-R)", () => {
    const prevDisable = process.env.OMS_DISABLE_OSASCRIPT;
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    try {
      const daemon = Object.create(OmsDaemon.prototype) as OmsDaemon;
      for (const unsafePath of [
        "/tmp/Evil.command",
        "/tmp/Installer.pkg",
        "/tmp/Fake.app",
        "/tmp/Fake.app/Contents/MacOS/run.png",
        "/tmp/script.sh",
        "/tmp/page.html",
      ]) {
        expect(OmsDaemon.isSafeForDirectMacPreview(unsafePath)).toBe(false);
        const res = daemon.openFileOnMac(unsafePath, false);
        expect(res.downgradedToReveal).toBe(true);
        expect(res.args).toEqual(["-R", unsafePath]);
      }

      for (const safePath of ["/tmp/photo.png", "/tmp/doc.pdf", "/tmp/voice.m4a", "/tmp/note.md"]) {
        expect(OmsDaemon.isSafeForDirectMacPreview(safePath)).toBe(true);
        const res = daemon.openFileOnMac(safePath, false);
        expect(res.downgradedToReveal).toBe(false);
        expect(res.args).toEqual([safePath]);
      }
    } finally {
      if (prevDisable === undefined) delete process.env.OMS_DISABLE_OSASCRIPT;
      else process.env.OMS_DISABLE_OSASCRIPT = prevDisable;
    }
  });
});

describe("P1 contract & audio routing regressions", () => {
  test("ipcTimeoutForAction grants 95s budget for macSay, aiCall, say, and waitForAnswer", () => {
    expect(ipcTimeoutForAction("phone_device_control", { action: "call_phone", number: "10086" })).toBe(45_000);
    expect(ipcTimeoutForAction("phone_device_control", { action: "call_phone", say: "你好" })).toBe(95_000);
    expect(ipcTimeoutForAction("phone_device_control", { action: "call_phone", macSay: "你好" })).toBe(95_000);
    expect(ipcTimeoutForAction("phone_device_control", { action: "call_phone", aiCall: "你好" })).toBe(95_000);
    expect(ipcTimeoutForAction("phone_device_control", { action: "call", waitForAnswer: true })).toBe(95_000);
  });

  test("OmsDaemon.resolveSpeechAudioPolicy implements the 3-tier routing policy", () => {
    // Default say / normal notify -> alarm_respect_headset
    expect(OmsDaemon.resolveSpeechAudioPolicy({}, "alarm_respect_headset")).toBe("alarm_respect_headset");
    // Urgent notify / find_phone -> force_speaker_alarm
    expect(OmsDaemon.resolveSpeechAudioPolicy({}, "force_speaker_alarm")).toBe("force_speaker_alarm");
    // Explicit --force-speaker upgrades alarm_respect_headset -> force_speaker_alarm
    expect(
      OmsDaemon.resolveSpeechAudioPolicy({ forceSpeakerAlarmStream: true }, "alarm_respect_headset")
    ).toBe("force_speaker_alarm");
    // Explicit --no-alarm downgrades to media_commute
    expect(
      OmsDaemon.resolveSpeechAudioPolicy({ useAlarmStream: false }, "alarm_respect_headset")
    ).toBe("media_commute");
    expect(
      OmsDaemon.resolveSpeechAudioPolicy({ audioPolicy: "media_commute" }, "force_speaker_alarm")
    ).toBe("media_commute");
  });

  test("macSay preflight aborts before dialing when Mac speaker is not built-in, and falls back to system say when Neural TTS is offline", async () => {
    const daemon = Object.create(OmsDaemon.prototype) as any;
    const dialed: any[] = [];
    let speakerOk = false;
    let fallbackSpoken: string[] = [];

    const assertCurrent = () => {};
    const boundCall = Object.assign(
      async (method: string, params: any) => {
        dialed.push({ method, params });
        return { ok: true, callAnswered: true };
      },
      { assertCurrent }
    );

    Object.assign(daemon, {
      config: {},
      phoneEmergencyStopEpoch: 0,
      synthesizeNeuralTts: async (_text: string, opts?: any) => {
        opts?.onFallback?.("edge_tts_offline");
        return null;
      },
      playFallbackSayOnMacSpeaker: async (text: string) => {
        fallbackSpoken.push(text);
        return true;
      },
      session: {
        bindPhoneSession: () => boundCall,
        setPhoneStatus: () => {},
        getBluetoothBridge: () => ({
          queryAudioOutput: async () =>
            speakerOk
              ? { ok: true, builtInSpeaker: true, uid: "BuiltInSpeakerDevice" }
              : { ok: true, builtInSpeaker: false, uid: "AirPods" },
        }),
      },
    });

    // 1. Fails fast before placing phone call when AirPods are Mac output
    await expect(
      daemon.handleIpcAction("phone_device_control", {
        action: "call",
        number: "10086",
        macSay: "测试外放",
        postAnswerDelayMs: 150,
        deadlineMs: Date.now() + 3000,
      })
    ).rejects.toThrow("ERR_MAC_SPEAKER_ROUTE");
    expect(dialed).toHaveLength(0);

    // 2. When Mac built-in speaker is active and Edge TTS is offline, dials and speaks via system say fallback
    speakerOk = true;
    const res = await daemon.handleIpcAction("phone_device_control", {
      action: "call",
      number: "10086",
      macSay: "测试离线回退",
      postAnswerDelayMs: 150,
      deadlineMs: Date.now() + 3000,
    });
    expect(dialed).toHaveLength(1);
    expect(fallbackSpoken).toEqual(["测试离线回退"]);
    expect(res.spoken).toBe(true);
    expect(res.ttsEngine).toBe("mac_speaker_system_say");
    expect(res.ttsFallbackReason).toBe("edge_tts_offline");
  });

  test("PresenceEngine transitions LAN-only AT_DESK back to AWAY on sustained high RTT or stale LAN heartbeat", () => {
    const engine = new PresenceEngine({ leaveHoldMs: 5000 });
    engine.onLanDirectActive(5, 1000);
    expect(engine.getSnapshot().state).toBe("AT_DESK");

    // Sustained high RTT (e.g. Tailscale/VPN roaming) transitions AT_DESK -> AWAY after leaveHoldMs
    engine.onLanDirectActive(120, 2000);
    expect(engine.getSnapshot().state).toBe("AT_DESK");
    engine.onLanDirectActive(120, 7500);
    expect(engine.getSnapshot().state).toBe("AWAY");

    // Low RTT restores AT_DESK, then stale LAN heartbeat (>=25s) transitions back to AWAY
    engine.onLanDirectActive(6, 10_000);
    expect(engine.getSnapshot().state).toBe("AT_DESK");
    expect(engine.evaluateTimeouts(36_000)).toBe("AWAY");
  });

  test("shared OTP extractor treats generic MIUI/验证码 titles as non-sender titles", () => {
    const hit = extractOtpFromText("【招商银行】您的验证码为 482910，5分钟内有效。", "验证码");
    expect(hit?.code).toBe("482910");
    expect(hit?.sender).toBe("招商银行");

    const miuiHit = extractOtpFromText("登录验证码：839201，请勿泄露", "com.miui.notification");
    expect(miuiHit?.code).toBe("839201");
    expect(miuiHit?.sender).toBeUndefined();
  });

  test("MCP server reports package.json version and merges pkb_vault + voice_idea in oms://inbox/pkb_pending", async () => {
    const mcp = new OmsMcpServer() as any;
    const calls: any[] = [];
    mcp.ipc = {
      call: async (action: string, params: any) => {
        calls.push({ action, params });
        if (params.tag === "pkb_vault") {
          return { items: [{ itemId: "pkb-1", intentTag: "pkb_vault", createdAtMs: 100 }] };
        }
        if (params.tag === "voice_idea") {
          return { items: [{ itemId: "voice-1", intentTag: "voice_idea", createdAtMs: 200 }] };
        }
        return { items: [] };
      },
    };

    const pkgJson = JSON.parse(fs.readFileSync(new URL("../package.json", import.meta.url), "utf8"));
    const initRes = await mcp.handleRpcRequest({ jsonrpc: "2.0", id: 1, method: "initialize" });
    expect(initRes.result.serverInfo.version).toBe(pkgJson.version);

    const pkbRes = await mcp.handleRpcRequest({
      jsonrpc: "2.0",
      id: 2,
      method: "resources/read",
      params: { uri: "oms://inbox/pkb_pending" },
    });
    const parsed = JSON.parse(pkbRes.result.contents[0].text);
    expect(parsed.items.map((i: any) => i.itemId)).toEqual(["voice-1", "pkb-1"]);
  });

  test("InboxManager removes committed pulled_cache staging files while preserving external files", () => {
    const { dir, inbox } = createInboxFixture();
    const pulledCacheDir = path.join(inbox.inboxRootDir, "pulled_cache");
    const stagedInPulledCache = makeAttachment(pulledCacheDir, "staged.png", "image/png", "staged-bytes");
    const externalFile = makeAttachment(dir, "external.png", "image/png", "external-bytes");

    const stored = inbox.storeSubmittedItem(
      {
        clientItemId: generateUuidV7(),
        intentTag: "default_inbox",
        createdAtMs: Date.now(),
        attachments: [stagedInPulledCache.attachment, externalFile.attachment],
      },
      (id) =>
        id === stagedInPulledCache.attachment.streamId
          ? stagedInPulledCache.transfer
          : externalFile.transfer
    );

    expect(fs.existsSync(stored.files[0].absPath)).toBe(true);
    expect(fs.existsSync(stagedInPulledCache.transfer.savedAbsPath)).toBe(false);
    expect(fs.existsSync(stored.files[1].absPath)).toBe(true);
    expect(fs.existsSync(externalFile.transfer.savedAbsPath)).toBe(true);
  });
});
