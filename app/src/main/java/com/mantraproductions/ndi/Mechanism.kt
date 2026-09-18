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
     * Fader position to shutter time. Squared so the short end, where the
     * useful stops are, gets most of the travel.
     */
    fun shutterFromProgress(progress: Int, steps: Int, minNs: Long, maxNs: Long): Long {
        if (maxNs <= minNs || steps <= 0) return minNs
        val fraction = (progress.coerceIn(0, steps).toDouble() / steps).pow(2.0)
        return (minNs + fraction * (maxNs - minNs)).toLong().coerceIn(minNs, maxNs)
    }

    /** The denominator a camera operator reads: 20000000ns becomes 50, as in 1/50. */
    fun shutterDenominator(ns: Long): Int =
        if (ns <= 0) 0 else Math.round(1_000_000_000.0 / ns).toInt()

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
