package com.mantraproductions.ndi

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * THE CAMERA'S END OF MANTRA LINK (v131, see Link.kt).
 *
 * The window in front (the camera, or its settings) is copied (PixelCopy, which takes the camera's own picture with
 * it) about 24 times a second into an H.264 encoder at most 1280 px on the long side, and every encoded frame goes
 * down the socket. In CLEAN mode the preview alone is sent ([cleanSource]), so the monitor's full screen is the
 * picture and nothing else. What comes back is played into the same window: a touch as a MotionEvent, exactly as a
 * finger would; a key by name through [onKey] (the rail's own keys), BACK as the back key.
 * One monitor at a time; a new one replaces the old.
 */
object LinkServer {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var top: Activity? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var client: Socket? = null
    @Volatile var clean = false
        private set
    /** When the last touch from the monitor was played (the FULL key asks: was this the monitor's finger?). */
    @Volatile var lastRemoteTouch = 0L
        private set
    var onKey: ((String) -> Unit)? = null
    var cleanSource: ((Int, Int) -> Bitmap?)? = null
    var stateProvider: (() -> Map<String, String>)? = null
    var onModeChanged: (() -> Unit)? = null
    private var started = false

    fun fromMonitorJustNow() = SystemClock.uptimeMillis() - lastRemoteTouch < 800

    /** The camera's FULL key pressed by the monitor's finger: the MONITOR goes clean (or back), not this screen. */
    fun setClean(on: Boolean) { clean = on; ui.post { onModeChanged?.invoke() } }

    fun start(app: Application) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = a }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (top === a) top = null }
        })
        thread(name = "link-accept", isDaemon = true) {
            val server = try { ServerSocket(Link.PORT) } catch (e: Exception) { Trace.fault("link", e); return@thread }
            announce(app)
            Trace.step("link: listening on ${Link.PORT}")
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { continue }
                runCatching { client?.close() }
                s.tcpNoDelay = true
                client = s
                serve(s)
            }
        }
    }

    private fun announce(app: Application) {
        runCatching {
            val nsd = app.getSystemService(Application.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = Settings(app).sourceName.ifBlank { "Mantra Camera" }
                serviceType = Link.SERVICE
                port = Link.PORT
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Trace.step("link: announced as ${i.serviceName}") }
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Trace.refused("link", "announce failed $e") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            })
        }.onFailure { Trace.fault("link announce", it) }
    }

    private fun serve(s: Socket) {
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
        out = o
        Trace.control("link", "monitor connected", s.inetAddress.hostAddress ?: "?")
        val enc = Encoder(o)
        enc.start()
        // what comes back
        thread(name = "link-read", isDaemon = true) {
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            try {
                while (true) {
                    val (t, b) = Link.read(inp) ?: break
                    when (t) {
                        Link.TOUCH -> touch(b)
                        Link.KEY -> String(b).let { k -> ui.post { key(k) } }
                        Link.MODE -> { clean = b.firstOrNull() == 1.toByte(); enc.keyframe(); ui.post { onModeChanged?.invoke() } }
                    }
                }
            } catch (e: Exception) {
                Trace.control("link", "monitor gone", e.javaClass.simpleName)
            } finally {
                enc.stop()
                if (out === o) out = null
                runCatching { s.close() }
                if (clean) { clean = false; ui.post { onModeChanged?.invoke() } }
            }
        }
    }

    // ---------------------------------------------------------------- what comes back
    private var downAt = 0L

    private fun touch(b: ByteArray) {
        val bb = java.nio.ByteBuffer.wrap(b)
        val ab = bb.get().toInt() and 0xFF
        val action = (ab and 0x0F) or ((ab shr 4) shl 8)              // actionMasked, the pointer index above it
        val n = (b.size - 1) / 9
        if (n <= 0) return
        val ids = IntArray(n); val xs = FloatArray(n); val ys = FloatArray(n)
        for (i in 0 until n) { ids[i] = bb.get().toInt(); xs[i] = bb.float; ys[i] = bb.float }
        ui.post {
            val a = top ?: return@post
            val v = a.window?.decorView ?: return@post
            val now = SystemClock.uptimeMillis()
            if (action and 0xFF == MotionEvent.ACTION_DOWN) downAt = now
            val props = Array(n) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(n) { MotionEvent.PointerCoords().apply { x = xs[it] * v.width; y = ys[it] * v.height; pressure = 1f; size = 1f } }
            val ev = MotionEvent.obtain(downAt, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            lastRemoteTouch = now
            a.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private fun key(name: String) {
        val a = top ?: return
        if (name == "BACK") {
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        } else onKey?.invoke(name)
    }

    // ---------------------------------------------------------------- what goes out
    private class Encoder(val o: DataOutputStream) {
        private val ht = HandlerThread("link-capture").apply { start() }
        private val h = Handler(ht.looper)
        @Volatile private var running = true
        private var codec: MediaCodec? = null
        private var surface: android.view.Surface? = null
        private var w = 0; private var hgt = 0
        private var bmp: Bitmap? = null
        private var lastState = 0L
        private val frameMs = 1000L / 24

        fun start() { h.post(tick) }
        fun stop() { running = false; h.post { release(); ht.quitSafely() } }
        fun keyframe() { h.post { runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } } }

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val t0 = SystemClock.uptimeMillis()
                runCatching { frame() }.onFailure { Trace.fault("link frame", it) }
                if (t0 - lastState > 500) { lastState = t0; sendState() }
                h.postDelayed(this, maxOf(5L, frameMs - (SystemClock.uptimeMillis() - t0)))
            }
        }

        private fun sendState() {
            val m = (stateProvider?.invoke() ?: emptyMap()) + mapOf("clean" to if (clean) "1" else "0")
            runCatching { Link.write(o, Link.STATE, Link.state(m)) }
        }

        private fun frame() {
            val a = top ?: return
            val win = a.window ?: return
            val dv = win.decorView
            if (dv.width <= 0 || dv.height <= 0) return
            // the size: the window's shape, the long side at most 1280, both even and a multiple of 16
            val scale = minOf(1f, 1280f / maxOf(dv.width, dv.height))
            val tw = ((dv.width * scale).toInt() / 16) * 16
            val th = ((dv.height * scale).toInt() / 16) * 16
            if (tw != w || th != hgt || codec == null) open(tw, th)
            val src: Bitmap = if (clean) {
                cleanSource?.invoke(tw, th) ?: return
            } else {
                val b = bmp?.takeIf { it.width == tw && it.height == th } ?: Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { bmp = it }
                val done = java.util.concurrent.CountDownLatch(1)
                var ok = false
                PixelCopy.request(win, Rect(0, 0, dv.width, dv.height), b, { r -> ok = r == PixelCopy.SUCCESS; done.countDown() }, ui)
                if (!done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS) || !ok) return
                b
            }
            val s = surface ?: return
            val c = s.lockHardwareCanvas()
            try {
                c.drawColor(android.graphics.Color.BLACK)
                // the clean picture keeps its own shape inside the frame
                val sx = tw.toFloat() / src.width; val sy = th.toFloat() / src.height
                val k = minOf(sx, sy)
                val dw = src.width * k; val dh = src.height * k
                c.drawBitmap(src, null, android.graphics.RectF((tw - dw) / 2, (th - dh) / 2, (tw + dw) / 2, (th + dh) / 2), null)
            } finally { s.unlockCanvasAndPost(c) }
            drain()
        }

        private fun open(tw: Int, th: Int) {
            release()
            w = tw; hgt = th
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, tw, th).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
            codec = c
            Trace.control("link", "encoder", "${tw}x$th H.264 8 Mbit/s")
        }

        private val info = MediaCodec.BufferInfo()
        private var payload = ByteArray(1 shl 20)

        private fun drain() {
            val c = codec ?: return
            while (true) {
                val i = c.dequeueOutputBuffer(info, 0)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    val csd0 = f.getByteBuffer("csd-0"); val csd1 = f.getByteBuffer("csd-1")
                    val bo = java.io.ByteArrayOutputStream(); val d = DataOutputStream(bo)
                    d.writeInt(w); d.writeInt(hgt)
                    for (cs in listOf(csd0, csd1)) {
                        val arr = if (cs == null) ByteArray(0) else ByteArray(cs.remaining()).also { cs.duplicate().get(it) }
                        d.writeInt(arr.size); d.write(arr)
                    }
                    Link.write(o, Link.CONFIG, bo.toByteArray())
                    continue
                }
                if (i < 0) return
                val buf = c.getOutputBuffer(i)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val need = 9 + info.size
                    if (payload.size < need) payload = ByteArray(need * 2)
                    payload[0] = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 1 else 0
                    java.nio.ByteBuffer.wrap(payload, 1, 8).putLong(info.presentationTimeUs)
                    buf.position(info.offset); buf.get(payload, 9, info.size)
                    try { Link.write(o, Link.FRAME, payload, need) } catch (e: Exception) { running = false }
                }
                c.releaseOutputBuffer(i, false)
            }
        }

        private fun release() {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { surface?.release() }
            codec = null; surface = null
        }
    }
}
