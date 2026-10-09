package com.mantraproductions.ndi

import android.os.SystemClock
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * v136: THE PICTURE'S OWN LANE TO NDI.
 *
 * Marko, 9.10.2026: *"It works for some time, then the picture stops and audio is continuing."* Until v135 the
 * encoder's drain loop called the NDI send itself, synchronously. When the network slowed (2.4 GHz at −68 dBm, a
 * receiver that reads late), that send waited; the encoder's output buffers filled; the encoder stopped taking
 * input; the GPU stage's swap into the encoder's surface waited; and the GPU thread, which draws the preview, the take
 * and the stream from one camera frame, stood still. The sound had its own lane (v135) and carried on.
 *
 * Now the drain loop only queues an access unit here and goes back for the next. A thread of its own sends. When
 * more than [DEPTH] frames wait (the wire cannot keep up), everything waiting is dropped, the queue refuses frames
 * until the next keyframe (a P-frame without its reference is a smear), and a keyframe is asked for at once.
 * A receiver sees a short freeze and then the live picture, never a picture that stops for good.
 */
object NdiVideo {

    private const val DEPTH = 12

    private class Au(val data: ByteArray, val key: Boolean, val ptsUs: Long, val hevc: Boolean)

    private val queue = LinkedBlockingDeque<Au>()
    private var worker: Thread? = null

    /** Asked of the encoder when frames had to be dropped. Set by the pipeline. */
    @Volatile var requestKeyframe: (() -> Unit)? = null

    @Volatile private var waitForKey = false

    @Volatile var queued = 0L; private set
    @Volatile var sent = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var flushes = 0L; private set
    /** elapsedRealtime of the last frame handed to NDI, and of the last frame the encoder gave. */
    @Volatile var lastSentAt = 0L; private set
    @Volatile var lastQueuedAt = 0L; private set
    /** The longest a single send took, ms, since the last [takeSlowest]. */
    @Volatile private var slowestMs = 0L

    fun takeSlowest(): Long = slowestMs.also { slowestMs = 0 }

    fun reset() {
        queue.clear()
        waitForKey = true      // a new sender starts on a keyframe
        queued = 0; sent = 0; dropped = 0; flushes = 0
        lastSentAt = SystemClock.elapsedRealtime(); lastQueuedAt = lastSentAt
        requestKeyframe?.invoke()
    }

    /** From the encoder's drain thread. Never blocks. */
    fun offer(data: ByteArray, key: Boolean, ptsUs: Long, hevc: Boolean) {
        lastQueuedAt = SystemClock.elapsedRealtime()
        if (waitForKey && !key) { dropped++; return }
        if (key) waitForKey = false
        ensureWorker()
        if (queue.size >= DEPTH) {
            dropped += queue.size + 1L
            queue.clear()
            flushes++
            waitForKey = true
            Trace.refused("ndi video", "the wire fell $DEPTH frames behind: dropped them, waiting for a keyframe (flush $flushes)")
            requestKeyframe?.invoke()
            return
        }
        queue.offer(Au(data, key, ptsUs, hevc))
        queued++
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = thread(name = "ndi-video", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            while (true) {
                val u = try { queue.poll(500, TimeUnit.MILLISECONDS) } catch (t: Throwable) { null } ?: continue
                val t0 = SystemClock.elapsedRealtime()
                NdiSender.sendCompressed(u.data, u.key, u.ptsUs, u.hevc)
                val now = SystemClock.elapsedRealtime()
                if (now - t0 > slowestMs) slowestMs = now - t0
                lastSentAt = now
                sent++
            }
        }
    }
}
