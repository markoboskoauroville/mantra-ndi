package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Record: an outlined circle with a red dot in it when idle, filled red with
 * the running time inside once recording.
 *
 * Putting the counter inside the button means the one thing you check mid-take
 * is where your thumb already is, rather than somewhere else on screen
 * competing with the image.
 *
 * It sits at the top of the right rail rather than being a key like the
 * others, and that is on purpose: it is the only control on this camera whose
 * state you must be able to read without reading anything.
 */
class RecordButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var recording: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Dark when there is nothing to record — no camera, or no microphone. */
    var dead: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var elapsedSeconds: Long = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
        )
    }

    init {
        isClickable = true
        contentDescription = "Record"
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        ringPaint.strokeWidth = 2f * density
        ringPaint.color = if (dead) Color.parseColor("#3A3E42") else Color.parseColor("#C8CDD2")
        fillPaint.color = when {
            dead -> Color.parseColor("#4A2420")
            else -> Color.parseColor("#FF1F0F")
        }
        val radius = min(cx, cy) - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, radius, ringPaint)

        if (recording && !dead) {
            canvas.drawCircle(cx, cy, radius - 3f * density, fillPaint)
            textPaint.textSize = radius * 0.46f
            val text = String.format(
                "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60
            )
            canvas.drawText(text, cx, cy + textPaint.textSize / 3f, textPaint)
        } else {
            canvas.drawCircle(cx, cy, radius * 0.60f, fillPaint)
        }
    }
}
