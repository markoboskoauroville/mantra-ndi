import AppKit
import AVFoundation

/// The camera's picture, and the mouse and trackpad turned into the camera's touches.
///   click / drag            one finger (DOWN, MOVE, UP): taps the camera's keys, drags its faders, places its marks
///   pinch on the trackpad   two fingers spreading or closing: resizes the armed mark, as a pinch on the phone
final class VideoView: NSView {
    let videoLayer = AVSampleBufferDisplayLayer()
    var client: LinkClient?
    private var format: CMVideoFormatDescription?
    private(set) var pictureSize = CGSize(width: 16, height: 9)
    private var dragging = false
    private var pinchSpan: CGFloat = 0
    private var pinchCentre = CGPoint.zero

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer = CALayer()
        layer?.backgroundColor = NSColor.black.cgColor
        videoLayer.videoGravity = .resizeAspect
        videoLayer.frame = bounds
        videoLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer?.addSublayer(videoLayer)
    }

    required init?(coder: NSCoder) { fatalError("not from a nib") }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    // --- the picture -------------------------------------------------------------------------------------------

    func configure(_ c: Link.Config) {
        format = H264.format(csd0: c.csd0, csd1: c.csd1)
        pictureSize = CGSize(width: max(1, c.width), height: max(1, c.height))
        videoLayer.flush()
    }

    func show(_ f: Link.Frame) {
        guard let format else { return }
        if videoLayer.status == .failed { videoLayer.flush() }
        if let s = H264.sample(avcc: H264.avcc(f.accessUnit), format: format) { videoLayer.enqueue(s) }
    }

    /// Where the picture sits in the view (aspect fit, as the layer draws it).
    private var pictureRect: CGRect { AVMakeRect(aspectRatio: pictureSize, insideRect: bounds) }

    /// A point in the view as a fraction of the picture, top left 0,0 (the camera's window coordinates).
    private func fraction(_ p: CGPoint) -> (Float, Float) {
        let r = pictureRect
        let x = (p.x - r.minX) / max(1, r.width)
        let y = 1 - (p.y - r.minY) / max(1, r.height)          // AppKit's y runs up, the camera's down
        return (Float(min(max(x, 0), 1)), Float(min(max(y, 0), 1)))
    }

    private func pointer(_ id: UInt8, _ p: CGPoint) -> Link.Pointer {
        let (x, y) = fraction(p)
        return Link.Pointer(id: id, x: x, y: y)
    }

    // --- one finger ---------------------------------------------------------------------------------------------

    override func mouseDown(with e: NSEvent) {
        window?.makeFirstResponder(self)
        let p = convert(e.locationInWindow, from: nil)
        guard pictureRect.contains(p) else { return }
        dragging = true
        client?.touch(.down, pointers: [pointer(0, p)])
    }

    override func mouseDragged(with e: NSEvent) {
        guard dragging else { return }
        client?.touch(.move, pointers: [pointer(0, convert(e.locationInWindow, from: nil))])
    }

    override func mouseUp(with e: NSEvent) {
        guard dragging else { return }
        dragging = false
        client?.touch(.up, pointers: [pointer(0, convert(e.locationInWindow, from: nil))])
    }

    // --- two fingers, from the trackpad's pinch -----------------------------------------------------------------

    private func pinchPointers() -> [Link.Pointer] {
        let half = pinchSpan / 2
        return [pointer(0, CGPoint(x: pinchCentre.x - half, y: pinchCentre.y)),
                pointer(1, CGPoint(x: pinchCentre.x + half, y: pinchCentre.y))]
    }

    override func magnify(with e: NSEvent) {
        switch e.phase {
        case .began:
            pinchCentre = convert(e.locationInWindow, from: nil)
            pinchSpan = min(pictureRect.width, pictureRect.height) * 0.2
            let pts = pinchPointers()
            client?.touch(.down, pointers: [pts[0]])
            client?.touch(.pointerDown, index: 1, pointers: pts)
        case .changed:
            pinchSpan = max(8, pinchSpan * (1 + e.magnification))
            client?.touch(.move, pointers: pinchPointers())
        case .ended, .cancelled:
            let pts = pinchPointers()
            client?.touch(.pointerUp, index: 1, pointers: pts)
            client?.touch(.up, pointers: [pts[0]])
        default: break
        }
    }

    // --- the keyboard -------------------------------------------------------------------------------------------

    var onKeyName: ((String) -> Void)?

    override func keyDown(with e: NSEvent) {
        switch e.keyCode {
        case 49: onKeyName?("REC")     // space
        case 53: onKeyName?("BACK")    // escape
        default: super.keyDown(with: e)
        }
    }
}
