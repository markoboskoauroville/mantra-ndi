package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * White balance has gone green twice in this project. These are the assertions
 * that would have caught it, and the direction checks that say the fader moves
 * the way a camera operator expects rather than the way the arithmetic fell out.
 */
class WhiteBalanceTest {

    /** A plausible sensor: Standard A and D65, the usual calibrated pair. */
    private val colourMatrixA = floatArrayOf(
        0.9f, -0.30f, -0.10f,
        -0.40f, 1.25f, 0.13f,
        -0.05f, 0.18f, 0.55f
    )
    private val colourMatrixD65 = floatArrayOf(
        1.05f, -0.42f, -0.12f,
        -0.35f, 1.20f, 0.15f,
        -0.03f, 0.12f, 0.70f
    )

    /**
     * The fader stays inside what the sensor was measured at.
     *
     * His phone publishes Standard A and D65 — 2856K and 6504K — and beyond
     * those two anchors there is nothing left to interpolate between, so the
     * ends of a 2000..10000 sweep were the same clamped matrix over and over
     * while the number went on moving. Tungsten to daylight is the range he
     * works in and the range the measurement covers.
     */
    @Test
    fun `the fader covers tungsten to daylight and no further`() {
        assertEquals(3200, WhiteBalance.COOLEST_KELVIN)
        assertEquals(6500, WhiteBalance.WARMEST_KELVIN)
        // The two numbers an operator actually works in are both on it.
        assertTrue(WhiteBalance.travel(3200) < 0.001f)
        assertTrue(WhiteBalance.travel(5600) in 0.1f..0.99f)
        // And every point of it is inside his sensor's calibrated span.
        assertTrue(WhiteBalance.COOLEST_KELVIN >= 2856)
        assertTrue(WhiteBalance.WARMEST_KELVIN <= 6504 + 1)
    }

    @Test
    fun `mired travel is even, unlike Kelvin`() {
        // A hundred Kelvin at the tungsten end must move the fader further than
        // a hundred Kelvin at the daylight end. That is the whole reason for it.
        // Both pairs are inside the travel, because outside it the fader is
        // clamped and every difference is zero.
        val nearTungsten = WhiteBalance.travel(3300) - WhiteBalance.travel(3200)
        val nearDaylight = WhiteBalance.travel(6500) - WhiteBalance.travel(6400)
        assertTrue(
            "a hundred Kelvin must count for more at the warm end",
            nearTungsten > nearDaylight * 3
        )
    }

    @Test
    fun `the fader runs tungsten on the left to daylight on the right`() {
        assertEquals(0f, WhiteBalance.travel(WhiteBalance.COOLEST_KELVIN), 0.001f)
        assertEquals(1f, WhiteBalance.travel(WhiteBalance.WARMEST_KELVIN), 0.001f)
        assertTrue(WhiteBalance.travel(3200) < WhiteBalance.travel(5600))
    }

    @Test
    fun `a point on the fader and its temperature are the same place`() {
        for (kelvin in intArrayOf(3200, 3600, 4300, 5000, 5600, 6200, 6500)) {
            val back = WhiteBalance.kelvinAt(WhiteBalance.travel(kelvin))
            assertTrue("$kelvin came back as $back", kotlin.math.abs(back - kelvin) <= 25)
        }
    }

    @Test
    fun `a light gets bluer as it gets hotter`() {
        // Low Kelvin is a red light; high Kelvin is a blue one. If this is ever
        // the other way round, the whole fader is mirrored.
        assertTrue(
            "tungsten must be redder",
            WhiteBalance.chromaticity(2856)[0] > WhiteBalance.chromaticity(6504)[0]
        )
    }

    @Test
    fun `the warm end is a filament and the cool end is the sky`() {
        // Standard A is a real black body, and the fader must land on it.
        val tungsten = WhiteBalance.chromaticity(2856)
        assertEquals(0.4476, tungsten[0], 0.001)
        assertEquals(0.4074, tungsten[1], 0.001)

        // Daylight is NOT a black body, and this is the half-millimetre that
        // has twice been a green picture: at 6500K the two loci are 0.005 apart
        // in y, which is a green cast. D65, D55 and D50 are points on the
        // daylight curve, so the fader must be on that curve, not the other one.
        val d65 = WhiteBalance.chromaticity(6504)
        assertEquals(0.3127, d65[0], 0.001)
        assertEquals(0.3290, d65[1], 0.001)

        val d50 = WhiteBalance.chromaticity(5003)
        assertEquals(0.3457, d50[0], 0.001)
        assertEquals(0.3585, d50[1], 0.001)

        val d55 = WhiteBalance.chromaticity(5503)
        assertEquals(0.3324, d55[0], 0.001)
        assertEquals(0.3474, d55[1], 0.001)
    }

    @Test
    fun `the fader has no step in it where the two loci meet`() {
        // A lurch in colour part way along a fader is worse than either curve
        // being slightly off, because the operator sees it move.
        var previous = WhiteBalance.chromaticity(WhiteBalance.COOLEST_KELVIN)
        for (kelvin in (WhiteBalance.COOLEST_KELVIN + 10)..WhiteBalance.WARMEST_KELVIN step 10) {
            val here = WhiteBalance.chromaticity(kelvin)
            assertTrue(
                "a step at ${kelvin}K: $previous to $here",
                kotlin.math.abs(here[0] - previous[0]) < 0.004 &&
                    kotlin.math.abs(here[1] - previous[1]) < 0.004
            )
            previous = here
        }
    }

    @Test
    fun `telling the camera it is under tungsten cools the picture`() {
        // The label on a white balance dial is the light you are saying you are
        // under. Under a red light the camera must lift blue to get back to
        // neutral — so a tungsten setting carries the most blue gain, and using
        // it in daylight is what makes a shot go cold. That is the direction an
        // operator relies on and it must never silently flip.
        val tungsten = WhiteBalance.gains(2856, colourMatrixA)
        val daylight = WhiteBalance.gains(6504, colourMatrixA)
        assertTrue(
            "tungsten must ask for more blue than daylight",
            tungsten[3] / tungsten[0] > daylight[3] / daylight[0]
        )
    }

    @Test
    fun `gains are never below unity and never absurd`() {
        for (kelvin in WhiteBalance.COOLEST_KELVIN..WhiteBalance.WARMEST_KELVIN step 100) {
            for (matrix in listOf(colourMatrixA, colourMatrixD65)) {
                val g = WhiteBalance.gains(kelvin, matrix)
                assertEquals(4, g.size)
                assertEquals("the greens must match", g[1], g[2], 0f)
                assertTrue("a gain below one is not a thing", g.all { it >= 0.999f })
                assertTrue("a gain of $g at $kelvin is absurd", g.all { it < 32f })
                assertTrue("one of them must be exactly unity", g.any { it < 1.001f })
            }
        }
    }

    @Test
    fun `a matrix that cannot be inverted gives neutral rather than a refusal`() {
        // A refused repeating request stops the camera rather than being
        // ignored, so nothing here may ever produce a value the sensor rejects.
        val broken = FloatArray(9)
        assertTrue(WhiteBalance.gains(5600, broken).all { it == 1f })
        assertTrue(WhiteBalance.gains(5600, FloatArray(3)).all { it == 1f })
    }

    @Test
    fun `the blend sits on each illuminant at its own temperature`() {
        assertEquals(0.0, WhiteBalance.blend(2856, 2856, 6504), 0.0001)
        assertEquals(1.0, WhiteBalance.blend(6504, 2856, 6504), 0.0001)
        // And never outside them: extrapolating a measured matrix is how a
        // picture goes magenta at the ends of its range.
        assertEquals(0.0, WhiteBalance.blend(1500, 2856, 6504), 0.0001)
        assertEquals(1.0, WhiteBalance.blend(20000, 2856, 6504), 0.0001)
        // Halfway in mired, not halfway in Kelvin.
        val middle = WhiteBalance.blend(4000, 2856, 6504)
        assertTrue(middle > 0.4 && middle < 0.6)
    }

    @Test
    fun `mixing the two matrices lands on each one at its own end`() {
        val atA = WhiteBalance.mix(colourMatrixA, colourMatrixD65, 0.0)
        val atD = WhiteBalance.mix(colourMatrixA, colourMatrixD65, 1.0)
        for (i in 0..8) {
            assertEquals(colourMatrixA[i], atA[i], 1e-6f)
            assertEquals(colourMatrixD65[i], atD[i], 1e-6f)
        }
    }

    @Test
    fun `both halves come out of the same blend`() {
        // This is the v65 bug, stated as a test. The gains and the matrix must
        // be derived from one interpolation: if they are taken at different
        // temperatures they disagree, and on a Bayer sensor a disagreement reads
        // as green because green has twice the samples.
        val kelvin = 4300
        val t = WhiteBalance.blend(kelvin, 2856, 6504)
        val colour = WhiteBalance.mix(colourMatrixA, colourMatrixD65, t)
        val gains = WhiteBalance.gains(kelvin, colour)

        // Neutral in, neutral out: the gains must put the light this matrix
        // says the sensor sees back onto the grey axis.
        val raw = WhiteBalance.apply(colour, WhiteBalance.planckianXyz(kelvin))
        val balanced = doubleArrayOf(
            raw[0] * gains[0], raw[1] * gains[1], raw[2] * gains[3]
        )
        assertEquals(balanced[0], balanced[1], 1e-6)
        assertEquals(balanced[1], balanced[2], 1e-6)
    }

    @Test
    fun `an identity forward matrix gives the standard sRGB transform`() {
        val t = WhiteBalance.transform(WhiteBalance.IDENTITY)
        for (i in 0..8) assertEquals(WhiteBalance.SRGB_FROM_XYZ_D50[i], t[i], 1e-5f)
        // And a sensor that publishes nothing gets identity rather than zeros,
        // because a zero matrix is a black picture.
        assertEquals(WhiteBalance.IDENTITY.toList(), WhiteBalance.transform(FloatArray(3)).toList())
    }

    @Test
    fun `the nearest preset is nearest in mired`() {
        assertEquals(0, WhiteBalance.nearestPreset(2700))
        assertEquals(3, WhiteBalance.nearestPreset(5500))
        assertEquals(6, WhiteBalance.nearestPreset(12000))
        // Only from the ones this camera actually offers.
        assertEquals(3, WhiteBalance.nearestPreset(2700, intArrayOf(3, 4)))
        assertEquals(-1, WhiteBalance.nearestPreset(5500, IntArray(0)))
    }

    @Test
    fun `the illuminant codes are the ones a sensor actually publishes`() {
        assertEquals(2856, WhiteBalance.kelvinForIlluminant(17))  // Standard A
        assertEquals(6504, WhiteBalance.kelvinForIlluminant(21))  // D65
        assertEquals(3200, WhiteBalance.kelvinForIlluminant(24))  // studio tungsten
        // Anything unknown is daylight rather than a guess: a wrong anchor
        // tilts the whole fader.
        assertEquals(6504, WhiteBalance.kelvinForIlluminant(999))
    }
}
