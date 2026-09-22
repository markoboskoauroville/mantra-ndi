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
     * The largest 16:9 box that fits, and what is left over for the keys.
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
    fun pictureBox(availableWidth: Int, availableHeight: Int): IntArray {
        if (availableWidth <= 0 || availableHeight <= 0) return intArrayOf(0, 0, 0, 0)
        var width = availableWidth
        var height = width * 9 / 16
        if (height > availableHeight) {
            height = availableHeight
            width = height * 16 / 9
        }
        return intArrayOf(width, height, availableWidth - width, availableHeight - height)
    }

    fun previewRotation(
        sensorOrientation: Int,
        displayRotation: Int,
        frontFacing: Boolean = false
    ): Int {
        val sign = if (frontFacing) -1 else 1
        return ((sensorOrientation - displayRotation * sign) + 360) % 360
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
