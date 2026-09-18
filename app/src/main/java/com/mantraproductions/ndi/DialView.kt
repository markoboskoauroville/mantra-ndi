package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * A round dial sized for a thumb rather than a mouse.
 *
 * Seekbars are the wrong control on a camera: they're a few pixels tall, they
 * sit under your hand while you're trying to see the image, and a small slip
 * jumps the value a long way. A dial gives a large target, and because it
 * tracks angular change rather than absolute position, your finger can wander
 * off centre mid-turn without the value leaping.
 *
 * Values are 0..[max] so it drops into the same places a SeekBar was.
 */
class DialView @JvmOverloads constructor(
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

    var accentColor: Int = Color.parseColor("#00E0E0")
        set(value) { field = value; invalidate() }

    /** Fired continuously while turning; [onRelease] once the finger lifts. */
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#263238")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#12171C")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()
    private var lastAngle = 0f
    private var dragging = false

    // Leaves a gap at the bottom so start and end of travel are distinguishable.
    private val startAngle = 135f
    private val sweepAngle = 270f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (112 * resources.displayMetrics.density).toInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val stroke = w * 0.09f
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        valuePaint.textSize = w * 0.20f
        labelPaint.textSize = w * 0.11f
        val inset = stroke / 2f + w * 0.02f
        arcRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        arcPaint.color = accentColor
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)

        val fraction = progress.toFloat() / max
        canvas.drawArc(arcRect, startAngle, sweepAngle * fraction, false, arcPaint)

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, arcRect.width() / 2f - arcPaint.strokeWidth, knobPaint)

        canvas.drawText(valueText, cx, cy + valuePaint.textSize / 3f, valuePaint)
        canvas.drawText(label, cx, cy + height * 0.30f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val angle = angleOf(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastAngle = angle
                // Stop a scrolling parent from stealing the gesture mid-turn.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                var delta = angle - lastAngle
                // Crossing the 180/-180 boundary would otherwise read as a huge jump.
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                if (abs(delta) > 90) return true

                lastAngle = angle
                val step = (delta / sweepAngle) * max
                progress = (progress + step).toInt().coerceIn(0, max)
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

    private fun angleOf(x: Float, y: Float): Float {
        val dx = x - width / 2f
        val dy = y - height / 2f
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }
}
