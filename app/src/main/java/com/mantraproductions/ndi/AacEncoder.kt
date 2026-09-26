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
 * **Timestamps are counted, not measured.** v82 stamped each buffer with the
 * moment it arrived and copied only as much of it as one encoder slot would
 * hold. The microphone hands over 40 ms at a time and a slot holds 21 ms, so
 * half of every buffer was thrown away and the half that was kept was stamped
 * 40 ms after the last: 8.2 s of sound spread over a 15.4 s take, which a
 * player renders as crackle, or plays straight through as a sped-up voice.
 * (Measured with ffprobe on his take of 26.9.2026: 386 AAC frames, every one
 * 40 ms after the one before, each holding 21.3 ms.)
 *
 * Now every byte goes in, split across as many slots as it needs, and every
 * slot is stamped from the number of samples before it: the first sample's
 * time plus samples / 48000. The first sample's time is read on the monotonic
 * clock ([clockNs], System.nanoTime), which is the clock the camera framework
 * puts on frames it sends into a video encoder, so picture and sound agree.
 */
class AacEncoder(
    private val sampleRate: Int = AudioMeter.SAMPLE_RATE,
    private val bitRate: Int = 192_000,
    /** Now, in nanoseconds, on the camera's own clock. */
    private val clockNs: () -> Long = { System.nanoTime() }
) {
    /** Where the next sample falls, counted from the first. */
    private var samples = 0L
    private var firstUs = -1L

    /** Bytes the encoder could not take in time. Said at the end of a take. */
    @Volatile var droppedBytes = 0L
        private set

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
        if (firstUs < 0) {
            // The buffer ends now; its first sample was its own length ago.
            firstUs = clockNs() / 1000 - Mechanism.pcmDurationUs(bytes, sampleRate)
        }
        var offset = 0
        try {
            while (offset < bytes && running.get()) {
                // Waited for, not skipped: a slot is always free within a few
                // milliseconds, and a skipped buffer is a hole in the sound.
                val index = c.dequeueInputBuffer(20_000)
                if (index < 0) {
                    droppedBytes += (bytes - offset)
                    samples += (bytes - offset) / 2
                    return
                }
                val input = c.getInputBuffer(index) ?: return
                input.clear()
                val n = minOf(bytes - offset, input.capacity()) and 1.inv()
                input.put(pcm, offset, n)
                val ptsUs = firstUs + Mechanism.samplesToUs(samples, sampleRate)
                c.queueInputBuffer(index, 0, n, ptsUs, 0)
                samples += n / 2
                offset += n
            }
        } catch (t: Throwable) {
            // A codec that has been stopped underneath the reader thread throws
            // here once. It is not worth a trace line per buffer.
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Trace.state(
            "take's sound: " + String.format("%.2f", samples.toDouble() / sampleRate) + " s" +
                (if (droppedBytes > 0) ", $droppedBytes bytes the encoder could not take" else ", nothing dropped")
        )
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
