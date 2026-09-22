package com.mantraproductions.ndi

/**
 * Colour temperature, as a continuous fader, without going green.
 *
 * **Read v65 before changing anything here.** White balance has gone green
 * twice in this project, and the reason was structural rather than a bad
 * number: *gains are only half of white balance*. The other half is a colour
 * correction matrix, calibrated per sensor and per illuminant. Supplying our
 * gains beside somebody else's matrix leaves the two disagreeing, and on a
 * Bayer sensor that disagreement reads as green, because green is the channel
 * with twice the samples. No slider position undid it, because the slider was
 * only ever moving one of the two halves.
 *
 * v65's answer was to give up the sweep and use the camera's six presets,
 * which move both halves together. That was the right trade at the time and it
 * is still the fallback here. But the conclusion drawn with it — that the
 * matrix "is not something an app can compute" — was too pessimistic, and this
 * file is why.
 *
 * A sensor that can produce a DNG must publish its own calibration: two
 * reference illuminants, and for each of them a colour matrix and a forward
 * matrix measured by the people who built it. Interpolating between those two
 * in **mired** is exactly what the DNG specification says to do and what every
 * raw converter has always done. Both halves then come out of *one*
 * calculation, from the sensor's own numbers, so they cannot disagree — which
 * is the only thing that was ever wrong.
 *
 * Nothing here touches Android. It is arithmetic, and arithmetic that has cost
 * this project two releases belongs somewhere it can be tested.
 */
object WhiteBalance {

    /**
     * The fader's travel: **tungsten at the left, daylight at the right.**
     *
     * It ran 2000K to 10000K, and most of that was travel nobody wants and
     * nothing can honour. His phone publishes its calibration at **2856K and
     * 6504K** — Standard A and D65 — and beyond those two anchors there is
     * nothing left to interpolate between, so the ends of the old sweep were
     * the same clamped matrix over and over while the number went on moving.
     *
     * 3200K is tungsten and 5600K is daylight, the two numbers a camera
     * operator actually works in; 6500K is the top because that is where the
     * measurement stops. Every point on the fader is now inside the sensor's
     * own calibrated span.
     */
    const val COOLEST_KELVIN = 3200
    const val WARMEST_KELVIN = 6500

    /**
     * Mired, the unit white balance actually behaves in.
     *
     * A hundred Kelvin at the tungsten end is a visible change and a hundred
     * Kelvin at the daylight end is nothing at all. Reciprocal degrees are even
     * to the eye, which is why every colour meter ever made reads in them, and
     * why the fader travels in them rather than in Kelvin.
     */
    fun mired(kelvin: Int): Double = 1_000_000.0 / kelvin.coerceAtLeast(1)

    /** Where a temperature sits on the fader, 0..1, evenly to the eye. */
    fun travel(kelvin: Int): Float {
        val cool = mired(COOLEST_KELVIN)
        val warm = mired(WARMEST_KELVIN)
        return (((cool - mired(kelvin)) / (cool - warm)).toFloat()).coerceIn(0f, 1f)
    }

    /** The temperature at a point on the fader. */
    fun kelvinAt(position: Float): Int {
        val cool = mired(COOLEST_KELVIN)
        val warm = mired(WARMEST_KELVIN)
        val m = cool - (cool - warm) * position.coerceIn(0f, 1f)
        return (1_000_000.0 / m).toInt().coerceIn(COOLEST_KELVIN, WARMEST_KELVIN)
    }

    /** Rounded the way a colour meter reads, so the number stops twitching. */
    fun format(kelvin: Int): String {
        val step = if (kelvin < 4000) 50 else 100
        return "${(kelvin + step / 2) / step * step}K"
    }

    // --- the chromaticity of a light at a temperature -------------------------

    /**
     * CIE 1931 x,y for a **black body** at [kelvin], by Kim's cubic.
     *
     * This is a filament: a tungsten lamp really is a black body, and at 2856K
     * this lands on Standard A to four decimal places.
     */
    fun planckianXy(kelvin: Int): DoubleArray {
        val t = kelvin.toDouble().coerceIn(1667.0, 25000.0)
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000.0) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t <= 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.8733867 * x2 + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x, y)
    }

    /**
     * CIE 1931 x,y on the **daylight** locus at [kelvin].
     *
     * Daylight is not a black body and it matters here. The sky is lit by a
     * filament *and* scattered by air, and the result sits measurably above the
     * Planckian locus — about 0.005 in y at 6500K, which is a green cast, which
     * is the exact fault this app has shipped twice. D50, D55 and D65 are points
     * on this curve and not on the other one, so this is what "daylight" on a
     * white balance dial has always meant.
     */
    fun daylightXy(kelvin: Int): DoubleArray {
        val t = kelvin.toDouble().coerceIn(4000.0, 25000.0)
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 7000.0) {
            0.244063 + 0.09911e3 / t + 2.9678e6 / t2 - 4.6070e9 / t3
        } else {
            0.237040 + 0.24748e3 / t + 1.9018e6 / t2 - 2.0064e9 / t3
        }
        val y = -3.000 * x * x + 2.870 * x - 0.275
        return doubleArrayOf(x, y)
    }

    /**
     * The chromaticity this fader means at [kelvin]: a filament at the warm end,
     * the sky at the cool end, and a crossfade between them.
     *
     * The two loci are about 0.007 in y apart where they meet, and a step that
     * size part way along a fader is a visible lurch in the picture's colour. So
     * they are blended across 3500K to 4500K — the band where a real light is
     * neither one thing nor the other anyway — and each end of the fader is
     * exactly the standard it should be.
     */
    fun chromaticity(kelvin: Int): DoubleArray {
        val black = planckianXy(kelvin)
        if (kelvin <= CROSSFADE_LOW) return black
        val day = daylightXy(kelvin)
        if (kelvin >= CROSSFADE_HIGH) return day
        val t = (kelvin - CROSSFADE_LOW).toDouble() / (CROSSFADE_HIGH - CROSSFADE_LOW)
        return doubleArrayOf(
            black[0] * (1 - t) + day[0] * t,
            black[1] * (1 - t) + day[1] * t
        )
    }

    private const val CROSSFADE_LOW = 3500
    private const val CROSSFADE_HIGH = 4500

    /** The same light as XYZ, scaled to Y = 1. */
    fun planckianXyz(kelvin: Int): DoubleArray {
        val (x, y) = chromaticity(kelvin).let { it[0] to it[1] }
        if (y <= 0.0) return doubleArrayOf(0.9642, 1.0, 0.8249)
        return doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
    }

    // --- interpolating the sensor's own calibration ---------------------------

    /**
     * How far [kelvin] sits between two calibrated illuminants, 0..1 in mired.
     *
     * 0 is entirely the first illuminant, 1 entirely the second. Clamped,
     * because beyond either end the measurement stops being a measurement and
     * extrapolating a colour matrix is how a picture goes magenta in the
     * corners of its range.
     */
    fun blend(kelvin: Int, kelvin1: Int, kelvin2: Int): Double {
        val m = mired(kelvin)
        val m1 = mired(kelvin1)
        val m2 = mired(kelvin2)
        if (m1 == m2) return 0.0
        return ((m - m1) / (m2 - m1)).coerceIn(0.0, 1.0)
    }

    /** Two 3x3 matrices, row-major, mixed. */
    fun mix(a: FloatArray, b: FloatArray, t: Double): FloatArray {
        if (a.size < 9 || b.size < 9) return if (a.size >= 9) a.copyOf(9) else FloatArray(9)
        return FloatArray(9) { i -> (a[i] * (1.0 - t) + b[i] * t).toFloat() }
    }

    /** Row-major 3x3 times a 3-vector. */
    fun apply(m: FloatArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )

    /** Row-major 3x3 times 3x3. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += a[r * 3 + k].toDouble() * b[k * 3 + c]
            out[r * 3 + c] = sum.toFloat()
        }
        return out
    }

    /**
     * The white balance gains for a light at [kelvin], from the sensor's own
     * colour matrices.
     *
     * The colour matrix maps CIE XYZ to raw sensor RGB. So the raw the sensor
     * would record from a neutral surface under that light is the matrix
     * applied to that light's XYZ, and the gains that put it back to neutral
     * are its reciprocal.
     *
     * Normalised so the smallest is exactly 1, because these are sensor gains
     * and a gain below unity is not a thing a sensor can be asked for.
     *
     * @return red, green-even, green-odd, blue
     */
    fun gains(kelvin: Int, colourMatrix: FloatArray): FloatArray {
        if (colourMatrix.size < 9) return floatArrayOf(1f, 1f, 1f, 1f)
        val raw = apply(colourMatrix, planckianXyz(kelvin))
        // A matrix that answers zero or less for a channel is not a matrix we
        // can invert, and a refused request stops the camera rather than being
        // ignored. Neutral is the safe answer.
        if (raw.any { it <= 1e-6 }) return floatArrayOf(1f, 1f, 1f, 1f)
        val r = 1.0 / raw[0]
        val g = 1.0 / raw[1]
        val b = 1.0 / raw[2]
        val smallest = minOf(r, g, b)
        if (smallest <= 0.0) return floatArrayOf(1f, 1f, 1f, 1f)
        return floatArrayOf(
            (r / smallest).toFloat(),
            (g / smallest).toFloat(),
            (g / smallest).toFloat(),
            (b / smallest).toFloat()
        )
    }

    /**
     * XYZ at D50 to linear sRGB, Bradford adapted. A constant of the standard.
     *
     * The forward matrices land in XYZ at D50 because that is what the DNG
     * specification fixes them to; this is the one step from there to the space
     * Camera2's transform is defined in.
     */
    val SRGB_FROM_XYZ_D50 = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f
    )

    /**
     * The colour correction matrix to send with those gains: sensor RGB to
     * linear sRGB, from the sensor's own forward matrix.
     *
     * **This is the half that was missing.** It is computed from the same
     * interpolation as the gains, out of the same measurement, so the two
     * cannot disagree — and that disagreement, not any particular number, is
     * what made the picture green.
     */
    fun transform(forwardMatrix: FloatArray): FloatArray {
        if (forwardMatrix.size < 9) return IDENTITY.copyOf()
        return multiply(SRGB_FROM_XYZ_D50, forwardMatrix)
    }

    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** A 3x3 row-major inverse, or null for a matrix that has none. */
    fun invert(m: FloatArray): FloatArray? {
        if (m.size < 9) return null
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return null
        return floatArrayOf(
            ((e * i - f * h) / det).toFloat(),
            ((c * h - b * i) / det).toFloat(),
            ((b * f - c * e) / det).toFloat(),
            ((f * g - d * i) / det).toFloat(),
            ((a * i - c * g) / det).toFloat(),
            ((c * d - a * f) / det).toFloat(),
            ((d * h - e * g) / det).toFloat(),
            ((b * g - a * h) / det).toFloat(),
            ((a * e - b * d) / det).toFloat()
        )
    }

    // --- the anchor: the camera's own answer, and the calibration's shape ----

    /**
     * **This is the green, and why a third attempt at it is not a fourth
     * guess.**
     *
     * v65 moved the gains and left the matrix. v77 computed both from the
     * sensor's published calibration, which is right in principle and still
     * wrong on his phone by whatever the absolute model is out by — and an
     * absolute error in white balance has exactly one colour, because green is
     * the channel a Bayer sensor has twice as many of.
     *
     * So the fader stops being absolute. The camera's own automatic white
     * balance is a measurement, made by the people who tuned this ISP, and it
     * is reported in every capture result: the gains it chose and the matrix it
     * chose. That pair is the **anchor**. The calibration is then used for the
     * only thing it is unarguably good for — the *shape* of the change from one
     * temperature to another — and the fader carries the camera's own answer
     * along that shape.
     *
     * At the anchor the picture is exactly what auto was showing, to the last
     * digit, so there is nothing left for a cast to hide in; moving away from
     * it moves both halves by the same interpolation, so the two still cannot
     * disagree. It is the same discipline as `M`: leave auto from where auto
     * had got to, and the picture does not jump.
     */
    fun shiftGains(
        measured: FloatArray,
        modelAtAnchor: FloatArray,
        modelAtWanted: FloatArray
    ): FloatArray {
        if (measured.size < 4 || modelAtAnchor.size < 4 || modelAtWanted.size < 4) {
            return normaliseGains(modelAtWanted)
        }
        val out = FloatArray(4)
        for (i in 0..3) {
            val from = modelAtAnchor[i]
            val to = modelAtWanted[i]
            out[i] = if (from > 1e-6f) measured[i] * (to / from) else measured[i]
        }
        return normaliseGains(out)
    }

    /**
     * The same shift, for the other half.
     *
     * `T(k) = measured · model(anchor)⁻¹ · model(k)` — the camera's own matrix
     * at the anchor, carried along the calibration's own change. Null when the
     * model cannot be inverted, which is a calibration not worth trusting.
     */
    fun shiftTransform(
        measured: FloatArray,
        modelAtAnchor: FloatArray,
        modelAtWanted: FloatArray
    ): FloatArray? {
        val back = invert(modelAtAnchor) ?: return null
        return multiply(measured, multiply(back, modelAtWanted))
    }

    /** Sensor gains as the camera wants them: nothing below unity. */
    fun normaliseGains(gains: FloatArray): FloatArray {
        if (gains.size < 4) return floatArrayOf(1f, 1f, 1f, 1f)
        val smallest = gains.min()
        if (smallest <= 0f) return floatArrayOf(1f, 1f, 1f, 1f)
        return FloatArray(4) { gains[it] / smallest }
    }

    /**
     * Which temperature the camera's own gains amount to, so the fader can
     * start where auto had got to and say a number for it.
     *
     * Matched on the ratios rather than the absolute gains, because the gains
     * are normalised and the ratios are what carry the colour. Searched in
     * mired, one step at a time, over the fader's own travel: the range is
     * fifteen stops of nothing and a hundred and sixty steps, so a search is
     * both exact and cheaper than thinking about it.
     */
    fun anchorKelvin(measured: FloatArray, colourMatrix: (Int) -> FloatArray): Int {
        if (measured.size < 4) return 5600
        val mR = measured[0].toDouble()
        val mG = measured[1].toDouble()
        val mB = measured[3].toDouble()
        if (mG <= 0.0 || mR <= 0.0 || mB <= 0.0) return 5600
        val wantRed = mR / mG
        val wantBlue = mB / mG

        var best = COOLEST_KELVIN
        var bestError = Double.MAX_VALUE
        val cool = mired(COOLEST_KELVIN)
        val warm = mired(WARMEST_KELVIN)
        var m = warm
        while (m <= cool + 0.5) {
            val kelvin = (1_000_000.0 / m).toInt().coerceIn(COOLEST_KELVIN, WARMEST_KELVIN)
            val model = gains(kelvin, colourMatrix(kelvin))
            val g = model[1].toDouble()
            val red = if (g > 0.0) model[0] / g else 0.0
            val blue = if (g > 0.0) model[3] / g else 0.0
            if (red > 0.0 && blue > 0.0) {
                val error = sq(kotlin.math.ln(red / wantRed)) + sq(kotlin.math.ln(blue / wantBlue))
                if (error < bestError) { bestError = error; best = kelvin }
            }
            m += 1.0
        }
        return best
    }

    private fun sq(x: Double) = x * x

    // --- the fallback: the camera's own presets -------------------------------

    /**
     * The presets, in Kelvin, as Android defines them.
     *
     * Used when a sensor publishes no calibration of its own — then there is
     * nothing to interpolate, and v65's answer stands: six correct steps beat a
     * continuous sweep that is wrong everywhere but its ends. The index is
     * mapped to Camera2's constant by the caller, so this file stays pure.
     */
    val PRESETS = intArrayOf(2700, 3000, 4000, 5500, 6500, 7500, 12000)

    /** Which preset is nearest [kelvin], in mired, among those [available]. */
    fun nearestPreset(kelvin: Int, available: IntArray = IntArray(PRESETS.size) { it }): Int {
        val usable = available.filter { it in PRESETS.indices }
        if (usable.isEmpty()) return -1
        val m = mired(kelvin)
        return usable.minByOrNull { kotlin.math.abs(mired(PRESETS[it]) - m) } ?: -1
    }

    /**
     * The Kelvin a DNG reference illuminant code stands for.
     *
     * The codes are EXIF's, which Camera2 passes through unchanged. Anything
     * unrecognised is treated as daylight rather than guessed at, because a
     * wrong anchor tilts the whole fader.
     */
    fun kelvinForIlluminant(code: Int): Int = when (code) {
        1 -> 6504    // Daylight
        2 -> 4100    // Fluorescent
        3 -> 2856    // Tungsten
        4 -> 5500    // Flash
        9 -> 5500    // Fine weather
        10 -> 6504   // Cloudy
        11 -> 7504   // Shade
        12 -> 6430   // Daylight fluorescent
        13 -> 4874   // Day white fluorescent
        14 -> 4230   // Cool white fluorescent
        15 -> 3450   // White fluorescent
        17 -> 2856   // Standard A
        18 -> 4874   // Standard B
        19 -> 6774   // Standard C
        20 -> 5503   // D55
        21 -> 6504   // D65
        22 -> 7504   // D75
        23 -> 5003   // D50
        24 -> 3200   // ISO studio tungsten
        else -> 6504
    }
}
