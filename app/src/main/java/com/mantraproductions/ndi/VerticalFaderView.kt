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
 * A vertical fader with a plus at the top and a minus at the bottom.
 *
 * Vertical because four of them side by side fit across a phone in landscape,
 * which puts every manual control on screen at once. Reading them is then one
 * glance rather than four taps through a carousel.
 *
 * The plus and minus are not decoration. A finger on a fader is good to
 * perhaps one percent of its travel, and focus in particular needs finer than
 * that, so the ends step by a fixed amount and can be held down to repeat.
 * Every real camera has the same pair for the same reason.
 */
class VerticalFaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    var max: Int = 100
        set(value) { field = value.coerceAtLeast(1); progress = progress.coerceIn(0, field) }

    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, max)
            if (field != clamped) { field = clamped; invalidate() }
        }

    var label: String = ""
        set(value) { field = value; invalidate() }

    var valueText: String = ""
        set(value) { field = value; invalidate() }

    var accent: Int = FaderView.AMBER
        set(value) { field = value; invalidate() }

    /** Drawn dimmed when the control is on automatic; a touch takes it manual. */
    var automatic: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    /** Fired when a dimmed control is touched, so the caller can leave auto. */
    var onTouchedWhileAutomatic: (() -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2E35")
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C8894")
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        letterSpacing = 0.18f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
    }

    private val endZone get() = 46f * density
    private val trackTop get() = endZone + 18f * density
    private val trackBottom get() = height - endZone - 22f * density

    private var dragStartY = 0f
    private var dragStartProgress = 0
    private var dragging = false
    private var repeatDirection = 0

    private val repeater = object : Runnable {
        override fun run() {
            if (repeatDirection == 0) return
            step(repeatDirection)
            postDelayed(this, 60)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val top = trackTop
        val bottom = trackBottom
        val span = bottom - top
        val dim = if (automatic) 0.35f else 1f

        canvas.drawText(label.uppercase(), cx, 20f * density, labelPaint)

        signPaint.color = withAlpha(if (automatic) Color.parseColor("#7C8894") else accent, 255)
        canvas.drawText("+", cx, endZone - 8f * density, signPaint)
        canvas.drawText("\u2212", cx, height - 10f * density, signPaint)

        trackPaint.strokeWidth = 3f * density
        canvas.drawLine(cx, top, cx, bottom, trackPaint)

        // Grows upward: more of anything is up, which is how every fader reads.
        val fraction = progress.toFloat() / max
        val thumbY = bottom - span * fraction

        fillPaint.color = withAlpha(accent, (255 * dim).toInt())
        fillPaint.strokeWidth = 3f * density
        canvas.drawLine(cx, bottom, cx, thumbY, fillPaint)

        val glowRadius = 20f * density
        glowPaint.shader = RadialGradient(
            cx, thumbY, glowRadius,
            intArrayOf(withAlpha(accent, (120 * dim).toInt()), withAlpha(accent, 0)),
            floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, thumbY, glowRadius, glowPaint)

        thumbPaint.color = withAlpha(accent, (255 * dim).toInt())
        canvas.drawCircle(cx, thumbY, 11f * density, thumbPaint)

        valuePaint.alpha = (255 * dim).toInt()
        canvas.drawText(valueText, cx, bottom + 18f * density, valuePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A touch on a dimmed control means the operator wants it, so
                // the caller leaves automatic rather than making them find a
                // checkbox first.
                if (automatic) onTouchedWhileAutomatic?.invoke()

                when {
                    event.y < endZone -> startRepeating(+1)
                    event.y > height - endZone -> startRepeating(-1)
                    else -> {
                        dragging = true
                        dragStartY = event.y
                        dragStartProgress = progress
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val span = trackBottom - trackTop
                if (span <= 0) return true
                // Up is more, so the sign is inverted against screen coordinates.
                val delta = -(event.y - dragStartY) / span * max
                if (abs(event.y - dragStartY) > 4f * density) {
                    progress = (dragStartProgress + delta).toInt()
                    onChange?.invoke(progress)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRepeating()
                if (dragging) {
                    dragging = false
                    onRelease?.invoke(progress)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startRepeating(direction: Int) {
        repeatDirection = direction
        step(direction)
        // A pause before repeating, so a single tap is a single step.
        postDelayed(repeater, 350)
    }

    private fun stopRepeating() {
        repeatDirection = 0
        removeCallbacks(repeater)
        onRelease?.invoke(progress)
    }

    /** One step is a hundredth of the travel, or one unit on a short range. */
    private fun step(direction: Int) {
        val amount = maxOf(1, max / 100)
        progress += direction * amount
        onChange?.invoke(progress)
    }

    private fun withAlpha(colour: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(colour), Color.green(colour), Color.blue(colour))
}
