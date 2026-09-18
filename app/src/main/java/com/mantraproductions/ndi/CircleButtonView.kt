package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A ring. Nothing inside it.
 *
 * The whole control is one stroked circle whose colour carries the state:
 * white at rest, red while recording, amber while a panel it opens is open.
 * No fill, no inner dot, no icon. On top of a live image that reads instantly
 * and hides none of the frame.
 */
class CircleButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    /** Ring colour. Changing this is the entire visual state of the control. */
    var ringColor: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** A faint outer halo, used to mark the active state without adding fill. */
    var glow: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (66 * density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        ringPaint.strokeWidth = 2.2f * density
        val radius = min(cx, cy) - ringPaint.strokeWidth * 2f

        if (glow) {
            glowPaint.strokeWidth = 1f * density
            glowPaint.color = Color.argb(
                70, Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor)
            )
            canvas.drawCircle(cx, cy, radius + 6f * density, glowPaint)
        }

        ringPaint.color = if (isPressed) {
            Color.argb(150, Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor))
        } else {
            ringColor
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    companion object {
        val IDLE: Int = Color.parseColor("#F2F4F6")
        val RECORDING: Int = Color.parseColor("#FF3B2F")
        val ACTIVE: Int = FaderView.AMBER
    }
}
