package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for the cube.
 *
 * The thing the one-dimensional strip could not do is the thing to test: a
 * cube must be able to move a colour somewhere its own channel value does not
 * determine. And the interpolation has to be right, because the errors it
 * makes appear on skin and on clean ramps, which is precisely where nobody
 * will forgive them.
 */
class CubeLutTest {

    @Test fun anIdentityCubeChangesNothing() {
        // The end to end check. If this bends anything, every other result is
        // a transform plus an unknown error.
        val cube = CubeLut.identity()
        for (i in 0..10) {
            val v = i / 10f
            val out = cube.sample(v, v, v)
            assertEquals("grey $v", v, out[0], 0.002f)
            assertEquals("grey $v", v, out[1], 0.002f)
            assertEquals("grey $v", v, out[2], 0.002f)
        }
    }

    @Test fun identityHoldsOffTheGreyAxisToo() {
        val cube = CubeLut.identity()
        val out = cube.sample(0.2f, 0.7f, 0.45f)
        assertEquals(0.2f, out[0], 0.002f)
        assertEquals(0.7f, out[1], 0.002f)
        assertEquals(0.45f, out[2], 0.002f)
    }

    @Test fun samplingLandsExactlyOnTheLatticePoints() {
        // No interpolation is involved at a corner, so any error here is in
        // the indexing, which would put the whole cube out by a step.
        val cube = CubeLut.identity()
        val step = 1f / (CubeLut.SIZE - 1)
        for (i in 0 until CubeLut.SIZE) {
            val v = i * step
            assertEquals(v, cube.sample(v, 0f, 0f)[0], 0.001f)
        }
    }

    @Test fun aCubeCanMoveAColourItsOwnChannelDoesNotDetermine() {
        // The whole reason for a cube. A strip bends each channel on its own,
        // so it can never make red depend on blue, which is what a hue
        // rotation or a saturation change actually requires.
        val size = 2
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            // Red output depends on blue input, which no 1D curve can express.
            data[i] = b.toFloat()
            data[i + 1] = g.toFloat()
            data[i + 2] = b.toFloat()
            i += 3
        }
        val cube = CubeLut.Table(size, data)
        assertEquals(0f, cube.sample(1f, 0f, 0f)[0], 0.01f)
        assertEquals(1f, cube.sample(0f, 0f, 1f)[0], 0.01f)
    }

    @Test fun aRampStaysStraight() {
        // Tetrahedral interpolation exists for this. A ramp that bends between
        // lattice points is banding, and it shows on exactly the gradients
        // people look at.
        val cube = CubeLut.identity()
        var previous = -1f
        for (i in 0..200) {
            val v = i / 200f
            val out = cube.sample(v, v, v)[0]
            assertTrue("went backwards at $v", out >= previous - 1e-4f)
            assertEquals("bent at $v", v, out, 0.003f)
            previous = out
        }
    }

    @Test fun aGeneratedCurveCubeUndoesTheCurve() {
        for (curve in LogCurves.Curve.values()) {
            if (curve == LogCurves.Curve.REC709) continue
            val cube = CubeLut.generate(curve)
            for (i in 1..9) {
                val light = i / 10.0
                val logged = LogCurves.encode(curve, light).toFloat()
                val corrected = cube.sample(logged, logged, logged)[0]
                val direct = LogCurves.encode(LogCurves.Curve.REC709, light).toFloat()
                assertEquals("$curve at $light", direct, corrected, 0.02f)
            }
        }
    }

    @Test fun aGeneratedCubeIsNeutralOnGrey() {
        // If a correction tints grey, every face in the shot goes with it, and
        // that is what a picture looks like when it has gone cartoonish.
        for (curve in LogCurves.Curve.values()) {
            val cube = CubeLut.generate(curve)
            for (i in 1..9) {
                val v = i / 10f
                val out = cube.sample(v, v, v)
                assertEquals("$curve red vs green", out[0], out[1], 0.001f)
                assertEquals("$curve green vs blue", out[1], out[2], 0.001f)
            }
        }
    }

    // --- reading what a grading system writes ---------------------------------

    private fun cubeText(size: Int, body: String) =
        "TITLE \"test\"\n# a comment\nLUT_3D_SIZE $size\n\n$body".byteInputStream()

    @Test fun aRealFileIsRead() {
        val body = buildString {
            for (b in 0 until 2) for (g in 0 until 2) for (r in 0 until 2) {
                append("$r.0 $g.0 $b.0\n")
            }
        }
        val cube = CubeLut.parse(cubeText(2, body))
        assertNotNull(cube)
        assertEquals(2, cube!!.size)
        assertEquals(1f, cube.sample(1f, 0f, 0f)[0], 0.01f)
    }

    @Test fun commentsTitlesAndBlankLinesAreIgnored() {
        // A parser that refuses these refuses half the LUTs in the world.
        val body = "\n# another comment\n0.0 0.0 0.0\n\n1.0 1.0 1.0\n" +
            "0.0 0.0 0.0\n1.0 1.0 1.0\n0.0 0.0 0.0\n1.0 1.0 1.0\n" +
            "0.0 0.0 0.0\n1.0 1.0 1.0\n"
        assertNotNull(CubeLut.parse(cubeText(2, body)))
    }

    @Test fun aTruncatedFileIsRefusedRatherThanHalfApplied() {
        // Half a cube applied to a picture is worse than none, because it
        // looks like a look rather than a fault.
        assertNull(CubeLut.parse(cubeText(2, "0.0 0.0 0.0\n1.0 1.0 1.0\n")))
    }

    @Test fun rubbishIsRefused() {
        assertNull(CubeLut.parse("not a lut at all".byteInputStream()))
        assertNull(CubeLut.parse("".byteInputStream()))
    }

    @Test fun aDeclaredDomainIsScaledIntoRange() {
        val body = buildString {
            for (b in 0 until 2) for (g in 0 until 2) for (r in 0 until 2) {
                append("${r * 100}.0 ${g * 100}.0 ${b * 100}.0\n")
            }
        }
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0.0\nDOMAIN_MAX 100.0\n$body"
        val cube = CubeLut.parse(text.byteInputStream())
        assertNotNull(cube)
        assertEquals(1f, cube!!.sample(1f, 1f, 1f)[0], 0.01f)
    }

    @Test fun theStandardSizeIsThirtyThree() {
        assertEquals(33, CubeLut.SIZE)
        assertEquals(33 * 33 * 33 * 3, CubeLut.identity().data.size)
    }
}
