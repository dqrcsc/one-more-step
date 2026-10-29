import Foundation
import Darwin

@main
struct AirPodsNativeRequestTests {
    static func main() throws {
        if CommandLine.arguments.contains("--guarded-child") || CommandLine.arguments.contains("--deadline-child") {
            signal(SIGTERM, SIG_IGN)
            let lifetime = AirPodsWorkerLifetime.start(budgetMs: CommandLine.arguments.contains("--deadline-child") ? 150 : 5000)
            withExtendedLifetime(lifetime) { while true { sleep(1) } }
        }
        if CommandLine.arguments.contains("--parent-child") {
            let child = Process()
            child.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
            child.arguments = ["--guarded-child"]
            child.standardOutput = FileHandle.nullDevice
            child.standardError = FileHandle.nullDevice
            child.standardInput = FileHandle.nullDevice
            try child.run()
            let data = try JSONSerialization.data(withJSONObject: ["workerPid": Int(child.processIdentifier)])
            FileHandle.standardOutput.write(data)
            // Abrupt parent loss: no graceful cancellation or deinit cleanup.
            _exit(0)
        }
        if CommandLine.arguments.contains("--hang-child") {
            signal(SIGTERM, SIG_IGN)
            while true { sleep(1) }
        }
        if CommandLine.arguments.contains("--success-child") {
            FileHandle.standardOutput.write(Data("{\"returnCode\":0}".utf8))
            return
        }
        func waitUntil(_ condition: () -> Bool, seconds: Double) {
            let end = Date().addingTimeInterval(seconds)
            while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
            assert(condition(), "Worker did not finish within deadline")
        }
        var exits = 0
        let stuck = AirPodsNativeRequest(executable: CommandLine.arguments[0], arguments: ["--hang-child"]) { status, _ in
            assert(status != 0)
            exits += 1
        }
        try stuck.start()
        let firstPid = stuck.pid
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        stuck.cancel()
        stuck.cancel()
        waitUntil({ exits == 1 }, seconds: 3)
        assert(!stuck.isRunning)
        var finished = false
        let fresh = AirPodsNativeRequest(executable: CommandLine.arguments[0], arguments: ["--success-child"]) { status, result in
            assert(status == 0 && result?["returnCode"] as? Int == 0)
            finished = true
        }
        try fresh.start()
        assert(fresh.pid != firstPid)
        waitUntil({ finished }, seconds: 3)
        assert(exits == 1)
        fresh.cancel()
        var deadlineExit: Int32?
        let bounded = AirPodsNativeRequest(executable: CommandLine.arguments[0], arguments: ["--deadline-child"]) { status, _ in deadlineExit = status }
        try bounded.start()
        waitUntil({ deadlineExit != nil }, seconds: 2)
        assert(deadlineExit == 124)
        var orphanPid: Int32?
        let parent = AirPodsNativeRequest(executable: CommandLine.arguments[0], arguments: ["--parent-child"]) { status, result in
            assert(status == 0)
            orphanPid = (result?["workerPid"] as? Int).map(Int32.init)
        }
        try parent.start()
        waitUntil({ orphanPid != nil }, seconds: 2)
        // The child has a five-second deadline; earlier exit proves parent loss is detected.
        waitUntil({ Darwin.kill(orphanPid!, 0) == -1 && errno == ESRCH }, seconds: 2)
        print("Native worker: hung request reaped, one completion, fresh request succeeds, independent deadline and parent death enforced")
    }
}
