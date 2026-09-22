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
 * **A double tap hands the zone back to the camera.** Once was a single tap,
 * and a single tap is what a thumb does by accident while it is looking for a
 * band — losing manual exposure mid-shot because a finger brushed the glass is
 * not a control, it is a hazard. Two taps in a third of a second is a thing
 * only intent does.
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
     * Two taps on a zone: give that parameter back to the camera.
     *
     * There is no room on the rail for a key per parameter, and there should
     * not be one: the place to say "you take this" about white balance is the
     * white balance zone.
     */
    var onDoubleTap: ((index: Int) -> Unit)? = null

    private var held: Int? = null
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var tapped: Int? = null
    private var lastTapAt = 0L
    private var lastTapIndex = -1

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
        textSize = density(9f)
        letterSpacing = 0.12f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(11f)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun density(dp: Float) = dp * resources.displayMetrics.density

    /** How far a finger may wander and still have meant a tap. */
    private val slop get() = density(8f)

    /** Two taps this far apart in time are one gesture. */
    private val doubleTapMs = 320L

    /** Below the status line, which is pinned to the top edge. */
    private val topInset get() = density(24f)

    /**
     * Tall enough to hit, short enough that five of them clear the picture.
     *
     * A band is a word and a track, so it needs about half as much again as a
     * line of text. Five at a fixed height is fine on this phone and is not on
     * a small one, so it is whatever fits in the top two thirds of the frame
     * and never more than a comfortable thumb's worth.
     */
    private val rowHeight: Float
        get() {
            val count = zones.size.coerceAtLeast(1)
            val room = (height - topInset) * 0.66f
            return (room / count).coerceIn(density(28f), density(40f))
        }

    private val sideGap get() = density(8f)

    /** Edge to edge, which is the whole point of this version. */
    private val trackLeft get() = sideGap
    private val trackRight get() = width - sideGap

    private fun rowTop(index: Int) = topInset + rowHeight * index

    /** Which band a finger at [y] landed on, or null. */
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
            // The word sits above the track, at the left, where the eye starts.
            val words = top + rowHeight * 0.42f
            val line = top + rowHeight * 0.78f

            val tint = when {
                !zone.live -> Color.argb(120, 122, 128, 135)
                held == i -> RailButton.GREEN
                zone.auto -> Color.argb(210, 232, 163, 61)
                else -> Color.argb(225, 235, 238, 240)
            }
            title.color = Color.argb(
                if (zone.live) 170 else 90, Color.red(tint), Color.green(tint), Color.blue(tint)
            )
            value.color = tint
            // Drawn with a shadow because it sits on a picture, and a picture
            // can be any colour at all.
            title.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)
            value.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)

            canvas.drawText(zone.title, sideGap, words, title)
            canvas.drawText(
                zone.value,
                sideGap + title.measureText(zone.title) + density(8f),
                words,
                value
            )

            if (!zone.live) continue

            val left = trackLeft
            val right = trackRight
            if (right <= left) continue

            // The travel behind, the part used in front, and the knob on top.
            track.strokeWidth = density(2f)
            track.color = Color.argb(70, 255, 255, 255)
            canvas.drawLine(left, line, right, line, track)

            val at = left + (right - left) * zone.position.coerceIn(0f, 1f)
            track.color = if (held == i) RailButton.GREEN else Color.argb(150, 235, 238, 240)
            canvas.drawLine(left, line, at, line, track)

            knob.color = if (held == i) RailButton.GREEN else Color.argb(235, 245, 247, 248)
            knob.setShadowLayer(density(3f), 0f, 0f, Color.BLACK)
            canvas.drawCircle(at, line, density(if (held == i) 7f else 5.5f), knob)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zones.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = rowAt(event.y) ?: return false
                // A dead band still takes a double tap, because that is what
                // hands it back to the camera — and AUTO is exactly the state a
                // band is in when it has nothing for the finger to drag.
                held = if (zones.getOrNull(index)?.live == true) index else null
                lastX = event.x
                downX = event.x
                downY = event.y
                moved = false
                tapped = index
                if (held != null) onGrab?.invoke(index)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved &&
                    (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop)
                ) moved = true
                val index = held ?: return true
                // Relative to the last position rather than to where the finger
                // landed: a parameter that jumps to an absolute value the moment
                // it is touched is a parameter that cannot be nudged.
                val span = (trackRight - trackLeft).coerceAtLeast(1f)
                val delta = (event.x - lastX) / span
                lastX = event.x
                onDrag?.invoke(index, delta)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = tapped
                held = null
                tapped = null
                onGrab?.invoke(null)
                invalidate()
                if (!moved && index != null) {
                    val now = SystemClock.uptimeMillis()
                    if (index == lastTapIndex && now - lastTapAt <= doubleTapMs) {
                        lastTapAt = 0L
                        lastTapIndex = -1
                        onDoubleTap?.invoke(index)
                    } else {
                        lastTapAt = now
                        lastTapIndex = index
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                held = null
                tapped = null
                onGrab?.invoke(null)
                invalidate()
                return true
            }
        }
        return false
    }
}
