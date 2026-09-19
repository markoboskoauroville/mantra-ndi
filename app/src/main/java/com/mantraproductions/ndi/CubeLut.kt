package com.mantraproductions.ndi

import java.io.InputStream

/**
 * A 33×33×33 colour lookup table, the format every grading system speaks.
 *
 * The one-dimensional strip this app used before could only bend each channel
 * on its own. That is a curve, not a look: it cannot rotate a hue, cannot pull
 * saturation out of a highlight, and cannot do any of the things a real
 * transform does, so a log picture corrected by it comes out with the contrast
 * roughly right and the colour visibly wrong. Three channels each pushed
 * independently is exactly how a picture ends up looking like a cartoon.
 *
 * A cube is the honest structure: for every combination of red, green and blue
 * it stores what that combination should become. Thirty-three steps per axis is
 * the size Resolve writes by default and the size every on-set box expects.
 *
 * Sampling is tetrahedral rather than trilinear. A cube divides into six
 * tetrahedra and interpolating within the one containing the sample keeps
 * straight lines in the source straight in the result. Trilinear does not, and
 * the error shows exactly where it is least wanted: on skin, and on a clean
 * ramp from black to white, which is where banding appears.
 */
object CubeLut {

    const val SIZE = 33

    /**
     * A cube as a flat array of triples, red fastest, as .cube files store it.
     * Length is SIZE^3 * 3.
     */
    class Table(val size: Int, val data: FloatArray) {

        init {
            require(data.size == size * size * size * 3) { "cube data does not match its size" }
        }

        private fun at(r: Int, g: Int, b: Int, channel: Int): Float {
            val ri = r.coerceIn(0, size - 1)
            val gi = g.coerceIn(0, size - 1)
            val bi = b.coerceIn(0, size - 1)
            return data[((bi * size + gi) * size + ri) * 3 + channel]
        }

        /**
         * Tetrahedral interpolation, the method a grading system uses.
         *
         * The unit cube around the sample is split into six tetrahedra by the
         * ordering of the three fractional parts. Whichever ordering holds
         * picks four corners, and the result is their weighted sum. The
         * ordering test is the whole algorithm.
         */
        fun sample(red: Float, green: Float, blue: Float): FloatArray {
            val scale = size - 1f
            val x = (red.coerceIn(0f, 1f)) * scale
            val y = (green.coerceIn(0f, 1f)) * scale
            val z = (blue.coerceIn(0f, 1f)) * scale

            val r0 = Math.floor(x.toDouble()).toInt()
            val g0 = Math.floor(y.toDouble()).toInt()
            val b0 = Math.floor(z.toDouble()).toInt()

            val fr = x - r0
            val fg = y - g0
            val fb = z - b0

            val out = FloatArray(3)
            for (c in 0 until 3) {
                val c000 = at(r0, g0, b0, c)
                val c111 = at(r0 + 1, g0 + 1, b0 + 1, c)

                out[c] = when {
                    fr >= fg && fg >= fb ->
                        c000 + (at(r0 + 1, g0, b0, c) - c000) * fr +
                            (at(r0 + 1, g0 + 1, b0, c) - at(r0 + 1, g0, b0, c)) * fg +
                            (c111 - at(r0 + 1, g0 + 1, b0, c)) * fb

                    fr >= fb && fb >= fg ->
                        c000 + (at(r0 + 1, g0, b0, c) - c000) * fr +
                            (at(r0 + 1, g0, b0 + 1, c) - at(r0 + 1, g0, b0, c)) * fb +
                            (c111 - at(r0 + 1, g0, b0 + 1, c)) * fg

                    fb >= fr && fr >= fg ->
                        c000 + (at(r0, g0, b0 + 1, c) - c000) * fb +
                            (at(r0 + 1, g0, b0 + 1, c) - at(r0, g0, b0 + 1, c)) * fr +
                            (c111 - at(r0 + 1, g0, b0 + 1, c)) * fg

                    fg >= fr && fr >= fb ->
                        c000 + (at(r0, g0 + 1, b0, c) - c000) * fg +
                            (at(r0 + 1, g0 + 1, b0, c) - at(r0, g0 + 1, b0, c)) * fr +
                            (c111 - at(r0 + 1, g0 + 1, b0, c)) * fb

                    fg >= fb && fb >= fr ->
                        c000 + (at(r0, g0 + 1, b0, c) - c000) * fg +
                            (at(r0, g0 + 1, b0 + 1, c) - at(r0, g0 + 1, b0, c)) * fb +
                            (c111 - at(r0, g0 + 1, b0 + 1, c)) * fr

                    else ->
                        c000 + (at(r0, g0, b0 + 1, c) - c000) * fb +
                            (at(r0, g0 + 1, b0 + 1, c) - at(r0, g0, b0 + 1, c)) * fg +
                            (c111 - at(r0, g0 + 1, b0 + 1, c)) * fr
                }
            }
            return out
        }

    }

    /**
     * Reads a .cube file.
     *
     * Deliberately forgiving about everything except the numbers. Real files
     * carry comments, a TITLE, a DOMAIN_MIN and DOMAIN_MAX, blank lines and
     * whatever line endings the machine that wrote them used, and a parser
     * that refuses one of those refuses half the LUTs in the world.
     */
    fun parse(stream: InputStream): Table? = try {
        var size = 0
        val values = ArrayList<Float>(SIZE * SIZE * SIZE * 3)
        var domainMin = 0f
        var domainMax = 1f

        stream.bufferedReader().forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "DOMAIN_MIN" -> domainMin = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                "DOMAIN_MAX" -> domainMax = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                "TITLE", "LUT_1D_SIZE", "LUT_IN_VIDEO_RANGE" -> Unit
                else -> {
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            values.add(r); values.add(g); values.add(b)
                        }
                    }
                }
            }
        }

        if (size <= 1) return null
        if (values.size != size * size * size * 3) return null

        // Scaled into 0..1 if the file declares a different domain, which some
        // scopes and cameras do.
        val span = (domainMax - domainMin).takeIf { it != 0f } ?: 1f
        val data = FloatArray(values.size) { i ->
            ((values[i] - domainMin) / span).coerceIn(0f, 1f)
        }
        Table(size, data)
    } catch (e: Exception) {
        null
    }

    /**
     * The correction for a log curve, as a real cube.
     *
     * Used when nothing has been uploaded for that curve. Built by taking each
     * cube corner back to scene light through the curve and re-encoding it for
     * a display, which is the same transform the strip did, but now living in
     * a structure that a vendor LUT can replace point for point.
     *
     * This is a technically correct inverse and not a look. A camera maker's
     * own LUT carries their rendering intent on top of it, which is why the
     * option to upload one matters.
     */
    fun generate(curve: LogCurves.Curve, size: Int = SIZE): Table {
        val data = FloatArray(size * size * size * 3)
        val scale = size - 1f
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    data[i] = correct(curve, r / scale)
                    data[i + 1] = correct(curve, g / scale)
                    data[i + 2] = correct(curve, b / scale)
                    i += 3
                }
            }
        }
        return Table(size, data)
    }

    private fun correct(curve: LogCurves.Curve, coded: Float): Float {
        val linear = LogCurves.decode(curve, coded.toDouble()).coerceAtLeast(0.0)
        return LogCurves.encode(LogCurves.Curve.REC709, linear)
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    /** The cube that changes nothing, for checking a pipeline end to end. */
    fun identity(size: Int = SIZE): Table {
        val data = FloatArray(size * size * size * 3)
        val scale = size - 1f
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    data[i] = r / scale
                    data[i + 1] = g / scale
                    data[i + 2] = b / scale
                    i += 3
                }
            }
        }
        return Table(size, data)
    }
}
