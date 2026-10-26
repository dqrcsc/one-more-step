import Foundation
import CoreBluetooth
import Darwin

/// Module 02: macOS Native CoreBluetooth GATT + Near-Field Bridge for OneMoreStep
/// Communicates with TypeScript `omsd` over a local Unix Domain Socket (JSON Lines).

let SERVICE_UUID = CBUUID(string: "0000FD9A-0000-1000-8000-00805F9B34FB")
let CHAR_MAC_INFO_UUID = CBUUID(string: "0000FD9B-0000-1000-8000-00805F9B34FB")
let CHAR_FRAME_PIPE_UUID = CBUUID(string: "0000FD9C-0000-1000-8000-00805F9B34FB")

final class UnixSocketLineClient {
    private var fd: Int32 = -1
    private let socketPath: String
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
        guard fd >= 0,
              let data = try? JSONSerialization.data(withJSONObject: dict) else { return }
        var packet = data
        packet.append(0x0A)
        _ = packet.withUnsafeBytes { ptr in
            Darwin.write(fd, ptr.baseAddress!, packet.count)
        }
    }
}

final class OmsBluetoothBridge: NSObject, CBPeripheralManagerDelegate {
    private var peripheralManager: CBPeripheralManager!
    private var macInfoChar: CBMutableCharacteristic?
    private var framePipeChar: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []
    private let ipc: UnixSocketLineClient
    private var macInfoJsonData: Data

    init(ipc: UnixSocketLineClient, initialMacInfo: String) {
        self.ipc = ipc
        self.macInfoJsonData = initialMacInfo.data(using: .utf8) ?? Data()
        super.init()
        self.peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

        ipc.onConnected = { [weak self] in
            guard let self = self else { return }
            self.reportCurrentBleState()
        }

        ipc.onMessage = { [weak self] msg in
            DispatchQueue.main.async {
                guard let self = self, let type = msg["type"] as? String else { return }
                if type == "update_mac_info", let infoStr = msg["payload"] as? String {
                    self.macInfoJsonData = infoStr.data(using: .utf8) ?? Data()
                    if let infoChar = self.macInfoChar, !self.subscribedCentrals.isEmpty {
                        _ = self.peripheralManager.updateValue(self.macInfoJsonData, for: infoChar, onSubscribedCentrals: nil)
                    }
                } else if type == "frame_tx", let b64 = msg["base64"] as? String,
                          let rawData = Data(base64Encoded: b64) {
                    self.notifyFrameToPhone(rawData)
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
        guard error == nil else { return }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [SERVICE_UUID],
            CBAdvertisementDataLocalNameKey: "OMS-Mac"
        ])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == CHAR_MAC_INFO_UUID {
            if !subscribedCentrals.contains(where: { $0.identifier == request.central.identifier }) {
                subscribedCentrals.append(request.central)
                ipc.sendJson([
                    "type": "central_connected",
                    "centralId": request.central.identifier.uuidString,
                    "maxUpdateValueLength": request.central.maximumUpdateValueLength
                ])
            }
            if request.offset > macInfoJsonData.count {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = macInfoJsonData.subdata(in: request.offset..<macInfoJsonData.count)
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if req.characteristic.uuid == CHAR_FRAME_PIPE_UUID, let val = req.value {
                // Check if this is an OOB JSON control packet (starts with '{') or raw 8-byte binary frame (starts with 0x4F 'O')
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
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        ipc.sendJson(["type": "central_connected", "centralId": central.identifier.uuidString, "maxUpdateValueLength": central.maximumUpdateValueLength])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        if subscribedCentrals.isEmpty {
            ipc.sendJson(["type": "central_disconnected", "centralId": central.identifier.uuidString])
        }
    }

    private func notifyFrameToPhone(_ data: Data) {
        guard let pipeChar = framePipeChar, !subscribedCentrals.isEmpty else { return }
        let mtu = max(20, subscribedCentrals.first?.maximumUpdateValueLength ?? 180)
        var offset = 0
        while offset < data.count {
            let end = min(offset + mtu, data.count)
            let slice = data.subdata(in: offset..<end)
            let ok = peripheralManager.updateValue(slice, for: pipeChar, onSubscribedCentrals: nil)
            if !ok {
                Thread.sleep(forTimeInterval: 0.015)
                _ = peripheralManager.updateValue(slice, for: pipeChar, onSubscribedCentrals: nil)
            }
            offset = end
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
