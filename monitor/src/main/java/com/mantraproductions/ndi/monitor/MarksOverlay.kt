package com.mantraproductions.ndi.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * v117: the camera's three marks drawn over the monitor's picture, where the camera has them and in the colour they
 * have there (square focus, circle exposure, triangle white balance). The camera sends them in STREAM coordinates;
 * [toScreen] is the same matrix that fits the picture on this screen, so a mark sits on the same thing it sits on there.
 */
class MarksOverlay(context: Context) : View(context) {
    data class Mark(val kind: Int, val l: Float, val t: Float, val r: Float, val b: Float, val argb: Int, val shown: Boolean)

    var marks: List<Mark> = emptyList()
        set(value) { field = value; invalidate() }
    var toScreen: Matrix? = null
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val m = toScreen ?: return
        for (k in marks) {
            if (!k.shown) continue
            val p = floatArrayOf(k.l * width, k.t * height, k.r * width, k.b * height)
            m.mapPoints(p)
            val l = minOf(p[0], p[2]); val r = maxOf(p[0], p[2]); val t = minOf(p[1], p[3]); val b = maxOf(p[1], p[3])
            val cx = (l + r) / 2; val cy = (t + b) / 2
            paint.color = k.argb
            when (k.kind) {
                0 -> { // the square's corners, as the camera draws them
                    val arm = minOf(r - l, b - t) * 0.42f
                    canvas.drawLine(l, t, l + arm, t, paint); canvas.drawLine(l, t, l, t + arm, paint)
                    canvas.drawLine(r, t, r - arm, t, paint); canvas.drawLine(r, t, r, t + arm, paint)
                    canvas.drawLine(l, b, l + arm, b, paint); canvas.drawLine(l, b, l, b - arm, paint)
                    canvas.drawLine(r, b, r - arm, b, paint); canvas.drawLine(r, b, r, b - arm, paint)
                }
                1 -> canvas.drawCircle(cx, cy, minOf(r - l, b - t) / 2, paint)
                else -> {
                    val hw = (r - l) / 2; val hh = (b - t) / 2
                    path.reset(); path.moveTo(cx, cy - hh); path.lineTo(cx + hw, cy + hh); path.lineTo(cx - hw, cy + hh); path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
