package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    /**
     * Audio level, 0..1, drawn as an arc around the same ring.
     *
     * It begins at the bottom of the circle and travels all the way round, so
     * full scale arrives back where it started. Nothing new appears on screen
     * to show audio: the control you already look at simply fills.
     */
    var level: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** Set false on the ring that is not metering. */
    var showsLevel: Boolean = false

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val levelRect = RectF()
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

        if (showsLevel && level > 0.005f) {
            levelPaint.strokeWidth = ringPaint.strokeWidth
            // Amber through most of the travel, red as it closes on the top of
            // the scale, which is also where the arc meets its own start.
            levelPaint.color = when {
                level > 0.94f -> Color.parseColor("#FF3B2F")
                level > 0.82f -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#E7A44C")
            }
            levelRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            // 90 degrees is the bottom of the circle on this canvas; sweeping
            // negative runs anticlockwise so the arc climbs the left side first.
            canvas.drawArc(levelRect, 90f, -360f * level, false, levelPaint)
        }
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
