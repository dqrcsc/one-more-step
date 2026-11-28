import CoreGraphics
import Foundation

@main
struct HidMathAndScreenBoundsTests {
    static func assertNear(_ a: CGFloat, _ b: CGFloat, eps: CGFloat = 0.01, _ msg: String) {
        if abs(a - b) > eps {
            fputs("FAIL: \(msg) (got \(a), expected \(b))\n", stderr)
            exit(1)
        }
    }

    static func main() {
        // 1. Quarter-pixel conversion
        assertNear(HidMath.quarterPxToPoints(10), 2.5, "quarterPxToPoints positive")
        assertNear(HidMath.quarterPxToPoints(-5), -1.25, "quarterPxToPoints negative")
        assertNear(HidMath.quarterPxToPoints(0), 0.0, "quarterPxToPoints zero")

        // 2. Multi-monitor clamping: Primary (0,0,1440,900) + Right External (1440,-180,2560,1440)
        let screens = [
            CGRect(x: 0, y: 0, width: 1440, height: 900),
            CGRect(x: 1440, y: -180, width: 2560, height: 1440),
        ]
        // Crossing seamlessly from primary into external display
        let across = HidMath.clampToDisplays(CGPoint(x: 1500, y: 200), displays: screens)
        assertNear(across.x, 1500, "crosses into right monitor X")
        assertNear(across.y, 200, "crosses into right monitor Y")

        // Sub-pixel 0.25pt micro-steps across monitor seam (1439 -> 1441)
        var cursor = CGPoint(x: 1439.0, y: 200.0)
        for _ in 0..<8 {
            let next = CGPoint(x: cursor.x + HidMath.quarterPxToPoints(1), y: cursor.y)
            cursor = HidMath.clampToDisplays(next, displays: screens)
        }
        assertNear(cursor.x, 1441.0, "micro-step crosses seam smoothly into external monitor")

        // Clamping beyond far-left edge of primary
        let clampedLeft = HidMath.clampToDisplays(CGPoint(x: -40, y: 400), displays: screens)
        assertNear(clampedLeft.x, 0, "clamps left edge")
        assertNear(clampedLeft.y, 400, "preserves valid Y")

        // Clamping above primary (y = -50 at x = 500 is closer to primary y=0 than external x=1440)
        let clampedTop = HidMath.clampToDisplays(CGPoint(x: 500, y: -50), displays: screens)
        assertNear(clampedTop.x, 500, "clamps top X on primary")
        assertNear(clampedTop.y, 0, "clamps top Y to 0 on primary")

        // Outer right/bottom clamping stays strictly inside CGRect.contains
        let clampedFarRight = HidMath.clampToDisplays(CGPoint(x: 5000, y: 2000), displays: screens)
        if !screens[1].contains(clampedFarRight) {
            fputs("FAIL: clampedFarRight \(clampedFarRight) should be contained within external screen\n", stderr)
            exit(1)
        }

        // 3. Laser trail pruning (180ms window)
        let samples: [LaserTrailSample] = [
            LaserTrailSample(point: CGPoint(x: 0, y: 0), timestampSec: 10.00),
            LaserTrailSample(point: CGPoint(x: 10, y: 10), timestampSec: 10.10),
            LaserTrailSample(point: CGPoint(x: 20, y: 20), timestampSec: 10.22),
        ]
        let pruned = HidMath.pruneTrail(samples, nowSec: 10.25, maxAgeSec: 0.18)
        if pruned.count != 2 || pruned.first?.point.x != 10 {
            fputs("FAIL: pruneTrail expected 2 recent samples\n", stderr)
            exit(1)
        }
        print("PASS: HidMathAndScreenBoundsTests")
    }
}
