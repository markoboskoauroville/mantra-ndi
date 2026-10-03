package com.mantraproductions.ndi

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * THE MONITOR'S END OF MANTRA LINK (v131, see Link.kt): the camera's window (or its clean picture) decoded straight
 * onto the screen, and the touches sent back.
 *
 * ZERO FRAMES (Marko, 3.10.2026: "detect when there is zero frame transmission to somehow restart connection"): when
 * nothing has arrived for 2.5 s while connected, the socket is dropped and opened again, and the camera answers a new
 * connection with a keyframe; a refused connection is tried again every second.
 */
class LinkClient(
    private val host: String,
    private val port: Int = Link.PORT,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit,
    private val onState: (Map<String, String>) -> Unit
) {
    @Volatile private var running = true
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var sock: Socket? = null
    @Volatile var lastFrameAt = 0L
        private set
    val frames = java.util.concurrent.atomic.AtomicLong()
    val bytes = java.util.concurrent.atomic.AtomicLong()
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    private var codec: MediaCodec? = null

    fun start() {
        thread(name = "link-client", isDaemon = true) {
            var tries = 0
            while (running) {
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 3000)
                    s.soTimeout = 2500                                // ZERO FRAMES: 2.5 s of nothing ends the read
                    sock = s
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    onStatus(if (tries == 0) "connected" else "connected again")
                    lastFrameAt = SystemClock.elapsedRealtime()
                    read(DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16)))
                } catch (e: java.net.SocketTimeoutException) {
                    onStatus("no picture for 2.5 s: connecting again")
                } catch (e: Exception) {
                    if (running) onStatus("cannot reach $host (${e.javaClass.simpleName}): trying again")
                    if (running) SystemClock.sleep(1000)
                } finally {
                    runCatching { sock?.close() }; sock = null; out = null
                    releaseCodec()
                }
                tries++
            }
        }
    }

    fun stop() { running = false; runCatching { sock?.close() } }

    private fun read(inp: DataInputStream) {
        while (running) {
            val (t, b) = Link.read(inp) ?: return
            when (t) {
                Link.CONFIG -> configure(b)
                Link.FRAME -> feed(b)
                Link.STATE -> onState(Link.parseState(b))
            }
        }
    }

    private fun configure(b: ByteArray) {
        val d = DataInputStream(b.inputStream())
        val w = d.readInt(); val h = d.readInt()
        val c0 = ByteArray(d.readInt()).also { d.readFully(it) }
        val c1 = ByteArray(d.readInt()).also { d.readFully(it) }
        releaseCodec()
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        if (c0.isNotEmpty()) f.setByteBuffer("csd-0", ByteBuffer.wrap(c0))
        if (c1.isNotEmpty()) f.setByteBuffer("csd-1", ByteBuffer.wrap(c1))
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { c.configure(f, surface, null, 0) } catch (e: Exception) {
            f.removeKey(MediaFormat.KEY_LOW_LATENCY); c.configure(f, surface, null, 0)
        }
        c.start()
        codec = c
        width = w; height = h
        onSize(w, h)
    }

    private val info = MediaCodec.BufferInfo()

    private fun feed(b: ByteArray) {
        val c = codec ?: return
        lastFrameAt = SystemClock.elapsedRealtime()
        frames.incrementAndGet(); bytes.addAndGet(b.size.toLong())
        val pts = ByteBuffer.wrap(b, 1, 8).long
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            val buf = c.getInputBuffer(i)!!
            buf.clear(); buf.put(b, 9, b.size - 9)
            c.queueInputBuffer(i, 0, b.size - 9, pts, 0)
        }
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) break
            c.releaseOutputBuffer(o, true)
        }
    }

    private fun releaseCodec() { runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null }

    // ---------------------------------------------------------------- what goes back
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()   // in order, off the UI thread

    private fun send(t: Byte, p: ByteArray) {
        val o = out ?: return
        sender.execute { runCatching { Link.write(o, t, p) } }
    }

    /** A touch, the pointers as fractions (0…1) of the camera's picture; the byte is actionMasked | index << 4. */
    fun touch(masked: Int, index: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        val bb = ByteBuffer.allocate(1 + ids.size * 9)
        bb.put((masked or (index shl 4)).toByte())
        for (i in ids.indices) { bb.put(ids[i].toByte()); bb.putFloat(xs[i]); bb.putFloat(ys[i]) }
        send(Link.TOUCH, bb.array())
    }

    fun key(name: String) = send(Link.KEY, name.toByteArray())
    fun mode(clean: Boolean) = send(Link.MODE, byteArrayOf(if (clean) 1 else 0))
}
