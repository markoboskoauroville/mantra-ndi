package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 1 for the logging. The mechanism alone, no Android, no file.
 *
 * What could be true that would make these pass and the logging still be
 * broken? Plenty: the file might never open, the handler might never fire,
 * MediaStore might refuse. None of that is here, and that is the point of
 * the split. What IS here is everything that can be silently wrong while
 * looking right on a phone screen at arm's length: an hour off, a month
 * boundary, a column that drifts, a filename a computer will not accept.
 */
class TraceFormatTest {

    // Zagreb is +02:00 in September, Auroville is +05:30, and a half-hour
    // offset is the case that catches arithmetic written for whole hours.
    private val zagreb = 120
    private val auroville = 330

    // 2026-09-20 12:52:49.123 UTC
    private val t = 1_789_908_769_123L

    // --- the clock ---------------------------------------------------------

    @Test fun theEpochIsTheFirstOfJanuary() {
        val ymd = TraceFormat.civilFromDays(0)
        assertEquals(1970, ymd[0]); assertEquals(1, ymd[1]); assertEquals(1, ymd[2])
    }

    @Test fun aLeapDayExistsInAFourHundredYearLeapYear() {
        // 2000 is a leap year, 1900 is not. A calendar that gets this wrong
        // is off by one for a century at a time.
        val ymd = TraceFormat.civilFromDays(11016)  // 2000-02-29
        assertEquals(2000, ymd[0]); assertEquals(2, ymd[1]); assertEquals(29, ymd[2])
    }

    @Test fun aDayBeforeTheEpochGoesBackwardsNotForwards() {
        val ymd = TraceFormat.civilFromDays(-1)
        assertEquals(1969, ymd[0]); assertEquals(12, ymd[1]); assertEquals(31, ymd[2])
    }

    @Test fun theClockAgreesWithTheOffsetItWasGiven() {
        assertEquals("14:52:49.123", TraceFormat.clock(t, zagreb))
        assertEquals("18:22:49.123", TraceFormat.clock(t, auroville))
    }

    @Test fun anOffsetCanPushTheClockPastMidnightIntoTheNextDay() {
        // 22:52 UTC in Zagreb is 00:52 the following day. The day must roll,
        // not the clock wrap while the date stands still.
        val late = t + 10 * 3_600_000L
        assertEquals("00:52:49.123", TraceFormat.clock(late, zagreb))
        assertTrue(TraceFormat.stamp(late, zagreb).startsWith("2026-09-21"))
    }

    @Test fun aNegativeOffsetRollsTheDayBackwards() {
        val early = 1_789_865_569_123L  // 2026-09-20 00:52:49 UTC
        assertTrue(TraceFormat.stamp(early, -480).startsWith("2026-09-19"))
    }

    @Test fun midnightIsAllZeroesAndNotBlank() {
        assertEquals("00:00:00.000", TraceFormat.clock(1_789_862_400_000L, 0))
    }

    @Test fun theLastMillisecondOfADayIsStillThatDay() {
        val end = 1_789_862_400_000L - 1
        assertEquals("23:59:59.999", TraceFormat.clock(end, 0))
    }

    @Test fun theStampCarriesTheDateAndTheClockTogether() {
        assertEquals("2026-09-20 14:52:49.123", TraceFormat.stamp(t, zagreb))
    }

    // --- filenames ---------------------------------------------------------

    @Test fun aFilenameHasNoColonInIt() {
        // Legal on the phone, illegal the moment it is copied to a computer
        // or an SD card, and a report that cannot leave the phone is no use.
        val name = TraceFormat.traceName(t, zagreb)
        assertTrue(name.none { it == ':' })
    }

    @Test fun aFilenameSortsByTime() {
        val a = TraceFormat.traceName(t, zagreb)
        val b = TraceFormat.traceName(t + 3_600_000L, zagreb)
        assertTrue(a < b)
    }

    @Test fun theTwoKindsOfFileAreToldApartByTheirFirstWord() {
        assertTrue(TraceFormat.traceName(t, 0).startsWith("trace-"))
        assertTrue(TraceFormat.crashName(t, 0).startsWith("crash-"))
    }

    @Test fun theFileStampIsTheDateAndTimeWithNoPunctuationInTheTime() {
        assertEquals("2026-09-20-145249", TraceFormat.fileStamp(t, zagreb))
    }

    // --- the line ----------------------------------------------------------

    @Test fun everyLineStartsItsTagAtTheSameColumn() {
        val short = TraceFormat.line(t, zagreb, 5, TraceFormat.STEP, "x")
        val long = TraceFormat.line(t, zagreb, 987_654, TraceFormat.CONTROL, "y")
        assertEquals(short.indexOf(TraceFormat.STEP), long.indexOf(TraceFormat.CONTROL))
    }

    @Test fun everyLineStartsItsTextAtTheSameColumn() {
        val a = TraceFormat.line(t, zagreb, 5, TraceFormat.STEP, "alpha")
        val b = TraceFormat.line(t, zagreb, 5, TraceFormat.REFUSED, "beta")
        assertEquals(a.indexOf("alpha"), b.indexOf("beta"))
    }

    @Test fun elapsedReadsAsADurationNotAsAClock() {
        assertEquals("+0.412", TraceFormat.elapsed(412))
        assertEquals("+12.000", TraceFormat.elapsed(12_000))
        assertEquals("+0.000", TraceFormat.elapsed(0))
    }

    @Test fun elapsedSurvivesAClockThatWentBackwards() {
        // A monotonic source cannot, but a wall clock can, and a line reading
        // 4294967.295 is how that arrives if it is not handled.
        assertEquals("-0.500", TraceFormat.elapsed(-500))
    }

    @Test fun aLineCarriesTheTimeTheDurationTheTagAndTheText() {
        val l = TraceFormat.line(t, zagreb, 412, TraceFormat.STEP, "application created")
        assertTrue(l.startsWith("14:52:49.123"))
        assertTrue(l.contains("+0.412"))
        assertTrue(l.contains(TraceFormat.STEP))
        assertTrue(l.endsWith("application created"))
    }

    // --- controls and refusals ---------------------------------------------

    @Test fun aControlLineShowsWhatWasAskedForAndWhatTookEffect() {
        val l = TraceFormat.control("iso", "12800", "6400", "clamped to 50..6400")
        assertTrue(l.contains("requested 12800"))
        assertTrue(l.contains("applied 6400"))
        assertTrue(l.contains("clamped"))
    }

    @Test fun aControlThatWasHonouredCarriesNoNoteAndNoEmptyBrackets() {
        val l = TraceFormat.control("zoom", "2.0", "2.0", "")
        assertTrue(!l.contains("("))
    }

    @Test fun controlNamesLineUpWithEachOther() {
        val a = TraceFormat.control("iso", "100", "100", "")
        val b = TraceFormat.control("whitebalance", "5600", "5600", "")
        assertEquals(a.indexOf("requested"), b.indexOf("requested"))
    }

    @Test fun aRefusalCarriesItsReasonAndNotOnlyTheFactOfIt() {
        val l = TraceFormat.refused("repeating", "AE_MODE_OFF not accepted")
        assertTrue(l.contains("AE_MODE_OFF"))
    }

    // --- the report --------------------------------------------------------

    @Test fun aCrashReportHoldsTheHeaderTheStackAndTheTrace() {
        val r = TraceFormat.crashReport(
            listOf("version" to "66", "when" to "2026-09-20 14:52:49.123"),
            "java.lang.IllegalStateException: deliberate\n\tat com.mantraproductions.ndi.MainActivity",
            listOf("14:52:49.000  +0.100  STEP     started")
        )
        assertTrue(r.contains("version"))
        assertTrue(r.contains("IllegalStateException"))
        assertTrue(r.contains("STEP"))
    }

    @Test fun aCrashReportSaysHowManyTraceEntriesItCarries() {
        val r = TraceFormat.crashReport(emptyList(), "x", listOf("a", "b", "c"))
        assertTrue(r.contains("the last 3 trace entries"))
    }

    @Test fun aCrashReportWithNoTraceAtAllIsStillAReport() {
        val r = TraceFormat.crashReport(listOf("version" to "66"), "boom", emptyList())
        assertTrue(r.contains("boom"))
        assertTrue(r.contains("the last 0 trace entries"))
    }

    @Test fun theTailIsCountedInEntriesAndNotInLines() {
        // The fault this replaces: a report said "the last 20 trace lines"
        // over a block a reader counted thirty-seven of, because one FAULT
        // entry carried a stack seventeen lines long. The count is of
        // entries and the word has to agree with it.
        val r = TraceFormat.crashReport(
            emptyList(), "x",
            listOf("one line", "a fault\n\tat a\n\tat b\n\tat c")
        )
        assertTrue(r.contains("the last 2 trace entries"))
    }

    @Test fun headerKeysLineUpWithEachOther() {
        val r = TraceFormat.crashReport(listOf("v" to "66", "android" to "36"), "x", emptyList())
        val lines = r.split("\n")
        val a = lines.first { it.startsWith("v ") }
        val b = lines.first { it.startsWith("android") }
        assertEquals(a.indexOf("66"), b.indexOf("36"))
    }

    // --- the small things --------------------------------------------------

    @Test fun bytesAreWrittenTheWayAFileManagerWritesThem() {
        assertEquals("0 B", TraceFormat.bytes(0))
        assertEquals("999 B", TraceFormat.bytes(999))
        assertEquals("1.0 kB", TraceFormat.bytes(1_000))
        assertEquals("12.3 kB", TraceFormat.bytes(12_340))
        assertEquals("1.5 MB", TraceFormat.bytes(1_500_000))
    }

    @Test fun bytesNeverUseACommaAsTheDecimalMark() {
        // A phone set to Croatian formats 1,5 through the platform. This file
        // does its own arithmetic so the trace reads the same on every phone.
        assertTrue(TraceFormat.bytes(1_500_000).none { it == ',' })
    }

    @Test fun aTruncatedValueSaysThatItWasTruncated() {
        assertEquals("abcd\u2026", TraceFormat.truncate("abcdefgh", 5))
        assertEquals("abc", TraceFormat.truncate("abc", 5))
    }

    @Test fun truncatingToNothingDoesNotThrow() {
        assertEquals("\u2026", TraceFormat.truncate("abcdef", 0))
    }

    @Test fun paddingNeverShortensWhatItWasGiven() {
        assertEquals("abcdef", TraceFormat.padRight("abcdef", 3))
        assertEquals("abcdef", TraceFormat.padLeft("abcdef", 3))
    }
}
