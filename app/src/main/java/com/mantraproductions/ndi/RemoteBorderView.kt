package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A thin red hairline around the picture, meaning the picture is not ours.
 *
 * In remote mode every control on screen reaches a camera in another room, and
 * that is a dangerous thing to forget. The frame says so continuously, in
 * peripheral vision, without covering anything.
 *
 * Distinct from the tally border on purpose: tally is about what a mixer is
 * doing with this source and can be any of three colours, while this is about
 * where the image comes from and is one hairline. They can both be true at
 * once, so they are two views and this one sits inside the other.
 *
 * It never reaches the audio meter. That is guaranteed by the layout rather
 * than by drawing order: this view is constrained to begin below the meter, so
 * there is no z ordering, animation or future edit that can put it on top.
 */
class RemoteBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF2D1F")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D1F")
        textSize = 9f * density
        letterSpacing = 0.22f
    }

    /** The camera being driven, written into the corner of the frame. */
    var sourceName: String = ""
        set(value) { field = value; invalidate() }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = 1.5f * density
        val inset = paint.strokeWidth
        canvas.drawRect(inset, inset, width - inset, height - inset, paint)

        if (sourceName.isNotEmpty()) {
            canvas.drawText(
                "REMOTE  ${sourceName.uppercase()}",
                10f * density,
                height - 9f * density,
                labelPaint
            )
        }
    }
}
