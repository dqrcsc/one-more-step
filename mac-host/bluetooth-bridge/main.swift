import Foundation
import CoreBluetooth
import IOBluetooth
import CoreAudio
import AudioToolbox
import AppKit
import Darwin

/// Module 02: macOS Native CoreBluetooth GATT + Near-Field Bridge + AirPods Seamless Auto-Switch Controller
/// Communicates with TypeScript `omsd` over a local Unix Domain Socket (JSON Lines).

let SERVICE_UUID = CBUUID(string: "0000FD9A-0000-1000-8000-00805F9B34FB")
let CHAR_MAC_INFO_UUID = CBUUID(string: "0000FD9B-0000-1000-8000-00805F9B34FB")
let CHAR_FRAME_PIPE_UUID = CBUUID(string: "0000FD9C-0000-1000-8000-00805F9B34FB")

final class UnixSocketLineClient {
    private var fd: Int32 = -1
    private let socketPath: String
    private let writeLock = NSLock()
    var onMessage: (([String: Any]) -> Void)?
    var onConnected: (() -> Void)?

    init(socketPath: String) {
        self.socketPath = socketPath
    }

    func connectAndListen() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }
            while true {
                if self.connectOnce() {
                    DispatchQueue.main.async {
                        self.onConnected?()
                    }
                    self.readLoop()
                }
                Thread.sleep(forTimeInterval: 2.0)
            }
        }
    }

    private func connectOnce() -> Bool {
        if fd >= 0 { close(fd); fd = -1 }
        fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let pathBytes = socketPath.utf8CString
        guard pathBytes.count <= MemoryLayout.size(ofValue: addr.sun_path) else {
            close(fd); fd = -1
            return false
        }
        withUnsafeMutablePointer(to: &addr.sun_path) { ptr in
            let rawPtr = UnsafeMutableRawPointer(ptr).assumingMemoryBound(to: CChar.self)
            for i in 0..<pathBytes.count {
                rawPtr[i] = pathBytes[i]
            }
        }

        let len = socklen_t(MemoryLayout<sockaddr_un>.size)
        let res = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, len)
            }
        }
        if res == 0 {
            sendJson(["type": "bridge_ready", "pid": ProcessInfo.processInfo.processIdentifier])
            return true
        } else {
            close(fd); fd = -1
            return false
        }
    }

    private func readLoop() {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        while fd >= 0 {
            let n = Darwin.read(fd, &chunk, chunk.count)
            if n <= 0 { break }
            buffer.append(contentsOf: chunk[0..<n])
            while let newlineIdx = buffer.firstIndex(of: 0x0A) {
                let lineData = buffer.subdata(in: 0..<newlineIdx)
                buffer.removeSubrange(0...newlineIdx)
                if let obj = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any] {
                    onMessage?(obj)
                }
            }
        }
        if fd >= 0 { close(fd); fd = -1 }
    }

    func sendJson(_ dict: [String: Any]) {
        writeLock.lock()
        defer { writeLock.unlock() }
        guard fd >= 0,
              let data = try? JSONSerialization.data(withJSONObject: dict) else { return }
        var packet = data
        packet.append(0x0A)
        _ = packet.withUnsafeBytes { ptr in
            Darwin.write(fd, ptr.baseAddress!, packet.count)
        }
    }
}

struct PairedAirPodsInfo {
    let name: String
    let mac: String
    let connected: Bool
}

final class MacAirPodsController {
    private let ipc: UnixSocketLineClient
    private let btWorkQueue = DispatchQueue(label: "com.oms.airpods.bt", qos: .userInitiated)

    var autoSwitchEnabled: Bool = true
    var pauseOtherOnSwitch: Bool = true
    var preferredMac: String? = nil
    var phoneAirpodsConnected: Bool = false
    var phoneAirpodsMac: String? = nil
    var phoneAirpodsName: String? = nil
    var phoneInCall: Bool = false

    private var lastActiveAirpodsMac: String? = nil
    private var suppressAutoGrabUntil: Date = .distantPast
    private var wasNonAirpodsAudioRunning: Bool = false
    private var lastNonAirpodsOutputDeviceId: AudioDeviceID = 0
    private var tempMutedDeviceId: AudioDeviceID = 0
    private var tempMuteRestoreTimer: DispatchWorkItem? = nil
    private var connectGeneration: Int = 0
    private var lastReportedStatusKey: String = ""
    private var lastDefaultOutputName: String = ""
    private var cachedPairedAirpods: [PairedAirPodsInfo] = []
    private var lastPairedAirpodsRefreshAt: Date = .distantPast
    private var pollTimer: Timer? = nil

    private var mediaRemoteSendCommand: (@convention(c) (UInt32, AnyObject?) -> Bool)? = nil

    init(ipc: UnixSocketLineClient) {
        self.ipc = ipc
        loadMediaRemoteSymbol()
        updateFallbackNonAirpodsSpeaker()
        setupCoreAudioListeners()
        startPollingTimer()
    }

    private func loadMediaRemoteSymbol() {
        if let handle = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW),
           let sym = dlsym(handle, "MRMediaRemoteSendCommand") {
            typealias Fn = @convention(c) (UInt32, AnyObject?) -> Bool
            self.mediaRemoteSendCommand = unsafeBitCast(sym, to: Fn.self)
        }
    }

    static func normalizeMac(_ raw: String?) -> String {
        guard let raw = raw, !raw.isEmpty else { return "" }
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: ":")
            .uppercased()
    }

    static func toIOBluetoothAddress(_ mac: String) -> String {
        return normalizeMac(mac).replacingOccurrences(of: ":", with: "-").lowercased()
    }

    // MARK: - CoreAudio Helpers

    func getDefaultOutputDevice() -> AudioDeviceID {
        var deviceID = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &deviceID)
        return deviceID
    }

    func getDeviceName(_ id: AudioDeviceID) -> String {
        guard id != 0 else { return "" }
        var unmanagedName: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioObjectPropertyName,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let status = AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &unmanagedName)
        guard status == noErr, let cfStr = unmanagedName?.takeRetainedValue() else { return "" }
        return cfStr as String
    }

    func isDeviceRunning(_ id: AudioDeviceID) -> Bool {
        guard id != 0 else { return false }
        var running: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let status = AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &running)
        return status == noErr && running != 0
    }

    func hasOutputStreams(_ id: AudioDeviceID) -> Bool {
        guard id != 0 else { return false }
        var outSize: UInt32 = 0
        var streamAddr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreams,
            mScope: kAudioObjectPropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
        let status = AudioObjectGetPropertyDataSize(id, &streamAddr, 0, nil, &outSize)
        return status == noErr && outSize > 0
    }

    func listOutputDevices() -> [(id: AudioDeviceID, name: String)] {
        var propSize: UInt32 = 0
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &propSize) == noErr else {
            return []
        }
        let count = Int(propSize) / MemoryLayout<AudioDeviceID>.size
        guard count > 0 else { return [] }
        var devices = [AudioDeviceID](repeating: 0, count: count)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &propSize, &devices) == noErr else {
            return []
        }
        var result: [(id: AudioDeviceID, name: String)] = []
        for id in devices {
            if hasOutputStreams(id) {
                let name = getDeviceName(id)
                if !name.isEmpty {
                    result.append((id: id, name: name))
                }
            }
        }
        return result
    }

    @discardableResult
    func setDefaultOutputDevice(_ id: AudioDeviceID) -> Bool {
        guard id != 0 else { return false }
        var targetId = id
        let size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var defAddr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let s1 = AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &defAddr, 0, nil, size, &targetId)

        var sysAddr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultSystemOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        _ = AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &sysAddr, 0, nil, size, &targetId)
        return s1 == noErr
    }

    func getDeviceMute(_ id: AudioDeviceID) -> Bool? {
        guard id != 0 else { return nil }
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyMute,
            mScope: kAudioObjectPropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectHasProperty(id, &addr) else { return nil }
        var muteVal: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &muteVal) == noErr else { return nil }
        return muteVal != 0
    }

    func setDeviceMute(_ id: AudioDeviceID, mute: Bool) {
        guard id != 0 else { return }
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyMute,
            mScope: kAudioObjectPropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectHasProperty(id, &addr) else { return }
        var muteVal: UInt32 = mute ? 1 : 0
        let size = UInt32(MemoryLayout<UInt32>.size)
        _ = AudioObjectSetPropertyData(id, &addr, 0, nil, size, &muteVal)
    }

    private func tempMuteDeviceIfNeeded(_ id: AudioDeviceID, maxDurationSec: Double) {
        guard id != 0 else { return }
        if tempMutedDeviceId != 0 && tempMutedDeviceId != id {
            restoreTempMute()
        }
        if tempMutedDeviceId == 0 {
            if getDeviceMute(id) == false {
                setDeviceMute(id, mute: true)
                tempMutedDeviceId = id
            }
        }
        tempMuteRestoreTimer?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.restoreTempMute()
        }
        tempMuteRestoreTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + maxDurationSec, execute: work)
    }

    private func restoreTempMute() {
        tempMuteRestoreTimer?.cancel()
        tempMuteRestoreTimer = nil
        if tempMutedDeviceId != 0 {
            setDeviceMute(tempMutedDeviceId, mute: false)
            tempMutedDeviceId = 0
        }
    }

    private func updateFallbackNonAirpodsSpeaker() {
        let defId = getDefaultOutputDevice()
        let defName = getDeviceName(defId)
        if defId != 0 && !defName.localizedCaseInsensitiveContains("airpods") && !defName.localizedCaseInsensitiveContains("ishot") {
            lastNonAirpodsOutputDeviceId = defId
            return
        }
        if lastNonAirpodsOutputDeviceId == 0 {
            let outputs = listOutputDevices()
            if let builtIn = outputs.first(where: { $0.name.contains("扬声器") || $0.name.localizedCaseInsensitiveContains("speaker") }) {
                lastNonAirpodsOutputDeviceId = builtIn.id
            } else if let other = outputs.first(where: { !$0.name.localizedCaseInsensitiveContains("airpods") && !$0.name.localizedCaseInsensitiveContains("ishot") }) {
                lastNonAirpodsOutputDeviceId = other.id
            }
        }
    }

    // MARK: - IOBluetooth Paired AirPods Discovery

    func listPairedAirpods(forceRefresh: Bool = false) -> [PairedAirPodsInfo] {
        let now = Date()
        if !forceRefresh && !cachedPairedAirpods.isEmpty && now.timeIntervalSince(lastPairedAirpodsRefreshAt) < 12.0 {
            return cachedPairedAirpods
        }
        return autoreleasepool {
            guard let paired = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else {
                return cachedPairedAirpods
            }
            let prefMac = Self.normalizeMac(preferredMac)
            let phoneMac = Self.normalizeMac(phoneAirpodsMac)
            var list: [PairedAirPodsInfo] = []
            for dev in paired {
                let name = dev.nameOrAddress ?? ""
                let mac = Self.normalizeMac(dev.addressString)
                guard !mac.isEmpty else { continue }
                let isAirpods = name.localizedCaseInsensitiveContains("airpods") ||
                    (!prefMac.isEmpty && mac == prefMac) ||
                    (!phoneMac.isEmpty && mac == phoneMac)
                if isAirpods {
                    let connected = dev.isConnected()
                    if connected {
                        lastActiveAirpodsMac = mac
                    }
                    list.append(PairedAirPodsInfo(name: name, mac: mac, connected: connected))
                }
            }
            // Sort by priority: currently connected > phoneAirpodsMac > preferredMac > exact name "AirPods" > others
            let lastMac = Self.normalizeMac(lastActiveAirpodsMac)
            list.sort { a, b in
                func score(_ item: PairedAirPodsInfo) -> Int {
                    var s = 0
                    if item.connected { s += 1000 }
                    if !phoneMac.isEmpty && item.mac == phoneMac { s += 500 }
                    if !prefMac.isEmpty && item.mac == prefMac { s += 300 }
                    if !lastMac.isEmpty && item.mac == lastMac { s += 200 }
                    if item.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare("AirPods") == .orderedSame {
                        s += 100
                    }
                    if item.mac == "B4:59:76:DB:BF:81" {
                        s += 50
                    }
                    return s
                }
                return score(a) > score(b)
            }
            cachedPairedAirpods = list
            lastPairedAirpodsRefreshAt = now
            return list
        }
    }

    func selectTargetAirpods(explicitMac: String?, forceRefresh: Bool = true) -> PairedAirPodsInfo? {
        let all = listPairedAirpods(forceRefresh: forceRefresh)
        guard !all.isEmpty else { return nil }
        let normExplicit = Self.normalizeMac(explicitMac)
        if !normExplicit.isEmpty, let match = all.first(where: { $0.mac == normExplicit }) {
            return match
        }
        return all.first
    }

    func findAirpodsCoreAudioDevice(preferredName: String?) -> (id: AudioDeviceID, name: String)? {
        let outputs = listOutputDevices()
        if let pref = preferredName, !pref.isEmpty {
            if let exact = outputs.first(where: { $0.name.caseInsensitiveCompare(pref) == .orderedSame }) {
                return exact
            }
            if let partial = outputs.first(where: { $0.name.localizedCaseInsensitiveContains(pref) || pref.localizedCaseInsensitiveContains($0.name) }) {
                return partial
            }
        }
        return outputs.first(where: { $0.name.localizedCaseInsensitiveContains("airpods") })
    }

    // MARK: - Media Control (Explicit Pause Only)

    private func postHardwarePlayPauseKey() {
        let keyCode: Int = 16 // NX_KEYTYPE_PLAY
        func postKey(down: Bool) {
            let flags = down ? 0x0A00 : 0x0B00
            let data1 = (keyCode << 16) | flags
            if let ev = NSEvent.otherEvent(
                with: .systemDefined,
                location: .zero,
                modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(flags)),
                timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: 0,
                context: nil,
                subtype: 8,
                data1: data1,
                data2: -1
            ) {
                ev.cgEvent?.post(tap: .cghidEventTap)
            }
        }
        postKey(down: true)
        postKey(down: false)
    }

    func pauseMacActiveMedia() {
        let defId = getDefaultOutputDevice()
        let wasRunning = isDeviceRunning(defId)
        // 1. Send explicit kMRPause = 1 via MediaRemote (never toggles paused media on)
        _ = mediaRemoteSendCommand?(1, nil)

        // 2. If audio hardware was actively running, verify after 160ms; if still running, post NX_KEYTYPE_PLAY
        if wasRunning {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { [weak self] in
                guard let self = self else { return }
                let curId = self.getDefaultOutputDevice()
                if self.isDeviceRunning(curId) {
                    self.postHardwarePlayPauseKey()
                }
            }
        }
    }

    // MARK: - Connect & Release Operations

    func connectAirpodsToMac(targetMac: String?, reqId: String?) {
        connectGeneration &+= 1
        let gen = connectGeneration
        suppressAutoGrabUntil = Date().addingTimeInterval(5.0)

        guard let target = selectTargetAirpods(explicitMac: targetMac) else {
            restoreTempMute()
            if let reqId = reqId {
                ipc.sendJson([
                    "type": "airpods_cmd_result",
                    "reqId": reqId,
                    "ok": false,
                    "action": "connect_mac",
                    "message": "Mac 蓝牙配对列表中未找到 AirPods"
                ])
            }
            return
        }

        lastActiveAirpodsMac = target.mac
        let targetName = target.name
        let targetMacNorm = target.mac
        let btAddr = Self.toIOBluetoothAddress(targetMacNorm)

        // If already in CoreAudio output list and connected, switch immediately
        if target.connected, let audioDev = findAirpodsCoreAudioDevice(preferredName: targetName) {
            _ = setDefaultOutputDevice(audioDev.id)
            restoreTempMute()
            reportStatusIfNeeded(force: true)
            if let reqId = reqId {
                ipc.sendJson([
                    "type": "airpods_cmd_result",
                    "reqId": reqId,
                    "ok": true,
                    "action": "connect_mac",
                    "deviceName": targetName,
                    "deviceMac": targetMacNorm
                ])
            }
            return
        }

        btWorkQueue.async { [weak self] in
            guard let self = self else { return }
            var connectedSuccess = false

            for attempt in 0..<3 {
                if self.connectGeneration != gen { return }
                if attempt > 0 {
                    Thread.sleep(forTimeInterval: 0.45)
                }
                if self.connectGeneration != gen { return }

                autoreleasepool {
                    if let dev = IOBluetoothDevice(addressString: btAddr) {
                        if !dev.isConnected() {
                            _ = dev.openConnection(nil, withPageTimeout: 5000, authenticationRequired: false)
                        }
                    }
                }

                // Poll up to 1.6s for CoreAudio output device to appear and become selectable
                let pollDeadline = Date().addingTimeInterval(1.6)
                while Date() < pollDeadline {
                    if self.connectGeneration != gen { return }
                    if let audioDev = self.findAirpodsCoreAudioDevice(preferredName: targetName) {
                        _ = self.setDefaultOutputDevice(audioDev.id)
                        connectedSuccess = true
                        break
                    }
                    Thread.sleep(forTimeInterval: 0.12)
                }

                if connectedSuccess {
                    break
                }
            }

            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if self.connectGeneration == gen {
                    if let audioDev = self.findAirpodsCoreAudioDevice(preferredName: targetName) {
                        _ = self.setDefaultOutputDevice(audioDev.id)
                        connectedSuccess = true
                    }
                    self.restoreTempMute()
                    self.reportStatusIfNeeded(force: true)
                }
                if let reqId = reqId {
                    self.ipc.sendJson([
                        "type": "airpods_cmd_result",
                        "reqId": reqId,
                        "ok": connectedSuccess,
                        "action": "connect_mac",
                        "deviceName": targetName,
                        "deviceMac": targetMacNorm,
                        "message": connectedSuccess ? "已连接到 Mac 并切为默认音频输出" : "AirPods 连接超时，请确认耳机在盒外且已佩戴"
                    ])
                }
            }
        }
    }

    func releaseAirpodsFromMac(pauseMedia: Bool, targetMac: String?, reqId: String?) {
        connectGeneration &+= 1
        suppressAutoGrabUntil = Date().addingTimeInterval(6.0)
        updateFallbackNonAirpodsSpeaker()

        // Temporarily mute fallback speaker for 1.0s so disconnecting AirPods never leaks audio
        if lastNonAirpodsOutputDeviceId != 0 {
            tempMuteDeviceIfNeeded(lastNonAirpodsOutputDeviceId, maxDurationSec: 1.0)
        }

        if pauseMedia {
            pauseMacActiveMedia()
        }

        // Switch default output away from AirPods to fallback speaker first so CoreAudio releases A2DP
        let defId = getDefaultOutputDevice()
        let defName = getDeviceName(defId)
        if defName.localizedCaseInsensitiveContains("airpods") && lastNonAirpodsOutputDeviceId != 0 {
            _ = setDefaultOutputDevice(lastNonAirpodsOutputDeviceId)
        }

        let paired = listPairedAirpods(forceRefresh: true)
        let normTarget = Self.normalizeMac(targetMac)
        let toDisconnect = paired.filter { info in
            info.connected || (!normTarget.isEmpty && info.mac == normTarget)
        }

        btWorkQueue.async { [weak self] in
            autoreleasepool {
                for item in toDisconnect {
                    let btAddr = Self.toIOBluetoothAddress(item.mac)
                    if let dev = IOBluetoothDevice(addressString: btAddr) {
                        if dev.isConnected() {
                            _ = dev.closeConnection()
                        }
                    }
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.reportStatusIfNeeded(force: true)
                if let reqId = reqId {
                    self.ipc.sendJson([
                        "type": "airpods_cmd_result",
                        "reqId": reqId,
                        "ok": true,
                        "action": "release_mac",
                        "deviceName": toDisconnect.first?.name ?? paired.first?.name ?? "AirPods",
                        "deviceMac": toDisconnect.first?.mac ?? paired.first?.mac ?? ""
                    ])
                }
            }
        }
    }

    func suppressAutoGrab(durationMs: Int) {
        let sec = max(0.5, Double(durationMs) / 1000.0)
        let candidate = Date().addingTimeInterval(sec)
        if candidate > suppressAutoGrabUntil {
            suppressAutoGrabUntil = candidate
        }
    }

    // MARK: - CoreAudio Monitoring & Rising-Edge Trigger

    private func setupCoreAudioListeners() {
        var defAddr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &defAddr, DispatchQueue.main) { [weak self] _, _ in
            self?.onCoreAudioStateChanged(forceBtRefresh: true)
        }

        var devListAddr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &devListAddr, DispatchQueue.main) { [weak self] _, _ in
            self?.onCoreAudioStateChanged(forceBtRefresh: true)
        }
    }

    private func startPollingTimer() {
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { [weak self] _ in
            self?.onCoreAudioStateChanged(forceBtRefresh: false)
        }
    }

    private func onCoreAudioStateChanged(forceBtRefresh: Bool = false) {
        autoreleasepool {
            updateFallbackNonAirpodsSpeaker()
            let defId = getDefaultOutputDevice()
            let defName = getDeviceName(defId)
            let outputChanged = (defName != lastDefaultOutputName)
            lastDefaultOutputName = defName
            let isDefAirpods = defName.localizedCaseInsensitiveContains("airpods")
            let running = isDeviceRunning(defId)

            if isDefAirpods {
                wasNonAirpodsAudioRunning = false
                restoreTempMute()
                reportStatusIfNeeded(force: forceBtRefresh || outputChanged)
                return
            }

            // Check rising edge on non-AirPods output (e.g., MacBook Pro Speakers or Monitor)
            let risingEdge = (!wasNonAirpodsAudioRunning && running)
            wasNonAirpodsAudioRunning = running

            if risingEdge && autoSwitchEnabled && Date() >= suppressAutoGrabUntil && !phoneInCall {
                let paired = listPairedAirpods(forceRefresh: false)
                if !paired.isEmpty {
                    // If AirPods are currently connected on Phone, mute Mac speaker at 0ms to prevent speaker blast
                    if phoneAirpodsConnected {
                        tempMuteDeviceIfNeeded(defId, maxDurationSec: 3.8)
                    }

                    // Debounce 320ms to ignore short system notification clicks/beeps
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.32) { [weak self] in
                        guard let self = self else { return }
                        let curDefId = self.getDefaultOutputDevice()
                        let curDefName = self.getDeviceName(curDefId)
                        let stillNonAirpods = !curDefName.localizedCaseInsensitiveContains("airpods")
                        let stillRunning = self.isDeviceRunning(curDefId)

                        guard self.autoSwitchEnabled,
                              stillNonAirpods,
                              stillRunning,
                              Date() >= self.suppressAutoGrabUntil,
                              !self.phoneInCall else {
                            self.restoreTempMute()
                            return
                        }

                        guard let target = self.selectTargetAirpods(explicitMac: self.phoneAirpodsMac, forceRefresh: true) else {
                            self.restoreTempMute()
                            return
                        }

                        self.suppressAutoGrabUntil = Date().addingTimeInterval(4.5)
                        self.ipc.sendJson([
                            "type": "airpods_mac_claim_triggered",
                            "targetMac": target.mac,
                            "targetName": target.name,
                            "reason": "mac_audio_started"
                        ])
                        self.connectAirpodsToMac(targetMac: target.mac, reqId: nil)
                    }
                }
            }

            reportStatusIfNeeded(force: forceBtRefresh || outputChanged)
        }
    }

    func reportStatusIfNeeded(force: Bool) {
        autoreleasepool {
            let defId = getDefaultOutputDevice()
            let defName = getDeviceName(defId)
            let isDefAirpods = defName.localizedCaseInsensitiveContains("airpods")
            let running = isDeviceRunning(defId)
            let paired = listPairedAirpods(forceRefresh: force)
            let connectedItem = paired.first(where: { $0.connected }) ?? (isDefAirpods ? paired.first : nil)
            let isConnected = (connectedItem != nil) || isDefAirpods

            let pairedDicts: [[String: Any]] = paired.map { item in
                [
                    "name": item.name,
                    "mac": item.mac,
                    "connected": item.connected
                ]
            }

            let key = "\(isConnected)|\(connectedItem?.mac ?? "")|\(isDefAirpods)|\(running)|\(defName)|\(paired.map { "\($0.mac):\($0.connected)" }.joined(separator: ","))"
            if !force && key == lastReportedStatusKey {
                return
            }
            lastReportedStatusKey = key

            var payload: [String: Any] = [
                "type": "airpods_status",
                "connected": isConnected,
                "isDefaultOutput": isDefAirpods,
                "macAudioRunning": running,
                "defaultOutputName": defName,
                "pairedAirpods": pairedDicts
            ]
            if let conn = connectedItem {
                payload["deviceName"] = conn.name
                payload["deviceMac"] = conn.mac
            } else if let first = paired.first {
                payload["deviceName"] = first.name
                payload["deviceMac"] = first.mac
            }
            ipc.sendJson(payload)
        }
    }
}

final class OmsBluetoothBridge: NSObject, CBPeripheralManagerDelegate {
    private var peripheralManager: CBPeripheralManager!
    private var macInfoChar: CBMutableCharacteristic?
    private var framePipeChar: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []
    private var announcedCentrals: Set<UUID> = []
    private var txQueue: [Data] = []
    private let ipc: UnixSocketLineClient
    private var macInfoJsonData: Data
    private let airpodsController: MacAirPodsController

    init(ipc: UnixSocketLineClient, initialMacInfo: String) {
        self.ipc = ipc
        self.macInfoJsonData = initialMacInfo.data(using: .utf8) ?? Data()
        self.airpodsController = MacAirPodsController(ipc: ipc)
        super.init()
        self.peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

        ipc.onConnected = { [weak self] in
            guard let self = self else { return }
            self.reportCurrentBleState()
            self.airpodsController.reportStatusIfNeeded(force: true)
        }

        ipc.onMessage = { [weak self] msg in
            DispatchQueue.main.async {
                guard let self = self, let type = msg["type"] as? String else { return }
                switch type {
                case "update_mac_info":
                    if let infoStr = msg["payload"] as? String {
                        self.macInfoJsonData = infoStr.data(using: .utf8) ?? Data()
                        if let infoChar = self.macInfoChar, !self.subscribedCentrals.isEmpty {
                            _ = self.peripheralManager.updateValue(self.macInfoJsonData, for: infoChar, onSubscribedCentrals: nil)
                        }
                    }
                case "frame_tx":
                    if let b64 = msg["base64"] as? String,
                       let rawData = Data(base64Encoded: b64) {
                        self.notifyFrameToPhone(rawData)
                    }
                case "airpods_config":
                    if let autoSw = msg["autoSwitch"] as? Bool {
                        self.airpodsController.autoSwitchEnabled = autoSw
                    }
                    if let pauseOther = msg["pauseOtherOnSwitch"] as? Bool {
                        self.airpodsController.pauseOtherOnSwitch = pauseOther
                    }
                    if let prefMac = msg["preferredMac"] as? String {
                        self.airpodsController.preferredMac = prefMac.isEmpty ? nil : prefMac
                    }
                    if let phoneConn = msg["phoneAirpodsConnected"] as? Bool {
                        self.airpodsController.phoneAirpodsConnected = phoneConn
                    }
                    if let phoneMac = msg["phoneAirpodsMac"] as? String {
                        self.airpodsController.phoneAirpodsMac = phoneMac.isEmpty ? nil : phoneMac
                    }
                    if let phoneName = msg["phoneAirpodsName"] as? String {
                        self.airpodsController.phoneAirpodsName = phoneName.isEmpty ? nil : phoneName
                    }
                    if let inCall = msg["phoneInCall"] as? Bool {
                        self.airpodsController.phoneInCall = inCall
                    }
                    self.airpodsController.reportStatusIfNeeded(force: false)
                case "airpods_connect_mac":
                    let reqId = msg["reqId"] as? String
                    let targetMac = msg["targetMac"] as? String
                    self.airpodsController.connectAirpodsToMac(targetMac: targetMac, reqId: reqId)
                case "airpods_release_mac":
                    let reqId = msg["reqId"] as? String
                    let pauseMedia = (msg["pauseMedia"] as? Bool) ?? true
                    let targetMac = msg["targetMac"] as? String
                    self.airpodsController.releaseAirpodsFromMac(pauseMedia: pauseMedia, targetMac: targetMac, reqId: reqId)
                case "airpods_suppress_grab":
                    let durationMs = (msg["durationMs"] as? Int) ?? 2500
                    self.airpodsController.suppressAutoGrab(durationMs: durationMs)
                case "airpods_get_status":
                    self.airpodsController.reportStatusIfNeeded(force: true)
                default:
                    break
                }
            }
        }
    }

    private func reportCurrentBleState() {
        let stateStr: String
        switch peripheralManager.state {
        case .poweredOn: stateStr = "poweredOn"
        case .poweredOff: stateStr = "poweredOff"
        case .unauthorized: stateStr = "unauthorized"
        case .unsupported: stateStr = "unsupported"
        default: stateStr = "unknown"
        }
        ipc.sendJson(["type": "ble_state", "state": stateStr])
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            setupServiceAndAdvertise()
        }
        reportCurrentBleState()
    }

    private func setupServiceAndAdvertise() {
        let infoChar = CBMutableCharacteristic(
            type: CHAR_MAC_INFO_UUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )
        let pipeChar = CBMutableCharacteristic(
            type: CHAR_FRAME_PIPE_UUID,
            properties: [.write, .writeWithoutResponse, .notify],
            value: nil,
            permissions: [.writeable, .readable]
        )
        self.macInfoChar = infoChar
        self.framePipeChar = pipeChar

        let service = CBMutableService(type: SERVICE_UUID, primary: true)
        service.characteristics = [infoChar, pipeChar]
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        peripheralManager.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            ipc.sendJson(["type": "ble_state", "state": "serviceAddError:\(error.localizedDescription)"])
            return
        }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [SERVICE_UUID],
            CBAdvertisementDataLocalNameKey: "OMS-Mac"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            ipc.sendJson(["type": "ble_state", "state": "advError:\(error.localizedDescription)"])
        } else {
            ipc.sendJson(["type": "ble_state", "state": "advertising"])
        }
    }

    private func announceCentralIfNeeded(_ central: CBCentral, reason: String) {
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        guard !announcedCentrals.contains(central.identifier) else { return }
        announcedCentrals.insert(central.identifier)
        ipc.sendJson(["type": "ble_state", "state": "centralReady:\(reason):mtu=\(central.maximumUpdateValueLength)"])
        ipc.sendJson([
            "type": "central_connected",
            "centralId": central.identifier.uuidString,
            "maxUpdateValueLength": central.maximumUpdateValueLength
        ])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == CHAR_MAC_INFO_UUID {
            if !subscribedCentrals.contains(where: { $0.identifier == request.central.identifier }) {
                subscribedCentrals.append(request.central)
            }
            if request.offset > macInfoJsonData.count {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = macInfoJsonData.subdata(in: request.offset..<macInfoJsonData.count)
            peripheral.respond(to: request, withResult: .success)
            let central = request.central
            if request.offset == 0 {
                announcedCentrals.remove(central.identifier)
                txQueue.removeAll()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                self?.announceCentralIfNeeded(central, reason: "readFallback")
            }
        } else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if !subscribedCentrals.contains(where: { $0.identifier == req.central.identifier }) {
                subscribedCentrals.append(req.central)
            }
            if req.characteristic.uuid == CHAR_FRAME_PIPE_UUID, let val = req.value {
                if let first = val.first, first == 0x7B,
                   let obj = try? JSONSerialization.jsonObject(with: val) as? [String: Any] {
                    if let rssi = obj["rssi"] as? Int {
                        ipc.sendJson(["type": "rssi", "rssi": rssi, "centralId": req.central.identifier.uuidString])
                    }
                    ipc.sendJson(["type": "oob_announce", "data": obj, "centralId": req.central.identifier.uuidString])
                } else {
                    ipc.sendJson([
                        "type": "frame_rx",
                        "base64": val.base64EncodedString(),
                        "centralId": req.central.identifier.uuidString
                    ])
                }
            }
            peripheral.respond(to: req, withResult: .success)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        announceCentralIfNeeded(central, reason: "subscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        announcedCentrals.remove(central.identifier)
        if subscribedCentrals.isEmpty {
            txQueue.removeAll()
            ipc.sendJson(["type": "central_disconnected", "centralId": central.identifier.uuidString])
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainTxQueue()
    }

    private func notifyFrameToPhone(_ data: Data) {
        guard !subscribedCentrals.isEmpty else { return }
        let mtu = max(20, subscribedCentrals.first?.maximumUpdateValueLength ?? 180)
        var offset = 0
        while offset < data.count {
            let end = min(offset + mtu, data.count)
            txQueue.append(data.subdata(in: offset..<end))
            offset = end
        }
        drainTxQueue()
    }

    private func drainTxQueue() {
        guard let pipeChar = framePipeChar, !subscribedCentrals.isEmpty else {
            txQueue.removeAll()
            return
        }
        while !txQueue.isEmpty {
            let slice = txQueue[0]
            let ok = peripheralManager.updateValue(slice, for: pipeChar, onSubscribedCentrals: nil)
            if ok {
                txQueue.removeFirst()
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) { [weak self] in
                    self?.drainTxQueue()
                }
                return
            }
        }
    }
}

var socketPath = "\(NSHomeDirectory())/.one_more_step/run/bt.sock"
var initialInfo = "{\"macId\":\"mac\",\"lanPort\":19527}"

let args = CommandLine.arguments
for i in 0..<args.count {
    if args[i] == "--socket", i + 1 < args.count {
        socketPath = args[i + 1]
    } else if args[i] == "--info", i + 1 < args.count {
        initialInfo = args[i + 1]
    }
}

let ipcClient = UnixSocketLineClient(socketPath: socketPath)
let bridge = OmsBluetoothBridge(ipc: ipcClient, initialMacInfo: initialInfo)
ipcClient.connectAndListen()
withExtendedLifetime(bridge) {
    RunLoop.main.run()
}
