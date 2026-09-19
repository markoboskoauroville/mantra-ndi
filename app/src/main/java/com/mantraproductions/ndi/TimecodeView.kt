package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The burn-in block: the clock, and everything a person needs to read beside it.
 *
 * Laid out the way a data burn-in is laid out and the way a deck's status
 * display is: one column, everything flush left, most important at the top.
 * Nothing is pushed to the right, because a reader's eye returns to the same
 * left edge for every line and anything set to the right of a variable width
 * number moves whenever the number does.
 *
 *     10:22:33:14
 *     SYNC EXT   MARKO PHONE
 *     RECORDING
 *     V-LOG   3840x2160   25p
 *
 * Four lines, each answering a question that gets asked on set: what time is
 * it, whose clock is that, what is this camera doing, and what is it writing.
 */
class TimecodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Sync(val label: String, val colour: Int) {
        /** This device is the clock the others follow. */
        MASTER("SYNC MST", Color.parseColor("#12C46A")),

        /** Following another device's clock. */
        EXTERNAL("SYNC EXT", Color.parseColor("#F2F4F6")),

        /** Its own clock, agreeing with nothing else. */
        INTERNAL("SYNC INT", Color.parseColor("#8C99A6"))
    }

    /** What the camera is doing, in the words a deck would use. */
    enum class Status(val label: String, val colour: Int) {
        IDLE("STANDBY", Color.parseColor("#8C99A6")),
        STREAMING("LIVE", Color.parseColor("#12C46A")),
        RECORDING("RECORDING", Color.parseColor("#FF4436")),
        BOTH("LIVE / RECORDING", Color.parseColor("#FF4436")),
        WATCHING("MONITOR", Color.parseColor("#FFC400"))
    }

    private val density = resources.displayMetrics.density

    /**
     * The big number: how long the current take has run.
     *
     * Large because it is the thing being watched during a take. How long the
     * shot is now is a question asked constantly while rolling; what time of
     * day it is is a question asked once, afterwards, in the edit.
     */
    var duration: String = "00:00:00:00"
        set(value) { if (field != value) { field = value; invalidate() } }

    /** The small number beneath it: the timecode actually written to the file. */
    var timecode: String = "00:00:00:00"
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Dimmed when no take is running, so a held length cannot read as a live one. */
    var rolling: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var sync: Sync = Sync.INTERNAL
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Whose clock it is. On internal, this device's own name. */
    var sourceName: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    var status: Status = Status.IDLE
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Curve, resolution and rate, already formatted. */
    var formatLine: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Space left, time left, frames lost. */
    var healthLine: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Drawn in a warning colour once either number stops being comfortable. */
    var healthLevel: RecordingHealth.Level = RecordingHealth.Level.FINE
        set(value) { if (field != value) { field = value; invalidate() } }

    var sizeSp: Float = 16f
        set(value) { field = value; requestLayout(); invalidate() }

    var showBackground: Boolean = true
        set(value) { field = value; invalidate() }

    /** Any line can be turned off without disturbing the ones that remain. */
    var showSync = true
    var showStatus = true
    var showFormat = true
    var showHealth = true

    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.1f
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The standard burn-in plate: dark enough to read white digits over a
        // bright sky, light enough not to hide the shot underneath.
        color = Color.argb(115, 0, 0, 0)
    }

    private val pad get() = 7f * density
    private fun digitSize() = sizeSp * density
    private fun smallSize() = (sizeSp * 0.44f * density).coerceAtLeast(8f * density)
    private fun lineGap() = smallSize() * 1.45f

    private fun lines(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        // The file's own timecode, directly under the take length, because the
        // two are read together: how long this is, and where it sits.
        out += ("TC " + timecode) to sync.colour
        if (showSync) {
            val name = sourceName.uppercase()
            out += (if (name.isEmpty()) sync.label else sync.label + "   " + name) to sync.colour
        }
        if (showStatus) out += status.label to status.colour
        if (showFormat && formatLine.isNotEmpty()) {
            out += formatLine.uppercase() to Color.parseColor("#9AA6B2")
        }
        if (showHealth && healthLine.isNotEmpty()) {
            out += healthLine.uppercase() to when (healthLevel) {
                RecordingHealth.Level.CRITICAL -> Status.RECORDING.colour
                RecordingHealth.Level.LOW -> Status.WATCHING.colour
                RecordingHealth.Level.FINE -> Color.parseColor("#9AA6B2")
            }
        }
        return out
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        digits.textSize = digitSize()
        small.textSize = smallSize()

        val widest = maxOf(
            digits.measureText("00:00:00:00"),
            lines().maxOfOrNull { small.measureText(it.first) } ?: 0f
        )
        val height = pad * 2 + digitSize() * 1.1f + lines().size * lineGap()

        setMeasuredDimension(
            resolveSize((pad * 2 + widest).toInt(), widthSpec),
            resolveSize(height.toInt(), heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        digits.textSize = digitSize()
        small.textSize = smallSize()

        if (showBackground) {
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                3f * density, 3f * density, plate
            )
        }

        // One left edge for everything. The eye returns to the same place for
        // every line, which is the whole reason a burn-in is a column.
        val left = pad
        var y = pad + digitSize() * 0.88f

        digits.color = if (rolling) Status.RECORDING.colour else sync.colour
        digits.alpha = if (rolling) 255 else 170
        canvas.drawText(duration, left, y, digits)
        digits.alpha = 255

        y += digitSize() * 0.22f
        for ((text, colour) in lines()) {
            y += lineGap()
            small.color = colour
            canvas.drawText(text, left, y, small)
        }
    }
}
