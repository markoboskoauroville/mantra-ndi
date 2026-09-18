package com.mantraproductions.ndi

/**
 * Reading a Tentacle's broadcast.
 *
 * A Tentacle transmits timecode continuously in its Bluetooth advertisement.
 * Nothing pairs and nothing connects: the device is shouting the time into the
 * room and any number of apps can listen, which is why several cameras can sit
 * on one generator.
 *
 * **The byte layout is not published.** Tentacle ship an SDK rather than a
 * specification, so rather than guess at offsets and produce a clock that is
 * confidently wrong, this tries the layouts that are known to occur and
 * reports which one matched, and it keeps the raw bytes so an advertisement
 * this does not understand can be looked at rather than shrugged at. A
 * timecode that is silently wrong is worse than no timecode, because it is
 * only discovered in the edit, with the whole day already out of sync.
 */
object TentacleParser {

    data class Reading(
        val timecode: Timecode,
        /** Which interpretation produced it, for the diagnostic screen. */
        val layout: String,
        val raw: String
    )

    /** Frame rate indices as the devices enumerate them. */
    private val RATE_BY_INDEX = listOf(
        23.976 to false,
        24.0 to false,
        25.0 to false,
        29.97 to false,
        29.97 to true,   // drop frame
        30.0 to false
    )

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * @param data the manufacturer specific bytes from one advertisement
     * @return a reading, or null when nothing in the payload looks like a
     *   valid time of day. Returning null is the honest answer; inventing a
     *   timecode from a payload we cannot read is not.
     */
    fun parse(data: ByteArray): Reading? {
        val raw = hex(data)

        // Layout A: a rate byte, then hours, minutes, seconds, frames, each as
        // a plain byte. The most direct encoding and the one to try first.
        for (offset in 0..maxOf(0, data.size - 5)) {
            val rateByte = data[offset].toInt() and 0xFF
            val rate = RATE_BY_INDEX.getOrNull(rateByte and 0x0F) ?: continue
            val tc = readPlain(data, offset + 1, rate.first, rate.second) ?: continue
            return Reading(tc, "rate+hhmmssff at $offset", raw)
        }

        // Layout B: four bytes of frames since midnight, little endian, with
        // the rate alongside. Compact, and used where the device counts rather
        // than formats.
        for (offset in 0..maxOf(0, data.size - 5)) {
            val rateByte = data[offset].toInt() and 0xFF
            val rate = RATE_BY_INDEX.getOrNull(rateByte and 0x0F) ?: continue
            val count = readLittleEndian(data, offset + 1) ?: continue
            val perDay = Timecode.nominal(rate.first) * 86400L
            // Zero is refused for the same reason as above: an empty buffer
            // and midnight are the same bytes, and one of them is a mistake.
            if (count !in 1 until perDay) continue
            return Reading(
                Timecode.fromFrameCount(count, rate.first, rate.second),
                "rate+frames32 at $offset",
                raw
            )
        }

        // There was a third layout here that read four bytes as a time with
        // no rate byte to check them against. It had to go: with nothing to
        // validate, it scans a payload until some four bytes happen to look
        // like a clock, and almost every payload contains four such bytes. It
        // turned a parser that refuses what it cannot read into one that
        // always answers, which is the exact failure this file exists to
        // avoid. A rate byte is what makes a reading trustworthy.
        return null
    }

    private fun readPlain(
        data: ByteArray,
        offset: Int,
        fps: Double,
        dropFrame: Boolean
    ): Timecode? {
        if (offset + 3 >= data.size) return null
        val h = data[offset].toInt() and 0xFF
        val m = data[offset + 1].toInt() and 0xFF
        val s = data[offset + 2].toInt() and 0xFF
        val f = data[offset + 3].toInt() and 0xFF
        if (h > 23 || m > 59 || s > 59) return null
        if (f >= Timecode.nominal(fps)) return null
        // All zeroes is what an empty buffer looks like as much as midnight,
        // so it is refused rather than reported as a valid time.
        if (h == 0 && m == 0 && s == 0 && f == 0) return null
        return Timecode(h, m, s, f, fps, dropFrame)
    }

    private fun readLittleEndian(data: ByteArray, offset: Int): Long? {
        if (offset + 3 >= data.size) return null
        return ((data[offset].toLong() and 0xFF)) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** A device worth listening to, by the names these units advertise under. */
    fun looksLikeTentacle(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return "tentacle" in n || "sync e" in n || "track e" in n || "timebar" in n
    }
}
