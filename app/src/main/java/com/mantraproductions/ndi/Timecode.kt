package com.mantraproductions.ndi

/**
 * SMPTE timecode, and the arithmetic that makes it agree with a clock.
 *
 * Nothing here touches Android or Bluetooth, so every rule below is checked on
 * a desk. That matters more than usual: timecode that is wrong by one frame
 * looks exactly like timecode that is right, and the discovery happens in the
 * edit when the sound no longer lines up.
 *
 * Drop frame is the part worth being careful about. 29.97 is not thirty, so a
 * counter running at thirty labels drifts about 3.6 seconds ahead of the clock
 * every hour. Drop frame fixes the labels rather than the frames: the first
 * two frame numbers of every minute are skipped, except on every tenth minute,
 * which lands the count back on the wall clock. No frames are lost. Only two
 * names per minute go unused.
 */
data class Timecode(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val frames: Int,
    val rate: Rate
) {
    enum class Rate(val fps: Double, val nominal: Int, val dropFrame: Boolean, val label: String) {
        FPS_23_976(24000.0 / 1001.0, 24, false, "23.98"),
        FPS_24(24.0, 24, false, "24"),
        FPS_25(25.0, 25, false, "25"),
        FPS_29_97(30000.0 / 1001.0, 30, false, "29.97"),
        FPS_29_97_DF(30000.0 / 1001.0, 30, true, "29.97 DF"),
        FPS_30(30.0, 30, false, "30");

        companion object {
            /** Nearest rate to a camera's frame rate, for labelling a recording. */
            fun nearest(fps: Int): Rate = when (fps) {
                24 -> FPS_24
                25 -> FPS_25
                30 -> FPS_30
                50 -> FPS_25
                60 -> FPS_30
                else -> FPS_25
            }
        }
    }

    /**
     * The shape the Tentacle parser and the log already speak.
     *
     * They work in a raw frame rate and a drop frame flag, because that is
     * what a device reports over the air; this model works in a named rate,
     * because that is what the arithmetic needs. Both are the same fact, so
     * the model answers to either rather than the two being kept in step by
     * hand.
     */
    constructor(hours: Int, minutes: Int, seconds: Int, frames: Int, fps: Double, dropFrame: Boolean)
        : this(hours, minutes, seconds, frames, rateOf(fps, dropFrame))

    val fps: Double get() = rate.fps
    val dropFrame: Boolean get() = rate.dropFrame

    /**
     * The same timecode, some milliseconds later. Used by the source to count
     * forward between broadcasts, which is most of the time.
     */
    fun advancedBy(millis: Long): Timecode =
        fromFrames(toFrames(this) + Math.round(millis / 1000.0 * rate.fps), rate)

    /** The separator says which kind it is, as it does on every professional display. */
    override fun toString(): String {
        val separator = if (rate.dropFrame) ';' else ':'
        return "%02d:%02d:%02d%c%02d".format(hours, minutes, seconds, separator, frames)
    }

    companion object {

        /**
         * Frame number to timecode, skipping the labels drop frame skips.
         *
         * The awkward one, and the reason it is written out rather than
         * approximated: two labels are dropped at every minute except every
         * tenth, so a ten minute block holds 17982 frames rather than 18000.
         */
        fun fromFrames(frameNumber: Long, rate: Rate): Timecode {
            val n = rate.nominal
            if (!rate.dropFrame) {
                var f = frameNumber
                val perDay = n.toLong() * 60 * 60 * 24
                f = ((f % perDay) + perDay) % perDay
                return Timecode(
                    hours = (f / (n * 3600)).toInt(),
                    minutes = ((f / (n * 60)) % 60).toInt(),
                    seconds = ((f / n) % 60).toInt(),
                    frames = (f % n).toInt(),
                    rate = rate
                )
            }

            val dropPerMinute = 2L
            val framesPerTenMinutes = 17982L
            val framesPerMinute = 1798L

            val perDay = framesPerTenMinutes * 6 * 24
            var f = ((frameNumber % perDay) + perDay) % perDay

            val tenMinuteBlocks = f / framesPerTenMinutes
            var remainder = f % framesPerTenMinutes

            // The first minute of each block drops nothing, so it is longer.
            var minutesIn = 0L
            if (remainder >= 1800) {
                remainder -= 1800
                minutesIn = 1 + remainder / framesPerMinute
                remainder %= framesPerMinute
                remainder += dropPerMinute
            }

            val totalMinutes = tenMinuteBlocks * 10 + minutesIn
            return Timecode(
                hours = ((totalMinutes / 60) % 24).toInt(),
                minutes = (totalMinutes % 60).toInt(),
                seconds = (remainder / 30).toInt(),
                frames = (remainder % 30).toInt(),
                rate = rate
            )
        }

        /** The inverse, so a timecode read off a device can be counted from. */
        fun toFrames(tc: Timecode): Long {
            val n = tc.rate.nominal.toLong()
            val plain = tc.hours * 3600L * n + tc.minutes * 60L * n + tc.seconds * n + tc.frames
            if (!tc.rate.dropFrame) return plain
            // Every minute drops two labels except every tenth.
            val totalMinutes = tc.hours * 60L + tc.minutes
            val dropped = 2L * (totalMinutes - totalMinutes / 10)
            return plain - dropped
        }

        /**
         * Frames per second as an integer, for code that counts rather than
         * formats. Kept as a function because the parser walking a byte buffer
         * has an fps in hand rather than a Rate.
         */
        /** The named rate nearest a reported frame rate and its flag. */
        fun rateOf(fps: Double, dropFrame: Boolean): Rate = when {
            // Drop frame is checked first because it only exists at one rate,
            // and a signal that says drop frame is telling us the rate as
            // surely as its timing does. Deciding by fps first threw the flag
            // away whenever the measurement rounded to 25.
            dropFrame -> Rate.FPS_29_97_DF
            fps < 23.99 -> Rate.FPS_23_976
            fps < 24.5 -> Rate.FPS_24
            fps < 27.0 -> Rate.FPS_25
            fps < 29.99 -> Rate.FPS_29_97
            else -> Rate.FPS_30
        }

        fun nominal(fps: Double): Int = Math.round(fps).toInt()

        fun nominal(rate: Rate): Int = rate.nominal

        /**
         * The same as [fromFrames], named the way the parser asks for it, and
         * taking the rate as fps plus a drop frame flag because that is what a
         * device reports rather than an enum.
         */
        fun fromFrameCount(count: Long, fps: Double, dropFrame: Boolean): Timecode {
            val rate = Rate.values().firstOrNull {
                Math.abs(it.fps - fps) < 0.01 && it.dropFrame == dropFrame
            } ?: Rate.FPS_25
            return fromFrames(count, rate)
        }

        /**
         * The names the Tentacle parser already calls, kept as the one public
         * spelling rather than two doing the same thing.
         */
        /**
         * As NDI carries it: 100 nanosecond units since midnight.
         *
         * Computed from the frame count rather than from the fields, so drop
         * frame lands on real time rather than on its own labels. That is the
         * whole point of drop frame, and a converter that ignores it puts an
         * hour of 29.97 out by three and a half seconds.
         */
        fun to100ns(tc: Timecode): Long =
            Math.round(toFrames(tc) / tc.rate.fps * 10_000_000.0)

        /** And back, for a follower reading a master's stamp. */
        fun from100ns(units: Long, rate: Rate): Timecode =
            fromFrames(Math.round(units / 10_000_000.0 * rate.fps), rate)

        /** Parses what a device or a person writes, in either separator. */
        fun parse(text: String, rate: Rate): Timecode? {
            val parts = text.trim().split(':', ';', '.')
            if (parts.size != 4) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers[0] !in 0..23 || numbers[1] !in 0..59 ||
                numbers[2] !in 0..59 || numbers[3] !in 0 until rate.nominal
            ) return null
            return Timecode(numbers[0], numbers[1], numbers[2], numbers[3], rate)
        }
    }
}

/**
 * A timecode that keeps running between messages.
 *
 * A Tentacle broadcasts every so often, not every frame, so a display that
 * only moved when a packet arrived would stutter and a file stamped from the
 * last packet would be late by however long ago it was. The answer is the one
 * every timecode device uses: take a reading, note the instant it arrived by a
 * clock that does not jump, and from then on report the reading plus the time
 * since.
 *
 * elapsedRealtimeNanos is that clock. It counts through sleep and cannot be
 * moved by the user, a time zone or the network, all of which would otherwise
 * put a shoot's timecode out by an hour without anybody noticing.
 */
class TimecodeClock {

    private var anchorFrames = 0L
    private var anchorNanos = 0L

    // A separate flag rather than a magic zero. Zero is a perfectly good
    // instant, and treating it as "nothing yet" made a clock jammed at the
    // start of time report no signal forever.
    private var jammed = false
    private var rate: Timecode.Rate = Timecode.Rate.FPS_25

    @Volatile var lastHeardNanos = 0L
        private set

    val hasSignal: Boolean get() = jammed

    /** How long since a device was last heard from, in seconds. */
    fun secondsSinceHeard(nowNanos: Long): Double =
        if (!jammed) Double.MAX_VALUE
        else (nowNanos - lastHeardNanos) / 1_000_000_000.0

    fun jam(timecode: Timecode, atNanos: Long) {
        anchorFrames = Timecode.toFrames(timecode)
        anchorNanos = atNanos
        rate = timecode.rate
        lastHeardNanos = atNanos
        jammed = true
    }

    /** Where the clock is now, counted forward from the last reading. */
    fun now(nowNanos: Long): Timecode? {
        if (!hasSignal) return null
        val elapsedSeconds = (nowNanos - anchorNanos) / 1_000_000_000.0
        val advanced = anchorFrames + Math.round(elapsedSeconds * rate.fps)
        return Timecode.fromFrames(advanced, rate)
    }

    fun clear() {
        jammed = false
        anchorNanos = 0L
        lastHeardNanos = 0L
    }
}
