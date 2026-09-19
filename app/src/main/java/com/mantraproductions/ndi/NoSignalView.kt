package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * What a monitor shows when there is nothing to show.
 *
 * A frozen frame is the worst possible answer. It looks exactly like a working
 * picture of a static scene, so an operator watching a stalled feed has no way
 * to tell, and finds out when the take is over. The slate is unmistakable and
 * covers the last frame completely.
 */
class NoSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    /** Shown under the words: which source was expected, if any. */
    var expected: String? = null
        set(value) { field = value; invalidate() }

    private val background = Paint().apply { color = Color.parseColor("#2B2F33") }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12C46A")
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.3f
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8C99A6")
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }

    init {
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        val cx = width / 2f
        val cy = height / 2f

        titlePaint.textSize = minOf(width, height) * 0.14f
        canvas.drawText("NDI", cx, cy, titlePaint)

        subPaint.textSize = minOf(width, height) * 0.045f
        canvas.drawText("NO SIGNAL", cx, cy + subPaint.textSize * 1.9f, subPaint)

        expected?.let {
            subPaint.textSize = minOf(width, height) * 0.032f
            canvas.drawText(
                "waiting for ${it.uppercase()}",
                cx, cy + subPaint.textSize * 5.5f, subPaint
            )
        }
    }
}
