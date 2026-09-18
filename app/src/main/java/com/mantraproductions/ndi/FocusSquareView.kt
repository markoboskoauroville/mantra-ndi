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
    /** Small, middle, large. Tapping the box steps through them. */
    enum class Size(val halfDp: Float) { SMALL(30f), MEDIUM(50f), LARGE(78f) }

    var boxSize: Size = Size.MEDIUM
        set(value) { field = value; invalidate() }

    private val half get() = boxSize.halfDp * density

    init {
        // Not clickable: an unclaimed touch has to fall through to the image.
        isClickable = false
    }

    /** Steps the box through its three sizes. */
    fun cycleSize() {
        boxSize = when (boxSize) {
            Size.SMALL -> Size.MEDIUM
            Size.MEDIUM -> Size.LARGE
            Size.LARGE -> Size.SMALL
        }
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
        // Half the weight it was. A focus box is a reference, not a graphic,
        // and a heavy one hides the very detail being judged.
        paint.strokeWidth = 1f * density

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

    private var grabbed = false
    private var pressStart = 0L
    private var moved = false

    /** Fired on a tap that was not a drag: the caller decides what focus means. */
    var onTapped: (() -> Unit)? = null

    /**
     * Only touches on the box itself belong to the box.
     *
     * This view covers the whole image, so claiming every touch would swallow
     * the tap that opens and closes the controls, which is exactly what it did
     * the first time. A touch outside the box is returned untouched and reaches
     * the preview underneath.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = width * centreX
                val cy = height * centreY
                val reach = half + 20f * density
                grabbed = Math.abs(event.x - cx) <= reach && Math.abs(event.y - cy) <= reach
                if (!grabbed) return false
                pressStart = System.currentTimeMillis()
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!grabbed) return false
                val travelled = Math.abs(event.x - width * centreX) > 6f * density ||
                        Math.abs(event.y - height * centreY) > 6f * density
                if (travelled) moved = true
                moveTo(event.x / width, event.y / height)
                if (moved) state = State.IDLE
                onMoved?.invoke(centreX, centreY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasGrabbed = grabbed
                grabbed = false
                if (wasGrabbed && !moved && System.currentTimeMillis() - pressStart < 400) {
                    onTapped?.invoke()
                }
                return wasGrabbed
            }
        }
        return false
    }
}
