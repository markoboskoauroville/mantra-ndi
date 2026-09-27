package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Record: a white circle when idle, red when recording, and nothing inside
 * (v88). *"Remove the running number inside the record button, because it is
 * now duplicated"* — the timecode at the bottom middle says it.
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

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
            dead -> Color.parseColor("#3A3E42")
            recording -> Color.parseColor("#FF1F0F")
            else -> Color.WHITE
        }
        val radius = min(cx, cy) - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius - 3f * density, fillPaint)
    }
}
