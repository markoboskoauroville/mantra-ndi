package com.mantraproductions.ndi

/**
 * A 3D lookup table, the format every grading system speaks.
 *
 * A 1D curve can only move brightness. A cube moves colour: each of its points
 * says where one particular red, green and blue combination should end up, so
 * it can carry a gamut change, a film emulation, or anything else a colourist
 * can build. That is why the correction had to become one of these rather than
 * a better curve.
 *
 * 33 points a side is the size every vendor ships and Resolve defaults to.
 * That is 35,937 entries, which is small enough to hold in a texture and dense
 * enough that interpolating between neighbours is invisible.
 */
class CubeLut(val size: Int, val data: FloatArray, val title: String = "") {

    init {
        require(size in 2..64) { "cube size $size" }
        require(data.size == size * size * size * 3) { "cube data ${data.size}" }
    }

    /** Index order is red fastest, then green, then blue, as .cube files are. */
    private fun at(ri: Int, gi: Int, bi: Int): Int =
        ((bi * size + gi) * size + ri) * 3

    /**
     * Tetrahedral interpolation, which is what Resolve and every on-set box
     * use, and which matters more than the table's size.
     *
     * Trilinear blends the eight corners of the cell, and along a neutral ramp
     * those eight do not agree, so greys drift slightly coloured and a gradient
     * shows faint banding. Tetrahedral picks the four corners of the tetrahedron
     * the point actually sits in, which keeps the diagonal exact, and the
     * diagonal is where every grey in the picture lives.
     */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val n = size - 1
        val rf = (r.coerceIn(0f, 1f)) * n
        val gf = (g.coerceIn(0f, 1f)) * n
        val bf = (b.coerceIn(0f, 1f)) * n

        val r0 = rf.toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val g0 = gf.toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val b0 = bf.toInt().coerceAtMost(n - 1).coerceAtLeast(0)

        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        fun c(ri: Int, gi: Int, bi: Int): FloatArray {
            val i = at(r0 + ri, g0 + gi, b0 + bi)
            return floatArrayOf(data[i], data[i + 1], data[i + 2])
        }

        val c000 = c(0, 0, 0)
        val c111 = c(1, 1, 1)

        // Which of the six tetrahedra the point falls in is decided by the
        // ordering of the three fractions, and each case blends a different
        // four corners.
        val (w0, rest) = when {
            dr > dg && dg > db ->
                (1 - dr) to listOf(c(1, 0, 0) to (dr - dg), c(1, 1, 0) to (dg - db))
            dr > db && db > dg ->
                (1 - dr) to listOf(c(1, 0, 0) to (dr - db), c(1, 0, 1) to (db - dg))
            db > dr && dr > dg ->
                (1 - db) to listOf(c(0, 0, 1) to (db - dr), c(1, 0, 1) to (dr - dg))
            dg > dr && dr > db ->
                (1 - dg) to listOf(c(0, 1, 0) to (dg - dr), c(1, 1, 0) to (dr - db))
            dg > db && db > dr ->
                (1 - dg) to listOf(c(0, 1, 0) to (dg - db), c(0, 1, 1) to (db - dr))
            else ->
                (1 - db) to listOf(c(0, 0, 1) to (db - dg), c(0, 1, 1) to (dg - dr))
        }

        val last = when {
            dr > dg && dg > db -> db
            dr > db && db > dg -> dg
            db > dr && dr > dg -> dg
            dg > dr && dr > db -> db
            dg > db && db > dr -> dr
            else -> dr
        }

        val out = FloatArray(3)
        for (i in 0 until 3) {
            var v = c000[i] * w0
            for ((corner, weight) in rest) v += corner[i] * weight
            v += c111[i] * last
            out[i] = v
        }
        return out
    }

    companion object {

        /**
         * Reads a .cube file.
         *
         * Tolerant on purpose: vendors ship these with comments, blank lines,
         * Windows line endings, a title, and a domain that is occasionally not
         * zero to one. A parser that insists on a tidy file rejects most real
         * LUTs.
         */
        fun parse(text: String): CubeLut? {
            var size = 0
            var title = ""
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>(35937 * 3)

            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    "LUT_1D_SIZE" -> return null   // a different thing entirely
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"')
                    "DOMAIN_MIN" -> for (i in 0 until 3) {
                        domainMin[i] = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 0f
                    }
                    "DOMAIN_MAX" -> for (i in 0 until 3) {
                        domainMax[i] = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 1f
                    }
                    else -> {
                        if (parts.size < 3) continue
                        val r = parts[0].toFloatOrNull() ?: continue
                        val g = parts[1].toFloatOrNull() ?: continue
                        val b = parts[2].toFloatOrNull() ?: continue
                        values.add(r); values.add(g); values.add(b)
                    }
                }
            }

            if (size < 2) return null
            val expected = size * size * size * 3
            if (values.size != expected) return null

            // Normalised into zero to one, since the shader samples a texture
            // and a texture has no other range.
            val data = FloatArray(expected)
            for (i in 0 until expected) {
                val channel = i % 3
                val span = (domainMax[channel] - domainMin[channel]).takeIf { it != 0f } ?: 1f
                data[i] = ((values[i] - domainMin[channel]) / span).coerceIn(0f, 1f)
            }
            return CubeLut(size, data, title)
        }

        /**
         * A .cube file as text, for handing to an edit.
         *
         * The same three steps the monitor correction uses, written out so a
         * grading system applies exactly what the operator was looking at on
         * set. A LUT that differs from the monitor is worse than none, because
         * the shot was lit against the monitor.
         */
        fun generate(from: LogCurves.Curve, to: LogCurves.Curve, size: Int): String {
            // 512 cubed is 134 million entries and several gigabytes of text.
            // A size nobody ships is a typo, not a request.
            require(size in 2..65) { "cube size $size" }
            val matrix = ColourSpaces.toRec709(ColourSpaces.gamutFor(from))
            val n = (size - 1).toDouble()
            return buildString {
                append("TITLE \"").append(from.displayName).append(" to ")
                    .append(to.displayName).append("\"\n")
                append("LUT_3D_SIZE ").append(size).append("\n")
                append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n\n")
                for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
                    val rec = ColourSpaces.apply(
                        matrix,
                        LogCurves.decode(from, ri / n),
                        LogCurves.decode(from, gi / n),
                        LogCurves.decode(from, bi / n)
                    )
                    for (c in 0 until 3) {
                        if (c > 0) append(' ')
                        append(
                            String.format(
                                java.util.Locale.US, "%.6f",
                                LogCurves.encode(to, rec[c].coerceAtLeast(0.0))
                                    .coerceIn(0.0, 1.0)
                            )
                        )
                    }
                    append('\n')
                }
            }
        }

        /** A name that says what the file is without opening it. */
        fun fileName(from: LogCurves.Curve, to: LogCurves.Curve, size: Int): String =
            "${from.displayName}_to_${to.displayName}_${size}.cube"
                .replace(' ', '_')
                .replace(".7", "7")

        /**
         * The default for a curve, built from what is known about it.
         *
         * Three steps, and the middle one is what a 1D strip could never do:
         * the log value goes back to scene light, a matrix carries it from the
         * camera's own gamut into Rec.709, and the Rec.709 curve encodes it for
         * a screen. Skip the matrix and saturated colour lands somewhere no
         * real scene contains.
         */
        /**
         * Generated tables, kept.
         *
         * A 33 cube is 35,937 entries and each one costs three decodes and
         * three encodes: about a hundred and eight thousand logarithms and
         * powers. That was being rebuilt on every call, which includes
         * every resume and every press of any overlay toggle, on the thread
         * drawing the picture. Once per curve per launch is enough, because
         * the table depends on nothing else.
         */
        private val generatedCache = HashMap<String, CubeLut>()

        fun generate(curve: LogCurves.Curve, size: Int = 33): CubeLut =
            synchronized(generatedCache) {
                generatedCache.getOrPut(curve.name + size) { build(curve, size) }
            }

        private fun build(curve: LogCurves.Curve, size: Int): CubeLut {
            val matrix = ColourSpaces.toRec709(ColourSpaces.gamutFor(curve))
            val data = FloatArray(size * size * size * 3)
            val n = (size - 1).toDouble()

            for (bi in 0 until size) {
                for (gi in 0 until size) {
                    for (ri in 0 until size) {
                        val lr = LogCurves.decode(curve, ri / n)
                        val lg = LogCurves.decode(curve, gi / n)
                        val lb = LogCurves.decode(curve, bi / n)

                        val rec = ColourSpaces.apply(matrix, lr, lg, lb)

                        val i = ((bi * size + gi) * size + ri) * 3
                        for (c in 0 until 3) {
                            data[i + c] = LogCurves
                                .encode(LogCurves.Curve.REC709, rec[c].coerceAtLeast(0.0))
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        }
                    }
                }
            }
            return CubeLut(size, data)
        }

        /** The table that changes nothing, for a reset. */
        fun identity(size: Int = 33): CubeLut {
            val data = FloatArray(size * size * size * 3)
            val n = (size - 1).toFloat()
            for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
                val i = ((bi * size + gi) * size + ri) * 3
                data[i] = ri / n
                data[i + 1] = gi / n
                data[i + 2] = bi / n
            }
            return CubeLut(size, data)
        }
    }
}
