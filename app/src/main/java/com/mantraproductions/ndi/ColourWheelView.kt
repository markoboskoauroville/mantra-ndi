package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One wheel of a three way grade, with its master beneath it.
 *
 * The puck is dragged towards a hue and the strength is how far from the
 * centre it sits, which is the gesture every grading surface has used since
 * they were physical trackballs. The bar underneath is luminance, kept
 * separate on purpose: a colour push should not change how bright the picture
 * is, and on this wheel it cannot, because the three channel offsets of any
 * angle sum to zero.
 *
 * Double tap returns this wheel to neutral. A grade you cannot undo is a grade
 * nobody will try anything with.
 */
class ColourWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    var label: String = ""
        set(value) { field = value; invalidate() }

    /** Where the puck sits: degrees from red, and how far out. */
    var angle = 0.0
        private set
    var radius = 0.0
        private set

    /** The master, minus one to plus one. */
    var master = 0.0
        private set

    var onChanged: (() -> Unit)? = null
    var onReleased: (() -> Unit)? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#44FFFFFF")
    }
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val puckRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#AA000000")
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2A2E35")
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = FaderView.AMBER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA6B2")
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        letterSpacing = 0.18f
    }

    private var draggingPuck = false
    private var draggingMaster = false
    private var lastTapAt = 0L
    private var moved = false

    // width is an Int and height minus the bar is a Float, so both are floated
    // before they are compared rather than letting the comparison pick one.
    private val wheelRadius: Float
        get() = (minOf(width.toFloat(), height - barSpace) / 2f) - 4f * density
    private val barSpace: Float get() = 44f * density
    private val centreX: Float get() = width / 2f
    private val centreY: Float get() = (height - barSpace) / 2f

    fun setNeutral() {
        angle = 0.0
        radius = 0.0
        master = 0.0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = centreX
        val cy = centreY
        val r = wheelRadius
        if (r <= 0) return

        // Hue around, desaturating to neutral at the centre, which is what a
        // wheel means: the middle is no correction at all.
        wheelPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
            ),
            null
        )
        canvas.drawCircle(cx, cy, r, wheelPaint)

        fadePaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.parseColor("#FF0B0E11"), Color.parseColor("#000B0E11")),
            floatArrayOf(0f, 0.85f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fadePaint)

        ringPaint.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, r, ringPaint)

        // The puck.
        val px = cx + (r * radius * Math.cos(Math.toRadians(angle))).toFloat()
        val py = cy - (r * radius * Math.sin(Math.toRadians(angle))).toFloat()
        canvas.drawCircle(px, py, 9f * density, puckPaint)
        puckRing.strokeWidth = 1.5f * density
        canvas.drawCircle(px, py, 9f * density, puckRing)

        canvas.drawText(label.uppercase(), cx, cy + r + 15f * density, labelPaint)

        // The master, a bar rather than another wheel, because luminance has
        // no hue and a second circle would imply it did.
        val barY = height - 16f * density
        val left = 10f * density
        val right = width - 10f * density
        barPaint.strokeWidth = 4f * density
        canvas.drawLine(left, barY, right, barY, barPaint)

        val mid = (left + right) / 2f
        val handleX = mid + ((right - left) / 2f * master).toFloat()
        barFill.strokeWidth = 4f * density
        canvas.drawLine(mid, barY, handleX, barY, barFill)
        canvas.drawCircle(handleX, barY, 8f * density, puckPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val barY = height - 16f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                draggingMaster = event.y > barY - 22f * density
                draggingPuck = !draggingMaster
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(
                        event.x - centreX, event.y - centreY
                    ) > 6f * density
                ) moved = true

                if (draggingMaster) {
                    val left = 10f * density
                    val right = width - 10f * density
                    val mid = (left + right) / 2f
                    master = (((event.x - mid) / ((right - left) / 2f)).toDouble())
                        .coerceIn(-1.0, 1.0)
                } else if (draggingPuck) {
                    val dx = (event.x - centreX).toDouble()
                    val dy = (centreY - event.y).toDouble()
                    angle = Math.toDegrees(atan2(dy, dx))
                    radius = (hypot(dx, dy) / wheelRadius).coerceIn(0.0, 1.0)
                }
                onChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingPuck = false
                draggingMaster = false
                if (!moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 320) {
                        setNeutral()
                        onChanged?.invoke()
                        lastTapAt = 0
                    } else {
                        lastTapAt = now
                    }
                }
                onReleased?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
