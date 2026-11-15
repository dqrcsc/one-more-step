import { expect, test } from "bun:test";
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

test.skipIf(process.platform !== "darwin")("real AppKit detail window constructs and lays out empty and populated inboxes", () => {
  // Exercise the actual window factory without showing UI, reading a real inbox or starting IPC.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-hud-window-"));
  const source = fs.readFileSync(path.resolve(import.meta.dir, "../mac-hud/main.swift"), "utf8");
  const production = source.slice(0, source.indexOf("// MARK: - Entry Point"));
  const fixture = String.raw`
extension OmsMacHudAppDelegate {
    func verifyWindowConstruction() {
        buildDetailPanelWindow()
        guard let panel = detailPanel else { fatalError("No detail panel") }
        precondition(panel.canBecomeKey && panel.canBecomeMain)
        precondition(!panel.isVisible)
        precondition(panel.collectionBehavior.contains(.fullScreenAuxiliary))
        selectDetailTab(1)
        precondition(mirrorWebView == nil, "Missing URL must not load WebKit or connect")
        precondition(inboxContent?.isHidden == true && mirrorContent?.isHidden == false)
        for invalid in ["http://example.com:19527/mirror?hud=1", "file:///tmp/mirror", "http://127.0.0.1:19527/inbox?hud=1", "http://user@127.0.0.1:19527/mirror?hud=1", "http://127.0.0.1:0/mirror?hud=1"] {
            statusSnapshot.mirrorUrl = invalid
            precondition(validatedMirrorURL() == nil)
        }
        statusSnapshot.mirrorUrl = "http://127.0.0.1:23671/mirror?hud=1"
        precondition(validatedMirrorURL()?.port == 23671)
        statusSnapshot.mirrorUrl = nil
        selectDetailTab(0)
        precondition(inboxContent?.isHidden == false)
        rebuildSidebarAndInspector()
        precondition(sidebarStack?.arrangedSubviews.count == 1, "Missing empty inbox state")
        for count in [1, 25, 30] {
            items = (0..<count).map { i in
                HudInboxItem(itemId: "fixture-\(i)", receivedAt: "2026-11-15T00:00:00Z",
                    createdAtMs: Double(i), intentTag: "default_inbox", title: "Fixture \(i)",
                    textContent: "Synthetic inbox content", voiceTranscript: nil, sourceApp: nil,
                    otpCode: nil, otpSender: nil, processed: false, files: [])
            }
            rebuildSidebarAndInspector()
            panel.contentView?.layoutSubtreeIfNeeded()
            precondition(sidebarStack?.arrangedSubviews.count == min(count, 25))
            precondition(selectedItemId == "fixture-0")
            precondition(inspectorContainer?.subviews.isEmpty == false)
            precondition((sidebarStack?.fittingSize.height ?? 0) > 0)
        }
        // A real image's intrinsic size used to grow this window to >6000pt tall.
        let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1200, pixelsHigh: 6000,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        let imageURL = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent().appendingPathComponent("tall.png")
        try! bitmap.representation(using: .png, properties: [:])!.write(to: imageURL)
        items = [HudInboxItem(itemId: "tall", receivedAt: "", createdAtMs: 0,
            intentTag: "default_inbox", title: "Synthetic tall image", textContent: "Synthetic text",
            voiceTranscript: nil, sourceApp: nil, otpCode: nil, otpSender: nil, processed: false,
            files: [HudStoredFile(fileName: "tall.png", absPath: imageURL.path,
                mimeType: "image/png", sizeBytes: 1, sha256: nil)])]
        selectedItemId = "tall"
        panel.alphaValue = 0
        panel.orderBack(nil)
        rebuildSidebarAndInspector()
        func settle() {
            panel.layoutIfNeeded()
            for _ in 0..<5 { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        }
        settle()
        precondition(panel.frame.width <= 840 && panel.frame.height <= 560, "Image enlarged window")
        selectDetailTab(1)
        panel.setContentSize(NSSize(width: 680, height: 440))
        settle()
        precondition(panel.frame.height == 440, "Hidden inspector constrains mirror")
        selectDetailTab(0)
        settle()
        precondition(panel.frame.width == 680 && panel.frame.height < 560, "Narrow inbox cannot fit")
        func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap { descendants($0) } }
        let close = descendants(inspectorContainer!).compactMap { $0 as? NSButton }.first { $0.title.hasSuffix("(Esc)") }!
        let actions = close.enclosingScrollView!
        precondition(actions.hasHorizontalScroller && actions.documentView!.frame.width > actions.contentView.bounds.width,
            "Narrow action row must scroll to reach all buttons")
        precondition(close.frame.maxX <= actions.documentView!.bounds.maxX && close.frame.height > 0)
        // Simulate a smaller usable region, keeping AppKit on the actual display.
        let visible = panel.screen!.visibleFrame
        let screen = NSRect(x: visible.minX + 50, y: visible.minY + 50, width: 1024, height: 700)
        panel.setFrame(NSRect(x: -1400, y: -1200, width: 1400, height: 1800), display: false)
        constrainDetailPanelToScreen(visibleFrame: screen)
        settle()
        precondition(screen.contains(panel.frame), "Window not clamped inside usable display: \(panel.frame), expected \(screen)")
        precondition(panel.maxSize == screen.size)
        panel.cancelOperation(nil)
        precondition(!panel.isVisible, "Escape did not hide window")
        panel.orderBack(nil)
        panel.performClose(nil)
        precondition(!panel.isVisible, "Close button did not close window")
        // Closing keeps the reusable panel alive.
        panel.orderBack(nil)
        precondition(panel.isVisible)
        items = (0..<25).map { i in
            HudInboxItem(itemId: "fixture-\(i)", receivedAt: "", createdAtMs: Double(i),
                intentTag: "default_inbox", title: "Fixture", textContent: "Text",
                voiceTranscript: nil, sourceApp: nil, otpCode: nil, otpSender: nil, processed: false, files: [])
        }
        selectedItemId = "fixture-24"
        rebuildSidebarAndInspector()
        precondition(selectedItemId == "fixture-24")
        items = []
        selectedItemId = nil
        rebuildSidebarAndInspector()
        precondition(sidebarStack?.arrangedSubviews.count == 1)
        closeDetailPanel()
        print("APPKIT_WINDOW_AND_INBOX_LAYOUT_OK")
    }
}
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)
let delegate = OmsMacHudAppDelegate(socketPath: "/tmp/unused-hud-window-test.sock", parentPid: getppid())
delegate.verifyWindowConstruction()
`;
  const swift = path.join(dir, "main.swift"), binary = path.join(dir, "window-test");
  fs.writeFileSync(swift, production + fixture);
  execFileSync("swiftc", [swift, "-framework", "Cocoa", "-framework", "PDFKit", "-framework", "Quartz", "-framework", "AVFoundation", "-framework", "WebKit", "-o", binary], { timeout: 45000 });
  const result = spawnSync(binary, { encoding: "utf8", timeout: 15000 });
  if (result.status !== 0) throw new Error(`Window fixture failed: ${result.signal}\n${result.stdout}\n${result.stderr}`);
  expect(result.error).toBeUndefined();
  expect(result.signal).toBeNull();
  expect(result.status).toBe(0);
  expect(result.stderr).not.toContain("NSInternalInconsistencyException");
  expect(result.stderr).not.toContain("Unable to simultaneously satisfy constraints");
  expect(result.stdout).toContain("APPKIT_WINDOW_AND_INBOX_LAYOUT_OK");
}, 65000);
