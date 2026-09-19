package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * One status line across the top, the way a deck or a mixer writes one.
 *
 * Everything on a single row and nothing stacked. A block of text sitting in
 * the corner of a frame covers the part of the shot nearest it; a line along
 * the top edge covers a strip nobody composes into anyway, and the eye reads a
 * row far faster than a column of short lines.
 *
 *   TC 19:16:22:15  SYNC INT  MANTRA CAM A142   00:01:47:12   STANDBY  LOGC4 ...
 *
 * The take length sits in the middle in a larger face, because it is the one
 * field watched continuously while rolling and everything else is checked
 * occasionally. Centring it also means it stays where the eye expects it as
 * the fields either side change width.
 */
class TimecodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Sync(val label: String, val colour: Int) {
        MASTER("SYNC MST", Color.parseColor("#12C46A")),
        EXTERNAL("SYNC EXT", Color.parseColor("#F2F4F6")),
        INTERNAL("SYNC INT", Color.parseColor("#8C99A6"))
    }

    /**
     * Three letters, because the status sits beside the take length and a
     * word wide enough to read from a distance is a word wide enough to push
     * the number off centre. These are the abbreviations a deck uses.
     */
    enum class Status(val label: String, val colour: Int) {
        IDLE("STB", Color.parseColor("#8C99A6")),
        STREAMING("LIV", Color.parseColor("#12C46A")),
        RECORDING("REC", Color.parseColor("#FF4436")),
        BOTH("R+L", Color.parseColor("#FF4436")),
        WATCHING("MON", Color.parseColor("#FFC400"))
    }

    /** Each field can be turned off on its own, as a burn-in's can. */
    enum class Field(val label: String) {
        TIMECODE("Timecode"),
        SYNC("Sync state"),
        SOURCE("Clock source"),
        STATUS("Camera status"),
        FORMAT("Format"),
        HEALTH("Space and drops")
    }

    private val density = resources.displayMetrics.density

    var duration: String = "00:00:00:00"
        set(value) { if (field != value) { field = value; invalidate() } }

    var timecode: String = "00:00:00:00"
        set(value) { if (field != value) { field = value; invalidate() } }

    var sync: Sync = Sync.INTERNAL
        set(value) { if (field != value) { field = value; invalidate() } }

    var sourceName: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * LOC, REM or MON. Three letters on the same line as everything else,
     * because it was being written across the picture as a word and there is
     * room for it here.
     */
    var modeLabel: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    var status: Status = Status.IDLE
        set(value) { if (field != value) { field = value; invalidate() } }

    var formatLine: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    var healthLine: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    var healthLevel: RecordingHealth.Level = RecordingHealth.Level.FINE
        set(value) { if (field != value) { field = value; invalidate() } }

    var rolling: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var fields: Set<Field> = Field.values().toSet()
        set(value) { field = value; requestLayout(); invalidate() }

    var sizeSp: Float = 16f
        set(value) { field = value; requestLayout(); invalidate() }

    var showBackground: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.06f
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 0, 0, 0)
    }

    private val pad get() = 8f * density
    private val gap get() = 14f * density
    private fun bigSize() = sizeSp * density
    private fun smallSize() = (sizeSp * 0.46f * density).coerceAtLeast(8f * density)

    /** Left of the number: where the clock is and where it came from. */
    private fun leftFields(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        if (modeLabel.isNotEmpty()) out += modeLabel to Color.parseColor("#E7A44C")
        if (Field.TIMECODE in fields) out += ("TC " + timecode) to sync.colour
        if (Field.SYNC in fields) out += sync.label to sync.colour
        if (Field.SOURCE in fields && sourceName.isNotEmpty()) {
            out += sourceName.uppercase() to Color.parseColor("#9AA6B2")
        }
        return out
    }

    /** Right of it: what the camera is doing and what it is writing. */
    private fun rightFields(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        if (Field.FORMAT in fields && formatLine.isNotEmpty()) {
            out += formatLine.uppercase() to Color.parseColor("#9AA6B2")
        }
        if (Field.HEALTH in fields && healthLine.isNotEmpty()) {
            out += healthLine.uppercase() to when (healthLevel) {
                RecordingHealth.Level.CRITICAL -> Status.RECORDING.colour
                RecordingHealth.Level.LOW -> Status.WATCHING.colour
                RecordingHealth.Level.FINE -> Color.parseColor("#9AA6B2")
            }
        }
        return out
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        big.textSize = bigSize()
        small.textSize = smallSize()
        val height = (pad * 2 + bigSize() * 1.12f).toInt()
        // Full width: it is a status line along an edge, not a label.
        setMeasuredDimension(
            resolveSize(Int.MAX_VALUE, widthSpec),
            resolveSize(height, heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        big.textSize = bigSize()
        small.textSize = smallSize()

        if (showBackground) {
            canvas.drawRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), plate)
        }

        val middle = height / 2f
        val smallBaseline = middle - (small.descent() + small.ascent()) / 2f

        // The take, centred, in the larger face.
        big.color = if (rolling) Status.RECORDING.colour else sync.colour
        big.alpha = if (rolling) 255 else 180
        if (Field.TIMECODE in fields || fields.isNotEmpty()) {
            canvas.drawText(
                duration, width / 2f,
                middle - (big.descent() + big.ascent()) / 2f, big
            )
        }
        big.alpha = 255

        // Drawn hard against the number so the two read as one block: what the
        // camera is doing and how long it has been doing it.
        var reserved = big.measureText(duration) / 2f + gap
        if (Field.STATUS in fields) {
            small.textAlign = Paint.Align.LEFT
            small.color = status.colour
            canvas.drawText(
                status.label,
                width / 2f + big.measureText(duration) / 2f + gap * 0.5f,
                smallBaseline,
                small
            )
            reserved += small.measureText(status.label) + gap * 0.5f
        }

        // Left group runs outwards from the edge and stops before the number.
        var x = pad
        small.textAlign = Paint.Align.LEFT
        for ((text, colour) in leftFields()) {
            val w = small.measureText(text)
            if (x + w > width / 2f - reserved) break
            small.color = colour
            canvas.drawText(text, x, smallBaseline, small)
            x += w + gap
        }

        // Right group runs inwards from the other edge, so the line stays
        // balanced however wide the middle gets.
        var rx = width - pad
        small.textAlign = Paint.Align.RIGHT
        for ((text, colour) in rightFields().reversed()) {
            val w = small.measureText(text)
            if (rx - w < width / 2f + reserved) break
            small.color = colour
            canvas.drawText(text, rx, smallBaseline, small)
            rx -= w + gap
        }
    }
}
