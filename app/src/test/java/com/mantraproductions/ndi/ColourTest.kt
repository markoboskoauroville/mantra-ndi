package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for the colour pipeline: curves, LUTs and the histogram.
 *
 * These are the cases that would let a wrong curve ship. A log curve that is
 * subtly wrong produces footage that looks plausible on set and cannot be
 * graded to match anything, which is the worst possible failure: invisible
 * until post.
 */
class ColourTest {

    private val allCurves = LogCurves.Curve.values()

    // --- the property that matters most: encode and decode are inverses ------

    @Test fun everyCurveRoundTripsAcrossItsWholeRange() {
        for (curve in allCurves) {
            var linear = 0.0001
            while (linear < 60.0) {
                val back = LogCurves.decode(curve, LogCurves.encode(curve, linear))
                assertEquals("$curve at $linear", linear, back, linear * 0.001 + 1e-6)
                linear *= 1.35
            }
        }
    }

    @Test fun everyCurveRoundTripsFromTheSignalSide() {
        for (curve in allCurves) {
            for (i in 0..100) {
                val signal = i / 100.0
                val linear = LogCurves.decode(curve, signal)
                if (linear < 0) continue
                val back = LogCurves.encode(curve, linear)
                assertEquals("$curve at $signal", signal, back, 0.0005)
            }
        }
    }

    // --- values published by the manufacturers ------------------------------

    @Test fun slog3PutsMiddleGreyWhereSonySaysItDoes() {
        // Sony's technical summary: 18% grey encodes to 0.4105571
        assertEquals(0.410557, LogCurves.middleGrey(LogCurves.Curve.SLOG3), 0.0005)
    }

    @Test fun vlogPutsMiddleGreyWherePanasonicSaysItDoes() {
        // Panasonic V-Log: 18% grey sits at 42.3 IRE
        assertEquals(0.423, LogCurves.middleGrey(LogCurves.Curve.VLOG), 0.002)
    }

    @Test fun logc3PutsMiddleGreyWhereArriSaysItDoes() {
        // ARRI LogC3 EI800: 18% grey at 0.391
        assertEquals(0.391, LogCurves.middleGrey(LogCurves.Curve.LOGC3), 0.003)
    }

    @Test fun everyLogCurveHoldsHighlightsThatRec709Throws() {
        // Grey is anchored near 0.41 in all of them, deliberately, so grey is
        // not what separates log from Rec.709. Highlights are. Ten stops over
        // middle grey leaves Rec.709 at about 3.0, which is to say gone, while
        // every log curve is still inside the signal with room left.
        assertTrue(LogCurves.encode(LogCurves.Curve.REC709, 10.0) > 2.0)
        for (curve in allCurves) {
            if (curve == LogCurves.Curve.REC709) continue
            val highlight = LogCurves.encode(curve, 10.0)
            assertTrue("$curve reached $highlight at 10x grey", highlight < 1.0)
            assertTrue("$curve wasted its range, only $highlight", highlight > 0.6)
        }
    }

    @Test fun middleGreyIsAnchoredNearTheSamePlaceInEveryCurve() {
        // Not a coincidence: it is what makes exposure transferable between
        // cameras. Anything outside this band is a wrong constant.
        for (curve in allCurves) {
            val grey = LogCurves.middleGrey(curve)
            assertTrue("$curve puts grey at $grey", grey in 0.33..0.46)
        }
    }

    @Test fun curvesAreMonotonicSoNoTwoBrightnessesSwapPlaces() {
        for (curve in allCurves) {
            var previous = -1.0
            var linear = 0.0
            while (linear < 20.0) {
                val value = LogCurves.encode(curve, linear)
                assertTrue("$curve went backwards at $linear", value >= previous - 1e-9)
                previous = value
                linear += 0.02
            }
        }
    }

    @Test fun blackStaysBlackAndNothingRunsAway() {
        for (curve in allCurves) {
            val black = LogCurves.encode(curve, 0.0)
            assertTrue("$curve black is $black", black >= -0.01 && black < 0.2)
            assertTrue("$curve is not finite", LogCurves.encode(curve, 1.0).isFinite())
        }
    }

    // --- conversion, which is what a LUT is ---------------------------------

    @Test fun convertingACurveToItselfChangesNothing() {
        for (curve in allCurves) {
            // Below a curve's own black there is no scene light to represent,
            // so conversion clamps. Start at the signal black actually maps to.
            val black = LogCurves.encode(curve, 0.0)
            for (i in 0..20) {
                val signal = black + (1.0 - black) * i / 20.0
                assertEquals(
                    "$curve at $signal", signal,
                    LogCurves.convert(curve, curve, signal), 0.001
                )
            }
        }
    }

    @Test fun signalsBelowBlackClampRatherThanProducingNonsense() {
        // LogC4 encodes negative scene values, so a signal under its black is
        // legal input and must come back inside the range, not as a NaN.
        for (curve in allCurves) {
            val v = LogCurves.convert(curve, LogCurves.Curve.REC709, 0.0)
            assertTrue("$curve gave $v", v.isFinite() && v in 0.0..1.0)
        }
    }

    @Test fun conversionIsReversibleThroughLinear() {
        val signal = 0.5
        val toRec = LogCurves.convert(LogCurves.Curve.SLOG3, LogCurves.Curve.REC709, signal)
        val back = LogCurves.convert(LogCurves.Curve.REC709, LogCurves.Curve.SLOG3, toRec)
        assertEquals(signal, back, 0.002)
    }

    @Test fun conversionNeverLeavesTheLegalRange() {
        for (from in allCurves) {
            for (to in allCurves) {
                for (i in 0..20) {
                    val v = LogCurves.convert(from, to, i / 20.0)
                    assertTrue("$from to $to gave $v", v in 0.0..1.0)
                }
            }
        }
    }

    // --- the generated LUT --------------------------------------------------

    @Test fun generatedLutHasExactlyTheEntriesTheHeaderPromises() {
        val size = 17
        val cube = CubeLut.generate(LogCurves.Curve.SLOG3, LogCurves.Curve.REC709, size)
        val rows = cube.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && it[0].isDigit() }
            .count()
        assertEquals(size * size * size, rows)
        assertTrue(cube.contains("LUT_3D_SIZE $size"))
    }

    @Test fun generatedLutParsesBackAsTheSameTable() {
        val cube = CubeLut.generate(LogCurves.Curve.VLOG, LogCurves.Curve.REC709, 9)
        val parsed = CubeLut.parse(cube)
        assertNotNull(parsed)
        assertEquals(9, parsed!!.size)
        assertEquals(9 * 9 * 9 * 3, parsed.data.size)
    }

    @Test fun theGeneratedLutActuallyUndoesTheCurve() {
        // Feed the LUT a log signal and it must return the Rec.709 value of the
        // same scene light. This is the test that a wrong LUT fails.
        val size = 33
        val parsed = CubeLut.parse(
            CubeLut.generate(LogCurves.Curve.SLOG3, LogCurves.Curve.REC709, size)
        )!!
        for (i in 0 until size) {
            val signal = i.toDouble() / (size - 1)
            val expected = LogCurves.convert(
                LogCurves.Curve.SLOG3, LogCurves.Curve.REC709, signal
            )
            val got = parsed.sample(signal.toFloat(), signal.toFloat(), signal.toFloat())
            assertEquals("at $signal", expected, got[0].toDouble(), 0.002)
        }
    }

    @Test fun lutOrderingPutsBlueSlowestAsTheFormatRequires() {
        val size = 3
        val cube = CubeLut.generate(LogCurves.Curve.REC709, LogCurves.Curve.REC709, size)
        val rows = cube.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && it[0].isDigit() }
            .toList()
        // Second row differs from the first in red only.
        val first = rows[0].split(" ")
        val second = rows[1].split(" ")
        assertTrue("red did not move first", first[0] != second[0])
        assertEquals("blue moved too early", first[2], second[2])
    }

    @Test fun everyCurveGeneratesAUsableLut() {
        for (curve in allCurves) {
            val cube = CubeLut.generate(curve, LogCurves.Curve.REC709, 9)
            assertNotNull("$curve produced nothing parseable", CubeLut.parse(cube))
        }
    }

    @Test fun absurdLutSizesAreRefusedRatherThanAttempted() {
        try {
            CubeLut.generate(LogCurves.Curve.SLOG3, LogCurves.Curve.REC709, 512)
            throw AssertionError("a 512 cube should have been refused")
        } catch (expected: IllegalArgumentException) {
            // 512 cubed is 134 million entries and several gigabytes of text.
        }
    }

    // --- parsing what the operator hands us ---------------------------------

    @Test fun rubbishIsRejectedRatherThanHalfRead() {
        assertNull(CubeLut.parse(""))
        assertNull(CubeLut.parse("hello"))
        assertNull(CubeLut.parse("LUT_3D_SIZE 4\n0.0 0.0 0.0\n"))
    }

    @Test fun aOneDimensionalLutIsRefusedNotMisread() {
        // Reading a 1D LUT as 3D would give a plausible looking wrong image.
        assertNull(CubeLut.parse("LUT_1D_SIZE 16\n0.0 0.0 0.0\n"))
    }

    @Test fun realWorldFormattingSurvives() {
        val messy = buildString {
            append("# a comment\r\n")
            append("\r\n")
            append("TITLE \"Some LUT\"\r\n")
            append("LUT_3D_SIZE 2\r\n")
            append("DOMAIN_MIN 0.0 0.0 0.0\r\n")
            append("DOMAIN_MAX 1.0 1.0 1.0\r\n")
            for (i in 0 until 8) append("0.5\t0.5\t0.5\r\n")
        }
        val parsed = CubeLut.parse(messy)
        assertNotNull(parsed)
        assertEquals("Some LUT", parsed!!.title)
        assertEquals(2, parsed.size)
    }

    @Test fun aTruncatedFileIsRefused() {
        val good = CubeLut.generate(LogCurves.Curve.LOGC3, LogCurves.Curve.REC709, 5)
        val truncated = good.lines().dropLast(10).joinToString("\n")
        assertNull(CubeLut.parse(truncated))
    }

    // --- histogram -----------------------------------------------------------

    @Test fun flatBlackLandsEntirelyInTheFirstBucket() {
        val buckets = Histogram.fromLuma(ByteArray(64 * 64), 64, 64, stride = 1)
        assertEquals(64 * 64, buckets[0])
        assertEquals(0, buckets.drop(1).sum())
    }

    @Test fun flatWhiteLandsEntirelyInTheLastBucket() {
        val luma = ByteArray(64 * 64) { 0xFF.toByte() }
        val buckets = Histogram.fromLuma(luma, 64, 64, stride = 1)
        assertEquals(64 * 64, buckets[Histogram.BUCKETS - 1])
    }

    @Test fun clippingIsReportedAsAFractionNotACount() {
        val luma = ByteArray(100) { if (it < 10) 0xFF.toByte() else 128.toByte() }
        val buckets = Histogram.fromLuma(luma, 100, 1, stride = 1)
        assertEquals(0.1f, Histogram.clippedHighlights(buckets), 0.001f)
    }

    @Test fun samplingSparselyGivesTheSameShapeAsSamplingEverything() {
        // Scene-like content, not a synthetic ramp: a perfect ramp aliases
        // against any fixed stride and would be testing arithmetic, not the
        // sampling decision. Compared in eighths, which is the resolution
        // anybody reads a histogram at.
        val w = 480
        val h = 480
        val random = java.util.Random(42)
        val luma = ByteArray(w * h) { (random.nextInt(256)).toByte() }
        val full = Histogram.fromLuma(luma, w, h, stride = 1)
        val sparse = Histogram.fromLuma(luma, w, h, stride = 12)

        fun eighths(buckets: IntArray): FloatArray {
            val total = buckets.sum().toFloat()
            val groups = FloatArray(8)
            buckets.forEachIndexed { i, v -> groups[i * 8 / buckets.size] += v / total }
            return groups
        }

        val a = eighths(full)
        val b = eighths(sparse)
        for (i in a.indices) {
            assertEquals("eighth $i", a[i], b[i], 0.02f)
        }
    }

    @Test fun anEmptyOrImpossibleFrameDoesNotCrash() {
        assertEquals(0, Histogram.fromLuma(ByteArray(0), 0, 0).sum())
        assertEquals(0, Histogram.fromLuma(ByteArray(10), 100, 100, stride = 1).sum() - 10)
        assertEquals(0f, Histogram.clippedHighlights(IntArray(Histogram.BUCKETS)), 0.0001f)
    }

    @Test fun middleGreyMarkerLandsInsideTheHistogram() {
        for (curve in allCurves) {
            val bucket = Histogram.middleGreyBucket(curve)
            assertTrue("$curve marked bucket $bucket", bucket in 1 until Histogram.BUCKETS - 1)
        }
        // LogC3 sits lowest of the set, so the marker must be able to move.
        assertTrue(
            Histogram.middleGreyBucket(LogCurves.Curve.LOGC3) <
                Histogram.middleGreyBucket(LogCurves.Curve.VLOG)
        )
    }
}
