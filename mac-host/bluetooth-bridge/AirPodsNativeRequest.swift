import Foundation
import Darwin

/// Each request owns a fresh IOBluetooth client process, so a timed-out native
/// client cannot retain connection state used by the next request.
final class AirPodsNativeRequest {
    private let process = Process()
    private let output = Pipe()
    var pid: Int32 { process.processIdentifier }
    var isRunning: Bool { process.isRunning }

    init(executable: String, arguments: [String], completion: @escaping (Int32, [String: Any]?) -> Void) {
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        process.standardInput = FileHandle.nullDevice
        let pipe = output
        process.terminationHandler = { child in
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let result = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            DispatchQueue.main.async { completion(child.terminationStatus, result) }
        }
    }

    func start() throws { try process.run() }

    func cancel() {
        guard process.isRunning else { return }
        process.terminate()
        let child = process
        // SIGTERM may not interrupt a blocked native call. Only this owned
        // Process is eligible for escalation; never look up a process by name.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            if child.isRunning { Darwin.kill(child.processIdentifier, SIGKILL) }
        }
    }
}


/// Independent of the bridge's event loop: even if the parent exits before
/// delivering cancellation, a blocked native client must not remain alive.
enum AirPodsWorkerLifetime {
    static func start(budgetMs: Int) -> DispatchSourceTimer {
        let parent = getppid()
        let deadline = ProcessInfo.processInfo.systemUptime + Double(max(0, budgetMs)) / 1000
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now(), repeating: .milliseconds(100))
        timer.setEventHandler {
            if parent <= 1 || getppid() != parent { _exit(125) }
            if ProcessInfo.processInfo.systemUptime >= deadline { _exit(124) }
        }
        timer.resume()
        return timer
    }
}
