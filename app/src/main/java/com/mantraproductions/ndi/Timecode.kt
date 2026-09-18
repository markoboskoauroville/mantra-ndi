package com.mantraproductions.ndi

/**
 * SMPTE timecode, and the arithmetic it actually needs.
 *
 * Nothing here touches Android or Bluetooth, so every rule below is tested on
 * a desk. Which matters more than usual: timecode errors are invisible on set
 * and only surface in the edit, when the sync is already wrong across every
 * clip of the day.
 *
 * Drop frame is the part that catches people. At 29.97 the clock runs slower
 * than wall time, so two frame numbers are skipped at the start of every
 * minute except every tenth, which keeps the count matching a real clock over
 * an hour. It is a counting trick, not a dropped picture, and getting it wrong
 * puts the whole day out by seconds.
 */
data class Timecode(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val frames: Int,
    val fps: Double,
    val dropFrame: Boolean = false
) {
    /** The conventional reading: semicolon for drop frame, colon for the rest. */
    override fun toString(): String {
        val separator = if (dropFrame) ';' else ':'
        return "%02d:%02d:%02d%c%02d".format(hours, minutes, seconds, separator, frames)
    }

    companion object {

        /** The rates a Tentacle will produce, and nothing else. */
        val RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)

        /** Whole frames per second for counting: 23.976 counts as 24. */
        fun nominal(fps: Double): Int = Math.round(fps).toInt().coerceAtLeast(1)

        /**
         * A frame count from midnight, which is the only sane form to do
         * arithmetic in. Everything else converts to this and back.
         */
        fun fromFrameCount(count: Long, fps: Double, dropFrame: Boolean): Timecode {
            val rate = nominal(fps)
            var f = count.coerceAtLeast(0)

            if (dropFrame && (rate == 30 || rate == 60)) {
                // Two frames per minute, ten per ten minutes, put back.
                val dropPerMinute = if (rate == 30) 2L else 4L
                val framesPerTenMinutes = rate * 600L - dropPerMinute * 9L
                val framesPerMinute = rate * 60L - dropPerMinute

                val tenMinuteBlocks = f / framesPerTenMinutes
                var remainder = f % framesPerTenMinutes
                // The first minute of each block drops nothing.
                val firstMinute = rate * 60L
                remainder = if (remainder < firstMinute) {
                    remainder
                } else {
                    remainder - firstMinute
                }
                val minutesInBlock = if (f % framesPerTenMinutes < firstMinute) 0L
                    else remainder / framesPerMinute + 1
                val leftover = if (f % framesPerTenMinutes < firstMinute) {
                    f % framesPerTenMinutes
                } else {
                    remainder % framesPerMinute
                }
                f = (tenMinuteBlocks * 10 + minutesInBlock) * rate * 60L + leftover +
                    (tenMinuteBlocks * 10 + minutesInBlock) * 0L
                // Rebuild directly from the pieces rather than from f.
                val totalMinutes = tenMinuteBlocks * 10 + minutesInBlock
                val h = ((totalMinutes / 60) % 24).toInt()
                val m = (totalMinutes % 60).toInt()
                val s = (leftover / rate).toInt()
                val fr = (leftover % rate).toInt()
                return Timecode(h, m, s, fr, fps, true)
            }

            val framesPerDay = rate * 86400L
            f %= framesPerDay
            val h = (f / (rate * 3600L)).toInt()
            var rest = f % (rate * 3600L)
            val m = (rest / (rate * 60L)).toInt()
            rest %= rate * 60L
            val s = (rest / rate).toInt()
            val fr = (rest % rate).toInt()
            return Timecode(h, m, s, fr, fps, false)
        }

        /** The inverse, for arithmetic and for comparing two codes. */
        fun toFrameCount(tc: Timecode): Long {
            val rate = nominal(tc.fps).toLong()
            val totalMinutes = tc.hours * 60L + tc.minutes
            var count = ((tc.hours * 3600L + tc.minutes * 60L + tc.seconds) * rate) + tc.frames
            if (tc.dropFrame && (rate == 30L || rate == 60L)) {
                val dropPerMinute = if (rate == 30L) 2L else 4L
                count -= dropPerMinute * (totalMinutes - totalMinutes / 10)
            }
            return count
        }
    }

    /**
     * This timecode, moved on by a length of real time.
     *
     * The rate matters: at 23.976 a second of wall clock is not twenty four
     * frames, and pretending it is drifts by about three frames a minute,
     * which is a second and a half across a shooting day.
     */
    fun advancedBy(millis: Long): Timecode {
        val framesElapsed = Math.round(millis / 1000.0 * fps)
        return fromFrameCount(toFrameCount(this) + framesElapsed, fps, dropFrame)
    }
}
