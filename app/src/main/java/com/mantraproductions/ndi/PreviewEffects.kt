package com.mantraproductions.ndi

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Everything drawn onto the preview pixels, in one pass.
 *
 * One shader rather than two, on purpose. A View has a single render effect,
 * so two features that each set their own would silently cancel each other:
 * turn on peaking and the monitor LUT disappears, and nothing about the screen
 * tells you why. Combining them means both can be on, which is the normal way
 * to work anyway, since peaking is judged against a corrected picture.
 *
 * Both are preview only. Neither reaches the encoder, because both live on the
 * View rather than on the capture request. That is the whole reason to do it
 * here: a tone curve or an overlay on the capture session would be baked into
 * the recording and into the NDI feed.
 */
object PreviewEffects {

    /** Runtime shaders arrived in Android 13; below that, none of this runs. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    enum class PeakColour(val label: String, val colour: Int) {
        RED("Red", Color.rgb(255, 40, 40)),
        GREEN("Green", Color.rgb(40, 255, 90)),
        YELLOW("Yellow", Color.rgb(255, 210, 40)),
        WHITE("White", Color.rgb(255, 255, 255))
    }

    /**
     * The correction and the edge detector, in that order.
     *
     * Peaking is measured on the original pixels rather than on the corrected
     * ones. A log picture is low contrast by design, and a correction that
     * stretches it also stretches the noise; measuring edges first means the
     * threshold stays meaningful whether or not the LUT is on, so turning the
     * LUT on and off does not change what is in focus.
     */
    private const val SHADER = """
        uniform shader content;
        uniform shader curve;
        uniform half cubeSize;
        uniform half useLut;
        uniform half usePeak;
        uniform half threshold;
        uniform half3 peakTint;
        uniform half lutSize;

        // The trace, drawn in the same pass and the same coordinate space as
        // the picture it describes.
        uniform half useWave;
        uniform half waveTop;      // where the trace starts, in pixels
        uniform half waveHeight;
        uniform half viewHeight;
        uniform half waveLuma;
        uniform half waveRed;
        uniform half waveGreen;
        uniform half waveBlue;

        half luma(half4 c) {
            return dot(c.rgb, half3(0.299, 0.587, 0.114));
        }

        // How much of this column sits at this brightness.
        //
        // Column x of the trace reads column x of the picture, which is the
        // whole point of overlaying it: a part of the frame and its trace are
        // at the same horizontal position by construction, with no scale to
        // line up. Forty-eight samples down the column is enough to show the
        // shape of a real image and cheap enough to run every frame.
        half columnDensity(float2 coord, half level, half band, int channel) {
            half hits = 0.0;
            for (int i = 0; i < 48; i++) {
                half t = (half(i) + 0.5) / 48.0;
                half4 s = content.eval(float2(coord.x, t * viewHeight));
                half v;
                if (channel == 0) { v = s.r; }
                else if (channel == 1) { v = s.g; }
                else if (channel == 2) { v = s.b; }
                else { v = dot(s.rgb, half3(0.299, 0.587, 0.114)); }
                if (abs(v - level) < band) { hits += 1.0; }
            }
            return hits / 48.0;
        }

        half4 main(float2 coord) {
            half4 c = content.eval(coord);

            // Sobel, measured on what the sensor sent.
            half edge = 0.0;
            if (usePeak > 0.5) {
                half tl = luma(content.eval(coord + float2(-1.0, -1.0)));
                half tc = luma(content.eval(coord + float2( 0.0, -1.0)));
                half tr = luma(content.eval(coord + float2( 1.0, -1.0)));
                half ml = luma(content.eval(coord + float2(-1.0,  0.0)));
                half mr = luma(content.eval(coord + float2( 1.0,  0.0)));
                half bl = luma(content.eval(coord + float2(-1.0,  1.0)));
                half bc = luma(content.eval(coord + float2( 0.0,  1.0)));
                half br = luma(content.eval(coord + float2( 1.0,  1.0)));

                half gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
                half gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
                edge = sqrt(gx * gx + gy * gy);
            }

            half3 rgb = c.rgb;
            if (useLut > 0.5) {
                // A 3D lookup, read from the cube laid out as slices side by
                // side. Two fetches and a blend between neighbouring blue
                // slices, which is the standard way a GPU carries a cube.
                half last = lutSize - 1.0;
                half br = clamp(c.b, 0.0, 1.0) * last;
                half b0 = floor(br);
                half b1 = min(b0 + 1.0, last);
                half bf = br - b0;

                half gx = clamp(c.r, 0.0, 1.0) * last + 0.5;
                half gy = clamp(c.g, 0.0, 1.0) * last + 0.5;

                half4 s0 = curve.eval(float2(b0 * lutSize + gx, gy));
                half4 s1 = curve.eval(float2(b1 * lutSize + gx, gy));
                rgb = mix(s0.rgb, s1.rgb, bf);
            }

            if (usePeak > 0.5 && edge > threshold) {
                // Replaced rather than blended: a half transparent marker over
                // a busy texture is a marker nobody can see.
                rgb = peakTint;
            }

            if (useWave > 0.5 &&
                coord.y >= waveTop && coord.y <= waveTop + waveHeight) {
                // Black at the bottom of the trace, white at the top, which is
                // the way every scope on a desk is drawn.
                half level = 1.0 - (coord.y - waveTop) / waveHeight;
                half band = 0.5 / 128.0;

                half3 trace = half3(0.0);
                if (waveLuma > 0.5) {
                    half d = columnDensity(coord, level, band, 3);
                    trace += half3(0.35, 1.0, 0.5) * sqrt(d) * 3.0;
                }
                if (waveRed > 0.5) {
                    half d = columnDensity(coord, level, band, 0);
                    trace += half3(1.0, 0.24, 0.24) * sqrt(d) * 3.0;
                }
                if (waveGreen > 0.5) {
                    half d = columnDensity(coord, level, band, 1);
                    trace += half3(0.24, 1.0, 0.35) * sqrt(d) * 3.0;
                }
                if (waveBlue > 0.5) {
                    half d = columnDensity(coord, level, band, 2);
                    trace += half3(0.31, 0.51, 1.0) * sqrt(d) * 3.0;
                }

                // Laid over a dimmed picture rather than a black box, so the
                // shot is still readable underneath the trace.
                half strength = clamp(max(trace.r, max(trace.g, trace.b)), 0.0, 1.0);
                rgb = mix(rgb * 0.55, clamp(trace, 0.0, 1.0), strength);
            }

            return half4(rgb, c.a);
        }
    """

    /**
     * A cube laid out as an image the shader can sample: the blue slices side
     * by side, so a 33 cube becomes 1089 by 33.
     *
     * How every GPU has carried a 3D LUT since before there were 3D textures
     * to put one in. One fetch per neighbour rather than a loop.
     */
    fun stripFor(table: CubeLut): Bitmap {
        val size = table.size
        val bitmap = Bitmap.createBitmap(size * size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size * size)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val i = ((b * size + g) * size + r) * 3
                    pixels[g * (size * size) + b * size + r] = Color.argb(
                        255,
                        (table.data[i] * 255f).toInt().coerceIn(0, 255),
                        (table.data[i + 1] * 255f).toInt().coerceIn(0, 255),
                        (table.data[i + 2] * 255f).toInt().coerceIn(0, 255)
                    )
                }
            }
        }
        bitmap.setPixels(pixels, 0, size * size, 0, 0, size * size, size)
        return bitmap
    }

    /**
     * @param sensitivity 0..100, how faint an edge still counts
     * @return true when the state asked for was achieved, so a caller can say
     *         plainly that a phone cannot do it rather than lighting a button
     *         that does nothing
     */
    fun apply(
        view: View,
        curve: LogCurves.Curve,
        lut: Boolean,
        peak: Boolean,
        peakColour: PeakColour = PeakColour.RED,
        sensitivity: Int = 50,
        waveform: Set<Mechanism.WaveformChannel> = emptySet(),
        uploaded: CubeLut? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return !lut && !peak
        }
        return try {
            // Nothing to correct on a picture that is already Rec.709.
            val wantLut = lut && curve != LogCurves.Curve.REC709
            val wantWave = waveform.isNotEmpty() && view.height > 0
            if (!wantLut && !peak && !wantWave) {
                view.setRenderEffect(null)
                return true
            }

            val shader = RuntimeShader(SHADER)
            // The uploaded cube for this curve if there is one, otherwise the
            // correction computed from the curve itself, in the same 33 point
            // structure so a vendor LUT replaces it point for point.
            val table = uploaded ?: CubeLut.generate(curve)
            shader.setInputShader(
                "curve",
                BitmapShader(stripFor(table), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("lutSize", table.size.toFloat())
            shader.setFloatUniform("useLut", if (wantLut) 1f else 0f)
            shader.setFloatUniform("usePeak", if (peak) 1f else 0f)
            // Higher sensitivity means a lower bar. A Sobel magnitude on
            // normalised luma runs to about 4 at a hard black to white edge,
            // so the usable range sits well below one.
            shader.setFloatUniform(
                "threshold",
                (0.60f - (sensitivity.coerceIn(0, 100) / 100f) * 0.55f).coerceAtLeast(0.02f)
            )
            shader.setFloatUniform(
                "peakTint",
                Color.red(peakColour.colour) / 255f,
                Color.green(peakColour.colour) / 255f,
                Color.blue(peakColour.colour) / 255f
            )
            // The whole frame. A trace squeezed into a strip throws away the
            // vertical resolution that makes it readable: black and white end
            // up a few pixels apart and every exposure looks the same.
            val height = view.height.toFloat()
            shader.setFloatUniform("useWave", if (wantWave) 1f else 0f)
            shader.setFloatUniform("waveTop", 0f)
            shader.setFloatUniform("waveHeight", height)
            shader.setFloatUniform("viewHeight", height)
            shader.setFloatUniform(
                "waveLuma", if (Mechanism.WaveformChannel.LUMA in waveform) 1f else 0f
            )
            shader.setFloatUniform(
                "waveRed", if (Mechanism.WaveformChannel.RED in waveform) 1f else 0f
            )
            shader.setFloatUniform(
                "waveGreen", if (Mechanism.WaveformChannel.GREEN in waveform) 1f else 0f
            )
            shader.setFloatUniform(
                "waveBlue", if (Mechanism.WaveformChannel.BLUE in waveform) 1f else 0f
            )

            view.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content")
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "preview effects", e)
            runCatching { view.setRenderEffect(null) }
            false
        }
    }

    private const val TAG = "PreviewEffects"
}
