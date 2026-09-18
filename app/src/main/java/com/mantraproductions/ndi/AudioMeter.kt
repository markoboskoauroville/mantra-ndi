package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The microphone, read for level alone, whenever nothing else is reading it.
 *
 * The meter has to be alive before a take, not only during one: it is how an
 * operator finds out the mic is dead, or plugged into the wrong socket, or
 * clipping, at a point where that is still fixable. A meter that only appears
 * once recording starts reports the problem after it has ruined something.
 *
 * Only one thing may hold the microphone at a time, so this stands down the
 * moment the encoder wants it and takes over again when the encoder stops.
 * [NdiSendService] does the handover.
 */
class AudioMeter(private val onLevel: (level: Float, isInput: Boolean) -> Unit) {

    /** Mirrors the encoder's gain so the output meter reads the same idle or live. */
    @Volatile var gain: Float = 1f

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (running.getAndSet(true)) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            return
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: Exception) {
            running.set(false)
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            running.set(false)
            return
        }

        record = audioRecord
        audioRecord.startRecording()

        worker = thread(name = "audio-meter") {
            val buffer = ByteArray(minBuffer)
            while (running.get()) {
                val read = try {
                    audioRecord.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    break
                }
                if (read > 0) {
                    // The same two readings the encoder path produces, so the
                    // meters behave identically whether or not a stream runs.
                    val slice = buffer.copyOf(read)
                    onLevel(Mechanism.rmsOfPcm16(slice), true)
                    Mechanism.applyGainPcm16(slice, gain)
                    onLevel(Mechanism.rmsOfPcm16(slice), false)
                }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        worker?.join(600)
        worker = null
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "meter stop", e)
        }
        record?.release()
        record = null
        onLevel(0f, true)
        onLevel(0f, false)
    }

    private companion object {
        const val TAG = "AudioMeter"
        const val SAMPLE_RATE = 48_000
    }
}
