import Foundation

/// Shared by the main queue and the serial Bluetooth worker. Native connection calls
/// already submitted to macOS cannot be withdrawn; subsequent effects must pass this gate.
final class AirPodsOperation {
    let reqId: String?
    let action: String
    private let deadline: TimeInterval
    private let now: () -> TimeInterval
    private let lock = NSLock()
    private var finished = false
    private let started: TimeInterval
    private var events: [[String: Any]] = []

    init(reqId: String?, action: String, budgetMs: Int,
         now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.reqId = reqId
        self.action = action
        self.now = now
        self.started = now()
        self.deadline = now() + Double(max(0, budgetMs)) / 1000
    }

    // HCI page timeouts use 0.625 ms slots, not milliseconds.
    static func pageTimeoutSlots(remainingMs: Int) -> UInt16 {
        UInt16(max(1, min(8000, remainingMs * 8 / 5)))
    }

    static func matchesDeviceUID(_ uid: String, mac: String) -> Bool {
        let address = mac.uppercased().replacingOccurrences(of: "-", with: ":")
        let normalized = uid.uppercased().replacingOccurrences(of: "-", with: ":")
        guard address.count == 17 else { return false }
        return normalized == address || normalized.hasPrefix(address + ":")
    }

    func record(_ stage: String, _ fields: [String: Any] = [:]) {
        lock.lock(); defer { lock.unlock() }
        var event = fields
        event["stage"] = stage
        event["elapsedMs"] = Int((now() - started) * 1000)
        if events.count < 48 { events.append(event) }
    }

    var diagnostics: [String: Any] {
        lock.lock(); defer { lock.unlock() }
        return ["operationId": reqId ?? "auto", "action": action,
                "elapsedMs": Int((now() - started) * 1000), "finished": finished, "events": events]
    }

    var remainingMs: Int {
        lock.lock(); defer { lock.unlock() }
        return finished ? 0 : max(0, Int((deadline - now()) * 1000))
    }

    func performIfActive(_ effect: () -> Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !finished && now() < deadline else { return false }
        return effect()
    }

    /// Exactly one result, including cancellation/timeout. Late successes become failures.
    func finish(success: Bool) -> Bool? {
        lock.lock(); defer { lock.unlock() }
        guard !finished else { return nil }
        finished = true
        return success && now() < deadline
    }
}
