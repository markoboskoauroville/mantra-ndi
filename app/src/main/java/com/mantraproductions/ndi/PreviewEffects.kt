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
        uniform half useLut;
        uniform half usePeak;
        uniform half threshold;
        uniform half3 peakTint;
        uniform half lutSize;
        uniform half useFalse;
        uniform half useZebra;
        uniform half zebraLevel;


        half luma(half4 c) {
            return dot(c.rgb, half3(0.299, 0.587, 0.114));
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

            // FALSE COLOUR, measured on the picture as recorded (before the
            // monitor LUT): purple crushed, blue near black, green middle
            // grey, pink a stop over (skin), yellow nearly clipped, red
            // clipped, everything else grey so the bands stand out.
            half y = luma(c);
            if (useFalse > 0.5) {
                if (y < 0.025) rgb = half3(0.50, 0.00, 0.80);
                else if (y < 0.10) rgb = half3(0.10, 0.30, 1.00);
                else if (y > 0.38 && y < 0.42) rgb = half3(0.15, 0.85, 0.25);
                else if (y > 0.52 && y < 0.56) rgb = half3(1.00, 0.45, 0.70);
                else if (y > 0.99) rgb = half3(1.00, 0.10, 0.10);
                else if (y > 0.97) rgb = half3(1.00, 0.90, 0.10);
                else rgb = half3(y, y, y);
            }

            // ZEBRA: diagonal stripes over everything at or above the level.
            if (useZebra > 0.5 && y >= zebraLevel) {
                half band = fract((coord.x + coord.y) / 14.0);
                rgb = band < 0.5 ? mix(rgb, half3(1.0), 0.65) : mix(rgb, half3(0.0), 0.65);
            }

            if (usePeak > 0.5 && edge > threshold) {
                // Replaced rather than blended: a half transparent marker over
                // a busy texture is a marker nobody can see.
                rgb = peakTint;
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
        lut: Boolean,
        peak: Boolean,
        peakColour: PeakColour = PeakColour.RED,
        sensitivity: Int = 50,

        uploaded: CubeLut? = null,
        falseColour: Boolean = false,
        zebra: Boolean = false,
        zebraLevel: Int = 95
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return !lut && !peak && !falseColour && !zebra
        }
        return try {
            // Nothing to correct on a picture that is already Rec.709.
            val wantLut = lut && uploaded != null
            if (!wantLut && !peak && !falseColour && !zebra) {
                view.setRenderEffect(null)
                return true
            }

            val shader = RuntimeShader(SHADER)
            // The operator's own cube, and nothing else. There is no generated
            // stand-in here on purpose: a slot showing a computed curve while
            // claiming to show ARRI's file would be lying about the picture,
            // and the whole reason for eleven slots is to compare real ones.
            //
            // PEAKING WITH NO LUT. Every uniform shader in an AGSL program has
            // to be bound, used or not, or the effect is rejected. This used to
            // return "refused" whenever no cube was loaded, which is why
            // peaking said "this phone will not run the preview shader" on a
            // Pixel 7 and a Nothing Phone alike: it was not the phone, it was
            // peaking on its own. With no cube the input is one black pixel
            // that the shader never reads, because useLut is 0.
            val table = if (wantLut) uploaded else null
            val strip = table?.let { stripFor(it) } ?: EMPTY_STRIP
            shader.setInputShader(
                "curve",
                BitmapShader(strip, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("lutSize", (table?.size ?: 2).toFloat())
            shader.setFloatUniform("useLut", if (wantLut) 1f else 0f)
            shader.setFloatUniform("usePeak", if (peak) 1f else 0f)
            shader.setFloatUniform("useFalse", if (falseColour) 1f else 0f)
            shader.setFloatUniform("useZebra", if (zebra) 1f else 0f)
            shader.setFloatUniform("zebraLevel", zebraLevel.coerceIn(50, 100) / 100f)
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

            view.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content")
            )
            true
        } catch (e: Throwable) {
            // Throwable, not Exception. A rejected shader can come back as an
            // Error from the graphics layer rather than an Exception, and
            // catching only the latter lets it reach the top and close the app.
            Log.w(TAG, "preview effects", e)
            runCatching { view.setRenderEffect(null) }
            false
        }
    }

    private const val TAG = "PreviewEffects"

    /** The LUT input when there is no LUT: bound, never read. */
    private val EMPTY_STRIP: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}
