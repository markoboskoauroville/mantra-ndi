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
    /**
     * Chosen in settings rather than by tapping. Cycling it from the box made
     * one gesture mean three things, which on a shoot is one too many. Full
     * screen means exactly that: focus on everything and let the camera decide,
     * which is the right answer for a wide landscape.
     */
    enum class Size(val halfDp: Float) { SMALL(26f), MEDIUM(48f), LARGE(76f), FULL(-1f) }

    var boxSize: Size = Size.MEDIUM
        set(value) { field = value; invalidate() }

    private val half: Float
        get() = if (boxSize == Size.FULL) minOf(width, height) * 0.46f
                else boxSize.halfDp * density

    init {
        // Not clickable: an unclaimed touch has to fall through to the image.
        isClickable = false
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

    private companion object {
        /** Long enough to be a decision, short enough not to feel stuck. */
        const val HOLD_TO_DRAG_MS = 260L
        const val FAR_SLOP = 44f
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
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    /**
     * Dragging has to be asked for.
     *
     * A tap that wanders a few pixels is still a tap: a thumb on glass always
     * moves a little, and treating that as a drag meant the box slid away
     * instead of focusing. Focusing happens far more often than moving the
     * box, so the rarer gesture is the one made deliberate. Hold for a moment
     * and it becomes a drag; let go before that and it was a tap, however much
     * the finger wandered.
     */
    private val holdToDrag = Runnable {
        dragging = true
        state = State.IDLE
    }

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
                val insideBox = Math.abs(event.x - cx) <= reach &&
                    Math.abs(event.y - cy) <= reach

                // A tap anywhere on the picture puts the box there. It used to
                // refuse anything outside itself, so the only way to focus on
                // something was to drag the box onto it first, which is not
                // what tapping a viewfinder has ever meant.
                if (!insideBox) {
                    centreX = (event.x / width).coerceIn(0.08f, 0.92f)
                    centreY = (event.y / height).coerceIn(0.08f, 0.92f)
                    onMoved?.invoke(centreX, centreY)
                    invalidate()
                }
                grabbed = true
                pressStart = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                dragging = false
                postDelayed(holdToDrag, HOLD_TO_DRAG_MS)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!grabbed) return false
                // A long, deliberate sweep is a drag even without the wait.
                if (!dragging &&
                    (Math.abs(event.x - downX) > FAR_SLOP * density ||
                        Math.abs(event.y - downY) > FAR_SLOP * density)
                ) {
                    removeCallbacks(holdToDrag)
                    dragging = true
                }
                if (dragging) {
                    moveTo(event.x / width, event.y / height)
                    state = State.IDLE
                    onMoved?.invoke(centreX, centreY)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(holdToDrag)
                val wasGrabbed = grabbed
                val wasDragging = dragging
                grabbed = false
                dragging = false
                // Anything that was not a drag was a tap, however wobbly.
                if (wasGrabbed && !wasDragging) onTapped?.invoke()
                return wasGrabbed
            }
        }
        return false
    }
}
