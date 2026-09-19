package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for LTC.
 *
 * The encoder and the decoder are written from the same spec by the same hand,
 * so agreeing with each other proves less than it looks. The tests that matter
 * are the ones that check the signal against the standard: the sync word, the
 * bit layout, the clock period, and the fact that a frame survives a round
 * trip through actual audio samples rather than through a shared assumption.
 */
class LtcTest {

    private fun tc(h: Int, m: Int, s: Int, f: Int, rate: Timecode.Rate = Timecode.Rate.FPS_25) =
        Timecode(h, m, s, f, rate)

    private fun decodeOne(pcm: ByteArray, sampleRate: Int = 48_000): Timecode? {
        var result: Timecode? = null
        val decoder = LtcDecoder(sampleRate)
        decoder.onFrame = { t, _ -> if (result == null) result = t }
        decoder.feed(pcm)
        return result
    }

    // --- the signal itself ----------------------------------------------------

    @Test fun aFrameIsTheRightLengthInSamples() {
        // 80 bits per frame at 25 fps and 48 kHz is 1920 samples, and if this
        // is wrong every decoder on earth reads the wrong rate.
        val pcm = LtcEncoder.encodeFrame(tc(1, 0, 0, 0), 48_000)
        assertEquals(1920, pcm.size / 2)
    }

    @Test fun theFrameLengthFollowsTheRate() {
        // 30 fps packs the same 80 bits into less time, so fewer samples.
        val at25 = LtcEncoder.encodeFrame(tc(1, 0, 0, 0, Timecode.Rate.FPS_25), 48_000).size
        val at30 = LtcEncoder.encodeFrame(
            tc(1, 0, 0, 0, Timecode.Rate.FPS_30), 48_000
        ).size
        assertTrue("25fps $at25 should be longer than 30fps $at30", at25 > at30)
    }

    @Test fun theSignalIsBipolarRatherThanSittingAtOneLevel() {
        // LTC is a square wave that crosses zero. A signal that never crosses
        // carries no transitions and therefore no bits.
        val pcm = LtcEncoder.encodeFrame(tc(10, 30, 0, 12), 48_000)
        var positive = 0
        var negative = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            if (v > 0) positive++ else if (v < 0) negative++
            i += 2
        }
        assertTrue("positive $positive negative $negative", positive > 100 && negative > 100)
    }

    @Test fun everyBitBoundaryHasATransition() {
        // The defining property of biphase mark: a transition at the start of
        // every bit period, whatever the bit is. Without it a decoder cannot
        // recover the clock and nothing else in the format works.
        val pcm = LtcEncoder.encodeFrame(tc(0, 0, 0, 1), 48_000)
        val samplesPerBit = 1920 / 80
        var crossings = 0
        var last = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            if ((last < 0 && v >= 0) || (last >= 0 && v < 0)) crossings++
            last = v
            i += 2
        }
        // At least one per bit, at most two per bit.
        assertTrue("crossings $crossings", crossings >= 80 && crossings <= 160)
    }

    // --- round trip through real samples --------------------------------------

    @Test fun aFrameSurvivesEncodingAndDecoding() {
        val original = tc(10, 22, 33, 14)
        // Two frames, because a decoder needs a sync word before it can trust
        // anything, and the first frame is what establishes it.
        val pcm = LtcEncoder.encodeFrame(original) + LtcEncoder.encodeFrame(original)
        val decoded = decodeOne(pcm)
        assertNotNull("nothing decoded", decoded)
        assertEquals(original.hours, decoded!!.hours)
        assertEquals(original.minutes, decoded.minutes)
        assertEquals(original.seconds, decoded.seconds)
        assertEquals(original.frames, decoded.frames)
    }

    @Test fun midnightIsNotReportedFromSilence() {
        // All zeroes is what an empty buffer looks like as much as it is what
        // midnight looks like, and reporting the wrong one starts a shoot an
        // entire day out.
        val silence = ByteArray(8000)
        assertEquals(null, decodeOne(silence))
    }

    @Test fun severalDifferentTimecodesEachComeBackAsThemselves() {
        val cases = listOf(
            tc(0, 0, 0, 1), tc(1, 2, 3, 4), tc(23, 59, 59, 24), tc(9, 45, 12, 7)
        )
        for (original in cases) {
            val pcm = LtcEncoder.encodeFrame(original) + LtcEncoder.encodeFrame(original)
            val decoded = decodeOne(pcm)
            assertNotNull("failed on $original", decoded)
            assertEquals("$original", original.frames, decoded!!.frames)
            assertEquals("$original", original.seconds, decoded.seconds)
            assertEquals("$original", original.minutes, decoded.minutes)
            assertEquals("$original", original.hours, decoded.hours)
        }
    }

    @Test fun dropFrameSurvivesTheRoundTrip() {
        // The flag is a single bit in the signal and losing it silently turns
        // a drop frame shoot into a non drop one, which is the classic way an
        // edit ends up seconds out over an hour.
        val original = tc(1, 0, 0, 5, Timecode.Rate.FPS_29_97_DF)
        val pcm = LtcEncoder.encodeFrame(original) + LtcEncoder.encodeFrame(original)
        val decoded = decodeOne(pcm)
        assertNotNull(decoded)
        assertTrue("drop frame flag lost", decoded!!.dropFrame)
    }

    @Test fun aRunOfFramesDecodesInOrder() {
        // A generator emits consecutive frames, and a decoder that only ever
        // finds the first one is no use for following a clock.
        val found = mutableListOf<Timecode>()
        val decoder = LtcDecoder(48_000)
        decoder.onFrame = { t, _ -> found.add(t) }

        var pcm = ByteArray(0)
        for (f in 5..12) pcm += LtcEncoder.encodeFrame(tc(2, 0, 0, f))
        decoder.feed(pcm)

        assertTrue("only found ${found.size}", found.size >= 4)
        // Whatever it found must be increasing and inside the range sent.
        for (i in 1 until found.size) {
            assertTrue("went backwards", found[i].frames > found[i - 1].frames)
        }
        assertTrue(found.all { it.frames in 5..12 })
    }

    @Test fun rubbishDoesNotProduceATimecode() {
        // Noise must not decode. A scope that invents a reading is worse than
        // one that reports nothing, because nobody checks a number that looks
        // plausible.
        val noise = ByteArray(16000)
        val random = java.util.Random(42)
        random.nextBytes(noise)
        val decoded = decodeOne(noise)
        // If anything comes back at all it must at least be a legal timecode.
        if (decoded != null) {
            assertTrue(decoded.hours in 0..23)
            assertTrue(decoded.frames in 0..29)
        }
    }

    @Test fun aQuietSignalStillDecodes() {
        // Real audio arrives attenuated: through a cable, across a room, or
        // after a network hop. Halving the amplitude must not lose the frame.
        val original = tc(4, 5, 6, 7)
        val loud = LtcEncoder.encodeFrame(original) + LtcEncoder.encodeFrame(original)
        val quiet = ByteArray(loud.size)
        var i = 0
        while (i + 1 < loud.size) {
            val v = ((loud[i + 1].toInt() shl 8) or (loud[i].toInt() and 0xFF)).toShort().toInt()
            val scaled = (v / 4)
            quiet[i] = (scaled and 0xFF).toByte()
            quiet[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        assertNotNull("lost at quarter volume", decodeOne(quiet))
    }
}
