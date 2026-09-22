package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Four faders across the picture, stacked down from the top left.
 *
 * They were four invisible *columns* — a third of the frame wide each, dragged
 * up and down — and that was wrong twice over. The values sat at the foot of
 * the picture where the geometry readout already lives, so the two overlapped
 * and neither could be read; and a phone held across is wide and short, so a
 * vertical drag has the *short* side of the screen to travel in while the long
 * side sits empty.
 *
 * So: horizontal. Each parameter is a row, its name and its value on the left
 * where the eye starts, the track running away to the right with the whole
 * width of a landscape screen to move in. Right is more of everything: more
 * light, more ISO, closer focus.
 *
 * They begin below the status line and beside `L1`, which is where a thumb
 * already is on this phone.
 *
 * Still exclusive with the focus box, and that is still the reason the rail has
 * a key to put it away: a tap that could mean "focus here" and could mean
 * "start changing ISO" is a tap that means neither.
 */
class ControlZones @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * One fader.
     *
     * [live] false is a parameter this lens does not have — a phone's iris is
     * fixed, because the blades cost room a phone does not have, and an ultra
     * wide has no focus motor. It keeps its row and shows its value, dimmed and
     * with no track, rather than disappearing and shuffling the others under
     * his thumb.
     *
     * [position] is 0..1, where the value sits in its own travel. It is what
     * the knob is drawn at; without it a fader is a word with a line beside it.
     */
    data class Zone(
        val title: String,
        var value: String = "—",
        var live: Boolean = true,
        var position: Float = 0f
    )

    var zones: List<Zone> = emptyList()
        set(value) { field = value; invalidate() }

    /** A drag, as a fraction of the track's width. Right is positive. */
    var onDrag: ((index: Int, delta: Float) -> Unit)? = null

    /** Told when a row is grabbed and let go, so the name can light up. */
    var onGrab: ((index: Int?) -> Unit)? = null

    private var held: Int? = null
    private var lastX = 0f

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
        textSize = density(9f)
        letterSpacing = 0.12f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(15f)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun density(dp: Float) = dp * resources.displayMetrics.density

    /** Below the status line, which is pinned to the top edge. */
    private val topInset get() = density(26f)
    private val rowHeight get() = density(40f)
    private val nameWidth get() = density(62f)
    private val valueWidth get() = density(96f)
    private val sideGap get() = density(10f)

    /** Where a row's track starts and ends. */
    private val trackLeft get() = sideGap + nameWidth + valueWidth + density(12f)
    private val trackRight get() = width - sideGap

    private fun rowTop(index: Int) = topInset + rowHeight * index

    /** Which row a finger at [y] landed on, or null. */
    private fun rowAt(y: Float): Int? {
        if (zones.isEmpty()) return null
        val index = ((y - topInset) / rowHeight).toInt()
        return if (index in zones.indices) index else null
    }

    override fun onDraw(canvas: Canvas) {
        if (zones.isEmpty() || width == 0) return

        for (i in zones.indices) {
            val zone = zones[i]
            val top = rowTop(i)
            val middle = top + rowHeight / 2f

            val tint = when {
                !zone.live -> Color.argb(120, 122, 128, 135)
                held == i -> RailButton.GREEN
                else -> Color.argb(225, 235, 238, 240)
            }
            title.color = Color.argb(
                if (zone.live) 175 else 90, Color.red(tint), Color.green(tint), Color.blue(tint)
            )
            value.color = tint
            // Drawn with a shadow because it sits on a picture, and a picture
            // can be any colour at all.
            title.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)
            value.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)

            // The name, then the value, then the track: left to right, the way
            // it is read.
            canvas.drawText(zone.title, sideGap, middle + density(4f), title)
            canvas.drawText(
                zone.value, sideGap + nameWidth + valueWidth, middle + density(5f), value
            )

            if (!zone.live) continue

            val left = trackLeft
            val right = trackRight
            if (right <= left) continue

            // The travel behind, the part used in front, and the knob on top.
            track.strokeWidth = density(2f)
            track.color = Color.argb(70, 255, 255, 255)
            canvas.drawLine(left, middle, right, middle, track)

            val at = left + (right - left) * zone.position.coerceIn(0f, 1f)
            track.color = if (held == i) RailButton.GREEN else Color.argb(150, 235, 238, 240)
            canvas.drawLine(left, middle, at, middle, track)

            knob.color = if (held == i) RailButton.GREEN else Color.argb(235, 245, 247, 248)
            knob.setShadowLayer(density(3f), 0f, 0f, Color.BLACK)
            canvas.drawCircle(at, middle, density(if (held == i) 7f else 5.5f), knob)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zones.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = rowAt(event.y) ?: return false
                // A row with nothing behind it is not grabbed. The iris is
                // fixed on a phone and an ultra wide has no focus travel: a row
                // that lights up and then does nothing is worse than one that
                // never answers.
                if (zones.getOrNull(index)?.live != true) return false
                held = index
                lastX = event.x
                onGrab?.invoke(index)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = held ?: return false
                // Relative to the last position rather than to where the finger
                // landed: a parameter that jumps to an absolute value the moment
                // it is touched is a parameter that cannot be nudged.
                val span = (trackRight - trackLeft).coerceAtLeast(1f)
                val delta = (event.x - lastX) / span
                lastX = event.x
                onDrag?.invoke(index, delta)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                held = null
                onGrab?.invoke(null)
                invalidate()
                return true
            }
        }
        return false
    }
}
