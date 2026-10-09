import XCTest
@testable import MantraRemote

/// The wire, byte for byte as the Android side writes and reads it (Link.kt, LinkServer.kt, LinkClient.kt).
final class LinkTests: XCTestCase {
    func testAMessageIsTypeLengthPayload() {
        XCTAssertEqual(Array(Link.encode(Link.key, Array("REC".utf8))), [11, 0, 0, 0, 3, 82, 69, 67])
    }

    func testMessagesArriveWholeHoweverTheBytesAreCut() {
        let p = LinkParser()
        let wire = Link.encode(Link.state, Array("rec=1;name=Pixel".utf8)) + Link.encode(Link.key, [65])
        var got = [Link.Message]()
        for b in wire { got += p.push(Data([b])) }
        XCTAssertEqual(got.count, 2)
        XCTAssertEqual(Link.parseState(got[0].payload), ["rec": "1", "name": "Pixel"])
        XCTAssertEqual(got[1], Link.Message(type: Link.key, payload: [65]))
    }

    func testAnAbsurdLengthIsABrokenStream() {
        let p = LinkParser()
        _ = p.push(Data([2, 0x7F, 0xFF, 0xFF, 0xFF]))
        XCTAssertTrue(p.broken)
    }

    func testATouchIsActionIndexThenIdXYBigEndian() {
        // The camera reads: byte (actionMasked | index << 4), then per pointer id (byte), x, y (big-endian floats)
        let b = Link.touch(.pointerDown, index: 1, pointers: [Link.Pointer(id: 0, x: 0.5, y: 1), Link.Pointer(id: 1, x: 0, y: 0.25)])
        XCTAssertEqual(b.count, 1 + 2 * 9)
        XCTAssertEqual(b[0], 5 | 1 << 4)
        XCTAssertEqual(Array(b[1...9]), [0, 0x3F, 0x00, 0x00, 0x00, 0x3F, 0x80, 0x00, 0x00])
        XCTAssertEqual(b[10], 1)
    }

    func testConfigAndFrameParse() {
        var cfg = [UInt8]()
        for v: UInt32 in [1280, 720, 2] { cfg += Link.bigEndian(v) }
        cfg += [0xAA, 0xBB]
        cfg += Link.bigEndian(1) + [0xCC]
        let c = Link.parseConfig(cfg)
        XCTAssertEqual(c, Link.Config(width: 1280, height: 720, csd0: [0xAA, 0xBB], csd1: [0xCC]))

        let f = Link.parseFrame([1, 0, 0, 0, 0, 0, 0, 0x01, 0x00, 9, 9])
        XCTAssertEqual(f?.keyframe, true)
        XCTAssertEqual(f?.ptsUs, 256)
        XCTAssertEqual(Array(f!.accessUnit), [9, 9])
    }

    func testAnnexBBecomesAvccWithoutParameterSets() {
        let au: [UInt8] = [0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 7, 7, 7]
        XCTAssertEqual(H264.nalUnits(au).map { H264.type($0) }, [7, 8, 5])
        XCTAssertEqual(H264.avcc(au), [0, 0, 0, 4, 0x65, 7, 7, 7])
    }
}
