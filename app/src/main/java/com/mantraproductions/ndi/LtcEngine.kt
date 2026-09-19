package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Timecode over audio, in both directions.
 *
 * This is the way round the licensed Bluetooth SDK, and it is the better way
 * regardless. LTC is what a Tentacle actually puts out of its jack, what a
 * slate records on a spare track, and what every edit tool already reads. Once
 * timecode is audio it travels down anything that carries audio: a cable, a
 * phone speaker into another phone's microphone, or the audio channel of an
 * NDI stream that is already being sent.
 *
 * So one phone is named master and generates the signal. Every other device
 * that can hear it is in sync, and nothing had to be paired, licensed or
 * bought.
 *
 * Reading works with a real Tentacle on a cable too, since the signal is the
 * same standard either way.
 */
class LtcEngine {

    enum class Role {
        /** Neither generating nor listening. */
        OFF,

        /** This phone is the clock. It generates LTC continuously. */
        MASTER,

        /** This phone listens and follows whatever it hears. */
        FOLLOW
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    /** Where the timecode is now, free running between decoded frames. */
    val clock = TimecodeClock()

    /** The rate the master generates at, and the rate a follower assumes. */
    @Volatile var rate: Timecode.Rate = Timecode.Rate.FPS_25

    /** Set on the master to start the count somewhere other than zero. */
    @Volatile var startAt: Timecode? = null

    @Volatile var lastError: String? = null
        private set

    /** True once a follower has actually decoded something. */
    val isLocked: Boolean get() = clock.hasSignal

    fun start(role: Role): Boolean {
        stop()
        if (role == Role.OFF) return true
        running.set(true)
        worker = when (role) {
            Role.MASTER -> thread(name = "ltc-master") { generate() }
            Role.FOLLOW -> thread(name = "ltc-follow") { listen() }
            Role.OFF -> null
        }
        return true
    }

    fun stop() {
        running.set(false)
        worker?.join(600)
        worker = null
    }

    /**
     * Generates LTC continuously out of the phone's audio output.
     *
     * Frames are produced ahead of the clock rather than in step with it,
     * because AudioTrack drains at its own pace and a generator that waits for
     * real time to catch up would leave gaps in the signal. The timecode
     * carried by each frame comes from the free running clock, so what is
     * written is always what the clock says rather than a counter that can
     * drift away from it.
     */
    @SuppressLint("MissingPermission")
    private fun generate() {
        val sampleRate = 48_000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, 8192))
                .build()
        } catch (e: Exception) {
            lastError = "Could not open audio output: ${e.message}"
            return
        }

        val begin = startAt ?: Timecode(0, 0, 0, 0, rate)
        clock.jam(begin, SystemClock.elapsedRealtimeNanos())

        var frameNumber = Timecode.toFrames(begin)
        track.play()

        try {
            while (running.get()) {
                val tc = Timecode.fromFrames(frameNumber, rate)
                val pcm = LtcEncoder.encodeFrame(tc, sampleRate)
                // A blocking write is the pacing: it returns when the buffer
                // has room, which is exactly one frame of real time later.
                track.write(pcm, 0, pcm.size)
                frameNumber++
                clock.jam(tc, SystemClock.elapsedRealtimeNanos())
            }
        } catch (e: Exception) {
            lastError = e.message
        } finally {
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "release", e)
            }
        }
    }

    /**
     * Listens on the microphone and decodes whatever LTC it hears.
     *
     * Works with a Tentacle on a cable, with another phone running master a
     * metre away, or with a slate. The decoder is told the instant each frame
     * completed so the clock anchors to that moment rather than to whenever
     * this thread happened to be scheduled.
     */
    @SuppressLint("MissingPermission")
    private fun listen() {
        val sampleRate = 48_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            lastError = "This phone will not record at 48 kHz"
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 8192)
            )
        } catch (e: Exception) {
            lastError = "Could not open the microphone: ${e.message}"
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            lastError = "Microphone unavailable"
            record.release()
            return
        }

        val decoder = LtcDecoder(sampleRate)
        decoder.onFrame = { tc, _ ->
            // Anchored now, because the frame just finished arriving.
            clock.jam(tc, SystemClock.elapsedRealtimeNanos())
        }

        val buffer = ByteArray(4096)
        record.startRecording()
        try {
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) decoder.feed(buffer.copyOf(read))
            }
        } catch (e: Exception) {
            lastError = e.message
        } finally {
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "release", e)
            }
        }
    }

    private companion object {
        const val TAG = "LtcEngine"
    }
}
