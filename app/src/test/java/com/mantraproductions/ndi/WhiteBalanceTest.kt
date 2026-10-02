package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the fader covers a candle to the blue sky`() {
        // v127: the curve is measured (or physics), not the calibration's two points, so it goes on past them
        assertEquals(2000, WhiteBalance.COOLEST_KELVIN)
        assertEquals(10000, WhiteBalance.WARMEST_KELVIN)
        assertTrue(WhiteBalance.travel(3200) in 0.1f..0.99f)
        assertTrue(WhiteBalance.travel(5600) in 0.1f..0.99f)
    }

    // --- the measured curve (v127) ---------------------------------------------

    /** A curve the shape a real sensor's presets have: tungsten wants much more blue and less red. */
    private val real = listOf(
        2700 to doubleArrayOf(1.30, 2.70), 5500 to doubleArrayOf(2.00, 1.55),
        6500 to doubleArrayOf(2.15, 1.42), 7500 to doubleArrayOf(2.25, 1.35)
    )

    @Test
    fun `down the fader the paper goes blue, never yellow`() {
        val camera = floatArrayOf(2.056f, 1f, 1f, 2.027f)
        val anchor = WhiteBalance.curveKelvin(real, camera)
        var lastBlue = 0f
        var lastRed = Float.MAX_VALUE
        // from the warm end of the dial to the cool end: tungsten setting = most blue gain
        for (k in intArrayOf(2000, 2700, 3200, 3600, 4500, 5600, 6500, 7500, 10000)) {
            val g = WhiteBalance.alongCurve(real, camera, anchor, k)
            if (lastBlue != 0f) {
                assertTrue("${k}K: blue gain must fall as the setting rises", g[3] < lastBlue)
                assertTrue("${k}K: red gain must rise as the setting rises", g[0] > lastRed)
            }
            lastBlue = g[3]; lastRed = g[0]
            // and no green creeps in: both greens are the camera's own
            assertEquals(1f, g[1], 0f); assertEquals(1f, g[2], 0f)
        }
    }

    @Test
    fun `at the camera's own temperature the picture does not move`() {
        val camera = floatArrayOf(2.0f, 1f, 1f, 1.55f)
        val anchor = WhiteBalance.curveKelvin(real, camera)
        val g = WhiteBalance.alongCurve(real, camera, anchor, anchor)
        assertEquals(2.0f, g[0], 1e-4f); assertEquals(1.55f, g[3], 1e-4f)
        assertTrue("anchor $anchor", anchor in 5300..5700)
    }

    @Test
    fun `the Pixel's flat calibration is refused, a real one kept`() {
        // what the Pixel 7's published matrices give (measured 2.10.2026): B/G 0.907 at 3200K, 1.000 at 6500K
        assertFalse(WhiteBalance.calibrationIsReal(floatArrayOf(1.004f, 1f, 1f, 0.907f), floatArrayOf(1.001f, 1f, 1f, 1.0f)))
        assertTrue(WhiteBalance.calibrationIsReal(floatArrayOf(1.30f, 1f, 1f, 2.70f), floatArrayOf(2.15f, 1f, 1f, 1.42f)))
        // a measured curve with no spread, or blue rising with temperature, is not walked
        assertEquals(null, WhiteBalance.usableCurve(listOf(3200 to doubleArrayOf(1.0, 0.91), 6500 to doubleArrayOf(1.0, 1.0))))
        assertEquals(4, WhiteBalance.usableCurve(real)?.size)
    }

    @Test
    fun `the physics curve goes the right way everywhere`() {
        val c = WhiteBalance.physicsCurve()
        assertEquals(c.size, WhiteBalance.usableCurve(c)?.size)
        for (i in 1 until c.size) {
            assertTrue(c[i].second[1] < c[i - 1].second[1])
            assertTrue(c[i].second[0] > c[i - 1].second[0])
        }
    }

    @Test
    fun `a kept curve comes back the same`() {
        val back = WhiteBalance.decodeCurve(WhiteBalance.encodeCurve(real))!!
        assertEquals(real.size, back.size)
        for (i in real.indices) {
            assertEquals(real[i].first, back[i].first)
            assertEquals(real[i].second[1], back[i].second[1], 1e-4)
        }
        assertEquals(null, WhiteBalance.decodeCurve("nonsense"))
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

    // --- the anchor ---------------------------------------------------------

    private fun colourAt(kelvin: Int): FloatArray =
        WhiteBalance.mix(
            colourMatrixA, colourMatrixD65, WhiteBalance.blend(kelvin, 2856, 6504)
        )

    /**
     * **The green, stated as a test.**
     *
     * The whole of the anchor is this: at the temperature the camera's own
     * answer amounts to, the fader must hand the camera back *exactly* what it
     * was already doing. Anything else is an absolute error, and an absolute
     * error in white balance on a Bayer sensor has one colour.
     */
    @Test
    fun `at the anchor the camera gets its own answer back untouched`() {
        val measured = floatArrayOf(1.94f, 1f, 1f, 1.62f)
        val anchor = WhiteBalance.anchorKelvin(measured) { colourAt(it) }
        val model = WhiteBalance.gains(anchor, colourAt(anchor))
        val out = WhiteBalance.shiftGains(measured, model, model)
        for (i in 0..3) {
            assertEquals("channel $i", measured[i].toDouble(), out[i].toDouble(), 0.001)
        }
    }

    @Test
    fun `the anchor is the temperature the camera's own gains amount to`() {
        for (kelvin in intArrayOf(3200, 4000, 5000, 6500)) {
            val gains = WhiteBalance.gains(kelvin, colourAt(kelvin))
            val found = WhiteBalance.anchorKelvin(gains) { colourAt(it) }
            // Within a hundred Kelvin, which is finer than the fader prints.
            assertTrue("$kelvin came back as $found", kotlin.math.abs(found - kelvin) <= 100)
        }
    }

    @Test
    fun `moving off the anchor moves both halves the way the calibration says`() {
        val measured = floatArrayOf(1.94f, 1f, 1f, 1.62f)
        val anchor = WhiteBalance.anchorKelvin(measured) { colourAt(it) }
        val atAnchor = WhiteBalance.gains(anchor, colourAt(anchor))
        val warm = WhiteBalance.shiftGains(
            measured, atAnchor, WhiteBalance.gains(3200, colourAt(3200))
        )
        val cool = WhiteBalance.shiftGains(
            measured, atAnchor, WhiteBalance.gains(6500, colourAt(6500))
        )
        // Telling the camera the light is tungsten must cool the picture: more
        // blue against red, not less. The direction is the whole of the fader.
        assertTrue(
            "warm ${warm[3] / warm[0]} vs cool ${cool[3] / cool[0]}",
            warm[3] / warm[0] > cool[3] / cool[0]
        )
        // And nothing below unity, which is not a gain a sensor can be asked for.
        for (g in warm + cool) assertTrue("gain $g", g >= 0.999f)
    }

    @Test
    fun `a matrix and its inverse come back to where they started`() {
        val m = floatArrayOf(
            1.2f, -0.2f, 0.05f,
            -0.1f, 1.1f, -0.02f,
            0.03f, -0.15f, 1.05f
        )
        val back = WhiteBalance.invert(m)!!
        val identity = WhiteBalance.multiply(m, back)
        for (i in 0..8) {
            assertEquals(
                "element $i",
                WhiteBalance.IDENTITY[i].toDouble(), identity[i].toDouble(), 0.0005
            )
        }
        // A matrix with no inverse is said so rather than answered with noise.
        assertTrue(WhiteBalance.invert(FloatArray(9)) == null)
    }

    @Test
    fun `the transform is anchored the same way the gains are`() {
        val measured = floatArrayOf(
            1.1f, -0.1f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, -0.05f, 1.2f
        )
        val model = WhiteBalance.transform(colourAt(4000))
        val same = WhiteBalance.shiftTransform(measured, model, model)!!
        for (i in 0..8) {
            assertEquals("element $i", measured[i].toDouble(), same[i].toDouble(), 0.0005)
        }
    }

    // --- the spot (v100) ----------------------------------------------------------

    /** The case that started it: a white key seen blue. Blue gain must fall, red must rise. */
    @Test fun spotTurnsABlueWhiteNeutral() {
        val gains = floatArrayOf(1.529f, 1f, 1f, 2.448f)      // the trace of 2.10.2026
        val seenBlue = doubleArrayOf(0.30, 0.40, 0.70)        // the white key, through those gains
        val next = WhiteBalance.spotGains(gains, seenBlue)
        assertTrue("red rises", next[0] > gains[0])
        assertTrue("blue falls", next[3] < gains[3])
        assertEquals("green is the reference", 1f, next[1], 0f)
        // a linear sensor answers in proportion: one round lands it
        val r = seenBlue[0] / gains[0] * next[0]; val b = seenBlue[2] / gains[3] * next[3]
        assertTrue(WhiteBalance.spotNeutral(doubleArrayOf(r, 0.40, b)))
    }

    @Test fun spotLeavesANeutralBoxAlone() {
        val gains = floatArrayOf(2.0f, 1f, 1f, 1.6f)
        val next = WhiteBalance.spotGains(gains, doubleArrayOf(0.5, 0.5, 0.5))
        assertEquals(2.0f, next[0], 1e-6f); assertEquals(1.6f, next[3], 1e-6f)
        assertTrue(WhiteBalance.spotNeutral(doubleArrayOf(0.5, 0.505, 0.495)))
    }

    /** A box full of one coloured light must not throw the picture across the room in one round. */
    @Test fun spotMovesAtMostHalfOrDoublePerRound() {
        val next = WhiteBalance.spotGains(floatArrayOf(2f, 1f, 1f, 2f), doubleArrayOf(0.01, 0.5, 0.9))
        assertEquals(4f, next[0], 1e-6f)          // capped at double
        assertEquals(1.111f, next[3], 1e-3f)       // 0.5/0.9 is inside the cap
        val far = WhiteBalance.spotGains(floatArrayOf(6f, 1f, 1f, 0.6f), doubleArrayOf(0.01, 0.5, 5.0))
        assertEquals(WhiteBalance.SPOT_MAX_GAIN, far[0], 1e-6f)
        assertEquals(WhiteBalance.SPOT_MIN_GAIN, far[3], 1e-6f)
    }

    /** A pipeline that answers r/g ∝ gain^3 (as steep as the phone's was): the learned steps must close, not swing. */
    @Test fun spotLearnClosesOnASteepPicture() {
        val truth = doubleArrayOf(0.7, 1.0, 1.6)          // the light, before gains
        fun seen(g: FloatArray) = doubleArrayOf(Math.pow(truth[0] * g[0], 3.0), 1.0, Math.pow(truth[2] * g[3], 3.0))
        var gains = floatArrayOf(1.529f, 1f, 1f, 2.448f)
        val learn = WhiteBalance.SpotLearn()
        var last = 0.0
        for (round in 1..WhiteBalance.SPOT_ROUNDS) {
            val rgb = seen(gains)
            if (WhiteBalance.spotNeutral(rgb, 0.03)) break
            val err = Math.abs(Math.log(rgb[0])) + Math.abs(Math.log(rgb[2]))
            if (round > 3) assertTrue("round $round got worse: $err after $last", err <= last + 1e-9)
            last = err
            gains = learn.next(gains, rgb)
        }
        assertTrue("neutral within the rounds: ${seen(gains).toList()}", WhiteBalance.spotNeutral(seen(gains), 0.03))
    }
}
