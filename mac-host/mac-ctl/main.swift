import AppKit
import ApplicationServices
import CoreGraphics
import Darwin
import Foundation
import IOKit.pwr_mgt
import QuartzCore

@_silgen_name("CGSessionCopyCurrentDictionary")
func CGSessionCopyCurrentDictionary() -> CFDictionary?

func isScreenLocked() -> Bool {
    guard let dict = CGSessionCopyCurrentDictionary() as? [String: Any] else {
        return false
    }
    if let locked = dict["CGSSessionScreenIsLocked"] as? Bool, locked {
        return true
    }
    if let lockedNum = dict["CGSSessionScreenIsLocked"] as? NSNumber, lockedNum.boolValue {
        return true
    }
    return false
}

func checkAccessibilityTrusted(prompt: Bool = false) -> Bool {
    if prompt {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        let opts = [key: true] as CFDictionary
        return AXIsProcessTrustedWithOptions(opts)
    }
    return AXIsProcessTrusted()
}

func wakeDisplayNative() {
    var assertionID: IOPMAssertionID = 0
    _ = IOPMAssertionDeclareUserActivity(
        "OneMoreStep Remote Wake & Unlock" as CFString,
        kIOPMUserActiveLocal,
        &assertionID
    )
    let task = Process()
    task.executableURL = URL(fileURLWithPath: "/usr/bin/caffeinate")
    task.arguments = ["-u", "-t", "5"]
    try? task.run()
}

func lockScreenNow() -> Bool {
    let frameworkPath = "/System/Library/PrivateFrameworks/login.framework/Versions/Current/login"
    if let handle = dlopen(frameworkPath, RTLD_NOW),
       let sym = dlsym(handle, "SACLockScreenImmediate") {
        typealias LockFn = @convention(c) () -> Void
        let fn = unsafeBitCast(sym, to: LockFn.self)
        fn()
        dlclose(handle)
        return true
    }
    return false
}

// Complete US ANSI Hardware CGKeyCode Table for macOS loginwindow / LockScreen Secure Input
private struct KeyStrokeSpec {
    let keyCode: CGKeyCode
    let shift: Bool
}

private let ansiKeyMap: [Character: KeyStrokeSpec] = [
    "a": .init(keyCode: 0, shift: false), "A": .init(keyCode: 0, shift: true),
    "s": .init(keyCode: 1, shift: false), "S": .init(keyCode: 1, shift: true),
    "d": .init(keyCode: 2, shift: false), "D": .init(keyCode: 2, shift: true),
    "f": .init(keyCode: 3, shift: false), "F": .init(keyCode: 3, shift: true),
    "h": .init(keyCode: 4, shift: false), "H": .init(keyCode: 4, shift: true),
    "g": .init(keyCode: 5, shift: false), "G": .init(keyCode: 5, shift: true),
    "z": .init(keyCode: 6, shift: false), "Z": .init(keyCode: 6, shift: true),
    "x": .init(keyCode: 7, shift: false), "X": .init(keyCode: 7, shift: true),
    "c": .init(keyCode: 8, shift: false), "C": .init(keyCode: 8, shift: true),
    "v": .init(keyCode: 9, shift: false), "V": .init(keyCode: 9, shift: true),
    "b": .init(keyCode: 11, shift: false), "B": .init(keyCode: 11, shift: true),
    "q": .init(keyCode: 12, shift: false), "Q": .init(keyCode: 12, shift: true),
    "w": .init(keyCode: 13, shift: false), "W": .init(keyCode: 13, shift: true),
    "e": .init(keyCode: 14, shift: false), "E": .init(keyCode: 14, shift: true),
    "r": .init(keyCode: 15, shift: false), "R": .init(keyCode: 15, shift: true),
    "y": .init(keyCode: 16, shift: false), "Y": .init(keyCode: 16, shift: true),
    "t": .init(keyCode: 17, shift: false), "T": .init(keyCode: 17, shift: true),
    "1": .init(keyCode: 18, shift: false), "!": .init(keyCode: 18, shift: true),
    "2": .init(keyCode: 19, shift: false), "@": .init(keyCode: 19, shift: true),
    "3": .init(keyCode: 20, shift: false), "#": .init(keyCode: 20, shift: true),
    "4": .init(keyCode: 21, shift: false), "$": .init(keyCode: 21, shift: true),
    "6": .init(keyCode: 22, shift: false), "^": .init(keyCode: 22, shift: true),
    "5": .init(keyCode: 23, shift: false), "%": .init(keyCode: 23, shift: true),
    "=": .init(keyCode: 24, shift: false), "+": .init(keyCode: 24, shift: true),
    "9": .init(keyCode: 25, shift: false), "(": .init(keyCode: 25, shift: true),
    "7": .init(keyCode: 26, shift: false), "&": .init(keyCode: 26, shift: true),
    "-": .init(keyCode: 27, shift: false), "_": .init(keyCode: 27, shift: true),
    "8": .init(keyCode: 28, shift: false), "*": .init(keyCode: 28, shift: true),
    "0": .init(keyCode: 29, shift: false), ")": .init(keyCode: 29, shift: true),
    "]": .init(keyCode: 30, shift: false), "}": .init(keyCode: 30, shift: true),
    "o": .init(keyCode: 31, shift: false), "O": .init(keyCode: 31, shift: true),
    "u": .init(keyCode: 32, shift: false), "U": .init(keyCode: 32, shift: true),
    "[": .init(keyCode: 33, shift: false), "{": .init(keyCode: 33, shift: true),
    "i": .init(keyCode: 34, shift: false), "I": .init(keyCode: 34, shift: true),
    "p": .init(keyCode: 35, shift: false), "P": .init(keyCode: 35, shift: true),
    "l": .init(keyCode: 37, shift: false), "L": .init(keyCode: 37, shift: true),
    "j": .init(keyCode: 38, shift: false), "J": .init(keyCode: 38, shift: true),
    "'": .init(keyCode: 39, shift: false), "\"": .init(keyCode: 39, shift: true),
    "k": .init(keyCode: 40, shift: false), "K": .init(keyCode: 40, shift: true),
    ";": .init(keyCode: 41, shift: false), ":": .init(keyCode: 41, shift: true),
    "\\": .init(keyCode: 42, shift: false), "|": .init(keyCode: 42, shift: true),
    ",": .init(keyCode: 43, shift: false), "<": .init(keyCode: 43, shift: true),
    "/": .init(keyCode: 44, shift: false), "?": .init(keyCode: 44, shift: true),
    "n": .init(keyCode: 45, shift: false), "N": .init(keyCode: 45, shift: true),
    "m": .init(keyCode: 46, shift: false), "M": .init(keyCode: 46, shift: true),
    ".": .init(keyCode: 47, shift: false), ">": .init(keyCode: 47, shift: true),
    " ": .init(keyCode: 49, shift: false),
    "`": .init(keyCode: 50, shift: false), "~": .init(keyCode: 50, shift: true),
]

func postVirtualKey(_ src: CGEventSource?, keyCode: CGKeyCode, flags: CGEventFlags = [], holdUs: useconds_t = 22_000) -> Bool {
    guard let down = CGEvent(keyboardEventSource: src, virtualKey: keyCode, keyDown: true),
          let up = CGEvent(keyboardEventSource: src, virtualKey: keyCode, keyDown: false) else {
        return false
    }
    down.flags = flags
    up.flags = flags
    down.post(tap: .cghidEventTap)
    usleep(holdUs)
    up.post(tap: .cghidEventTap)
    usleep(26_000)
    return true
}

func typeSinglePass(_ src: CGEventSource?, password: String) -> Bool {
    // Clear any stray character and ensure LockScreen secure input field is focused (never Escape!)
    _ = postVirtualKey(src, keyCode: 0, flags: .maskCommand) // Cmd+A
    _ = postVirtualKey(src, keyCode: 51, flags: [])          // Delete/Backspace
    _ = postVirtualKey(src, keyCode: 51, flags: [])
    usleep(160_000)

    for ch in password {
        if let spec = ansiKeyMap[ch] {
            let flags: CGEventFlags = spec.shift ? .maskShift : []
            if !postVirtualKey(src, keyCode: spec.keyCode, flags: flags) {
                return false
            }
        } else {
            for unit in String(ch).utf16 {
                var u = unit
                guard let down = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: true),
                      let up = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: false) else {
                    return false
                }
                down.flags = []
                up.flags = []
                down.keyboardSetUnicodeString(stringLength: 1, unicodeString: &u)
                up.keyboardSetUnicodeString(stringLength: 1, unicodeString: &u)
                down.post(tap: .cghidEventTap)
                usleep(22_000)
                up.post(tap: .cghidEventTap)
                usleep(26_000)
            }
        }
    }
    usleep(120_000)
    // Submit via Return (keyCode 36)
    return postVirtualKey(src, keyCode: 36, flags: [])
}

func performWakeAndUnlock(_ password: String) -> (ok: Bool, error: String?) {
    if !isScreenLocked() {
        wakeDisplayNative()
        return (true, nil)
    }
    guard checkAccessibilityTrusted(prompt: true) else {
        openAccessibilitySettings()
        return (false, "ERR_AX_NOT_TRUSTED")
    }
    wakeDisplayNative()
    let src = CGEventSource(stateID: .hidSystemState)
    // Wait for display + LockScreen secure password box to wake up
    usleep(720_000)

    for attempt in 1...2 {
        if !typeSinglePass(src, password: password) {
            return (false, "ERR_CGEVENT_FAILED")
        }
        for _ in 0..<10 {
            usleep(250_000)
            if !isScreenLocked() {
                return (true, nil)
            }
        }
        if attempt == 1 {
            // If display just finished waking up on attempt 1, wait briefly and try 1 clean retry
            wakeDisplayNative()
            usleep(450_000)
        }
    }
    return (false, "ERR_STILL_LOCKED")
}

func openAccessibilitySettings() {
    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
        NSWorkspace.shared.open(url)
    }
}

// =============================================================================
// High-Frequency Native HID Injector & Laser/Spotlight Coordinator (v3.7)
// =============================================================================

final class HidEventEngine {
    static let shared = HidEventEngine()

    private let lock = NSLock()
    private var cachedEventSource: CGEventSource? = nil
    private var eventSourceCreatedWhileTrusted: Bool = false
    private var virtualPos: CGPoint? = nil
    private var lastPointerActivitySec: TimeInterval = 0
    private var leftButtonDown: Bool = false
    private var scrollRemX: CGFloat = 0
    private var scrollRemY: CGFloat = 0
    private var cachedDisplays: [CGRect] = []
    private var lastDisplayRefreshSec: TimeInterval = 0

    private static let allowedSystemKeys: Set<Int> = [0, 1, 2, 3, 7, 16, 17, 18]

    private init() {}

    private var eventSource: CGEventSource? {
        let nowTrusted = AXIsProcessTrusted()
        if cachedEventSource == nil || (nowTrusted && !eventSourceCreatedWhileTrusted) {
            let src = CGEventSource(stateID: .hidSystemState)
            src?.localEventsSuppressionInterval = 0.0
            cachedEventSource = src
            eventSourceCreatedWhileTrusted = nowTrusted
        }
        return cachedEventSource
    }

    private func getDisplayRectsLocked(now: TimeInterval) -> [CGRect] {
        if cachedDisplays.isEmpty || (now - lastDisplayRefreshSec) > 0.5 {
            cachedDisplays = LaserOverlayController.currentGlobalDisplayRects()
            lastDisplayRefreshSec = now
        }
        return cachedDisplays
    }

    private func currentVirtualPosLocked(now: TimeInterval) -> CGPoint {
        if let vp = virtualPos, (now - lastPointerActivitySec) <= 0.35 {
            return vp
        }
        let livePos = CGEvent(source: nil)?.location ?? virtualPos ?? .zero
        let clamped = HidMath.clampToDisplays(livePos, displays: getDisplayRectsLocked(now: now))
        virtualPos = clamped
        return clamped
    }

    private func advanceCursorLocked(dxRaw: Int, dyRaw: Int, now: TimeInterval) -> (pos: CGPoint, dxPt: CGFloat, dyPt: CGFloat) {
        let dxPt = HidMath.quarterPxToPoints(dxRaw)
        let dyPt = HidMath.quarterPxToPoints(dyRaw)
        let base = currentVirtualPosLocked(now: now)
        let target = CGPoint(x: base.x + dxPt, y: base.y + dyPt)
        let clamped = HidMath.clampToDisplays(target, displays: getDisplayRectsLocked(now: now))
        virtualPos = clamped
        lastPointerActivitySec = now
        return (clamped, dxPt, dyPt)
    }

    private func postPointerMoveEvent(pos: CGPoint, dxPt: CGFloat, dyPt: CGFloat, isDragging: Bool, flags: CGEventFlags = []) {
        let evType: CGEventType = isDragging ? .leftMouseDragged : .mouseMoved
        guard let ev = CGEvent(mouseEventSource: eventSource, mouseType: evType, mouseCursorPosition: pos, mouseButton: .left) else {
            return
        }
        if !flags.isEmpty {
            ev.flags = flags
        }
        ev.setDoubleValueField(.mouseEventDeltaX, value: Double(dxPt))
        ev.setDoubleValueField(.mouseEventDeltaY, value: Double(dyPt))
        ev.post(tap: .cghidEventTap)
    }

    private func postMouseButtonPair(buttonCode: Int, pos: CGPoint, isDown: Bool, clickState: Int64 = 1) {
        let cgBtn: CGMouseButton
        let cgType: CGEventType
        switch buttonCode {
        case 2:
            cgBtn = .right
            cgType = isDown ? .rightMouseDown : .rightMouseUp
        case 3:
            cgBtn = .center
            cgType = isDown ? .otherMouseDown : .otherMouseUp
        default:
            cgBtn = .left
            cgType = isDown ? .leftMouseDown : .leftMouseUp
        }
        guard let ev = CGEvent(mouseEventSource: eventSource, mouseType: cgType, mouseCursorPosition: pos, mouseButton: cgBtn) else {
            return
        }
        ev.setIntegerValueField(.mouseEventClickState, value: clickState)
        ev.post(tap: .cghidEventTap)
    }

    private func modifierFlags(from mask: Int) -> CGEventFlags {
        var flags: CGEventFlags = []
        if (mask & 0x01) != 0 { flags.insert(.maskCommand) }
        if (mask & 0x02) != 0 { flags.insert(.maskShift) }
        if (mask & 0x04) != 0 { flags.insert(.maskAlternate) }
        if (mask & 0x08) != 0 { flags.insert(.maskControl) }
        return flags
    }

    private func postModifierTransition(mask: Int, isDown: Bool) {
        guard mask != 0 else { return }
        let modSpecs: [(bit: Int, keyCode: CGKeyCode, flag: CGEventFlags)] = [
            (0x08, 59, .maskControl),
            (0x04, 58, .maskAlternate),
            (0x02, 56, .maskShift),
            (0x01, 55, .maskCommand),
        ]
        var active: CGEventFlags = isDown ? [] : modifierFlags(from: mask)
        let sequence = isDown ? modSpecs : modSpecs.reversed()
        for spec in sequence where (mask & spec.bit) != 0 {
            if isDown {
                active.insert(spec.flag)
            } else {
                active.remove(spec.flag)
            }
            if let ev = CGEvent(keyboardEventSource: eventSource, virtualKey: spec.keyCode, keyDown: isDown) {
                ev.type = .flagsChanged
                ev.flags = active
                ev.post(tap: .cghidEventTap)
            }
        }
    }

    private func postKeyTap(keyCodeRaw: Int, modsMask: Int) {
        guard !isScreenLocked() else { return }
        guard keyCodeRaw >= 0 && keyCodeRaw <= 255 else { return }
        let keyCode = CGKeyCode(keyCodeRaw)
        let modFlags = modifierFlags(from: modsMask)

        postModifierTransition(mask: modsMask, isDown: true)
        if let down = CGEvent(keyboardEventSource: eventSource, virtualKey: keyCode, keyDown: true),
           let up = CGEvent(keyboardEventSource: eventSource, virtualKey: keyCode, keyDown: false) {
            let intrinsic = down.flags.intersection([.maskNumericPad, .maskSecondaryFn])
            let combinedFlags = modFlags.union(intrinsic)
            down.flags = combinedFlags
            up.flags = combinedFlags
            down.post(tap: .cghidEventTap)
            usleep(2_000)
            up.post(tap: .cghidEventTap)
        }
        postModifierTransition(mask: modsMask, isDown: false)
    }

    private func postSystemDefinedKey(_ keyType: Int) {
        guard Self.allowedSystemKeys.contains(keyType) else { return }
        let emitBlock = {
            for down in [true, false] {
                let flagsRaw: UInt = down ? 0x0a00 : 0x0b00
                let data1 = (keyType << 16) | Int(flagsRaw)
                if let nsEv = NSEvent.otherEvent(
                    with: .systemDefined,
                    location: .zero,
                    modifierFlags: NSEvent.ModifierFlags(rawValue: flagsRaw),
                    timestamp: ProcessInfo.processInfo.systemUptime,
                    windowNumber: 0,
                    context: nil,
                    subtype: 8,
                    data1: data1,
                    data2: -1
                ), let cgEv = nsEv.cgEvent {
                    cgEv.post(tap: .cghidEventTap)
                }
            }
        }
        if Thread.isMainThread {
            emitBlock()
        } else {
            DispatchQueue.main.async(execute: emitBlock)
        }
    }

    func handleHidPacket(op: Int, modsOrBtn: Int, dx: Int, dy: Int, aux: Int, flags: Int) {
        switch op {
        case 0x01: // POINTER_MOVE_REL
            lock.lock()
            let now = CACurrentMediaTime()
            let step = advanceCursorLocked(dxRaw: dx, dyRaw: dy, now: now)
            let dragging = leftButtonDown
            let evFlags = modifierFlags(from: modsOrBtn)
            lock.unlock()
            postPointerMoveEvent(pos: step.pos, dxPt: step.dxPt, dyPt: step.dyPt, isDragging: dragging, flags: evFlags)

        case 0x02: // MOUSE_BUTTON
            let btn = (modsOrBtn >= 1 && modsOrBtn <= 3) ? modsOrBtn : 1
            let action = dx
            lock.lock()
            let now = CACurrentMediaTime()
            let pos = currentVirtualPosLocked(now: now)
            lastPointerActivitySec = now
            switch action {
            case 1: // CLICK
                if btn == 1 && leftButtonDown {
                    leftButtonDown = false
                    lock.unlock()
                    postMouseButtonPair(buttonCode: 1, pos: pos, isDown: false, clickState: 1)
                } else {
                    lock.unlock()
                    postMouseButtonPair(buttonCode: btn, pos: pos, isDown: true, clickState: 1)
                    usleep(4_000)
                    postMouseButtonPair(buttonCode: btn, pos: pos, isDown: false, clickState: 1)
                }
            case 2: // DOUBLE_CLICK
                if btn == 1 { leftButtonDown = false }
                lock.unlock()
                postMouseButtonPair(buttonCode: btn, pos: pos, isDown: true, clickState: 1)
                usleep(3_000)
                postMouseButtonPair(buttonCode: btn, pos: pos, isDown: false, clickState: 1)
                usleep(12_000)
                postMouseButtonPair(buttonCode: btn, pos: pos, isDown: true, clickState: 2)
                usleep(3_000)
                postMouseButtonPair(buttonCode: btn, pos: pos, isDown: false, clickState: 2)
            case 3: // DOWN
                if btn == 1 { leftButtonDown = true }
                lock.unlock()
                postMouseButtonPair(buttonCode: btn, pos: pos, isDown: true, clickState: 1)
            case 4: // UP
                if btn == 1 { leftButtonDown = false }
                lock.unlock()
                postMouseButtonPair(buttonCode: btn, pos: pos, isDown: false, clickState: 1)
            case 5: // DRAG_LOCK_TOGGLE
                let nextHeld = !leftButtonDown
                leftButtonDown = nextHeld
                lock.unlock()
                postMouseButtonPair(buttonCode: 1, pos: pos, isDown: nextHeld, clickState: 1)
            default:
                lock.unlock()
            }

        case 0x03: // SCROLL_PIXEL
            lock.lock()
            scrollRemX += HidMath.quarterPxToPoints(dx)
            scrollRemY += HidMath.quarterPxToPoints(dy)
            let dxPx = Int32(scrollRemX.rounded(.towardZero))
            let dyPx = Int32(scrollRemY.rounded(.towardZero))
            scrollRemX -= CGFloat(dxPx)
            scrollRemY -= CGFloat(dyPx)
            let evFlags = modifierFlags(from: modsOrBtn)
            lock.unlock()
            guard dxPx != 0 || dyPx != 0 else { return }
            if let ev = CGEvent(
                scrollWheelEvent2Source: eventSource,
                units: .pixel,
                wheelCount: 2,
                wheel1: dyPx,
                wheel2: dxPx,
                wheel3: 0
            ) {
                ev.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
                if !evFlags.isEmpty {
                    ev.flags = evFlags
                }
                ev.post(tap: .cghidEventTap)
            }

        case 0x04: // KEY_TAP
            postKeyTap(keyCodeRaw: dx, modsMask: modsOrBtn)

        case 0x05: // SYSTEM_DEFINED_KEY
            let keyType = (dx > 0) ? dx : modsOrBtn
            postSystemDefinedKey(keyType)

        case 0x06: // LASER_STATE_AND_DELTA
            lock.lock()
            let now = CACurrentMediaTime()
            let hasDelta = (dx != 0 || dy != 0)
            let nextPos: CGPoint
            let dxPt: CGFloat
            let dyPt: CGFloat
            let dragging = leftButtonDown
            if hasDelta {
                let step = advanceCursorLocked(dxRaw: dx, dyRaw: dy, now: now)
                nextPos = step.pos
                dxPt = step.dxPt
                dyPt = step.dyPt
            } else {
                nextPos = currentVirtualPosLocked(now: now)
                lastPointerActivitySec = now
                dxPt = 0
                dyPt = 0
            }
            lock.unlock()

            if hasDelta {
                postPointerMoveEvent(pos: nextPos, dxPt: dxPt, dyPt: dyPt, isDragging: dragging)
            }
            let mode = modsOrBtn
            let radius = CGFloat(aux)
            if Thread.isMainThread {
                LaserOverlayController.shared.updateOnMain(mode: mode, globalCursor: nextPos, spotlightRadius: radius)
            } else {
                DispatchQueue.main.async {
                    LaserOverlayController.shared.updateOnMain(mode: mode, globalCursor: nextPos, spotlightRadius: radius)
                }
            }

        case 0x0f: // RESET_HID_STATE
            resetState()

        default:
            break
        }
    }

    func typeUnicodeText(_ text: String, submitReturn: Bool) -> [String: Any] {
        guard !isScreenLocked() else {
            return [
                "ok": false,
                "typedChars": 0,
                "error": "ERR_SCREEN_LOCKED",
                "message": "Mac 当前处于锁屏状态，请先点击「远程解锁」后再输入文字"
            ]
        }
        guard checkAccessibilityTrusted(prompt: false) else {
            return [
                "ok": false,
                "typedChars": 0,
                "error": "ERR_AX_NOT_TRUSTED",
                "message": "缺少 macOS 辅助功能权限"
            ]
        }

        let utf16Units = Array(text.utf16)
        var idx = 0
        while idx < utf16Units.count {
            var end = min(idx + 16, utf16Units.count)
            if end < utf16Units.count && UTF16.isLeadSurrogate(utf16Units[end - 1]) {
                end -= 1
            }
            if end <= idx {
                end = min(idx + 1, utf16Units.count)
            }
            var chunk = Array(utf16Units[idx..<end])
            let chunkLen = chunk.count
            guard let down = CGEvent(keyboardEventSource: eventSource, virtualKey: 0, keyDown: true),
                  let up = CGEvent(keyboardEventSource: eventSource, virtualKey: 0, keyDown: false) else {
                return [
                    "ok": false,
                    "typedChars": 0,
                    "error": "ERR_CGEVENT_FAILED",
                    "message": "CGEvent 创建失败"
                ]
            }
            down.flags = []
            up.flags = []
            chunk.withUnsafeMutableBufferPointer { buf in
                guard let base = buf.baseAddress else { return }
                down.keyboardSetUnicodeString(stringLength: chunkLen, unicodeString: base)
                up.keyboardSetUnicodeString(stringLength: chunkLen, unicodeString: base)
            }
            down.post(tap: .cghidEventTap)
            usleep(3_000)
            up.post(tap: .cghidEventTap)
            usleep(3_000)
            idx = end
        }

        if submitReturn {
            if let down = CGEvent(keyboardEventSource: eventSource, virtualKey: 36, keyDown: true),
               let up = CGEvent(keyboardEventSource: eventSource, virtualKey: 36, keyDown: false) {
                down.flags = []
                up.flags = []
                down.post(tap: .cghidEventTap)
                usleep(6_000)
                up.post(tap: .cghidEventTap)
            }
        }

        return [
            "ok": true,
            "typedChars": text.count
        ]
    }

    func resetState() {
        lock.lock()
        scrollRemX = 0
        scrollRemY = 0
        let wasLeftDown = leftButtonDown
        leftButtonDown = false
        let releasePos = virtualPos ?? (CGEvent(source: nil)?.location ?? .zero)
        virtualPos = nil
        lock.unlock()

        if wasLeftDown {
            postMouseButtonPair(buttonCode: 1, pos: releasePos, isDown: false, clickState: 1)
        }

        if Thread.isMainThread {
            LaserOverlayController.shared.hideImmediateOnMain()
        } else {
            DispatchQueue.main.async {
                LaserOverlayController.shared.hideImmediateOnMain()
            }
        }
    }
}

private func intField(_ dict: [String: Any], _ key: String, fallbackKey: String? = nil) -> Int {
    if let num = dict[key] as? NSNumber { return num.intValue }
    if let val = dict[key] as? Int { return val }
    if let alt = fallbackKey {
        if let num = dict[alt] as? NSNumber { return num.intValue }
        if let val = dict[alt] as? Int { return val }
    }
    return 0
}

private func boolField(_ dict: [String: Any], _ key: String) -> Bool {
    if let b = dict[key] as? Bool { return b }
    if let num = dict[key] as? NSNumber { return num.boolValue }
    return false
}

func jsonString(_ dict: [String: Any]) -> String {
    if let data = try? JSONSerialization.data(withJSONObject: dict),
       let s = String(data: data, encoding: .utf8) {
        return s
    }
    return "{\"ok\":false}"
}

func handleCommandDict(_ req: [String: Any]) -> [String: Any] {
    let cmd = (req["cmd"] as? String) ?? "status"
    switch cmd {
    case "hid":
        let op = intField(req, "op", fallbackKey: "opcode")
        let modsOrBtn = intField(req, "m", fallbackKey: "modsOrBtn")
        let dx = intField(req, "dx")
        let dy = intField(req, "dy")
        let aux = intField(req, "aux")
        let fl = intField(req, "fl", fallbackKey: "flags")
        HidEventEngine.shared.handleHidPacket(op: op, modsOrBtn: modsOrBtn, dx: dx, dy: dy, aux: aux, flags: fl)
        return ["ok": true]
    case "type-text":
        let text = (req["text"] as? String) ?? ""
        let submitReturn = boolField(req, "submitReturn")
        return HidEventEngine.shared.typeUnicodeText(text, submitReturn: submitReturn)
    case "reset-hid":
        HidEventEngine.shared.resetState()
        return ["ok": true]
    case "status":
        let prompt = boolField(req, "prompt")
        return [
            "ok": true,
            "screenLocked": isScreenLocked(),
            "accessibilityTrusted": checkAccessibilityTrusted(prompt: prompt),
            "frontmostApp": NSWorkspace.shared.frontmostApplication?.localizedName ?? ""
        ]
    case "request-accessibility":
        let trusted = checkAccessibilityTrusted(prompt: true)
        if !trusted {
            openAccessibilitySettings()
        }
        return [
            "ok": true,
            "accessibilityTrusted": trusted
        ]
    case "wake":
        wakeDisplayNative()
        return ["ok": true]
    case "lock":
        let ok = lockScreenNow()
        return ok ? ["ok": true] : ["ok": false, "error": "SACLockScreenImmediate unavailable"]
    case "type-unlock":
        let pwd = ((req["password"] as? String) ?? "").trimmingCharacters(in: .newlines)
        guard !pwd.isEmpty else {
            return ["ok": false, "error": "ERR_EMPTY_PASSWORD"]
        }
        let res = performWakeAndUnlock(pwd)
        if res.ok {
            return ["ok": true, "screenLocked": false]
        } else {
            return ["ok": false, "error": res.error ?? "ERR_UNLOCK_FAILED", "screenLocked": isScreenLocked()]
        }
    default:
        return ["ok": false, "error": "unknown command"]
    }
}

@discardableResult
private func writeAllSocket(_ fd: Int32, _ text: String) -> Bool {
    let bytes = Array(text.utf8)
    var offset = 0
    while offset < bytes.count {
        let written = bytes.withUnsafeBufferPointer { buf -> Int in
            guard let base = buf.baseAddress else { return -1 }
            return Darwin.write(fd, base.advanced(by: offset), bytes.count - offset)
        }
        if written <= 0 {
            if written < 0 && errno == EINTR { continue }
            return false
        }
        offset += written
    }
    return true
}

func runUnixSocketDaemon(socketPath: String) -> Never {
    signal(SIGPIPE, SIG_IGN)
    let fm = FileManager.default
    let dir = (socketPath as NSString).deletingLastPathComponent
    try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    unlink(socketPath)

    let serverFd = socket(AF_UNIX, SOCK_STREAM, 0)
    guard serverFd >= 0 else {
        fputs("Failed to create AF_UNIX socket\n", stderr)
        exit(1)
    }

    var addr = sockaddr_un()
    addr.sun_family = sa_family_t(AF_UNIX)
    let pathBytes = socketPath.utf8CString
    guard pathBytes.count <= MemoryLayout.size(ofValue: addr.sun_path) else {
        fputs("Socket path too long\n", stderr)
        exit(1)
    }
    withUnsafeMutablePointer(to: &addr.sun_path) { ptr in
        ptr.withMemoryRebound(to: CChar.self, capacity: pathBytes.count) { dst in
            for i in 0..<pathBytes.count {
                dst[i] = pathBytes[i]
            }
        }
    }

    let bindRes = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(serverFd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
        }
    }
    guard bindRes == 0 else {
        fputs("Failed to bind socket at \(socketPath)\n", stderr)
        exit(1)
    }
    chmod(socketPath, 0o600)
    listen(serverFd, 16)

    DispatchQueue.global(qos: .userInteractive).async {
        while true {
            let clientFd = accept(serverFd, nil, nil)
            if clientFd < 0 { continue }
            var noSigPipe: Int32 = 1
            _ = setsockopt(clientFd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))

            DispatchQueue.global(qos: .userInteractive).async {
                defer { close(clientFd) }
                var pending = Data()
                var buf = [UInt8](repeating: 0, count: 8192)
                let maxLineBytes = 256 * 1024

                while true {
                    let n = Darwin.read(clientFd, &buf, buf.count)
                    if n < 0 {
                        if errno == EINTR { continue }
                        break
                    }
                    if n == 0 {
                        // Process any trailing non-newline-terminated payload from legacy one-shot clients
                        if !pending.isEmpty {
                            let line = (String(data: pending, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                            pending.removeAll()
                            if !line.isEmpty {
                                if let jsonData = line.data(using: .utf8),
                                   let obj = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any] {
                                    let noReply = boolField(obj, "noreply")
                                    let resp = handleCommandDict(obj)
                                    if !noReply {
                                        _ = writeAllSocket(clientFd, jsonString(resp) + "\n")
                                    }
                                } else {
                                    _ = writeAllSocket(clientFd, "{\"ok\":false,\"error\":\"invalid_json\"}\n")
                                }
                            }
                        }
                        break
                    }

                    pending.append(buf, count: n)
                    if pending.count > maxLineBytes && !pending.contains(0x0A) {
                        _ = writeAllSocket(clientFd, "{\"ok\":false,\"error\":\"payload_too_large\"}\n")
                        break
                    }

                    var writeFailed = false
                    while let nlIndex = pending.firstIndex(of: 0x0A) {
                        let lineData = pending.subdata(in: pending.startIndex..<nlIndex)
                        pending.removeSubrange(pending.startIndex...nlIndex)
                        let line = (String(data: lineData, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                        if line.isEmpty { continue }

                        if let jsonData = line.data(using: .utf8),
                           let obj = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any] {
                            let noReply = boolField(obj, "noreply")
                            let resp = handleCommandDict(obj)
                            if !noReply {
                                if !writeAllSocket(clientFd, jsonString(resp) + "\n") {
                                    writeFailed = true
                                    break
                                }
                            }
                        } else {
                            if !writeAllSocket(clientFd, "{\"ok\":false,\"error\":\"invalid_json\"}\n") {
                                writeFailed = true
                                break
                            }
                        }
                    }
                    if writeFailed { break }
                }
            }
        }
    }

    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)
    app.run()
    exit(0)
}

let args = CommandLine.arguments
if args.count >= 3 && args[1] == "--daemon" {
    runUnixSocketDaemon(socketPath: args[2])
}

let cmd = args.count > 1 ? args[1] : "status"
switch cmd {
case "status":
    print(jsonString(handleCommandDict(["cmd": "status"])))
case "request-accessibility":
    print(jsonString(handleCommandDict(["cmd": "request-accessibility"])))
case "wake":
    print(jsonString(handleCommandDict(["cmd": "wake"])))
case "reset-hid":
    print(jsonString(handleCommandDict(["cmd": "reset-hid"])))
case "lock":
    let res = handleCommandDict(["cmd": "lock"])
    print(jsonString(res))
    exit((res["ok"] as? Bool) == true ? 0 : 1)
case "type-unlock":
    let stdinData = FileHandle.standardInput.readDataToEndOfFile()
    let pwd = (String(data: stdinData, encoding: .utf8) ?? "").trimmingCharacters(in: .newlines)
    let res = handleCommandDict(["cmd": "type-unlock", "password": pwd])
    print(jsonString(res))
    exit((res["ok"] as? Bool) == true ? 0 : 1)
default:
    print("{\"ok\":false,\"error\":\"unknown command\"}")
    exit(1)
}
