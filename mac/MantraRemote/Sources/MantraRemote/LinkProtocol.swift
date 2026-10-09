import Foundation

/// MANTRA LINK, the same wire as the Android monitor (shared/kotlin/com/mantraproductions/ndi/Link.kt):
/// every message is type (1 byte), length (4 bytes, big-endian), payload.
///   camera → remote   CONFIG  w, h (Int32), csd-0 and csd-1 (Int32 length + bytes each)
///                     FRAME   flags (1 = keyframe), pts µs (Int64), one H.264 access unit (Annex-B)
///                     STATE   UTF-8 "key=value;…"  (rec, since, clean, name, msg, msgAt)
///   remote → camera   TOUCH   actionMasked | pointerIndex << 4, then per pointer: id (byte), x, y (Float32, 0…1)
///                     KEY     UTF-8 key name ("REC", a rail key's label, "BACK")
///                     MODE    1 byte: 0 the camera's window, 1 the clean picture
enum Link {
    static let port: UInt16 = 48100
    static let service = "_mantralink._tcp"

    static let config: UInt8 = 1
    static let frame: UInt8 = 2
    static let state: UInt8 = 3
    static let touch: UInt8 = 10
    static let key: UInt8 = 11
    static let mode: UInt8 = 12

    /// Android MotionEvent actions, as the camera plays them back.
    enum Action: UInt8 { case down = 0, up = 1, move = 2, cancel = 3, pointerDown = 5, pointerUp = 6 }

    struct Message: Equatable {
        let type: UInt8
        let payload: [UInt8]
    }

    static func encode(_ type: UInt8, _ payload: [UInt8]) -> Data {
        var out = [UInt8]()
        out.reserveCapacity(5 + payload.count)
        out.append(type)
        out.append(contentsOf: bigEndian(UInt32(payload.count)))
        out.append(contentsOf: payload)
        return Data(out)
    }

    struct Pointer: Equatable {
        let id: UInt8
        let x: Float
        let y: Float
    }

    /// A touch: [action] for the pointer at [index] in [pointers], every pointer as a fraction of the picture.
    static func touch(_ action: Action, index: Int = 0, pointers: [Pointer]) -> [UInt8] {
        var out: [UInt8] = [action.rawValue | UInt8((index & 0x0F) << 4)]
        for p in pointers {
            out.append(p.id)
            out.append(contentsOf: bigEndian(p.x.bitPattern))
            out.append(contentsOf: bigEndian(p.y.bitPattern))
        }
        return out
    }

    struct Config: Equatable {
        let width: Int
        let height: Int
        let csd0: [UInt8]
        let csd1: [UInt8]
    }

    static func parseConfig(_ b: [UInt8]) -> Config? {
        var r = Reader(b)
        guard let w = r.int32(), let h = r.int32(),
              let n0 = r.int32(), let c0 = r.bytes(Int(n0)),
              let n1 = r.int32(), let c1 = r.bytes(Int(n1)) else { return nil }
        return Config(width: Int(w), height: Int(h), csd0: c0, csd1: c1)
    }

    struct Frame {
        let keyframe: Bool
        let ptsUs: Int64
        let accessUnit: ArraySlice<UInt8>
    }

    static func parseFrame(_ b: [UInt8]) -> Frame? {
        guard b.count >= 9 else { return nil }
        var pts: Int64 = 0
        for i in 1...8 { pts = (pts << 8) | Int64(b[i]) }
        return Frame(keyframe: b[0] & 1 == 1, ptsUs: pts, accessUnit: b[9...])
    }

    static func parseState(_ b: [UInt8]) -> [String: String] {
        let text = String(decoding: b, as: UTF8.self)
        var out = [String: String]()
        for part in text.split(separator: ";") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            out[String(part[part.startIndex..<eq])] = String(part[part.index(after: eq)...])
        }
        return out
    }

    static func bigEndian(_ v: UInt32) -> [UInt8] {
        [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)]
    }

    struct Reader {
        let b: [UInt8]
        var i = 0
        init(_ b: [UInt8]) { self.b = b }
        mutating func int32() -> Int32? {
            guard i + 4 <= b.count else { return nil }
            let v = UInt32(b[i]) << 24 | UInt32(b[i + 1]) << 16 | UInt32(b[i + 2]) << 8 | UInt32(b[i + 3])
            i += 4
            return Int32(bitPattern: v)
        }
        mutating func bytes(_ n: Int) -> [UInt8]? {
            guard n >= 0, i + n <= b.count else { return nil }
            defer { i += n }
            return Array(b[i..<i + n])
        }
    }
}

/// Bytes off the socket in, whole messages out. A length past 16 MB is a broken stream (as on Android).
final class LinkParser {
    private var buffer = [UInt8]()
    private(set) var broken = false

    func push(_ data: Data) -> [Link.Message] {
        buffer.append(contentsOf: data)
        var out = [Link.Message]()
        var start = 0
        while buffer.count - start >= 5 {
            let n = Int(UInt32(buffer[start + 1]) << 24 | UInt32(buffer[start + 2]) << 16 |
                        UInt32(buffer[start + 3]) << 8 | UInt32(buffer[start + 4]))
            if n > 16 << 20 { broken = true; buffer.removeAll(); return out }
            guard buffer.count - start >= 5 + n else { break }
            out.append(Link.Message(type: buffer[start], payload: Array(buffer[(start + 5)..<(start + 5 + n)])))
            start += 5 + n
        }
        if start > 0 { buffer.removeFirst(start) }
        return out
    }

    func reset() { buffer.removeAll(); broken = false }
}
