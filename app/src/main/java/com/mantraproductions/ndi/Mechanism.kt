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

    /**
     * A recording's name, taken from what the camera calls itself.
     *
     * On a multi camera shoot the filename is the only thing that survives the
     * card reader, so it has to say which camera shot it. Rename the camera and
     * the next take is named accordingly; takes already on disk keep the name
     * they were shot under, which is the point of naming them at all.
     */
    fun recordingFileName(sourceName: String, timestamp: String, extension: String = "mp4"): String {
        val safe = fileSafeName(sourceName)
        return "${safe}_$timestamp.$extension"
    }

    /** Spaces and anything a file system or an NLE might trip on. */
    fun fileSafeName(raw: String): String {
        val cleaned = sanitizeSourceName(raw)
            .replace(' ', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "")
            .trim('_', '.', '-')
        return cleaned.ifEmpty { "MantraNDI" }
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

    // --- controls that correct rather than set --------------------------------

    /**
     * A fader whose centre is whatever the camera decided.
     *
     * Setting an absolute ISO from one end of a range that runs 50 to 6400
     * means the useful positions are crowded into a few millimetres. What an
     * operator actually does is take the camera's reading and push it a little
     * either way, so the fader is a correction: centre is the detected value
     * and the ends are a fixed number of stops around it.
     *
     * @param progress fader position
     * @param steps fader length, centre at steps/2
     * @param detected what the camera chose
     * @param stopsEachWay how far the ends reach
     */
    fun correctedFromAuto(
        progress: Int,
        steps: Int,
        detected: Double,
        stopsEachWay: Double
    ): Double {
        if (steps <= 0 || detected <= 0.0) return detected
        val centre = steps / 2.0
        val offset = (progress.coerceIn(0, steps) - centre) / centre
        return detected * 2.0.pow(offset * stopsEachWay)
    }

    /** The fader position that corresponds to no correction at all. */
    fun centreOf(steps: Int): Int = steps / 2

    /** How the correction reads on the label: -1.3, 0, +0.7 and so on. */
    fun correctionLabel(progress: Int, steps: Int, stopsEachWay: Double): String {
        if (steps <= 0) return "0"
        val centre = steps / 2.0
        val stops = (progress.coerceIn(0, steps) - centre) / centre * stopsEachWay
        return when {
            Math.abs(stops) < 0.05 -> "0"
            stops > 0 -> "+%.1f".format(stops)
            else -> "%.1f".format(stops)
        }
    }

    // --- white balance around a working centre --------------------------------

    /**
     * A narrower Kelvin range than the sensor will accept.
     *
     * 2000 to 10000 puts tungsten and shade at opposite ends of a fader and
     * everything anybody shoots in the middle centimetre. Almost all work sits
     * between warm interior and daylight, so the fader covers that and the
     * ends are still reachable by holding the stepper.
     */
    const val KELVIN_WORKING_MIN = 3200
    const val KELVIN_WORKING_MAX = 5600
    const val KELVIN_WORKING_CENTRE = 4400

    fun kelvinFromProgress(progress: Int, steps: Int): Int {
        if (steps <= 0) return KELVIN_WORKING_CENTRE
        val fraction = progress.coerceIn(0, steps).toDouble() / steps
        return (KELVIN_WORKING_MIN + fraction * (KELVIN_WORKING_MAX - KELVIN_WORKING_MIN))
            .toInt()
    }

    fun progressForKelvin(kelvin: Int, steps: Int): Int {
        val clamped = kelvin.coerceIn(KELVIN_WORKING_MIN, KELVIN_WORKING_MAX)
        val fraction = (clamped - KELVIN_WORKING_MIN).toDouble() /
                (KELVIN_WORKING_MAX - KELVIN_WORKING_MIN)
        return (fraction * steps).toInt().coerceIn(0, steps)
    }

    /**
     * The colour temperature that best matches a set of camera gains.
     *
     * The inverse of [kelvinToGains], found by search rather than by algebra:
     * the forward curve is a piecewise black body approximation with no clean
     * closed form, and a search over eight thousand Kelvin converges in about
     * thirteen steps, which costs nothing once per press of a button.
     *
     * What matters is the red to blue ratio. Green is the reference both ways,
     * and the absolute scale of the gains is set by whichever channel needed
     * the least, so only the ratio carries the temperature.
     */
    fun kelvinFromGains(red: Float, green: Float, blue: Float): Int {
        if (red <= 0f || blue <= 0f) return KELVIN_WORKING_CENTRE
        val targetRatio = (red / blue).toDouble()

        var low = KELVIN_MIN
        var high = KELVIN_MAX
        // Ratio rises with temperature: cool light needs more red gain.
        repeat(14) {
            val mid = (low + high) / 2
            val gains = kelvinToGains(mid)
            val ratio = gains[0].toDouble() / gains[2].toDouble()
            if (ratio < targetRatio) low = mid else high = mid
        }
        return ((low + high) / 2).coerceIn(KELVIN_MIN, KELVIN_MAX)
    }

    /**
     * Constrains an illuminant estimate to the light sources that exist.
     *
     * This is the step that separates a camera from a textbook. Grey world and
     * its relatives assume the average of a scene is neutral, and a scene that
     * is mostly grass, mostly sky or mostly a wooden bench breaks that
     * assumption completely: the estimate chases the dominant object and the
     * correction cancels the actual colour of the world.
     *
     * Real illuminants do not wander. Daylight, tungsten, fluorescent and LED
     * all sit on or near the Planckian locus, because that is what a hot body
     * radiates and what lamp makers copy. So the fix used across the industry
     * is to refuse any estimate off that curve: take whatever the statistics
     * suggest, find the colour temperature whose gains point most nearly the
     * same way, and use that instead. The estimate can be wrong by some
     * hundreds of Kelvin. It can no longer be green.
     *
     * @return the constrained temperature in Kelvin
     */
    fun constrainToPlanckian(red: Float, green: Float, blue: Float): Int {
        if (red <= 0f || green <= 0f || blue <= 0f) return KELVIN_WORKING_CENTRE
        // Work in gains normalised on green, which is how a sensor expresses
        // an illuminant and what removes exposure from the comparison.
        val targetR = red / green
        val targetB = blue / green

        var best = KELVIN_WORKING_CENTRE
        var bestError = Double.MAX_VALUE
        var k = KELVIN_MIN
        while (k <= KELVIN_MAX) {
            val candidate = kelvinToGains(k)
            val cr = candidate[0] / candidate[1]
            val cb = candidate[2] / candidate[1]
            // Angular-ish error in the two ratios, in log space so a factor of
            // two costs the same whichever direction it goes.
            val error = sq(ln(cr / targetR)) + sq(ln(cb / targetB))
            if (error < bestError) {
                bestError = error
                best = k
            }
            k += 50
        }
        return best
    }

    private fun sq(x: Double) = x * x

    /**
     * Shades of grey: the Minkowski norm of each channel.
     *
     * p = 1 is the mean, which is grey world and is the one that fails. p at
     * infinity is the brightest pixel, which is white patch and fails
     * differently. Around 6 has been the practical answer in the literature
     * for twenty years, because it leans towards the bright, near neutral
     * parts of a frame without letting a single specular highlight decide.
     */
    fun shadesOfGrey(samples: List<Triple<Double, Double, Double>>, p: Double = 6.0): FloatArray {
        if (samples.isEmpty()) return floatArrayOf(1f, 1f, 1f)
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        for ((r, g, b) in samples) {
            sr += r.pow(p)
            sg += g.pow(p)
            sb += b.pow(p)
        }
        val n = samples.size.toDouble()
        val nr = (sr / n).pow(1.0 / p)
        val ng = (sg / n).pow(1.0 / p)
        val nb = (sb / n).pow(1.0 / p)
        // Gains are the inverse of the illuminant, normalised on green.
        if (nr <= 0.0 || ng <= 0.0 || nb <= 0.0) return floatArrayOf(1f, 1f, 1f)
        return floatArrayOf((ng / nr).toFloat(), 1f, (ng / nb).toFloat())
    }

    /**
     * Moves a measured set of gains from one temperature to another without
     * discarding the measurement.
     *
     * The gains a camera reports are specific to its sensor and its scene. If
     * the operator then asks for 5000K, recomputing gains from a black body
     * curve throws all of that away and hands the sensor a textbook answer.
     * Applying the ratio between two curve points instead keeps the camera's
     * own measurement and shifts it by exactly the amount asked for.
     */
    fun shiftGains(measured: FloatArray, fromKelvin: Int, toKelvin: Int): FloatArray {
        if (measured.size < 3) return measured
        val from = kelvinToGains(fromKelvin)
        val to = kelvinToGains(toKelvin)
        val out = FloatArray(3)
        for (i in 0..2) {
            val ratio = if (from[i] > 0f) to[i] / from[i] else 1f
            out[i] = (measured[i] * ratio).coerceAtLeast(0.05f)
        }
        // Renormalise so the smallest gain is 1.0, which is what the camera
        // expects and what keeps exposure unchanged by a colour move.
        val smallest = minOf(out[0], out[1], out[2])
        if (smallest > 0f) for (i in 0..2) out[i] /= smallest
        return out
    }

    /**
     * Temporal smoothing with a dead zone.
     *
     * A balance that re-solves every frame visibly breathes, and a cut between
     * two breathing shots cannot be graded. Cameras hold the last answer until
     * the new one differs enough to be real, then move gradually.
     *
     * @return the temperature to actually use this frame
     */
    fun smoothKelvin(current: Int, measured: Int, deadZone: Int = 250, step: Double = 0.25): Int {
        if (Math.abs(measured - current) < deadZone) return current
        return (current + (measured - current) * step).toInt()
    }

    // --- audio gain -----------------------------------------------------------

    /** Gain in dB from a fader, centred on unity so the middle changes nothing. */
    fun gainDbFromProgress(progress: Int, steps: Int, rangeDb: Double = 18.0): Double {
        if (steps <= 0) return 0.0
        val centre = steps / 2.0
        return (progress.coerceIn(0, steps) - centre) / centre * rangeDb
    }

    fun gainFactor(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    /**
     * Applies gain to 16 bit PCM in place, clamping rather than wrapping.
     * Wrapping a sample turns a loud moment into a burst of noise, which is
     * far worse than the clipping it came from.
     */
    fun applyGainPcm16(pcm: ByteArray, factor: Float) {
        if (factor == 1f) return
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            val scaled = (sample * factor).toInt().coerceIn(-32768, 32767)
            pcm[i] = (scaled and 0xFF).toByte()
            pcm[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    // --- the tone curve handed to the camera ---------------------------------

    /**
     * A log curve sampled into the flat pair array Camera2 wants: input, output,
     * input, output. The camera is already tone mapping every frame, so
     * replacing its curve with this one costs nothing per frame. That is the
     * whole trick behind recording log without a GPU pass.
     *
     * @param points how many pairs, from the camera's own maximum
     */
    fun toneCurvePoints(curve: LogCurves.Curve, points: Int): FloatArray {
        val n = points.coerceIn(2, 128)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val input = i.toFloat() / (n - 1)
            // The camera hands the curve a display-referred value, so undo
            // Rec.709 to reach scene light before re-encoding in the target.
            val linear = LogCurves.decode(LogCurves.Curve.REC709, input.toDouble())
                .coerceAtLeast(0.0)
            val output = LogCurves.encode(curve, linear).coerceIn(0.0, 1.0)
            out[i * 2] = input
            out[i * 2 + 1] = output.toFloat()
        }
        return out
    }

    /**
     * Focus as a fraction of the lens travel, stepped by a fixed amount.
     * The plus and minus at the ends of the fader exist because focus is the
     * one control where a finger is never precise enough.
     */
    fun stepFocus(fraction: Float, steps: Int): Float =
        (fraction + steps * FOCUS_STEP).coerceIn(0f, 1f)

    const val FOCUS_STEP = 0.01f

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
