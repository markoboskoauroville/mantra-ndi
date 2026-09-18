package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The trace, drawn over the picture it describes.
 *
 * Column for column with the image beneath it, which is the whole point: a
 * waveform is read by looking at something in the frame and then at the same
 * horizontal place on the trace. Overlaying it at the same width makes that
 * true by construction, so there is no scale to line up and no mental
 * arithmetic between the two.
 *
 * Channels are additive and half transparent, the way a parade laid on top of
 * itself behaves on a desk: where all three agree the trace goes white, and
 * where they part the colour names which channel is where.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    /** Any combination, because a parade and a luma trace answer different questions. */
    var channels: Set<Mechanism.WaveformChannel> = setOf(Mechanism.WaveformChannel.LUMA)
        set(value) { field = value; invalidate() }

    private var traces: Map<Mechanism.WaveformChannel, IntArray> = emptyMap()
    private var columns = 0
    private var bins = 0
    private var peak = 1

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gratPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33FFFFFF")
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun setTraces(
        traces: Map<Mechanism.WaveformChannel, IntArray>,
        columns: Int,
        bins: Int
    ) {
        this.traces = traces
        this.columns = columns
        this.bins = bins
        this.peak = traces.values.mapNotNull { it.maxOrNull() }.maxOrNull()?.coerceAtLeast(1) ?: 1
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (columns == 0 || bins == 0 || traces.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        // The reference lines a colourist reads against: black, mid, and the
        // top of legal range.
        gratPaint.strokeWidth = 1f * density
        for (fraction in listOf(0f, 0.5f, 1f)) {
            val y = h - h * fraction
            canvas.drawLine(0f, y, w, y, gratPaint)
        }

        val columnWidth = w / columns
        val binHeight = h / bins

        for ((channel, data) in traces) {
            val base = when (channel) {
                Mechanism.WaveformChannel.RED -> Color.rgb(255, 60, 60)
                Mechanism.WaveformChannel.GREEN -> Color.rgb(60, 255, 90)
                Mechanism.WaveformChannel.BLUE -> Color.rgb(80, 130, 255)
                Mechanism.WaveformChannel.LUMA -> Color.rgb(235, 235, 235)
            }
            for (x in 0 until columns) {
                val offset = x * bins
                for (b in 0 until bins) {
                    val count = data[offset + b]
                    if (count == 0) continue
                    // Square root, because a waveform is about where the
                    // signal reaches rather than how many pixels happen to sit
                    // there, and a linear scale hides everything but the sky.
                    val strength = Math.sqrt(count.toDouble() / peak).toFloat()
                    dotPaint.color = Color.argb(
                        (strength * 150f).toInt().coerceIn(12, 150),
                        Color.red(base), Color.green(base), Color.blue(base)
                    )
                    val top = h - (b + 1) * binHeight
                    canvas.drawRect(
                        x * columnWidth, top, (x + 1) * columnWidth, top + binHeight, dotPaint
                    )
                }
            }
        }
    }
}
