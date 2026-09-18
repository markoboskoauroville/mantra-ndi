package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for timecode.
 *
 * Worth more care than most, because timecode that is wrong by one frame looks
 * exactly like timecode that is right, and nobody finds out until the sound no
 * longer lines up in the edit.
 */
class TimecodeTest {

    private fun tc(h: Int, m: Int, s: Int, f: Int, rate: Timecode.Rate) =
        Timecode(h, m, s, f, rate)

    // --- counting, without drop frame ----------------------------------------

    @Test fun framesCountUpThroughEverySecondAndMinute() {
        val rate = Timecode.Rate.FPS_25
        assertEquals(tc(0, 0, 0, 0, rate), Timecode.fromFrames(0, rate))
        assertEquals(tc(0, 0, 0, 24, rate), Timecode.fromFrames(24, rate))
        assertEquals(tc(0, 0, 1, 0, rate), Timecode.fromFrames(25, rate))
        assertEquals(tc(0, 1, 0, 0, rate), Timecode.fromFrames(25 * 60, rate))
        assertEquals(tc(1, 0, 0, 0, rate), Timecode.fromFrames(25 * 3600, rate))
    }

    @Test fun everyRateRoundTripsThroughItsFrameCount() {
        for (rate in Timecode.Rate.values()) {
            var frame = 0L
            while (frame < 60L * 60 * rate.nominal * 2) {
                val back = Timecode.toFrames(Timecode.fromFrames(frame, rate))
                assertEquals("$rate at $frame", frame, back)
                frame += rate.nominal * 37L + 13L
            }
        }
    }

    @Test fun aDayWrapsRatherThanRunningToTwentyFive() {
        val rate = Timecode.Rate.FPS_25
        assertEquals(tc(0, 0, 0, 0, rate), Timecode.fromFrames(25L * 3600 * 24, rate))
    }

    // --- drop frame, which is the one that catches people ---------------------

    @Test fun dropFrameSkipsTheFirstTwoLabelsOfAMinute() {
        val rate = Timecode.Rate.FPS_29_97_DF
        // The last frame of the first minute, then the next one.
        val lastOfMinute = Timecode.toFrames(tc(0, 0, 59, 29, rate))
        assertEquals(tc(0, 1, 0, 2, rate), Timecode.fromFrames(lastOfMinute + 1, rate))
    }

    @Test fun dropFrameKeepsTheLabelsOnEveryTenthMinute() {
        val rate = Timecode.Rate.FPS_29_97_DF
        val lastOfNine = Timecode.toFrames(tc(0, 9, 59, 29, rate))
        // Minute ten drops nothing, so this one really is frame zero.
        assertEquals(tc(0, 10, 0, 0, rate), Timecode.fromFrames(lastOfNine + 1, rate))
    }

    @Test fun tenMinutesOfDropFrameIsSeventeenThousandNineHundredAndEightyTwoFrames() {
        val rate = Timecode.Rate.FPS_29_97_DF
        assertEquals(17982L, Timecode.toFrames(tc(0, 10, 0, 0, rate)))
    }

    @Test fun anHourOfDropFrameStaysWithTwoFramesOfTheWallClock() {
        // The whole purpose of drop frame. An hour of labels must be an hour
        // of real time, not the 3.6 seconds longer a plain count would be.
        val rate = Timecode.Rate.FPS_29_97_DF
        val frames = Timecode.toFrames(tc(1, 0, 0, 0, rate))
        val seconds = frames / rate.fps
        assertEquals(3600.0, seconds, 2.0 / rate.fps)
    }

    @Test fun nonDropAtTwentyNineNineSevenDriftsAndThatIsCorrect() {
        // Not a bug: non drop labels every frame in order, so after an hour of
        // labels rather less than an hour has passed. Worth pinning down so
        // nobody later mistakes it for one.
        val rate = Timecode.Rate.FPS_29_97
        val seconds = Timecode.toFrames(tc(1, 0, 0, 0, rate)) / rate.fps
        assertTrue("drifted $seconds", seconds < 3600.0 - 3.0)
    }

    @Test fun dropFrameRoundTripsAcrossAMinuteBoundaryEitherWay() {
        val rate = Timecode.Rate.FPS_29_97_DF
        for (frame in 1790L..1810L) {
            assertEquals(frame, Timecode.toFrames(Timecode.fromFrames(frame, rate)))
        }
    }

    // --- how it is written ----------------------------------------------------

    @Test fun theSeparatorSaysWhichKindItIs() {
        assertEquals("01:02:03:04", tc(1, 2, 3, 4, Timecode.Rate.FPS_25).toString())
        assertEquals("01:02:03;04", tc(1, 2, 3, 4, Timecode.Rate.FPS_29_97_DF).toString())
    }

    @Test fun everyFieldIsPaddedSoTheDigitsDoNotMoveAbout() {
        assertEquals("00:00:00:00", tc(0, 0, 0, 0, Timecode.Rate.FPS_25).toString())
    }

    @Test fun eitherSeparatorIsAccepted() {
        assertNotNull(Timecode.parse("10:20:30:12", Timecode.Rate.FPS_25))
        assertNotNull(Timecode.parse("10:20:30;12", Timecode.Rate.FPS_29_97_DF))
    }

    @Test fun somethingImpossibleIsRefusedRatherThanRolledOver() {
        assertNull(Timecode.parse("25:00:00:00", Timecode.Rate.FPS_25))
        assertNull(Timecode.parse("00:60:00:00", Timecode.Rate.FPS_25))
        assertNull(Timecode.parse("00:00:00:25", Timecode.Rate.FPS_25))
        assertNull(Timecode.parse("nonsense", Timecode.Rate.FPS_25))
        assertNull(Timecode.parse("01:02:03", Timecode.Rate.FPS_25))
    }

    // --- the clock that keeps running between broadcasts ---------------------

    @Test fun theClockAdvancesBetweenReadings() {
        val clock = TimecodeClock()
        val rate = Timecode.Rate.FPS_25
        clock.jam(tc(10, 0, 0, 0, rate), atNanos = 1_000_000_000L)

        // Two seconds later, without another broadcast.
        val later = clock.now(3_000_000_000L)
        assertEquals(tc(10, 0, 2, 0, rate), later)
    }

    @Test fun aFractionOfASecondLandsOnTheRightFrame() {
        val clock = TimecodeClock()
        val rate = Timecode.Rate.FPS_25
        clock.jam(tc(0, 0, 0, 0, rate), atNanos = 0L)
        // 400 milliseconds at 25 is ten frames.
        assertEquals(tc(0, 0, 0, 10, rate), clock.now(400_000_000L))
    }

    @Test fun nothingIsReportedBeforeADeviceHasBeenHeard() {
        val clock = TimecodeClock()
        assertNull(clock.now(1_000_000_000L))
        assertTrue(clock.secondsSinceHeard(1_000_000_000L) > 1000)
    }

    @Test fun theClockKnowsHowLongSinceItLastHeardAnything() {
        val clock = TimecodeClock()
        clock.jam(tc(1, 0, 0, 0, Timecode.Rate.FPS_25), atNanos = 1_000_000_000L)
        assertEquals(5.0, clock.secondsSinceHeard(6_000_000_000L), 0.001)
    }

    @Test fun clearingStopsItReportingAnythingAtAll() {
        val clock = TimecodeClock()
        clock.jam(tc(1, 0, 0, 0, Timecode.Rate.FPS_25), atNanos = 0L)
        clock.clear()
        assertNull(clock.now(1_000_000_000L))
    }

    @Test fun anHourOfFreeRunningStaysOnTheFrameItShould() {
        // The clock is only as good as its arithmetic over a long take.
        val clock = TimecodeClock()
        val rate = Timecode.Rate.FPS_24
        clock.jam(tc(0, 0, 0, 0, rate), atNanos = 0L)
        val anHour = clock.now(3_600_000_000_000L)
        assertEquals(tc(1, 0, 0, 0, rate), anHour)
    }

    @Test fun aFrameRateIsChosenSensiblyForACameraRate() {
        assertEquals(Timecode.Rate.FPS_24, Timecode.Rate.nearest(24))
        assertEquals(Timecode.Rate.FPS_25, Timecode.Rate.nearest(25))
        // Fifty is counted at twenty five, which is what every recorder does.
        assertEquals(Timecode.Rate.FPS_25, Timecode.Rate.nearest(50))
        assertEquals(Timecode.Rate.FPS_30, Timecode.Rate.nearest(60))
    }
}
