package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The focus box, the way a camera shows it.
 *
 * Drag it onto the subject, then press the focus A and the camera focuses
 * there and holds. The point is that focus becomes a place rather than a
 * number: nobody thinks in dioptres, everybody can point at a face.
 *
 * White while idle, amber while the camera is hunting, green once it has
 * locked, which is the convention every camera already taught the operator.
 */
class FocusSquareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { IDLE, SEEKING, LOCKED, FAILED }

    private val density = resources.displayMetrics.density

    var state: State = State.IDLE
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Centre as a fraction of the view, so it survives rotation and resize. */
    var centreX = 0.5f
        private set
    var centreY = 0.5f
        private set

    /** Fired when the box is moved, with the new normalised centre. */
    var onMoved: ((Float, Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val half get() = 46f * density

    init {
        isClickable = true
    }

    fun moveTo(x: Float, y: Float) {
        centreX = x.coerceIn(0.05f, 0.95f)
        centreY = y.coerceIn(0.05f, 0.95f)
        invalidate()
    }

    /**
     * The box in sensor coordinates, which is what CONTROL_AF_REGIONS wants.
     * Returned normalised so the caller can scale it to whatever the active
     * array happens to be on that phone.
     */
    fun normalisedBounds(): FloatArray {
        val w = (half * 2) / width.toFloat()
        val h = (half * 2) / height.toFloat()
        return floatArrayOf(
            (centreX - w / 2).coerceIn(0f, 1f),
            (centreY - h / 2).coerceIn(0f, 1f),
            (centreX + w / 2).coerceIn(0f, 1f),
            (centreY + h / 2).coerceIn(0f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * centreX
        val cy = height * centreY
        rect.set(cx - half, cy - half, cx + half, cy + half)

        paint.color = when (state) {
            State.IDLE -> Color.parseColor("#CCFFFFFF")
            State.SEEKING -> Color.parseColor("#FFD400")
            State.LOCKED -> Color.parseColor("#12C46A")
            State.FAILED -> Color.parseColor("#FF2D1F")
        }
        paint.strokeWidth = 2f * density

        // Corners rather than a full box, so it covers as little of the
        // subject as possible while still reading as a target.
        val arm = half * 0.42f
        with(canvas) {
            drawLine(rect.left, rect.top, rect.left + arm, rect.top, paint)
            drawLine(rect.left, rect.top, rect.left, rect.top + arm, paint)
            drawLine(rect.right, rect.top, rect.right - arm, rect.top, paint)
            drawLine(rect.right, rect.top, rect.right, rect.top + arm, paint)
            drawLine(rect.left, rect.bottom, rect.left + arm, rect.bottom, paint)
            drawLine(rect.left, rect.bottom, rect.left, rect.bottom - arm, paint)
            drawLine(rect.right, rect.bottom, rect.right - arm, rect.bottom, paint)
            drawLine(rect.right, rect.bottom, rect.right, rect.bottom - arm, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                moveTo(event.x / width, event.y / height)
                state = State.IDLE
                onMoved?.invoke(centreX, centreY)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
