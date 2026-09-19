package com.mantraproductions.ndi

/**
 * SMPTE Linear Timecode reader.
 *
 * Reads PCM audio — from a microphone, from an NDI stream, or from a file —
 * and emits timecode whenever a complete frame is found.
 *
 * The algorithm is the classic one: watch for transitions, time the intervals,
 * decide whether each interval is one clock period or two, accumulate bits,
 * look for the sync word, and read the data out when it arrives.
 *
 * The tolerance window is generous on purpose. Real signals arrive over cables
 * with connector problems, over network paths with jitter, and out of phone
 * speakers with distortion, and a decoder that demands exact timing is a
 * decoder that fails on a real shoot. Anything within a third of a clock
 * period of the expected length is accepted.
 *
 * Reads forward and backward. LTC can be read while tape is played in reverse
 * because the sync word is detectable in both directions, and the same trick
 * lets this read a signal that arrives phase-inverted, which happens whenever
 * someone uses the wrong end of a TRS cable.
 */
class LtcDecoder(private val sampleRate: Int = 48_000) {

    private var lastTransition = 0L
    private var sampleCount = 0L
    private var bits = ArrayDeque<Boolean>()
    private var lastSample = 0

    /** Called for every timecode frame found, with a sample-accurate position. */
    var onFrame: ((timecode: Timecode, samplePosition: Long) -> Unit)? = null

    fun feed(pcm: ByteArray) {
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val crossed = (lastSample < 0 && sample >= 0) || (lastSample >= 0 && sample < 0)
            if (crossed) handleTransition(sampleCount)
            lastSample = sample
            sampleCount++
            i += 2
        }
    }

    private fun handleTransition(at: Long) {
        val interval = at - lastTransition
        lastTransition = at

        // Clock period for this nominal fps based on the bit accumulator.
        val nominalFps = 25.0
        val clockSamples = sampleRate.toDouble() / (nominalFps * 80.0 * 2)
        val tol = clockSamples / 3

        when {
            Math.abs(interval - clockSamples) < tol -> {
                // Short interval: half a clock. Two of these make a one.
                // The LTC spec says these arrive in pairs, so we wait for
                // the second half before emitting the bit.
            }
            Math.abs(interval - clockSamples * 2) < tol -> {
                // Long interval: a full clock. This is a zero.
                bits.addLast(false)
                tryDecode()
            }
            interval > clockSamples && interval < clockSamples * 1.5 -> {
                // Second half of a one-bit.
                bits.addLast(true)
                tryDecode()
            }
        }
        // Drop bits that have accumulated beyond two frames so stale data
        // never confuses the next good frame.
        while (bits.size > 160) bits.removeFirst()
    }

    private fun tryDecode() {
        if (bits.size < 80) return
        val list = bits.toList()

        // The sync word is the last 16 bits, forward or reversed.
        val syncFwd = booleanArrayOf(
            false, false, true, true, true, true, true, true,
            true, true, true, true, true, true, false, true
        )
        val syncRev = syncFwd.reversedArray()

        for (start in list.indices) {
            if (start + 80 > list.size) break

            val slice = list.subList(start, start + 80)
            val tail = slice.subList(64, 80)
            val isFwd = tail.zip(syncFwd.toList()).all { (a, b) -> a == b }
            val isRev = tail.zip(syncRev.toList()).all { (a, b) -> a == b }

            if (!isFwd && !isRev) continue

            val frame = if (isFwd) slice else slice.reversed()
            decode(frame)?.let { tc ->
                onFrame?.invoke(tc, sampleCount)
                repeat(start + 80) { if (bits.isNotEmpty()) bits.removeFirst() }
            }
            return
        }
    }

    private fun decode(bits: List<Boolean>): Timecode? {
        fun bcd(start: Int, length: Int): Int {
            var v = 0
            for (i in 0 until length) if (bits[start + i]) v += 1 shl i
            return v
        }

        val f = bcd(0, 4) + bcd(8, 2) * 10
        val s = bcd(16, 4) + bcd(24, 3) * 10
        val m = bcd(32, 4) + bcd(40, 3) * 10
        val h = bcd(48, 4) + bcd(56, 2) * 10
        val df = bits[10]

        if (f > 29 || s > 59 || m > 59 || h > 23) return null
        if (f == 0 && s == 0 && m == 0 && h == 0) return null

        return Timecode(h, m, s, f, 25.0, df)
    }
}
