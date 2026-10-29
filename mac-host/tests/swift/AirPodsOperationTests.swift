import Foundation

@main
struct AirPodsOperationTests {
    static func main() {
        var time: TimeInterval = 10
        let expired = AirPodsOperation(reqId: "timeout", action: "connect", budgetMs: 500, now: { time })
        assert(expired.remainingMs == 500)
        time = 10.5
        var effects = 0
        assert(!expired.performIfActive { effects += 1; return true })
        assert(expired.finish(success: true) == false)
        assert(effects == 0)
        assert(expired.finish(success: true) == nil)

        let cancelled = AirPodsOperation(reqId: "old", action: "connect", budgetMs: 1000)
        assert(cancelled.finish(success: false) == false)
        assert(!cancelled.performIfActive { effects += 1; return true })
        assert(cancelled.remainingMs == 0)
        assert(cancelled.finish(success: true) == nil)

        let fresh = AirPodsOperation(reqId: "new", action: "connect", budgetMs: 1000)
        assert(fresh.performIfActive { effects += 1; return true })
        assert(fresh.finish(success: true) == true)
        assert(fresh.finish(success: false) == nil)
        assert(effects == 1)

        let failedRoute = AirPodsOperation(reqId: "route", action: "connect", budgetMs: 1000)
        assert(!failedRoute.performIfActive { false })
        assert(failedRoute.finish(success: false) == false)
        let emptyBudget = AirPodsOperation(reqId: nil, action: "connect", budgetMs: 0)
        assert(!emptyBudget.performIfActive { fatalError("Expired operation ran") })
        assert(AirPodsOperation.pageTimeoutSlots(remainingMs: 5000) == 8000)
        assert(AirPodsOperation.pageTimeoutSlots(remainingMs: 100) == 160)
        assert(AirPodsOperation.pageTimeoutSlots(remainingMs: 22000) == 8000)
        assert(AirPodsOperation.matchesDeviceUID("B4-59-76-DB-BF-81:output", mac: "B4:59:76:DB:BF:81"))
        assert(AirPodsOperation.matchesDeviceUID("b4:59:76:db:bf:81", mac: "B4:59:76:DB:BF:81"))
        assert(!AirPodsOperation.matchesDeviceUID("5C-1B-F4-42-58-BF:output", mac: "B4:59:76:DB:BF:81"))
        assert(!AirPodsOperation.matchesDeviceUID("B4-59-76-DB-BF-810:output", mac: "B4:59:76:DB:BF:81"))
        assert(!AirPodsOperation.matchesDeviceUID("AirPods", mac: "B4:59:76:DB:BF:81"))
        let traced = AirPodsOperation(reqId: "trace", action: "connect", budgetMs: 1000)
        for _ in 0..<60 { traced.record("request_returned", ["code": 1]) }
        assert((traced.diagnostics["events"] as? [[String: Any]])?.count == 48)
        assert(traced.finish(success: false) == false)
        assert(!traced.performIfActive { fatalError("Diagnostics revived a finished request") })
        print("AirPods operation: expiry, cancellation, late result, new operation, failed route, empty budget, HCI units passed")
    }
}
