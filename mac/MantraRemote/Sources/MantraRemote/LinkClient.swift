import Foundation
import Network

/// The remote's end of Mantra Link, like the Android monitor's LinkClient: connect, read messages, send touches and
/// keys. ZERO FRAMES: nothing for 2.5 s while connected drops the connection and opens it again (the camera answers a
/// new connection with a keyframe); a refused connection is tried again every second.
final class LinkClient {
    enum Status { case connecting, connected, waiting(String) }

    var onStatus: ((Status) -> Void)?
    var onConfig: ((Link.Config) -> Void)?
    var onFrame: ((Link.Frame) -> Void)?
    var onState: (([String: String]) -> Void)?

    private let endpoint: NWEndpoint
    private let queue = DispatchQueue(label: "mantra.link")
    private var connection: NWConnection?
    private let parser = LinkParser()
    private var running = false
    private var lastDataAt = Date()
    private var watchdog: DispatchSourceTimer?

    private(set) var frames = 0
    private(set) var bytes = 0

    init(endpoint: NWEndpoint) { self.endpoint = endpoint }

    convenience init?(host: String, port: UInt16 = Link.port) {
        guard let p = NWEndpoint.Port(rawValue: port) else { return nil }
        self.init(endpoint: .hostPort(host: NWEndpoint.Host(host), port: p))
    }

    func start() {
        queue.async {
            self.running = true
            self.open()
            let t = DispatchSource.makeTimerSource(queue: self.queue)
            t.schedule(deadline: .now() + 0.5, repeating: 0.5)
            t.setEventHandler { [weak self] in self?.watch() }
            t.resume()
            self.watchdog = t
        }
    }

    func stop() {
        queue.async {
            self.running = false
            self.watchdog?.cancel()
            self.watchdog = nil
            self.connection?.cancel()
            self.connection = nil
        }
    }

    private func open() {
        guard running else { return }
        parser.reset()
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 3
        let c = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
        connection = c
        report(.connecting)
        c.stateUpdateHandler = { [weak self, weak c] state in
            guard let self, let c, c === self.connection else { return }
            switch state {
            case .ready:
                self.lastDataAt = Date()
                self.report(.connected)
                self.receive(on: c)
            case .failed(let e), .waiting(let e):
                self.reconnect("cannot reach the camera (\(e.localizedDescription)), trying again")
            default: break
            }
        }
        c.start(queue: queue)
    }

    private func reconnect(_ why: String) {
        connection?.cancel()
        connection = nil
        report(.waiting(why))
        guard running else { return }
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.running, self.connection == nil else { return }
            self.open()
        }
    }

    private func watch() {
        guard running, let c = connection, c.state == .ready else { return }
        if Date().timeIntervalSince(lastDataAt) > 2.5 { reconnect("no picture for 2.5 s: connecting again") }
    }

    private func receive(on c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, complete, error in
            guard let self, c === self.connection else { return }
            if let data, !data.isEmpty {
                self.lastDataAt = Date()
                self.bytes += data.count
                for m in self.parser.push(data) { self.handle(m) }
                if self.parser.broken { self.reconnect("broken stream, connecting again"); return }
            }
            if complete || error != nil { self.reconnect("the camera closed the link, connecting again"); return }
            self.receive(on: c)
        }
    }

    private func handle(_ m: Link.Message) {
        switch m.type {
        case Link.config: if let cfg = Link.parseConfig(m.payload) { onConfig?(cfg) }
        case Link.frame: if let f = Link.parseFrame(m.payload) { frames += 1; onFrame?(f) }
        case Link.state: onState?(Link.parseState(m.payload))
        default: break
        }
    }

    private func report(_ s: Status) {
        DispatchQueue.main.async { self.onStatus?(s) }
    }

    // --- what goes back, in order ----------------------------------------------------------------------------------

    private func send(_ type: UInt8, _ payload: [UInt8]) {
        queue.async {
            guard let c = self.connection, c.state == .ready else { return }
            c.send(content: Link.encode(type, payload), completion: .contentProcessed { _ in })
        }
    }

    func touch(_ action: Link.Action, index: Int = 0, pointers: [Link.Pointer]) {
        send(Link.touch, Link.touch(action, index: index, pointers: pointers))
    }

    func key(_ name: String) { send(Link.key, Array(name.utf8)) }

    func mode(clean: Bool) { send(Link.mode, [clean ? 1 : 0]) }
}
