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

    // --- recordings named after the camera that shot them -------------------

    @Test fun aRecordingCarriesTheCameraName() {
        assertEquals(
            "CAM_A_20260918_143000.mp4",
            Mechanism.recordingFileName("CAM A", "20260918_143000")
        )
    }

    @Test fun renamingTheCameraRenamesTheNextTake() {
        val first = Mechanism.recordingFileName("Wide", "20260918_143000")
        val second = Mechanism.recordingFileName("Tele", "20260918_143500")
        assertTrue(first.startsWith("Wide_"))
        assertTrue(second.startsWith("Tele_"))
    }

    @Test fun aFileNameNeverCarriesSomethingAFileSystemWillRefuse() {
        for (raw in listOf("CAM/A", "cam:1", "a*b?c", "  spaced  out  ")) {
            val name = Mechanism.fileSafeName(raw)
            assertFalse(name, name.any { it in "/\\:*?\"<>| " })
            assertTrue(name.isNotEmpty())
        }
    }

    @Test fun anUnusableNameFallsBackRatherThanProducingADotFile() {
        // An empty name has already become the default source name by the time
        // it reaches here, so the file is named after that rather than after a
        // second, different fallback. Two fallbacks for one condition is how
        // files end up named inconsistently.
        assertEquals("Mantra_Cam", Mechanism.fileSafeName(""))
        assertEquals("Mantra_Cam", Mechanism.fileSafeName("///"))
        // Only a name that survives sanitising and then strips to nothing
        // reaches the last resort.
        assertEquals("MantraNDI", Mechanism.fileSafeName("..."))
        assertTrue(Mechanism.fileSafeName("---").isNotEmpty())
    }

    @Test fun theExtensionIsNotDuplicatedIntoTheName() {
        val name = Mechanism.recordingFileName("CAM A", "20260918_143000")
        assertEquals(1, name.count { it == '.' })
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

    // --- faders that correct rather than set --------------------------------

    @Test fun theCentreOfACorrectionFaderChangesNothing() {
        val detected = 800.0
        assertEquals(detected, Mechanism.correctedFromAuto(50, 100, detected, 2.0), 0.001)
    }

    @Test fun eachEndIsExactlyTheStopsItPromises() {
        val detected = 800.0
        // Two stops up is four times the light, two down is a quarter.
        assertEquals(3200.0, Mechanism.correctedFromAuto(100, 100, detected, 2.0), 1.0)
        assertEquals(200.0, Mechanism.correctedFromAuto(0, 100, detected, 2.0), 1.0)
    }

    @Test fun correctionIsSymmetricAroundTheCentre() {
        val up = Mechanism.correctedFromAuto(75, 100, 400.0, 2.0)
        val down = Mechanism.correctedFromAuto(25, 100, 400.0, 2.0)
        assertEquals(400.0 * 400.0, up * down, 400.0)
    }

    @Test fun aDetectedValueOfNothingIsNotMultipliedIntoNonsense() {
        assertEquals(0.0, Mechanism.correctedFromAuto(90, 100, 0.0, 2.0), 0.0001)
    }

    @Test fun theCorrectionLabelReadsAsStops() {
        assertEquals("0", Mechanism.correctionLabel(50, 100, 2.0))
        assertEquals("+2.0", Mechanism.correctionLabel(100, 100, 2.0))
        assertEquals("-2.0", Mechanism.correctionLabel(0, 100, 2.0))
    }

    // --- a white balance range somebody would actually use -------------------

    @Test fun kelvinSpansTheWorkingRangeNotTheSensorRange() {
        assertEquals(Mechanism.KELVIN_WORKING_MIN, Mechanism.kelvinFromProgress(0, 100))
        assertEquals(Mechanism.KELVIN_WORKING_MAX, Mechanism.kelvinFromProgress(100, 100))
    }

    @Test fun kelvinRoundTripsThroughItsInverse() {
        for (k in Mechanism.KELVIN_WORKING_MIN..Mechanism.KELVIN_WORKING_MAX step 100) {
            val p = Mechanism.progressForKelvin(k, 100)
            assertTrue("at $k", Math.abs(Mechanism.kelvinFromProgress(p, 100) - k) <= 30)
        }
    }

    @Test fun kelvinOutsideTheWorkingRangeClampsToTheEnds() {
        assertEquals(0, Mechanism.progressForKelvin(1000, 100))
        assertEquals(100, Mechanism.progressForKelvin(20000, 100))
    }

    // --- audio gain ----------------------------------------------------------

    @Test fun theMiddleOfTheGainFaderIsUnity() {
        assertEquals(0.0, Mechanism.gainDbFromProgress(50, 100), 0.001)
        assertEquals(1f, Mechanism.gainFactor(0.0), 0.0001f)
    }

    @Test fun sixDbIsDoubleAndMinusSixIsHalf() {
        assertEquals(2f, Mechanism.gainFactor(6.02), 0.01f)
        assertEquals(0.5f, Mechanism.gainFactor(-6.02), 0.01f)
    }

    @Test fun gainClampsRatherThanWrapping() {
        // A wrapped sample turns a loud moment into a burst of noise, which is
        // far worse than the clipping it came from.
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())  // +16384, -16384
        Mechanism.applyGainPcm16(pcm, 4f)
        val first = ((pcm[1].toInt() shl 8) or (pcm[0].toInt() and 0xFF)).toShort()
        val second = ((pcm[3].toInt() shl 8) or (pcm[2].toInt() and 0xFF)).toShort()
        assertEquals(32767, first.toInt())
        assertEquals(-32768, second.toInt())
    }

    @Test fun unityGainLeavesEverySampleUntouched() {
        val original = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val copy = original.copyOf()
        Mechanism.applyGainPcm16(copy, 1f)
        assertTrue(original.contentEquals(copy))
    }

    @Test fun gainOnAnEmptyOrOddBufferDoesNotCrash() {
        Mechanism.applyGainPcm16(ByteArray(0), 2f)
        Mechanism.applyGainPcm16(ByteArray(1), 2f)
    }

    // --- the gain fader sits between two different readings ------------------

    @Test fun outputDiffersFromInputOnceThereIsGain() {
        // A quiet mic and six dB of gain: the top meter must not move and the
        // fader's meter must. Showing one number in both places is what made
        // the second meter furniture.
        val pcm = ByteArray(512)
        var i = 0
        while (i + 1 < pcm.size) {
            // A modest tone, well below clipping so gain has room to work.
            pcm[i] = 0x00
            pcm[i + 1] = 0x10
            i += 2
        }
        val input = Mechanism.rmsOfPcm16(pcm, stride = 2)
        val boosted = pcm.copyOf()
        Mechanism.applyGainPcm16(boosted, Mechanism.gainFactor(6.02))
        val output = Mechanism.rmsOfPcm16(boosted, stride = 2)

        assertEquals(2.0, (output / input).toDouble(), 0.05)
    }

    @Test fun cuttingGainLowersTheOutputAndLeavesTheInputAlone() {
        val pcm = ByteArray(512) { if (it % 2 == 0) 0x00 else 0x20 }
        val input = Mechanism.rmsOfPcm16(pcm, stride = 2)
        val cut = pcm.copyOf()
        Mechanism.applyGainPcm16(cut, Mechanism.gainFactor(-6.02))
        assertTrue(Mechanism.rmsOfPcm16(cut, stride = 2) < input)
        // The original buffer is untouched, which is what lets both be read.
        assertEquals(input, Mechanism.rmsOfPcm16(pcm, stride = 2), 0.0001f)
    }

    @Test fun atUnityTheTwoReadingsAgree() {
        val pcm = ByteArray(512) { if (it % 2 == 0) 0x00 else 0x18 }
        val copy = pcm.copyOf()
        Mechanism.applyGainPcm16(copy, 1f)
        assertEquals(
            Mechanism.rmsOfPcm16(pcm, stride = 2),
            Mechanism.rmsOfPcm16(copy, stride = 2),
            0.0001f
        )
    }

    // --- what a measurement means for the settings that depend on it --------

    // --- whether a take will fit, and whether it survived --------------------

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

    @Test fun focusTravelsOverTheWireBothWays() {
        val sent = CameraCommand(focus = 0.42f)
        assertEquals(0.42f, CameraCommand.parse(sent.toXml())!!.focus!!, 0.001f)
    }

    @Test fun aFocusPointTravelsAsAPlace() {
        val sent = CameraCommand(focusX = 0.31f, focusY = 0.77f)
        val got = CameraCommand.parse(sent.toXml())!!
        assertEquals(0.31f, got.focusX!!, 0.001f)
        assertEquals(0.77f, got.focusY!!, 0.001f)
    }

    @Test fun autoFocusAndLogCurveSurviveTheRoundTrip() {
        val sent = CameraCommand(focusAuto = true, logCurve = "SLOG3")
        val got = CameraCommand.parse(sent.toXml())!!
        assertEquals(true, got.focusAuto)
        assertEquals("SLOG3", got.logCurve)
    }

    @Test fun aCurveNameThatTravelsCanBeResolvedBack() {
        // The far camera looks the name up in the same enum, so every curve
        // this app offers has to survive being written as a name.
        for (curve in LogCurves.Curve.values()) {
            val xml = CameraCommand(logCurve = curve.name).toXml()
            val name = CameraCommand.parse(xml)!!.logCurve
            assertEquals(curve, LogCurves.Curve.values().first { it.name == name })
        }
    }

    @Test fun aFocusCommandCarriesNothingItWasNotGiven() {
        val got = CameraCommand.parse(CameraCommand(focus = 0.5f).toXml())!!
        assertEquals(null, got.focusX)
        assertEquals(null, got.focusAuto)
        assertEquals(null, got.logCurve)
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

    // --- the 16:9 box the whole interface is built around --------------------

    @Test
    fun pictureBoxFillsAWideScreenByItsHeight() {
        // A landscape phone: 2400 across, 1080 down. 16:9 of 1080 is 1920, so
        // the picture is limited by the height and 480 is left for the rails.
        val box = Mechanism.pictureBox(2400, 1080)
        assertEquals(1920, box[0])
        assertEquals(1080, box[1])
        assertEquals(480, box[2])
        assertEquals(0, box[3])
    }

    @Test
    fun pictureBoxFillsATallScreenByItsWidth() {
        // The same phone upright. Now the width limits it and the margin is
        // the band above and below where the rails go.
        val box = Mechanism.pictureBox(1080, 2400)
        assertEquals(1080, box[0])
        assertEquals(607, box[1])
        assertEquals(0, box[2])
        assertEquals(1793, box[3])
    }

    @Test
    fun pictureBoxIsNeverWiderThanWhatItWasGiven() {
        // The one thing that must never happen: a picture that runs under the
        // rails, which reads as a control that does not work.
        for (w in listOf(320, 720, 1080, 1440, 2400, 3840)) {
            for (h in listOf(240, 600, 1080, 2400)) {
                val box = Mechanism.pictureBox(w, h)
                assertTrue("$w x $h overflowed across", box[0] <= w)
                assertTrue("$w x $h overflowed down", box[1] <= h)
            }
        }
    }

    @Test
    fun pictureBoxRefusesNothing() {
        // Called once before the view has been laid out, every time.
        assertEquals(0, Mechanism.pictureBox(0, 0)[0])
        assertEquals(0, Mechanism.pictureBox(-10, 500)[1])
    }

    // --- the preview geometry, which has been wrong for six attempts ---------

    /**
     * The check none of the previous attempts was ever asked.
     *
     * Whatever the view is, whatever the buffer is, and whichever way the
     * picture has been turned, what ends up on the screen must have the
     * buffer's own shape. If it does not, the picture is stretched — and a
     * stretched picture is the single fault this app has shipped most often,
     * because it is invisible on a test pattern and obvious on a face.
     */
    @Test
    fun previewIsNeverStretched() {
        val views = listOf(
            1920 to 1080,   // landscape, 16:9
            1080 to 607,    // portrait, the band between the rails
            2400 to 1080,   // the whole screen, 20:9
            1080 to 2400,
            1000 to 1000    // square, which nothing is, but nothing should break
        )
        val buffers = listOf(
            1920 to 1080,
            1280 to 720,
            1440 to 1080,   // 4:3, which some lenses report
            3840 to 2160
        )
        for ((vw, vh) in views) {
            for ((bw, bh) in buffers) {
                for (rotation in listOf(0, 90, 180, 270)) {
                    for (fill in listOf(false, true)) {
                        val expected =
                            if (rotation == 90 || rotation == 270) bh.toDouble() / bw
                            else bw.toDouble() / bh
                        val actual = Mechanism.displayedAspect(vw, vh, bw, bh, rotation, fill)
                        assertEquals(
                            "view ${vw}x$vh buffer ${bw}x$bh rot $rotation fill $fill",
                            expected, actual, 0.0005
                        )
                    }
                }
            }
        }
    }

    @Test
    fun previewFitsInsideTheViewAndFillCoversIt() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val swap = rotation == 90 || rotation == 270
            val rotatedW = if (swap) 1080.0 else 1920.0
            val rotatedH = if (swap) 1920.0 else 1080.0

            val fit = Mechanism.previewFit(1920, 1080, 1440, 1080, rotation, fill = false)
            assertTrue("fit overflowed across", fit[0] * rotatedW <= 1920.0 + 0.5)
            assertTrue("fit overflowed down", fit[1] * rotatedH <= 1080.0 + 0.5)

            val fill = Mechanism.previewFit(1920, 1080, 1440, 1080, rotation, fill = true)
            assertTrue("fill left a gap across", fill[0] * rotatedW >= 1920.0 - 0.5)
            assertTrue("fill left a gap down", fill[1] * rotatedH >= 1080.0 - 0.5)
        }
    }

    @Test
    fun matchingAspectsNeedNoCorrectionAtAll() {
        // The ordinary case: a 16:9 buffer in the 16:9 box. Anything other than
        // 1,1 here is a transform inventing work for itself.
        val scale = Mechanism.previewFit(1920, 1080, 1920, 1080, 0)
        assertEquals(1.0, scale[0].toDouble(), 0.001)
        assertEquals(1.0, scale[1].toDouble(), 0.001)
    }

    @Test
    fun aQuarterTurnScalesBothWaysByTheSameAmount() {
        // A 16:9 picture turned into a 16:9 box is one uniform shrink. Two
        // different numbers here is the stretch, arriving as arithmetic.
        val scale = Mechanism.previewFit(1920, 1080, 1920, 1080, 90)
        assertEquals(scale[0].toDouble(), scale[1].toDouble(), 0.001)
    }

    @Test
    fun previewFitRefusesNothing() {
        // Called once before the view has been laid out, every single time.
        assertEquals(1f, Mechanism.previewFit(0, 0, 1920, 1080, 0)[0])
        assertEquals(1f, Mechanism.previewFit(1920, 1080, 0, 0, 90)[1])
    }

    /**
     * The angle the picture has to be turned through, which is the other half
     * of the bug and the half people argue about.
     */
    @Test
    fun backCameraIsUprightInLandscapeAndTurnedInPortrait() {
        // A Pixel's back sensor is mounted at 90 degrees.
        assertEquals(90, Mechanism.previewRotation(90, 0))     // phone upright
        assertEquals(0, Mechanism.previewRotation(90, 90))     // turned left
        assertEquals(270, Mechanism.previewRotation(90, 180))
        assertEquals(180, Mechanism.previewRotation(90, 270))  // turned right
    }

    @Test
    fun frontCameraTurnsTheOtherWay() {
        // The front sensor is mounted at 270 and the display rotation counts
        // against it rather than with it, which is the sign error that has
        // shipped a sideways selfie in every version so far.
        assertEquals(270, Mechanism.previewRotation(270, 0, frontFacing = true))
        assertEquals(0, Mechanism.previewRotation(270, 90, frontFacing = true))
        assertEquals(90, Mechanism.previewRotation(270, 180, frontFacing = true))
        assertEquals(180, Mechanism.previewRotation(270, 270, frontFacing = true))
    }

    @Test
    fun rotationIsAlwaysAQuarterTurnAndNeverNegative() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                for (front in listOf(false, true)) {
                    val r = Mechanism.previewRotation(sensor, display, front)
                    assertTrue("negative rotation $r", r >= 0)
                    assertTrue("not a quarter turn: $r", r % 90 == 0)
                    assertTrue("out of range $r", r < 360)
                }
            }
        }
    }
}
