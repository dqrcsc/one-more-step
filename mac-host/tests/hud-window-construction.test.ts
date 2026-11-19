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
        selectedItemId = "fixture-24"
        rebuildSidebarAndInspector()
        precondition(selectedItemId == "fixture-24")
        items = []
        selectedItemId = nil
        rebuildSidebarAndInspector()
        precondition(sidebarStack?.arrangedSubviews.count == 1)

        // Verify WeChat Context tab & multi-line title hit-testing routes clicks to ClickableRowView
        selectDetailTab(2)
        precondition(wechatContent?.isHidden == false && inboxContent?.isHidden == true && mirrorContent?.isHidden == true)
        wxArticles = [
            HudWeChatArticleSummary(id: 156, title: "DeepSeek SuperAgent architecture and engineering practice long wrapping title", author: "A", url: "https://example.com/156",
                user_notes: "", status: "light_done", triage: "process_later", process_level: "light",
                short_summary: "Summary 1", source_md_path: "", note_md_path: "", created_at: "2026-11-18T10:00:00Z", isFallbackNote: false),
            HudWeChatArticleSummary(id: 155, title: "Enterprise AI knowledge base compilation and semantic layer long wrapping title", author: "B", url: "https://example.com/155",
                user_notes: "", status: "light_done", triage: "process_later", process_level: "light",
                short_summary: "Summary 2", source_md_path: "", note_md_path: "", created_at: "2026-11-18T09:00:00Z", isFallbackNote: false)
        ]
        selectedWxArticleId = 156
        rebuildWeChatSidebar()
        panel.contentView?.layoutSubtreeIfNeeded()
        guard let wxStack = wxSidebarStack, wxStack.arrangedSubviews.count == 2,
              let secondRow = wxStack.arrangedSubviews[1] as? ClickableRowView,
              let titleLabel = secondRow.subviews.last as? NSTextField else {
            fatalError("Missing WeChat sidebar rows")
        }
        precondition(!titleLabel.isSelectable, "Wrapping label must not swallow clicks via text selection")
        let titleMidInRow = NSPoint(x: titleLabel.frame.midX, y: titleLabel.frame.midY)
        let titleMidInStackSuper = secondRow.convert(titleMidInRow, to: wxStack.superview)
        precondition(wxStack.hitTest(titleMidInStackSuper) === secondRow, "Clicking on wrapping article title must hit ClickableRowView")
        secondRow.onClick?()
        precondition(selectedWxArticleId == 155, "Clicking second WeChat row must select article 155")

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
  expect(result.error).toBeUndefined();
  expect(result.signal).toBeNull();
  expect(result.status).toBe(0);
  expect(result.stderr).not.toContain("NSInternalInconsistencyException");
  expect(result.stderr).not.toContain("Unable to simultaneously satisfy constraints");
  expect(result.stdout).toContain("APPKIT_WINDOW_AND_INBOX_LAYOUT_OK");
}, 65000);
