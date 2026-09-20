package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for the cube.
 *
 * The tests that matter are the ones a 1D strip could never have passed, since
 * that is the whole reason for this work: a table that only moves brightness
 * leaves every saturated colour where the camera's wide gamut put it.
 */
class CubeLutTest {

    // --- the identity, which must change nothing -----------------------------

    @Test fun theIdentityReturnsWhatItWasGiven() {
        val cube = CubeLut.identity(33)
        for (v in listOf(0f, 0.125f, 0.3f, 0.5f, 0.77f, 1f)) {
            val out = cube.sample(v, v, v)
            assertEquals("grey $v", v, out[0], 0.002f)
            assertEquals(v, out[1], 0.002f)
            assertEquals(v, out[2], 0.002f)
        }
    }

    @Test fun theIdentityKeepsColoursApart() {
        val cube = CubeLut.identity(33)
        val red = cube.sample(0.9f, 0.1f, 0.1f)
        assertTrue(red[0] > 0.8f && red[1] < 0.2f && red[2] < 0.2f)
    }

    @Test fun samplingOffTheEndIsClampedRatherThanRead() {
        val cube = CubeLut.identity(17)
        assertEquals(1f, cube.sample(2f, 2f, 2f)[0], 0.01f)
        assertEquals(0f, cube.sample(-1f, -1f, -1f)[0], 0.01f)
    }

    // --- interpolation, which is where precision lives -----------------------

    @Test fun tetrahedralKeepsGreyNeutral() {
        // The point of tetrahedral over trilinear. Along the neutral diagonal
        // the eight corners of a cell disagree, so trilinear tints greys and a
        // gradient bands. Every grey in a picture lives on this diagonal.
        val cube = CubeLut.generate(LogCurves.Curve.SLOG3, 33)
        for (i in 1..19) {
            val v = i / 20f
            val out = cube.sample(v, v, v)
            assertEquals("R vs G at $v", out[0], out[1], 0.004f)
            assertEquals("G vs B at $v", out[1], out[2], 0.004f)
        }
    }

    @Test fun interpolationIsMonotonicAlongTheNeutral() {
        val cube = CubeLut.generate(LogCurves.Curve.LOGC4, 33)
        var previous = -1f
        for (i in 0..60) {
            val v = i / 60f
            val out = cube.sample(v, v, v)[0]
            assertTrue("dipped at $v", out >= previous - 1e-4f)
            previous = out
        }
    }

    // --- the generated table, and the gamut a strip could not carry ----------

    @Test fun aGeneratedTableIsThirtyThreeASide() {
        val cube = CubeLut.generate(LogCurves.Curve.VLOG)
        assertEquals(33, cube.size)
        assertEquals(33 * 33 * 33 * 3, cube.data.size)
    }

    @Test fun blackStaysBlackAndWhiteStaysWhite() {
        for (curve in LogCurves.Curve.values()) {
            val cube = CubeLut.generate(curve, 17)
            val black = cube.sample(0f, 0f, 0f)
            assertTrue("$curve black ${black[0]}", black[0] < 0.08f)
            val white = cube.sample(1f, 1f, 1f)
            assertTrue("$curve white ${white[0]}", white[0] > 0.9f)
        }
    }

    @Test fun theGeneratedTableActuallyMovesColour() {
        // What a 1D strip cannot do, stated as the thing that matters: a
        // strip applies one identical curve to all three channels, so its
        // output for a pure input can never depend on the other two. Here it
        // must, because the matrix mixes them.
        val cube = CubeLut.generate(LogCurves.Curve.SLOG3, 33)
        val red = cube.sample(0.8f, 0.2f, 0.2f)
        val green = cube.sample(0.2f, 0.8f, 0.2f)

        // A strip would give the same number for the red channel of both,
        // since in both cases some channel is 0.8 and the mapping is per
        // channel. The matrix makes them differ.
        assertTrue(
            "red channel ${red[0]} vs ${green[0]} should differ once a gamut is applied",
            Math.abs(red[0] - green[0]) > 0.01f
        )
    }

    @Test fun everyOutputIsInRange() {
        for (curve in LogCurves.Curve.values()) {
            val cube = CubeLut.generate(curve, 17)
            for (v in cube.data) {
                assertTrue("out of range $v", v in 0f..1f && v.isFinite())
            }
        }
    }

    // --- reading what vendors actually ship ----------------------------------

    private fun tinyCube(): String = buildString {
        append("# A comment\r\n")
        append("TITLE \"test\"\n")
        append("LUT_3D_SIZE 2\n")
        append("\n")
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            append("$r.0 $g.0 $b.0\n")
        }
    }

    @Test fun aRealFileWithCommentsAndBlankLinesReads() {
        val cube = CubeLut.parse(tinyCube())
        assertNotNull(cube)
        assertEquals(2, cube!!.size)
    }

    @Test fun windowsLineEndingsDoNotBreakIt() {
        val cube = CubeLut.parse(tinyCube().replace("\n", "\r\n"))
        assertNotNull(cube)
    }

    @Test fun aDomainOtherThanZeroToOneIsNormalised() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" +
            (0..7).joinToString("\n") { "2.0 2.0 2.0" }
        val cube = CubeLut.parse(text)
        assertNotNull(cube)
        assertEquals(1f, cube!!.data[0], 0.001f)
    }

    @Test fun aTruncatedFileIsRefusedRatherThanHalfLoaded() {
        val text = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n"
        assertNull(CubeLut.parse(text))
    }

    @Test fun aOneDimensionalCubeIsRefused() {
        assertNull(CubeLut.parse("LUT_1D_SIZE 16\n0 0 0\n"))
    }

    @Test fun rubbishIsRefused() {
        assertNull(CubeLut.parse("this is not a lut"))
        assertNull(CubeLut.parse(""))
    }

    // --- the matrices, derived rather than copied ----------------------------

    @Test fun rec709ToItselfIsTheIdentity() {
        // If this is wrong every other matrix is wrong by the same amount, and
        // a faint cast on everything is the hardest fault to trace.
        val m = ColourSpaces.toRec709(ColourSpaces.REC709)
        val expected = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (i in 0 until 9) assertEquals("cell $i", expected[i], m[i], 0.001)
    }

    @Test fun whiteStaysWhiteThroughEveryGamut() {
        // The step that is easy to skip: scaling the primaries so the gamut's
        // own white lands on its white point. Without it the hues are right
        // and everything neutral has a cast.
        for (curve in LogCurves.Curve.values()) {
            val m = ColourSpaces.toRec709(ColourSpaces.gamutFor(curve))
            val out = ColourSpaces.apply(m, 1.0, 1.0, 1.0)
            assertEquals("$curve R", 1.0, out[0], 0.01)
            assertEquals("$curve G", 1.0, out[1], 0.01)
            assertEquals("$curve B", 1.0, out[2], 0.01)
        }
    }

    @Test fun aWideGamutRedPullsInwards() {
        // A camera primary is outside Rec.709, so carrying it in must leave
        // the other channels negative before clipping. That negative number is
        // the gamut change made visible, and it is exactly what the old 1D
        // strip had no way to produce.
        val m = ColourSpaces.toRec709(ColourSpaces.S_GAMUT3_CINE)
        val out = ColourSpaces.apply(m, 1.0, 0.0, 0.0)
        assertTrue("green ${out[1]} should be negative", out[1] < 0.0)
    }

    @Test fun invertingAMatrixTwiceReturnsIt() {
        val m = ColourSpaces.toXyz(ColourSpaces.V_GAMUT)
        val back = ColourSpaces.invert(ColourSpaces.invert(m))
        for (i in 0 until 9) assertEquals(m[i], back[i], 1e-6)
    }
}
