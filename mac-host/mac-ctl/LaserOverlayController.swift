import AppKit
import CoreGraphics
import Foundation
import QuartzCore

final class LaserCanvasView: NSView {
    private static let sharedDotImage: CGImage? = makeLaserDotImage()

    let solidDimLayer = CALayer()
    let spotlightLayer = CALayer()
    let outerTrailLayer = CAShapeLayer()
    let innerTrailLayer = CAShapeLayer()
    let dotLayer = CALayer()

    override var isOpaque: Bool { false }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupLayers()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupLayers()
    }

    private func setupLayers() {
        wantsLayer = true
        layerContentsRedrawPolicy = .never
        guard let root = self.layer else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        root.backgroundColor = CGColor.clear
        root.masksToBounds = true

        solidDimLayer.backgroundColor = NSColor.black.withAlphaComponent(0.58).cgColor
        solidDimLayer.frame = bounds
        solidDimLayer.isHidden = true
        root.addSublayer(solidDimLayer)

        spotlightLayer.contentsScale = 1.0
        spotlightLayer.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        spotlightLayer.isHidden = true
        root.addSublayer(spotlightLayer)

        outerTrailLayer.fillColor = nil
        outerTrailLayer.lineCap = .round
        outerTrailLayer.lineJoin = .round
        outerTrailLayer.lineWidth = 11.0
        outerTrailLayer.strokeColor = NSColor(calibratedRed: 1.0, green: 0.08, blue: 0.14, alpha: 0.24).cgColor
        outerTrailLayer.masksToBounds = false
        outerTrailLayer.frame = bounds
        outerTrailLayer.isHidden = true
        root.addSublayer(outerTrailLayer)

        innerTrailLayer.fillColor = nil
        innerTrailLayer.lineCap = .round
        innerTrailLayer.lineJoin = .round
        innerTrailLayer.lineWidth = 4.8
        innerTrailLayer.strokeColor = NSColor(calibratedRed: 1.0, green: 0.18, blue: 0.22, alpha: 0.68).cgColor
        innerTrailLayer.masksToBounds = false
        innerTrailLayer.frame = bounds
        innerTrailLayer.isHidden = true
        root.addSublayer(innerTrailLayer)

        dotLayer.bounds = CGRect(x: 0, y: 0, width: 48, height: 48)
        dotLayer.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        dotLayer.contentsScale = 2.0
        dotLayer.contents = Self.sharedDotImage
        dotLayer.isHidden = true
        root.addSublayer(dotLayer)

        CATransaction.commit()
    }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        solidDimLayer.frame = bounds
        outerTrailLayer.frame = bounds
        innerTrailLayer.frame = bounds
        CATransaction.commit()
    }

    func resetAllLayers() {
        solidDimLayer.isHidden = true
        spotlightLayer.isHidden = true
        outerTrailLayer.isHidden = true
        outerTrailLayer.path = nil
        innerTrailLayer.isHidden = true
        innerTrailLayer.path = nil
        dotLayer.isHidden = true
    }

    /// Pre-renders the 48x48pt (96x96px @ 2x) red-white glowing laser dot ONCE at startup.
    static func makeLaserDotImage() -> CGImage? {
        let ptSize: CGFloat = 48.0
        let scale: CGFloat = 2.0
        let pxSize = Int(ptSize * scale)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil,
            width: pxSize,
            height: pxSize,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return nil
        }

        ctx.scaleBy(x: scale, y: scale)
        let center = CGPoint(x: ptSize * 0.5, y: ptSize * 0.5)

        // Soft radial outer halo
        let gradColors = [
            CGColor(red: 1.0, green: 0.10, blue: 0.14, alpha: 0.44),
            CGColor(red: 1.0, green: 0.08, blue: 0.12, alpha: 0.18),
            CGColor(red: 1.0, green: 0.06, blue: 0.10, alpha: 0.0),
        ] as CFArray
        let gradLocs: [CGFloat] = [0.0, 0.56, 1.0]
        if let grad = CGGradient(colorsSpace: colorSpace, colors: gradColors, locations: gradLocs) {
            ctx.drawRadialGradient(
                grad,
                startCenter: center,
                startRadius: 0,
                endCenter: center,
                endRadius: 22.0,
                options: []
            )
        }

        // Mid vivid red body
        let midR: CGFloat = 9.5
        ctx.setFillColor(CGColor(red: 1.0, green: 0.12, blue: 0.16, alpha: 0.94))
        ctx.fillEllipse(in: CGRect(x: center.x - midR, y: center.y - midR, width: midR * 2, height: midR * 2))

        // Hot white-pink core
        let coreR: CGFloat = 4.3
        ctx.setFillColor(CGColor(red: 1.0, green: 0.95, blue: 0.95, alpha: 1.0))
        ctx.fillEllipse(in: CGRect(x: center.x - coreR, y: center.y - coreR, width: coreR * 2, height: coreR * 2))

        return ctx.makeImage()
    }

    /// Pre-generates a 1.0x point-scale spotlight mask with a clear aperture at its center.
    /// Translating `spotlightLayer.position` on GPU costs 0 CPU rasterization per frame!
    static func makeSpotlightTexture(screenSize: CGSize, quantizedRadius: Int) -> (image: CGImage, size: CGSize)? {
        let r = CGFloat(quantizedRadius)
        let texW = max(1, Int(max(ceil(screenSize.width * 2.5), ceil((screenSize.width + r + 24.0) * 2.0))))
        let texH = max(1, Int(max(ceil(screenSize.height * 2.5), ceil((screenSize.height + r + 24.0) * 2.0))))
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil,
            width: texW,
            height: texH,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return nil
        }

        let fullRect = CGRect(x: 0, y: 0, width: CGFloat(texW), height: CGFloat(texH))
        let cx = CGFloat(texW) * 0.5
        let cy = CGFloat(texH) * 0.5

        // 58% dark mask across entire oversized canvas
        ctx.setFillColor(CGColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 0.58))
        ctx.fill(fullRect)

        // Punch smooth circular aperture at exact center
        ctx.saveGState()
        ctx.setBlendMode(.clear)
        ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2.0, height: r * 2.0))
        ctx.restoreGState()

        // Soft outer feather glow ring
        let outerGlow = CGRect(x: cx - r - 1.5, y: cy - r - 1.5, width: (r + 1.5) * 2.0, height: (r + 1.5) * 2.0)
        ctx.setStrokeColor(CGColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 0.22))
        ctx.setLineWidth(5.0)
        ctx.strokeEllipse(in: outerGlow)

        // Crisp 2pt white 0.72-alpha spotlight border ring
        let ringRect = CGRect(x: cx - r, y: cy - r, width: r * 2.0, height: r * 2.0)
        ctx.setStrokeColor(CGColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 0.72))
        ctx.setLineWidth(2.0)
        ctx.strokeEllipse(in: ringRect)

        guard let image = ctx.makeImage() else { return nil }
        return (image: image, size: CGSize(width: CGFloat(texW), height: CGFloat(texH)))
    }

    /// Builds a C1-continuous midpoint quadratic Bezier ribbon path through `points`.
    static func buildSmoothRibbonPath(_ points: [CGPoint]) -> CGPath? {
        guard points.count >= 2 else { return nil }
        let path = CGMutablePath()
        path.move(to: points[0])
        if points.count == 2 {
            path.addLine(to: points[1])
            return path
        }
        let firstMid = CGPoint(
            x: (points[0].x + points[1].x) * 0.5,
            y: (points[0].y + points[1].y) * 0.5
        )
        path.addLine(to: firstMid)
        for i in 1..<(points.count - 1) {
            let pCurr = points[i]
            let pNext = points[i + 1]
            let mid = CGPoint(x: (pCurr.x + pNext.x) * 0.5, y: (pCurr.y + pNext.y) * 0.5)
            path.addQuadCurve(to: mid, control: pCurr)
        }
        if let last = points.last {
            path.addLine(to: last)
        }
        return path
    }
}

final class LaserOverlayController {
    static let shared = LaserOverlayController()

    private final class ScreenWindowEntry {
        let panel: NSPanel
        let canvas: LaserCanvasView
        let cgBounds: CGRect
        let nsFrame: NSRect
        var spotlightCache: [Int: (image: CGImage, size: CGSize)] = [:]
        var cachedRadiusOrder: [Int] = []
        var appliedSpotlightRadius: Int = -1

        init(panel: NSPanel, canvas: LaserCanvasView, cgBounds: CGRect, nsFrame: NSRect) {
            self.panel = panel
            self.canvas = canvas
            self.cgBounds = cgBounds
            self.nsFrame = nsFrame
        }

        func spotlightTexture(for quantizedRadius: Int) -> (image: CGImage, size: CGSize)? {
            if let hit = spotlightCache[quantizedRadius] {
                return hit
            }
            guard let generated = LaserCanvasView.makeSpotlightTexture(
                screenSize: nsFrame.size,
                quantizedRadius: quantizedRadius
            ) else {
                return nil
            }
            if cachedRadiusOrder.count >= 4, let oldest = cachedRadiusOrder.first {
                cachedRadiusOrder.removeFirst()
                spotlightCache.removeValue(forKey: oldest)
            }
            spotlightCache[quantizedRadius] = generated
            cachedRadiusOrder.append(quantizedRadius)
            return generated
        }

        func trimCacheKeepingLatest() {
            while cachedRadiusOrder.count > 1 {
                let oldest = cachedRadiusOrder.removeFirst()
                spotlightCache.removeValue(forKey: oldest)
            }
        }
    }

    private var entries: [ScreenWindowEntry] = []
    private var mode: Int = 0
    private var spotlightRadius: CGFloat = 140
    private var globalPoint: CGPoint = .zero
    private var globalTrail: [LaserTrailSample] = []
    private var lastHeartbeatSec: TimeInterval = 0
    private var renderTimer: Timer? = nil
    private let watchdogTimeoutSec: TimeInterval = 1.8
    private let trailWindowSec: TimeInterval = 0.15
    private let trailMaxPoints: Int = 28

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
            if let last = self.globalTrail.last {
                let d = hypot(globalCursor.x - last.point.x, globalCursor.y - last.point.y)
                if d >= 0.25 {
                    self.globalTrail.append(LaserTrailSample(point: globalCursor, timestampSec: now))
                }
            } else {
                self.globalTrail.append(LaserTrailSample(point: globalCursor, timestampSec: now))
            }
            self.globalTrail = HidMath.pruneTrail(
                self.globalTrail,
                nowSec: now,
                maxAgeSec: trailWindowSec,
                maxPoints: trailMaxPoints
            )
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

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for e in entries {
            e.canvas.resetAllLayers()
            e.trimCacheKeepingLatest()
            e.panel.orderOut(nil)
        }
        CATransaction.commit()
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
        let timer = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let now = CACurrentMediaTime()
            if self.mode <= 0 || (now - self.lastHeartbeatSec) > self.watchdogTimeoutSec {
                self.hideImmediateOnMain()
                return
            }
            if self.mode == 1 && !self.globalTrail.isEmpty {
                let prevCount = self.globalTrail.count
                self.globalTrail = HidMath.pruneTrail(
                    self.globalTrail,
                    nowSec: now,
                    maxAgeSec: self.trailWindowSec,
                    maxPoints: self.trailMaxPoints
                )
                if self.globalTrail.count != prevCount {
                    self.renderFrameOnMain(nowSec: now)
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        renderTimer = timer
    }

    private func renderFrameOnMain(nowSec: TimeInterval) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }

        if mode == 2 {
            let clampedR = min(320, max(60, spotlightRadius))
            let qRadius = max(60, min(320, Int((clampedR / 4.0).rounded()) * 4))
            let activeMargin = clampedR + 16.0

            for e in entries {
                let canvas = e.canvas
                canvas.dotLayer.isHidden = true
                canvas.outerTrailLayer.isHidden = true
                canvas.outerTrailLayer.path = nil
                canvas.innerTrailLayer.isHidden = true
                canvas.innerTrailLayer.path = nil

                if e.cgBounds.insetBy(dx: -activeMargin, dy: -activeMargin).contains(globalPoint),
                   let tex = e.spotlightTexture(for: qRadius) {
                    canvas.solidDimLayer.isHidden = true
                    if e.appliedSpotlightRadius != qRadius {
                        canvas.spotlightLayer.bounds = CGRect(origin: .zero, size: tex.size)
                        canvas.spotlightLayer.contents = tex.image
                        e.appliedSpotlightRadius = qRadius
                    }
                    let localPt = CGPoint(
                        x: globalPoint.x - e.cgBounds.minX,
                        y: e.cgBounds.height - (globalPoint.y - e.cgBounds.minY)
                    )
                    canvas.spotlightLayer.position = localPt
                    canvas.spotlightLayer.isHidden = false
                } else {
                    canvas.spotlightLayer.isHidden = true
                    canvas.solidDimLayer.frame = canvas.bounds
                    canvas.solidDimLayer.isHidden = false
                }
            }
            return
        }

        // Mode 1: Red Laser Dot + Smooth Quadratic Bezier GPU Ribbon Trail
        for e in entries {
            let canvas = e.canvas
            canvas.solidDimLayer.isHidden = true
            canvas.spotlightLayer.isHidden = true

            if e.cgBounds.insetBy(dx: -28.0, dy: -28.0).contains(globalPoint) {
                let localPt = CGPoint(
                    x: globalPoint.x - e.cgBounds.minX,
                    y: e.cgBounds.height - (globalPoint.y - e.cgBounds.minY)
                )
                canvas.dotLayer.position = localPt
                canvas.dotLayer.isHidden = false
            } else {
                canvas.dotLayer.isHidden = true
            }

            if globalTrail.count >= 2 {
                let nearRect = e.cgBounds.insetBy(dx: -48.0, dy: -48.0)
                let hasVisibleSample = globalTrail.contains { nearRect.contains($0.point) }
                if hasVisibleSample {
                    var localPts: [CGPoint] = []
                    localPts.reserveCapacity(globalTrail.count + 1)
                    for sample in globalTrail {
                        let lp = CGPoint(
                            x: sample.point.x - e.cgBounds.minX,
                            y: e.cgBounds.height - (sample.point.y - e.cgBounds.minY)
                        )
                        localPts.append(lp)
                    }
                    // Anchor ribbon tip directly to current dot center so trail never lags behind dot
                    let tipPt = CGPoint(
                        x: globalPoint.x - e.cgBounds.minX,
                        y: e.cgBounds.height - (globalPoint.y - e.cgBounds.minY)
                    )
                    if let last = localPts.last, hypot(tipPt.x - last.x, tipPt.y - last.y) >= 0.2 {
                        localPts.append(tipPt)
                    }
                    if let path = LaserCanvasView.buildSmoothRibbonPath(localPts) {
                        canvas.outerTrailLayer.path = path
                        canvas.outerTrailLayer.isHidden = false
                        canvas.innerTrailLayer.path = path
                        canvas.innerTrailLayer.isHidden = false
                    } else {
                        canvas.outerTrailLayer.isHidden = true
                        canvas.outerTrailLayer.path = nil
                        canvas.innerTrailLayer.isHidden = true
                        canvas.innerTrailLayer.path = nil
                    }
                } else {
                    canvas.outerTrailLayer.isHidden = true
                    canvas.outerTrailLayer.path = nil
                    canvas.innerTrailLayer.isHidden = true
                    canvas.innerTrailLayer.path = nil
                }
            } else {
                canvas.outerTrailLayer.isHidden = true
                canvas.outerTrailLayer.path = nil
                canvas.innerTrailLayer.isHidden = true
                canvas.innerTrailLayer.path = nil
            }
        }
    }
}
