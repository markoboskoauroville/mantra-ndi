import CoreMedia
import Foundation

/// The camera's H.264, Annex-B from Android's MediaCodec, turned into what AVSampleBufferDisplayLayer takes.
enum H264 {
    /// The NAL units in an Annex-B stream (start codes 00 00 01 or 00 00 00 01), without their start codes.
    static func nalUnits<C: Collection>(_ bytes: C) -> [[UInt8]] where C.Element == UInt8 {
        let b = Array(bytes)
        var units = [[UInt8]]()
        var i = 0
        var start = -1
        while i + 2 < b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                if start >= 0 {
                    var end = i
                    if end > start, b[end - 1] == 0 { end -= 1 }   // the 4-byte form's leading zero
                    if end > start { units.append(Array(b[start..<end])) }
                }
                i += 3
                start = i
                continue
            }
            i += 1
        }
        if start >= 0, start < b.count { units.append(Array(b[start...])) }
        if start < 0, !b.isEmpty { units.append(b) }   // no start code at all: one bare unit
        return units
    }

    static func type(_ nal: [UInt8]) -> UInt8 { nal.first.map { $0 & 0x1F } ?? 0 }

    /// The format from CONFIG's csd-0 (SPS) and csd-1 (PPS).
    static func format(csd0: [UInt8], csd1: [UInt8]) -> CMVideoFormatDescription? {
        let units = nalUnits(csd0 + csd1)
        guard let sps = units.first(where: { type($0) == 7 }), let pps = units.first(where: { type($0) == 8 }) else { return nil }
        var fmt: CMVideoFormatDescription?
        let status = sps.withUnsafeBufferPointer { s in
            pps.withUnsafeBufferPointer { p -> OSStatus in
                let pointers: [UnsafePointer<UInt8>] = [s.baseAddress!, p.baseAddress!]
                let sizes: [Int] = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4, formatDescriptionOut: &fmt)
            }
        }
        return status == noErr ? fmt : nil
    }

    /// One access unit as AVCC: each picture NAL with a 4-byte big-endian length (parameter sets and delimiters out).
    static func avcc<C: Collection>(_ accessUnit: C) -> [UInt8] where C.Element == UInt8 {
        var out = [UInt8]()
        for nal in nalUnits(accessUnit) {
            let t = type(nal)
            if t == 7 || t == 8 || t == 9 { continue }
            out.append(contentsOf: Link.bigEndian(UInt32(nal.count)))
            out.append(contentsOf: nal)
        }
        return out
    }

    /// A sample buffer to show at once.
    static func sample(avcc: [UInt8], format: CMVideoFormatDescription) -> CMSampleBuffer? {
        guard !avcc.isEmpty else { return nil }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: avcc.count, flags: 0, blockBufferOut: &block) == kCMBlockBufferNoErr, let block else { return nil }
        let copied = avcc.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == kCMBlockBufferNoErr else { return nil }
        var sample: CMSampleBuffer?
        var sizes = [avcc.count]
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
            sampleSizeEntryCount: 1, sampleSizeArray: &sizes, sampleBufferOut: &sample) == noErr, let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict,
                                 Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}
