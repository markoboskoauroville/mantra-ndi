package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Microphone to AAC, metered and gain-staged on the way through. */
class HdrAudioEncoder(
    private val sampleRate: Int = 48_000,
    private val onLevel: (Float) -> Unit,
    private val onFrame: (data: ByteArray, config: ByteArray, samples: Int, ptsUs: Long) -> Unit
) {
    private var codec: MediaCodec? = null
    private var record: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var config = ByteArray(0)

    /** Digital gain, applied before metering so the meter shows what is sent. */
    @Volatile var gain: Float = 1f

    var onEncodedFormat: ((MediaFormat) -> Unit)? = null
    var onEncodedSample: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        return try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            codec = c
            record = r
            running.set(true)
            r.startRecording()

            thread(name = "hdr-audio-feed") { feed(r, c, minBuffer) }
            thread(name = "hdr-audio-drain") { drain(c) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "audio start", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { record?.stop() } catch (e: Exception) { Log.w(TAG, "record stop", e) }
        record?.release()
        record = null
        try { codec?.stop() } catch (e: Exception) { Log.w(TAG, "codec stop", e) }
        codec?.release()
        codec = null
        onLevel(0f)
    }

    private fun feed(r: AudioRecord, c: MediaCodec, bufferSize: Int) {
        val pcm = ByteArray(bufferSize)
        while (running.get()) {
            val read = try { r.read(pcm, 0, pcm.size) } catch (e: Exception) { break }
            if (read <= 0) continue

            val slice = pcm.copyOf(read)
            Mechanism.applyGainPcm16(slice, gain)
            onLevel(Mechanism.rmsOfPcm16(slice))

            val index = try { c.dequeueInputBuffer(10_000) } catch (e: IllegalStateException) { break }
            if (index < 0) continue
            val input = c.getInputBuffer(index) ?: continue
            input.clear()
            input.put(slice)
            c.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0)
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onEncodedFormat?.invoke(c.outputFormat)
                c.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                    val dup = csd.duplicate()
                    dup.rewind()
                    config = ByteArray(dup.remaining()).also { dup.get(it) }
                }
                continue
            }
            if (index < 0) continue

            val buffer = c.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    onEncodedSample?.invoke(buffer.duplicate(), info)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    // AAC-LC is always 1024 samples per frame.
                    onFrame(bytes, config, 1024, info.presentationTimeUs)
                }
            }
            try { c.releaseOutputBuffer(index, false) } catch (e: IllegalStateException) { break }
        }
    }

    private companion object {
        const val TAG = "HdrAudioEncoder"
    }
}
