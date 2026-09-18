package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 of the four: the mechanism, alone.
 *
 * No Android, no camera, no network. Every case below is written to fail if
 * the rule is wrong, not to confirm the happy path from four angles.
 */
class MechanismTest {

    // --- source names -------------------------------------------------------

    @Test fun blankNameFallsBackRatherThanAnnouncingNothing() {
        assertEquals("Mantra Cam", Mechanism.sanitizeSourceName("   "))
        assertEquals("Mantra Cam", Mechanism.sanitizeSourceName(""))
    }

    @Test fun charactersThatBreakMdnsAreStripped() {
        assertEquals("CAM A", Mechanism.sanitizeSourceName("CAM/A"))
        assertEquals("Stage Left", Mechanism.sanitizeSourceName("Stage<>Left"))
        assertEquals("cam.1_(wide)", Mechanism.sanitizeSourceName("cam.1_(wide)"))
    }

    @Test fun runsOfWhitespaceCollapse() {
        assertEquals("CAM A", Mechanism.sanitizeSourceName("CAM     A"))
    }

    @Test fun overlongNamesAreCutAndNotLeftRagged() {
        val name = Mechanism.sanitizeSourceName("x".repeat(80))
        assertEquals(Mechanism.MAX_NAME_LENGTH, name.length)
    }

    @Test fun clashIsDetectedInsideTheBracketsNdiAdvertises() {
        val existing = listOf("PIXEL7 (Mantra Cam A142)", "STUDIO (Wide)")
        assertTrue(Mechanism.nameClashes("Mantra Cam A142", existing))
        assertTrue(Mechanism.nameClashes("mantra cam a142", existing))
        assertFalse(Mechanism.nameClashes("Mantra Cam B", existing))
    }

    @Test fun clashHandlesSourcesWithNoBrackets() {
        assertTrue(Mechanism.nameClashes("Wide", listOf("Wide")))
        assertFalse(Mechanism.nameClashes("Wide", emptyList()))
    }

    // --- exposure -----------------------------------------------------------

    @Test fun shutterEndsLandOnTheLimits() {
        assertEquals(1_000L, Mechanism.shutterFromProgress(0, 200, 1_000L, 100_000_000L))
        assertEquals(100_000_000L, Mechanism.shutterFromProgress(200, 200, 1_000L, 100_000_000L))
    }

    @Test fun shutterProgressIsMonotonic() {
        var previous = -1L
        for (p in 0..200 step 10) {
            val value = Mechanism.shutterFromProgress(p, 200, 1_000L, 100_000_000L)
            assertTrue("fell at $p", value >= previous)
            previous = value
        }
    }

    @Test fun shutterOutOfRangeInputIsClamped() {
        assertEquals(1_000L, Mechanism.shutterFromProgress(-50, 200, 1_000L, 2_000L))
        assertEquals(2_000L, Mechanism.shutterFromProgress(9_999, 200, 1_000L, 2_000L))
    }

    @Test fun degenerateSensorRangeDoesNotDivideByZero() {
        assertEquals(5_000L, Mechanism.shutterFromProgress(100, 200, 5_000L, 5_000L))
        assertEquals(0, Mechanism.progressForShutter(5_000L, 200, 5_000L, 5_000L))
        assertEquals(5_000L, Mechanism.shutterFromProgress(100, 0, 5_000L, 9_000L))
    }

    @Test fun shutterDenominatorReadsAsOperatorsExpect() {
        assertEquals(50, Mechanism.shutterDenominator(20_000_000L))
        assertEquals(48, Mechanism.shutterDenominator(Mechanism.shutter180Ns(24)))
        assertEquals(0, Mechanism.shutterDenominator(0))
    }

    @Test fun oneEightyShutterMatchesFrameRate() {
        assertEquals(1_000_000_000L / 48, Mechanism.shutter180Ns(24))
        assertEquals(1_000_000_000L / 50, Mechanism.shutter180Ns(25))
        assertEquals(0L, Mechanism.shutter180Ns(0))
    }

    // The bug that froze the preview: a shutter longer than the frame.

    @Test fun shutterNeverExceedsOneFrameInterval() {
        // Sensor offers ten seconds; at 24fps nothing beyond 1/24 is usable.
        val (min, max) = Mechanism.shutterRangeForFps(24, 1_000L, 10_000_000_000L)
        assertEquals(1_000L, min)
        assertEquals(1_000_000_000L / 24, max)
    }

    @Test fun everyFrameRateGetsItsOwnCeiling() {
        assertEquals(1_000_000_000L / 25, Mechanism.shutterRangeForFps(25, 1_000L, 1_000_000_000L).second)
        assertEquals(1_000_000_000L / 50, Mechanism.shutterRangeForFps(50, 1_000L, 1_000_000_000L).second)
    }

    @Test fun aSlowSensorCeilingIsNotRaisedToTheFrameInterval() {
        // If the sensor cannot reach 1/24, the sensor wins.
        val (_, max) = Mechanism.shutterRangeForFps(24, 1_000L, 5_000_000L)
        assertEquals(5_000_000L, max)
    }

    @Test fun noFrameRateMeansNoCeiling() {
        val (_, max) = Mechanism.shutterRangeForFps(0, 1_000L, 9_000_000_000L)
        assertEquals(9_000_000_000L, max)
    }

    @Test fun oneHundredEightyDegreeShutterFitsInsideEveryFrameRateCeiling() {
        for (fps in intArrayOf(24, 25, 30, 50, 60)) {
            val (_, max) = Mechanism.shutterRangeForFps(fps, 1_000L, 10_000_000_000L)
            assertTrue("at $fps", Mechanism.shutter180Ns(fps) <= max)
        }
    }

    @Test fun frameDurationMatchesTheFrameRate() {
        assertEquals(1_000_000_000L / 24, Mechanism.frameDurationForFps(24))
        assertEquals(0L, Mechanism.frameDurationForFps(0))
    }

    // Stops, not linear: a fader that is useful across the whole range.

    @Test fun shutterTravelIsGeometricSoEachHalfIsAnEqualNumberOfStops() {
        val min = 1_000L
        val max = 1_000_000_000L / 24
        val quarter = Mechanism.shutterFromProgress(50, 200, min, max)
        val half = Mechanism.shutterFromProgress(100, 200, min, max)
        val threeQuarter = Mechanism.shutterFromProgress(150, 200, min, max)
        // Equal ratios between equal steps is what geometric means.
        val firstRatio = half.toDouble() / quarter
        val secondRatio = threeQuarter.toDouble() / half
        assertEquals(firstRatio, secondRatio, firstRatio * 0.05)
    }

    @Test fun shutterRoundTripsThroughItsOwnInverse() {
        val min = 1_000L
        val max = 1_000_000_000L / 25
        for (p in 0..200 step 25) {
            val ns = Mechanism.shutterFromProgress(p, 200, min, max)
            val back = Mechanism.progressForShutter(ns, 200, min, max)
            assertTrue("at $p came back $back", Math.abs(back - p) <= 1)
        }
    }

    @Test fun oneEightyShutterLandsInsideTheFaderTravel() {
        val (min, max) = Mechanism.shutterRangeForFps(24, 1_000L, 10_000_000_000L)
        val p = Mechanism.progressForShutter(Mechanism.shutter180Ns(24), 200, min, max)
        assertTrue("landed at $p", p in 1..199)
    }

    @Test fun longExposuresReadAsSecondsNotAsOneOverZero() {
        // 1/0 was what the fader showed before this existed.
        assertEquals("1.0s", Mechanism.formatShutter(1_000_000_000L))
        assertEquals("2.5s", Mechanism.formatShutter(2_500_000_000L))
        assertEquals("30s", Mechanism.formatShutter(30_000_000_000L))
    }

    @Test fun normalExposuresStillReadAsFractions() {
        assertEquals("1/50", Mechanism.formatShutter(20_000_000L))
        assertEquals("1/48", Mechanism.formatShutter(Mechanism.shutter180Ns(24)))
        assertEquals("1/1000", Mechanism.formatShutter(1_000_000L))
    }

    @Test fun noShutterValueNeverPrintsAsANumber() {
        assertEquals("--", Mechanism.formatShutter(0L))
        assertEquals("--", Mechanism.formatShutter(-1L))
    }

    @Test fun everyShutterInASensorRangeFormatsWithoutZeroDenominator() {
        for (p in 0..200) {
            val (lo, hi) = Mechanism.shutterRangeForFps(24, 1_000L, 60_000_000_000L)
            val ns = Mechanism.shutterFromProgress(p, 200, lo, hi)
            val text = Mechanism.formatShutter(ns)
            assertFalse("at $p got $text", text.endsWith("/0"))
        }
    }

    // --- white balance ------------------------------------------------------

    @Test fun warmLightBoostsBlueAndCoolLightBoostsRed() {
        val warm = Mechanism.kelvinToGains(2700)
        val cool = Mechanism.kelvinToGains(9000)
        assertTrue("warm blue ${warm[2]} vs red ${warm[0]}", warm[2] > warm[0])
        assertTrue("cool red ${cool[0]} vs blue ${cool[2]}", cool[0] > cool[2])
    }

    @Test fun gainsAreNormalisedSoTheSmallestIsOne() {
        for (k in intArrayOf(2000, 3200, 5600, 6500, 10000)) {
            val g = Mechanism.kelvinToGains(k)
            assertEquals("at ${k}K", 1.0f, minOf(g[0], g[1], g[2]), 0.001f)
        }
    }

    @Test fun gainsAreNeverZeroOrNegative() {
        for (k in Mechanism.KELVIN_MIN..Mechanism.KELVIN_MAX step 250) {
            for (g in Mechanism.kelvinToGains(k)) {
                assertTrue("at ${k}K got $g", g > 0f && g.isFinite())
            }
        }
    }

    @Test fun kelvinOutOfRangeClampsRatherThanExploding() {
        val low = Mechanism.kelvinToGains(-1000)
        val high = Mechanism.kelvinToGains(999_999)
        assertTrue(low.all { it.isFinite() })
        assertTrue(high.all { it.isFinite() })
    }

    @Test fun blueGainFallsMonotonicallyAsLightGetsCooler() {
        var previous = Float.MAX_VALUE
        for (k in 2000..6500 step 500) {
            val blue = Mechanism.kelvinToGains(k)[2]
            assertTrue("rose at ${k}K", blue <= previous + 0.001f)
            previous = blue
        }
    }

    // --- metering -----------------------------------------------------------

    @Test fun silenceReadsZeroAndFullScaleReadsOne() {
        assertEquals(0f, Mechanism.rmsToMeterFraction(0f), 0.0001f)
        assertEquals(1f, Mechanism.rmsToMeterFraction(1f), 0.0001f)
    }

    @Test fun meterIsDbScaledNotLinear() {
        // Half amplitude is about -6dB, which on a -54dB floor sits near 0.89,
        // nowhere near the 0.5 a linear meter would show.
        val half = Mechanism.rmsToMeterFraction(0.5f)
        assertTrue("half read $half", half > 0.85f && half < 0.92f)
    }

    @Test fun belowTheFloorIsPinnedToZeroNotNegative() {
        assertEquals(0f, Mechanism.rmsToMeterFraction(0.0000001f), 0.0001f)
        assertTrue(Mechanism.rmsToMeterFraction(0.001f) >= 0f)
    }

    @Test fun rmsOfSilentPcmIsZero() {
        assertEquals(0f, Mechanism.rmsOfPcm16(ByteArray(1024)), 0.0001f)
    }

    @Test fun rmsOfFullScaleToneApproachesOne() {
        // Alternating +32767 / -32768 as little endian pairs.
        val pcm = ByteArray(1024)
        var i = 0
        while (i + 1 < pcm.size) {
            pcm[i] = 0xFF.toByte()
            pcm[i + 1] = 0x7F
            i += 2
        }
        assertTrue(Mechanism.rmsOfPcm16(pcm, stride = 2) > 0.99f)
    }

    @Test fun emptyOrOddPcmDoesNotCrash() {
        assertEquals(0f, Mechanism.rmsOfPcm16(ByteArray(0)), 0.0001f)
        assertEquals(0f, Mechanism.rmsOfPcm16(ByteArray(1)), 0.0001f)
    }

    // --- the record counter, which has to fit inside a circle ---------------

    @Test fun underAMinuteShowsOnlyTheSeconds() {
        // No leading zero and no empty minute field, so the digits can be drawn
        // twice the size inside the same ring.
        assertEquals("0", Mechanism.recordLabel(0))
        assertEquals("7", Mechanism.recordLabel(7))
        assertEquals("45", Mechanism.recordLabel(45))
        assertEquals("59", Mechanism.recordLabel(59))
    }

    @Test fun minutesAppearOnlyOnceThereAreMinutes() {
        assertEquals("1:00", Mechanism.recordLabel(60))
        assertEquals("1:05", Mechanism.recordLabel(65))
        assertEquals("10:05", Mechanism.recordLabel(605))
        assertEquals("59:59", Mechanism.recordLabel(3599))
    }

    @Test fun hoursAppearOnlyOnceThereAreHours() {
        assertEquals("1:00:00", Mechanism.recordLabel(3600))
        assertEquals("2:03:04", Mechanism.recordLabel(7384))
    }

    @Test fun theLabelOnlyEverGrows() {
        // Each step up must be at least as long as the last, or the text would
        // jump size backwards mid take.
        var longest = 0
        for (s in 0L..7300L step 7) {
            val length = Mechanism.recordLabel(s).length
            assertTrue("shrank at $s", length >= longest)
            longest = length
        }
    }

    @Test fun negativeTimeIsTreatedAsZeroRatherThanPrintingAMinus() {
        assertEquals("0", Mechanism.recordLabel(-5))
    }

    // --- cycling the one fader ----------------------------------------------

    @Test fun cyclingVisitsEveryParameterAndComesBack() {
        var p = Mechanism.Param.ISO
        val seen = mutableListOf(p)
        repeat(Mechanism.Param.entries.size - 1) {
            p = p.next()
            seen.add(p)
        }
        assertEquals(Mechanism.Param.entries.size, seen.distinct().size)
        assertEquals(Mechanism.Param.ISO, p.next())
    }

    @Test fun anUnknownRememberedParameterFallsBackRatherThanCrashing() {
        assertEquals(Mechanism.Param.ISO, Mechanism.Param.fromName(null))
        assertEquals(Mechanism.Param.ISO, Mechanism.Param.fromName("NONSENSE"))
        assertEquals(Mechanism.Param.SHUTTER, Mechanism.Param.fromName("SHUTTER"))
    }

    // --- remote control protocol -------------------------------------------

    @Test fun commandSurvivesTheRoundTrip() {
        val sent = CameraCommand(iso = 800, shutterNs = 20_000_000L, exposureMode = "manual")
        val got = CameraCommand.parse(sent.toXml())!!
        assertEquals(800, got.iso)
        assertEquals(20_000_000L, got.shutterNs)
        assertEquals("manual", got.exposureMode)
    }

    @Test fun unsetFieldsStayNullSoACommandOnlyCarriesWhatChanged() {
        val got = CameraCommand.parse(CameraCommand(record = "start").toXml())!!
        assertEquals("start", got.record)
        assertEquals(null, got.iso)
        assertEquals(null, got.whiteBalanceKelvin)
    }

    @Test fun foreignMetadataIsRejectedRatherThanMisread() {
        assertEquals(null, CameraCommand.parse("<ndi_something value=\"1\"/>"))
        assertEquals(null, CameraCommand.parse(""))
    }

    @Test fun cameraStateRoundTripsIncludingItsName() {
        val sent = CameraState(
            isoMin = 50, isoMax = 3200, shutterMinNs = 1_000, shutterMaxNs = 100_000_000,
            recording = true, manualSupported = true, whiteBalanceSupported = true,
            whiteBalanceKelvin = 5600, cameraName = "Mantra Cam A142"
        )
        val got = CameraState.parse(sent.toXml())!!
        assertEquals(50, got.isoMin)
        assertEquals(3200, got.isoMax)
        assertTrue(got.recording)
        assertEquals(5600, got.whiteBalanceKelvin)
        assertEquals("Mantra Cam A142", got.cameraName)
    }

    @Test fun absentValuesComeBackAsNullNotAsMinusOne() {
        val got = CameraState.parse(CameraState().toXml())!!
        assertEquals(null, got.iso)
        assertEquals(null, got.whiteBalanceKelvin)
        assertFalse(got.recording)
    }
}
