package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * THE WHITE BALANCE TRIANGLE (v101): the third shape on the picture.
 *
 * Marko, 2.10.2026: *"to the white balance, we need to add a 3rd rectangle, and that's the white balance
 * rectangle. So I can point that rectangle or anything gray or white in the scene, tap on it, and it will
 * do white balancing."*
 *
 * And, the same night: *"the square is focus, circle will be the exposure and triangle will be the white
 * balance. So we have 3 different shapes ... for the 3 different functions"*; *"when I tap, it reads and it
 * locks ... While searching, it's orange. When it's locked, then it's green."* So it is a TRIANGLE, point up,
 * white while idle, orange while it searches, green when what is inside came out neutral and is locked,
 * red when it could not (nothing lit, or all clipped). Nothing tracks: a tap reads, probes, locks, stays.
 * A hold, or a long sweep, drags it. It claims only touches that land on it: everything else falls through to the focus
 * square and the picture underneath, exactly as before it existed.
 */
class WhiteBalanceBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { IDLE, MEASURING, NEUTRAL, FAILED }

    private val density = resources.displayMetrics.density

    var state: State = State.IDLE
        set(value) { if (field != value) { field = value; invalidate() } }

    var centreX = 0.78f
        private set
    var centreY = 0.5f
        private set

    /** The rectangle's width as a fraction of the picture's width; its height is two thirds of that, in pixels. */
    var size = 0.16f
        set(value) { field = value.coerceIn(0.06f, 0.5f); invalidate() }

    /** A tap on the rectangle: balance on what is inside. */
    var onTapped: (() -> Unit)? = null
    /** It was dragged somewhere new (normalised centre), so it can be remembered. */
    var onMoved: ((Float, Float) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val path = Path()

    /** Half the triangle's base, and half its height (equilateral), in pixels. */
    private fun halfW() = size * width / 2f
    private fun halfH() = halfW() * 0.866f

    /**
     * Is the normalised point inside the triangle (apex at the top centre, base at the bottom)? The spot
     * reads only these pixels.
     */
    fun contains(nx: Float, ny: Float): Boolean {
        if (width <= 0 || height <= 0) return false
        val x = nx * width - width * centreX
        val y = ny * height - height * centreY
        val hw = halfW(); val hh = halfH()
        if (y < -hh || y > hh) return false
        // at height y the triangle spans ±hw * (y + hh) / (2 hh)
        return Math.abs(x) <= hw * (y + hh) / (2f * hh)
    }

    fun moveTo(x: Float, y: Float) {
        val hw = if (width > 0) halfW() / width else 0.05f
        val hh = if (height > 0) halfH() / height else 0.05f
        centreX = x.coerceIn(hw, 1f - hw)
        centreY = y.coerceIn(hh, 1f - hh)
        invalidate()
    }

    /** The rectangle as fractions of the picture: left, top, right, bottom. */
    fun normalisedBounds(): FloatArray {
        if (width <= 0 || height <= 0) return floatArrayOf(0.7f, 0.45f, 0.86f, 0.55f)
        val hw = halfW() / width
        val hh = halfH() / height
        return floatArrayOf(
            (centreX - hw).coerceIn(0f, 1f), (centreY - hh).coerceIn(0f, 1f),
            (centreX + hw).coerceIn(0f, 1f), (centreY + hh).coerceIn(0f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * centreX
        val cy = height * centreY
        path.reset()
        path.moveTo(cx, cy - halfH())
        path.lineTo(cx + halfW(), cy + halfH())
        path.lineTo(cx - halfW(), cy + halfH())
        path.close()
        line.color = when (state) {
            State.IDLE -> Color.WHITE
            State.MEASURING -> Color.parseColor("#E8A33D")
            State.NEUTRAL -> Color.parseColor("#33D17A")
            State.FAILED -> Color.parseColor("#FF3B30")
        }
        line.strokeWidth = 1.5f * density
        canvas.drawPath(path, line)
    }

    private var grabbed = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    private val holdToDrag = Runnable { dragging = true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val reach = 14f * density
                val inside = Math.abs(event.x - width * centreX) <= halfW() + reach &&
                    Math.abs(event.y - height * centreY) <= halfH() + reach
                // not on the rectangle: not ours, it goes on to the focus square underneath
                if (!inside) return false
                grabbed = true
                dragging = false
                downX = event.x; downY = event.y
                postDelayed(holdToDrag, HOLD_TO_DRAG_MS)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return grabbed
            MotionEvent.ACTION_MOVE -> {
                if (!grabbed) return false
                if (!dragging && (Math.abs(event.x - downX) > FAR_SLOP * density || Math.abs(event.y - downY) > FAR_SLOP * density)) {
                    removeCallbacks(holdToDrag)
                    dragging = true
                }
                if (dragging) {
                    moveTo(event.x / width, event.y / height)
                    state = State.IDLE
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(holdToDrag)
                val was = grabbed
                val wasDragging = dragging
                grabbed = false
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP && was) {
                    if (wasDragging) onMoved?.invoke(centreX, centreY) else onTapped?.invoke()
                }
                return was
            }
        }
        return grabbed
    }

    private companion object {
        const val HOLD_TO_DRAG_MS = 260L
        const val FAR_SLOP = 44f
    }
}
