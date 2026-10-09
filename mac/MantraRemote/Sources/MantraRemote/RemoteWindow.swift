import AppKit
import Network

/// One window: the cameras found on the network (Bonjour, `_mantralink._tcp`, as the phone announces itself), an
/// address to type when discovery cannot reach (a USB tether, another subnet), the picture, and the keys a remote
/// needs without reaching into the picture: REC, BACK, CLEAN.
final class RemoteWindowController: NSWindowController, NSWindowDelegate {
    private let video = VideoView(frame: NSRect(x: 0, y: 0, width: 960, height: 540))
    private let cameras = NSPopUpButton()
    private let address = NSTextField()
    private let status = NSTextField(labelWithString: "Looking for cameras…")
    private let recButton = NSButton(title: "● REC", target: nil, action: nil)
    private let cleanButton = NSButton(checkboxWithTitle: "CLEAN", target: nil, action: nil)
    private var browser: NWBrowser?
    private var found: [NWBrowser.Result] = []
    private var client: LinkClient?
    private var connectedTo = ""
    private var lastState: [String: String] = [:]
    private var rateTimer: Timer?
    private var lastFrames = 0

    convenience init() {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 640),
                         styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        w.title = "Mantra Remote"
        w.minSize = NSSize(width: 520, height: 360)
        self.init(window: w)
        w.delegate = self
        build(in: w)
        browse()
        rateTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }

    private func build(in w: NSWindow) {
        let root = NSView()
        w.contentView = root

        cameras.target = self
        cameras.action = #selector(pickCamera)
        cameras.addItem(withTitle: "Cameras on the network")
        address.placeholderString = "or an address, e.g. 192.168.1.23"
        address.target = self
        address.action = #selector(connectAddress)
        let connect = NSButton(title: "Connect", target: self, action: #selector(connectAddress))
        recButton.target = self; recButton.action = #selector(pressRec)
        let back = NSButton(title: "BACK", target: self, action: #selector(pressBack))
        cleanButton.target = self; cleanButton.action = #selector(toggleClean)
        status.lineBreakMode = .byTruncatingTail
        status.textColor = .secondaryLabelColor

        let bar = NSStackView(views: [cameras, address, connect, NSView(), recButton, back, cleanButton])
        bar.orientation = .horizontal
        bar.spacing = 8
        bar.edgeInsets = NSEdgeInsets(top: 8, left: 10, bottom: 8, right: 10)
        address.widthAnchor.constraint(greaterThanOrEqualToConstant: 200).isActive = true

        video.onKeyName = { [weak self] k in self?.client?.key(k) }
        for v in [bar, video, status] as [NSView] { v.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(v) }
        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: root.topAnchor),
            bar.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            video.topAnchor.constraint(equalTo: bar.bottomAnchor),
            video.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            video.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            status.topAnchor.constraint(equalTo: video.bottomAnchor, constant: 4),
            status.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 10),
            status.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -10),
            status.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -6),
        ])
    }

    // --- finding the camera -------------------------------------------------------------------------------------

    private func browse() {
        let b = NWBrowser(for: .bonjour(type: Link.service, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            DispatchQueue.main.async { self?.listCameras(Array(results)) }
        }
        b.stateUpdateHandler = { [weak self] state in
            if case .failed(let e) = state {
                DispatchQueue.main.async { self?.status.stringValue = "Bonjour: \(e.localizedDescription). Type the phone's address." }
            }
        }
        b.start(queue: .main)
        browser = b
    }

    private func name(of r: NWBrowser.Result) -> String {
        if case .service(let name, _, _, _) = r.endpoint { return name }
        return "\(r.endpoint)"
    }

    private func listCameras(_ results: [NWBrowser.Result]) {
        found = results
        cameras.removeAllItems()
        cameras.addItem(withTitle: results.isEmpty ? "No camera found yet" : "Cameras on the network")
        for r in results { cameras.addItem(withTitle: name(of: r)) }
        if client == nil, results.count == 1 { connect(to: results[0].endpoint, label: name(of: results[0])) }
        else if client == nil { status.stringValue = results.isEmpty ? "Looking for cameras… (Mantra Manual Camera open on the phone, same network)" : "Pick a camera" }
    }

    @objc private func pickCamera() {
        let i = cameras.indexOfSelectedItem - 1
        guard i >= 0, i < found.count else { return }
        connect(to: found[i].endpoint, label: name(of: found[i]))
    }

    @objc private func connectAddress() {
        let text = address.stringValue.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        let parts = text.split(separator: ":")
        let host = String(parts[0])
        let port = parts.count > 1 ? UInt16(parts[1]) ?? Link.port : Link.port
        guard let p = NWEndpoint.Port(rawValue: port) else { return }
        connect(to: .hostPort(host: NWEndpoint.Host(host), port: p), label: text)
    }

    private func connect(to endpoint: NWEndpoint, label: String) {
        client?.stop()
        let c = LinkClient(endpoint: endpoint)
        c.onStatus = { [weak self] s in self?.show(s) }
        c.onConfig = { [weak self] cfg in DispatchQueue.main.async { self?.video.configure(cfg) } }
        c.onFrame = { [weak self] f in DispatchQueue.main.async { self?.video.show(f) } }
        c.onState = { [weak self] st in DispatchQueue.main.async { self?.apply(st) } }
        video.client = c
        client = c
        connectedTo = label
        lastFrames = 0
        window?.title = "Mantra Remote — \(label)"
        c.start()
    }

    // --- what the camera says -----------------------------------------------------------------------------------

    private func show(_ s: LinkClient.Status) {
        switch s {
        case .connecting: status.stringValue = "Connecting to \(connectedTo)…"
        case .connected: status.stringValue = "Connected to \(connectedTo)"
        case .waiting(let why): status.stringValue = why
        }
    }

    private func apply(_ st: [String: String]) {
        lastState = st
        let rec = st["rec"] == "1"
        recButton.title = rec ? "■ STOP  \(timecode(st["since"]))" : "● REC"
        recButton.contentTintColor = rec ? .systemRed : nil
        cleanButton.state = st["clean"] == "1" ? .on : .off
        if let name = st["name"], !name.isEmpty { window?.title = "Mantra Remote — \(name)" }
    }

    private func timecode(_ ms: String?) -> String {
        let s = (Int(ms ?? "0") ?? 0) / 1000
        return String(format: "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private func tick() {
        guard let c = client else { return }
        let fps = c.frames - lastFrames
        lastFrames = c.frames
        let msg = lastState["msg"].map { "  ·  \($0)" } ?? ""
        if fps > 0 {
            status.stringValue = "\(connectedTo)  ·  \(fps) fps  ·  \(Int(video.pictureSize.width))x\(Int(video.pictureSize.height))\(msg)"
        }
    }

    // --- the keys -----------------------------------------------------------------------------------------------

    @objc private func pressRec() { client?.key("REC") }
    @objc private func pressBack() { client?.key("BACK") }
    @objc private func toggleClean() { client?.mode(clean: cleanButton.state == .on) }

    func windowWillClose(_ notification: Notification) {
        client?.stop()
        browser?.cancel()
        NSApp.terminate(nil)
    }
}
