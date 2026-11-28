import AppKit
import CoreGraphics
import Foundation
import QuartzCore

final class LaserCanvasView: NSView {
    var mode: Int = 0 // 0 = off, 1 = red laser, 2 = spotlight
    var localCursorPoint: CGPoint? = nil
    var localTrail: [LaserTrailSample] = []
    var spotlightRadius: CGFloat = 140
    var nowSec: TimeInterval = CACurrentMediaTime()

    override var isOpaque: Bool { false }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext, mode != 0 else { return }
        ctx.clear(bounds)

        if mode == 2 {
            // Spotlight: 58% dark overlay across all displays; cut out smooth circular evenOdd aperture
            ctx.saveGState()
            ctx.setFillColor(NSColor.black.withAlphaComponent(0.58).cgColor)
            let path = CGMutablePath()
            path.addRect(bounds)
            let r = max(60, min(320, spotlightRadius))
            if let pt = localCursorPoint {
                let hole = CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)
                path.addEllipse(in: hole)
            }
            ctx.addPath(path)
            ctx.fillPath(using: .evenOdd)

            if let pt = localCursorPoint {
                let outerGlow = CGRect(x: pt.x - r - 1.5, y: pt.y - r - 1.5, width: (r + 1.5) * 2, height: (r + 1.5) * 2)
                ctx.setStrokeColor(NSColor.white.withAlphaComponent(0.22).cgColor)
                ctx.setLineWidth(5.0)
                ctx.strokeEllipse(in: outerGlow)

                let ring = CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)
                ctx.setStrokeColor(NSColor.white.withAlphaComponent(0.68).cgColor)
                ctx.setLineWidth(2.2)
                ctx.strokeEllipse(in: ring)
            }
            ctx.restoreGState()
            return
        }

        // Mode 1: Red Laser Dot + 180ms Fading Glow Trail
        if localTrail.count >= 2 {
            for i in 1..<localTrail.count {
                let prev = localTrail[i - 1]
                let curr = localTrail[i]
                let age = max(0, nowSec - curr.timestampSec)
                let progress = max(0, 1.0 - (age / 0.18))
                guard progress > 0.02 else { continue }

                ctx.saveGState()
                ctx.setLineCap(.round)
                ctx.setLineWidth(CGFloat(6.0 + 10.0 * progress))
                ctx.setStrokeColor(NSColor(calibratedRed: 1.0, green: 0.08, blue: 0.14, alpha: CGFloat(0.18 * progress)).cgColor)
                ctx.move(to: prev.point)
                ctx.addLine(to: curr.point)
                ctx.strokePath()

                ctx.setLineWidth(CGFloat(2.8 + 6.8 * progress))
                ctx.setStrokeColor(NSColor(calibratedRed: 1.0, green: 0.16, blue: 0.20, alpha: CGFloat(0.52 * progress)).cgColor)
                ctx.move(to: prev.point)
                ctx.addLine(to: curr.point)
                ctx.strokePath()
                ctx.restoreGState()
            }
        }

        if let pt = localCursorPoint {
            ctx.saveGState()
            // Outer soft red halo
            let haloR: CGFloat = 18
            ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.10, blue: 0.14, alpha: 0.28).cgColor)
            ctx.fillEllipse(in: CGRect(x: pt.x - haloR, y: pt.y - haloR, width: haloR * 2, height: haloR * 2))

            // Mid vivid red body
            let midR: CGFloat = 9.5
            ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.12, blue: 0.16, alpha: 0.92).cgColor)
            ctx.fillEllipse(in: CGRect(x: pt.x - midR, y: pt.y - midR, width: midR * 2, height: midR * 2))

            // Hot white-pink core
            let coreR: CGFloat = 4.2
            ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.94, blue: 0.94, alpha: 1.0).cgColor)
            ctx.fillEllipse(in: CGRect(x: pt.x - coreR, y: pt.y - coreR, width: coreR * 2, height: coreR * 2))
            ctx.restoreGState()
        }
    }
}

final class LaserOverlayController {
    static let shared = LaserOverlayController()

    private struct ScreenWindowEntry {
        let panel: NSPanel
        let canvas: LaserCanvasView
        let cgBounds: CGRect
        let nsFrame: NSRect
    }

    private var entries: [ScreenWindowEntry] = []
    private var mode: Int = 0
    private var spotlightRadius: CGFloat = 140
    private var globalPoint: CGPoint = .zero
    private var globalTrail: [LaserTrailSample] = []
    private var lastHeartbeatSec: TimeInterval = 0
    private var renderTimer: Timer? = nil
    private let watchdogTimeoutSec: TimeInterval = 1.8

    private init() {
        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            self.rebuildWindowsOnMain()
            if self.mode > 0 {
                for e in self.entries {
                    e.panel.alphaValue = 1.0
                    e.panel.orderFrontRegardless()
                }
                self.renderFrameOnMain(nowSec: CACurrentMediaTime())
            }
        }
    }

    static func currentGlobalDisplayRects() -> [CGRect] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else {
            return []
        }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else {
            return []
        }
        return ids.prefix(Int(count)).map { CGDisplayBounds($0) }
    }

    func updateOnMain(mode: Int, globalCursor: CGPoint, spotlightRadius: CGFloat) {
        precondition(Thread.isMainThread)
        let now = CACurrentMediaTime()
        if mode <= 0 {
            hideImmediateOnMain()
            return
        }
        self.mode = mode
        if spotlightRadius > 0 {
            self.spotlightRadius = min(320, max(60, spotlightRadius))
        }
        self.globalPoint = globalCursor
        self.lastHeartbeatSec = now
        if mode == 1 {
            self.globalTrail.append(LaserTrailSample(point: globalCursor, timestampSec: now))
            self.globalTrail = HidMath.pruneTrail(self.globalTrail, nowSec: now)
        } else {
            self.globalTrail.removeAll(keepingCapacity: true)
        }

        if entries.isEmpty {
            rebuildWindowsOnMain()
        }
        for e in entries {
            e.panel.alphaValue = 1.0
            if !e.panel.isVisible {
                e.panel.orderFrontRegardless()
            }
        }
        ensureTimerRunningOnMain()
        renderFrameOnMain(nowSec: now)
    }

    func hideImmediateOnMain() {
        precondition(Thread.isMainThread)
        mode = 0
        globalTrail.removeAll(keepingCapacity: true)
        renderTimer?.invalidate()
        renderTimer = nil
        for e in entries {
            e.canvas.mode = 0
            e.canvas.localCursorPoint = nil
            e.canvas.localTrail = []
            e.canvas.needsDisplay = true
            e.panel.orderOut(nil)
        }
    }

    private func rebuildWindowsOnMain() {
        for e in entries {
            e.panel.orderOut(nil)
            e.panel.close()
        }
        entries.removeAll()

        let screens = NSScreen.screens
        guard let primaryHeight = screens.first?.frame.height else { return }

        for screen in screens {
            let nsFrame = screen.frame
            // Convert AppKit bottom-left screen frame to CoreGraphics top-left global bounds
            let cgY = primaryHeight - nsFrame.maxY
            let cgBounds = CGRect(x: nsFrame.minX, y: cgY, width: nsFrame.width, height: nsFrame.height)

            let panel = NSPanel(
                contentRect: nsFrame,
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            panel.isReleasedWhenClosed = false
            panel.level = .screenSaver
            panel.backgroundColor = .clear
            panel.isOpaque = false
            panel.hasShadow = false
            panel.ignoresMouseEvents = true
            panel.hidesOnDeactivate = false
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]

            let canvas = LaserCanvasView(frame: NSRect(origin: .zero, size: nsFrame.size))
            canvas.autoresizingMask = [.width, .height]
            panel.contentView = canvas

            entries.append(ScreenWindowEntry(panel: panel, canvas: canvas, cgBounds: cgBounds, nsFrame: nsFrame))
        }
    }

    private func ensureTimerRunningOnMain() {
        guard renderTimer == nil else { return }
        let timer = Timer(timeInterval: 1.0 / 90.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let now = CACurrentMediaTime()
            if self.mode <= 0 || (now - self.lastHeartbeatSec) > self.watchdogTimeoutSec {
                self.hideImmediateOnMain()
                return
            }
            if self.mode == 1 {
                let prevCount = self.globalTrail.count
                self.globalTrail = HidMath.pruneTrail(self.globalTrail, nowSec: now)
                if prevCount > 1 || self.globalTrail.count > 1 {
                    self.renderFrameOnMain(nowSec: now)
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        renderTimer = timer
    }

    private func renderFrameOnMain(nowSec: TimeInterval) {
        let overlapMargin: CGFloat = (mode == 2) ? (spotlightRadius + 8) : 24
        for e in entries {
            e.canvas.mode = mode
            e.canvas.spotlightRadius = spotlightRadius
            e.canvas.nowSec = nowSec

            if e.cgBounds.insetBy(dx: -overlapMargin, dy: -overlapMargin).contains(globalPoint) {
                let lx = globalPoint.x - e.cgBounds.minX
                let ly = e.cgBounds.height - (globalPoint.y - e.cgBounds.minY)
                e.canvas.localCursorPoint = CGPoint(x: lx, y: ly)
            } else {
                e.canvas.localCursorPoint = nil
            }

            if mode == 1 && !globalTrail.isEmpty {
                e.canvas.localTrail = globalTrail.compactMap { sample in
                    let lx = sample.point.x - e.cgBounds.minX
                    let ly = e.cgBounds.height - (sample.point.y - e.cgBounds.minY)
                    if lx >= -32 && lx <= e.cgBounds.width + 32 && ly >= -32 && ly <= e.cgBounds.height + 32 {
                        return LaserTrailSample(point: CGPoint(x: lx, y: ly), timestampSec: sample.timestampSec)
                    }
                    return nil
                }
            } else {
                e.canvas.localTrail = []
            }
            e.canvas.needsDisplay = true
        }
    }
}
