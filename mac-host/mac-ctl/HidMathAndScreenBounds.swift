import CoreGraphics
import Foundation

struct LaserTrailSample {
    let point: CGPoint
    let timestampSec: TimeInterval
}

enum HidMath {
    private static let displayEdgeEpsilon: CGFloat = 0.001

    static func quarterPxToPoints(_ raw: Int) -> CGFloat {
        return CGFloat(raw) * 0.25
    }

    /// Clamp a global CoreGraphics point (top-left origin) into the nearest active display rect,
    /// allowing seamless sub-pixel crossing across shared edges of multi-monitor layouts.
    static func clampToDisplays(_ target: CGPoint, displays: [CGRect]) -> CGPoint {
        guard !displays.isEmpty else {
            return CGPoint(x: max(0, target.x), y: max(0, target.y))
        }
        for rect in displays {
            let maxX = max(rect.minX, rect.maxX - displayEdgeEpsilon)
            let maxY = max(rect.minY, rect.maxY - displayEdgeEpsilon)
            if target.x >= rect.minX && target.x <= maxX &&
               target.y >= rect.minY && target.y <= maxY {
                return target
            }
        }
        var bestPoint = target
        var bestDistSq = CGFloat.greatestFiniteMagnitude
        for rect in displays {
            let maxX = max(rect.minX, rect.maxX - displayEdgeEpsilon)
            let maxY = max(rect.minY, rect.maxY - displayEdgeEpsilon)
            let cx = min(max(target.x, rect.minX), maxX)
            let cy = min(max(target.y, rect.minY), maxY)
            let dx = target.x - cx
            let dy = target.y - cy
            let d2 = dx * dx + dy * dy
            if d2 < bestDistSq {
                bestDistSq = d2
                bestPoint = CGPoint(x: cx, y: cy)
            }
        }
        return bestPoint
    }

    static func pruneTrail(
        _ samples: [LaserTrailSample],
        nowSec: TimeInterval,
        maxAgeSec: TimeInterval = 0.18,
        maxPoints: Int = 36
    ) -> [LaserTrailSample] {
        let cutoff = nowSec - maxAgeSec
        let filtered = samples.filter { $0.timestampSec >= cutoff }
        if filtered.count <= maxPoints {
            return filtered
        }
        return Array(filtered.suffix(maxPoints))
    }
}
