package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * One action key, drawn in the black beside the picture.
 *
 * Grey when it is not doing anything and green when it is, which is the whole
 * language of this camera: the operator should be able to tell what is on from
 * across a room without reading a word. A third state, dark, is for a key that
 * cannot do its job on this phone — a fourth lens on a phone with three, a
 * lamp on a lens with no lamp. Dark rather than hidden, so the rail does not
 * reshuffle itself between phones and a hand learns where things are.
 *
 * A View rather than a Button because a MaterialButton under a Material theme
 * tints itself from the theme and ignores what it is told, which is how v67
 * shipped four keys the wrong colour. Everything here is drawn.
 */
class RailButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { OFF, ON, DEAD, ARMED }

    /**
     * A key can carry a mark instead of a word.
     *
     * Drawn rather than typed. The gear and the arrows exist in Unicode, but
     * whether a given phone's monospace face has them is not something to find
     * out on a shoot — a missing glyph is a hollow box, and a hollow box in the
     * corner of a camera is indistinguishable from a bug.
     */
    enum class Glyph { NONE, GEAR, UP, DOWN }

    var glyph: Glyph = Glyph.NONE
        set(value) { field = value; describe(); invalidate() }

    var label: String = ""
        set(value) { field = value; describe(); invalidate() }

    /** The small word under the label, for what is in a LUT slot. */
    var sub: String? = null
        set(value) { field = value; describe(); invalidate() }

    var state: State = State.OFF
        set(value) { field = value; describe(); invalidate() }

    /**
     * What this key is, in the view tree.
     *
     * Everything on this key is drawn on a canvas, so without this there is no
     * text anywhere for anything to read: not a screen reader, and not the
     * bench, which drives the app by finding what the screen says. The first
     * attempt to tap HX from a script answered "nothing on the screen says HX"
     * while HX was plainly on the screen.
     */
    private fun describe() {
        val what = when {
            glyph != Glyph.NONE -> glyph.name.lowercase() + (sub?.let { " $it" } ?: "")
            sub.isNullOrBlank() -> label
            else -> "$label $sub"
        }
        contentDescription = "$what, ${state.name.lowercase()}"
    }

    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        // THE WORD IS THE KEY.
        //
        // *"We are losing the space with actually creating button rectangles.
        // There should be only text, so that the buttons are invisible and they
        // can be much closer."* He is right and he asked for it at the start: a
        // box around a word costs an outline, a corner radius, an inset and a
        // margin, and every one of those is taken off the word. Without them
        // the same rail carries the same keys with the letters half as big
        // again, which is the difference between a key that is read and a key
        // that is recognised by position and hoped for.
        //
        // Grey is off and green is on. That is the whole language and it does
        // not need a border to say it.
        textSize = density(13f)
        letterSpacing = 0.04f
    }
    private val whisper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        textSize = density(9f)
    }

    init {
        isClickable = true
        isFocusable = true
    }

    private fun density(dp: Float) = dp * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val tint = when (state) {
            State.ON -> GREEN
            State.OFF -> GREY
            State.ARMED -> AMBER
            State.DEAD -> DEAD
        }

        word.color = tint
        whisper.color = Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))

        if (glyph != Glyph.NONE) {
            drawGlyph(canvas, tint)
            return
        }

        val hasSub = !sub.isNullOrBlank()
        val metrics = word.fontMetrics
        val centre = height / 2f
        if (hasSub) {
            canvas.drawText(label, width / 2f, centre - density(1.5f), word)
            canvas.drawText(sub!!, width / 2f, centre + density(10f), whisper)
        } else {
            canvas.drawText(
                label, width / 2f, centre - (metrics.ascent + metrics.descent) / 2f, word
            )
        }
    }

    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density(1.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markPath = Path()

    private fun drawGlyph(canvas: Canvas, tint: Int) {
        val cx = width / 2f
        val cy = height / 2f
        // Sized off the key rather than fixed, so the same code draws a mark
        // that fits whichever way the rail is running.
        val r = minOf(width, height) * 0.24f
        mark.color = tint
        markPath.reset()

        when (glyph) {
            Glyph.GEAR -> {
                // A heavy ring with six short teeth sitting on it.
                //
                // The first attempt was a thin circle with eight long spokes
                // radiating well past it, which at this size is a sun, not a
                // gear — and a sun in the corner of a camera reads as
                // brightness. The ring has to dominate and the teeth have to
                // touch it.
                val ring = r * 0.66f
                mark.strokeWidth = density(2.2f)
                canvas.drawCircle(cx, cy, ring, mark)
                mark.strokeWidth = density(2.6f)
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0)
                    val sx = cx + (ring * 0.92f) * cos(a).toFloat()
                    val sy = cy + (ring * 0.92f) * sin(a).toFloat()
                    val ex = cx + (ring * 1.42f) * cos(a).toFloat()
                    val ey = cy + (ring * 1.42f) * sin(a).toFloat()
                    canvas.drawLine(sx, sy, ex, ey, mark)
                }
                mark.strokeWidth = density(1.4f)
                // The hole, which is what makes it a gear rather than a wheel.
                canvas.drawCircle(cx, cy, ring * 0.30f, mark)
            }
            Glyph.DOWN -> {
                markPath.moveTo(cx - r, cy - r * 0.45f)
                markPath.lineTo(cx, cy + r * 0.55f)
                markPath.lineTo(cx + r, cy - r * 0.45f)
                canvas.drawPath(markPath, mark)
            }
            Glyph.UP -> {
                markPath.moveTo(cx - r, cy + r * 0.45f)
                markPath.lineTo(cx, cy - r * 0.55f)
                markPath.lineTo(cx + r, cy + r * 0.45f)
                canvas.drawPath(markPath, mark)
            }
            Glyph.NONE -> Unit
        }

        // The page number still belongs under the arrow.
        sub?.takeIf { it.isNotBlank() }?.let {
            whisper.color = Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawText(it, cx, height - density(3f), whisper)
        }
    }

    override fun performClick(): Boolean {
        // A dead key is not a key. Swallowing the tap here rather than at every
        // call site is what keeps "never ship a setting that does nothing"
        // true by construction.
        if (state == State.DEAD) return false
        return super.performClick()
    }

    companion object {
        val GREEN: Int = Color.parseColor("#33D17A")
        val GREY: Int = Color.parseColor("#7A8087")
        val AMBER: Int = Color.parseColor("#E8A33D")
        val DEAD: Int = Color.parseColor("#33383E")
    }
}
