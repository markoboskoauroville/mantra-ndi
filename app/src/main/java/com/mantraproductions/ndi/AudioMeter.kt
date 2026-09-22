package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The microphone, read for level, whether or not anything is being recorded.
 *
 * The meter has to be alive before a take, not only during one: it is how an
 * operator finds out the mic is dead, or plugged into the wrong socket, or
 * clipping, at a point where that is still fixable. A meter that only appears
 * once recording starts reports the problem after it has ruined something.
 *
 * Only one thing may hold the microphone at a time, and the old build handed
 * it back and forth between a meter and an encoder — which is a handover that
 * can fail, and when it failed the take had no sound. So there is one reader
 * here and one only: it meters every buffer, and while a take is running it
 * also passes that same buffer to [sink]. The meter and the file therefore
 * cannot disagree, because they are the same samples.
 */
class AudioMeter(private val onLevel: (rms: Float) -> Unit) {

    /** Where PCM goes while a take is running, or null. Set from any thread. */
    @Volatile var sink: ((pcm: ByteArray, bytes: Int) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // the caller holds RECORD_AUDIO
    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            Trace.refused("audio meter", "this phone reports no usable buffer size")
            return false
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (t: Throwable) {
            running.set(false)
            Trace.fault("audio meter", t)
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            running.set(false)
            Trace.refused("audio meter", "the microphone would not initialise")
            return false
        }

        record = audioRecord
        audioRecord.startRecording()
        Trace.state("audio meter running at $SAMPLE_RATE Hz, mono")

        worker = thread(name = "audio-meter") {
            val buffer = ByteArray(minBuffer)
            while (running.get()) {
                val read = try {
                    audioRecord.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    break
                }
                if (read > 0) {
                    onLevel(Mechanism.rmsOfPcm16(buffer.copyOf(read)))
                    sink?.invoke(buffer, read)
                }
            }
        }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        sink = null
        worker?.join(600)
        worker = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        onLevel(0f)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}
