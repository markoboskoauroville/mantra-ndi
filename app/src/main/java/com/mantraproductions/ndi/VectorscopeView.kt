package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * A vectorscope, and a way to balance with it.
 *
 * A histogram answers how bright, which the eye can already judge. A
 * vectorscope answers which way the colour leans, which the eye cannot, because
 * it adapts to a cast within seconds of looking at one. That is why it is on
 * every grading desk and why it belongs here rather than a histogram.
 *
 * Neutral is the centre. A scene under warm light sits towards red; under
 * fluorescent it leans green. Dragging the marker back to the middle applies
 * the opposite correction to the camera, which is the same gesture as pulling
 * the balance in Resolve and the same result: the cloud moves with it, live,
 * so the operator is looking at the answer rather than guessing at it.
 */
class VectorscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private var grid: IntArray = IntArray(0)
    private var gridSize = 0
    private var peak = 1

    /** Where the scene's chroma actually sits, as fractions from centre. */
    private var centroidU = 0f
    private var centroidV = 0f

    /** Where the operator has dragged the correction to. */
    var offsetU = 0f
        private set
    var offsetV = 0f
        private set

    /** Fired continuously while dragging, with the current offset. */
    var onBalanceMoved: ((Float, Float) -> Unit)? = null
    var onBalanceReleased: (() -> Unit)? = null

    /** Two taps: close and give the screen back. */
    var onDismiss: (() -> Unit)? = null

    /** One tap: balance back to whatever the camera itself decided. */
    var onResetBalance: (() -> Unit)? = null

    /**
     * Taps are counted, and the count is shown.
     *
     * The previous version cancelled its long press on any ACTION_MOVE, and a
     * finger on glass always produces one, so nothing ever fired and the scope
     * could not be closed at all. Movement is now measured against a slop
     * threshold rather than merely detected, and the gestures are counted taps
     * rather than a hold, because a count can be displayed and a hold cannot:
     * the operator sees 1 and knows a second tap closes it.
     */
    private var tapCount = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val tapWindow = Runnable {
        // A tap that stood alone was a reset.
        if (tapCount == 1) onResetBalance?.invoke()
        tapCount = 0
        invalidate()
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#55FFFFFF")
    }
    private val gratPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#33FFFFFF")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centroidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#FFD400")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FaderView.AMBER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C8894")
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
    }

    fun setFrame(newGrid: IntArray, size: Int, cu: Float, cv: Float) {
        grid = newGrid
        gridSize = size
        hasFrame = true
        peak = (newGrid.maxOrNull() ?: 1).coerceAtLeast(1)
        centroidU = cu
        centroidV = cv
        postInvalidateOnAnimation()
    }

    /** True once a frame has been supplied, so an empty scope can say why. */
    var hasFrame: Boolean = false
        private set

    fun reset() {
        offsetU = 0f
        offsetV = 0f
        tapCount = 0
        removeCallbacks(tapWindow)
        invalidate()
    }

    private companion object {
        /** Beyond this it is a drag; within it, a wandering finger. */
        const val TOUCH_SLOP = 14f
        const val TAP_WINDOW_MS = 450L
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 4f * density

        canvas.drawCircle(cx, cy, radius, framePaint)
        canvas.drawCircle(cx, cy, radius * 0.5f, gratPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gratPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gratPaint)

        // The six broadcast targets, in their own colours, so a face or a sky
        // can be checked against where it is meant to sit.
        val targets = listOf(
            "R" to Color.parseColor("#FF4444"), "Mg" to Color.parseColor("#FF44FF"),
            "B" to Color.parseColor("#4488FF"), "Cy" to Color.parseColor("#44FFFF"),
            "G" to Color.parseColor("#44FF66"), "Yl" to Color.parseColor("#FFFF44")
        )
        targets.forEachIndexed { index, (label, colour) ->
            val angle = Math.toRadians(index * 60.0 + 13.0)
            val x = cx + (radius * 0.75f * Math.cos(angle)).toFloat()
            val y = cy - (radius * 0.75f * Math.sin(angle)).toFloat()
            labelPaint.color = colour
            canvas.drawText(label, x, y, labelPaint)
        }

        // The cloud itself.
        if (gridSize > 0) {
            val cell = (radius * 2f) / gridSize
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val count = grid[y * gridSize + x]
                    if (count == 0) continue
                    val px = cx - radius + x * cell
                    val py = cy - radius + y * cell
                    if (hypot(px - cx, py - cy) > radius) continue
                    val strength = (count.toFloat() / peak).coerceIn(0f, 1f)
                    dotPaint.color = Color.argb(
                        (40 + strength * 200).toInt().coerceIn(0, 255), 140, 255, 190
                    )
                    canvas.drawRect(px, py, px + cell, py + cell, dotPaint)
                }
            }
        }

        // Where the scene's average colour is, which is the cast.
        val centroidX = cx + centroidU * radius
        val centroidY = cy - centroidV * radius
        canvas.drawCircle(centroidX, centroidY, 7f * density, centroidPaint)

        // The handle the operator drags.
        val handleX = cx + offsetU * radius
        val handleY = cy - offsetV * radius
        canvas.drawCircle(handleX, handleY, 13f * density, handlePaint)

        // The count, so a gesture in progress is visible rather than guessed at.
        labelPaint.color = if (tapCount > 0) FaderView.AMBER else Color.parseColor("#7C8894")
        canvas.drawText(
            when (tapCount) {
                0 -> "ONE TAP RESETS, TWO TAPS CLOSE"
                else -> "TAP 1   TAP AGAIN TO CLOSE"
            },
            cx, height - 12f * density, labelPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 4f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Only real movement is movement. A thumb always wanders a few
                // pixels and treating that as a drag is what jammed this view.
                if (!dragging &&
                    (Math.abs(event.x - downX) > TOUCH_SLOP * density ||
                        Math.abs(event.y - downY) > TOUCH_SLOP * density)
                ) {
                    dragging = true
                    removeCallbacks(tapWindow)
                    tapCount = 0
                }
                if (dragging) {
                    offsetU = ((event.x - cx) / radius).coerceIn(-1f, 1f)
                    offsetV = ((cy - event.y) / radius).coerceIn(-1f, 1f)
                    onBalanceMoved?.invoke(offsetU, offsetV)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    onBalanceReleased?.invoke()
                    return true
                }

                tapCount++
                removeCallbacks(tapWindow)
                if (tapCount >= 2) {
                    tapCount = 0
                    invalidate()
                    onDismiss?.invoke()
                } else {
                    // Long enough to be a second tap, short enough that a
                    // single one does not feel ignored.
                    postDelayed(tapWindow, TAP_WINDOW_MS)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
