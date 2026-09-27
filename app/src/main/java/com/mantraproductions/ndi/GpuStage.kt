package com.mantraproductions.ndi

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * THE GPU STAGE (v97, Phase 4).
 *
 * *"The take is recorded straight from the sensor to storage by its own HEVC
 * encoder, at its own high bit rate, not copied from the NDI stream."* A camera
 * session offers a handful of processed outputs and the Pixel refuses most
 * combinations of them, so the camera writes ONE output — the texture made
 * here — and the GPU draws every frame to as many places as are switched on:
 * the monitor, the recording encoder, the stream encoder. Each is a window
 * surface on one EGL context, drawn from the same texture with the same
 * timestamp, so a take and a stream can never disagree about a frame.
 *
 * **Raw orientation, always.** The quad is drawn with the texture's vertical
 * flip only, never the camera's own transform: every output then carries the
 * sensor's buffer exactly as an encoder fed by the camera used to, so the
 * preview arithmetic (`Mechanism.previewRotation`) and the rotation written
 * into the MP4 (`Mechanism.recordingRotation`) stay exactly as proven.
 *
 * **Ten bit.** Only where the phone has all three of: `GL_EXT_YUV_target` (to
 * read the camera's 10-bit YUV as numbers rather than through an 8-bit
 * conversion), an RGBA1010102 config, and `EGL_EXT_gl_colorspace_bt2020_hlg`
 * (to tell the encoder its input is HLG). The YUV is turned into BT.2020 HLG
 * R'G'B' with the limited-range matrix Media3 uses, and the encoder turns it
 * back; nothing is re-graded on the way. Without all three, [create] says why
 * and the pipeline keeps the direct path.
 */
class GpuStage private constructor(
    private val width: Int,
    private val height: Int,
    val tenBit: Boolean
) {
    enum class Output { PREVIEW, RECORD, STREAM }

    private val thread = HandlerThread("gpu-stage").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var texture = 0
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var input: SurfaceTexture? = null

    /** Where the camera writes. */
    lateinit var inputSurface: Surface
        private set

    private class Target(val surface: Surface, var egl: EGLSurface, var enabled: Boolean)
    private val targets = HashMap<Output, Target>()

    /** Frames drawn, for the trace and the rate readout. */
    @Volatile var framesDrawn = 0L
        private set

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v — v flipped: GL's texture origin is the bottom left.
            put(floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            ))
            position(0)
        }

    // --- the set-up, on the GPU thread -----------------------------------------

    private fun setUp(): String? {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "no EGL display"

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        if (tenBit && !extensions.contains("EGL_EXT_gl_colorspace_bt2020_hlg")) {
            return "no EGL_EXT_gl_colorspace_bt2020_hlg"
        }

        val bits = if (tenBit) intArrayOf(10, 10, 10, 2) else intArrayOf(8, 8, 8, 0)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, bits[0],
            EGL14.EGL_GREEN_SIZE, bits[1],
            EGL14.EGL_BLUE_SIZE, bits[2],
            EGL14.EGL_ALPHA_SIZE, bits[3],
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return if (tenBit) "no RGBA1010102 recordable config" else "no RGB888 recordable config"
        }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) return "no GLES 3 context"
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return "context not current"

        if (tenBit && !(GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "").contains("GL_EXT_YUV_target")) {
            return "no GL_EXT_YUV_target"
        }

        program = link(VERTEX, if (tenBit) FRAGMENT_TEN_BIT else FRAGMENT_EIGHT_BIT)
            ?: return "the shader would not compile"
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ draw() }, handler)
        input = st
        inputSurface = Surface(st)
        return null
    }

    private fun link(vertex: String, fragment: String): Int? {
        fun compile(type: Int, source: String): Int? {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Trace.refused("gpu stage shader", GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return null
            }
            return shader
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vertex) ?: return null
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fragment) ?: return null
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Trace.refused("gpu stage program", GLES20.glGetProgramInfoLog(p))
            return null
        }
        return p
    }

    // --- the outputs --------------------------------------------------------------

    /**
     * Attaches [surface] as [output] (null takes it away). An encoder's surface
     * is attached once and then switched with [setEnabled]; drawing into an
     * encoder nobody is reading would fill its queue for nothing.
     */
    fun setOutput(output: Output, surface: Surface?, enabled: Boolean = true) = onGpu {
        targets.remove(output)?.let { EGL14.eglDestroySurface(display, it.egl) }
        if (surface == null || !surface.isValid) return@onGpu
        val attribs = if (tenBit) intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
            else intArrayOf(EGL14.EGL_NONE)
        val egl = runCatching { EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0) }.getOrNull()
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            Trace.refused("gpu stage", "no window surface for $output (EGL error 0x" +
                Integer.toHexString(EGL14.eglGetError()) + ")")
            return@onGpu
        }
        targets[output] = Target(surface, egl, enabled)
        Trace.control("gpu output", output.name, if (enabled) "attached, on" else "attached, off")
    }

    fun setEnabled(output: Output, enabled: Boolean) = onGpu {
        targets[output]?.let {
            if (it.enabled != enabled) {
                it.enabled = enabled
                Trace.control("gpu output", output.name, if (enabled) "on" else "off")
            }
        }
    }

    // --- one frame ----------------------------------------------------------------

    private fun draw() {
        val st = input ?: return
        runCatching { EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context); st.updateTexImage() }
            .onFailure { return }
        val timestamp = st.timestamp
        for ((output, target) in targets) {
            if (!target.enabled) continue
            if (!EGL14.eglMakeCurrent(display, target.egl, target.egl, context)) {
                Trace.refused("gpu stage", "$output lost its surface")
                target.enabled = false
                continue
            }
            val size = IntArray(2)
            EGL14.eglQuerySurface(display, target.egl, EGL14.EGL_WIDTH, size, 0)
            EGL14.eglQuerySurface(display, target.egl, EGL14.EGL_HEIGHT, size, 1)
            GLES20.glViewport(0, 0, size[0], size[1])
            drawQuad()
            EGLExt.eglPresentationTimeANDROID(display, target.egl, timestamp)
            EGL14.eglSwapBuffers(display, target.egl)
        }
        framesDrawn++
    }

    private fun drawQuad() {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // --- the end --------------------------------------------------------------------

    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            runCatching {
                input?.setOnFrameAvailableListener(null)
                for (t in targets.values) EGL14.eglDestroySurface(display, t.egl)
                targets.clear()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                input?.release()
                if (::inputSurface.isInitialized) inputSurface.release()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }.onFailure { Trace.fault("gpu stage release", it) }
            done.countDown()
        }
        done.await(1500, TimeUnit.MILLISECONDS)
        thread.quitSafely()
        Trace.state("gpu stage released after $framesDrawn frames")
    }

    private fun onGpu(block: () -> Unit) {
        handler.post { runCatching(block).onFailure { Trace.fault("gpu stage", it) } }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

        /**
         * A stage at [width] x [height], or null with the reason traced. Built
         * on its own thread and waited for, so the caller knows before it opens
         * the camera whether to give it this stage's surface or the old ones.
         */
        fun create(width: Int, height: Int, tenBit: Boolean): GpuStage? {
            val stage = GpuStage(width, height, tenBit)
            var why: String? = "timed out"
            val ready = CountDownLatch(1)
            stage.handler.post {
                why = runCatching { stage.setUp() }.getOrElse { "failed: " + Trace.describe(it) }
                ready.countDown()
            }
            ready.await(2000, TimeUnit.MILLISECONDS)
            if (why != null) {
                Trace.refused("gpu stage", "${if (tenBit) "10-bit" else "8-bit"}: $why — the direct path is used")
                stage.release()
                return null
            }
            Trace.state("gpu stage ready: ${width}x$height, ${if (tenBit) "10-bit HLG (YUV read as numbers, RGBA1010102, BT.2020 HLG surfaces)" else "8-bit"}")
            return stage
        }

        private const val VERTEX = """#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""

        private const val FRAGMENT_EIGHT_BIT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 outColour;
void main() {
    outColour = texture(uTexture, vTexCoord);
}
"""

        /**
         * The camera's 10-bit YUV read as numbers, then BT.2020 limited range
         * to R'G'B' — the same matrix and offsets Media3 uses — so what reaches
         * the HLG surface is the camera's own HLG signal, not a re-grade.
         */
        private const val FRAGMENT_TEN_BIT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTexture;
in vec2 vTexCoord;
out vec4 outColour;
const mat3 YUV_TO_RGB = mat3(
    1.1678, 1.1678, 1.1678,
    0.0, -0.1878, 2.1481,
    1.6836, -0.6523, 0.0);
const vec3 OFFSET = vec3(0.0625, 0.5, 0.5);
void main() {
    vec3 yuv = texture(uTexture, vTexCoord).xyz;
    outColour = vec4(clamp(YUV_TO_RGB * (yuv - OFFSET), 0.0, 1.0), 1.0);
}
"""
    }
}
