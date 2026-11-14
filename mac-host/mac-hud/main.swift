import Cocoa
import PDFKit
import Quartz
import AVFoundation
import Darwin

// MARK: - Wire Models

struct HudStoredFile: Codable {
    let fileName: String
    let absPath: String
    let mimeType: String
    let sizeBytes: Int
    let sha256: String?
}

struct HudInboxItem: Codable {
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
}

struct HudStatusSnapshot: Codable {
    let activeTier: String?
    let phoneName: String?
    let inboxDir: String?
}

struct HudIncomingEnvelope: Codable {
    let type: String
    let items: [HudInboxItem]?
    let item: HudInboxItem?
    let toast: HudToastPayload?
    let status: HudStatusSnapshot?
    let itemId: String?
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
        onEscapePressed?()
    }

    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 { // Escape
            onEscapePressed?()
            return
        }
        super.keyDown(with: event)
    }
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
    override func mouseDown(with event: NSEvent) {
        onClick?()
    }
}

// MARK: - Unix Socket Client

final class HudIpcClient {
    private let socketPath: String
    private let ioQueue = DispatchQueue(label: "com.oms.hud.ipc")
    private var sockFD: Int32 = -1
    private var running = true
    var onEnvelope: ((HudIncomingEnvelope) -> Void)?

    init(socketPath: String) {
        self.socketPath = socketPath
    }

    func start() {
        ioQueue.async { [weak self] in
            self?.connectLoop()
        }
    }

    func stop() {
        running = false
        if sockFD >= 0 {
            Darwin.close(sockFD)
            sockFD = -1
        }
    }

    func sendJson(_ obj: [String: Any]) {
        ioQueue.async { [weak self] in
            guard let self = self, self.sockFD >= 0 else { return }
            guard let data = try? JSONSerialization.data(withJSONObject: obj, options: []) else { return }
            var framed = data
            framed.append(0x0A)
            _ = writeAllFD(self.sockFD, framed)
        }
    }

    private func connectLoop() {
        let decoder = JSONDecoder()
        while running {
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

            sockFD = fd
            var buffer = Data()
            var readBuf = [UInt8](repeating: 0, count: 16384)
            while running {
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
            if sockFD == fd {
                sockFD = -1
            }
            Darwin.close(fd)
            if running {
                Thread.sleep(forTimeInterval: 1.0)
            }
        }
    }
}

// MARK: - Main HUD Application Controller

final class OmsMacHudAppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, AVAudioPlayerDelegate {
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
    private var copyFeedbackButton: NSButton?

    // Detail panel state
    private var detailPanel: KeyableDetailPanel?
    private var isDetailPinned = false
    private var selectedItemId: String?
    private var selectedAttachmentIndex = 0
    private var activeQLPreview: QLPreviewView?
    private var audioPlayer: AVAudioPlayer?
    private weak var audioPlayButton: NSButton?

    // Subviews inside detail panel
    private var sidebarStack: NSStackView?
    private var inspectorContainer: NSView?
    private var pinButton: NSButton?

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
        ipc.start()

        watchdogTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let currentPpid = getppid()
            if currentPpid <= 1 || currentPpid != self.initialParentPid {
                self.cleanupAndExit()
            }
        }
    }

    private func cleanupAndExit() {
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
        let tierDesc = tier == "DISCONNECTED" ? "⚪ 手机当前未连接" : "🟢 已连接 (\(tier))"
        let statusEntry = NSMenuItem(title: tierDesc, action: nil, keyEquivalent: "")
        statusEntry.isEnabled = false
        menu.addItem(statusEntry)
        menu.addItem(NSMenuItem.separator())

        let openItem = NSMenuItem(title: "📬 打开投递详情浮窗 (\(items.count) 条)", action: #selector(openDetailFromMenu), keyEquivalent: "")
        openItem.target = self
        menu.addItem(openItem)

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
            statusSnapshot = st
        }
        switch env.type {
        case "sync_items":
            if let incoming = env.items {
                items = incoming.sorted { $0.createdAtMs > $1.createdAtMs }
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
        root.layer?.borderColor = NSColor(calibratedRed: 0.22, green: 0.74, blue: 0.97, alpha: 0.42).cgColor

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
        let brandLabel = NSTextField(labelWithString: "⚡ 青简·咫尺")
        brandLabel.font = NSFont.systemFont(ofSize: 11, weight: .semibold)
        brandLabel.textColor = NSColor(calibratedRed: 0.22, green: 0.74, blue: 0.97, alpha: 1.0)
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
            title: "🔍 查看详情",
            bg: NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.92),
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
        let targetId = currentToastItem?.itemId ?? items.first?.itemId
        dismissToastAnimated()
        openDetailPanel(selectingItemId: targetId)
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
        closeActiveQLPreview()
        audioPlayer?.stop()
        audioPlayer = nil
        detailPanel?.orderOut(nil)
    }

    func windowDidResignKey(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        if !isDetailPinned {
            closeDetailPanel()
        }
    }

    func windowWillClose(_ notification: Notification) {
        guard let win = notification.object as? NSWindow, win === detailPanel else { return }
        closeActiveQLPreview()
        audioPlayer?.stop()
        audioPlayer = nil
    }

    private func openDetailPanel(selectingItemId targetId: String?) {
        ipc.sendJson(["type": "request_sync"])
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
        rebuildSidebarAndInspector()

        guard let panel = detailPanel else { return }
        if !panel.isVisible {
            if let screen = NSScreen.main?.visibleFrame {
                let w = panel.frame.width
                let h = panel.frame.height
                let x = screen.midX - w / 2.0
                let y = screen.midY - h / 2.0 + 40
                panel.setFrameOrigin(NSPoint(x: x, y: y))
            }
        }
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
    }

    private func markItemReadLocally(_ itemId: String) {
        unreadItemIds.remove(itemId)
        updateStatusBarAppearance()
    }

    private func refreshDetailPanelIfVisible() {
        guard let panel = detailPanel, panel.isVisible else { return }
        rebuildSidebarAndInspector()
    }

    private func buildDetailPanelWindow() {
        let winW: CGFloat = 840
        let winH: CGFloat = 560
        let panel = KeyableDetailPanel(
            contentRect: NSRect(x: 0, y: 0, width: winW, height: winH),
            styleMask: [.titled, .closable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        panel.title = "青简·咫尺 — 手机投递速览"
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .moveToActiveSpace]
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

        // Top Bar
        let topBar = NSView()
        topBar.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(topBar)

        let headerTitle = NSTextField(labelWithString: "⚡ 青简·咫尺 · 手机投递内联预览（Esc 即关 · 零外部应用）")
        headerTitle.font = NSFont.systemFont(ofSize: 13, weight: .semibold)
        headerTitle.textColor = NSColor(calibratedWhite: 0.92, alpha: 1.0)
        headerTitle.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(headerTitle)

        let pinBtn = makePillButton(
            title: "📌 钉住窗口: 关",
            bg: NSColor(calibratedWhite: 1.0, alpha: 0.10),
            fg: NSColor(calibratedWhite: 0.85, alpha: 1.0),
            action: #selector(togglePinDetailWindow)
        )
        pinBtn.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(pinBtn)
        self.pinButton = pinBtn

        // Horizontal Split: Sidebar (240pt) + Right Inspector
        let divider = NSBox()
        divider.boxType = .separator
        divider.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(divider)

        let vDivider = NSBox()
        vDivider.boxType = .separator
        vDivider.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(vDivider)

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

        effect.addSubview(scrollSidebar)
        self.sidebarStack = stack

        let inspector = NSView()
        inspector.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(inspector)
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
            divider.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            divider.trailingAnchor.constraint(equalTo: effect.trailingAnchor),

            scrollSidebar.topAnchor.constraint(equalTo: divider.bottomAnchor),
            scrollSidebar.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            scrollSidebar.bottomAnchor.constraint(equalTo: effect.bottomAnchor),
            scrollSidebar.widthAnchor.constraint(equalToConstant: 245),

            vDivider.topAnchor.constraint(equalTo: divider.bottomAnchor),
            vDivider.leadingAnchor.constraint(equalTo: scrollSidebar.trailingAnchor),
            vDivider.bottomAnchor.constraint(equalTo: effect.bottomAnchor),

            inspector.topAnchor.constraint(equalTo: divider.bottomAnchor),
            inspector.leadingAnchor.constraint(equalTo: vDivider.trailingAnchor),
            inspector.trailingAnchor.constraint(equalTo: effect.trailingAnchor),
            inspector.bottomAnchor.constraint(equalTo: effect.bottomAnchor)
        ])

        panel.contentView = effect
        self.detailPanel = panel
    }

    @objc private func togglePinDetailWindow() {
        isDetailPinned.toggle()
        pinButton?.title = isDetailPinned ? "📌 钉住窗口: 开" : "📌 钉住窗口: 关"
        pinButton?.layer?.backgroundColor = isDetailPinned
            ? NSColor(calibratedRed: 0.01, green: 0.52, blue: 0.78, alpha: 0.85).cgColor
            : NSColor(calibratedWhite: 1.0, alpha: 0.10).cgColor
    }

    private func rebuildSidebarAndInspector() {
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

        let statusBadge = item.processed ? "✓ 已处理" : "● 未读"
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

        vStack.addArrangedSubview(actionBar)

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
            let imgView = NSImageView()
            imgView.image = img
            imgView.imageScaling = .scaleProportionallyUpOrDown
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
        if let idx = items.firstIndex(where: { $0.itemId == id }) {
            items[idx].processed = true
        }
        unreadItemIds.remove(id)
        updateStatusBarAppearance()
        ipc.sendJson(["type": "mark_processed", "itemId": id])
        rebuildSidebarAndInspector()
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
