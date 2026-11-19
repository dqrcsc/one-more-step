import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { handleDashboardHttpRequest } from "../src/inbox/inbox-web-dashboard.js";
import { WeChatBridgeSupervisor, type ManagedWeChatEvent } from "../src/wechat/wechat-supervisor.js";

describe("Unified WeChat-as-Context (青简·尺素) Loopback Guard & Supervisor Integration", () => {
  test("strictly blocks non-loopback LAN clients from all /wechat and /api/wechat routes", async () => {
    const protectedPaths = [
      "/wechat/config",
      "/config",
      "/api/wechat/overview",
      "/api/wechat/article?id=1",
      "/api/wechat/to-phone",
      "/api/config",
      "/api/push",
      "/api/ingest",
      "/api/schedules",
    ];
    for (const p of protectedPaths) {
      const url = new URL(`http://192.168.31.44:19527${p}`);
      const req = new Request(url.toString(), {
        headers: { Host: "192.168.31.44:19527" },
      });
      const res = await handleDashboardHttpRequest({} as any, req, url, "192.168.31.88");
      expect(res).not.toBeNull();
      expect(res!.status).toBe(403);
    }
  });

  test("serves unified WeChat Context tab in /inbox and proxies loopback /api/wechat/* to worker", async () => {
    const upstream = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch(req) {
        const u = new URL(req.url);
        if (u.pathname === "/api/overview") {
          return Response.json({
            ok: true,
            articles: [{ id: 42, title: "青简合一架构演进", status: "light_done", triage: "deep" }],
          });
        }
        if (u.pathname === "/config") {
          return new Response("<html><body>尺素配置中心</body></html>", {
            headers: { "Content-Type": "text/html; charset=utf-8" },
          });
        }
        return new Response("Not found", { status: 404 });
      },
    });

    try {
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-wx-sup-"));
      const sup = new WeChatBridgeSupervisor({
        omsHomeDir: tmpDir,
        serverEntryPath: "/nonexistent/server.ts",
        internalPort: upstream.port,
        logFilePath: path.join(tmpDir, "wechat.log"),
      });
      // Simulate live worker state for proxy test
      (sup as any).child = { pid: 12345, exitCode: null, killed: false };

      const mockDaemon = { wechat: sup } as any;

      const dashUrl = new URL("http://127.0.0.1:19527/inbox?module=wechat");
      const dashRes = await handleDashboardHttpRequest(
        mockDaemon,
        new Request(dashUrl.toString(), { headers: { Host: "127.0.0.1:19527" } }),
        dashUrl,
        "127.0.0.1"
      );
      expect(dashRes?.status).toBe(200);
      const dashHtml = await dashRes!.text();
      expect(dashHtml).toContain("微信尺素 (文章 · 速记 · 定时任务)");
      expect(dashHtml).toContain("推送到手机微信");

      const apiUrl = new URL("http://127.0.0.1:19527/api/wechat/overview?limit=10");
      const apiRes = await handleDashboardHttpRequest(
        mockDaemon,
        new Request(apiUrl.toString(), { headers: { Host: "127.0.0.1:19527" } }),
        apiUrl,
        "127.0.0.1"
      );
      expect(apiRes?.status).toBe(200);
      const apiJson = (await apiRes!.json()) as any;
      expect(apiJson.ok).toBe(true);
      expect(apiJson.articles[0].id).toBe(42);

      const cfgUrl = new URL("http://127.0.0.1:19527/wechat/config");
      const cfgRes = await handleDashboardHttpRequest(
        mockDaemon,
        new Request(cfgUrl.toString(), { headers: { Host: "127.0.0.1:19527" } }),
        cfgUrl,
        "127.0.0.1"
      );
      expect(cfgRes?.status).toBe(200);
      expect(cfgRes?.headers.get("X-Frame-Options")).toBe("SAMEORIGIN");
      expect(await cfgRes!.text()).toContain("尺素配置中心");
    } finally {
      upstream.stop(true);
    }
  });

  test("WeChatBridgeSupervisor parses __OMS_WX_EVENT__ IPC lines cleanly", async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-wx-evt-"));
    const mockWorker = path.join(tmpDir, "mock-worker.ts");
    fs.writeFileSync(
      mockWorker,
      `console.log("normal boot log");
process.stdout.write("__OMS_WX_EVENT__:" + JSON.stringify({
  eventType: "article",
  articleId: 159,
  title: "测试文章初筛完成",
  summary: "核心干货提炼",
  timestamp: "2026-11-19T06:00:00.000Z"
}) + "\\n");
setTimeout(() => {}, 5000);
`,
      "utf8"
    );

    const logPath = path.join(tmpDir, "logs", "wechat.log");
    const sup = new WeChatBridgeSupervisor({
      omsHomeDir: tmpDir,
      serverEntryPath: mockWorker,
      internalPort: 29528,
      logFilePath: logPath,
    });

    const received = new Promise<ManagedWeChatEvent>((resolve) => {
      sup.once("wechatEvent", resolve);
    });

    sup.start();
    try {
      const evt = await received;
      expect(evt.eventType).toBe("article");
      expect(evt.articleId).toBe(159);
      expect(evt.title).toBe("测试文章初筛完成");
      const logContent = fs.readFileSync(logPath, "utf8");
      expect(logContent).toContain("normal boot log");
      expect(logContent).not.toContain("__OMS_WX_EVENT__");
    } finally {
      await sup.stop();
    }
  });
});
