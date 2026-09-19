package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Tally light drawn as a border around the whole image, the way a camera
 * operator expects to see it: visible in peripheral vision without covering
 * anything, and unmistakable at a glance.
 *
 * States follow vision mixer convention, which is what vMix and the rest
 * already send over NDI:
 *   red    on program, you are live
 *   amber  on preview, you are next
 *   green  connected to a receiver but neither
 *   none   nobody is watching this source
 */
class TallyBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * What the border means, in the order a camera operator cares about.
     *
     * Yellow is somebody watching: a monitor has opened this source, so the
     * camera is being seen even though it is not cut. Red is on air. Green
     * was wrong for watched, because green everywhere else in this app and on
     * every mixer means safe, and being watched is not safe.
     */
    enum class State { OFF, CONNECTED, PREVIEW, PROGRAM }

    var state: State = State.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        // Purely an indicator, never swallow a touch meant for the image.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val colour = when (state) {
            State.PROGRAM -> Color.parseColor("#FF1F0F")
            State.PREVIEW -> Color.parseColor("#FFC400")
            State.CONNECTED -> Color.parseColor("#FFC400")
            State.OFF -> return
        }
        // Program gets a heavier border; being live should not be subtle.
        val widthDp = if (state == State.PROGRAM) 8f else 5f
        paint.strokeWidth = widthDp * resources.displayMetrics.density
        paint.color = colour
        val inset = paint.strokeWidth / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, paint)
    }
}
