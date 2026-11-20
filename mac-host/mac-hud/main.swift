import Cocoa
import PDFKit
import Quartz
import AVFoundation
import Darwin
import WebKit

// MARK: - Wire Models

struct HudStoredFile: Codable, Equatable {
    let fileName: String
    let absPath: String
    let mimeType: String
    let sizeBytes: Int
    let sha256: String?
}

struct HudInboxItem: Codable, Equatable {
    let itemId: String
    let receivedAt: String
    let createdAtMs: Double
    let intentTag: String
    let title: String?
    let textContent: String?
    let voiceTranscript: String?
    let sourceApp: String?
    let otpCode: String?
    let otpSender: String?
    var processed: Bool
    let files: [HudStoredFile]
}

struct HudToastPayload: Codable {
    let title: String
    let message: String
    let subtitle: String?
    let soundName: String?
    let itemId: String?
    let channel: String?
    let wechatArticleId: Int?
}

struct HudStatusSnapshot: Codable {
    let activeTier: String?
    let phoneName: String?
    let inboxDir: String?
    var mirrorUrl: String? = nil
    var wechatRunning: Bool? = nil
    var wechatConfigured: Bool? = nil
    var wechatLastPoll: String? = nil
    var wechatApiBaseUrl: String? = nil
    var wechatConfigUrl: String? = nil
}

struct HudIncomingEnvelope: Codable {
    let type: String
    let items: [HudInboxItem]?
    let item: HudInboxItem?
    let toast: HudToastPayload?
    let status: HudStatusSnapshot?
    let itemId: String?
    let wechatArticleId: Int?
}

struct HudWeChatArticleSummary: Codable, Equatable {
    let id: Int
    let title: String
    let author: String
    let url: String
    let user_notes: String
    let status: String
    let triage: String
    let process_level: String
    let short_summary: String
    let source_md_path: String
    let note_md_path: String
    let created_at: String
    let isFallbackNote: Bool?
}

struct HudWeChatOverviewResponse: Codable {
    let ok: Bool
    let articles: [HudWeChatArticleSummary]?
}

struct HudWeChatArticleDetailResponse: Codable {
    let ok: Bool
    let article: HudWeChatArticleSummary?
    let noteMarkdown: String?
    let noteHtml: String?
    let sourceMarkdown: String?
    let sourceHtml: String?
    let summaryHtml: String?
    let resolvedImages: [String: String]?
}

// MARK: - Helpers

private func writeAllFD(_ fd: Int32, _ data: Data) -> Bool {
    return data.withUnsafeBytes { rawBuf -> Bool in
        guard let base = rawBuf.baseAddress else { return true }
        var offset = 0
        while offset < rawBuf.count {
            let n = Darwin.write(fd, base.advanced(by: offset), rawBuf.count - offset)
            if n > 0 {
                offset += n
            } else if n < 0 && errno == EINTR {
                continue
            } else {
                return false
            }
        }
        return true
    }
}

private func formatByteSize(_ bytes: Int) -> String {
    if bytes <= 0 { return "0 B" }
    if bytes < 1024 { return "\(bytes) B" }
    if bytes < 1024 * 1024 { return String(format: "%.1f KB", Double(bytes) / 1024.0) }
    return String(format: "%.2f MB", Double(bytes) / (1024.0 * 1024.0))
}

private func formatTimeLabel(_ ms: Double) -> String {
    let date = Date(timeIntervalSince1970: ms / 1000.0)
    let cal = Calendar.current
    let fmt = DateFormatter()
    if cal.isDateInToday(date) {
        fmt.dateFormat = "HH:mm:ss"
    } else {
        fmt.dateFormat = "MM-dd HH:mm"
    }
    return fmt.string(from: date)
}

    private func tagDisplayLabel(_ tag: String) -> String {
    switch tag {
    case "pkb_vault": return "📚 存知识库"
    case "voice_idea": return "💡 语音闪念"
    case "ui_bug": return "🐛 修UI报错"
    case "sms_otp": return "🔑 验证码"
    case "wechat_context": return "💬 微信尺素"
    default: return "📥 默认Inbox"
    }
}

private func iconForItem(_ item: HudInboxItem) -> String {
    if item.otpCode != nil || item.intentTag == "sms_otp" { return "🔑" }
    if let first = item.files.first {
        return iconForFile(first)
    }
    if item.voiceTranscript != nil || item.intentTag == "voice_idea" { return "🎙️" }
    return "💬"
}

private func iconForFile(_ file: HudStoredFile) -> String {
    let ext = (file.fileName as NSString).pathExtension.lowercased()
    let mime = file.mimeType.lowercased()
    if mime.hasPrefix("image/") || ["png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp", "svg"].contains(ext) {
        return "🖼️"
    }
    if mime.hasPrefix("audio/") || ["m4a", "mp3", "wav", "ogg", "aac", "flac"].contains(ext) {
        return "🎙️"
    }
    if ext == "pdf" || mime == "application/pdf" { return "📕" }
    if ["doc", "docx", "rtf"].contains(ext) || mime.contains("word") { return "📘" }
    if ["xls", "xlsx", "csv"].contains(ext) || mime.contains("excel") || mime.contains("spreadsheet") { return "📗" }
    if ["ppt", "pptx"].contains(ext) || mime.contains("powerpoint") || mime.contains("presentation") { return "📙" }
    if ["zip", "rar", "7z", "tar", "gz"].contains(ext) { return "🗜️" }
    return "📄"
}

private func isImageFile(_ file: HudStoredFile) -> Bool {
    let ext = (file.fileName as NSString).pathExtension.lowercased()
    return file.mimeType.lowercased().hasPrefix("image/") ||
        ["png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp"].contains(ext)
}

private func isPdfFile(_ file: HudStoredFile) -> Bool {
    let ext = (file.fileName as NSString).pathExtension.lowercased()
    return ext == "pdf" || file.mimeType.lowercased() == "application/pdf"
}

private func isAudioFile(_ file: HudStoredFile) -> Bool {
    let ext = (file.fileName as NSString).pathExtension.lowercased()
    return file.mimeType.lowercased().hasPrefix("audio/") ||
        ["m4a", "mp3", "wav", "ogg", "aac", "flac"].contains(ext)
}

private func isPlainTextFile(_ file: HudStoredFile) -> Bool {
    let ext = (file.fileName as NSString).pathExtension.lowercased()
    return ["txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log"].contains(ext)
}

// MARK: - Custom Non-Activating & Keyable Panels

final class NonActivatingToastPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

final class KeyableDetailPanel: NSPanel {
    var onEscapePressed: (() -> Void)?

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }

    override func cancelOperation(_ sender: Any?) {
        if (firstResponder as? NSTextInputClient)?.hasMarkedText() == true { return }
        onEscapePressed?()
    }

    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 && (firstResponder as? NSTextInputClient)?.hasMarkedText() != true { // Escape
            onEscapePressed?()
            return
        }
        super.keyDown(with: event)
    }
}

final class MirrorWebView: WKWebView {
    var onEscapePressed: (() -> Void)?
    override func keyDown(with event: NSEvent) {
        let composing = (window?.firstResponder as? NSTextInputClient)?.hasMarkedText() ?? false
        if event.keyCode == 53 && !composing { onEscapePressed?(); return }
        super.keyDown(with: event)
    }
}

final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var delegate: WKScriptMessageHandler?
    init(_ delegate: WKScriptMessageHandler) {
        self.delegate = delegate
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        delegate?.userContentController(userContentController, didReceive: message)
    }
}

// The preview viewport determines the image size, never the source pixels.
// NSImageView's intrinsic size otherwise raises the window's layout minimum.
final class ScaledPreviewImageView: NSImageView {
    var onOpen: (() -> Void)?
    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: NSView.noIntrinsicMetric)
    }
    override var acceptsFirstResponder: Bool { onOpen != nil }
    override func mouseDown(with event: NSEvent) { window?.makeFirstResponder(self) }
    override func mouseUp(with event: NSEvent) {
        if bounds.contains(convert(event.locationInWindow, from: nil)) { onOpen?() }
    }
    override func keyDown(with event: NSEvent) {
        if event.keyCode == 36 || event.keyCode == 49 { onOpen?() }
        else { super.keyDown(with: event) }
    }
    override func resetCursorRects() {
        super.resetCursorRects()
        if onOpen != nil { addCursorRect(bounds, cursor: .pointingHand) }
    }
    override func accessibilityPerformPress() -> Bool {
        guard let open = onOpen else { return false }
        open()
        return true
    }
}

final class CenteredImageClipView: NSClipView {
    override func constrainBoundsRect(_ proposedBounds: NSRect) -> NSRect {
        var rect = super.constrainBoundsRect(proposedBounds)
        if let document = documentView {
            if document.frame.width < rect.width { rect.origin.x = (document.frame.width - rect.width) / 2 }
            if document.frame.height < rect.height { rect.origin.y = (document.frame.height - rect.height) / 2 }
        }
        return rect
    }
}

final class ImagePreviewController: NSWindowController, NSWindowDelegate {
    let scroll = NSScrollView()
    private let imageSize: NSSize
    private var fitsWindow = true
    private var magnificationObserver: NSObjectProtocol?
    var onClose: (() -> Void)?

    init(image: NSImage, title: String, screen: NSRect) {
        imageSize = image.size
        let panel = KeyableDetailPanel(contentRect: NSRect(x: 0, y: 0, width: 900, height: 650),
            styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        super.init(window: panel)
        panel.title = title
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.level = .floating
        panel.minSize = NSSize(width: 360, height: 280)
        panel.maxSize = screen.size
        panel.setFrame(NSRect(x: screen.midX - min(1100, screen.width) / 2, y: screen.minY,
            width: min(1100, screen.width), height: screen.height), display: false)
        panel.delegate = self
        panel.onEscapePressed = { [weak self] in self?.close() }

        guard let content = panel.contentView else { return }
        let toolbar = NSStackView()
        toolbar.spacing = 8
        for (label, action) in [("−", #selector(zoomOut)), ("+", #selector(zoomIn)),
            ("适应窗口", #selector(fitImage)), ("100%", #selector(actualSize)), ("关闭 (Esc)", #selector(closePreview))] {
            toolbar.addArrangedSubview(NSButton(title: label, target: self, action: action))
        }
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(toolbar)
        scroll.contentView = CenteredImageClipView()
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = true
        scroll.autohidesScrollers = true
        scroll.allowsMagnification = true
        scroll.minMagnification = 0.01
        scroll.maxMagnification = 8
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let imageView = NSImageView(frame: NSRect(origin: .zero, size: imageSize))
        imageView.image = image
        imageView.imageScaling = .scaleProportionallyUpOrDown
        scroll.documentView = imageView
        magnificationObserver = NotificationCenter.default.addObserver(
            forName: NSScrollView.didEndLiveMagnifyNotification, object: scroll, queue: .main
        ) { [weak self] _ in self?.fitsWindow = false }
        content.addSubview(scroll)
        NSLayoutConstraint.activate([
            toolbar.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            toolbar.topAnchor.constraint(equalTo: content.topAnchor, constant: 8),
            scroll.topAnchor.constraint(equalTo: toolbar.bottomAnchor, constant: 8),
            scroll.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: content.bottomAnchor)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    deinit {
        if let observer = magnificationObserver { NotificationCenter.default.removeObserver(observer) }
    }
    @objc func fitImage() {
        fitsWindow = true
        window?.layoutIfNeeded()
        let viewport = scroll.contentView.frame.size
        let scale = min(viewport.width / max(1, imageSize.width), viewport.height / max(1, imageSize.height))
        setZoom(min(1, scale))
    }
    private func setZoom(_ scale: CGFloat) {
        scroll.setMagnification(min(scroll.maxMagnification, max(scroll.minMagnification, scale)),
            centeredAt: NSPoint(x: imageSize.width / 2, y: imageSize.height / 2))
    }
    @objc func zoomIn() { fitsWindow = false; setZoom(scroll.magnification * 1.25) }
    @objc func zoomOut() { fitsWindow = false; setZoom(scroll.magnification / 1.25) }
    @objc func actualSize() { fitsWindow = false; setZoom(1) }
    @objc func closePreview() { close() }
    func windowDidResize(_ notification: Notification) { if fitsWindow { fitImage() } }
    func windowWillClose(_ notification: Notification) { onClose?() }
}

final class HoverableToastView: NSVisualEffectView {
    var onHoverChanged: ((Bool) -> Void)?
    var onCardClicked: (() -> Void)?
    private var tracking: NSTrackingArea?

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let area = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        tracking = area
    }

    override func mouseEntered(with event: NSEvent) {
        onHoverChanged?(true)
    }

    override func mouseExited(with event: NSEvent) {
        onHoverChanged?(false)
    }

    override func mouseUp(with event: NSEvent) {
        let pt = convert(event.locationInWindow, from: nil)
        if bounds.contains(pt) {
            // Let subview buttons handle their own clicks first; background click opens detail
            let hit = hitTest(pt)
            if !(hit is NSButton) {
                onCardClicked?()
            }
        }
    }
}

final class ClickableRowView: NSView {
    var onClick: (() -> Void)?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool {
        return true
    }

    override func hitTest(_ point: NSPoint) -> NSView? {
        // Route clicks anywhere inside the row (including multi-line wrapping NSTextFields)
        // directly to ClickableRowView so subview text cells never swallow mouseDown.
        return super.hitTest(point) != nil ? self : nil
    }

    override func resetCursorRects() {
        super.resetCursorRects()
        addCursorRect(bounds, cursor: .pointingHand)
    }

    override func mouseDown(with event: NSEvent) {
        onClick?()
    }
}

// MARK: - Unix Socket Client

final class HudIpcClient {
    private let socketPath: String
    private let readQueue = DispatchQueue(label: "com.oms.hud.ipc.read")
    private let writeQueue = DispatchQueue(label: "com.oms.hud.ipc.write")
    private let stateLock = NSLock()
    private var sockFD: Int32 = -1
    private var generation: UInt64 = 0
    private var running = true
    private var started = false
    // Only accessed on writeQueue; retain unsent frames across reconnects.
    private var pendingFrames: [Data] = []

    private var isRunning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return running
    }
    var onEnvelope: ((HudIncomingEnvelope) -> Void)?

    init(socketPath: String) {
        self.socketPath = socketPath
    }

    func start() {
        stateLock.lock()
        guard running && !started else { stateLock.unlock(); return }
        started = true
        stateLock.unlock()
        readQueue.async { [weak self] in self?.connectLoop() }
    }

    func stop() {
        stateLock.lock()
        running = false
        // The reader owns close(). shutdown wakes read/write without fd reuse races.
        if sockFD >= 0 { Darwin.shutdown(sockFD, SHUT_RDWR) }
        stateLock.unlock()
        writeQueue.async { [weak self] in self?.pendingFrames.removeAll() }
    }

    func sendJson(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: []) else { return }
        var framed = data
        framed.append(0x0A)
        writeQueue.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.pendingFrames.append(framed)
            self.flushPendingFrames()
        }
    }

    private func flushPendingFrames() {
        while !pendingFrames.isEmpty {
            stateLock.lock()
            // A duplicate keeps this connection alive if the reader closes its fd.
            // Never write through a descriptor that could have been reused.
            let fd = running && sockFD >= 0 ? Darwin.dup(sockFD) : -1
            let writingGeneration = generation
            stateLock.unlock()
            guard fd >= 0 else { return }
            let sent = writeAllFD(fd, pendingFrames[0])
            Darwin.close(fd)
            if sent {
                pendingFrames.removeFirst()
            } else {
                stateLock.lock()
                if sockFD >= 0 && generation == writingGeneration {
                    Darwin.shutdown(sockFD, SHUT_RDWR)
                }
                stateLock.unlock()
                return
            }
        }
    }

    var onConnectionChanged: ((Bool) -> Void)?

    private func connectLoop() {
        let decoder = JSONDecoder()
        while isRunning {
            let fd = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
            if fd < 0 {
                Thread.sleep(forTimeInterval: 1.5)
                continue
            }
            var addr = sockaddr_un()
            addr.sun_family = sa_family_t(AF_UNIX)
            let pathBytes = socketPath.utf8CString
            let maxLen = MemoryLayout.size(ofValue: addr.sun_path)
            if pathBytes.count >= maxLen {
                Darwin.close(fd)
                return
            }
            withUnsafeMutablePointer(to: &addr.sun_path) { ptr in
                let raw = UnsafeMutableRawPointer(ptr).assumingMemoryBound(to: CChar.self)
                for i in 0..<pathBytes.count {
                    raw[i] = pathBytes[i]
                }
            }
            let addrLen = socklen_t(MemoryLayout<sockaddr_un>.size)
            let connected = withUnsafePointer(to: &addr) { ptr -> Int32 in
                let sockPtr = UnsafeRawPointer(ptr).assumingMemoryBound(to: sockaddr.self)
                return Darwin.connect(fd, sockPtr, addrLen)
            }
            if connected != 0 {
                Darwin.close(fd)
                Thread.sleep(forTimeInterval: 1.2)
                continue
            }

            // Bound writes to an unresponsive peer and suppress process-wide SIGPIPE.
            var noSigPipe: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))
            var sendTimeout = timeval(tv_sec: 2, tv_usec: 0)
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &sendTimeout, socklen_t(MemoryLayout<timeval>.size))
            stateLock.lock()
            guard running else { stateLock.unlock(); Darwin.close(fd); return }
            sockFD = fd
            generation &+= 1
            stateLock.unlock()
            DispatchQueue.main.async { [weak self] in self?.onConnectionChanged?(true) }
            writeQueue.async { [weak self] in self?.flushPendingFrames() }
            var buffer = Data()
            var readBuf = [UInt8](repeating: 0, count: 16384)
            while isRunning {
                let n = Darwin.read(fd, &readBuf, readBuf.count)
                if n > 0 {
                    buffer.append(readBuf, count: n)
                    while let nlIdx = buffer.firstIndex(of: 0x0A) {
                        let lineData = buffer.subdata(in: 0..<nlIdx)
                        buffer.removeSubrange(0...nlIdx)
                        if lineData.isEmpty { continue }
                        if let env = try? decoder.decode(HudIncomingEnvelope.self, from: lineData) {
                            DispatchQueue.main.async { [weak self] in
                                self?.onEnvelope?(env)
                            }
                        }
                    }
                } else if n < 0 && errno == EINTR {
                    continue
                } else {
                    break
                }
            }
            stateLock.lock()
            sockFD = -1
            Darwin.shutdown(fd, SHUT_RDWR)
            Darwin.close(fd)
            stateLock.unlock()
            DispatchQueue.main.async { [weak self] in self?.onConnectionChanged?(false) }
            if isRunning {
                Thread.sleep(forTimeInterval: 1.0)
            }
        }
    }
}

// MARK: - Main HUD Application Controller

final class OmsMacHudAppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, AVAudioPlayerDelegate, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    private let socketPath: String
    private let initialParentPid: pid_t
    private var ipc: HudIpcClient!
    private var watchdogTimer: Timer?

    private var statusItem: NSStatusItem!
    private var items: [HudInboxItem] = []
    private var unreadItemIds = Set<String>()
    private var statusSnapshot = HudStatusSnapshot(activeTier: "DISCONNECTED", phoneName: nil, inboxDir: nil)

    // Toast state
    private var toastPanel: NonActivatingToastPanel?
    private var toastDismissTimer: Timer?
    private var currentToastItem: HudInboxItem?
    private var currentToastChannel: String = "phone"
    private var currentToastWeChatArticleId: Int?
    private var copyFeedbackButton: NSButton?

    // Detail panel state
    private var detailPanel: KeyableDetailPanel?
    private var isDetailPinned = false
    private var selectedItemId: String?
    private var selectedAttachmentIndex = 0
    private var activeQLPreview: QLPreviewView?
    private var imagePreview: ImagePreviewController?
    private var audioPlayer: AVAudioPlayer?
    private weak var audioPlayButton: NSButton?

    // Subviews inside detail panel
    private var sidebarStack: NSStackView?
    private var inspectorContainer: NSView?
    private var renderedInspectorItem: HudInboxItem?
    private var renderedAttachmentIndex: Int?
    private var pinButton: NSButton?
    private var detailTabs: NSSegmentedControl?
    private var inboxContent: NSView?
    private var wechatContent: NSView?
    private var wxSidebarStack: NSStackView?
    private var wxInspectorContainer: NSView?
    private var wxArticles: [HudWeChatArticleSummary] = []
    private var selectedWxArticleId: Int?
    private var currentWxDetail: HudWeChatArticleDetailResponse?
    private var selectedWxDocSegment: Int = 0
    private var isWxRawSourceMode: Bool = false
    private var wxReaderWebView: MirrorWebView?
    private var wxReaderBaseURL: URL?
    private var mirrorContent: NSView?
    private var mirrorWebView: MirrorWebView?
    private var mirrorLoadedURL: URL?
    private var mirrorMessage: NSTextField?
    private var mirrorRetry: NSButton?
    private var selectedDetailTab = 0
    private var sleepObserver: NSObjectProtocol?

    init(socketPath: String, parentPid: pid_t) {
        self.socketPath = socketPath
        self.initialParentPid = parentPid
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupMenuBarStatusItem()
        ipc = HudIpcClient(socketPath: socketPath)
        ipc.onEnvelope = { [weak self] env in
            self?.handleEnvelope(env)
        }
        ipc.onConnectionChanged = { [weak self] connected in
            guard let self = self, !connected else { return }
            self.statusSnapshot.mirrorUrl = nil
            self.destroyMirrorView()
            self.mirrorMessage?.stringValue = "Mac 服务已断开，等待重新连接"
        }
        ipc.start()
        sleepObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            self?.destroyMirrorView()
            self?.mirrorMessage?.stringValue = "镜像已随 Mac 休眠结束，重新加载后可手动开始"
        }

        watchdogTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let currentPpid = getppid()
            if currentPpid <= 1 || currentPpid != self.initialParentPid {
                self.cleanupAndExit()
            }
        }
    }

    private func cleanupAndExit() {
        destroyMirrorView()
        destroyWxReaderView()
        if let token = sleepObserver { NSWorkspace.shared.notificationCenter.removeObserver(token) }
        closeActiveQLPreview()
        audioPlayer?.stop()
        ipc?.stop()
        NSApplication.shared.terminate(nil)
        exit(0)
    }

    // MARK: - Menu Bar Icon

    private func setupMenuBarStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        guard let btn = statusItem.button else { return }
        btn.target = self
        btn.action = #selector(handleStatusBarClick(_:))
        btn.sendAction(on: [.leftMouseUp, .rightMouseUp])
        updateStatusBarAppearance()
    }

    private func updateStatusBarAppearance() {
        guard let btn = statusItem?.button else { return }
        if let sym = NSImage(systemSymbolName: "iphone.radiowaves.left.and.right", accessibilityDescription: "青简·咫尺") {
            sym.isTemplate = true
            btn.image = sym
            btn.imagePosition = unreadItemIds.isEmpty ? .imageOnly : .imageLeft
            btn.title = unreadItemIds.isEmpty ? "" : "•\(min(unreadItemIds.count, 99))"
        } else {
            btn.title = unreadItemIds.isEmpty ? "青简" : "青简•\(unreadItemIds.count)"
        }
        let tierText: String
        switch statusSnapshot.activeTier ?? "DISCONNECTED" {
        case "LAN_WS": tierText = "局域网高速直连"
        case "BT_RFCOMM": tierText = "蓝牙近场已连"
        case "WAN_WEBRTC": tierText = "远程跨网在线"
        default: tierText = "离线待机"
        }
        btn.toolTip = "青简·咫尺 — \(tierText)（左键查看最近投递，右键更多操作）"
    }

    @objc private func handleStatusBarClick(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            toggleDetailPanel()
            return
        }
        if event.type == .rightMouseUp || event.modifierFlags.contains(.control) || event.modifierFlags.contains(.option) {
            showStatusContextMenu()
        } else {
            toggleDetailPanel()
        }
    }

    private func showStatusContextMenu() {
        let menu = NSMenu()
        let tier = statusSnapshot.activeTier ?? "DISCONNECTED"
        let tierDesc = tier == "DISCONNECTED" ? "⚪ 手机当前未连接" : "🟢 手机已连 (\(tier))"
        let statusEntry = NSMenuItem(title: tierDesc, action: nil, keyEquivalent: "")
        statusEntry.isEnabled = false
        menu.addItem(statusEntry)

        let wxDesc: String
        if statusSnapshot.wechatRunning == true && statusSnapshot.wechatConfigured == true {
            wxDesc = "🟢 微信尺素：ClawBot 在线"
        } else if statusSnapshot.wechatRunning == true {
            wxDesc = "🟠 微信尺素：待扫码绑定"
        } else {
            wxDesc = "⚪ 微信尺素：待命"
        }
        let wxEntry = NSMenuItem(title: wxDesc, action: nil, keyEquivalent: "")
        wxEntry.isEnabled = false
        menu.addItem(wxEntry)
        menu.addItem(NSMenuItem.separator())

        let openItem = NSMenuItem(title: "📬 查看最近投递（\(min(items.count, 25)) 条）", action: #selector(openDetailFromMenu), keyEquivalent: "")
        openItem.target = self
        menu.addItem(openItem)

        let wxItem = NSMenuItem(title: "💬 微信尺素伴读收件箱…", action: #selector(openWeChatFromMenu), keyEquivalent: "")
        wxItem.target = self
        menu.addItem(wxItem)

        let mirrorItem = NSMenuItem(title: "📱 手机镜像…", action: #selector(openMirrorFromMenu), keyEquivalent: "")
        mirrorItem.target = self
        menu.addItem(mirrorItem)

        let markAllItem = NSMenuItem(title: "✓ 清除菜单栏未读角标", action: #selector(clearUnreadBadges), keyEquivalent: "")
        markAllItem.target = self
        menu.addItem(markAllItem)

        if let dir = statusSnapshot.inboxDir, !dir.isEmpty {
            let finderItem = NSMenuItem(title: "📂 在 Finder 中打开 Inbox 文件夹", action: #selector(openInboxFolderInFinder), keyEquivalent: "")
            finderItem.target = self
            menu.addItem(finderItem)
        }

        statusItem.menu = menu
        statusItem.button?.performClick(nil)
        statusItem.menu = nil
    }

    @objc private func openDetailFromMenu() {
        openDetailPanel(selectingItemId: items.first?.itemId)
    }

    @objc private func openWeChatFromMenu() {
        openWeChatDetailTab(articleId: nil)
    }

    @objc private func openMirrorFromMenu() {
        if detailPanel == nil { buildDetailPanelWindow() }
        ipc?.sendJson(["type": "request_sync"])
        selectDetailTab(1)
        showDetailPanel()
    }

    @objc private func clearUnreadBadges() {
        unreadItemIds.removeAll()
        updateStatusBarAppearance()
    }

    @objc private func openInboxFolderInFinder() {
        guard let dir = statusSnapshot.inboxDir, !dir.isEmpty else { return }
        NSWorkspace.shared.open(URL(fileURLWithPath: dir))
    }

    // MARK: - Envelope Dispatch

    private func handleEnvelope(_ env: HudIncomingEnvelope) {
        if let st = env.status {
            let changed = statusSnapshot.mirrorUrl != st.mirrorUrl
            statusSnapshot = st
            if changed {
                destroyMirrorView()
                if selectedDetailTab == 1 && detailPanel?.isVisible == true { loadMirrorView() }
            }
            updateStatusBarAppearance()
        }
        switch env.type {
        case "sync_items":
            if let incoming = env.items {
                items = incoming.sorted { $0.createdAtMs > $1.createdAtMs }
                unreadItemIds.formIntersection(incoming.filter { !$0.processed }.map { $0.itemId })
                if selectedItemId == nil {
                    selectedItemId = items.first?.itemId
                }
                refreshDetailPanelIfVisible()
            }
            updateStatusBarAppearance()

        case "notify":
            if let item = env.item {
                upsertItem(item)
                if !item.processed {
                    unreadItemIds.insert(item.itemId)
                }
            }
            if let list = env.items {
                items = list.sorted { $0.createdAtMs > $1.createdAtMs }
            }
            updateStatusBarAppearance()
            refreshDetailPanelIfVisible()
            if env.toast?.channel == "wechat" && selectedDetailTab == 2 && detailPanel?.isVisible == true {
                fetchWeChatOverviewForHud(selectingArticleId: env.toast?.wechatArticleId)
            }
            if let toast = env.toast {
                let matched = env.item ?? toast.itemId.flatMap { id in items.first(where: { $0.itemId == id }) }
                presentTopRightToast(toast: toast, item: matched)
            }

        case "peek":
            if let item = env.item {
                upsertItem(item)
            }
            let targetId = env.itemId ?? env.item?.itemId ?? items.first?.itemId
            openDetailPanel(selectingItemId: targetId)

        case "peek_wechat":
            openWeChatDetailTab(articleId: env.wechatArticleId)

        default:
            break
        }
    }

    private func upsertItem(_ item: HudInboxItem) {
        if let idx = items.firstIndex(where: { $0.itemId == item.itemId }) {
            items[idx] = item
        } else {
            items.insert(item, at: 0)
        }
        items.sort { $0.createdAtMs > $1.createdAtMs }
        if items.count > 40 {
            items = Array(items.prefix(40))
        }
    }

    // MARK: - Top-Right Non-Activating Toast Card

    private func presentTopRightToast(toast: HudToastPayload, item: HudInboxItem?) {
        toastDismissTimer?.invalidate()
        toastPanel?.orderOut(nil)
        toastPanel = nil
        currentToastItem = item
        currentToastChannel = toast.channel ?? "phone"
        currentToastWeChatArticleId = toast.wechatArticleId
        let isWeChatToast = (currentToastChannel == "wechat")

        if let sndName = toast.soundName, !sndName.isEmpty {
            NSSound(named: NSSound.Name(sndName))?.play()
        }

        let screenFrame = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let cardW: CGFloat = 384
        let cardH: CGFloat = 114
        let originX = screenFrame.maxX - cardW - 16
        let originY = screenFrame.maxY - cardH - 14

        let panel = NonActivatingToastPanel(
            contentRect: NSRect(x: originX, y: originY, width: cardW, height: cardH),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .statusBar
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false

        let root = HoverableToastView(frame: NSRect(x: 0, y: 0, width: cardW, height: cardH))
        root.material = .hudWindow
        root.blendingMode = .behindWindow
        root.state = .active
        root.appearance = NSAppearance(named: .darkAqua)
        root.wantsLayer = true
        root.layer?.cornerRadius = 14
        root.layer?.masksToBounds = true
        root.layer?.borderWidth = 1
        root.layer?.borderColor = isWeChatToast
            ? NSColor(calibratedRed: 0.29, green: 0.87, blue: 0.50, alpha: 0.48).cgColor
            : NSColor(calibratedRed: 0.22, green: 0.74, blue: 0.97, alpha: 0.42).cgColor

        root.onHoverChanged = { [weak self] hovering in
            if hovering {
                self?.toastDismissTimer?.invalidate()
            } else {
                self?.scheduleToastDismiss(after: 2.6)
            }
        }
        root.onCardClicked = { [weak self] in
            self?.openDetailFromToast()
        }

        // Top Header Row
        let brandLabel = NSTextField(labelWithString: isWeChatToast ? "💬 青简·尺素" : "⚡ 青简·咫尺")
        brandLabel.font = NSFont.systemFont(ofSize: 11, weight: .semibold)
        brandLabel.textColor = isWeChatToast
            ? NSColor(calibratedRed: 0.29, green: 0.87, blue: 0.50, alpha: 1.0)
            : NSColor(calibratedRed: 0.22, green: 0.74, blue: 0.97, alpha: 1.0)
        brandLabel.frame = NSRect(x: 14, y: cardH - 26, width: 92, height: 16)
        root.addSubview(brandLabel)

        let subText = toast.subtitle ?? (item != nil ? tagDisplayLabel(item!.intentTag) : "点击卡片查看详情")
        let subLabel = NSTextField(labelWithString: subText)
        subLabel.font = NSFont.systemFont(ofSize: 11, weight: .regular)
        subLabel.textColor = NSColor(calibratedWhite: 0.72, alpha: 1.0)
        subLabel.lineBreakMode = .byTruncatingTail
        subLabel.frame = NSRect(x: 106, y: cardH - 26, width: cardW - 142, height: 16)
        root.addSubview(subLabel)

        let closeBtn = NSButton(title: "✕", target: self, action: #selector(dismissToastClicked))
        closeBtn.isBordered = false
        closeBtn.font = NSFont.systemFont(ofSize: 12, weight: .medium)
        closeBtn.contentTintColor = NSColor(calibratedWhite: 0.68, alpha: 1.0)
        closeBtn.frame = NSRect(x: cardW - 30, y: cardH - 28, width: 20, height: 20)
        root.addSubview(closeBtn)

        // Optional Right Thumbnail / File Badge
        let hasRightPreview = (item?.files.isEmpty == false)
        let textWidth: CGFloat = hasRightPreview ? (cardW - 88) : (cardW - 28)

        if let firstFile = item?.files.first {
            let thumbFrame = NSRect(x: cardW - 64, y: 36, width: 50, height: 50)
            if isImageFile(firstFile), let nsImg = NSImage(contentsOfFile: firstFile.absPath) {
                let imgView = NSImageView(frame: thumbFrame)
                imgView.image = nsImg
                imgView.imageScaling = .scaleProportionallyUpOrDown
                imgView.wantsLayer = true
                imgView.layer?.cornerRadius = 8
                imgView.layer?.masksToBounds = true
                imgView.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.35).cgColor
                root.addSubview(imgView)
            } else {
                let badgeBox = NSView(frame: thumbFrame)
                badgeBox.wantsLayer = true
                badgeBox.layer?.cornerRadius = 10
                badgeBox.layer?.backgroundColor = NSColor(calibratedRed: 0.12, green: 0.20, blue: 0.32, alpha: 0.9).cgColor
                let emoji = NSTextField(labelWithString: iconForFile(firstFile))
                emoji.font = NSFont.systemFont(ofSize: 22)
                emoji.alignment = .center
                emoji.frame = NSRect(x: 0, y: 12, width: 50, height: 28)
                badgeBox.addSubview(emoji)
                root.addSubview(badgeBox)
            }
        }

        // Title & Message
        let titleField = NSTextField(labelWithString: toast.title)
        titleField.font = NSFont.systemFont(ofSize: 13, weight: .bold)
        titleField.textColor = .white
        titleField.lineBreakMode = .byTruncatingTail
        titleField.frame = NSRect(x: 14, y: cardH - 48, width: textWidth, height: 18)
        root.addSubview(titleField)

        let bodyField = NSTextField(wrappingLabelWithString: toast.message.replacingOccurrences(of: "\n", with: " "))
        bodyField.font = NSFont.systemFont(ofSize: 12, weight: .regular)
        bodyField.textColor = NSColor(calibratedWhite: 0.88, alpha: 1.0)
        bodyField.maximumNumberOfLines = 1
        bodyField.cell?.truncatesLastVisibleLine = true
        bodyField.frame = NSRect(x: 14, y: cardH - 68, width: textWidth, height: 17)
        root.addSubview(bodyField)

        // Bottom Action Buttons
        let detailBtn = makePillButton(
            title: isWeChatToast ? "💬 查看伴读" : "🔍 查看详情",
            bg: isWeChatToast
                ? NSColor(calibratedRed: 0.09, green: 0.64, blue: 0.29, alpha: 0.92)
                : NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.92),
            fg: .white,
            action: #selector(openDetailFromToast)
        )
        detailBtn.frame = NSRect(x: 14, y: 10, width: 92, height: 24)
        root.addSubview(detailBtn)

        if item != nil {
            let copyTitle: String
            if item?.otpCode != nil {
                copyTitle = "📋 复制验证码"
            } else if let f = item?.files.first, isImageFile(f) && (item?.textContent ?? "").isEmpty {
                copyTitle = "🖼️ 复制图片"
            } else if let f = item?.files.first, (item?.textContent ?? "").isEmpty {
                copyTitle = "📎 复制文件(\((f.fileName as NSString).pathExtension.uppercased()))"
            } else {
                copyTitle = "📋 复制内容"
            }
            let copyBtn = makePillButton(
                title: copyTitle,
                bg: NSColor(calibratedWhite: 1.0, alpha: 0.12),
                fg: NSColor(calibratedWhite: 0.95, alpha: 1.0),
                action: #selector(copyToastPayloadClicked(_:))
            )
            copyBtn.frame = NSRect(x: 114, y: 10, width: 126, height: 24)
            root.addSubview(copyBtn)
            copyFeedbackButton = copyBtn
        }

        panel.contentView = root
        panel.alphaValue = 0.0
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.18
            panel.animator().alphaValue = 1.0
        }
        toastPanel = panel
        scheduleToastDismiss(after: 5.8)
    }

    private func scheduleToastDismiss(after seconds: TimeInterval) {
        toastDismissTimer?.invalidate()
        toastDismissTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.dismissToastAnimated()
        }
    }

    @objc private func dismissToastClicked() {
        dismissToastAnimated()
    }

    private func dismissToastAnimated() {
        toastDismissTimer?.invalidate()
        guard let panel = toastPanel else { return }
        toastPanel = nil
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.16
            panel.animator().alphaValue = 0.0
        }, completionHandler: {
            panel.orderOut(nil)
        })
    }

    @objc private func openDetailFromToast() {
        let isWeChat = (currentToastChannel == "wechat")
        let wxId = currentToastWeChatArticleId
        let targetId = currentToastItem?.itemId ?? items.first?.itemId
        dismissToastAnimated()
        if isWeChat {
            openWeChatDetailTab(articleId: wxId)
        } else {
            openDetailPanel(selectingItemId: targetId)
        }
    }

    @objc private func copyToastPayloadClicked(_ sender: NSButton) {
        guard let item = currentToastItem else { return }
        if let otp = item.otpCode, !otp.isEmpty {
            copyStringToPasteboard(otp)
        } else if let txt = item.textContent ?? item.voiceTranscript, !txt.isEmpty {
            copyStringToPasteboard(txt)
        } else if let first = item.files.first {
            if isImageFile(first), let img = NSImage(contentsOfFile: first.absPath) {
                copyImageToPasteboard(img, fileUrl: URL(fileURLWithPath: first.absPath))
            } else {
                copyFileToPasteboard(URL(fileURLWithPath: first.absPath))
            }
        }
        sender.title = "✓ 已复制(可⌘V)"
    }

    // MARK: - Inline Quick-Detail Floating Panel (Zero External App!)

    private func toggleDetailPanel() {
        if let panel = detailPanel, panel.isVisible {
            closeDetailPanel()
        } else {
            openDetailPanel(selectingItemId: selectedItemId ?? items.first?.itemId)
        }
    }

    private func closeDetailPanel() {
        closeImagePreview()
        destroyMirrorView()
        closeActiveQLPreview()
        audioPlayer?.stop()
        audioPlayer = nil
        detailPanel?.orderOut(nil)
    }

    func windowDidResignKey(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        if !isDetailPinned && (selectedDetailTab == 0 || selectedDetailTab == 2) && imagePreview == nil {
            closeDetailPanel()
        }
    }

    func windowDidMiniaturize(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        closeImagePreview()
        destroyMirrorView()
    }

    func windowDidDeminiaturize(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        constrainDetailPanelToScreen(maximizeHeight: true)
        if selectedDetailTab == 1 { loadMirrorView() } // Capture still requires an explicit start.
    }

    func windowDidChangeScreen(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        constrainDetailPanelToScreen(maximizeHeight: true)
    }

    func windowWillClose(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        closeImagePreview()
        destroyMirrorView()
        destroyWxReaderView()
        closeActiveQLPreview()
        audioPlayer?.stop()
        audioPlayer = nil
    }

    private func openDetailPanel(selectingItemId targetId: String?) {
        ipc?.sendJson(["type": "request_sync"])
        if let id = targetId {
            selectedItemId = id
            selectedAttachmentIndex = 0
            markItemReadLocally(id)
        } else if selectedItemId == nil {
            selectedItemId = items.first?.itemId
            if let firstId = selectedItemId { markItemReadLocally(firstId) }
        }

        if detailPanel == nil {
            buildDetailPanelWindow()
        }
        selectDetailTab(0)
        showDetailPanel()
    }

    private func showDetailPanel() {
        guard let panel = detailPanel else { return }
        constrainDetailPanelToScreen(center: !panel.isVisible, maximizeHeight: true)
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
    }

    private func constrainDetailPanelToScreen(visibleFrame: NSRect? = nil, center: Bool = false, maximizeHeight: Bool = false) {
        guard let panel = detailPanel,
              let screen = visibleFrame ?? panel.screen?.visibleFrame ?? NSScreen.main?.visibleFrame else { return }
        panel.maxSize = screen.size
        var frame = panel.frame
        frame.size.width = min(frame.width, screen.width)
        frame.size.height = maximizeHeight ? screen.height : min(frame.height, screen.height)
        if center {
            frame.origin = NSPoint(x: screen.midX - frame.width / 2, y: screen.midY - frame.height / 2)
        }
        frame.origin.x = max(screen.minX, min(frame.minX, screen.maxX - frame.width))
        frame.origin.y = max(screen.minY, min(frame.minY, screen.maxY - frame.height))
        if frame != panel.frame { panel.setFrame(frame, display: false) }
    }

    private func markItemReadLocally(_ itemId: String) {
        unreadItemIds.remove(itemId)
        updateStatusBarAppearance()
    }

    private func refreshDetailPanelIfVisible() {
        guard let panel = detailPanel, panel.isVisible else { return }
        if selectedDetailTab == 0 { rebuildSidebarAndInspector(preserveInspector: true) }
    }

    private func buildDetailPanelWindow() {
        let winW: CGFloat = 840
        let winH: CGFloat = 560
        let panel = KeyableDetailPanel(
            contentRect: NSRect(x: 0, y: 0, width: winW, height: winH),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        panel.title = "青简·咫尺 — 投递与手机镜像"
        panel.isReleasedWhenClosed = false
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.hidesOnDeactivate = false
        panel.level = isDetailPinned ? .floating : .normal
        // canJoinAllSpaces and moveToActiveSpace are mutually exclusive in AppKit.
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.minSize = NSSize(width: 680, height: 440)
        panel.appearance = NSAppearance(named: .darkAqua)
        panel.delegate = self
        panel.onEscapePressed = { [weak self] in
            self?.closeDetailPanel()
        }

        let effect = NSVisualEffectView(frame: NSRect(x: 0, y: 0, width: winW, height: winH))
        effect.autoresizingMask = [.width, .height]
        effect.material = .hudWindow
        effect.blendingMode = .behindWindow
        effect.state = .active

        // NSWindow derives its Auto Layout minimum from content constraints.
        // A lazy WebView has no intrinsic height; retain a usable content area.
        NSLayoutConstraint.activate([
            effect.widthAnchor.constraint(greaterThanOrEqualToConstant: 680),
            effect.heightAnchor.constraint(greaterThanOrEqualToConstant: 440)
        ])

        // Top Bar
        let topBar = NSView()
        topBar.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(topBar)

        let headerTitle = NSSegmentedControl(labels: ["📬 最近投递", "📱 手机镜像", "💬 微信尺素"], trackingMode: .selectOne, target: self, action: #selector(detailTabChanged))
        headerTitle.selectedSegment = 0
        headerTitle.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(headerTitle)
        detailTabs = headerTitle

        let pinBtn = makePillButton(
            title: "📌 置顶常驻: 关",
            bg: NSColor(calibratedWhite: 1.0, alpha: 0.10),
            fg: NSColor(calibratedWhite: 0.85, alpha: 1.0),
            action: #selector(togglePinDetailWindow)
        )
        pinBtn.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(pinBtn)
        pinBtn.toolTip = "开启：置顶且切换 App 后保持显示；关闭：投递窗口失焦后收起"
        self.pinButton = pinBtn

        // Horizontal Split: Sidebar (240pt) + Right Inspector
        let divider = NSBox()
        divider.boxType = .separator
        divider.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(divider)

        let inboxHost = NSView()
        let wechatHost = NSView()
        let mirrorHost = NSView()
        for host in [inboxHost, wechatHost, mirrorHost] {
            host.translatesAutoresizingMaskIntoConstraints = false
            effect.addSubview(host)
            NSLayoutConstraint.activate([
                host.topAnchor.constraint(equalTo: divider.bottomAnchor),
                host.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
                host.trailingAnchor.constraint(equalTo: effect.trailingAnchor),
                host.bottomAnchor.constraint(equalTo: effect.bottomAnchor)
            ])
        }
        inboxContent = inboxHost
        wechatContent = wechatHost
        mirrorContent = mirrorHost
        wechatHost.isHidden = true
        mirrorHost.isHidden = true
        buildWeChatSplitView(in: wechatHost)
        let message = NSTextField(wrappingLabelWithString: "手动开始镜像，不会自动唤醒手机")
        message.translatesAutoresizingMaskIntoConstraints = false
        message.alignment = .center
        mirrorHost.addSubview(message)
        mirrorMessage = message
        let retry = NSButton(title: "重新加载", target: self, action: #selector(reloadMirrorView))
        retry.translatesAutoresizingMaskIntoConstraints = false
        mirrorHost.addSubview(retry)
        mirrorRetry = retry
        NSLayoutConstraint.activate([
            message.centerXAnchor.constraint(equalTo: mirrorHost.centerXAnchor),
            message.centerYAnchor.constraint(equalTo: mirrorHost.centerYAnchor, constant: -18),
            message.widthAnchor.constraint(lessThanOrEqualTo: mirrorHost.widthAnchor, constant: -40),
            retry.centerXAnchor.constraint(equalTo: mirrorHost.centerXAnchor),
            retry.topAnchor.constraint(equalTo: message.bottomAnchor, constant: 12)
        ])

        let vDivider = NSBox()
        vDivider.boxType = .separator
        vDivider.translatesAutoresizingMaskIntoConstraints = false
        inboxHost.addSubview(vDivider)

        let scrollSidebar = NSScrollView()
        scrollSidebar.drawsBackground = false
        scrollSidebar.hasVerticalScroller = true
        scrollSidebar.translatesAutoresizingMaskIntoConstraints = false

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 4
        stack.edgeInsets = NSEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let docView = FlippedContainerView()
        docView.translatesAutoresizingMaskIntoConstraints = false
        docView.addSubview(stack)
        scrollSidebar.documentView = docView

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: docView.topAnchor),
            stack.leadingAnchor.constraint(equalTo: docView.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: docView.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: docView.bottomAnchor),
            docView.widthAnchor.constraint(equalTo: scrollSidebar.contentView.widthAnchor)
        ])

        inboxHost.addSubview(scrollSidebar)
        self.sidebarStack = stack

        let inspector = NSView()
        inspector.translatesAutoresizingMaskIntoConstraints = false
        inboxHost.addSubview(inspector)
        self.inspectorContainer = inspector

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: effect.topAnchor, constant: 6),
            topBar.leadingAnchor.constraint(equalTo: effect.leadingAnchor, constant: 76),
            topBar.trailingAnchor.constraint(equalTo: effect.trailingAnchor, constant: -14),
            topBar.heightAnchor.constraint(equalToConstant: 32),

            headerTitle.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            headerTitle.leadingAnchor.constraint(equalTo: topBar.leadingAnchor),

            pinBtn.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            pinBtn.trailingAnchor.constraint(equalTo: topBar.trailingAnchor),
            pinBtn.widthAnchor.constraint(equalToConstant: 112),
            pinBtn.heightAnchor.constraint(equalToConstant: 24),

            divider.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 4),
            divider.heightAnchor.constraint(equalToConstant: 1),
            divider.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            divider.trailingAnchor.constraint(equalTo: effect.trailingAnchor),

            scrollSidebar.topAnchor.constraint(equalTo: inboxHost.topAnchor),
            scrollSidebar.leadingAnchor.constraint(equalTo: inboxHost.leadingAnchor),
            scrollSidebar.bottomAnchor.constraint(equalTo: inboxHost.bottomAnchor),
            scrollSidebar.widthAnchor.constraint(equalToConstant: 245),

            vDivider.topAnchor.constraint(equalTo: inboxHost.topAnchor),
            vDivider.leadingAnchor.constraint(equalTo: scrollSidebar.trailingAnchor),
            vDivider.widthAnchor.constraint(equalToConstant: 1),
            vDivider.bottomAnchor.constraint(equalTo: inboxHost.bottomAnchor),

            inspector.topAnchor.constraint(equalTo: inboxHost.topAnchor),
            inspector.leadingAnchor.constraint(equalTo: vDivider.trailingAnchor),
            inspector.trailingAnchor.constraint(equalTo: inboxHost.trailingAnchor),
            inspector.bottomAnchor.constraint(equalTo: inboxHost.bottomAnchor)
        ])

        panel.contentView = effect
        self.detailPanel = panel
    }

    @objc private func detailTabChanged(_ sender: NSSegmentedControl) {
        selectDetailTab(sender.selectedSegment)
    }

    private func selectDetailTab(_ index: Int) {
        closeImagePreview()
        selectedDetailTab = (0...2).contains(index) ? index : 0
        detailPanel?.level = (selectedDetailTab == 1 || isDetailPinned) ? .floating : .normal
        detailTabs?.selectedSegment = selectedDetailTab
        // Pinning controls auto-dismiss on inbox & wechat tabs; mirror always stays open.
        pinButton?.isHidden = selectedDetailTab == 1
        inboxContent?.isHidden = selectedDetailTab != 0
        mirrorContent?.isHidden = selectedDetailTab != 1
        wechatContent?.isHidden = selectedDetailTab != 2
        if selectedDetailTab == 1 {
            destroyWxReaderView()
            closeActiveQLPreview()
            audioPlayer?.stop()
            audioPlayer = nil
            inspectorContainer?.subviews.forEach { $0.removeFromSuperview() }
            loadMirrorView()
        } else if selectedDetailTab == 2 {
            destroyMirrorView()
            closeActiveQLPreview()
            audioPlayer?.stop()
            audioPlayer = nil
            inspectorContainer?.subviews.forEach { $0.removeFromSuperview() }
            fetchWeChatOverviewForHud(selectingArticleId: selectedWxArticleId)
        } else {
            destroyMirrorView()
            destroyWxReaderView()
            rebuildSidebarAndInspector()
        }
        constrainDetailPanelToScreen(maximizeHeight: true)
    }

    private func openWeChatDetailTab(articleId: Int?) {
        ipc?.sendJson(["type": "request_sync"])
        if let aid = articleId, aid > 0 {
            selectedWxArticleId = aid
        }
        if detailPanel == nil {
            buildDetailPanelWindow()
        }
        selectDetailTab(2)
        showDetailPanel()
    }

    private func buildWeChatSplitView(in host: NSView) {
        let leftHeader = NSView()
        leftHeader.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(leftHeader)

        let refreshBtn = makePillButton(
            title: "🔄 刷新",
            bg: NSColor(calibratedWhite: 1.0, alpha: 0.10),
            fg: .white,
            action: #selector(refreshWeChatHudClicked)
        )
        refreshBtn.translatesAutoresizingMaskIntoConstraints = false
        leftHeader.addSubview(refreshBtn)

        let cfgBtn = makePillButton(
            title: "⚙️ 尺素配置",
            bg: NSColor(calibratedRed: 0.09, green: 0.64, blue: 0.29, alpha: 0.32),
            fg: NSColor(calibratedRed: 0.53, green: 0.94, blue: 0.67, alpha: 1.0),
            action: #selector(openWeChatConfigInBrowser)
        )
        cfgBtn.translatesAutoresizingMaskIntoConstraints = false
        leftHeader.addSubview(cfgBtn)

        let scrollSidebar = NSScrollView()
        scrollSidebar.drawsBackground = false
        scrollSidebar.hasVerticalScroller = true
        scrollSidebar.translatesAutoresizingMaskIntoConstraints = false

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 4
        stack.edgeInsets = NSEdgeInsets(top: 6, left: 8, bottom: 8, right: 8)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let docView = FlippedContainerView()
        docView.translatesAutoresizingMaskIntoConstraints = false
        docView.addSubview(stack)
        scrollSidebar.documentView = docView

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: docView.topAnchor),
            stack.leadingAnchor.constraint(equalTo: docView.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: docView.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: docView.bottomAnchor),
            docView.widthAnchor.constraint(equalTo: scrollSidebar.contentView.widthAnchor)
        ])
        host.addSubview(scrollSidebar)
        self.wxSidebarStack = stack

        let vDivider = NSBox()
        vDivider.boxType = .separator
        vDivider.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(vDivider)

        let inspector = NSView()
        inspector.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(inspector)
        self.wxInspectorContainer = inspector

        NSLayoutConstraint.activate([
            leftHeader.topAnchor.constraint(equalTo: host.topAnchor, constant: 6),
            leftHeader.leadingAnchor.constraint(equalTo: host.leadingAnchor, constant: 8),
            leftHeader.widthAnchor.constraint(equalToConstant: 239),
            leftHeader.heightAnchor.constraint(equalToConstant: 28),

            refreshBtn.leadingAnchor.constraint(equalTo: leftHeader.leadingAnchor),
            refreshBtn.centerYAnchor.constraint(equalTo: leftHeader.centerYAnchor),
            refreshBtn.widthAnchor.constraint(equalToConstant: 84),
            refreshBtn.heightAnchor.constraint(equalToConstant: 24),

            cfgBtn.trailingAnchor.constraint(equalTo: leftHeader.trailingAnchor),
            cfgBtn.centerYAnchor.constraint(equalTo: leftHeader.centerYAnchor),
            cfgBtn.widthAnchor.constraint(equalToConstant: 108),
            cfgBtn.heightAnchor.constraint(equalToConstant: 24),

            scrollSidebar.topAnchor.constraint(equalTo: leftHeader.bottomAnchor, constant: 4),
            scrollSidebar.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            scrollSidebar.bottomAnchor.constraint(equalTo: host.bottomAnchor),
            scrollSidebar.widthAnchor.constraint(equalToConstant: 255),

            vDivider.topAnchor.constraint(equalTo: host.topAnchor),
            vDivider.leadingAnchor.constraint(equalTo: scrollSidebar.trailingAnchor),
            vDivider.widthAnchor.constraint(equalToConstant: 1),
            vDivider.bottomAnchor.constraint(equalTo: host.bottomAnchor),

            inspector.topAnchor.constraint(equalTo: host.topAnchor),
            inspector.leadingAnchor.constraint(equalTo: vDivider.trailingAnchor),
            inspector.trailingAnchor.constraint(equalTo: host.trailingAnchor),
            inspector.bottomAnchor.constraint(equalTo: host.bottomAnchor)
        ])
    }

    private func validatedLoopbackURL(_ raw: String?) -> URL? {
        guard let raw = raw, let c = URLComponents(string: raw),
              c.scheme == "http", c.host == "127.0.0.1", c.user == nil, c.password == nil,
              let port = c.port, (1...65535).contains(port)
        else { return nil }
        return c.url
    }

    @objc private func refreshWeChatHudClicked() {
        fetchWeChatOverviewForHud(selectingArticleId: selectedWxArticleId)
    }

    @objc private func openWeChatConfigInBrowser() {
        let raw = statusSnapshot.wechatConfigUrl ?? "http://127.0.0.1:19527/wechat/config"
        if let url = validatedLoopbackURL(raw) {
            NSWorkspace.shared.open(url)
        }
    }

    private func fetchWeChatOverviewForHud(selectingArticleId: Int?) {
        let baseRaw = statusSnapshot.wechatApiBaseUrl ?? "http://127.0.0.1:19527/api/wechat"
        guard let url = validatedLoopbackURL("\(baseRaw)/overview?limit=50") else { return }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
        req.httpMethod = "GET"
        URLSession.shared.dataTask(with: req) { [weak self] data, _, _ in
            guard let self = self, let data = data,
                  let decoded = try? JSONDecoder().decode(HudWeChatOverviewResponse.self, from: data),
                  decoded.ok else { return }
            DispatchQueue.main.async {
                self.wxArticles = decoded.articles ?? []
                if let explicit = selectingArticleId, explicit > 0 {
                    self.selectedWxArticleId = explicit
                } else if self.selectedWxArticleId == nil {
                    self.selectedWxArticleId = self.wxArticles.first?.id
                }
                self.rebuildWeChatSidebar()
                if let aid = self.selectedWxArticleId {
                    self.fetchWeChatArticleDetailForHud(articleId: aid)
                } else {
                    self.renderWeChatEmptyInspector("暂无微信转发文章记录")
                }
            }
        }.resume()
    }

    private func fetchWeChatArticleDetailForHud(articleId: Int) {
        let baseRaw = statusSnapshot.wechatApiBaseUrl ?? "http://127.0.0.1:19527/api/wechat"
        guard let url = validatedLoopbackURL("\(baseRaw)/article?id=\(articleId)") else { return }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
        req.httpMethod = "GET"
        URLSession.shared.dataTask(with: req) { [weak self] data, _, _ in
            guard let self = self, let data = data,
                  let decoded = try? JSONDecoder().decode(HudWeChatArticleDetailResponse.self, from: data),
                  decoded.ok else { return }
            DispatchQueue.main.async {
                self.currentWxDetail = decoded
                self.renderWeChatInspector()
            }
        }.resume()
    }

    private func rebuildWeChatSidebar() {
        guard let stack = wxSidebarStack else { return }
        stack.arrangedSubviews.forEach { v in
            stack.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        if wxArticles.isEmpty {
            let empty = NSTextField(wrappingLabelWithString: "暂无微信文章记录\n在手机微信转发公众号、视频或网页给 ClawBot 即可自动归档")
            empty.font = NSFont.systemFont(ofSize: 12)
            empty.textColor = NSColor(calibratedWhite: 0.62, alpha: 1.0)
            empty.alignment = .center
            stack.addArrangedSubview(empty)
            return
        }
        for art in wxArticles {
            let isSelected = (art.id == selectedWxArticleId)
            let row = ClickableRowView()
            row.translatesAutoresizingMaskIntoConstraints = false
            row.wantsLayer = true
            row.layer?.cornerRadius = 8
            row.layer?.backgroundColor = isSelected
                ? NSColor(calibratedRed: 0.16, green: 0.72, blue: 0.40, alpha: 0.28).cgColor
                : NSColor(calibratedWhite: 1.0, alpha: 0.04).cgColor
            row.layer?.borderWidth = isSelected ? 1 : 0
            row.layer?.borderColor = NSColor(calibratedRed: 0.29, green: 0.87, blue: 0.50, alpha: 0.65).cgColor
            let targetId = art.id
            row.onClick = { [weak self] in
                self?.selectedWxArticleId = targetId
                self?.rebuildWeChatSidebar()
                self?.fetchWeChatArticleDetailForHud(articleId: targetId)
            }

            let badgeIcon = art.process_level == "deep" ? "💎" : (art.triage == "deep" ? "🔥" : (art.triage == "reference" ? "📎" : "📌"))
            let topMeta = NSTextField(labelWithString: "\(badgeIcon) #\(art.id) · \(String(art.created_at.prefix(10)))")
            topMeta.isSelectable = false
            topMeta.font = NSFont.systemFont(ofSize: 10.5, weight: .medium)
            topMeta.textColor = NSColor(calibratedWhite: 0.66, alpha: 1.0)
            topMeta.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(topMeta)

            let titleLbl = NSTextField(wrappingLabelWithString: art.title.isEmpty ? art.url : art.title)
            titleLbl.isSelectable = false
            titleLbl.font = NSFont.systemFont(ofSize: 12.5, weight: isSelected ? .semibold : .medium)
            titleLbl.textColor = .white
            titleLbl.maximumNumberOfLines = 2
            titleLbl.cell?.truncatesLastVisibleLine = true
            titleLbl.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(titleLbl)

            stack.addArrangedSubview(row)
            NSLayoutConstraint.activate([
                row.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -16),
                topMeta.topAnchor.constraint(equalTo: row.topAnchor, constant: 7),
                topMeta.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 9),
                topMeta.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -9),
                titleLbl.topAnchor.constraint(equalTo: topMeta.bottomAnchor, constant: 3),
                titleLbl.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 9),
                titleLbl.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -9),
                titleLbl.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -8)
            ])
        }
    }

    private func destroyWxReaderView() {
        guard let web = wxReaderWebView else { return }
        wxReaderWebView = nil
        wxReaderBaseURL = nil
        web.onEscapePressed = nil
        web.navigationDelegate = nil
        web.uiDelegate = nil
        web.configuration.userContentController.removeScriptMessageHandler(forName: "omsImagePreview")
        web.removeFromSuperview()
        web.loadHTMLString("", baseURL: nil)
    }

    private func validatedWxLoopbackOriginURL() -> URL {
        let baseRaw = statusSnapshot.wechatApiBaseUrl ?? "http://127.0.0.1:19527/api/wechat"
        if let valid = validatedLoopbackURL(baseRaw),
           var comps = URLComponents(url: valid, resolvingAgainstBaseURL: false) {
            comps.path = "/"
            comps.query = nil
            comps.fragment = nil
            if let rootUrl = comps.url {
                return rootUrl
            }
        }
        return URL(string: "http://127.0.0.1:19527/")!
    }

    private func buildWeChatReaderDocumentHtml(fragmentHtml: String) -> String {
        return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8"/>
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src http://127.0.0.1:* data:; style-src 'unsafe-inline'; script-src 'unsafe-inline';"/>
        <style>
          * { box-sizing: border-box; }
          html, body {
            margin: 0;
            padding: 0;
            background: transparent;
            color: #E2E8F0;
            font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", sans-serif;
            font-size: 14px;
            line-height: 1.78;
            word-break: break-word;
            -webkit-font-smoothing: antialiased;
          }
          body { padding: 16px 20px 24px 20px; }
          .wx-rich-reader > *:first-child { margin-top: 0; }
          .wx-rich-reader > *:last-child { margin-bottom: 0; }
          .wx-meta-strip {
            display: flex;
            flex-wrap: wrap;
            gap: 7px;
            margin-bottom: 14px;
            padding-bottom: 10px;
            border-bottom: 1px dashed rgba(255,255,255,0.12);
          }
          .wx-meta-chip {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            font-size: 11px;
            padding: 2px 8px;
            border-radius: 999px;
            background: rgba(255,255,255,0.06);
            border: 1px solid rgba(255,255,255,0.10);
            color: #94A3B8;
            text-decoration: none;
          }
          .wx-meta-link { color: #38BDF8; border-color: rgba(56,189,248,0.38); }
          .wx-rich-reader h1, .wx-rich-reader h2, .wx-rich-reader h3, .wx-rich-reader h4 {
            color: #F8FAFC;
            font-weight: 700;
            line-height: 1.38;
            margin: 20px 0 9px 0;
          }
          .wx-rich-reader h1 { font-size: 18px; }
          .wx-rich-reader h2 { font-size: 15.5px; padding-bottom: 5px; border-bottom: 1px solid rgba(255,255,255,0.10); color: #7DD3FC; }
          .wx-rich-reader h3 { font-size: 14.5px; color: #BAE6FD; }
          .wx-rich-reader .wx-p { margin: 0 0 12px 0; color: #E2E8F0; }
          .wx-rich-reader strong { color: #FFFFFF; font-weight: 700; }
          .wx-rich-reader .wx-sec-num {
            display: inline-block;
            margin: 16px 0 6px 0;
            padding: 2px 9px;
            border-radius: 6px;
            background: rgba(56, 189, 248, 0.15);
            border: 1px solid rgba(56, 189, 248, 0.35);
            color: #38BDF8;
            font-weight: 700;
            font-size: 12px;
          }
          .wx-rich-reader .wx-list { margin: 0 0 13px 0; padding-left: 20px; }
          .wx-rich-reader .wx-list li { margin-bottom: 5px; }
          .wx-rich-reader .wx-quote {
            margin: 12px 0;
            padding: 9px 13px;
            border-left: 3px solid #38BDF8;
            background: rgba(255,255,255,0.04);
            border-radius: 0 8px 8px 0;
            color: #CBD5E1;
          }
          .wx-rich-reader .wx-quote p { margin: 0 0 5px 0; }
          .wx-rich-reader .wx-quote p:last-child { margin-bottom: 0; }
          .wx-rich-reader .wx-inline-code {
            background: rgba(255,255,255,0.10);
            color: #FDE68A;
            padding: 1px 5px;
            border-radius: 4px;
            font-size: 12px;
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
          }
          .wx-rich-reader .wx-code-wrap {
            margin: 12px 0;
            background: rgba(3, 6, 12, 0.85);
            border: 1px solid rgba(255,255,255,0.10);
            border-radius: 8px;
            overflow: hidden;
          }
          .wx-rich-reader .wx-code-lang {
            padding: 3px 10px;
            font-size: 10.5px;
            color: #94A3B8;
            background: rgba(255,255,255,0.05);
            border-bottom: 1px solid rgba(255,255,255,0.08);
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
          }
          .wx-rich-reader .wx-code {
            margin: 0;
            padding: 10px 12px;
            overflow-x: auto;
            font-size: 12px;
            line-height: 1.5;
            color: #E2E8F0;
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
          }
          .wx-rich-reader .wx-hr {
            border: none;
            border-top: 1px solid rgba(255,255,255,0.10);
            margin: 16px 0;
          }
          .wx-rich-reader .wx-figure {
            margin: 14px 0;
            text-align: center;
          }
          .wx-rich-reader .wx-inline-img {
            display: inline-block;
            max-width: 100%;
            max-height: 460px;
            border-radius: 9px;
            border: 1px solid rgba(255,255,255,0.12);
            background: rgba(0,0,0,0.35);
            cursor: zoom-in;
            transition: border-color 0.15s;
          }
          .wx-rich-reader .wx-inline-img:hover {
            border-color: #38BDF8;
          }
          .wx-rich-reader .wx-figcaption {
            margin-top: 5px;
            font-size: 11.5px;
            color: #94A3B8;
          }
          .wx-rich-reader .wx-link { color: #38BDF8; text-decoration: underline; text-underline-offset: 2px; }
          .wx-rich-reader .wx-wikilink {
            display: inline-block;
            padding: 0 5px;
            border-radius: 4px;
            background: rgba(74, 222, 128, 0.14);
            color: #86EFAC;
            font-size: 12px;
          }
          .wx-rich-reader .wx-ext-img-pill, .wx-rich-reader .wx-missing-img {
            display: inline-block;
            margin: 4px 0;
            padding: 3px 9px;
            border-radius: 6px;
            background: rgba(255,255,255,0.06);
            border: 1px solid rgba(255,255,255,0.12);
            color: #94A3B8;
            font-size: 11.5px;
            text-decoration: none;
          }
          .wx-rich-reader .wx-table-wrap { margin: 12px 0; overflow-x: auto; }
          .wx-rich-reader .wx-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
          .wx-rich-reader .wx-table th, .wx-rich-reader .wx-table td {
            border: 1px solid rgba(255,255,255,0.12);
            padding: 6px 9px;
            text-align: left;
          }
          .wx-rich-reader .wx-table th { background: rgba(255,255,255,0.06); color: #F8FAFC; font-weight: 600; }
        </style>
        </head>
        <body>
        \(fragmentHtml)
        <script>
        document.addEventListener('click', function(ev) {
          var t = ev.target;
          var img = t && t.closest ? t.closest('img.wx-inline-img') : null;
          if (!img) return;
          ev.preventDefault();
          var key = img.getAttribute('data-img-key') || '';
          var rel = img.getAttribute('data-rel-path') || '';
          var alt = img.getAttribute('alt') || '文章配图';
          if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.omsImagePreview) {
            window.webkit.messageHandlers.omsImagePreview.postMessage({ key: key, rel: rel, alt: alt });
          }
        });
        </script>
        </body>
        </html>
        """
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "omsImagePreview",
              message.webView === wxReaderWebView,
              let payload = message.body as? [String: String],
              let map = currentWxDetail?.resolvedImages else { return }
        let key = payload["key"] ?? ""
        let rel = payload["rel"] ?? ""
        guard let absPath = map[key] ?? map[rel],
              !absPath.isEmpty,
              FileManager.default.fileExists(atPath: absPath),
              let nsImage = NSImage(contentsOfFile: absPath) else { return }
        let rawAlt = (payload["alt"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let previewTitle = (!rawAlt.isEmpty && rawAlt != "文章配图" && rawAlt.lowercased() != "image")
            ? rawAlt
            : (absPath as NSString).lastPathComponent
        openImagePreview(image: nsImage, title: previewTitle)
    }

    private func renderWeChatEmptyInspector(_ text: String) {
        destroyWxReaderView()
        guard let box = wxInspectorContainer else { return }
        box.subviews.forEach { $0.removeFromSuperview() }
        let lbl = NSTextField(wrappingLabelWithString: text)
        lbl.font = NSFont.systemFont(ofSize: 13)
        lbl.textColor = NSColor(calibratedWhite: 0.65, alpha: 1.0)
        lbl.alignment = .center
        lbl.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(lbl)
        NSLayoutConstraint.activate([
            lbl.centerXAnchor.constraint(equalTo: box.centerXAnchor),
            lbl.centerYAnchor.constraint(equalTo: box.centerYAnchor)
        ])
    }

    private func renderWeChatInspector() {
        destroyWxReaderView()
        guard let box = wxInspectorContainer, let detail = currentWxDetail, let art = detail.article else {
            renderWeChatEmptyInspector("选择左侧微信文章查看伴读与原文")
            return
        }
        box.subviews.forEach { $0.removeFromSuperview() }

        let titleField = NSTextField(wrappingLabelWithString: "#\(art.id) \(art.title.isEmpty ? "未命名文章" : art.title)")
        titleField.font = NSFont.systemFont(ofSize: 15, weight: .bold)
        titleField.textColor = .white
        titleField.maximumNumberOfLines = 2
        titleField.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(titleField)

        let metaText = [
            art.author.isEmpty ? nil : "✍️ \(art.author)",
            "📊 \(art.status) (\(art.process_level))",
            art.user_notes.isEmpty ? nil : "💬 批注: \(art.user_notes)"
        ].compactMap { $0 }.joined(separator: "  ·  ")
        let metaField = NSTextField(labelWithString: metaText)
        metaField.font = NSFont.systemFont(ofSize: 11.5)
        metaField.textColor = NSColor(calibratedWhite: 0.72, alpha: 1.0)
        metaField.lineBreakMode = .byTruncatingTail
        metaField.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(metaField)

        let actionRow = NSStackView()
        actionRow.orientation = .horizontal
        actionRow.spacing = 7
        actionRow.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(actionRow)

        let docSeg = NSSegmentedControl(labels: ["📒 伴读笔记", "💡 初筛摘要", "📄 归档原文"], trackingMode: .selectOne, target: self, action: #selector(wxDocSegmentChanged(_:)))
        docSeg.selectedSegment = selectedWxDocSegment
        actionRow.addArrangedSubview(docSeg)

        let phoneBtn = makePillButton(
            title: "📱 推到手机朗读",
            bg: NSColor(calibratedRed: 0.09, green: 0.64, blue: 0.29, alpha: 0.88),
            fg: .white,
            action: #selector(pushSelectedWeChatToPhoneClicked(_:))
        )
        phoneBtn.widthAnchor.constraint(equalToConstant: 114).isActive = true
        phoneBtn.heightAnchor.constraint(equalToConstant: 24).isActive = true
        actionRow.addArrangedSubview(phoneBtn)

        let copyBtn = makePillButton(
            title: "📋 复制",
            bg: NSColor(calibratedWhite: 1.0, alpha: 0.12),
            fg: .white,
            action: #selector(copySelectedWeChatContentClicked(_:))
        )
        copyBtn.widthAnchor.constraint(equalToConstant: 66).isActive = true
        copyBtn.heightAnchor.constraint(equalToConstant: 24).isActive = true
        actionRow.addArrangedSubview(copyBtn)

        let modeBtn = makePillButton(
            title: isWxRawSourceMode ? "📖 阅读" : "</> 源码",
            bg: isWxRawSourceMode
                ? NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.75)
                : NSColor(calibratedWhite: 1.0, alpha: 0.10),
            fg: .white,
            action: #selector(toggleWxRawSourceClicked)
        )
        modeBtn.widthAnchor.constraint(equalToConstant: 72).isActive = true
        modeBtn.heightAnchor.constraint(equalToConstant: 24).isActive = true
        actionRow.addArrangedSubview(modeBtn)

        let targetFinderPath = selectedWxDocSegment == 2
            ? (!art.source_md_path.isEmpty ? art.source_md_path : art.note_md_path)
            : (!art.note_md_path.isEmpty ? art.note_md_path : art.source_md_path)
        if !targetFinderPath.isEmpty {
            let revealBtn = makePillButton(
                title: "📂 Finder",
                bg: NSColor(calibratedWhite: 1.0, alpha: 0.10),
                fg: .white,
                action: #selector(revealSelectedWeChatNoteInFinder)
            )
            revealBtn.widthAnchor.constraint(equalToConstant: 74).isActive = true
            revealBtn.heightAnchor.constraint(equalToConstant: 24).isActive = true
            actionRow.addArrangedSubview(revealBtn)
        }

        let bodyContent: String
        let richHtml: String?
        switch selectedWxDocSegment {
        case 1:
            bodyContent = art.short_summary.isEmpty ? "（暂无初筛摘要）" : art.short_summary
            richHtml = detail.summaryHtml
        case 2:
            bodyContent = detail.sourceMarkdown ?? "（本地原文文件不存在或尚未抓取）"
            richHtml = detail.sourceHtml
        default:
            bodyContent = detail.noteMarkdown ?? (art.short_summary.isEmpty ? "（暂无伴读笔记）" : art.short_summary)
            richHtml = detail.noteHtml ?? detail.summaryHtml
        }

        let contentViewBox: NSView
        if !isWxRawSourceMode, let htmlFrag = richHtml, !htmlFrag.isEmpty {
            let readerHost = NSView()
            readerHost.wantsLayer = true
            readerHost.layer?.backgroundColor = NSColor(calibratedRed: 0.05, green: 0.07, blue: 0.12, alpha: 0.88).cgColor
            readerHost.layer?.cornerRadius = 10
            readerHost.layer?.borderWidth = 1
            readerHost.layer?.borderColor = NSColor(calibratedWhite: 1.0, alpha: 0.10).cgColor
            readerHost.layer?.masksToBounds = true
            readerHost.translatesAutoresizingMaskIntoConstraints = false

            let config = WKWebViewConfiguration()
            config.websiteDataStore = .nonPersistent()
            config.preferences.javaScriptCanOpenWindowsAutomatically = false
            config.userContentController.add(WeakScriptMessageHandler(self), name: "omsImagePreview")

            let web = MirrorWebView(frame: .zero, configuration: config)
            web.setValue(false, forKey: "drawsBackground")
            web.navigationDelegate = self
            web.uiDelegate = self
            web.onEscapePressed = { [weak self] in self?.closeDetailPanel() }
            web.translatesAutoresizingMaskIntoConstraints = false
            readerHost.addSubview(web)
            NSLayoutConstraint.activate([
                web.topAnchor.constraint(equalTo: readerHost.topAnchor),
                web.bottomAnchor.constraint(equalTo: readerHost.bottomAnchor),
                web.leadingAnchor.constraint(equalTo: readerHost.leadingAnchor),
                web.trailingAnchor.constraint(equalTo: readerHost.trailingAnchor)
            ])
            let baseUrl = validatedWxLoopbackOriginURL()
            self.wxReaderBaseURL = baseUrl
            self.wxReaderWebView = web
            web.loadHTMLString(buildWeChatReaderDocumentHtml(fragmentHtml: htmlFrag), baseURL: baseUrl)
            box.addSubview(readerHost)
            contentViewBox = readerHost
        } else {
            let scroll = NSScrollView()
            scroll.drawsBackground = true
            scroll.backgroundColor = NSColor(calibratedRed: 0.05, green: 0.07, blue: 0.12, alpha: 0.88)
            scroll.hasVerticalScroller = true
            scroll.wantsLayer = true
            scroll.layer?.cornerRadius = 10
            scroll.layer?.borderWidth = 1
            scroll.layer?.borderColor = NSColor(calibratedWhite: 1.0, alpha: 0.10).cgColor
            scroll.translatesAutoresizingMaskIntoConstraints = false

            let textView = NSTextView()
            textView.isEditable = false
            textView.isSelectable = true
            textView.drawsBackground = false
            textView.textColor = NSColor(calibratedWhite: 0.94, alpha: 1.0)
            textView.font = NSFont.monospacedSystemFont(ofSize: 12.5, weight: .regular)
            textView.textContainerInset = NSSize(width: 12, height: 12)
            textView.isVerticallyResizable = true
            textView.isHorizontallyResizable = false
            textView.autoresizingMask = [.width]
            textView.textContainer?.widthTracksTextView = true
            textView.string = bodyContent
            scroll.documentView = textView
            box.addSubview(scroll)
            contentViewBox = scroll
        }

        NSLayoutConstraint.activate([
            titleField.topAnchor.constraint(equalTo: box.topAnchor, constant: 12),
            titleField.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 14),
            titleField.trailingAnchor.constraint(equalTo: box.trailingAnchor, constant: -14),

            metaField.topAnchor.constraint(equalTo: titleField.bottomAnchor, constant: 4),
            metaField.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 14),
            metaField.trailingAnchor.constraint(equalTo: box.trailingAnchor, constant: -14),

            actionRow.topAnchor.constraint(equalTo: metaField.bottomAnchor, constant: 10),
            actionRow.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 14),

            contentViewBox.topAnchor.constraint(equalTo: actionRow.bottomAnchor, constant: 10),
            contentViewBox.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 14),
            contentViewBox.trailingAnchor.constraint(equalTo: box.trailingAnchor, constant: -14),
            contentViewBox.bottomAnchor.constraint(equalTo: box.bottomAnchor, constant: -14)
        ])
    }

    @objc private func toggleWxRawSourceClicked() {
        isWxRawSourceMode.toggle()
        renderWeChatInspector()
    }

    @objc private func wxDocSegmentChanged(_ sender: NSSegmentedControl) {
        selectedWxDocSegment = sender.selectedSegment
        renderWeChatInspector()
    }

    @objc private func pushSelectedWeChatToPhoneClicked(_ sender: NSButton) {
        guard let art = currentWxDetail?.article else { return }
        ipc?.sendJson([
            "type": "push_wechat_to_phone",
            "articleId": art.id,
            "title": art.title
        ])
        sender.title = "✓ 已请求同步"
    }

    @objc private func copySelectedWeChatContentClicked(_ sender: NSButton) {
        guard let detail = currentWxDetail, let art = detail.article else { return }
        let txt: String
        switch selectedWxDocSegment {
        case 1: txt = art.short_summary
        case 2: txt = detail.sourceMarkdown ?? ""
        default: txt = detail.noteMarkdown ?? art.short_summary
        }
        if !txt.isEmpty {
            copyStringToPasteboard(txt)
            sender.title = "✓ 已复制"
        }
    }

    @objc private func revealSelectedWeChatNoteInFinder() {
        guard let art = currentWxDetail?.article else { return }
        let path = selectedWxDocSegment == 2
            ? (!art.source_md_path.isEmpty ? art.source_md_path : art.note_md_path)
            : (!art.note_md_path.isEmpty ? art.note_md_path : art.source_md_path)
        guard !path.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }

    private func validatedMirrorURL() -> URL? {
        guard let raw = statusSnapshot.mirrorUrl, let c = URLComponents(string: raw),
              c.scheme == "http", c.host == "127.0.0.1", c.user == nil, c.password == nil,
              let port = c.port, (1...65535).contains(port), c.path == "/mirror", c.fragment == nil,
              c.queryItems?.count == 1, c.queryItems?.first?.name == "hud", c.queryItems?.first?.value == "1"
        else { return nil }
        return c.url
    }

    @objc private func reloadMirrorView() {
        destroyMirrorView()
        if selectedDetailTab == 1 { loadMirrorView() }
    }

    private func loadMirrorView() {
        guard mirrorWebView == nil, let host = mirrorContent else { return }
        guard let url = validatedMirrorURL() else {
            mirrorMessage?.stringValue = "镜像入口不可用，请确认 Mac 服务已连接并更新"
            return
        }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let web = MirrorWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.onEscapePressed = { [weak self] in self?.closeDetailPanel() }
        web.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(web)
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: host.topAnchor), web.bottomAnchor.constraint(equalTo: host.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: host.leadingAnchor), web.trailingAnchor.constraint(equalTo: host.trailingAnchor)
        ])
        mirrorLoadedURL = url
        mirrorWebView = web
        mirrorMessage?.stringValue = "正在加载手机镜像…"
        mirrorRetry?.isHidden = true
        web.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10))
    }

    private func destroyMirrorView() {
        guard let web = mirrorWebView else { return }
        mirrorWebView = nil
        mirrorLoadedURL = nil
        web.onEscapePressed = nil
        web.navigationDelegate = nil
        web.uiDelegate = nil
        web.removeFromSuperview()
        // Unloading the document also closes sockets if its JS context is unavailable.
        web.evaluateJavaScript("window.hostDeactivate && window.hostDeactivate()", completionHandler: nil)
        // Do not wait for a suspended/crashed JS context before unloading its document.
        web.load(URLRequest(url: URL(string: "about:blank")!))
        mirrorMessage?.stringValue = "本窗口观看已结束；其他观看窗口不受影响"
        mirrorRetry?.isHidden = false
    }

    private func mirrorPageFailed(_ webView: WKWebView, message: String) {
        guard webView === mirrorWebView else { return }
        destroyMirrorView()
        mirrorMessage?.stringValue = message
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if webView === wxReaderWebView {
            if navigationAction.navigationType == .linkActivated,
               let url = navigationAction.request.url,
               let scheme = url.scheme?.lowercased(),
               (scheme == "https" || scheme == "http") {
                NSWorkspace.shared.open(url)
                decisionHandler(.cancel)
                return
            }
            let isInitialLoad = navigationAction.navigationType == .other &&
                navigationAction.targetFrame?.isMainFrame == true &&
                (navigationAction.request.url == wxReaderBaseURL || navigationAction.request.url?.absoluteString == "about:blank")
            decisionHandler(isInitialLoad ? .allow : .cancel)
            return
        }
        let allowed = webView === mirrorWebView && navigationAction.targetFrame?.isMainFrame == true && navigationAction.request.url == mirrorLoadedURL
        decisionHandler(allowed ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if webView === wxReaderWebView {
            decisionHandler(.allow)
            return
        }
        let response = navigationResponse.response as? HTTPURLResponse
        let allowed = webView === mirrorWebView && response?.statusCode == 200 && response?.mimeType == "text/html"
        decisionHandler(allowed ? .allow : .cancel)
        if !allowed { mirrorPageFailed(webView, message: "镜像页面加载失败，请确认 Mac 服务后重新加载") }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        mirrorPageFailed(webView, message: "无法连接本机镜像页面，请重新加载")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        mirrorPageFailed(webView, message: "镜像页面加载中断，请重新加载")
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        mirrorPageFailed(webView, message: "镜像页面已退出，请重新加载后手动开始")
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        return nil
    }

    @objc private func togglePinDetailWindow() {
        isDetailPinned.toggle()
        detailPanel?.level = (isDetailPinned || selectedDetailTab == 1) ? .floating : .normal
        pinButton?.title = isDetailPinned ? "📌 置顶常驻: 开" : "📌 置顶常驻: 关"
        pinButton?.layer?.backgroundColor = isDetailPinned
            ? NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.85).cgColor
            : NSColor(calibratedWhite: 1.0, alpha: 0.10).cgColor
    }

    private func closeImagePreview() {
        guard let viewer = imagePreview else { return }
        imagePreview = nil
        viewer.onClose = nil
        if let window = viewer.window { detailPanel?.removeChildWindow(window) }
        viewer.close()
    }

    private func openImagePreview(image: NSImage, title: String) {
        guard let parent = detailPanel,
              let screen = parent.screen?.visibleFrame ?? NSScreen.main?.visibleFrame else { return }
        closeImagePreview()
        let viewer = ImagePreviewController(image: image, title: title, screen: screen)
        imagePreview = viewer
        viewer.onClose = { [weak self, weak viewer] in
            guard let self = self else { return }
            if let window = viewer?.window { self.detailPanel?.removeChildWindow(window) }
            self.imagePreview = nil
            self.detailPanel?.makeKeyAndOrderFront(nil)
        }
        if let window = viewer.window {
            window.alphaValue = parent.alphaValue
            parent.addChildWindow(window, ordered: .above)
            window.makeKeyAndOrderFront(nil)
            viewer.fitImage()
        }
    }

    private func rebuildSidebarAndInspector(preserveInspector: Bool = false) {
        guard let stack = sidebarStack else { return }
        stack.arrangedSubviews.forEach { v in
            stack.removeArrangedSubview(v)
            v.removeFromSuperview()
        }

        if items.isEmpty {
            let emptyLabel = NSTextField(wrappingLabelWithString: "📭 暂无手机投递记录\n在手机端选相册图、选 PDF/Word 或控制中心点「青简·发Mac」即可实时同步。")
            emptyLabel.font = NSFont.systemFont(ofSize: 12)
            emptyLabel.textColor = NSColor(calibratedWhite: 0.65, alpha: 1.0)
            stack.addArrangedSubview(emptyLabel)
        } else {
            if selectedItemId == nil || !items.contains(where: { $0.itemId == selectedItemId }) {
                selectedItemId = items.first?.itemId
                selectedAttachmentIndex = 0
            }
            for item in items.prefix(25) {
                let row = buildSidebarRow(for: item, isSelected: item.itemId == selectedItemId)
                stack.addArrangedSubview(row)
                row.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -16).isActive = true
            }
        }

        // Background sync must not reset text selection, document scroll or playback.
        if preserveInspector && currentSelectedItem() == renderedInspectorItem &&
            selectedAttachmentIndex == renderedAttachmentIndex && inspectorContainer?.subviews.isEmpty == false { return }
        rebuildInspectorPane()
    }

    private func buildSidebarRow(for item: HudInboxItem, isSelected: Bool) -> NSView {
        let row = ClickableRowView()
        row.translatesAutoresizingMaskIntoConstraints = false
        row.wantsLayer = true
        row.layer?.cornerRadius = 8
        row.layer?.backgroundColor = isSelected
            ? NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.42).cgColor
            : NSColor(calibratedWhite: 1.0, alpha: 0.04).cgColor

        let rawTitle = (item.title?.isEmpty == false ? item.title! : (item.files.first?.fileName ?? item.textContent ?? "手机投递"))
            .replacingOccurrences(of: "\n", with: " ")
        let unreadDot = unreadItemIds.contains(item.itemId) ? "🔵 " : ""
        let titleLbl = NSTextField(labelWithString: "\(unreadDot)\(iconForItem(item)) \(rawTitle)")
        titleLbl.font = NSFont.systemFont(ofSize: 12, weight: isSelected ? .bold : .medium)
        titleLbl.textColor = .white
        titleLbl.lineBreakMode = .byTruncatingTail
        titleLbl.translatesAutoresizingMaskIntoConstraints = false

        let metaLbl = NSTextField(labelWithString: "\(formatTimeLabel(item.createdAtMs)) · #\(item.intentTag)")
        metaLbl.font = NSFont.systemFont(ofSize: 10.5)
        metaLbl.textColor = NSColor(calibratedWhite: 0.65, alpha: 1.0)
        metaLbl.lineBreakMode = .byTruncatingTail
        metaLbl.translatesAutoresizingMaskIntoConstraints = false

        row.addSubview(titleLbl)
        row.addSubview(metaLbl)

        NSLayoutConstraint.activate([
            row.heightAnchor.constraint(equalToConstant: 46),
            titleLbl.topAnchor.constraint(equalTo: row.topAnchor, constant: 6),
            titleLbl.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 8),
            titleLbl.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -8),
            metaLbl.topAnchor.constraint(equalTo: titleLbl.bottomAnchor, constant: 2),
            metaLbl.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 8),
            metaLbl.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -8)
        ])

        let itemId = item.itemId
        row.onClick = { [weak self] in
            guard let self = self else { return }
            self.selectedItemId = itemId
            self.selectedAttachmentIndex = 0
            self.markItemReadLocally(itemId)
            self.rebuildSidebarAndInspector()
        }
        return row
    }

    private func closeActiveQLPreview() {
        if let ql = activeQLPreview {
            ql.previewItem = nil
            ql.close()
            ql.removeFromSuperview()
            activeQLPreview = nil
        }
    }

    private func rebuildInspectorPane() {
        closeActiveQLPreview()
        audioPlayer?.stop()
        audioPlayer = nil

        guard let container = inspectorContainer else { return }
        renderedInspectorItem = currentSelectedItem()
        renderedAttachmentIndex = selectedAttachmentIndex
        container.subviews.forEach { $0.removeFromSuperview() }

        guard let currentId = selectedItemId,
              let item = items.first(where: { $0.itemId == currentId }) else {
            let hint = NSTextField(labelWithString: "在左侧选择一条手机投递即可在此直接查看文字、图片、PDF 或 Word 文档")
            hint.font = NSFont.systemFont(ofSize: 13)
            hint.textColor = NSColor(calibratedWhite: 0.65, alpha: 1.0)
            hint.translatesAutoresizingMaskIntoConstraints = false
            container.addSubview(hint)
            NSLayoutConstraint.activate([
                hint.centerXAnchor.constraint(equalTo: container.centerXAnchor),
                hint.centerYAnchor.constraint(equalTo: container.centerYAnchor)
            ])
            return
        }

        let vStack = NSStackView()
        vStack.orientation = .vertical
        vStack.alignment = .leading
        vStack.spacing = 10
        vStack.edgeInsets = NSEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        vStack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(vStack)

        // 1. Header Title & Metadata
        let displayTitle = item.title ?? item.files.first?.fileName ?? "手机投递内容"
        let titleLbl = NSTextField(wrappingLabelWithString: displayTitle)
        titleLbl.font = NSFont.systemFont(ofSize: 15, weight: .bold)
        titleLbl.textColor = .white
        titleLbl.maximumNumberOfLines = 2
        vStack.addArrangedSubview(titleLbl)

        let statusBadge = item.processed ? "✓ 已处理" : "● 待处理"
        let subInfo = "\(tagDisplayLabel(item.intentTag))  ·  \(formatTimeLabel(item.createdAtMs))  ·  \(statusBadge)"
        let subLbl = NSTextField(labelWithString: subInfo)
        subLbl.font = NSFont.systemFont(ofSize: 11.5)
        subLbl.textColor = NSColor(calibratedRed: 0.58, green: 0.77, blue: 0.99, alpha: 1.0)
        vStack.addArrangedSubview(subLbl)

        // 2. OTP Banner if applicable
        if let otp = item.otpCode, !otp.isEmpty {
            let otpCard = NSView()
            otpCard.translatesAutoresizingMaskIntoConstraints = false
            otpCard.wantsLayer = true
            otpCard.layer?.cornerRadius = 10
            otpCard.layer?.backgroundColor = NSColor(calibratedRed: 0.08, green: 0.33, blue: 0.18, alpha: 0.65).cgColor

            let otpText = NSTextField(labelWithString: "🔑 验证码：\(otp)")
            otpText.font = NSFont.monospacedDigitSystemFont(ofSize: 16, weight: .bold)
            otpText.textColor = NSColor(calibratedRed: 0.29, green: 0.87, blue: 0.50, alpha: 1.0)
            otpText.translatesAutoresizingMaskIntoConstraints = false
            otpCard.addSubview(otpText)

            let copyOtpBtn = makePillButton(
                title: "一键复制验证码",
                bg: NSColor(calibratedRed: 0.09, green: 0.64, blue: 0.29, alpha: 0.95),
                fg: .white,
                action: #selector(copyCurrentOtp)
            )
            copyOtpBtn.translatesAutoresizingMaskIntoConstraints = false
            otpCard.addSubview(copyOtpBtn)

            NSLayoutConstraint.activate([
                otpCard.heightAnchor.constraint(equalToConstant: 40),
                otpText.centerYAnchor.constraint(equalTo: otpCard.centerYAnchor),
                otpText.leadingAnchor.constraint(equalTo: otpCard.leadingAnchor, constant: 12),
                copyOtpBtn.centerYAnchor.constraint(equalTo: otpCard.centerYAnchor),
                copyOtpBtn.trailingAnchor.constraint(equalTo: otpCard.trailingAnchor, constant: -10),
                copyOtpBtn.widthAnchor.constraint(equalToConstant: 118),
                copyOtpBtn.heightAnchor.constraint(equalToConstant: 26)
            ])
            vStack.addArrangedSubview(otpCard)
            otpCard.widthAnchor.constraint(equalTo: vStack.widthAnchor, constant: -32).isActive = true
        }

        // 3. Full Selectable Text / Voice Transcript Area
        var bodySegments: [String] = []
        if let txt = item.textContent, !txt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            bodySegments.append(txt)
        }
        if let vt = item.voiceTranscript, !vt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, vt != item.textContent {
            bodySegments.append("🎙️ 语音离线转写：\n\(vt)")
        }
        if !bodySegments.isEmpty {
            let fullBody = bodySegments.joined(separator: "\n\n")
            let textScroll = buildReadonlySelectableTextScroll(text: fullBody)
            let desiredH: CGFloat = item.files.isEmpty ? 300 : min(110, max(54, CGFloat(fullBody.count / 2)))
            textScroll.heightAnchor.constraint(greaterThanOrEqualToConstant: desiredH).isActive = true
            if !item.files.isEmpty {
                textScroll.heightAnchor.constraint(lessThanOrEqualToConstant: 130).isActive = true
            }
            vStack.addArrangedSubview(textScroll)
            textScroll.widthAnchor.constraint(equalTo: vStack.widthAnchor, constant: -32).isActive = true
        }

        // 4. Multi-Attachment Switcher Pills (if > 1 attachment)
        if item.files.count > 1 {
            let tabScroll = NSScrollView()
            tabScroll.drawsBackground = false
            tabScroll.hasHorizontalScroller = false
            tabScroll.translatesAutoresizingMaskIntoConstraints = false
            tabScroll.heightAnchor.constraint(equalToConstant: 30).isActive = true

            let tabRow = NSStackView()
            tabRow.orientation = .horizontal
            tabRow.spacing = 6
            tabRow.translatesAutoresizingMaskIntoConstraints = false

            for (idx, f) in item.files.enumerated() {
                let active = idx == selectedAttachmentIndex
                let btn = makePillButton(
                    title: "\(iconForFile(f)) \(f.fileName) (\(formatByteSize(f.sizeBytes)))",
                    bg: active
                        ? NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.9)
                        : NSColor(calibratedWhite: 1.0, alpha: 0.10),
                    fg: .white,
                    action: #selector(selectAttachmentTab(_:))
                )
                btn.tag = idx
                btn.translatesAutoresizingMaskIntoConstraints = false
                btn.heightAnchor.constraint(equalToConstant: 26).isActive = true
                tabRow.addArrangedSubview(btn)
            }
            tabScroll.documentView = tabRow
            vStack.addArrangedSubview(tabScroll)
            tabScroll.widthAnchor.constraint(equalTo: vStack.widthAnchor, constant: -32).isActive = true
        }

        // 5. Inline File Viewer (Image / PDF / Audio / PlainText / Word-Excel-PPT QuickLook!)
        let validIdx = min(max(0, selectedAttachmentIndex), max(0, item.files.count - 1))
        let activeFile: HudStoredFile? = item.files.isEmpty ? nil : item.files[validIdx]

        if let file = activeFile {
            let previewHolder = buildInlineFilePreview(for: file)
            previewHolder.setContentHuggingPriority(.defaultLow, for: .vertical)
            vStack.addArrangedSubview(previewHolder)
            previewHolder.widthAnchor.constraint(equalTo: vStack.widthAnchor, constant: -32).isActive = true
            previewHolder.heightAnchor.constraint(greaterThanOrEqualToConstant: 210).isActive = true
        } else {
            let spacer = NSView()
            spacer.translatesAutoresizingMaskIntoConstraints = false
            vStack.addArrangedSubview(spacer)
        }

        // 6. Bottom Action Bar (Copy Text / Copy Image / Copy File for ⌘V / Reveal in Finder / Mark Read / Close)
        let actionBar = NSStackView()
        actionBar.orientation = .horizontal
        actionBar.spacing = 8
        actionBar.translatesAutoresizingMaskIntoConstraints = false

        if !bodySegments.isEmpty {
            let copyTxtBtn = makePillButton(
                title: "📋 复制全文",
                bg: NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.9),
                fg: .white,
                action: #selector(copyCurrentTextClicked(_:))
            )
            copyTxtBtn.heightAnchor.constraint(equalToConstant: 28).isActive = true
            actionBar.addArrangedSubview(copyTxtBtn)
        }

        if let file = activeFile {
            if isImageFile(file) {
                let copyImgBtn = makePillButton(
                    title: "🖼️ 复制图片(直接⌘V贴图)",
                    bg: NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.9),
                    fg: .white,
                    action: #selector(copyCurrentImageClicked(_:))
                )
                copyImgBtn.heightAnchor.constraint(equalToConstant: 28).isActive = true
                actionBar.addArrangedSubview(copyImgBtn)
            }

            let copyFileBtn = makePillButton(
                title: "📎 复制原文件(微信/文档直接⌘V)",
                bg: NSColor(calibratedRed: 0.09, green: 0.64, blue: 0.29, alpha: 0.88),
                fg: .white,
                action: #selector(copyCurrentFileClicked(_:))
            )
            copyFileBtn.heightAnchor.constraint(equalToConstant: 28).isActive = true
            actionBar.addArrangedSubview(copyFileBtn)

            let finderBtn = makePillButton(
                title: "📂 Finder定位",
                bg: NSColor(calibratedWhite: 1.0, alpha: 0.12),
                fg: .white,
                action: #selector(revealCurrentFileInFinder)
            )
            finderBtn.heightAnchor.constraint(equalToConstant: 28).isActive = true
            actionBar.addArrangedSubview(finderBtn)
        }

        if !item.processed {
            let readBtn = makePillButton(
                title: "✓ 标为已处理",
                bg: NSColor(calibratedWhite: 1.0, alpha: 0.12),
                fg: NSColor(calibratedWhite: 0.9, alpha: 1.0),
                action: #selector(markCurrentItemProcessed)
            )
            readBtn.heightAnchor.constraint(equalToConstant: 28).isActive = true
            actionBar.addArrangedSubview(readBtn)
        }

        let closeActionBtn = makePillButton(
            title: "关闭 (Esc)",
            bg: NSColor(calibratedWhite: 1.0, alpha: 0.08),
            fg: NSColor(calibratedWhite: 0.8, alpha: 1.0),
            action: #selector(closeDetailButtonClicked)
        )
        closeActionBtn.heightAnchor.constraint(equalToConstant: 28).isActive = true
        actionBar.addArrangedSubview(closeActionBtn)

        // Keep all actions reachable without forcing the window wider than the screen.
        let actionScroll = NSScrollView()
        actionScroll.drawsBackground = false
        actionScroll.hasHorizontalScroller = true
        actionScroll.autohidesScrollers = true
        actionScroll.translatesAutoresizingMaskIntoConstraints = false
        actionScroll.documentView = actionBar
        vStack.addArrangedSubview(actionScroll)
        NSLayoutConstraint.activate([
            actionScroll.widthAnchor.constraint(equalTo: vStack.widthAnchor, constant: -32),
            actionScroll.heightAnchor.constraint(equalToConstant: 44)
        ])

        NSLayoutConstraint.activate([
            vStack.topAnchor.constraint(equalTo: container.topAnchor),
            vStack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            vStack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            vStack.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])
    }

    private func buildReadonlySelectableTextScroll(text: String) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.drawsBackground = true
        scroll.backgroundColor = NSColor(calibratedRed: 0.06, green: 0.09, blue: 0.16, alpha: 0.85)
        scroll.hasVerticalScroller = true
        scroll.wantsLayer = true
        scroll.layer?.cornerRadius = 10
        scroll.layer?.borderWidth = 1
        scroll.layer?.borderColor = NSColor(calibratedWhite: 1.0, alpha: 0.10).cgColor
        scroll.translatesAutoresizingMaskIntoConstraints = false

        let tv = NSTextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.drawsBackground = false
        tv.textColor = NSColor(calibratedWhite: 0.95, alpha: 1.0)
        tv.font = NSFont.systemFont(ofSize: 13)
        tv.textContainerInset = NSSize(width: 10, height: 10)
        tv.isVerticallyResizable = true
        tv.isHorizontallyResizable = false
        tv.autoresizingMask = [.width]
        tv.textContainer?.widthTracksTextView = true
        tv.string = text
        scroll.documentView = tv
        return scroll
    }

    private func buildInlineFilePreview(for file: HudStoredFile) -> NSView {
        let wrapper = NSView()
        wrapper.translatesAutoresizingMaskIntoConstraints = false
        wrapper.wantsLayer = true
        wrapper.layer?.cornerRadius = 10
        wrapper.layer?.masksToBounds = true
        wrapper.layer?.backgroundColor = NSColor(calibratedRed: 0.04, green: 0.07, blue: 0.13, alpha: 0.90).cgColor
        wrapper.layer?.borderWidth = 1
        wrapper.layer?.borderColor = NSColor(calibratedWhite: 1.0, alpha: 0.10).cgColor

        guard FileManager.default.fileExists(atPath: file.absPath) else {
            let missing = NSTextField(labelWithString: "⚠️ 本地附件文件不存在：\(file.fileName)")
            missing.textColor = .systemOrange
            missing.translatesAutoresizingMaskIntoConstraints = false
            wrapper.addSubview(missing)
            NSLayoutConstraint.activate([
                missing.centerXAnchor.constraint(equalTo: wrapper.centerXAnchor),
                missing.centerYAnchor.constraint(equalTo: wrapper.centerYAnchor)
            ])
            return wrapper
        }

        let fileUrl = URL(fileURLWithPath: file.absPath)

        if isImageFile(file), let img = NSImage(contentsOf: fileUrl) {
            let imgView = ScaledPreviewImageView()
            imgView.image = img
            imgView.imageScaling = .scaleProportionallyUpOrDown
            imgView.toolTip = "点击放大查看图片"
            imgView.setAccessibilityLabel("放大查看图片")
            imgView.onOpen = { [weak self] in self?.openImagePreview(image: img, title: file.fileName) }
            imgView.translatesAutoresizingMaskIntoConstraints = false
            wrapper.addSubview(imgView)
            NSLayoutConstraint.activate([
                imgView.topAnchor.constraint(equalTo: wrapper.topAnchor, constant: 6),
                imgView.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor, constant: 6),
                imgView.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor, constant: -6),
                imgView.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor, constant: -6)
            ])
            return wrapper
        }

        if isPdfFile(file), let doc = PDFDocument(url: fileUrl) {
            let pdfView = PDFView()
            pdfView.document = doc
            pdfView.autoScales = true
            pdfView.displayMode = .singlePageContinuous
            pdfView.displaysPageBreaks = true
            pdfView.translatesAutoresizingMaskIntoConstraints = false
            wrapper.addSubview(pdfView)
            NSLayoutConstraint.activate([
                pdfView.topAnchor.constraint(equalTo: wrapper.topAnchor),
                pdfView.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                pdfView.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
                pdfView.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor)
            ])
            return wrapper
        }

        if isAudioFile(file) {
            let infoLbl = NSTextField(labelWithString: "🎙️ 语音附件：\(file.fileName) (\(formatByteSize(file.sizeBytes)))")
            infoLbl.font = NSFont.systemFont(ofSize: 13, weight: .medium)
            infoLbl.textColor = .white
            infoLbl.translatesAutoresizingMaskIntoConstraints = false

            let playBtn = makePillButton(
                title: "▶️ 播放原声录音",
                bg: NSColor(calibratedRed: 0.96, green: 0.62, blue: 0.04, alpha: 0.9),
                fg: .black,
                action: #selector(toggleAudioPlayback(_:))
            )
            playBtn.translatesAutoresizingMaskIntoConstraints = false
            self.audioPlayButton = playBtn

            wrapper.addSubview(infoLbl)
            wrapper.addSubview(playBtn)
            NSLayoutConstraint.activate([
                infoLbl.centerXAnchor.constraint(equalTo: wrapper.centerXAnchor),
                infoLbl.centerYAnchor.constraint(equalTo: wrapper.centerYAnchor, constant: -18),
                playBtn.centerXAnchor.constraint(equalTo: wrapper.centerXAnchor),
                playBtn.topAnchor.constraint(equalTo: infoLbl.bottomAnchor, constant: 12),
                playBtn.widthAnchor.constraint(equalToConstant: 148),
                playBtn.heightAnchor.constraint(equalToConstant: 32)
            ])
            return wrapper
        }

        if isPlainTextFile(file),
           let fh = try? FileHandle(forReadingFrom: fileUrl) {
            let data = (try? fh.read(upToCount: 262_144)) ?? Data()
            try? fh.close()
            if let content = String(data: data, encoding: .utf8) {
                let textScroll = buildReadonlySelectableTextScroll(text: content)
                wrapper.addSubview(textScroll)
                NSLayoutConstraint.activate([
                    textScroll.topAnchor.constraint(equalTo: wrapper.topAnchor),
                    textScroll.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                    textScroll.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
                    textScroll.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor)
                ])
                return wrapper
            }
        }

        // Office Documents (.docx, .doc, .xlsx, .xls, .pptx, .ppt, .rtf, .zip, etc.) -> Native macOS QuickLook!
        if let ql = QLPreviewView(frame: NSRect(x: 0, y: 0, width: 520, height: 300), style: .normal) {
            ql.autostarts = true
            ql.shouldCloseWithWindow = false
            ql.previewItem = fileUrl as NSURL
            ql.translatesAutoresizingMaskIntoConstraints = false
            wrapper.addSubview(ql)
            NSLayoutConstraint.activate([
                ql.topAnchor.constraint(equalTo: wrapper.topAnchor),
                ql.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                ql.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
                ql.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor)
            ])
            self.activeQLPreview = ql
            return wrapper
        }

        let fallback = NSTextField(labelWithString: "\(iconForFile(file)) \(file.fileName) · \(formatByteSize(file.sizeBytes))")
        fallback.textColor = .white
        fallback.translatesAutoresizingMaskIntoConstraints = false
        wrapper.addSubview(fallback)
        NSLayoutConstraint.activate([
            fallback.centerXAnchor.constraint(equalTo: wrapper.centerXAnchor),
            fallback.centerYAnchor.constraint(equalTo: wrapper.centerYAnchor)
        ])
        return wrapper
    }

    // MARK: - Inspector Actions

    private func currentSelectedItem() -> HudInboxItem? {
        guard let id = selectedItemId else { return nil }
        return items.first(where: { $0.itemId == id })
    }

    private func currentSelectedFile() -> HudStoredFile? {
        guard let item = currentSelectedItem(), !item.files.isEmpty else { return nil }
        let idx = min(max(0, selectedAttachmentIndex), item.files.count - 1)
        return item.files[idx]
    }

    @objc private func selectAttachmentTab(_ sender: NSButton) {
        selectedAttachmentIndex = sender.tag
        rebuildInspectorPane()
    }

    @objc private func copyCurrentOtp() {
        guard let otp = currentSelectedItem()?.otpCode else { return }
        copyStringToPasteboard(otp)
    }

    @objc private func copyCurrentTextClicked(_ sender: NSButton) {
        guard let item = currentSelectedItem() else { return }
        let parts = [item.textContent, item.voiceTranscript].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        if !parts.isEmpty {
            copyStringToPasteboard(parts.joined(separator: "\n\n"))
            sender.title = "✓ 已复制全文"
        }
    }

    @objc private func copyCurrentImageClicked(_ sender: NSButton) {
        guard let file = currentSelectedFile(),
              let img = NSImage(contentsOfFile: file.absPath) else { return }
        copyImageToPasteboard(img, fileUrl: URL(fileURLWithPath: file.absPath))
        sender.title = "✓ 已复制图片(可⌘V)"
    }

    @objc private func copyCurrentFileClicked(_ sender: NSButton) {
        guard let file = currentSelectedFile() else { return }
        copyFileToPasteboard(URL(fileURLWithPath: file.absPath))
        sender.title = "✓ 已复制文件(可⌘V)"
    }

    @objc private func revealCurrentFileInFinder() {
        guard let file = currentSelectedFile() else { return }
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: file.absPath)])
    }

    @objc private func markCurrentItemProcessed() {
        guard let id = selectedItemId else { return }
        // The host persists this change and returns sync_items. Only that snapshot
        // may update the processed badge; queued/written IPC is not confirmation.
        ipc.sendJson(["type": "mark_processed", "itemId": id])
    }

    @objc private func closeDetailButtonClicked() {
        closeDetailPanel()
    }

    @objc private func toggleAudioPlayback(_ sender: NSButton) {
        if let player = audioPlayer, player.isPlaying {
            player.stop()
            audioPlayer = nil
            sender.title = "▶️ 播放原声录音"
            return
        }
        guard let file = currentSelectedFile() else { return }
        do {
            let player = try AVAudioPlayer(contentsOf: URL(fileURLWithPath: file.absPath))
            player.delegate = self
            player.prepareToPlay()
            player.play()
            audioPlayer = player
            sender.title = "⏹ 停止播放"
        } catch {
            sender.title = "⚠️ 无法播放音频"
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        audioPlayButton?.title = "▶️ 播放原声录音"
    }

    // MARK: - Pasteboard Helpers

    private func copyStringToPasteboard(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
    }

    private func copyImageToPasteboard(_ image: NSImage, fileUrl: URL) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects([image, fileUrl as NSURL])
    }

    private func copyFileToPasteboard(_ fileUrl: URL) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects([fileUrl as NSURL])
    }

    private func makePillButton(title: String, bg: NSColor, fg: NSColor, action: Selector) -> NSButton {
        let btn = NSButton(title: title, target: self, action: action)
        btn.isBordered = false
        btn.wantsLayer = true
        btn.layer?.cornerRadius = 7
        btn.layer?.backgroundColor = bg.cgColor
        btn.font = NSFont.systemFont(ofSize: 11.5, weight: .semibold)
        btn.contentTintColor = fg
        let attr = NSMutableAttributedString(string: title)
        let style = NSMutableParagraphStyle()
        style.alignment = .center
        attr.addAttributes([
            .foregroundColor: fg,
            .font: NSFont.systemFont(ofSize: 11.5, weight: .semibold),
            .paragraphStyle: style
        ], range: NSRange(location: 0, length: (title as NSString).length))
        btn.attributedTitle = attr
        return btn
    }
}

final class FlippedContainerView: NSView {
    override var isFlipped: Bool { true }
}

// MARK: - Entry Point

var parsedSocketPath = ""
let args = CommandLine.arguments
var idx = 1
while idx < args.count {
    if args[idx] == "--socket" && idx + 1 < args.count {
        parsedSocketPath = args[idx + 1]
        idx += 2
    } else {
        idx += 1
    }
}

if parsedSocketPath.isEmpty {
    fputs("Usage: oms-mac-hud --socket <unix_socket_path>\n", stderr)
    exit(2)
}

let parentPid = getppid()
let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = OmsMacHudAppDelegate(socketPath: parsedSocketPath, parentPid: parentPid)
app.delegate = delegate
app.run()
