package com.mantraproductions.ndi

/**
 * SMPTE Linear Timecode as a PCM audio signal.
 *
 * LTC is the signal a Tentacle Sync outputs from its 3.5mm jack, the same
 * signal a clapperboard slate records on a separate track, and the thing
 * every edit tool on earth knows how to read. Generating it in software costs
 * nothing and makes this phone a sync source for any device on the network,
 * without any proprietary SDK, without Bluetooth, and without a cable.
 *
 * The signal is 80 bits per frame, encoded in biphase mark: a zero is
 * a single clock period with no transition at its midpoint, a one is two
 * half-periods with a transition between them. The last 16 bits are a sync
 * word, 0011 1111 1111 1101, which a decoder uses to find where frames begin.
 *
 * The PCM output is suitable for sending as the audio track of an NDI
 * stream, at which point every phone receiving the stream is also receiving
 * the timecode, and the decoder below reads it back out.
 */
object LtcEncoder {

    /** Amplitude, chosen to be loud enough to decode at 48 kHz over a network. */
    private const val AMP = 16000

    /**
     * The output level, carried from one frame to the next.
     *
     * LTC is one unbroken square wave, not a series of separate frames that
     * happen to follow each other. Restarting the level at every frame leaves
     * a double length gap at each join, and a decoder reads that as the signal
     * having stopped, which throws away everything it had gathered. So the
     * phase persists, and a generator writing frame after frame produces one
     * continuous signal exactly as a Tentacle does.
     */
    @Volatile private var phase = false

    /** Call before starting a fresh run, so a new take begins predictably. */
    fun reset() {
        phase = false
    }

    /**
     * One frame of LTC as PCM samples at the given sample rate.
     *
     * @param timecode the timecode this frame carries
     * @param sampleRate 48000 is the NDI audio rate
     * @return signed 16-bit little-endian PCM
     */
    fun encodeFrame(timecode: Timecode, sampleRate: Int = 48_000): ByteArray {
        val bits = frameBits(timecode)
        val samplesPerBit = sampleRate.toDouble() / (timecode.rate.fps * 80.0)
        val totalSamples = (samplesPerBit * 80).toInt() + 4
        val out = ByteArray(totalSamples * 2)

        var samplePos = 0
        var nextSample = 0.0

        for (i in 0 until 80) {
            val bit = bits[i]
            val endOfBit = nextSample + samplesPerBit

            // Biphase mark: always a transition at the start of each bit.
            phase = !phase
            writeUntil(out, phase, samplePos, nextSample.toInt())

            if (bit) {
                // A one gets a second transition at the midpoint.
                val mid = (nextSample + samplesPerBit / 2).toInt()
                writeUntil(out, phase, nextSample.toInt(), mid)
                phase = !phase
                writeUntil(out, phase, mid, endOfBit.toInt())
            } else {
                writeUntil(out, phase, nextSample.toInt(), endOfBit.toInt())
            }

            samplePos = endOfBit.toInt()
            nextSample = endOfBit
        }
        return out.copyOf(samplePos * 2)
    }

    private fun writeUntil(buf: ByteArray, high: Boolean, from: Int, to: Int) {
        val level = if (high) AMP else -AMP
        for (i in from until to) {
            val idx = i * 2
            if (idx + 1 >= buf.size) break
            buf[idx] = (level and 0xFF).toByte()
            buf[idx + 1] = ((level shr 8) and 0xFF).toByte()
        }
    }

    /**
     * The 80 bits of one LTC frame, packed as the spec says.
     *
     * The layout from LSB to MSB within each 32-bit group:
     *   0-3   frame units
     *   4-7   user bits 1
     *   8-9   frame tens (0-2)
     *   10    drop frame flag
     *   11    colour frame flag
     *   12-15 user bits 2
     *   16-19 seconds units
     *   20-23 user bits 3
     *   24-26 seconds tens (0-5)
     *   27    biphase mark correction (auto)
     *   28-31 user bits 4
     *   32-35 minutes units
     *   36-39 user bits 5
     *   40-42 minutes tens (0-5)
     *   43    binary group flag
     *   44-47 user bits 6
     *   48-51 hours units
     *   52-55 user bits 7
     *   56-57 hours tens (0-2)
     *   58    binary group flag 2
     *   59    reserved
     *   60-63 user bits 8
     *   64-79 sync word: 0011 1111 1111 1101
     */
    private fun frameBits(tc: Timecode): BooleanArray {
        val bits = BooleanArray(80)

        fun bcd(value: Int, start: Int, length: Int) {
            for (i in 0 until length) bits[start + i] = (value shr i) and 1 == 1
        }

        bcd(tc.frames % 10, 0, 4)
        bcd(tc.frames / 10, 8, 2)
        bits[10] = tc.rate.dropFrame

        bcd(tc.seconds % 10, 16, 4)
        bcd(tc.seconds / 10, 24, 3)

        bcd(tc.minutes % 10, 32, 4)
        bcd(tc.minutes / 10, 40, 3)

        bcd(tc.hours % 10, 48, 4)
        bcd(tc.hours / 10, 56, 2)

        // Biphase mark correction: the parity bit at position 27 ensures
        // the total count of transitions in the first 64 bits is even.
        // A decoder uses this to tell which polarity it is receiving.
        var transitions = 0
        var phase = false
        for (i in 0 until 64) {
            phase = !phase
            if (bits[i]) { transitions++; phase = !phase }
        }
        bits[27] = (transitions % 2) != 0

        // Sync word: the only 16-bit sequence the biphase encoding guarantees
        // cannot appear inside the data, because it has no transitions where
        // the rules require them.
        val sync = booleanArrayOf(
            false, false, true, true, true, true, true, true,
            true, true, true, true, true, true, false, true
        )
        sync.forEachIndexed { i, b -> bits[64 + i] = b }

        return bits
    }
}
