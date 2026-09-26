package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The zones: five bands across the picture, one parameter each.
 *
 * They were four invisible *columns* — a third of the frame wide, dragged up
 * and down — and that was wrong twice over: the values sat at the foot of the
 * picture where the geometry readout already lives, and a phone held across is
 * wide and short, so a vertical drag has the *short* side of the screen to
 * travel in while the long side sits empty. So the zones went horizontal, one
 * band per parameter, right for more of everything.
 *
 * **What v79 still had wrong was the room.** The name and the value were two
 * fixed columns a hundred and eighty density pixels wide, and the track began
 * after them — a third of the travel spent on two words. *"We want to maximise
 * the slider length so I can do it precisely in the zone."* Quite right: the
 * words are small and sit **above** the track now, and the track runs the
 * whole width of the picture, edge to edge.
 *
 * **A zone is relative.** Touching one never moves anything: the value goes
 * where the thumb pushes it from where it already was, so a fader can be
 * nudged a hair without first being found exactly. An absolute fader on a
 * picture is a parameter that jumps the moment it is touched, which on a live
 * camera is a ruined take.
 *
 * **v87: mixer faders with an A / M switch each.** A double tap and a hold
 * used to hand a zone back to the camera, and nobody could remember which did
 * what. Now a switch at the head of each fader says A or M and is pressed to
 * change it; a drag moves the fader (and takes it to M); a single tap anywhere
 * else focuses there; a double tap means nothing.
 *
 * Still exclusive with the focus box, and that is still the reason the rail has
 * a key to put it away: a tap that could mean "focus here" and could mean
 * "start changing ISO" is a tap that means neither.
 */
class ControlZones @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * One zone.
     *
     * [live] false is a parameter this lens does not have — a phone's iris is
     * fixed, because the blades cost room a phone does not have, and an ultra
     * wide has no focus motor. It keeps its band and shows its value, dimmed
     * and with no track, rather than disappearing and shuffling the others
     * under his thumb.
     *
     * [position] is 0..1, where the value sits in its own travel. It is what
     * the knob is drawn at; without it a zone is a word with a line beside it.
     */
    data class Zone(
        val title: String,
        var value: String = "—",
        var live: Boolean = true,
        var position: Float = 0f,
        /** True while the camera is deciding this one, so the word can say so. */
        var auto: Boolean = false
    )

    var zones: List<Zone> = emptyList()
        set(value) { field = value; invalidate() }

    /** A drag, as a fraction of the track's width. Right is positive. */
    var onDrag: ((index: Int, delta: Float) -> Unit)? = null

    /** Told when a zone is grabbed and let go, so the name can light up. */
    var onGrab: ((index: Int?) -> Unit)? = null

    /**
     * The A / M switch at the head of a fader: that parameter automatic or
     * manual. *"Next to each slider should be A and M ... avoid confusion with
     * what is double tap, what is single tap."* A double tap and a hold used to
     * carry these meanings; now a switch that says which it is carries them,
     * and a double tap means nothing at all.
     */
    var onToggle: ((index: Int) -> Unit)? = null

    /**
     * One tap anywhere that is not the switch: focus there. With nothing else
     * meaning a tap, it fires at once rather than a double-tap interval late.
     */
    var onSingleTap: ((x: Float, y: Float) -> Unit)? = null

    private var held: Int? = null
    private var onSwitch: Int? = null
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
        textSize = density(10f)
        letterSpacing = 0.12f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(15f)
    }
    private val switchText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(16f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val box = android.graphics.RectF()

    private fun density(dp: Float) = dp * resources.displayMetrics.density

    /** How far a finger may wander and still have meant a tap. */
    private val slop get() = density(8f)

    /** Below the status line, which is pinned to the top edge. */
    private val topInset get() = density(24f)

    /**
     * Thick: a fader is grabbed without looking. As tall as the picture allows
     * for four of them in the top three quarters, and never thinner than a
     * thumb.
     */
    private val rowHeight: Float
        get() {
            val count = zones.size.coerceAtLeast(1)
            val room = (height - topInset) * 0.78f
            return (room / count).coerceIn(density(44f), density(68f))
        }

    private val sideGap get() = density(8f)

    /** The A / M switch, a square at the head of each fader. */
    private val switchSize get() = (rowHeight * 0.62f).coerceAtMost(density(40f))

    private val trackLeft get() = sideGap + switchSize + density(12f)
    private val trackRight get() = width - sideGap - density(14f)

    private fun rowTop(index: Int) = topInset + rowHeight * index

    /** Which band a finger at [y] landed on, or null. */
    private fun rowAt(y: Float): Int? {
        if (zones.isEmpty()) return null
        val index = ((y - topInset) / rowHeight).toInt()
        return if (y >= topInset && index in zones.indices) index else null
    }

    private fun onSwitchAt(x: Float): Boolean = x <= sideGap + switchSize + density(6f)

    override fun onDraw(canvas: Canvas) {
        if (zones.isEmpty() || width == 0) return

        for (i in zones.indices) {
            val zone = zones[i]
            val top = rowTop(i)
            val mid = top + rowHeight * 0.62f

            // THE A / M SWITCH: amber A while the camera decides, white M
            // while the operator does. A lens that does not have the
            // parameter (a fixed focus, an iris) shows neither.
            val s = switchSize
            box.set(sideGap, mid - s / 2f, sideGap + s, mid + s / 2f)
            if (zone.live) {
                fill.color = if (zone.auto) Color.argb(200, 232, 163, 61) else Color.argb(215, 245, 247, 248)
                fill.setShadowLayer(density(3f), 0f, 0f, Color.BLACK)
                canvas.drawRoundRect(box, density(4f), density(4f), fill)
                fill.clearShadowLayer()
                switchText.color = Color.BLACK
                val m = switchText.fontMetrics
                canvas.drawText(if (zone.auto) "A" else "M", box.centerX(),
                    box.centerY() - (m.ascent + m.descent) / 2f, switchText)
            }

            // The name and the number, above the track, the number big: it is
            // what the eye comes for, and it moves as the fader moves.
            val tint = when {
                !zone.live -> Color.argb(120, 122, 128, 135)
                held == i -> RailButton.GREEN
                zone.auto -> Color.argb(225, 232, 163, 61)
                else -> Color.argb(235, 245, 247, 248)
            }
            title.color = Color.argb(if (zone.live) 190 else 90, 235, 238, 240)
            value.color = tint
            title.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)
            value.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)
            val words = top + rowHeight * 0.30f
            canvas.drawText(zone.title, trackLeft, words, title)
            canvas.drawText(zone.value, trackLeft + title.measureText(zone.title) + density(10f), words, value)

            if (!zone.live) continue
            val left = trackLeft
            val right = trackRight
            if (right <= left) continue

            // THE GROOVE: a thick dark slot with ticks every tenth, like the
            // slot a mixer's fader runs in.
            line.strokeWidth = density(7f)
            line.color = Color.argb(150, 0, 0, 0)
            canvas.drawLine(left, mid, right, mid, line)
            line.strokeWidth = density(1f)
            line.color = Color.argb(110, 255, 255, 255)
            for (t in 0..10) {
                val x = left + (right - left) * t / 10f
                val h = if (t % 5 == 0) density(9f) else density(5f)
                canvas.drawLine(x, mid + density(6f), x, mid + density(6f) + h, line)
            }

            // THE CAP: a wide flat block with a line across its middle, the
            // way an audio fader's cap is made to be read at a glance.
            val at = left + (right - left) * zone.position.coerceIn(0f, 1f)
            val capW = density(18f)
            val capH = (rowHeight * 0.62f).coerceAtMost(density(42f))
            box.set(at - capW / 2f, mid - capH / 2f, at + capW / 2f, mid + capH / 2f)
            fill.color = when {
                held == i -> RailButton.GREEN
                zone.auto -> Color.argb(200, 200, 190, 170)
                else -> Color.argb(240, 230, 232, 235)
            }
            fill.setShadowLayer(density(4f), 0f, 0f, Color.BLACK)
            canvas.drawRoundRect(box, density(3f), density(3f), fill)
            fill.clearShadowLayer()
            line.strokeWidth = density(2f)
            line.color = Color.argb(220, 20, 20, 20)
            canvas.drawLine(box.left + density(2f), mid, box.right - density(2f), mid, line)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zones.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                moved = false
                val index = rowAt(event.y)
                onSwitch = if (index != null && onSwitchAt(event.x) && zones[index].live) index else null
                held = if (index != null && onSwitch == null && zones[index].live) index else null
                if (held != null) onGrab?.invoke(held)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved &&
                    (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop)
                ) moved = true
                val index = held ?: return true
                if (!moved) return true
                // Relative to the last position: a fader that jumps to where
                // the finger landed is a picture that jumps when it is touched.
                val span = (trackRight - trackLeft).coerceAtLeast(1f)
                val delta = (event.x - lastX) / span
                lastX = event.x
                onDrag?.invoke(index, delta)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val sw = onSwitch
                val wasHeld = held
                held = null
                onSwitch = null
                if (wasHeld != null) onGrab?.invoke(null)
                invalidate()
                if (!moved) {
                    if (sw != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        onToggle?.invoke(sw)
                    } else {
                        onSingleTap?.invoke(event.x / width.coerceAtLeast(1), event.y / height.coerceAtLeast(1))
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                held = null
                onSwitch = null
                onGrab?.invoke(null)
                invalidate()
                return true
            }
        }
        return false
    }
}
