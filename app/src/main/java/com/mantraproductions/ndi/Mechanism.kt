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
     * The fastest shutter worth putting on a fader: 1/8000.
     *
     * His sensor will expose for about eleven microseconds, which reads out as
     * **1/92030**, and the fader dutifully offered every stop of it. Nobody
     * shoots at a ninety-thousandth of a second; what that end of the range
     * costs is precision everywhere else, because the useful two-thirds of the
     * travel is squeezed into the right-hand third of the glass.
     */
    const val FASTEST_USEFUL_SHUTTER_NS = 125_000L

    /**
     * The range a shutter fader should actually cover.
     *
     * The ceiling is one frame interval, because a shutter longer than a frame
     * cannot be honoured at the frame rate and the camera resolves the
     * contradiction by dropping the rate. The floor is 1/8000 or whatever the
     * sensor's own fastest is, whichever is the *slower* — so the whole track
     * is speeds somebody might choose, and the knob can be put on 1/50 with a
     * thumb rather than with a fingernail.
     */
    fun shutterFaderRange(fps: Int, sensorMinNs: Long, sensorMaxNs: Long): Pair<Long, Long> {
        val (low, high) = shutterRangeForFps(fps, sensorMinNs, sensorMaxNs)
        val floor = maxOf(low, FASTEST_USEFUL_SHUTTER_NS).coerceAtMost(high)
        return floor to high
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

    /**
     * A fader's position, 0..1, to the value it stands for, in stops.
     *
     * **This is "the slider stops two thirds of the way along".** A fader whose
     * knob is drawn from what the camera reports, while the drag moves a fixed
     * number of stops per swipe, is two different instruments wearing one hat:
     * the sensor will take an exposure of ten microseconds and the frame rate
     * will not, so the camera clamps at a thirtieth of a second, and the knob
     * parks at whatever fraction of the *sensor's* range that happens to be —
     * two thirds, on his phone — with a third of the track that can never be
     * reached and no way to tell from looking.
     *
     * So the position is the instrument. The whole track is the whole of the
     * range the camera will honour, the knob goes where the finger puts it,
     * and the value is read off the position rather than the other way round.
     * Geometric, because these are stops: each equal step of the thumb is an
     * equal change of light, which is the only spacing a camera operator's
     * hand has ever been trained on.
     */
    fun valueAtPosition(position: Float, low: Double, high: Double): Double {
        if (low <= 0.0 || high <= low) return low.coerceAtLeast(0.0)
        val at = position.coerceIn(0f, 1f).toDouble()
        return low * (high / low).pow(at)
    }

    /** Where a value sits on that fader, 0..1. The inverse of [valueAtPosition]. */
    fun positionOfValue(value: Double, low: Double, high: Double): Float {
        if (value <= 0.0 || low <= 0.0 || high <= low) return 0f
        val span = ln(high / low)
        if (span <= 0.0) return 0f
        return (ln(value.coerceIn(low, high) / low) / span).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The frame rates worth offering, out of what the camera will accept.
     *
     * A camera publishes ranges rather than rates, and most of them are
     * ranges nobody wants — `[15,30]` is the one that lets a dim room halve
     * the frame rate to keep the picture bright, which on a stream is worse
     * than a dark picture. A rate is offered here only when the camera will
     * hold it steady, top and bottom, and only if it is a rate anybody shoots
     * at: 24 for film, 25 and 50 where the mains is 50Hz, 30 and 60 where it
     * is 60.
     */
    val FRAME_RATES = intArrayOf(24, 25, 30, 50, 60)

    fun frameRatesFrom(ranges: List<Pair<Int, Int>>, minFrameDurationNs: Long): List<Int> {
        val steady = ranges.filter { it.first == it.second }.map { it.second }.toSet()
        val ceiling =
            if (minFrameDurationNs > 0L) (1_000_000_000.0 / minFrameDurationNs) else Double.MAX_VALUE
        return FRAME_RATES.filter { fps ->
            fps <= ceiling + 0.5 &&
                (steady.contains(fps) || ranges.any { it.first <= fps && fps <= it.second })
        }
    }

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
        ZOOM("Zoom"),
        // No exceptions: every fader on the panel is a rocker parameter.
        // Leaving two out meant two columns that could not be selected and
        // could not be driven, with nothing on screen explaining why.
        FOCUS("Focus"),
        GAIN("Gain");

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
    /**
     * The locus, computed once and then only ever read.
     *
     * The search used to evaluate the black body curve a hundred and sixty
     * times per call, and it was called on every capture result, which is
     * nearly five thousand logarithms and powers per second of video on the
     * camera's own callback thread. That thread delivers frames; making it do
     * arithmetic delays them, which is where the app's sluggishness and the
     * minute-long wait after a balance reset both came from.
     *
     * The curve does not change, so it is a table. Built once on first use,
     * read forever after, and the search becomes a scan over a hundred and
     * sixty pairs of floats with no transcendental left in it.
     */
    private val planckianTable: Array<FloatArray> by lazy {
        Array(((KELVIN_MAX - KELVIN_MIN) / 50) + 1) { i ->
            val kelvin = KELVIN_MIN + i * 50
            val gains = kelvinToGains(kelvin)
            // kelvin, red over green, blue over green
            floatArrayOf(kelvin.toFloat(), gains[0] / gains[1], gains[2] / gains[1])
        }
    }

    fun constrainToPlanckian(red: Float, green: Float, blue: Float): Int {
        if (red <= 0f || green <= 0f || blue <= 0f) return KELVIN_WORKING_CENTRE
        val targetR = red / green
        val targetB = blue / green

        var best = KELVIN_WORKING_CENTRE
        var bestError = Float.MAX_VALUE
        for (row in planckianTable) {
            // Squared difference of the two ratios. Not log space any more,
            // which changes the answer by less than the table's own spacing
            // and removes the last two logarithms from the hot path.
            val dr = row[1] - targetR
            val db = row[2] - targetB
            val error = dr * dr + db * db
            if (error < bestError) {
                bestError = error
                best = row[0].toInt()
            }
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

    // --- white balance presets that snap ------------------------------------

    /**
     * The two references anybody actually works between, and the reason they
     * are marked rather than remembered: tungsten and daylight are where most
     * lighting sits, and hitting them exactly matters because a shot lit by a
     * practical and a shot lit by the sun have to cut together.
     */
    val WHITE_BALANCE_PRESETS = listOf(3200 to "Tungsten", 5500 to "Daylight")

    /**
     * Magnetic: within [pullKelvin] of a preset the fader lands on it exactly.
     * A fader that can reach 5487K when the operator meant 5500K is a fader
     * that produces two shots which do not match, so near enough is snapped to
     * exact. Finer positions are still reachable by the steppers, which do not
     * snap.
     */
    fun snapKelvin(kelvin: Int, pullKelvin: Int = 180): Int {
        val nearest = WHITE_BALANCE_PRESETS.minByOrNull { Math.abs(it.first - kelvin) }
            ?: return kelvin
        return if (Math.abs(nearest.first - kelvin) <= pullKelvin) nearest.first else kelvin
    }

    /** Where the preset marks belong on a fader of [steps]. */
    fun presetPositions(steps: Int): List<Pair<Float, String>> =
        WHITE_BALANCE_PRESETS.map { (kelvin, label) ->
            (progressForKelvin(kelvin, steps).toFloat() / steps) to label
        }

    // --- the vectorscope ------------------------------------------------------

    /**
     * Chroma scatter, the way a vectorscope shows it.
     *
     * A histogram tells you how bright things are, which anybody can see by
     * looking. A vectorscope tells you which way the colour is leaning, which
     * nobody can see reliably, because the eye adapts to a cast within seconds
     * of looking at it. That is the whole reason it exists on a desk.
     *
     * U and V come straight from the frame, so this costs one pass over a
     * downsampled chroma plane and no conversion.
     *
     * @return counts on a [size] by [size] grid, U across and V down, centred
     */
    fun vectorscope(u: ByteArray, v: ByteArray, stride: Int = 2, size: Int = 64): IntArray {
        val grid = IntArray(size * size)
        val count = minOf(u.size, v.size)
        var i = 0
        while (i < count) {
            // 8 bit chroma is offset by 128; centre is neutral.
            val cu = ((u[i].toInt() and 0xFF) - 128) / 128.0
            val cv = ((v[i].toInt() and 0xFF) - 128) / 128.0
            val x = ((cu * 0.5 + 0.5) * (size - 1)).toInt().coerceIn(0, size - 1)
            val y = ((0.5 - cv * 0.5) * (size - 1)).toInt().coerceIn(0, size - 1)
            grid[y * size + x]++
            i += stride
        }
        return grid
    }

    /**
     * The same scope, computed from ordinary pixels.
     *
     * Reading chroma planes needs an analysis stream, and only the direct
     * pipeline can carry one. Every pipeline has a preview, though, and a
     * preview can be sampled, so the scope is built from that instead and then
     * works everywhere rather than only where ten bit does.
     *
     * The conversion is BT.601, which is what U and V mean on a vectorscope.
     * It is the same arithmetic the camera did on the way out, run backwards
     * on a small copy.
     *
     * @param argb packed pixels, as a Bitmap hands them over
     */
    fun vectorscopeFromArgb(argb: IntArray, stride: Int = 3, size: Int = 64): IntArray {
        val grid = IntArray(size * size)
        var i = 0
        while (i < argb.size) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // U is blue minus luma, V is red minus luma, both scaled the way a
            // scope expects and normalised to plus or minus one.
            val u = (-0.169 * r - 0.331 * g + 0.5 * b) / 128.0
            val v = (0.5 * r - 0.419 * g - 0.081 * b) / 128.0
            val x = ((u * 0.5 + 0.5) * (size - 1)).toInt().coerceIn(0, size - 1)
            val y = ((0.5 - v * 0.5) * (size - 1)).toInt().coerceIn(0, size - 1)
            grid[y * size + x]++
            i += stride
        }
        return grid
    }

    /**
     * Where the cast is, from the same pixels.
     *
     * Near black and clipped pixels are left out. Black carries no colour and
     * clipped highlights carry whatever channel saturated first, so both drag
     * the answer towards a cast that is not in the scene. Leaving them out is
     * the same reason a colourist reads the mids rather than the whole frame.
     */
    fun chromaCentroidFromArgb(argb: IntArray, stride: Int = 3): FloatArray {
        var su = 0.0
        var sv = 0.0
        var n = 0
        var i = 0
        while (i < argb.size) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            if (luma in 24.0..238.0) {
                su += (-0.169 * r - 0.331 * g + 0.5 * b) / 128.0
                sv += (0.5 * r - 0.419 * g - 0.081 * b) / 128.0
                n++
            }
            i += stride
        }
        if (n == 0) return floatArrayOf(0f, 0f)
        return floatArrayOf((su / n).toFloat(), (sv / n).toFloat())
    }

    /**
     * Where the cloud sits, as a fraction from the centre.
     *
     * The average chroma of a frame is the cast: a neutral scene sits on the
     * centre and a warm one sits towards red. This is the number the operator
     * is trying to zero, and the number a drag on the scope moves.
     */
    fun chromaCentroid(u: ByteArray, v: ByteArray, stride: Int = 2): FloatArray {
        var su = 0.0
        var sv = 0.0
        var n = 0
        val count = minOf(u.size, v.size)
        var i = 0
        while (i < count) {
            su += (u[i].toInt() and 0xFF) - 128
            sv += (v[i].toInt() and 0xFF) - 128
            n++
            i += stride
        }
        if (n == 0) return floatArrayOf(0f, 0f)
        return floatArrayOf((su / n / 128.0).toFloat(), (sv / n / 128.0).toFloat())
    }

    /**
     * Turns a move on the vectorscope into a change in camera gains.
     *
     * U is blue against luma and V is red against luma, so pulling the dot
     * towards blue means the image needs less blue, and the gain moves the
     * other way. Green is the reference and is left alone, which is what keeps
     * a colour move from also being an exposure move.
     */
    fun gainsFromChromaOffset(base: FloatArray, du: Float, dv: Float, strength: Float = 0.6f): FloatArray {
        if (base.size < 3) return base
        val out = floatArrayOf(
            (base[0] * (1f - dv * strength)).coerceAtLeast(0.05f),
            base[1],
            (base[2] * (1f - du * strength)).coerceAtLeast(0.05f)
        )
        val smallest = minOf(out[0], out[1], out[2])
        if (smallest > 0f) for (i in 0..2) out[i] /= smallest
        return out
    }

    /**
     * How far the preview has to be turned.
     *
     * A sensor is mounted at whatever angle suited the phone's assembly, not
     * at whatever angle the app is held, and Camera2 delivers frames in the
     * sensor's orientation without apology. RootEncoder used to do this sum
     * for the eight bit path and nothing did it for the ten bit one, which is
     * why a house appeared on its side in ten bit and upright in eight.
     *
     * @param sensorOrientation degrees, from the camera characteristics
     * @param displayRotation Surface.ROTATION_0/90/180/270 as degrees
     * @param frontFacing a front camera is mirrored, so its correction turns
     *        the other way
     */
    /**
     * The largest box of the picture's own shape that fits, and what is left
     * over for the keys.
     *
     * The shape is a parameter rather than 16:9, because it is not always 16:9.
     * An ultra wide is a physical sub-lens with a 4:3 sensor, and forcing its
     * picture into a 16:9 box is the squeeze this app shipped for six versions.
     * A lens that makes 4:3 is shown at 4:3; the rails simply get more room.
     *
     * This is the whole geometry of this camera in four numbers. A phone's
     * screen is about 20:9 and a broadcast picture is 16:9, so a margin exists
     * whether or not anything is put in it; the rails go there, which is how
     * thirty controls fit on screen without one of them sitting on the shot.
     *
     * Here rather than in the View because it is arithmetic, and arithmetic in
     * an onMeasure can only be checked by looking at a phone.
     *
     * @return width, height, and the margin left over across and down
     */
    fun pictureBox(
        availableWidth: Int,
        availableHeight: Int,
        aspect: Double = 16.0 / 9.0
    ): IntArray {
        if (availableWidth <= 0 || availableHeight <= 0 || aspect <= 0.0) {
            return intArrayOf(0, 0, 0, 0)
        }
        // Rounded, not truncated. Flooring loses up to a pixel on the derived
        // side, which at phone sizes is nothing and at small sizes is a real
        // change of shape: 320 wide at 2.39 floors to 133 and comes back as
        // 2.406, which is a squeeze nobody asked for arriving from a cast.
        var width = availableWidth
        var height = Math.round(width / aspect).toInt()
        if (height > availableHeight) {
            height = availableHeight
            width = Math.round(height * aspect).toInt()
        }
        return intArrayOf(width, height, availableWidth - width, availableHeight - height)
    }

    /**
     * The scale to apply AFTER rotating the preview, so the picture is never
     * stretched and never cropped.
     *
     * This is the arithmetic that has been wrong in this app for six attempts,
     * and the reason is that it was never written down anywhere it could be
     * checked. It lived inside an `onMeasure` or a `setTransform` call, where
     * the only way to test it is to hold a phone up to something rectangular
     * and squint.
     *
     * The mechanism a TextureView actually has:
     *
     *   1. it draws the camera buffer stretched to its own bounds, asking
     *      nobody — so before any transform the content occupies the view and
     *      is already distorted unless the two aspects happen to agree
     *   2. the transform matrix is then applied about the view's centre
     *
     * So the job is: rotate, then scale the result to the largest rectangle
     * with the buffer's true aspect that fits inside the view. Rotating by a
     * quarter turn swaps which of the view's sides the content now spans,
     * which is why the two scale factors are not the same number and why
     * getting one of them wrong looks exactly like a stretched picture.
     *
     * @param rotation degrees clockwise, 0/90/180/270
     * @param fill true to cover the view and crop, false to fit and leave bars
     * @return scaleX and scaleY, to postScale about the view's centre
     */
    fun previewFit(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int,
        fill: Boolean = false
    ): FloatArray {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return floatArrayOf(1f, 1f)
        }
        val turn = ((rotation % 360) + 360) % 360
        val swap = turn == 90 || turn == 270

        // What the picture's shape becomes once it has been turned.
        val finalAspect =
            if (swap) bufferHeight.toDouble() / bufferWidth
            else bufferWidth.toDouble() / bufferHeight

        var width = viewWidth.toDouble()
        var height = width / finalAspect
        val tooTall = height > viewHeight
        if (tooTall != fill) {
            height = viewHeight.toDouble()
            width = height * finalAspect
        }

        // The box the rotated content occupies before it is scaled: the view's
        // own rectangle, turned.
        val rotatedWidth = if (swap) viewHeight.toDouble() else viewWidth.toDouble()
        val rotatedHeight = if (swap) viewWidth.toDouble() else viewHeight.toDouble()

        return floatArrayOf(
            (width / rotatedWidth).toFloat(),
            (height / rotatedHeight).toFloat()
        )
    }

    /**
     * The shape the operator ends up looking at, for the one test that matters.
     *
     * If this does not equal the buffer's own aspect ratio (turned, if it was
     * turned) then the picture on the screen is stretched, whatever else is
     * right. Every previous attempt at this would have failed this check, and
     * none of them was ever asked it.
     */
    fun displayedAspect(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int,
        fill: Boolean = false
    ): Double {
        val scale = previewFit(viewWidth, viewHeight, bufferWidth, bufferHeight, rotation, fill)
        val turn = ((rotation % 360) + 360) % 360
        val swap = turn == 90 || turn == 270
        val rotatedWidth = if (swap) viewHeight.toDouble() else viewWidth.toDouble()
        val rotatedHeight = if (swap) viewWidth.toDouble() else viewHeight.toDouble()
        val shownWidth = scale[0] * rotatedWidth
        val shownHeight = scale[1] * rotatedHeight
        return if (shownHeight == 0.0) 0.0 else shownWidth / shownHeight
    }

    fun previewRotation(
        sensorOrientation: Int,
        displayRotation: Int,
        frontFacing: Boolean = false
    ): Int {
        val sign = if (frontFacing) -1 else 1
        return ((sensorOrientation - displayRotation * sign) + 360) % 360
    }

    /**
     * What the *camera* already did to the frame, before we were handed it.
     *
     * **This is the rotation bug, and it is why seven attempts at the angle all
     * failed.** Every one of them argued about `sensorOrientation` against the
     * display, which is the right formula — for a buffer that arrives as the
     * sensor read it. On this phone it does not. A `SurfaceTexture` carries a
     * transform matrix from its producer, a `TextureView` applies that matrix
     * before anything in this app runs, and the Pixel 7's camera puts a quarter
     * turn in it. So the correct angle was being worked out and then added to
     * one that was already there, and the picture came out a quarter turn wrong
     * no matter which way the arithmetic was pushed.
     *
     * His own numbers say it twice over: held across (`disp 90`) the formula
     * answers 0 and he had to dial `ROT` to 270; held upright (`disp 0`) it
     * answers 90 and he had to dial 270 again to reach 0. Both are the formula
     * **minus 90**, and 90 is exactly what the matrix below decodes to.
     *
     * The matrix is 4x4 column-major, as OpenGL wants it. Only the top-left
     * 2x2 carries rotation: index 0 and 1 are the first column, 4 and 5 the
     * second. A camera that has not turned anything still reports a vertical
     * flip, because a texture's origin is at the bottom and a screen's is at
     * the top — so that flip is divided out first and what is left is the turn.
     *
     * Dominance rather than equality, because the same matrix also carries the
     * crop, so the entries are near ±1 rather than exactly ±1.
     *
     * @return the producer's own clockwise rotation: 0, 90, 180 or 270
     */
    fun producerRotation(matrix: FloatArray): Int {
        if (matrix.size < 6) return 0
        // Divide out the standard vertical flip: R = M · flipY.
        var r00 = matrix[0]
        var r10 = matrix[1]
        val r01 = -matrix[4]
        val r11 = -matrix[5]
        // A matrix with nothing in it at all is a texture that has not had a
        // frame yet. Nothing has been turned, so nothing is taken off.
        if (kotlin.math.abs(r00) < 1e-4f && kotlin.math.abs(r10) < 1e-4f &&
            kotlin.math.abs(r01) < 1e-4f && kotlin.math.abs(r11) < 1e-4f
        ) return 0
        // THE SELFIE CAMERA, UPSIDE DOWN.
        //
        // A reflection is not a rotation, and this read one as the other. A
        // front camera whose producer mirrors the frame hands over a matrix
        // with a negative determinant, and the classification below saw its
        // negative first entry and answered 180 — so a quarter of a turn that
        // was never there was subtracted from the angle, and the selfie lens
        // came up upside down while the three rear lenses were right. The
        // mirror is taken off first and reported separately: R = Rot · flipX,
        // so R · flipX is the rotation on its own.
        if (mirrorOf(r00, r10, r01, r11)) {
            r00 = -r00
            r10 = -r10
        }
        return if (kotlin.math.abs(r00) >= kotlin.math.abs(r10)) {
            if (r00 >= 0f) 0 else 180
        } else {
            if (r10 < 0f) 90 else 270
        }
    }

    /**
     * Whether the camera handed over a *mirrored* frame.
     *
     * A determinant below zero is a reflection, and no amount of rotating
     * undoes one. This exists for two reasons: so [producerRotation] cannot
     * mistake a mirror for a half turn, and so the preview can put the mirror
     * back the other way — because this is a broadcast camera and the monitor
     * has to show what the wire is carrying. The encoder is fed the camera
     * buffer directly and never sees this matrix at all.
     */
    fun producerMirrored(matrix: FloatArray): Boolean {
        if (matrix.size < 6) return false
        val r00 = matrix[0]
        val r10 = matrix[1]
        val r01 = -matrix[4]
        val r11 = -matrix[5]
        if (kotlin.math.abs(r00) < 1e-4f && kotlin.math.abs(r10) < 1e-4f &&
            kotlin.math.abs(r01) < 1e-4f && kotlin.math.abs(r11) < 1e-4f
        ) return false
        return mirrorOf(r00, r10, r01, r11)
    }

    private fun mirrorOf(r00: Float, r10: Float, r01: Float, r11: Float): Boolean =
        (r00 * r11 - r01 * r10) < 0f

    /**
     * The buffer's shape **as the view already sees it**.
     *
     * A producer transform is applied to *texture coordinates*, not to the
     * view: a `TextureView` still draws its quad at the view's own size, so a
     * camera that transposes the frame hands over content whose width and
     * height have swapped while the quad has not. Everything downstream — the
     * fit, the squeeze readout, the box the picture is held in — has to be told
     * the swapped shape or it corrects an aspect that is no longer there.
     *
     * **This is the squash.** v76 took the camera's quarter turn off the angle
     * and the picture came up the right way round, which is why it looked so
     * nearly right — but the fit was still being computed against 1920x1080
     * when what had arrived was 1080x1920, so the picture was left in a narrow
     * strip down the middle instead of filling the frame edge to edge.
     *
     * @return width and height, swapped if the camera turned a quarter
     */
    fun effectiveBuffer(width: Int, height: Int, producerDegrees: Int): IntArray {
        val turn = ((producerDegrees % 360) + 360) % 360
        return if (turn == 90 || turn == 270) intArrayOf(height, width)
        else intArrayOf(width, height)
    }

    /**
     * The shape the picture ends up on screen, once every turn is counted.
     *
     * **This is the portrait strip.** The black box the picture sits in was
     * given the buffer's own shape — 16:9, always — and in landscape that is
     * right, because the camera's quarter turn and ours cancel and a 16:9 frame
     * comes out 16:9. Held upright they do not cancel: the total is a quarter,
     * the picture that arrives is 9:16, and a 9:16 picture fitted inside a 16:9
     * box is a narrow strip with black on all four sides.
     *
     * So the box is told the total, not the buffer. A phone held upright gets a
     * tall box and the picture fills its width, which is what every camera app
     * on a phone does and what he asked for.
     *
     * @param producerDegrees what the camera turned, [producerRotation]
     * @param appliedDegrees what we turned, including any by hand
     */
    fun shownAspect(
        bufferWidth: Int,
        bufferHeight: Int,
        producerDegrees: Int,
        appliedDegrees: Int
    ): Double {
        if (bufferWidth <= 0 || bufferHeight <= 0) return 16.0 / 9.0
        val total = ((producerDegrees + appliedDegrees) % 360 + 360) % 360
        return if (total == 90 || total == 270) bufferHeight.toDouble() / bufferWidth
        else bufferWidth.toDouble() / bufferHeight
    }

    /**
     * The angle to put on the preview, once the camera's own turn is taken off.
     *
     * On a phone whose camera turns nothing this is the old formula unchanged,
     * so nothing that worked stops working.
     */
    fun previewRotation(
        sensorOrientation: Int,
        displayRotation: Int,
        frontFacing: Boolean,
        producerDegrees: Int
    ): Int {
        val auto = previewRotation(sensorOrientation, displayRotation, frontFacing)
        return ((auto - producerDegrees) % 360 + 360) % 360
    }

    // --- fitting a picture into a view ----------------------------------------

    /**
     * Scale factors that fit a picture inside a view without distorting it.
     *
     * A TextureView stretches its buffer to its own bounds and asks nobody,
     * which is why a picture goes wrong the moment the two stop agreeing:
     * rotate the phone, or send the app away and bring it back, and the view
     * is resized while the buffer is not. The picture is then stretched, and
     * it stays stretched because nothing recalculates.
     *
     * The fix is a transform applied every time either one changes. These are
     * the two numbers that transform needs.
     *
     * @param fill true to cover the view and crop the overflow, false to fit
     *             the whole picture and leave bars
     * @return scaleX and scaleY about the view's centre
     */
    fun previewTransform(
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        fill: Boolean = false
    ): FloatArray {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return floatArrayOf(1f, 1f)
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        val videoAspect = videoWidth.toFloat() / videoHeight

        // The view already draws the buffer stretched to its bounds, so these
        // are corrections to that, not scales from the original size.
        val wider = videoAspect > viewAspect
        val correction = if (wider) videoAspect / viewAspect else viewAspect / videoAspect

        return if (wider == fill) {
            floatArrayOf(1f, correction)
        } else {
            floatArrayOf(correction, 1f)
        }
    }

    /**
     * The camera's own white balance preset nearest a colour temperature.
     *
     * Manual gains have now turned the picture green twice, and the reason is
     * structural rather than a bad number: the gains are only half of white
     * balance. The other half is the colour correction matrix, which differs
     * per illuminant, is calibrated per sensor, and is not something an app
     * can compute. Supplying our gains with somebody else's matrix leaves the
     * two disagreeing, and on a Bayer sensor that disagreement is green,
     * because green is the channel with twice the samples.
     *
     * The presets are the camera's own calibrated pairs. Fewer steps than a
     * continuous slider, and every one of them correct.
     *
     * @return a CONTROL_AWB_MODE value
     */
    fun awbPresetFor(kelvin: Int): Int = when {
        kelvin < 3000 -> 2   // INCANDESCENT, about 2700K
        kelvin < 4200 -> 3   // FLUORESCENT, about 4000K
        kelvin < 4800 -> 4   // WARM_FLUORESCENT
        kelvin < 6000 -> 5   // DAYLIGHT, about 5500K
        kelvin < 7000 -> 6   // CLOUDY_DAYLIGHT, about 6500K
        else -> 7            // TWILIGHT and above
    }

    /** What that preset actually is, for the number on screen. */
    fun kelvinForPreset(mode: Int): Int = when (mode) {
        2 -> 2700
        3 -> 4000
        4 -> 4600
        5 -> 5500
        6 -> 6500
        else -> 7500
    }

    // --- how sharp the frame is, where it matters -----------------------------

    /**
     * Sharpness over a region, by how fast brightness changes across it.
     *
     * A sharp edge is a large difference between neighbouring pixels; a soft
     * one is a small difference. Summing the square of those differences gives
     * a number that peaks exactly where focus does, which is what every
     * contrast detect system has used since the first one.
     *
     * Measured only inside the focus box, because sharpness over a whole frame
     * is dominated by whatever happens to be nearest the camera rather than by
     * the thing being focused on.
     *
     * @param bounds left, top, right, bottom as fractions of the frame
     */
    fun sharpness(argb: IntArray, width: Int, height: Int, bounds: FloatArray): Double {
        if (width <= 2 || height <= 2) return 0.0
        val x0 = (bounds[0] * width).toInt().coerceIn(0, width - 2)
        val y0 = (bounds[1] * height).toInt().coerceIn(0, height - 2)
        val x1 = (bounds[2] * width).toInt().coerceIn(x0 + 1, width - 1)
        val y1 = (bounds[3] * height).toInt().coerceIn(y0 + 1, height - 1)

        var total = 0.0
        var counted = 0
        var y = y0
        while (y < y1) {
            val row = y * width
            var x = x0
            while (x < x1) {
                val index = row + x
                if (index + 1 >= argb.size) break
                val a = luma(argb[index])
                val b = luma(argb[index + 1])
                val d = a - b
                total += d * d
                counted++
                x++
            }
            y++
        }
        // Normalised, so a bigger box does not read as a sharper one.
        return if (counted == 0) 0.0 else total / counted
    }

    private fun luma(p: Int): Double {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /**
     * Has focus drifted far enough to be worth moving for?
     *
     * Sharpness wanders with the light and with anything that moves in frame,
     * so a small drop is not a reason to touch the lens. A quarter down from
     * what it was when focus was set is a real change; less than that and the
     * right answer is to leave it alone, which is most of the time.
     */
    fun focusHasDrifted(reference: Double, current: Double, tolerance: Double = 0.75): Boolean =
        reference > 0.0 && current < reference * tolerance

    /**
     * Where the lens should be partway through a rack.
     *
     * Eased rather than linear: a rack that starts and stops abruptly reads as
     * a mistake, and the same move with soft ends reads as a decision. This is
     * the shape a focus puller's hand makes.
     */
    fun rackPosition(from: Float, to: Float, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return from + (to - from) * eased
    }

    // --- the waveform ---------------------------------------------------------

    /**
     * A waveform, column for column with the picture.
     *
     * The scale down the side matters far less than the alignment across it.
     * A waveform is read by looking at a part of the frame and then at the
     * same horizontal position on the trace, so column x of the trace has to
     * be column x of the image and nothing else. Keeping the widths equal and
     * drawing it over the picture is what makes that true by construction
     * rather than by careful arithmetic.
     *
     * @param channel 0 red, 1 green, 2 blue, 3 luma
     * @return counts, column major, [column * bins + bin], bin 0 is black
     */
    fun waveform(
        argb: IntArray,
        width: Int,
        height: Int,
        bins: Int = 128,
        channel: Int = 3,
        rowStride: Int = 2
    ): IntArray {
        val out = IntArray(width * bins)
        if (width <= 0 || height <= 0) return out

        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                val index = row + x
                if (index >= argb.size) break
                val p = argb[index]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val value = when (channel) {
                    0 -> r
                    1 -> g
                    2 -> b
                    else -> ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt()
                }
                val bin = (value * (bins - 1) / 255).coerceIn(0, bins - 1)
                out[x * bins + bin]++
                x++
            }
            y += rowStride
        }
        return out
    }

    /** Which traces are drawn. A set rather than a mode, so any combination works. */
    enum class WaveformChannel(val index: Int, val label: String) {
        LUMA(3, "Luma"),
        RED(0, "Red"),
        GREEN(1, "Green"),
        BLUE(2, "Blue")
    }

    // --- three way colour, applied in the camera ------------------------------

    /**
     * A wheel position as three channel offsets.
     *
     * Red, green and blue sit a hundred and twenty degrees apart, and the three
     * cosines of any angle sum to zero. That is not a coincidence to be worked
     * around, it is the property that makes a colour wheel a colour wheel: a
     * push towards any hue takes from the other two and leaves the brightness
     * where it was. Luminance is the master control's job, and only its job.
     *
     * @param angleDegrees 0 is red, 120 green, 240 blue
     * @param radius 0 at the centre, 1 at the rim
     */
    fun wheelToChannels(angleDegrees: Double, radius: Double): FloatArray {
        val r = radius.coerceIn(0.0, 1.0)
        val a = Math.toRadians(angleDegrees)
        return floatArrayOf(
            (r * Math.cos(a)).toFloat(),
            (r * Math.cos(a - 2.0 * Math.PI / 3.0)).toFloat(),
            (r * Math.cos(a - 4.0 * Math.PI / 3.0)).toFloat()
        )
    }

    /**
     * Lift, gamma and gain for one channel.
     *
     * The classic form, and the one a colourist's hands already know:
     *
     *     out = ((in * (gain - lift)) + lift) ^ (1 / gamma)
     *
     * Lift moves the bottom and leaves white alone. Gain moves the top and
     * leaves black alone. Gamma bends what is between them without touching
     * either end. Applied in that order they stay independent, which is the
     * whole reason the three exist rather than one.
     */
    fun gradeChannel(input: Double, lift: Double, gamma: Double, gain: Double): Double {
        val safeGamma = gamma.coerceIn(0.2, 5.0)
        val scaled = input * (gain - lift) + lift
        if (scaled <= 0.0) return 0.0
        return scaled.pow(1.0 / safeGamma).coerceIn(0.0, 1.0)
    }

    /**
     * The whole grade as three tone curves, ready for the camera.
     *
     * One curve per channel does the grade, and the log curve is composed into
     * the same pass rather than fighting it: the value is taken to scene light,
     * encoded in whatever curve is chosen, then graded. So there is still only
     * one tone curve on the request, still no GPU, and still no copy of any
     * frame. A three way grade costs exactly what log already cost, which is
     * nothing per frame.
     *
     * @return red, green and blue point arrays, each input, output, input, output
     */
    fun gradeCurves(
        curve: LogCurves.Curve,
        lift: FloatArray,
        gamma: FloatArray,
        gain: FloatArray,
        points: Int
    ): Array<FloatArray> {
        val n = points.coerceIn(2, 128)
        return Array(3) { channel ->
            val out = FloatArray(n * 2)
            for (i in 0 until n) {
                val input = i.toDouble() / (n - 1)
                // Display referred in, scene light, then the chosen curve.
                val linear = LogCurves.decode(LogCurves.Curve.REC709, input).coerceAtLeast(0.0)
                val encoded = LogCurves.encode(curve, linear).coerceIn(0.0, 1.0)
                val graded = gradeChannel(
                    encoded,
                    lift[channel].toDouble(),
                    gamma[channel].toDouble(),
                    gain[channel].toDouble()
                )
                out[i * 2] = input.toFloat()
                out[i * 2 + 1] = graded.toFloat()
            }
            out
        }
    }

    /** How far a wheel may push a channel before it stops being a trim. */
    const val LIFT_RANGE = 0.15
    const val GAMMA_RANGE = 0.5
    const val GAIN_RANGE = 0.4

    /** Wheel offsets and a master, as the three numbers gradeChannel wants. */
    fun liftFrom(channels: FloatArray, master: Double): FloatArray =
        FloatArray(3) { (channels[it] * LIFT_RANGE + master * LIFT_RANGE).toFloat() }

    fun gammaFrom(channels: FloatArray, master: Double): FloatArray =
        FloatArray(3) { (1.0 + channels[it] * GAMMA_RANGE + master * GAMMA_RANGE).toFloat() }

    fun gainFrom(channels: FloatArray, master: Double): FloatArray =
        FloatArray(3) { (1.0 + channels[it] * GAIN_RANGE + master * GAIN_RANGE).toFloat() }

    /** The grade that does nothing, for a reset. */
    fun neutralGrade(): Triple<FloatArray, FloatArray, FloatArray> = Triple(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f)
    )

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
     * The log curve with a LUT composed onto it, as three channel curves.
     *
     * **This is how a LUT reaches the wire at all.** The stream is whatever the
     * phone's tone mapper produced, and the tone mapper is the only thing in
     * the pipeline before the encoder that an app can change — a shader cannot
     * be put there, because a ten bit dynamic range profile is only legal
     * against a PRIVATE or P010 surface and a GL texture is neither.
     *
     * `TonemapCurve` takes a separate curve per channel, so the cube is walked
     * along its neutral axis and each channel's answer becomes that channel's
     * curve. What that carries is the LUT's **tone and its colour balance** —
     * which is most of what a display LUT does, and all of what makes a log
     * picture look wrong until it is applied.
     *
     * What it cannot carry is the rest of the cube: how the LUT treats a
     * saturated red differently from a grey of the same brightness. That is
     * three-dimensional by nature and a per-channel curve has one dimension.
     * So the monitor shows the cube exactly and the wire carries as much of it
     * as a tone curve can hold, and the app says so rather than pretending.
     *
     * @return red, green and blue, each as TonemapCurve's (in, out) pairs
     */
    fun toneCurveThroughCube(
        curve: LogCurves.Curve,
        cube: CubeLut?,
        points: Int
    ): Array<FloatArray> {
        val n = points.coerceIn(2, 128)
        val red = FloatArray(n * 2)
        val green = FloatArray(n * 2)
        val blue = FloatArray(n * 2)
        for (i in 0 until n) {
            val input = i.toFloat() / (n - 1)
            val linear = LogCurves.decode(LogCurves.Curve.REC709, input.toDouble())
                .coerceAtLeast(0.0)
            val logged = LogCurves.encode(curve, linear).coerceIn(0.0, 1.0).toFloat()
            val out = cube?.sample(logged, logged, logged)
                ?: floatArrayOf(logged, logged, logged)
            red[i * 2] = input; red[i * 2 + 1] = out[0].coerceIn(0f, 1f)
            green[i * 2] = input; green[i * 2 + 1] = out[1].coerceIn(0f, 1f)
            blue[i * 2] = input; blue[i * 2 + 1] = out[2].coerceIn(0f, 1f)
        }
        return arrayOf(red, green, blue)
    }

    /**
     * Focus as a fraction of the lens travel, stepped by a fixed amount.
     * The plus and minus at the ends of the fader exist because focus is the
     * one control where a finger is never precise enough.
     */
    fun stepFocus(fraction: Float, steps: Int): Float =
        (fraction + steps * FOCUS_STEP).coerceIn(0f, 1f)

    const val FOCUS_STEP = 0.01f

    // --- the focus box --------------------------------------------------------

    /** The smallest box, as a fraction of the picture's short side: about an eye. */
    const val BOX_MIN = 0.06f

    /**
     * How big the box is drawn, in pixels, for a size [t] and a picture [w] by [h].
     *
     * *"Pinch to enlarge or reduce the rectangle for the focus, so it can be
     * tiny until the full screen."* One number does both ends: [t] is the box's
     * side as a fraction of the picture's short side. Up to 1 it is a square,
     * which is what a focus mark on a face should be; past 1 it stops growing
     * across the short side, which it already fills, and goes on growing along
     * the long one until it is the whole picture. So the largest [t] is the
     * picture's own aspect, and there is no size at which the box is anything
     * but a square or the frame.
     *
     * @return half the width and half the height, in pixels
     */
    fun focusBoxHalves(t: Float, w: Int, h: Int): FloatArray {
        if (w <= 0 || h <= 0) return floatArrayOf(0f, 0f)
        val short = minOf(w, h).toFloat()
        val long = maxOf(w, h).toFloat()
        val size = t.coerceIn(BOX_MIN, long / short)
        val shortHalf = minOf(size, 1f) * short / 2f
        val longHalf = size * short / 2f
        return if (w >= h) floatArrayOf(longHalf, shortHalf) else floatArrayOf(shortHalf, longHalf)
    }

    /** The largest size [focusBoxHalves] will draw: the whole picture. */
    fun focusBoxMax(w: Int, h: Int): Float =
        if (w <= 0 || h <= 0) 1f else maxOf(w, h).toFloat() / minOf(w, h)

    /**
     * Where the box's centre may be, so the box never hangs off the picture.
     *
     * A box the size of the frame has one place it can be, the middle; a
     * small one can go nearly to the edge. Without this the region sent to the
     * camera is clipped on one side and the camera focuses on a smaller, off
     * centre patch than the one drawn.
     */
    fun clampBoxCentre(c: Float, halfFraction: Float): Float {
        val h = halfFraction.coerceIn(0f, 0.5f)
        return c.coerceIn(h, 1f - h)
    }

    /**
     * How far the sensor's picture is turned, clockwise, to stand upright on
     * this screen: Android's own formula for a camera preview, plus the
     * operator's quarter turns.
     *
     * The camera wants its focus region in the **sensor's** coordinates and a
     * tap arrives in the **screen's**. They are the same only when the phone
     * is held the one way the sensor is mounted: landscape, camera on the
     * left. Upright, a tap at the top of the screen is the sensor's left edge;
     * upside down it is the opposite corner; a front camera is also a mirror.
     */
    fun sensorToViewDegrees(sensor: Int, display: Int, front: Boolean, extraTurns: Int = 0): Int {
        val base = if (front) (sensor + display) % 360 else (sensor - display + 360) % 360
        return ((base + extraTurns * 90) % 360 + 360) % 360
    }

    /**
     * A region on the screen, as fractions, to the same region on the sensor.
     *
     * The inverse of turning the sensor's picture [degrees] clockwise (and
     * mirroring it, for a front camera) to get the screen's. Returned as
     * left, top, right, bottom, each 0..1 of the sensor's active array.
     */
    fun viewRegionToSensor(
        left: Float, top: Float, right: Float, bottom: Float,
        degrees: Int, mirrored: Boolean
    ): FloatArray {
        fun point(xIn: Float, y: Float): FloatArray {
            val x = if (mirrored) 1f - xIn else xIn
            return when (((degrees % 360) + 360) % 360) {
                90 -> floatArrayOf(y, 1f - x)
                180 -> floatArrayOf(1f - x, 1f - y)
                270 -> floatArrayOf(1f - y, x)
                else -> floatArrayOf(x, y)
            }
        }
        val a = point(left, top)
        val b = point(right, bottom)
        return floatArrayOf(
            minOf(a[0], b[0]).coerceIn(0f, 1f),
            minOf(a[1], b[1]).coerceIn(0f, 1f),
            maxOf(a[0], b[0]).coerceIn(0f, 1f),
            maxOf(a[1], b[1]).coerceIn(0f, 1f)
        )
    }

    /**
     * A region of the picture the camera streams, as fractions, to the same
     * region of the sensor's whole active array, as fractions.
     *
     * The sensor is 4:3 and the picture is a 16:9 cut out of its middle (the
     * camera crops to the stream's shape, centred), so the top of the picture
     * is not the top of the sensor. Taken as the same, a box near the top or
     * bottom of the picture focused on something a good way off it.
     */
    fun streamRegionToArray(
        region: FloatArray, streamW: Int, streamH: Int, arrayW: Int, arrayH: Int
    ): FloatArray {
        if (streamW <= 0 || streamH <= 0 || arrayW <= 0 || arrayH <= 0) return region.copyOf()
        val streamAspect = streamW.toFloat() / streamH
        val arrayAspect = arrayW.toFloat() / arrayH
        // The crop as fractions of the array: full along one axis, centred on the other.
        val (cw, ch) = if (streamAspect >= arrayAspect) 1f to (arrayAspect / streamAspect)
                       else (streamAspect / arrayAspect) to 1f
        val ox = (1f - cw) / 2f
        val oy = (1f - ch) / 2f
        return floatArrayOf(
            (ox + region[0] * cw).coerceIn(0f, 1f),
            (oy + region[1] * ch).coerceIn(0f, 1f),
            (ox + region[2] * cw).coerceIn(0f, 1f),
            (oy + region[3] * ch).coerceIn(0f, 1f)
        )
    }

    // --- metering -----------------------------------------------------------

    const val METER_FLOOR_DB = -54f

    /** Linear RMS to a 0..1 meter position on a dB scale. */
    fun rmsToMeterFraction(rms: Float): Float {
        if (rms <= 0.0000001f) return 0f
        val db = (20f * log10(rms)).coerceAtLeast(METER_FLOOR_DB)
        return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
    }

    /** Microseconds of sound in [samples] samples, exact to the microsecond. */
    fun samplesToUs(samples: Long, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else samples * 1_000_000L / sampleRate

    /** Microseconds of 16-bit mono PCM in [bytes]. */
    fun pcmDurationUs(bytes: Int, sampleRate: Int): Long = samplesToUs(bytes / 2L, sampleRate)

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
