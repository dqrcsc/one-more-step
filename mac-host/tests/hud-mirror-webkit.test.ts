import { expect, test } from 'bun:test';
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { handleDashboardHttpRequest } from '../src/inbox/inbox-web-dashboard.js';

test.skipIf(process.platform !== 'darwin')('real WKWebView renders a synthetic frame, ACKs input and releases the HUD viewer', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'oms-hud-webkit-'));
  const source = fs.readFileSync(path.resolve(import.meta.dir, '../mac-hud/main.swift'), 'utf8');
  const swift = path.join(dir, 'main.swift'), binary = path.join(dir, 'webkit-test');
  const fixture = String.raw`
func pump(_ seconds: Double) {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
}
func js(_ web: WKWebView, _ expression: String) -> Any? {
    var completed = false
    var result: Any?
    web.evaluateJavaScript(expression) { value, error in
        if error == nil { result = value }
        completed = true
    }
    let deadline = Date().addingTimeInterval(3)
    while !completed && Date() < deadline { pump(0.01) }
    return result
}
func require(_ condition: Bool, _ message: String) {
    if !condition { print("FAILED: " + message); exit(1) }
}
extension OmsMacHudAppDelegate {
    func verifyMirror() {
        buildDetailPanelWindow()
        // WebKit needs an ordered window for AppKit's full-size content layout.
        // Keep the synthetic window transparent and never activate the app.
        detailPanel?.alphaValue = 0
        detailPanel?.setFrameOrigin(NSPoint(x: -10000, y: -10000))
        detailPanel?.orderBack(nil)
        detailPanel?.layoutIfNeeded()
        statusSnapshot.mirrorUrl = CommandLine.arguments[1]
        selectDetailTab(1)
        guard let web = mirrorWebView else { require(false, "WebView missing"); return }
        let visible = detailPanel!.screen!.visibleFrame
        require(detailPanel!.frame.height == visible.height && detailPanel!.frame.minY == visible.minY, "Mirror did not open at usable maximum height")
        require(detailPanel!.frame.width == 840, "Height fit changed window width")
        require(pinButton?.isHidden == true, "Inbox-only pin visible in mirror")
        require(!web.configuration.websiteDataStore.isPersistent, "Persistent WebKit store")
        let end = Date().addingTimeInterval(10)
        while Date() < end {
            if js(web, "typeof window.hostDeactivate") as? String == "function" { break }
            pump(0.1)
        }
        require(js(web, "active") as? Bool == false, "Page auto-started or did not load")
        require(js(web, "document.getElementById('mirrorTextInput').disabled") as? Bool == true, "Input initially enabled")
        _ = js(web, "toggleStartStop()")
        let frameEnd = Date().addingTimeInterval(8)
        while Date() < frameEnd {
            if js(web, "canControl() && canvas.width === 1") as? Bool == true { break }
            pump(0.1)
        }
        require(js(web, "canControl() && canvas.width === 1") as? Bool == true, "WebP frame was not rendered")
        // Calling JS handlers exercises the actual renderer without touching a real phone.
        _ = js(web, "document.getElementById('mirrorTextInput').value = 'fixture'; void submitMirrorText(false)")
        pump(0.3)
        require(js(web, "document.getElementById('mirrorTextInput').value") as? String == "", "ACK did not clear text")
        detailPanel?.setContentSize(NSSize(width: 680, height: 440))
        detailPanel?.layoutIfNeeded()
        detailPanel?.contentView?.layoutSubtreeIfNeeded()
        _ = js(web, "shell.style.aspectRatio = '9 / 20'; shell.style.setProperty('--phone-ratio', '0.45')")
        pump(0.1)
        require(js(web, "(() => { const r = shell.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.right <= innerWidth && r.bottom <= document.querySelector('.bottombar').getBoundingClientRect().top + 1 && document.querySelector('.bottombar').getBoundingClientRect().bottom <= innerHeight; })()") as? Bool == true, "Minimum window clips mirror or controls: " + String(describing: js(web, "JSON.stringify({w:innerWidth,h:innerHeight,shell:shell.getBoundingClientRect(),bar:document.querySelector('.bottombar').getBoundingClientRect()})")))
        for size in [NSSize(width: 680, height: 440), NSSize(width: 840, height: visible.height)] {
            detailPanel?.setContentSize(size)
            detailPanel?.layoutIfNeeded()
            for ratio in ["9 / 20", "20 / 9"] {
                _ = js(web, "shell.style.aspectRatio = '\(ratio)'; shell.style.setProperty('--phone-ratio', String(\(ratio == "9 / 20" ? 0.45 : 20.0 / 9.0))); canvas.width = \(ratio == "9 / 20" ? 900 : 2000); canvas.height = \(ratio == "9 / 20" ? 2000 : 900)")
                pump(0.1)
                require(js(web, "(() => { const c = canvas.getBoundingClientRect(), d = document.querySelector('.side-dock').getBoundingClientRect(); return c.left >= 0 && c.right <= innerWidth && d.right <= innerWidth && Math.abs(c.width / c.height - canvas.width / canvas.height) < 0.005; })()") as? Bool == true, "Aspect or viewport mismatch: " + String(describing: js(web, "JSON.stringify({w:innerWidth,h:innerHeight,canvas:canvas.getBoundingClientRect(),shell:shell.getBoundingClientRect(),box:getComputedStyle(shell).boxSizing,ratio:getComputedStyle(shell).aspectRatio,cssHeight:getComputedStyle(shell).height,cssWidth:getComputedStyle(shell).width,dock:document.querySelector('.side-dock').getBoundingClientRect()})")))
            }
        }
        detailPanel?.setContentSize(NSSize(width: 680, height: 440))
        detailPanel?.layoutIfNeeded()
        _ = js(web, "document.getElementById('appBadge').textContent = 'com.example.' + 'longpackage'.repeat(12); document.getElementById('resultText').textContent = 'DiagnosticMessage'.repeat(30)")
        pump(0.1)
        require(js(web, "(() => { const b = document.querySelector('.bottombar').getBoundingClientRect(), c = canvas.getBoundingClientRect(); return document.body.scrollWidth <= innerWidth && b.bottom <= innerHeight && c.width > 0 && c.height > 0; })()") as? Bool == true, "Long status clips controls or canvas")
        windowDidResignKey(Notification(name: NSWindow.didResignKeyNotification, object: detailPanel))
        require(mirrorWebView === web, "Mirror was destroyed on focus loss")
        selectDetailTab(0)
        require(pinButton?.isHidden == false, "Inbox pin not restored")
        let unloadEnd = Date().addingTimeInterval(3)
        while Date() < unloadEnd {
            if js(web, "typeof window.hostDeactivate") as? String != "function" { break }
            pump(0.05)
        }
        require(mirrorWebView == nil, "WebView retained after switching tabs")
        require(js(web, "typeof window.hostDeactivate") as? String != "function", "Old page was not unloaded")
        selectDetailTab(1)
        require(detailPanel!.frame.height == visible.height, "Re-enter did not restore maximum height")
        guard let second = mirrorWebView else { require(false, "Reopen missing"); return }
        pump(0.5)
        require(js(second, "active") as? Bool == false, "Reopen started capture")
        _ = js(second, "location.href = 'https://example.invalid/'")
        pump(0.2)
        require(second.url?.host == "127.0.0.1", "External navigation escaped")
        windowDidMiniaturize(Notification(name: NSWindow.didMiniaturizeNotification, object: detailPanel))
        require(mirrorWebView == nil, "Minimized view retained viewer")
        windowDidDeminiaturize(Notification(name: NSWindow.didDeminiaturizeNotification, object: detailPanel))
        guard let third = mirrorWebView else { require(false, "Reload missing"); return }
        pump(0.5)
        require(js(third, "active") as? Bool == false, "Restore from minimize auto-started capture")
        webViewWebContentProcessDidTerminate(third)
        require(mirrorWebView == nil && mirrorRetry?.isHidden == false, "Process termination not recoverable")
        selectDetailTab(1)
        require(mirrorWebView != nil, "Escape fixture missing")
        detailPanel?.cancelOperation(nil)
        require(mirrorWebView == nil && detailPanel?.isVisible == false, "Escape retained mirror window or viewer")
        detailPanel?.orderBack(nil)
        selectDetailTab(1)
        require(mirrorWebView != nil, "Close fixture missing")
        detailPanel?.performClose(nil)
        require(mirrorWebView == nil && detailPanel?.isVisible == false, "Close button retained mirror window or viewer")
        closeDetailPanel()
        pump(0.2)
        print("WEBKIT_MIRROR_LIFECYCLE_OK")
    }
}
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)
let delegate = OmsMacHudAppDelegate(socketPath: "/tmp/unused-webkit-fixture.sock", parentPid: getppid())
delegate.verifyMirror()
`;
  fs.writeFileSync(swift, source.slice(0, source.indexOf('// MARK: - Entry Point')) + fixture);
  execFileSync('swiftc', [swift, '-framework', 'Cocoa', '-framework', 'PDFKit', '-framework', 'Quartz', '-framework', 'AVFoundation', '-framework', 'WebKit', '-o', binary], { timeout: 45000 });
  let opened = 0, closed = 0, starts = 0, inputs = 0, globalStops = 0;
  const invalid: string[] = [];
  const webp = Buffer.from('UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA', 'base64');
  const frame = new Uint8Array(16 + webp.length);
  const header = new DataView(frame.buffer);
  header.setUint8(0, 1); header.setUint16(2, 1); header.setUint16(4, 1); frame.set(webp, 16);
  const server = Bun.serve({
    hostname: '127.0.0.1', port: 0,
    async fetch(req, srv) {
      const url = new URL(req.url);
      if (url.pathname === '/ws/mirror') {
        if (url.search !== '?manual=1') invalid.push('not manual');
        if (req.headers.get('origin') !== url.origin) invalid.push('unexpected WebKit origin');
        if (srv.upgrade(req)) return;
      }
      return await handleDashboardHttpRequest({} as any, req, url) ?? new Response('Not found', { status: 404 });
    },
    websocket: {
      open(ws) { opened++; ws.send(JSON.stringify({ type: 'mirror_status', status: { running: false } })); },
      message(ws, raw) {
        const m = JSON.parse(String(raw));
        if (m.type === 'start') {
          starts++; if (m.autoWake !== false) invalid.push('auto wake');
          ws.send(JSON.stringify({ type: 'mirror_status', status: { running: true } }));
          ws.send(frame);
        } else if (m.type === 'input') {
          inputs++; ws.send(JSON.stringify({ type: 'input_ack', reqId: m.reqId, ok: true, result: { ok: true } }));
        } else if (m.type === 'stop') globalStops++;
      },
      close() { closed++; }
    }
  });
  const child = spawn(binary, [`http://127.0.0.1:${server.port}/mirror?hud=1`], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '', stderr = '';
  child.stdout.on('data', d => stdout += d); child.stderr.on('data', d => stderr += d);
  const timeout = setTimeout(() => child.kill('SIGTERM'), 30000);
  try {
    const code = await new Promise<number | null>((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
    if (code !== 0) throw new Error(`WebKit fixture exited ${code}\n${stdout}\n${stderr}`);
    expect(stdout).toContain('WEBKIT_MIRROR_LIFECYCLE_OK');
    expect(invalid).toEqual([]);
    expect(opened).toBe(1); expect(closed).toBe(1);
    expect(starts).toBe(1); expect(inputs).toBe(1); expect(globalStops).toBe(0);
    expect(stderr).not.toContain('Unable to simultaneously satisfy constraints');
  } finally { clearTimeout(timeout); child.kill('SIGTERM'); server.stop(true); }
}, 80000);
