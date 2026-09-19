package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The clock, its state, and where it came from.
 *
 * Laid out the way a timecode display on any professional monitor is laid out:
 * the number large on the left, because it is the thing being read, and the
 * small print stacked to its right where it can be checked without taking the
 * eye off the number.
 *
 *     10:22:33:14   SYC
 *                   MARKO PHONE
 *
 * The source name matters once there is more than one device generating. Two
 * cameras following two different masters look identical if all you show is a
 * running number, and they will stay looking identical until the edit.
 *
 * Colour and the three letters say the same thing twice on purpose. Colour
 * alone fails for anybody who cannot separate green from white; three small
 * letters alone are unreadable at arm's length on a gimbal.
 */
class TimecodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Sync(val label: String, val colour: Int) {
        /** Stamping frames; this device is the clock. */
        MASTER("MST", Color.parseColor("#12C46A")),

        /** Following somebody else's clock. */
        FOLLOWING("SYC", Color.parseColor("#F2F4F6")),

        /** A clock of its own, agreeing with nothing. */
        INTERNAL("INT", Color.parseColor("#8C99A6"))
    }

    private val density = resources.displayMetrics.density

    var timecode: String = "--:--:--:--"
        set(value) { if (field != value) { field = value; invalidate() } }

    var sync: Sync = Sync.INTERNAL
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Which device the clock came from. Empty when it came from nowhere. */
    var sourceName: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Height of the timecode digits in sp. */
    var sizeSp: Float = 16f
        set(value) { field = value; requestLayout(); invalidate() }

    /** A half transparent plate behind it, as a broadcast burn-in has. */
    var showBackground: Boolean = true
        set(value) { field = value; invalidate() }

    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val status = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.18f
    }
    private val source = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.06f
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Half transparent black, the standard burn-in plate: dark enough to
        // read white digits over a bright sky, light enough not to hide the
        // shot underneath it.
        color = Color.argb(128, 0, 0, 0)
    }

    private val padding get() = 6f * density
    private val gap get() = 8f * density

    private fun digitSize() = sizeSp * density
    private fun smallSize() = (sizeSp * 0.42f * density).coerceAtLeast(7f * density)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        digits.textSize = digitSize()
        status.textSize = smallSize()
        source.textSize = smallSize()

        val numberWidth = digits.measureText("00:00:00:00")
        val smallWidth = maxOf(
            status.measureText("MST"),
            source.measureText(sourceName.ifEmpty { " " })
        )
        val width = (padding * 2 + numberWidth + gap + smallWidth).toInt()

        // Two small lines stacked must never be shorter than the number beside
        // them, or the plate crops the device name on a small setting.
        val smallStack = smallSize() * 2.6f
        val height = (padding * 2 + maxOf(digitSize() * 1.15f, smallStack)).toInt()

        setMeasuredDimension(
            resolveSize(width, widthSpec),
            resolveSize(height, heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        digits.textSize = digitSize()
        status.textSize = smallSize()
        source.textSize = smallSize()

        if (showBackground) {
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                3f * density, 3f * density, plate
            )
        }

        digits.color = sync.colour
        status.color = sync.colour
        // The source name is deliberately quieter than the state: it is
        // reference, not a warning.
        source.color = Color.parseColor("#9AA6B2")

        // The number sits on the vertical centre; the two small lines straddle
        // it, so the block reads as one thing rather than three.
        val baseline = height / 2f - (digits.descent() + digits.ascent()) / 2f
        canvas.drawText(timecode, padding, baseline, digits)

        val smallX = padding + digits.measureText("00:00:00:00") + gap
        val centre = height / 2f
        canvas.drawText(sync.label, smallX, centre - smallSize() * 0.25f, status)
        if (sourceName.isNotEmpty()) {
            canvas.drawText(
                sourceName.uppercase(), smallX, centre + smallSize() * 1.05f, source
            )
        }
    }
}
