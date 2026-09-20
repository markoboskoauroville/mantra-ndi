package com.mantraproductions.ndi

/**
 * The shape of every line the app writes about itself.
 *
 * This file imports nothing from Android on purpose, so the whole of it runs
 * on a desk in a second. The previous build shipped ten versions with no
 * readable trace, and every one of those versions was a guess; the way that
 * does not happen again is that the part of the trace which can be wrong
 * (the clock, the columns, the filenames) is arithmetic, and arithmetic can
 * be held to account by a test.
 *
 * Two rules are baked in here rather than left to the caller.
 *
 * No java.util.Formatter and no String.format anywhere below. Those read the
 * default locale, so a phone set to Croatian writes 1,5 MB and +0,412 while
 * the desk that tested it writes 1.5 MB and +0.412. A trace whose numbers
 * change shape depending on the phone's language is a trace that cannot be
 * grepped. Everything here is integer arithmetic and padding.
 *
 * Fixed column widths, because the file is read on a phone screen. A trace
 * whose columns do not line up is read as prose, and prose is not scanned.
 */
object TraceFormat {

    /** Tags. Kept short and few, because a tag nobody can predict is a tag nobody greps for. */
    const val STEP = "STEP"        // a step of startup or shutdown
    const val STATE = "STATE"      // something the app now believes
    const val CONTROL = "CONTROL"  // an operator moved something, with its values
    const val REFUSED = "REFUSED"  // the hardware or the system said no, with the reason
    const val FAULT = "FAULT"      // a throwable that did not kill the process
    const val CRASH = "CRASH"      // a throwable that did

    private const val TAG_WIDTH = 7
    private const val ELAPSED_WIDTH = 9

    // --- the clock ---------------------------------------------------------

    /**
     * Year, month, day for a count of days since 1970-01-01, by Howard
     * Hinnant's civil-from-days. Used instead of a calendar class because a
     * calendar class needs a timezone database, and the offset is the only
     * part of a timezone a log line actually needs.
     */
    fun civilFromDays(epochDays: Long): IntArray {
        val z = epochDays + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return intArrayOf((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if (a % b != 0L && ((a xor b) < 0)) q--
        return q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    /** `HH:MM:SS.mmm` in local time. What every trace line carries. */
    fun clock(epochMillis: Long, offsetMinutes: Int): String {
        val local = epochMillis + offsetMinutes * 60_000L
        val ms = floorMod(local, 86_400_000L)
        val h = (ms / 3_600_000L).toInt()
        val m = ((ms / 60_000L) % 60).toInt()
        val s = ((ms / 1_000L) % 60).toInt()
        val milli = (ms % 1_000L).toInt()
        return pad(h, 2) + ":" + pad(m, 2) + ":" + pad(s, 2) + "." + pad(milli, 3)
    }

    /** `YYYY-MM-DD HH:MM:SS.mmm` in local time. The header of a crash report. */
    fun stamp(epochMillis: Long, offsetMinutes: Int): String {
        val local = epochMillis + offsetMinutes * 60_000L
        val ymd = civilFromDays(floorDiv(local, 86_400_000L))
        return pad(ymd[0], 4) + "-" + pad(ymd[1], 2) + "-" + pad(ymd[2], 2) +
            " " + clock(epochMillis, offsetMinutes)
    }

    /**
     * `YYYY-MM-DD-HHMMSS`, for a filename. No colons: a colon is legal on the
     * phone's own storage and illegal the moment the file is copied to a
     * Windows machine or an SD card, and a file that cannot be copied off the
     * phone defeats the point of writing it.
     */
    fun fileStamp(epochMillis: Long, offsetMinutes: Int): String {
        val local = epochMillis + offsetMinutes * 60_000L
        val ymd = civilFromDays(floorDiv(local, 86_400_000L))
        val t = clock(epochMillis, offsetMinutes)
        return pad(ymd[0], 4) + "-" + pad(ymd[1], 2) + "-" + pad(ymd[2], 2) +
            "-" + t.substring(0, 2) + t.substring(3, 5) + t.substring(6, 8)
    }

    fun traceName(epochMillis: Long, offsetMinutes: Int): String =
        "trace-" + fileStamp(epochMillis, offsetMinutes) + ".txt"

    fun crashName(epochMillis: Long, offsetMinutes: Int): String =
        "crash-" + fileStamp(epochMillis, offsetMinutes) + ".txt"

    // --- the line ----------------------------------------------------------

    /**
     * One trace line. The wall clock says when, the elapsed figure says how
     * long after the process started, and the second one is the number that
     * answers most questions: which step was slow, and what happened between
     * two things that look simultaneous on a clock reading in milliseconds.
     */
    fun line(epochMillis: Long, offsetMinutes: Int, sinceStartMs: Long, tag: String, text: String): String =
        clock(epochMillis, offsetMinutes) + "  " +
            padLeft(elapsed(sinceStartMs), ELAPSED_WIDTH) + "  " +
            padRight(tag, TAG_WIDTH) + "  " + text

    /** Seconds since start to three places, signed, so it never reads as a clock. */
    fun elapsed(ms: Long): String {
        val sign = if (ms < 0) "-" else "+"
        val abs = if (ms < 0) -ms else ms
        return sign + (abs / 1000) + "." + pad((abs % 1000).toInt(), 3)
    }

    /**
     * A control change, with what was asked for and what actually took effect.
     * Both, always: the whole reason white balance and rotation cost six
     * attempts in the last build is that nobody could see the difference
     * between a value being sent and a value being honoured.
     */
    fun control(name: String, requested: String, applied: String, note: String): String {
        val head = padRight(name, 12) + " requested " + requested + "  applied " + applied
        return if (note.isEmpty()) head else "$head  ($note)"
    }

    /** A request the hardware or the system turned down, and why it said it did. */
    fun refused(request: String, reason: String): String =
        padRight(request, 12) + " " + reason

    // --- the crash report --------------------------------------------------

    /**
     * The whole text of a crash file. The stack arrives already turned into a
     * string by the caller, which is what keeps this function pure and
     * testable; the caller's only job is printStackTrace into a writer.
     *
     * The tail of the trace is included in the report itself. A crash file
     * that only holds a stack tells you where the app died and nothing about
     * what it was doing, and what it was doing is usually the answer.
     */
    fun crashReport(header: List<Pair<String, String>>, stack: String, tail: List<String>): String {
        val out = StringBuilder()
        out.append("MANTRA NDI CRASH REPORT\n\n")
        val width = header.maxOfOrNull { it.first.length } ?: 0
        for ((k, v) in header) out.append(padRight(k, width)).append("  ").append(v).append('\n')
        out.append("\n--- what killed it ").append("-".repeat(40)).append("\n\n")
        out.append(stack.trimEnd()).append('\n')
        // Entries, not lines. One entry can be a stack seventeen lines long,
        // and a count that disagrees with what the eye counts is a number
        // nobody trusts afterwards, including the ones that are right.
        out.append("\n--- the last ").append(tail.size).append(" trace entries ")
            .append("-".repeat(26)).append("\n\n")
        for (l in tail) out.append(l).append('\n')
        return out.toString()
    }

    // --- small things everything uses --------------------------------------

    /** Kilobytes and megabytes on 1000, not 1024, because that is what a file manager shows. */
    fun bytes(n: Long): String = when {
        n < 1_000L -> "$n B"
        n < 1_000_000L -> oneDecimal(n, 1_000L) + " kB"
        n < 1_000_000_000L -> oneDecimal(n, 1_000_000L) + " MB"
        else -> oneDecimal(n, 1_000_000_000L) + " GB"
    }

    private fun oneDecimal(n: Long, unit: Long): String {
        val tenths = (n * 10 + unit / 2) / unit
        return (tenths / 10).toString() + "." + (tenths % 10)
    }

    /**
     * A long value cut to fit, with the cut marked. A silently truncated value
     * in a log is worse than a missing one, because it reads as the whole
     * thing.
     */
    fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, if (max < 1) 0 else max - 1) + "\u2026"

    fun padRight(s: String, n: Int): String =
        if (s.length >= n) s else s + " ".repeat(n - s.length)

    fun padLeft(s: String, n: Int): String =
        if (s.length >= n) s else " ".repeat(n - s.length) + s

    private fun pad(v: Int, n: Int): String {
        val s = v.toString()
        return if (s.length >= n) s else "0".repeat(n - s.length) + s
    }
}
