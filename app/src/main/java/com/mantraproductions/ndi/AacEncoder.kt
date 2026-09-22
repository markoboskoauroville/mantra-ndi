package com.mantraproductions.ndi

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * PCM in, AAC out, for the sound track of a take.
 *
 * It does not own the microphone. [AudioMeter] does, and hands the same
 * buffers here, so what is metered and what is recorded are the same samples
 * and there is no second capture to go out of step with the first.
 *
 * Timestamps come from `System.nanoTime`, which is the clock a camera surface
 * stamps its frames with. Using the encoder's own arrival time instead is what
 * makes sound drift away from picture over a long take.
 */
class AacEncoder(
    private val sampleRate: Int = AudioMeter.SAMPLE_RATE,
    private val bitRate: Int = 128_000
) {
    var onFormat: ((MediaFormat) -> Unit)? = null
    var onSample: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        return try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            running.set(true)
            worker = thread(name = "aac-drain") { drain(c) }
            true
        } catch (t: Throwable) {
            Trace.fault("aac encoder", t)
            running.set(false)
            false
        }
    }

    /** One buffer of 16-bit mono PCM, straight from the meter's reader thread. */
    fun feed(pcm: ByteArray, bytes: Int) {
        if (!running.get() || bytes <= 0) return
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(2_000)
            if (index < 0) return
            val input = c.getInputBuffer(index) ?: return
            input.clear()
            input.put(pcm, 0, bytes.coerceAtMost(input.capacity()))
            c.queueInputBuffer(index, 0, bytes.coerceAtMost(input.capacity()),
                System.nanoTime() / 1000, 0)
        } catch (t: Throwable) {
            // A codec that has been stopped underneath the reader thread throws
            // here once. It is not worth a trace line per buffer.
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        worker?.join(800)
        worker = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                break
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                runCatching { onFormat?.invoke(c.outputFormat) }
                continue
            }
            if (index < 0) continue
            val buffer = c.getOutputBuffer(index)
            if (buffer != null && info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            ) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                runCatching { onSample?.invoke(buffer, info) }
            }
            try {
                c.releaseOutputBuffer(index, false)
            } catch (t: Throwable) {
                break
            }
        }
    }
}
