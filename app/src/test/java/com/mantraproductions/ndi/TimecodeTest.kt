package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for timecode.
 *
 * Worth more here than almost anywhere else in this app: a timecode error is
 * invisible on set and only appears in the edit, by which point every clip of
 * the day is out of sync with every other. None of these cases can be caught
 * by looking at a screen.
 */
class TimecodeTest {

    @Test fun aTimecodeReadsTheWayAnEditorWritesIt() {
        assertEquals("01:23:45:12", Timecode(1, 23, 45, 12, 25.0).toString())
    }

    @Test fun dropFrameIsWrittenWithASemicolonBecauseThatIsWhatItMeans() {
        assertEquals("01:00:00;02", Timecode(1, 0, 0, 2, 29.97, true).toString())
    }

    @Test fun countingAndFormattingAreExactInverses() {
        for (fps in listOf(24.0, 25.0, 30.0, 50.0)) {
            val rate = Timecode.nominal(fps)
            for (count in listOf(0L, 1L, rate.toLong(), 3600L * rate, 86399L * rate)) {
                val tc = Timecode.fromFrameCount(count, fps, false)
                assertEquals("at $count on $fps", count, Timecode.toFrameCount(tc))
            }
        }
    }

    @Test fun oneSecondAtTwentyFiveIsTwentyFiveFrames() {
        val tc = Timecode.fromFrameCount(25, 25.0, false)
        assertEquals("00:00:01:00", tc.toString())
    }

    @Test fun theLastFrameOfASecondIsNotTheNextSecond() {
        assertEquals("00:00:00:24", Timecode.fromFrameCount(24, 25.0, false).toString())
    }

    @Test fun midnightWrapsRatherThanRunningToTwentyFive() {
        val perDay = 25L * 86400
        assertEquals("00:00:00:00", Timecode.fromFrameCount(perDay, 25.0, false).toString())
    }

    // --- drop frame, which is the part that catches people --------------------

    @Test fun dropFrameSkipsTwoNumbersAtTheTopOfAMinute() {
        // The whole trick: 00:00:59:29 is followed by 00:01:00:02, not :00.
        val before = Timecode(0, 0, 59, 29, 29.97, true)
        val after = Timecode.fromFrameCount(Timecode.toFrameCount(before) + 1, 29.97, true)
        assertEquals("00:01:00;02", after.toString())
    }

    @Test fun dropFrameKeepsTheTenthMinuteWhole() {
        // Every tenth minute drops nothing, which is what keeps the count
        // matching a real clock over an hour.
        val before = Timecode(0, 9, 59, 29, 29.97, true)
        val after = Timecode.fromFrameCount(Timecode.toFrameCount(before) + 1, 29.97, true)
        assertEquals("00:10:00;00", after.toString())
    }

    @Test fun dropFrameCountingAndFormattingStillInvert() {
        var count = 0L
        while (count < 30L * 60 * 12) {
            val tc = Timecode.fromFrameCount(count, 29.97, true)
            assertEquals("at $count", count, Timecode.toFrameCount(tc))
            count += 7
        }
    }

    // --- running forward between packets -------------------------------------

    @Test fun aSecondOfWallClockIsASecondOfTimecode() {
        val start = Timecode(10, 0, 0, 0, 25.0)
        assertEquals("10:00:01:00", start.advancedBy(1000).toString())
    }

    @Test fun twentyThreeNineSixIsNotTwentyFour() {
        // Treating 23.976 as 24 drifts about three frames a minute, which is a
        // second and a half across a shooting day.
        val start = Timecode(0, 0, 0, 0, 23.976)
        val afterAMinute = start.advancedBy(60_000)
        val asIfTwentyFour = Timecode(0, 0, 0, 0, 24.0).advancedBy(60_000)
        assertTrue(
            "$afterAMinute vs $asIfTwentyFour",
            Timecode.toFrameCount(afterAMinute) < Timecode.toFrameCount(asIfTwentyFour)
        )
    }

    @Test fun advancingByNothingChangesNothing() {
        val start = Timecode(4, 5, 6, 7, 25.0)
        assertEquals(start.toString(), start.advancedBy(0).toString())
    }

    // --- reading what a generator actually broadcasts -------------------------

    @Test fun aPlainTimecodePayloadIsRead() {
        // rate index 2 is 25fps, then hours, minutes, seconds, frames.
        val data = byteArrayOf(0x02, 10, 30, 15, 12)
        val reading = TentacleParser.parse(data)
        assertNotNull(reading)
        assertEquals("10:30:15:12", reading!!.timecode.toString())
        assertEquals(25.0, reading.timecode.fps, 0.001)
    }

    @Test fun aDropFrameRateIndexProducesADropFrameTimecode() {
        val data = byteArrayOf(0x04, 1, 0, 0, 2)
        val reading = TentacleParser.parse(data)
        assertNotNull(reading)
        assertTrue(reading!!.timecode.dropFrame)
        assertEquals(29.97, reading.timecode.fps, 0.01)
    }

    @Test fun nonsenseIsRefusedRatherThanTurnedIntoATime() {
        // A timecode invented from a payload nobody can read is worse than
        // none, because it is only found to be wrong in the edit.
        assertNull(TentacleParser.parse(byteArrayOf()))
        assertNull(TentacleParser.parse(byteArrayOf(99, 99, 99, 99, 99)))
    }

    @Test fun allZeroesIsNotReportedAsMidnight() {
        // An empty buffer looks exactly like 00:00:00:00, so it is refused.
        assertNull(TentacleParser.parse(ByteArray(8)))
    }

    @Test fun impossibleClockValuesAreRejected() {
        assertNull(TentacleParser.parse(byteArrayOf(0x02, 25, 0, 0, 0)))
        assertNull(TentacleParser.parse(byteArrayOf(0x02, 0, 60, 0, 0)))
        assertNull(TentacleParser.parse(byteArrayOf(0x02, 0, 0, 60, 0)))
    }

    @Test fun aFrameNumberBeyondTheRateIsRejected() {
        // 30 frames at 25fps is not a timecode, it is a misread payload.
        assertNull(TentacleParser.parse(byteArrayOf(0x02, 1, 0, 0, 30)))
    }

    @Test fun everyReadingSaysWhichLayoutProducedIt() {
        val reading = TentacleParser.parse(byteArrayOf(0x02, 10, 30, 15, 12))
        assertNotNull(reading!!.layout)
        assertTrue(reading.layout.isNotEmpty())
        assertTrue(reading.raw.contains("02"))
    }

    @Test fun theDevicesWorthListeningToAreRecognisedByName() {
        assertTrue(TentacleParser.looksLikeTentacle("Tentacle Sync E"))
        assertTrue(TentacleParser.looksLikeTentacle("TRACK E 1234"))
        assertTrue(TentacleParser.looksLikeTentacle("my timebar"))
        assertFalse(TentacleParser.looksLikeTentacle("Someone's Earbuds"))
        assertFalse(TentacleParser.looksLikeTentacle(null))
    }

    @Test fun rawBytesAreKeptSoAnUnreadableDeviceCanBeLookedAt() {
        assertEquals("0A FF 10", TentacleParser.hex(byteArrayOf(0x0A, 0xFF.toByte(), 0x10)))
    }
}
