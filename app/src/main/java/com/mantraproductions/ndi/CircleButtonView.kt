package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A ring, with optional text inside it.
 *
 * The ring itself carries state through its colour alone: white at rest, red
 * while recording. Nothing is filled, so the frame behind stays visible.
 *
 * When there is text it is sized to the circle rather than the other way
 * round. The running time starts as one digit and is drawn enormous; it only
 * shrinks when the clock actually needs more room. A fixed size would have to
 * be small enough for the longest string it might ever hold, which means it is
 * too small for the string it holds almost all of the time.
 */
class CircleButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    var ringColor: Int = IDLE
        set(value) { if (field != value) { field = value; invalidate() } }

    var glow: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Drawn inside the ring, scaled to fill it. Empty means an empty ring. */
    var centerText: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /** A bare glyph with no ring around it, for the gear. */
    var showRing: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * Red while the camera is being asked something and white once it answers.
     * An A that does nothing visible for most of a second reads as a dead
     * button, and the operator presses it again.
     */
    var busy: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                ringColor = if (value) RECORDING else IDLE
                invalidate()
            }
        }

    /** A glyph for the ring when it has no text, such as the settings mark. */
    var symbol: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val bounds = Rect()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (66 * density).toInt()
        val size = min(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
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
        if (showRing) canvas.drawCircle(cx, cy, radius, ringPaint)

        val text = centerText.ifEmpty { symbol }
        if (text.isEmpty()) return

        // The largest square that fits inside the circle is the diameter over
        // root two; a little less leaves the glyphs off the stroke.
        // With no ring there is nothing to stay clear of, so the glyph fills.
        val usable = if (showRing) radius * 1.30f else radius * 1.9f
        textPaint.color = ringColor
        fitTextTo(text, usable)
        textPaint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, cx, cy - bounds.exactCenterY(), textPaint)
    }

    /**
     * Two passes rather than a search: text width scales linearly with size, so
     * measuring once at a reference size gives the exact multiplier, and the
     * second measure only corrects for hinting.
     */
    private fun fitTextTo(text: String, available: Float) {
        val reference = 100f
        textPaint.textSize = reference
        val measured = textPaint.measureText(text)
        if (measured <= 0f) return
        var size = reference * available / measured
        textPaint.textSize = size
        val check = textPaint.measureText(text)
        if (check > available) {
            size *= available / check
            textPaint.textSize = size
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
