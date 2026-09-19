package com.mantraproductions.ndi

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Log to Rec.709 on the screen only.
 *
 * A log picture is flat by design, which is right for grading and wrong for
 * judging exposure and focus on set. Every cinema camera therefore has a
 * monitor LUT: the operator sees a corrected picture, the recording and the
 * feed stay flat. This is that.
 *
 * **It must never touch what is sent or recorded.** That is the whole point,
 * and it is why this is a RenderEffect on the preview View rather than a tone
 * curve on the capture request: a tone curve belongs to the capture session
 * and would reach the encoder, which would bake the correction into the file
 * and throw away the latitude the log curve was chosen for.
 *
 * The correction runs as a GPU shader, so it costs the preview surface a pass
 * and costs the encoder nothing. The curve itself is computed once on the CPU
 * into a 256 pixel strip and sampled in the shader, rather than being
 * reimplemented per curve in shader code: the maths already exists in
 * LogCurves, it is tested, and having it in two places is how they drift.
 */
object PreviewLut {

    /**
     * Whether this phone can do it at all.
     *
     * Runtime shaders arrived in Android 13. Below that there is no way to put
     * an arbitrary curve on a View's pixels without rendering the preview
     * through a GL surface of our own, which is a different piece of work.
     */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private const val SHADER = """
        uniform shader content;
        uniform shader curve;

        half4 main(float2 coord) {
            half4 c = content.eval(coord);
            // Each channel is looked up separately, which is what a 1D LUT is.
            // The strip is 256 wide, so the sample lands on the centre of the
            // matching texel rather than on a boundary between two.
            half r = curve.eval(float2(c.r * 255.0 + 0.5, 0.5)).r;
            half g = curve.eval(float2(c.g * 255.0 + 0.5, 0.5)).r;
            half b = curve.eval(float2(c.b * 255.0 + 0.5, 0.5)).r;
            return half4(r, g, b, c.a);
        }
    """

    /**
     * The correction strip for a curve: what each input level should look like
     * once it has been taken back to scene light and re-encoded for a screen.
     */
    private fun strip(curve: LogCurves.Curve): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256)
        for (i in 0 until 256) {
            val coded = i / 255.0
            // Log encoded value back to scene light, then to Rec.709 for a
            // display. The inverse of what the camera was asked to do.
            val linear = LogCurves.decode(curve, coded).coerceAtLeast(0.0)
            val display = LogCurves.encode(LogCurves.Curve.REC709, linear)
                .coerceIn(0.0, 1.0)
            val v = (display * 255.0).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 1)
        return bitmap
    }

    /**
     * Turns the correction on or off for one view.
     *
     * @return true if the state asked for was actually achieved, so a caller
     *         can say plainly when a phone cannot do it rather than lighting a
     *         button that does nothing.
     */
    fun apply(view: View, curve: LogCurves.Curve, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return !enabled
        }
        return try {
            if (!enabled || curve == LogCurves.Curve.REC709) {
                // Nothing to correct on a picture that is already Rec.709.
                view.setRenderEffect(null)
                return true
            }

            val shader = RuntimeShader(SHADER)
            shader.setInputShader(
                "curve",
                BitmapShader(strip(curve), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            view.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content")
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "preview lut", e)
            runCatching { view.setRenderEffect(null) }
            false
        }
    }

    private const val TAG = "PreviewLut"
}
