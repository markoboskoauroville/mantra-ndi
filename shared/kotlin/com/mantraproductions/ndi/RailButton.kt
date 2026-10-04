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

    enum class State { OFF, ON, DEAD, ARMED, SHOWN }

    /**
     * A key can carry a mark instead of a word.
     *
     * Drawn rather than typed. The gear and the arrows exist in Unicode, but
     * whether a given phone's monospace face has them is not something to find
     * out on a shoot — a missing glyph is a hollow box, and a hollow box in the
     * corner of a camera is indistinguishable from a bug.
     */
    enum class Glyph { NONE, GEAR, UP, DOWN, CAMERA, SQUARE, CIRCLE, TRIANGLE }

    var glyph: Glyph = Glyph.NONE
        set(value) { field = value; describe(); invalidate() }

    /** v107: a colour that overrides the state's, for the mark keys that wear their shape's colour. */
    var tint: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    /** v107: a bar under the key: this mark is the armed one (the pinch's and the tap's). */
    var marked = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var label: String = ""
        set(value) { field = value; describe(); invalidate() }

    /** The small word under the label, for what is in a LUT slot. */
    var sub: String? = null
        set(value) { field = value; describe(); invalidate() }

    /** v130: how much larger than a rail key the words may be drawn (the lens drawer's keys have the room). */
    var scale: Float = 1f
        set(value) { field = value; invalidate() }

    var state: State = State.OFF
        set(value) { field = value; describe(); invalidate() }

    /** v134: a thin line on the key's leading edge, so the lens drawer's keys do not read as one stream of text
     *  (Marko, 4.10.2026). Upright the rail runs across and the line stands; in landscape it lies. */
    var divider = false
        set(value) { if (field != value) { field = value; invalidate() } }

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
            glyph == Glyph.CAMERA -> "snap"
            glyph == Glyph.SQUARE -> "focus square"
            glyph == Glyph.CIRCLE -> "exposure circle"
            glyph == Glyph.TRIANGLE -> "white balance triangle"
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
            State.SHOWN -> WHITE
        }.let { if (state != State.OFF && state != State.DEAD) this.tint ?: it else it }
        if (marked) {
            mark.color = tint
            mark.strokeWidth = density(2.2f)
            canvas.drawLine(width * 0.32f, height - density(4f), width * 0.68f, height - density(4f), mark)
            mark.strokeWidth = density(1.4f)
        }

        if (divider) {
            val across = (parent as? android.widget.LinearLayout)?.orientation != android.widget.LinearLayout.VERTICAL
            rule.strokeWidth = density(1f)
            if (across) canvas.drawLine(0.5f, height * 0.18f, 0.5f, height * 0.82f, rule)
            else canvas.drawLine(width * 0.18f, 0.5f, width * 0.82f, 0.5f, rule)
        }

        word.color = tint
        whisper.color = Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))

        if (glyph != Glyph.NONE) {
            drawGlyph(canvas, tint)
            return
        }

        // SHRINK TO FIT. Eleven keys across a phone held upright left
        // "SHOOT", "FALSE" and "ZEBRA" clipped at both ends (v85 on the
        // emulator). A word is drawn at its full size when it fits, and
        // smaller, never clipped, when it does not.
        fit(word, label, density(13f * scale))
        sub?.let { fit(whisper, it, density(9f * scale)) }

        val hasSub = !sub.isNullOrBlank()
        val metrics = word.fontMetrics
        val centre = height / 2f
        if (hasSub) {
            canvas.drawText(label, width / 2f, centre - density(1.5f * scale), word)
            canvas.drawText(sub!!, width / 2f, centre + density(10f * scale), whisper)
        } else {
            canvas.drawText(
                label, width / 2f, centre - (metrics.ascent + metrics.descent) / 2f, word
            )
        }
    }

    private fun fit(paint: Paint, text: String, full: Float) {
        paint.textSize = full
        val room = width - density(3f)
        val needed = paint.measureText(text)
        if (needed > room && needed > 0f) paint.textSize = (full * room / needed).coerceAtLeast(density(6f))
    }

    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density(1.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markPath = Path()
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A5058") }

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
            Glyph.CAMERA -> {
                // SNAP, v88: *"put the icon of this application."* The launcher
                // icon's drawing (res/drawable/ic_launcher_foreground.xml),
                // in outline: its 42 x 32 body on a 108 grid, scaled to the key.
                val u = r * 2.3f / 42f
                val x0 = cx - 21f * u
                val y0 = cy - 16f * u
                markPath.moveTo(x0 + 12f * u, y0)
                markPath.lineTo(x0 + 26f * u, y0)
                markPath.lineTo(x0 + 29f * u, y0 + 5f * u)
                markPath.lineTo(x0 + 42f * u, y0 + 5f * u)
                markPath.lineTo(x0 + 42f * u, y0 + 32f * u)
                markPath.lineTo(x0, y0 + 32f * u)
                markPath.lineTo(x0, y0 + 5f * u)
                markPath.lineTo(x0 + 9f * u, y0 + 5f * u)
                markPath.close()
                mark.strokeWidth = density(1.8f)
                canvas.drawPath(markPath, mark)
                canvas.drawCircle(cx, y0 + 18f * u, 9f * u, mark)
                mark.strokeWidth = density(1.4f)
            }
            // v103: the three marks' keys draw the mark itself (square focus, circle exposure, triangle white balance)
            // v108: a letter inside the square and the circle (Marko, 2.10.2026: "square is F, circle is E"); the
            // triangle is filled instead of a W since v109. The marks
            // are drawn a little larger than v103's so the letter has room and stays readable.
            Glyph.SQUARE -> {
                mark.strokeWidth = density(1.8f)
                canvas.drawRect(cx - r * 1.05f, cy - r * 1.05f, cx + r * 1.05f, cy + r * 1.05f, mark)
                letter(canvas, "F", cx, cy, r * 1.15f, tint)
            }
            Glyph.CIRCLE -> {
                mark.strokeWidth = density(1.8f)
                canvas.drawCircle(cx, cy, r * 1.1f, mark)
                letter(canvas, "E", cx, cy, r * 1.15f, tint)
            }
            Glyph.TRIANGLE -> {
                mark.strokeWidth = density(1.8f)
                markPath.moveTo(cx, cy - r * 1.15f)
                markPath.lineTo(cx + r * 1.25f, cy + r * 1.0f)
                markPath.lineTo(cx - r * 1.25f, cy + r * 1.0f)
                markPath.close()
                // v110: a WHITE fill, and only the outline in the key's colour (Marko, 2.10.2026: "the triangle has a
                // fill which is white, but the outline changes its color between green, white, and yellow"). v109
                // filled it with the outline's colour. Off, both are grey.
                mark.style = Paint.Style.FILL
                mark.color = if (state == State.OFF || state == State.DEAD) tint else WHITE
                canvas.drawPath(markPath, mark)
                mark.style = Paint.Style.STROKE
                mark.color = tint
                mark.strokeWidth = density(2.6f)
                canvas.drawPath(markPath, mark)
                mark.strokeWidth = density(1.8f)
            }
            Glyph.NONE -> Unit
        }

        // The page number still belongs under the arrow.
        sub?.takeIf { it.isNotBlank() }?.let {
            whisper.color = Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawText(it, cx, height - density(3f), whisper)
        }
    }

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    /** A letter centred on (x, y), [size] tall, in the key's colour. */
    private fun letter(canvas: Canvas, text: String, x: Float, y: Float, size: Float, tint: Int) {
        letterPaint.color = tint
        letterPaint.textSize = size
        val m = letterPaint.fontMetrics
        canvas.drawText(text, x, y - (m.ascent + m.descent) / 2f, letterPaint)
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
        val WHITE: Int = Color.parseColor("#F2F2F2")
    }
}
