import AppKit
import ApplicationServices
import CoreGraphics
import Darwin
import Foundation
import IOKit.pwr_mgt

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
    case "status":
        let prompt = (req["prompt"] as? Bool) ?? false
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

func runUnixSocketDaemon(socketPath: String) -> Never {
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

    DispatchQueue.global(qos: .userInitiated).async {
        while true {
            let clientFd = accept(serverFd, nil, nil)
            if clientFd < 0 { continue }
            DispatchQueue.global(qos: .userInitiated).async {
                defer { close(clientFd) }
                var data = Data()
                var buf = [UInt8](repeating: 0, count: 4096)
                while true {
                    let n = read(clientFd, &buf, buf.count)
                    if n <= 0 { break }
                    data.append(buf, count: n)
                    if buf[0..<n].contains(0x0A) { break }
                }
                let line = (String(data: data, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                var resp: [String: Any] = ["ok": false, "error": "invalid_json"]
                if let jsonData = line.data(using: .utf8),
                   let obj = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any] {
                    resp = handleCommandDict(obj)
                }
                let out = (jsonString(resp) + "\n")
                out.withCString { cstr in
                    _ = write(clientFd, cstr, strlen(cstr))
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
