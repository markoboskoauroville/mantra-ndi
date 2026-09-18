package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Record button: an outlined circle when idle, filled red with the running
 * time inside once recording.
 *
 * Putting the counter inside the button means the one thing you check
 * mid-take is where your thumb already is, rather than somewhere else on
 * screen competing with the image.
 */
class RecordButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var recording: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                elapsedSeconds = 0
                invalidate()
            }
        }

    var elapsedSeconds: Long = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1F0F")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        contentDescription = "Record"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (76 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        ringPaint.strokeWidth = 3f * density
        val radius = min(cx, cy) - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, radius, ringPaint)

        if (recording) {
            canvas.drawCircle(cx, cy, radius - 5f * density, fillPaint)
            textPaint.textSize = radius * 0.46f
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            val text = String.format("%02d:%02d", minutes, seconds)
            canvas.drawText(text, cx, cy + textPaint.textSize / 3f, textPaint)
        } else {
            canvas.drawCircle(cx, cy, radius * 0.62f, fillPaint)
        }
    }
}
