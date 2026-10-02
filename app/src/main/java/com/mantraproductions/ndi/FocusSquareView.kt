package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
        set(value) { if (field != value) { field = value; invalidate(); onStateChanged?.invoke() } }

    /** v107: told when the colour changes, so its rail key can wear the same one. */
    var onStateChanged: (() -> Unit)? = null

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
     * The box's size, pinched: its side as a fraction of the picture's short
     * side, from [Mechanism.BOX_MIN] (about an eye) to the whole frame.
     *
     * *"Pinch to enlarge or reduce the size of the rectangle for the focus, so
     * it can be tiny until the full screen."* It was a fixed medium box, and
     * the camera was sent a fixed patch whatever was drawn. Now the pinch is
     * the size, the size is the region the camera is given, and the size is
     * kept between runs by whoever listens to [onResized].
     */
    var size = 0.18f
        set(value) {
            field = value.coerceIn(Mechanism.BOX_MIN, Mechanism.focusBoxMax(width, height)
                .takeIf { width > 0 } ?: 4f)
            invalidate()
        }

    /**
     * The tracking mark (v99): the box is the pattern, a thin outer square is
     * the search zone ([searchFactor] times it), and LOST is written under it
     * when the match falls below the confidence.
     */
    var tracking = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var lost = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var searchFactor = 2.5f
        set(value) { field = value; invalidate() }

    private val lostText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D1F")
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    /**
     * v103: false when its key is grey ("not showing on the screen"). It still takes the tap that focuses
     * where it lands — focusing is the camera's first job — it only stops being drawn.
     */
    var drawn = true
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * v105: false while the circle or the triangle is armed. Then a tap is theirs too (Marko, 2.10.2026:
     * "tapping is always 2 actions: it moves the shape to the position and do automatic correction and then
     * locks"): the square stays where it is and the tap's point goes to [onTapFor].
     */
    var tapMovesMe = true
    var onTapFor: ((Float, Float) -> Unit)? = null

    /** v103: false while the circle or the triangle is the armed mark: then a pinch is theirs. */
    var pinchResizesMe = true

    /** Fired when a pinch ends, with the new size, so it can be remembered. */
    var onResized: ((Float) -> Unit)? = null

    /** Where the last tap landed, normalised: the other marks under the same finger take it too (v125). */
    var tapX = 0.5f
        private set
    var tapY = 0.5f
        private set

    /** v125: the point is on the drawn box or within a finger's reach of it (what its own touch claims). */
    fun hits(nx: Float, ny: Float): Boolean {
        if (!drawn || visibility != VISIBLE || width <= 0 || height <= 0) return false
        val (hw, hh) = halves().let { it[0] to it[1] }
        val reach = 20f * density
        return Math.abs(nx * width - width * drawnCentreX()) <= hw + reach &&
            Math.abs(ny * height - height * drawnCentreY()) <= hh + reach
    }

    /** Half the box, across and down, in pixels. */
    private fun halves(): FloatArray = Mechanism.focusBoxHalves(size, width, height)

    /** The centre actually drawn: kept far enough in that the box stays on the picture. */
    private fun drawnCentreX(): Float =
        if (width <= 0) centreX else Mechanism.clampBoxCentre(centreX, halves()[0] / width)

    private fun drawnCentreY(): Float =
        if (height <= 0) centreY else Mechanism.clampBoxCentre(centreY, halves()[1] / height)

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
        if (width <= 0 || height <= 0) return floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f)
        val (hw, hh) = halves().let { (it[0] / width) to (it[1] / height) }
        val cx = drawnCentreX()
        val cy = drawnCentreY()
        return floatArrayOf(
            (cx - hw).coerceIn(0f, 1f),
            (cy - hh).coerceIn(0f, 1f),
            (cx + hw).coerceIn(0f, 1f),
            (cy + hh).coerceIn(0f, 1f)
        )
    }

    /**
     * Put the box on a point without touching it: the tap that focuses when
     * the box itself is not on the screen (the zones are up, or the clean
     * feed). The box is invisible then, but it is still where focus is, so it
     * moves; when it comes back it is on the thing that was tapped.
     */
    fun placeAt(x: Float, y: Float) {
        centreX = x.coerceIn(0f, 1f)
        centreY = y.coerceIn(0f, 1f)
        invalidate()
        onMoved?.invoke(centreX, centreY)
    }

    private companion object {
        /** Long enough to be a decision, short enough not to feel stuck. */
        const val HOLD_TO_DRAG_MS = 260L
        const val FAR_SLOP = 44f
    }

    /** The colour it is drawn in now; its rail key wears the same one (v107). */
    fun colour(): Int = when {
        tracking && lost -> Color.parseColor("#FF2D1F")
        tracking -> Color.parseColor("#33D17A")
        else -> when (state) {
            State.IDLE -> Color.parseColor("#CCFFFFFF")
            State.SEEKING -> Color.parseColor("#FFD400")
            State.LOCKED -> Color.parseColor("#12C46A")
            State.FAILED -> Color.parseColor("#FF2D1F")
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!drawn) return
        val cx = width * drawnCentreX()
        val cy = height * drawnCentreY()
        val (hw, hh) = halves().let { it[0] to it[1] }
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)

        paint.color = colour()
        // Half the weight it was. A focus box is a reference, not a graphic,
        // and a heavy one hides the very detail being judged.
        paint.strokeWidth = 1f * density

        // Corners rather than a full box, so it covers as little of the
        // subject as possible while still reading as a target.
        val arm = minOf(hw, hh) * 0.42f
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
        if (tracking) {
            // The search zone: a thin full square, faint, so the subject shows through.
            val alpha = paint.alpha
            paint.alpha = 110
            canvas.drawRect(cx - hw * searchFactor, cy - hh * searchFactor,
                cx + hw * searchFactor, cy + hh * searchFactor, paint)
            paint.alpha = alpha
            if (lost) {
                lostText.textSize = 13f * density
                canvas.drawText("LOST", cx, rect.bottom + 16f * density, lostText)
            }
        }
    }

    private var grabbed = false
    private var pressStart = 0L
    private var dragging = false
    private var pinching = false
    private var downX = 0f
    private var downY = 0f

    /**
     * Where a touch that began away from the box wants the box to go.
     *
     * Not moved on the way down any more: the first finger of a pinch lands
     * wherever it lands, and a box that jumped there before the second finger
     * arrived would be resized somewhere nobody meant. It goes there on a tap,
     * or when the hold turns the touch into a drag.
     */
    private var pendingX = Float.NaN
    private var pendingY = Float.NaN

    private fun takePending() {
        if (pendingX.isNaN()) return
        centreX = (pendingX / width).coerceIn(0.02f, 0.98f)
        centreY = (pendingY / height).coerceIn(0.02f, 0.98f)
        pendingX = Float.NaN
        pendingY = Float.NaN
        onMoved?.invoke(centreX, centreY)
        invalidate()
    }

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
        if (pinching) return@Runnable
        takePending()
        dragging = true
        state = State.IDLE
    }

    /** Fired on a tap that was not a drag: the caller decides what focus means. */
    var onTapped: (() -> Unit)? = null

    /**
     * Two fingers: the box's size.
     *
     * Multiplied by the pinch's own ratio each frame, so a pinch that doubles
     * the finger spread doubles the box, whatever size it started at, the way
     * a photo is zoomed.
     */
    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                removeCallbacks(holdToDrag)
                pendingX = Float.NaN
                pendingY = Float.NaN
                dragging = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // v103: a pinch resizes whichever mark is armed (orange); MainActivity carries it to the others
                if (!pinchResizesMe) return true
                size *= detector.scaleFactor
                onMoved?.invoke(centreX, centreY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (!pinchResizesMe) return
                onResized?.invoke(size)
                Trace.control("focus box", String.format("%.3f", size), "pinched")
            }
        }
    ).apply { isQuickScaleEnabled = false }

    /**
     * Only touches on the box itself belong to the box.
     *
     * This view covers the whole image, so claiming every touch would swallow
     * the tap that opens and closes the controls, which is exactly what it did
     * the first time. A touch outside the box is returned untouched and reaches
     * the preview underneath.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = width * drawnCentreX()
                val cy = height * drawnCentreY()
                val (hw, hh) = halves().let { it[0] to it[1] }
                val reach = 20f * density
                val insideBox = Math.abs(event.x - cx) <= hw + reach &&
                    Math.abs(event.y - cy) <= hh + reach

                // A tap anywhere on the picture puts the box there. It used to
                // refuse anything outside itself, so the only way to focus on
                // something was to drag the box onto it first, which is not
                // what tapping a viewfinder has ever meant.
                if (!insideBox) {
                    pendingX = event.x
                    pendingY = event.y
                } else {
                    pendingX = Float.NaN
                    pendingY = Float.NaN
                }
                grabbed = true
                pinching = false
                pressStart = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                dragging = false
                postDelayed(holdToDrag, HOLD_TO_DRAG_MS)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a pinch, never a tap or a drag.
                pinching = true
                removeCallbacks(holdToDrag)
                pendingX = Float.NaN
                pendingY = Float.NaN
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!grabbed || pinching) return grabbed
                // A long, deliberate sweep is a drag even without the wait.
                if (!dragging &&
                    (Math.abs(event.x - downX) > FAR_SLOP * density ||
                        Math.abs(event.y - downY) > FAR_SLOP * density)
                ) {
                    removeCallbacks(holdToDrag)
                    pendingX = Float.NaN
                    pendingY = Float.NaN
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
                val wasPinching = pinching
                grabbed = false
                dragging = false
                pinching = false
                // Anything that was not a drag or a pinch was a tap, however wobbly.
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    wasGrabbed && !wasDragging && !wasPinching
                ) {
                    tapX = (event.x / width).coerceIn(0f, 1f); tapY = (event.y / height).coerceIn(0f, 1f)
                    if (tapMovesMe) {
                        takePending()
                        onTapped?.invoke()
                    } else {
                        onTapFor?.invoke((event.x / width).coerceIn(0f, 1f), (event.y / height).coerceIn(0f, 1f))
                    }
                }
                pendingX = Float.NaN
                pendingY = Float.NaN
                return wasGrabbed
            }
        }
        return grabbed
    }
}
