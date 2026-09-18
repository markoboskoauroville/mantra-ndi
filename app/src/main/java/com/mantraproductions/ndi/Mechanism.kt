package com.mantraproductions.ndi

import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The arithmetic and the parsing, with nothing from Android in it.
 *
 * android-app.md §1 requires this: the rules live in a file that imports no
 * Android, so Test 1 runs on a desk in under a second instead of costing a
 * five minute CI round trip, and verify.py fails the build the day an
 * `import android.` appears here.
 *
 * Everything in this file is a pure function. No state, no clock, no IO.
 */
object Mechanism {

    // --- source names -------------------------------------------------------

    const val MAX_NAME_LENGTH = 40

    /** NDI names cross mDNS and get parsed by other software, so keep them plain. */
    fun sanitizeSourceName(raw: String): String {
        // Disallowed characters become a space rather than vanishing: deleting a
        // separator welds two words together, so "CAM/A" would read "CAMA".
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._()-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            cleaned.isEmpty() -> "Mantra Cam"
            cleaned.length > MAX_NAME_LENGTH -> cleaned.take(MAX_NAME_LENGTH).trim()
            else -> cleaned
        }
    }

    /** NDI advertises "MACHINE (Source Name)", so compare what is in the brackets. */
    fun nameClashes(candidate: String, existing: List<String>): Boolean {
        val target = sanitizeSourceName(candidate).lowercase()
        return existing.any { full ->
            val inner = full.substringAfter('(', full).substringBeforeLast(')').trim()
            inner.lowercase() == target || full.trim().lowercase() == target
        }
    }

    // --- exposure -----------------------------------------------------------

    /**
     * The shutter range a camera may actually use at a given frame rate.
     *
     * This is the rule the app was missing and it is why the preview froze.
     * The sensor will happily accept a two second exposure, and a sensor
     * producing a frame every two seconds is a camera running at half a frame
     * per second. Nothing is hung; there is simply no next frame. Film cameras
     * have the same physical limit and nobody notices, because a shutter dial
     * does not offer speeds the frame rate cannot carry.
     *
     * So the longest exposure is one frame interval, whatever the sensor
     * claims it can do.
     */
    fun shutterRangeForFps(fps: Int, sensorMinNs: Long, sensorMaxNs: Long): Pair<Long, Long> {
        if (fps <= 0) return sensorMinNs to sensorMaxNs
        val frameInterval = 1_000_000_000L / fps
        val upper = minOf(sensorMaxNs, frameInterval).coerceAtLeast(sensorMinNs)
        return sensorMinNs to upper
    }

    /**
     * Fader position to shutter time, in stops.
     *
     * Geometric rather than linear, because that is how shutter speeds work:
     * each stop halves the light. A linear fader spends most of its length
     * between 1/40 and 1/50 and crosses everything from 1/500 to 1/8000 in the
     * last few pixels, which is exactly backwards from what the hand wants.
     */
    fun shutterFromProgress(progress: Int, steps: Int, minNs: Long, maxNs: Long): Long {
        if (maxNs <= minNs || steps <= 0) return minNs
        val fraction = progress.coerceIn(0, steps).toDouble() / steps
        val ratio = maxNs.toDouble() / minNs.toDouble()
        return (minNs * ratio.pow(fraction)).toLong().coerceIn(minNs, maxNs)
    }

    /** The inverse of [shutterFromProgress], for placing the fader at a known speed. */
    fun progressForShutter(ns: Long, steps: Int, minNs: Long, maxNs: Long): Int {
        if (maxNs <= minNs || steps <= 0) return 0
        val clamped = ns.coerceIn(minNs, maxNs)
        val fraction = ln(clamped.toDouble() / minNs) / ln(maxNs.toDouble() / minNs)
        return Math.round(fraction * steps).toInt().coerceIn(0, steps)
    }

    /** Frame duration to request alongside a manual exposure, in nanoseconds. */
    fun frameDurationForFps(fps: Int): Long =
        if (fps <= 0) 0L else 1_000_000_000L / fps

    /** The denominator a camera operator reads: 20000000ns becomes 50, as in 1/50. */
    fun shutterDenominator(ns: Long): Int =
        if (ns <= 0) 0 else Math.round(1_000_000_000.0 / ns).toInt()

    /**
     * What to print on the fader. Past one second the fraction collapses to
     * 1/0, which is why long exposures are written as seconds instead.
     */
    fun formatShutter(ns: Long): String = when {
        ns <= 0L -> "--"
        ns >= 1_000_000_000L -> {
            val seconds = ns / 1_000_000_000.0
            if (seconds >= 10) "${Math.round(seconds)}s" else String.format("%.1fs", seconds)
        }
        else -> "1/${shutterDenominator(ns)}"
    }

    /** The film-standard 180 degree shutter for a frame rate. */
    fun shutter180Ns(fps: Int): Long = if (fps <= 0) 0 else 1_000_000_000L / (fps * 2L)

    // --- white balance ------------------------------------------------------

    const val KELVIN_MIN = 2000
    const val KELVIN_MAX = 10000

    /**
     * Colour temperature to camera gains, as [red, green, blue].
     *
     * The temperature gives the colour of the light; the gains are its inverse,
     * because a camera under warm light already sees plenty of red and needs
     * less of it. Normalised so the smallest gain is 1.0, which is what
     * COLOR_CORRECTION_GAINS expects.
     */
    fun kelvinToGains(kelvin: Int): FloatArray {
        val t = kelvin.coerceIn(KELVIN_MIN, KELVIN_MAX) / 100.0

        val red = if (t <= 66) 255.0
        else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)

        val green = if (t <= 66) (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
        else (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)

        val blue = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        var r = (255.0 / red.coerceAtLeast(1.0)).toFloat()
        var g = (255.0 / green.coerceAtLeast(1.0)).toFloat()
        var b = (255.0 / blue.coerceAtLeast(1.0)).toFloat()

        val smallest = minOf(r, g, b)
        r /= smallest
        g /= smallest
        b /= smallest
        return floatArrayOf(r, g, b)
    }

    // --- the record counter -------------------------------------------------

    /**
     * The running time, written as short as it can honestly be.
     *
     * Under a minute there is no reason to print a zero minute, and the digits
     * that are left can then be drawn twice the size inside the same circle.
     * The label grows only when the clock forces it: 7, 45, 1:00, 10:05,
     * 1:00:00. Every step keeps the largest text that still fits.
     */
    fun recordLabel(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val secs = s % 60
        return when {
            hours > 0 -> "%d:%02d:%02d".format(hours, minutes, secs)
            minutes > 0 -> "%d:%02d".format(minutes, secs)
            else -> secs.toString()
        }
    }

    // --- which control the single fader is showing ---------------------------

    /**
     * One fader at a time, cycled by the button at its left end. Covering the
     * image with four faders to change one of them was the problem; this keeps
     * a single row on screen and steps through it.
     */
    enum class Param(val label: String) {
        ISO("ISO"),
        SHUTTER("Shutter"),
        WHITE_BALANCE("Kelvin"),
        ZOOM("Zoom");

        fun next(): Param = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromName(name: String?): Param =
                entries.firstOrNull { it.name == name } ?: ISO
        }
    }

    // --- metering -----------------------------------------------------------

    const val METER_FLOOR_DB = -54f

    /** Linear RMS to a 0..1 meter position on a dB scale. */
    fun rmsToMeterFraction(rms: Float): Float {
        if (rms <= 0.0000001f) return 0f
        val db = (20f * log10(rms)).coerceAtLeast(METER_FLOOR_DB)
        return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
    }

    /** RMS of 16 bit little endian PCM, sampling every [stride] bytes. */
    fun rmsOfPcm16(pcm: ByteArray, stride: Int = 4): Float {
        var sumSquares = 0.0
        var counted = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val value = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            val normalised = value / 32768.0
            sumSquares += normalised * normalised
            counted++
            i += stride
        }
        return if (counted == 0) 0f else sqrt(sumSquares / counted).toFloat()
    }
}
