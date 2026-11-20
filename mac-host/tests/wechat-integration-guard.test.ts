import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { handleDashboardHttpRequest } from "../src/inbox/inbox-web-dashboard.js";
import { WeChatBridgeSupervisor, type ManagedWeChatEvent } from "../src/wechat/wechat-supervisor.js";
import {
  renderWeChatMarkdownReaderHtml,
  resolveMarkdownRelativeAsset,
} from "../../wechat-context/src/reader_renderer.js";

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

  test("renders rich WeChat reader HTML, resolves inline local raster assets, and blocks path traversal / SVG XSS", () => {
    const tmpVault = fs.mkdtempSync(path.join(os.tmpdir(), "oms-wx-reader-"));
    const wxDir = path.join(tmpVault, "wechat");
    const assetsDir = path.join(wxDir, "assets", "2026_11_19_demo");
    fs.mkdirSync(assetsDir, { recursive: true });

    const sourceMd = path.join(wxDir, "2026_11_19_demo.md");
    const imgWebp = path.join(assetsDir, "img_01_demo.webp");
    const evilSvg = path.join(assetsDir, "evil.svg");
    const outsideSecret = path.join(tmpVault, "outside.png");
    const escapedSymlink = path.join(assetsDir, "symlink_out.png");

    fs.writeFileSync(imgWebp, Buffer.from("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA", "base64"));
    fs.writeFileSync(evilSvg, "<svg onload=alert(1)></svg>", "utf8");
    fs.writeFileSync(outsideSecret, "secret", "utf8");
    fs.symlinkSync(outsideSecret, escapedSymlink);

    const sampleMarkdown = [
      "---",
      "type: source",
      'title: "淘宝闪购本体平台"',
      "---",
      "",
      "# 淘宝闪购本体平台",
      "",
      "- **公众号 / 作者**：阿里技术",
      "- **发布时间**：2026-11-18",
      "- **捕获时间**：2026-11-19",
      "- **原始链接**：[原文链接](https://mp.weixin.qq.com/s?__biz=demo&mid=123456)",
      "- **转发时批注**：[链接] 淘宝闪购本体平台",
      "",
      "---",
      "",
      "![image](assets/2026_11_19_demo/img_01_demo.webp)",
      "",
      "业务负责人脑子里记的却是另一种；**写下来的总是少数，多数口径还得靠人记。 **",
      "",
      "<script>alert('xss')</script>",
      "",
      "01",
      "",
      "## 平台架构",
      "",
      "| 层级 | 职责 |",
      "| --- | --- |",
      "| 元模型 | 统一语义 |",
    ].join("\n");
    fs.writeFileSync(sourceMd, sampleMarkdown, "utf8");

    // Valid relative raster image resolves cleanly
    const resolvedOk = resolveMarkdownRelativeAsset(sourceMd, "assets/2026_11_19_demo/img_01_demo.webp");
    expect(resolvedOk).not.toBeNull();
    expect(resolvedOk?.mimeType).toBe("image/webp");

    // Traversal, absolute path, active SVG, and symlink escape must all be rejected
    expect(resolveMarkdownRelativeAsset(sourceMd, "../outside.png")).toBeNull();
    expect(resolveMarkdownRelativeAsset(sourceMd, "/etc/passwd")).toBeNull();
    expect(resolveMarkdownRelativeAsset(sourceMd, "assets/2026_11_19_demo/evil.svg")).toBeNull();
    expect(resolveMarkdownRelativeAsset(sourceMd, "assets/2026_11_19_demo/symlink_out.png")).toBeNull();

    const rendered = renderWeChatMarkdownReaderHtml({
      markdown: sampleMarkdown,
      docPath: sourceMd,
      articleId: 161,
      docKind: "source",
    });

    expect(rendered.html).not.toContain("type: source");
    expect(rendered.html).toContain("查看微信原网页");
    expect(rendered.html).toContain("/api/wechat/asset?articleId=161&amp;doc=source&amp;path=assets%2F2026_11_19_demo%2Fimg_01_demo.webp");
    expect(rendered.html).toContain("<strong>写下来的总是少数，多数口径还得靠人记。</strong>");
    expect(rendered.html).toContain("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
    expect(rendered.html).not.toContain("<script>alert");
    expect(rendered.html).toContain('<div class="wx-sec-num">01</div>');
    expect(rendered.html).toContain('<table class="wx-table">');
    expect(rendered.resolvedImages["source:assets/2026_11_19_demo/img_01_demo.webp"]).toBe(
      fs.realpathSync(imgWebp)
    );
  });
});
