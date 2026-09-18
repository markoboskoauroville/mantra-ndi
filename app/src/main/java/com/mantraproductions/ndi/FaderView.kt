package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A horizontal fader that spans the screen.
 *
 * Long throw means fine control: across a phone in landscape a full sweep is
 * hundreds of pixels, so single ISO steps are reachable without a secondary
 * fine-adjust mode.
 *
 * Dragging is relative, never absolute. Touching the row does not snap the
 * value to your finger, which matters when the camera is live and a stray
 * touch would otherwise throw exposure across its whole range.
 *
 * Visually it is a hairline with a lit thumb, so it reads over a moving image
 * without blocking it.
 */
class FaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var max: Int = 100
        set(value) {
            field = value.coerceAtLeast(1)
            progress = progress.coerceIn(0, field)
        }

    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, max)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var label: String = ""
        set(value) { field = value; invalidate() }

    var valueText: String = ""
        set(value) { field = value; invalidate() }

    var accent: Int = AMBER
        set(value) { field = value; invalidate() }

    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2E35")
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C8894")
        textSize = 11f * density
        letterSpacing = 0.22f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        textSize = 17f * density
        textAlign = Paint.Align.RIGHT
    }

    private var dragStartX = 0f
    private var dragStartProgress = 0
    private var dragging = false

    private val sidePadding get() = 30f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (78 * density).toInt()
        setMeasuredDimension(
            resolveSize((240 * density).toInt(), widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = sidePadding
        val right = width - sidePadding
        val trackY = height * 0.72f
        val span = right - left

        canvas.drawText(label.uppercase(), left, height * 0.32f, labelPaint)
        canvas.drawText(valueText, right, height * 0.34f, valuePaint)

        trackPaint.strokeWidth = 3f * density
        canvas.drawLine(left, trackY, right, trackY, trackPaint)

        val fraction = progress.toFloat() / max
        val thumbX = left + span * fraction

        fillPaint.color = accent
        fillPaint.strokeWidth = 3f * density
        canvas.drawLine(left, trackY, thumbX, trackY, fillPaint)

        // A soft halo rather than a hard dot: legible over a bright image,
        // unobtrusive over a dark one.
        val glowRadius = 22f * density
        glowPaint.shader = RadialGradient(
            thumbX, trackY, glowRadius,
            intArrayOf(withAlpha(accent, 130), withAlpha(accent, 0)),
            floatArrayOf(0.25f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(thumbX, trackY, glowRadius, glowPaint)

        thumbPaint.color = if (isEnabled) accent else Color.parseColor("#4A5058")
        // Pixel sized: a thumb you can actually land on with a thumb.
        canvas.drawCircle(thumbX, trackY, 11f * density, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartX = event.x
                dragStartProgress = progress
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.x - dragStartX
                // Claim the gesture from the scrolling parent only once the
                // movement is clearly sideways, so vertical scrolling still works
                // when a finger lands on a fader on the way past.
                if (abs(dx) > 8f * density) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val span = width - sidePadding * 2
                if (span <= 0) return true
                val delta = (dx / span) * max
                progress = (dragStartProgress + delta).toInt()
                onChange?.invoke(progress)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun withAlpha(colour: Int, alpha: Int) =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

    companion object {
        val AMBER: Int = Color.parseColor("#E7A44C")
    }
}
