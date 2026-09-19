package com.mantraproductions.ndi

import android.os.Environment
import android.os.StatFs
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether the recording is actually surviving, and how long it can go on.
 *
 * The two ways a long take fails silently. The card fills, and the file stops
 * at whatever second that happened. Or the encoder falls behind, frames are
 * dropped to keep up, and nothing says so until an edit finds a take that
 * stutters. Both are invisible while shooting and both are unrecoverable
 * afterwards, which is exactly the kind of failure a camera should be shouting
 * about rather than a phone quietly absorbing.
 *
 * So: space left, time left at the bitrate actually in use, and a count of
 * what the encoder could not keep up with.
 */
object RecordingHealth {

    private val dropped = AtomicLong(0)
    private val delivered = AtomicLong(0)

    /** Called by the encoder path for every frame that made it. */
    fun frameDelivered() {
        delivered.incrementAndGet()
    }

    /**
     * Called when a frame was produced and could not be taken.
     *
     * Counted rather than logged, because one dropped frame in an hour is
     * noise and two hundred is a take to reshoot, and only a number tells
     * those apart.
     */
    fun frameDropped(count: Long = 1) {
        dropped.addAndGet(count)
    }

    fun reset() {
        dropped.set(0)
        delivered.set(0)
    }

    val droppedFrames: Long get() = dropped.get()

    /** Zero when nothing has been sent yet, rather than a division by it. */
    val dropPercent: Double
        get() {
            val total = delivered.get() + dropped.get()
            return if (total == 0L) 0.0 else dropped.get() * 100.0 / total
        }

    /** Bytes free where recordings are written. */
    fun freeBytes(): Long = try {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        0L
    }

    /**
     * How long the free space lasts at this bitrate.
     *
     * Video plus audio, since audio is small but not nothing over an hour, and
     * a figure that ignores it reads long by a few minutes exactly when those
     * minutes matter.
     */
    fun secondsRemaining(videoMbps: Int, free: Long = freeBytes()): Long {
        val bitsPerSecond = (videoMbps + 0.2) * 1_000_000.0
        if (bitsPerSecond <= 0) return 0
        return (free * 8.0 / bitsPerSecond).toLong()
    }

    /** "417 GB" or "980 MB", whichever a person would say. */
    fun formatSpace(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "${bytes / 1_000_000_000L} GB"
        bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "${bytes / 1000L} kB"
    }

    /** "2h14m" or "8m", never "0h8m". */
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    enum class Level { FINE, LOW, CRITICAL }

    /**
     * When to start worrying about space.
     *
     * By time rather than by bytes, because ten gigabytes is an hour at one
     * setting and six minutes at another, and the question anybody actually
     * asks is whether the next take will fit.
     */
    fun spaceLevel(secondsLeft: Long): Level = when {
        secondsLeft < 300 -> Level.CRITICAL
        secondsLeft < 1200 -> Level.LOW
        else -> Level.FINE
    }

    /**
     * When dropped frames stop being noise.
     *
     * A handful over a long take is the encoder catching its breath. A steady
     * percentage is a setting the phone cannot hold, and the answer is a lower
     * bitrate or a smaller size rather than hoping.
     */
    fun dropLevel(percent: Double): Level = when {
        percent >= 2.0 -> Level.CRITICAL
        percent >= 0.5 -> Level.LOW
        else -> Level.FINE
    }

    /** One line for the burn-in: what is left, and what has been lost. */
    fun summary(videoMbps: Int): String {
        val free = freeBytes()
        val left = secondsRemaining(videoMbps, free)
        return buildString {
            append(formatSpace(free)).append("   ").append(formatDuration(left))
            if (droppedFrames > 0) {
                append("   ").append(droppedFrames).append(" DROPPED")
            }
        }
    }
}
