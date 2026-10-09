package com.mantraproductions.ndi

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * v135: THE SOUND IN THE STREAM. The microphone's reader ([AudioMeter]) hands every buffer here as well as to the
 * meter and the take; a thread of its own sends it to NDI, so a send that waits on the network never holds up the
 * reader (an AudioRecord that is not read in time loses samples, and a take would lose them too).
 *
 * At most [DEPTH] buffers wait (about a second at the phone's 20–40 ms buffers). Beyond that the oldest goes: late
 * sound is worse than a gap, because everything after it stays late.
 */
object NdiAudio {

    /** The stream is up (set by the pipeline as it enters and leaves a mode). */
    @Volatile var live = false

    /** The sound is wanted in the stream (settings, NDI tab). */
    @Volatile var wanted = true

    /** Buffers sent and buffers dropped since the stream came up, for the trace and the stress test. */
    @Volatile var sent = 0L
        private set
    @Volatile var dropped = 0L
        private set

    private const val DEPTH = 32

    private class Chunk(val pcm: ByteArray, val channels: Int, val rate: Int)

    private val queue = ArrayBlockingQueue<Chunk>(DEPTH)
    private var worker: Thread? = null

    fun reset() {
        queue.clear()
        sent = 0
        dropped = 0
    }

    /** From the microphone's reader thread. Copies, because the reader reuses its buffer at once. */
    fun feed(pcm: ByteArray, bytes: Int, channels: Int, sampleRate: Int) {
        if (!live || !wanted || bytes <= 0 || !NdiSender.available) return
        ensureWorker()
        val chunk = Chunk(pcm.copyOf(bytes), channels, sampleRate)
        while (!queue.offer(chunk)) {
            if (queue.poll() != null) dropped++
        }
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = thread(name = "ndi-audio", isDaemon = true) {
            while (true) {
                val c = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (t: Throwable) {
                    null
                } ?: continue
                if (!live || !wanted) continue
                if (NdiSender.sendAudio(c.pcm, c.pcm.size, c.channels, c.rate)) sent++ else dropped++
            }
        }
    }
}
