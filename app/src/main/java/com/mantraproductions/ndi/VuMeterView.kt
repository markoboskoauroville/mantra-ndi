package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max

/**
 * Hairline audio meter down the edge of the picture.
 *
 * Deliberately thin: on a camera the picture is what you are looking at, and a
 * meter only needs to answer "is there audio, and is it clipping". The scale
 * is dB rather than linear, because linear spends most of its length on levels
 * nobody ever uses.
 *
 * Peak hold falls back slowly so a transient clip stays visible long enough to
 * notice, which is the whole reason anybody glances at a meter mid-take.
 *
 * This is the meter from the old build, unchanged in its arithmetic. What has
 * changed is where its samples come from: one reader on the microphone now,
 * rather than a meter and an encoder taking turns.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55000000")
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
    }

    /** 0..1, already dB-scaled. */
    private var level = 0f
    private var peak = 0f
    private var peakHoldFrames = 0

    /** True while nothing is reading the microphone at all. */
    var dead: Boolean = false
        set(value) { if (field != value) { field = value; postInvalidateOnAnimation() } }

    /** @param rms linear RMS, 0..1 */
    fun setLevel(rms: Float) {
        val db = if (rms <= 0.0000001f) MIN_DB else (20f * log10(rms)).coerceAtLeast(MIN_DB)
        val normalised = ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)

        // Fast attack so peaks register, slow release so the meter is readable.
        level = if (normalised > level) normalised else level * 0.82f + normalised * 0.18f

        if (normalised >= peak) {
            peak = normalised
            peakHoldFrames = PEAK_HOLD_FRAMES
        } else if (peakHoldFrames > 0) {
            peakHoldFrames--
        } else {
            peak = max(level, peak - 0.012f)
        }
        postInvalidateOnAnimation()
    }

    fun reset() {
        level = 0f
        peak = 0f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // −6 and −18 marked, because those are the two numbers anybody is
        // actually looking for: headroom left, and whether it is up at all.
        for (db in intArrayOf(-6, -18)) {
            val y = h * (1f - ((db - MIN_DB) / -MIN_DB))
            canvas.drawLine(0f, y, w, y, scalePaint)
        }

        if (dead) return

        val top = h * (1f - level)
        levelPaint.color = when {
            level > 0.93f -> Color.parseColor("#FF3B30")
            level > 0.80f -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#00E0A0")
        }
        canvas.drawRect(0f, top, w, h, levelPaint)

        if (peak > 0.01f) {
            val peakY = h * (1f - peak)
            canvas.drawRect(0f, peakY, w, peakY + PEAK_LINE_PX, peakPaint)
        }
    }

    private companion object {
        const val MIN_DB = -54f
        const val PEAK_HOLD_FRAMES = 45
        const val PEAK_LINE_PX = 2f
    }
}
