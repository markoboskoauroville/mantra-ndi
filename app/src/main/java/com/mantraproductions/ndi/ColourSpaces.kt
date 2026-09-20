package com.mantraproductions.ndi

/**
 * The half of a log format that this app had been ignoring.
 *
 * A log format is two things: a transfer function, which is the curve, and a
 * gamut, which is where its red, green and blue actually sit. The monitor
 * correction until now undid the curve and nothing else, applying one identical
 * 1D strip to all three channels. That cannot move a primary, so every colour
 * stayed where the camera's wide gamut put it and then got stretched by the
 * Rec.709 curve. Saturated colours went somewhere no real scene contains, which
 * is exactly why it looked like a cartoon rather than a picture.
 *
 * Undoing a log format properly is: curve back to scene light, then a matrix
 * from the camera's gamut to Rec.709, then the Rec.709 curve. The matrix is the
 * part that was missing, and no amount of improving the curve would have found
 * it.
 *
 * The matrices are derived here from each gamut's published primaries rather
 * than copied as coefficients, because a mistyped digit in a 3x3 is invisible
 * on inspection and shows up as a faint cast nobody can trace.
 */
object ColourSpaces {

    /** CIE xy chromaticities: red, green, blue, then white. */
    data class Gamut(
        val rx: Double, val ry: Double,
        val gx: Double, val gy: Double,
        val bx: Double, val by: Double,
        val wx: Double = 0.3127, val wy: Double = 0.3290
    )

    /**
     * Published primaries. The negative coordinates are not errors: these
     * gamuts deliberately reach outside the spectral locus so that the matrix
     * into a display space has room to work.
     */
    val REC709 = Gamut(0.640, 0.330, 0.300, 0.600, 0.150, 0.060)
    val S_GAMUT3_CINE = Gamut(0.766, 0.275, 0.225, 0.800, 0.089, -0.087)
    val V_GAMUT = Gamut(0.730, 0.280, 0.165, 0.840, 0.100, -0.030)
    val ARRI_WIDE_GAMUT_3 = Gamut(0.6840, 0.3130, 0.2210, 0.8480, 0.0861, -0.1020)
    val ARRI_WIDE_GAMUT_4 = Gamut(0.7347, 0.2653, 0.1424, 0.8576, 0.0991, -0.0308)
    val BLACKMAGIC_WIDE = Gamut(0.7177, 0.3171, 0.2280, 0.8616, 0.1006, -0.0820)

    /** Which gamut belongs with which curve. */
    fun gamutFor(curve: LogCurves.Curve): Gamut = when (curve) {
        LogCurves.Curve.SLOG3 -> S_GAMUT3_CINE
        LogCurves.Curve.VLOG -> V_GAMUT
        LogCurves.Curve.LOGC3 -> ARRI_WIDE_GAMUT_3
        LogCurves.Curve.LOGC4 -> ARRI_WIDE_GAMUT_4
        LogCurves.Curve.BMFILM -> BLACKMAGIC_WIDE
        LogCurves.Curve.REC709 -> REC709
    }

    /**
     * The 3x3 that takes linear RGB in this gamut to CIE XYZ.
     *
     * The standard construction: build a matrix from the primaries, then solve
     * for the three scale factors that make the gamut's own white land exactly
     * on its white point. Without that step the primaries are right and
     * everything neutral has a cast.
     */
    fun toXyz(g: Gamut): DoubleArray {
        fun xyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)

        val r = xyz(g.rx, g.ry)
        val gr = xyz(g.gx, g.gy)
        val b = xyz(g.bx, g.by)
        val w = xyz(g.wx, g.wy)

        val m = doubleArrayOf(
            r[0], gr[0], b[0],
            r[1], gr[1], b[1],
            r[2], gr[2], b[2]
        )
        val scale = multiply(invert(m), w)

        return doubleArrayOf(
            r[0] * scale[0], gr[0] * scale[1], b[0] * scale[2],
            r[1] * scale[0], gr[1] * scale[1], b[1] * scale[2],
            r[2] * scale[0], gr[2] * scale[1], b[2] * scale[2]
        )
    }

    /** Camera gamut to Rec.709, linear light both ends. */
    fun toRec709(from: Gamut): DoubleArray =
        concat(invert(toXyz(REC709)), toXyz(from))

    fun apply(m: DoubleArray, r: Double, g: Double, b: Double): DoubleArray =
        doubleArrayOf(
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b
        )

    // --- small 3x3 helpers, written out so the maths above reads plainly ----

    private fun multiply(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )

    private fun concat(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val row = i / 3
        val col = i % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]

        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (Math.abs(det) < 1e-12) {
            // Singular, which for a real gamut cannot happen; identity keeps
            // a caller alive rather than filling a LUT with infinities.
            return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det
        )
    }
}
