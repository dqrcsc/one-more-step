import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import net from "node:net";
import crypto from "node:crypto";
import { MacHudServer } from "../src/hud/mac-hud-server.js";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";
import { generateUuidV7 } from "@oms/shared-protocol";

function makeTempWorkspace(prefix: string): { dir: string; cleanup: () => void } {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  const trackedFiles: string[] = [];
  const trackedDirs: string[] = [dir];
  return {
    dir,
    cleanup: () => {
      for (const f of trackedFiles) {
        try {
          if (fs.existsSync(f)) fs.unlinkSync(f);
        } catch {}
      }
      for (const d of trackedDirs.reverse()) {
        try {
          if (fs.existsSync(d)) fs.rmdirSync(d);
        } catch {}
      }
    },
  };
}

describe("MacHudServer & multi-file (PDF/Word/Album) delivery without external app popups", () => {
  test("MacHudServer exchanges notify, peek, request_sync, and mark_processed over Unix socket", async () => {
    const tmp = makeTempWorkspace("oms-hud-test-");
    const sockPath = path.join(tmp.dir, "hud.sock");
    const server = new MacHudServer({ socketPath: sockPath, autoSpawnBinary: false });
    await server.start();

    const received: any[] = [];
    let markedId = "";
    let syncRequested = false;

    server.on("markProcessed", (id: string) => {
      markedId = id;
    });
    server.on("requestSync", () => {
      syncRequested = true;
    });

    const client = await new Promise<net.Socket>((resolve, reject) => {
      const c = net.createConnection(sockPath, () => resolve(c));
      c.once("error", reject);
    });
    client.setEncoding("utf8");
    let buf = "";
    client.on("data", (chunk: string) => {
      buf += chunk;
      let idx: number;
      while ((idx = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (line) received.push(JSON.parse(line));
      }
    });

    // Wait briefly for server connection registration
    await new Promise((r) => setTimeout(r, 40));
    expect(server.isConnected()).toBe(true);

    const itemId = generateUuidV7();
    const okNotify = server.notify(
      {
        title: "📱 收到小米手机投递",
        message: "年度合同终版.docx 等 2 个文件",
        itemId,
      },
      {
        status: { activeTier: "LAN_WS", inboxDir: tmp.dir },
      }
    );
    expect(okNotify).toBe(true);

    const okPeek = server.peek(itemId);
    expect(okPeek).toBe(true);

    client.write(JSON.stringify({ type: "mark_processed", itemId }) + "\n");
    client.write(JSON.stringify({ type: "request_sync" }) + "\n");

    await new Promise((r) => setTimeout(r, 60));

    expect(received.some((m) => m.type === "notify" && m.toast?.itemId === itemId)).toBe(true);
    expect(received.some((m) => m.type === "peek" && m.itemId === itemId)).toBe(true);
    expect(markedId).toBe(itemId);
    expect(syncRequested).toBe(true);

    client.destroy();
    await server.stop();
    tmp.cleanup();
  });

  test("OmsDaemon routes incoming image + Word + PDF multi-attachments to HUD without opening Preview.app", async () => {
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    const homeDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-daemon-hud-"));
    const daemon = new OmsDaemon(homeDir);

    const hudSock = path.join(homeDir, "run", "hud.sock");
    const hudServer = new MacHudServer({ socketPath: hudSock, autoSpawnBinary: false });
    await hudServer.start();
    (daemon as any).hudServer = hudServer;
    hudServer.on("markProcessed", (id: string) => {
      daemon.inbox.markProcessed(id, true);
    });

    let externalPreviewOpened = 0;
    const origOpen = daemon.openFileOnMac.bind(daemon);
    daemon.openFileOnMac = (absPath: string, reveal = false) => {
      externalPreviewOpened++;
      return origOpen(absPath, reveal);
    };

    const hudEnvelopes: any[] = [];
    const client = await new Promise<net.Socket>((resolve, reject) => {
      const c = net.createConnection(hudSock, () => resolve(c));
      c.once("error", reject);
    });
    client.setEncoding("utf8");
    let buf = "";
    client.on("data", (chunk: string) => {
      buf += chunk;
      let idx: number;
      while ((idx = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (line) hudEnvelopes.push(JSON.parse(line));
      }
    });
    await new Promise((r) => setTimeout(r, 40));

    // Stage 3 files (Album Image + Word .docx + PDF) into completed stream map
    const cacheDir = path.join(homeDir, "inbox", "pulled_cache");
    const mkStream = (streamId: string, fileName: string, mimeType: string, content: Buffer) => {
      const p = path.join(cacheDir, `${streamId}_${fileName}`);
      fs.writeFileSync(p, content);
      const sha256 = crypto.createHash("sha256").update(content).digest("hex");
      (daemon.session as any).completedStreams.set(streamId, {
        header: { streamId, fileName, mimeType, totalBytes: content.length, sha256 },
        savedAbsPath: p,
        sha256Actual: sha256,
      });
      return { streamId, fileName, mimeType, totalBytes: content.length, sha256 };
    };

    const attImg = mkStream("s_img", "相册照片_01.jpg", "image/jpeg", Buffer.from("fake-jpeg-bytes"));
    const attDocx = mkStream(
      "s_docx",
      "项目方案_v2.docx",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      Buffer.from("fake-docx-bytes")
    );
    const attPdf = mkStream("s_pdf", "报价单_2026.pdf", "application/pdf", Buffer.from("%PDF-1.7 fake"));

    const clientItemId = generateUuidV7();
    let rpcResult: any = null;
    (daemon.session as any).emit(
      "phoneRequest",
      {
        jsonrpc: "2.0",
        id: "req_1",
        method: "inbox.submitItem",
        params: {
          clientItemId,
          intentTag: "pkb_vault",
          title: "📘 项目方案_v2.docx 等 3 个附件",
          textContent: "请帮我归档这份 Word 和 PDF",
          createdAtMs: Date.now(),
          attachments: [attImg, attDocx, attPdf],
        },
      },
      (res: any) => {
        rpcResult = res;
      }
    );

    await new Promise((r) => setTimeout(r, 60));

    expect(rpcResult?.result?.ack).toBe(true);
    expect(externalPreviewOpened).toBe(0); // Must NOT launch Preview.app when native HUD is active!

    const stored = daemon.inbox.getItem(clientItemId);
    expect(stored).toBeDefined();
    expect(stored!.files.map((f) => f.fileName)).toEqual([
      "相册照片_01.jpg",
      "项目方案_v2.docx",
      "报价单_2026.pdf",
    ]);

    const notifyEnv = hudEnvelopes.find((e) => e.type === "notify");
    expect(notifyEnv).toBeDefined();
    expect(notifyEnv.item?.itemId).toBe(clientItemId);
    expect(notifyEnv.item?.files?.length).toBe(3);

    const peekRes = daemon.peekInboxInHud(clientItemId);
    expect(peekRes.ok).toBe(true);
    await new Promise((r) => setTimeout(r, 40));
    expect(hudEnvelopes.some((e) => e.type === "peek" && e.itemId === clientItemId)).toBe(true);

    client.destroy();
    await hudServer.stop();
    await daemon.stop();
  });
});
