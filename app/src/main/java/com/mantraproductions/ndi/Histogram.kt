package com.mantraproductions.ndi

/**
 * Luminance histogram, computed from the preview's own luma plane.
 *
 * Deliberately not on the GPU and deliberately not on every pixel. A 1080p
 * frame is two million samples; reading every twelfth one along both axes
 * gives about fourteen thousand, which is statistically indistinguishable for
 * a 64 bucket histogram and costs a fraction of a millisecond. Precision that
 * nobody can see is precision spent for nothing.
 */
object Histogram {

    const val BUCKETS = 64

    /**
     * @param luma Y plane, one byte per pixel, values 0..255
     * @param width row length in bytes, which is the row stride, not the image width
     * @param height number of rows
     * @param stride how many pixels to skip; 12 reads roughly one pixel in 144
     */
    fun fromLuma(
        luma: ByteArray,
        width: Int,
        height: Int,
        stride: Int = 12
    ): IntArray {
        val buckets = IntArray(BUCKETS)
        if (width <= 0 || height <= 0 || stride <= 0) return buckets

        var y = 0
        while (y < height) {
            val rowStart = y * width
            var x = 0
            while (x < width) {
                val index = rowStart + x
                if (index >= luma.size) break
                val value = luma[index].toInt() and 0xFF
                buckets[value * BUCKETS / 256]++
                x += stride
            }
            y += stride
        }
        return buckets
    }

    /** Scales buckets to 0..1 against the tallest, which is what a display needs. */
    fun normalise(buckets: IntArray): FloatArray {
        val peak = buckets.maxOrNull() ?: 0
        if (peak <= 0) return FloatArray(buckets.size)
        return FloatArray(buckets.size) { buckets[it].toFloat() / peak }
    }

    /**
     * Fraction of samples at the very top of the scale. Above a few percent
     * the highlights are gone and no grade brings them back, which is the one
     * thing a histogram is for on set.
     */
    fun clippedHighlights(buckets: IntArray): Float {
        val total = buckets.sum()
        if (total == 0) return 0f
        return buckets[buckets.size - 1].toFloat() / total
    }

    /** The same at the bottom: shadows crushed to black carry no detail. */
    fun clippedShadows(buckets: IntArray): Float {
        val total = buckets.sum()
        if (total == 0) return 0f
        return buckets[0].toFloat() / total
    }

    /**
     * Where middle grey should sit for a given curve, as a bucket index, so the
     * histogram can mark it. Log footage looks wrong on a histogram until you
     * know that its grey is not in the middle.
     */
    fun middleGreyBucket(curve: LogCurves.Curve): Int =
        (LogCurves.middleGrey(curve) * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
}
