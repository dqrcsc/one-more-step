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
    var onDisconnected: (() -> Void)?

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
                    DispatchQueue.main.async { self.onDisconnected?() }
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
    private var airpodsOperation: AirPodsOperation?
    private var nativeAirpodsRequest: AirPodsNativeRequest?
    private var lastReportedStatusKey: String = ""
    private var lastDefaultOutputName: String = ""
    private var cachedPairedAirpods: [PairedAirPodsInfo] = []
    private var lastPairedAirpodsRefreshAt: Date = .distantPast
    private var pollTimer: Timer? = nil

    private var mediaRemoteSendCommand: (@convention(c) (UInt32, AnyObject?) -> Bool)? = nil
    private var mediaRemoteGetIsPlaying: (@convention(c) (DispatchQueue, @escaping @convention(block) (Bool) -> Void) -> Void)? = nil

    init(ipc: UnixSocketLineClient) {
        self.ipc = ipc
        loadMediaRemoteSymbol()
        updateFallbackNonAirpodsSpeaker()
        setupCoreAudioListeners()
        startPollingTimer()
    }

    private func loadMediaRemoteSymbol() {
        if let handle = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW) {
            if let sym = dlsym(handle, "MRMediaRemoteSendCommand") {
                typealias Fn = @convention(c) (UInt32, AnyObject?) -> Bool
                self.mediaRemoteSendCommand = unsafeBitCast(sym, to: Fn.self)
            }
            if let symPlaying = dlsym(handle, "MRMediaRemoteGetNowPlayingApplicationIsPlaying") {
                typealias GetIsPlayingFn = @convention(c) (DispatchQueue, @escaping @convention(block) (Bool) -> Void) -> Void
                self.mediaRemoteGetIsPlaying = unsafeBitCast(symPlaying, to: GetIsPlayingFn.self)
            }
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

    func getDeviceUID(_ id: AudioDeviceID) -> String {
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceUID,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr,
              let uid = value?.takeRetainedValue() else { return "" }
        return uid as String
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
        if !normExplicit.isEmpty {
            return all.first(where: { $0.mac == normExplicit })
        }
        return all.first
    }

    func findAirpodsCoreAudioDevice(preferredName: String?, targetMac: String? = nil) -> (id: AudioDeviceID, name: String)? {
        let outputs = listOutputDevices()
        if let mac = targetMac, !mac.isEmpty {
            let matches = outputs.filter { AirPodsOperation.matchesDeviceUID(getDeviceUID($0.id), mac: mac) }
            return matches.count == 1 ? matches[0] : nil
        }
        if let pref = preferredName, !pref.isEmpty {
            let exact = outputs.filter { $0.name.caseInsensitiveCompare(pref) == .orderedSame }
            if exact.count == 1 { return exact[0] }
            return nil
        }
        return outputs.first(where: { $0.name.localizedCaseInsensitiveContains("airpods") })
    }

    // MARK: - Media Control (Explicit Pause Only, Never Launches Music.app)

    func pauseMacActiveMedia(operation: AirPodsOperation? = nil) {
        let defId = getDefaultOutputDevice()
        // Never send any media command if Mac audio output is not actively running
        guard isDeviceRunning(defId) else { return }

        // Only send kMRPause = 1 if MediaRemote confirms an active NowPlaying client is currently playing.
        // We strictly avoid posting NX_KEYTYPE_PLAY or calling MRMediaRemoteSendCommand when idle,
        // because macOS rcd/mediaremoted would otherwise auto-launch Apple Music (Music.app)!
        if let checkPlaying = mediaRemoteGetIsPlaying {
            checkPlaying(DispatchQueue.main) { [weak self] isPlaying in
                guard isPlaying, operation == nil || operation!.remainingMs > 0 else { return }
                _ = self?.mediaRemoteSendCommand?(1, nil)
            }
        }
    }

    // MARK: - Connect & Release Operations

    private func finishOperation(_ op: AirPodsOperation, ok: Bool, message: String,
                                 deviceName: String? = nil, deviceMac: String? = nil) {
        guard let confirmed = op.finish(success: ok) else { return }
        op.record("finished", ["ok": confirmed, "message": message])
        if airpodsOperation === op {
            if let request = nativeAirpodsRequest, request.isRunning {
                op.record("worker_cancel_requested", ["pid": Int(request.pid)])
                request.cancel()
            }
            nativeAirpodsRequest = nil
            airpodsOperation = nil
            restoreTempMute()
            reportStatusIfNeeded(force: true)
        }
        if let reqId = op.reqId {
            var result: [String: Any] = ["type": "airpods_cmd_result", "reqId": reqId,
                                        "ok": confirmed, "action": op.action, "diagnostics": op.diagnostics,
                                        "message": confirmed ? message : (ok ? "AirPods 请求已过期" : message)]
            if let name = deviceName { result["deviceName"] = name }
            if let mac = deviceMac { result["deviceMac"] = mac }
            ipc.sendJson(result)
        }
    }

    func cancelAirpodsOperation(reqId: String? = nil) {
        guard let op = airpodsOperation, reqId == nil || op.reqId == reqId else { return }
        finishOperation(op, ok: false, message: "AirPods 请求已取消或被新请求替代")
    }

    private func beginOperation(reqId: String?, action: String, deadlineMs: Double?, capMs: Int) -> AirPodsOperation {
        cancelAirpodsOperation()
        let remaining = deadlineMs.map { $0 - Date().timeIntervalSince1970 * 1000 } ?? Double(capMs)
        let budget = Int(min(Double(capMs), max(0, remaining)))
        let op = AirPodsOperation(reqId: reqId, action: action, budgetMs: budget)
        airpodsOperation = op
        op.record("started", ["budgetMs": budget])
        suppressAutoGrabUntil = Date().addingTimeInterval(Double(budget + 1000) / 1000)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(budget)) { [weak self] in
            self?.finishOperation(op, ok: false, message: "AirPods 操作超时，后续步骤已停止")
        }
        return op
    }

    func connectAirpodsToMac(targetMac: String?, reqId: String?, deadlineMs: Double? = nil) {
        let op = beginOperation(reqId: reqId, action: "connect_mac", deadlineMs: deadlineMs, capMs: 22000)
        guard op.remainingMs > 0 else {
            finishOperation(op, ok: false, message: "AirPods 请求已过期")
            return
        }
        guard let target = selectTargetAirpods(explicitMac: targetMac) else {
            finishOperation(op, ok: false, message: "Mac 配对列表中未找到指定 AirPods")
            return
        }
        lastActiveAirpodsMac = target.mac
        op.record("target_selected", ["name": target.name, "connected": target.connected])
        requestAirpodsConnection(op, target: target)
    }

    private func requestAirpodsConnection(_ op: AirPodsOperation, target: PairedAirPodsInfo) {
        guard op.remainingMs > 0 else { return }
        if let device = IOBluetoothDevice(addressString: Self.toIOBluetoothAddress(target.mac)), device.isConnected() {
            pollAirpodsOutput(op, target: target, until: ProcessInfo.processInfo.systemUptime + 2.0)
            return
        }
        let worker = AirPodsNativeRequest(executable: CommandLine.arguments[0],
            arguments: ["--airpods-connect-worker", target.mac, String(op.remainingMs)]) { [weak self] status, native in
            guard let self = self else { return }
            var fields = native ?? [:]
            fields["exitStatus"] = Int(status)
            fields["late"] = op.remainingMs == 0
            op.record("worker_exited", fields)
            self.ipc.sendJson(["type": "airpods_diagnostics", "diagnostics": op.diagnostics])
            guard op.remainingMs > 0, self.airpodsOperation === op else { return }
            self.nativeAirpodsRequest = nil
            self.pollAirpodsOutput(op, target: target, until: ProcessInfo.processInfo.systemUptime + 2.0)
        }
        nativeAirpodsRequest = worker
        do {
            try worker.start()
            op.record("worker_started", ["pid": Int(worker.pid), "remainingMs": op.remainingMs])
        } catch {
            finishOperation(op, ok: false, message: "无法启动原生蓝牙连接进程")
        }
    }

    private func pollAirpodsOutput(_ op: AirPodsOperation, target: PairedAirPodsInfo, until: TimeInterval) {
        guard op.remainingMs > 0 else { return }
        let routed = op.performIfActive {
            guard let device = IOBluetoothDevice(addressString: Self.toIOBluetoothAddress(target.mac)), device.isConnected(),
                  let audio = findAirpodsCoreAudioDevice(preferredName: target.name, targetMac: target.mac) else { return false }
            return setDefaultOutputDevice(audio.id) && getDefaultOutputDevice() == audio.id
        }
        if routed {
            finishOperation(op, ok: true, message: "已连接指定 AirPods 并确认 Mac 默认音频输出", deviceName: target.name, deviceMac: target.mac)
        } else if ProcessInfo.processInfo.systemUptime < until {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in
                self?.pollAirpodsOutput(op, target: target, until: until)
            }
        } else {
            finishOperation(op, ok: false, message: "AirPods 连接或默认音频输出未确认")
        }
    }

    func releaseAirpodsFromMac(pauseMedia: Bool, targetMac: String?, reqId: String?, deadlineMs: Double? = nil) {
        let op = beginOperation(reqId: reqId, action: "release_mac", deadlineMs: deadlineMs, capMs: 7000)
        // Allow the phone's following 12-second connect stage without immediately grabbing back.
        suppressAutoGrab(durationMs: 22000)
        guard op.remainingMs > 0 else { finishOperation(op, ok: false, message: "AirPods 请求已过期"); return }
        let paired = listPairedAirpods(forceRefresh: true)
        let normTarget = Self.normalizeMac(targetMac)
        let targets = paired.filter { normTarget.isEmpty ? $0.connected : $0.mac == normTarget }
        updateFallbackNonAirpodsSpeaker()
        if pauseMedia { pauseMacActiveMedia(operation: op) }
        let defaultUID = getDeviceUID(getDefaultOutputDevice())
        if targets.contains(where: { AirPodsOperation.matchesDeviceUID(defaultUID, mac: $0.mac) }), lastNonAirpodsOutputDeviceId != 0 {
            tempMuteDeviceIfNeeded(lastNonAirpodsOutputDeviceId, maxDurationSec: 1.0)
            _ = setDefaultOutputDevice(lastNonAirpodsOutputDeviceId)
        }
        btWorkQueue.async { [weak self] in
            guard let self = self else { return }
            for item in targets {
                guard op.remainingMs > 0 else { return }
                if let dev = IOBluetoothDevice(addressString: Self.toIOBluetoothAddress(item.mac)), dev.isConnected() {
                    op.record("close_connection")
                    let code = dev.closeConnection()
                    op.record("close_returned", ["returnCode": Int(code), "returnHex": String(format: "0x%08x", code)])
                }
            }
            while op.remainingMs > 0 {
                let released = targets.allSatisfy { item in
                    guard let dev = IOBluetoothDevice(addressString: Self.toIOBluetoothAddress(item.mac)) else { return false }
                    return !dev.isConnected()
                }
                if released {
                    DispatchQueue.main.async { self.finishOperation(op, ok: true, message: "Mac 已确认释放指定 AirPods", deviceName: targets.first?.name, deviceMac: targets.first?.mac) }
                    return
                }
                Thread.sleep(forTimeInterval: 0.1)
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

        ipc.onDisconnected = { [weak self] in self?.airpodsController.cancelAirpodsOperation() }

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
                    self.airpodsController.connectAirpodsToMac(targetMac: targetMac, reqId: reqId, deadlineMs: msg["deadlineMs"] as? Double)
                case "airpods_release_mac":
                    let reqId = msg["reqId"] as? String
                    let pauseMedia = (msg["pauseMedia"] as? Bool) ?? true
                    let targetMac = msg["targetMac"] as? String
                    self.airpodsController.releaseAirpodsFromMac(pauseMedia: pauseMedia, targetMac: targetMac, reqId: reqId, deadlineMs: msg["deadlineMs"] as? Double)
                case "airpods_cancel":
                    if let reqId = msg["reqId"] as? String { self.airpodsController.cancelAirpodsOperation(reqId: reqId) }
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
            announceCentralIfNeeded(req.central, reason: "write")
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

// Internal connection worker: no IPC server, audio routing or retry loop.
// The parent owns the deadline and reaps this process on cancellation.
if CommandLine.arguments.count > 1 && CommandLine.arguments[1] == "--airpods-connect-worker" {
    let args = CommandLine.arguments
    guard args.count == 4, args[2].range(of: "^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$", options: .regularExpression) != nil,
          let budget = Int(args[3]), budget > 0, budget <= 22000,
          let device = IOBluetoothDevice(addressString: args[2].replacingOccurrences(of: ":", with: "-")), device.isPaired() else { exit(2) }
    let lifetime = AirPodsWorkerLifetime.start(budgetMs: budget)
    withExtendedLifetime(lifetime) {
        let started = ProcessInfo.processInfo.systemUptime
        let code = device.openConnection(nil, withPageTimeout: AirPodsOperation.pageTimeoutSlots(remainingMs: budget), authenticationRequired: false)
        let result: [String: Any] = ["returnCode": Int(code), "returnHex": String(format: "0x%08x", code),
            "nativeElapsedMs": Int((ProcessInfo.processInfo.systemUptime - started) * 1000), "connected": device.isConnected()]
        if let data = try? JSONSerialization.data(withJSONObject: result) {
            FileHandle.standardOutput.write(data)
        }
    }
    exit(0)
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
