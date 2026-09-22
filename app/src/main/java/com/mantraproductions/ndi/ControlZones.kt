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
 * The picture is the control surface.
 *
 * Four invisible columns across the shot, each one a parameter. A finger put
 * anywhere in a column and slid up or down moves that parameter; nothing is
 * drawn but a small word and its value at the foot of each column, so the
 * operator sees what they are holding without a slider taking a quarter of the
 * screen away from the picture.
 *
 * This is how a camera is actually held. A fader needs to be found and then
 * hit; a column is a third of the frame wide and can be found by feel, in the
 * dark, without looking away from the shot — which is the only time any of
 * these get touched.
 *
 * It is exclusive with the focus box on purpose, and that is the whole reason
 * the rail has a key to put it away: a tap that could mean "focus here" and
 * could mean "start changing ISO" is a tap that means neither.
 */
class ControlZones @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * One column.
     *
     * [live] false is a parameter this lens does not have — a phone's iris is
     * fixed, because the blades cost room a phone does not have. It keeps its
     * column and shows its value, dimmed, rather than disappearing and
     * shuffling the other three under his thumb.
     */
    data class Zone(
        val title: String,
        var value: String = "—",
        var live: Boolean = true
    )

    var zones: List<Zone> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * A drag, as a fraction of the column's height. Up is positive, because up
     * is more of everything: more light, more ISO, further away.
     */
    var onDrag: ((index: Int, delta: Float) -> Unit)? = null

    /** Told when a column is grabbed and let go, so the title can light up. */
    var onGrab: ((index: Int?) -> Unit)? = null

    private var held: Int? = null
    private var lastY = 0f

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        textSize = density(8f)
        letterSpacing = 0.1f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(13f)
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density(0.5f)
    }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun density(dp: Float) = dp * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val count = zones.size
        if (count == 0 || width == 0) return
        val columnWidth = width.toFloat() / count

        // At the top, level with the first keys on the rail.
        //
        // They were at the foot of each column, and the foot of the picture is
        // where the geometry readout lives — so on a real phone the two sat on
        // top of each other and neither could be read. The top of the picture
        // is also where a hand already is: it is beside L1, and a value read
        // there is read without the eye leaving the frame.
        val titleY = density(14f)
        val valueY = density(30f)

        for (i in zones.indices) {
            val zone = zones[i]
            val left = columnWidth * i
            val centre = left + columnWidth / 2f

            // The column being held gets the faintest wash there is. Enough to
            // confirm the finger landed where it was aimed; not enough to be a
            // panel sitting on the shot.
            if (held == i && zone.live) {
                wash.color = Color.argb(28, 51, 209, 122)
                canvas.drawRect(left, 0f, left + columnWidth, height.toFloat(), wash)
            }
            // A divider marks a column that can be dragged. The iris has no
            // fader on a phone, so it is a readout and is not fenced off as if
            // there were something in it to grab.
            if (i > 0 && zone.live && zones[i - 1].live) {
                divider.color = Color.argb(34, 255, 255, 255)
                canvas.drawLine(left, density(6f), left, height - density(6f), divider)
            }

            val tint = when {
                !zone.live -> Color.argb(120, 122, 128, 135)
                held == i -> RailButton.GREEN
                else -> Color.argb(210, 235, 238, 240)
            }
            title.color = Color.argb(
                if (zone.live) 170 else 90, Color.red(tint), Color.green(tint), Color.blue(tint)
            )
            value.color = tint
            // Drawn with a shadow because it sits on a picture, and a picture
            // can be any colour at all.
            title.setShadowLayer(density(2f), 0f, 0f, Color.BLACK)
            value.setShadowLayer(density(2f), 0f, 0f, Color.BLACK)
            canvas.drawText(zone.title, centre, titleY, title)
            canvas.drawText(zone.value, centre, valueY, value)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val count = zones.size
        if (count == 0) return false
        val columnWidth = width.toFloat() / count

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = (event.x / columnWidth).toInt().coerceIn(0, count - 1)
                // A column with nothing behind it is not grabbed. The iris is
                // fixed on a phone and a fixed lens has no focus travel: a
                // column that lights up and then does nothing is worse than
                // one that never answers.
                if (zones.getOrNull(index)?.live != true) return false
                held = index
                lastY = event.y
                onGrab?.invoke(index)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = held ?: return false
                // Relative to the last position rather than to where the finger
                // landed: a parameter that jumps to an absolute value the moment
                // it is touched is a parameter that cannot be nudged.
                val delta = (lastY - event.y) / height.coerceAtLeast(1)
                lastY = event.y
                if (zones.getOrNull(index)?.live == true) onDrag?.invoke(index, delta)
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
