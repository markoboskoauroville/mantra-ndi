package com.mantraproductions.ndi

/**
 * SMPTE Linear Timecode reader.
 *
 * Reads PCM audio, from a microphone, a cable, or the audio channel of a
 * stream, and emits timecode whenever a complete frame arrives.
 *
 * The method is the classic one: find the zero crossings, measure the gaps
 * between them, and read the biphase mark code those gaps carry. One clock
 * period with nothing inside it is a zero; two half periods, so two crossings
 * close together, is a one. A one is not known until its second half arrives,
 * which is what the pending flag is for.
 *
 * Two details are worth the words, because both were wrong first time:
 *
 *  - **The clock is measured, not assumed.** The half period is taken as the
 *    smallest gap in a window of them rather than from whichever gap happened
 *    to arrive first. Bootstrapping from the first gap fails whenever that gap
 *    is a full period, which depends entirely on what the timecode happens to
 *    be, so it worked on some numbers and not others.
 *  - **A long gap resets the timing, not the accumulated bits.** Silence or a
 *    dropout means the clock estimate is stale; it does not mean the bits
 *    already gathered were wrong.
 */
class LtcDecoder(private val sampleRate: Int = 48_000) {

    /** Called for every timecode frame found, with the sample it finished on. */
    var onFrame: ((timecode: Timecode, samplePosition: Long) -> Unit)? = null

    private val bits = ArrayDeque<Boolean>()
    private val window = ArrayDeque<Int>()

    private var lastSample = 0
    private var sampleCount = 0L
    private var lastTransition = 0L
    private var halfPeriod = 0.0
    private var halfPending = false

    fun feed(pcm: ByteArray) {
        var i = 0
        while (i + 1 < pcm.size) {
            val sample =
                ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val crossed =
                (lastSample < 0 && sample >= 0) || (lastSample >= 0 && sample < 0)
            if (crossed) {
                val interval = (sampleCount - lastTransition).toInt()
                lastTransition = sampleCount
                if (interval > 0) accept(interval)
            }
            lastSample = sample
            sampleCount++
            i += 2
        }
    }

    /**
     * Holds the first gaps back until the clock is known, then plays them
     * through. Costs a fraction of a frame of latency once, and buys a decoder
     * that locks on whatever the first timecode happens to be.
     */
    private fun accept(interval: Int) {
        if (halfPeriod <= 0.0) {
            window.addLast(interval)
            if (window.size < BOOTSTRAP) return
            halfPeriod = (window.minOrNull() ?: interval).toDouble()
            val held = window.toList()
            window.clear()
            held.forEach { classify(it) }
            return
        }
        classify(interval)
    }

    private fun classify(interval: Int) {
        if (interval < halfPeriod) halfPeriod = interval.toDouble()

        when {
            interval < halfPeriod * 1.5 -> {
                if (halfPending) {
                    bits.addLast(true)
                    halfPending = false
                } else {
                    halfPending = true
                    return
                }
            }

            interval < halfPeriod * 3.0 -> {
                halfPending = false
                bits.addLast(false)
            }

            else -> {
                // A dropout. The clock estimate is stale, the bits are not.
                halfPending = false
                return
            }
        }

        tryDecode()
        while (bits.size > 240) bits.removeFirst()
    }

    private fun tryDecode() {
        if (bits.size < 80) return
        val list = bits.toList()

        for (start in 0..(list.size - 80)) {
            val slice = list.subList(start, start + 80)
            if (!matchesSync(slice.subList(64, 80))) continue

            decode(slice, measuredFps())?.let { tc ->
                onFrame?.invoke(tc, sampleCount)
                repeat(start + 80) { if (bits.isNotEmpty()) bits.removeFirst() }
            }
            return
        }
    }

    private fun matchesSync(tail: List<Boolean>): Boolean {
        for (i in SYNC.indices) if (tail[i] != SYNC[i]) return false
        return true
    }

    /**
     * The rate the signal is actually running at: eighty bits per frame, two
     * halves per bit. Snapped to the nearest standard rate, since a
     * measurement is never exactly 25.000.
     */
    private fun measuredFps(): Double {
        if (halfPeriod <= 0.0) return 25.0
        val raw = sampleRate / (halfPeriod * 2.0 * 80.0)
        return STANDARD_RATES.minByOrNull { Math.abs(it - raw) } ?: 25.0
    }

    private fun decode(frame: List<Boolean>, fps: Double): Timecode? {
        fun bcd(start: Int, length: Int): Int {
            var v = 0
            for (i in 0 until length) if (frame[start + i]) v += 1 shl i
            return v
        }

        val f = bcd(0, 4) + bcd(8, 2) * 10
        val s = bcd(16, 4) + bcd(24, 3) * 10
        val m = bcd(32, 4) + bcd(40, 3) * 10
        val h = bcd(48, 4) + bcd(56, 2) * 10
        val dropFrame = frame[10]

        if (f > 29 || s > 59 || m > 59 || h > 23) return null
        // All zeroes is what an empty buffer looks like as much as what
        // midnight looks like, and reporting the wrong one starts a shoot an
        // entire day out.
        if (f == 0 && s == 0 && m == 0 && h == 0) return null

        return Timecode(h, m, s, f, fps, dropFrame)
    }

    private companion object {
        /** Gaps held back before the clock is decided. */
        const val BOOTSTRAP = 16

        val STANDARD_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0)

        /**
         * 0011 1111 1111 1101. The one sixteen bit sequence biphase mark
         * cannot produce inside data, which is how a decoder finds where a
         * frame ends.
         */
        val SYNC = booleanArrayOf(
            false, false, true, true, true, true, true, true,
            true, true, true, true, true, true, false, true
        )
    }
}
