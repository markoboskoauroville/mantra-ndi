package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Audio level as a hairline across the top of the screen, the way TTT mini
 * shows it.
 *
 * Two jobs in one line of pixels. On screen it is a meter that costs no room
 * and hides nothing. Left visible while the app is in the background it is the
 * tell that the camera is still running, which matters when a phone is
 * recording in a pocket or on a stand and nothing else on screen says so.
 *
 * Grows from the left. Amber through the working range, red at the top, so the
 * colour alone answers the only question worth asking at a glance.
 */
class HairlineVuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FFFFFF")
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var level = 0f
    private var peak = 0f
    private var holdFrames = 0

    /** @param fraction 0..1, already dB scaled by [Mechanism.rmsToMeterFraction] */
    fun setLevel(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        // Fast attack so a transient registers, slow release so it stays readable.
        level = if (f > level) f else level * 0.80f + f * 0.20f
        if (f >= peak) {
            peak = f
            holdFrames = PEAK_HOLD
        } else if (holdFrames > 0) {
            holdFrames--
        } else {
            peak = maxOf(level, peak - 0.015f)
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
        canvas.drawRect(0f, 0f, w, h, trackPaint)

        // TTT mini's reading, which is the one already learned: green while
        // there is headroom, yellow as it runs out, red when it is gone.
        levelPaint.color = when {
            level >= PEAKING -> Color.parseColor("#FF2D1F")
            level >= NEARLY -> Color.parseColor("#FFD400")
            else -> Color.parseColor("#12C46A")
        }
        canvas.drawRect(0f, 0f, w * level, h, levelPaint)

        if (peak > 0.01f) {
            val x = w * peak
            canvas.drawRect(x - 1.5f, 0f, x + 1.5f, h, levelPaint)
        }
    }

    private companion object {
        const val PEAK_HOLD = 40

        /** Roughly the last 1.5 dB, which is where a digital peak actually bites. */
        const val PEAKING = 0.945f

        /** About 6 dB of headroom left. */
        const val NEARLY = 0.86f
    }
}
