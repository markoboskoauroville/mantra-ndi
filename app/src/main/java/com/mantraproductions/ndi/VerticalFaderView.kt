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

    /**
     * Draws a centre mark, for faders that correct a detected value rather
     * than set an absolute one. The mark is where the camera's own reading
     * sits, so the operator can always find their way back to it.
     */
    /**
     * Marks at known values, drawn across the track. Used for the white
     * balance references, where landing exactly on tungsten or daylight is
     * what makes two shots cut together.
     */
    var marks: List<Pair<Float, String>> = emptyList()
        set(value) { field = value; invalidate() }

    var showsCentre: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * Replaces the track with a live audio meter. The gain fader is the one
     * control where the thing being adjusted can be shown underneath the
     * finger doing the adjusting, so it is.
     */
    var meterLevel: Float? = null
        set(value) { field = value; postInvalidateOnAnimation() }

    /**
     * Whether the volume rocker is currently driving this column.
     *
     * The rocker moves one control and the screen never said which, so the
     * only way to find out was to press it and watch what changed. On a shot
     * that is not an acceptable way to find out.
     */
    var focused: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2E35")
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
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

    /**
     * Big ends. A professional adjusting to the last detail presses these far
     * more than they drag, so they are sized like the auto circles that used
     * to sit above the column rather than like a typographic plus.
     */
    // No end zones any more. The steppers are proper buttons in the column
    // below, where a thumb already is, rather than hit areas inside the track
    // that had to be aimed at and drawn small enough to fit.
    private val endZone get() = 10f * density

    /** Long press anywhere on the track to snap back to the detected value. */
    var onSnapToAuto: (() -> Unit)? = null
    private val trackTop get() = endZone + 6f * density
    private val trackBottom get() = height - endZone - 24f * density

    private var pressStart = 0L
    private var dragStartY = 0f
    private var dragStartProgress = 0
    private var dragging = false
    private var repeatDirection = 0

    private val snapRunnable = Runnable {
        dragging = false
        onSnapToAuto?.invoke()
    }

    private val repeater = object : Runnable {
        override fun run() {
            if (repeatDirection == 0) return
            step(repeatDirection)
            postDelayed(this, 60)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (focused) {
            // A hairline down the column rather than a filled panel: it has to
            // be unmistakable at a glance and must not sit on top of the shot.
            focusPaint.color = FaderView.AMBER
            focusPaint.strokeWidth = 1.5f * density
            val inset = 2f * density
            canvas.drawRoundRect(
                inset, inset, width - inset, height - inset,
                4f * density, 4f * density, focusPaint
            )
        }
        val cx = width / 2f
        val top = trackTop
        val bottom = trackBottom
        val span = bottom - top
        val dim = if (automatic) 0.35f else 1f

        // Written along the track rather than above it, turned a quarter so it
        // reads as if the phone were on its side. The heading then costs no
        // vertical room at all, which is what was pushing it into the buttons,
        // and a full word like SHUTTER fits where three letters used to.
        canvas.save()
        canvas.rotate(-90f, cx, (top + bottom) / 2f)
        // The label goes amber with the outline, so the two say the same thing
        // and either one alone is enough to read it.
        labelPaint.color = if (focused) FaderView.AMBER else Color.parseColor("#7C8894")
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            label.uppercase(), cx, (top + bottom) / 2f - 13f * density, labelPaint
        )
        canvas.restore()

        trackPaint.strokeWidth = 3f * density
        canvas.drawLine(cx, top, cx, bottom, trackPaint)

        // The gain fader shows the level it is adjusting, in the track itself.
        meterLevel?.let { meter ->
            meterPaint.strokeWidth = 9f * density
            meterPaint.color = when {
                meter >= 0.945f -> Color.parseColor("#FF2D1F")
                meter >= 0.86f -> Color.parseColor("#FFD400")
                else -> Color.parseColor("#12C46A")
            }
            meterPaint.alpha = 150
            canvas.drawLine(cx, bottom, cx, bottom - span * meter, meterPaint)
        }

        for ((fraction, _) in marks) {
            val markY = bottom - span * fraction
            centrePaint.strokeWidth = 1.5f * density
            canvas.drawLine(cx - 11f * density, markY, cx + 11f * density, markY, centrePaint)
        }

        if (showsCentre) {
            centrePaint.strokeWidth = 1.5f * density
            val centreY = bottom - span * 0.5f
            canvas.drawLine(cx - 9f * density, centreY, cx + 9f * density, centreY, centrePaint)
        }

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

                run {
                        dragging = true
                        dragStartY = event.y
                        dragStartProgress = progress
                        pressStart = System.currentTimeMillis()
                        // Two seconds of stillness on the track means "put this
                        // back where the camera had it", which is the reset
                        // that needs no button anywhere on screen.
                        postDelayed(snapRunnable, 2000)
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
                    // Any real movement cancels the long press.
                    removeCallbacks(snapRunnable)
                    progress = (dragStartProgress + delta).toInt()
                    onChange?.invoke(progress)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(snapRunnable)
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
    fun step(direction: Int) {
        val amount = maxOf(1, max / 100)
        progress += direction * amount
        onChange?.invoke(progress)
    }

    private fun withAlpha(colour: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(colour), Color.green(colour), Color.blue(colour))
}
