package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
 *
 * **v135: which microphone, and the stream.** The reader opens the input [Settings.audioSource] names (AUTO: a USB-C
 * microphone, audio interface or wireless receiver when one is plugged in, else the phone's own; see [Routing.pick]),
 * mono or stereo, and every buffer also goes to [NdiAudio], so the sound travels inside the NDI stream. A device
 * plugged in or pulled out while the app runs is followed live ([AudioRecord.setPreferredDevice] on the running
 * reader, no gap, no restart of a take). [routed] is what Android actually routed to, which is the only honest
 * answer to "is it the Hollyland or the phone?", and it is what the status line and the trace say.
 */
class AudioMeter(
    private val context: Context,
    /** [Routing.AUDIO_AUTO], [Routing.AUDIO_PHONE] or a device's [Routing.Input.key]. */
    private val choice: String = Routing.AUDIO_AUTO,
    stereo: Boolean = false,
    private val onLevel: (rms: Float) -> Unit,
    /** Called on the main thread whenever the input changes: its label, and whether it is the one chosen. */
    private val onRoute: ((label: String, asChosen: Boolean) -> Unit)? = null
) {

    /** Where PCM goes while a take is running, or null. Set from any thread. */
    @Volatile var sink: ((pcm: ByteArray, bytes: Int) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val wantedStereo = stereo

    val isRunning: Boolean get() = running.get()

    /** 1 or 2, fixed for the life of this reader (a take's AAC track is made for it). */
    var channels: Int = 1
        private set

    /** What Android routed the reader to, as settings names it ("USB-C · LARK M2", "PHONE MIC"). */
    @Volatile var routed: String = ""
        private set

    /** The input the choice resolves to now, as an Android device, or null for the phone's own. */
    private fun chosenDevice(): AudioDeviceInfo? {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val pick = Routing.pick(choice, devices.map(::describe)) ?: return null
        return devices.firstOrNull { it.id == pick.id }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = follow("plugged in: " + added.joinToString { describe(it).label })
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = follow("pulled out: " + removed.joinToString { describe(it).label })
    }

    /** A device came or went: point the running reader at whatever the choice resolves to now. */
    private fun follow(why: String) {
        val r = record ?: return
        if (!running.get()) return
        val device = chosenDevice()
        Trace.state("audio: $why → " + (device?.let { describe(it).label } ?: "PHONE MIC"))
        runCatching { r.setPreferredDevice(device) }
        main.postDelayed({ reportRoute() }, 300)
    }

    private fun reportRoute() {
        val r = record ?: return
        val dev = runCatching { r.routedDevice }.getOrNull()
        val label = dev?.let { describe(it).label } ?: "PHONE MIC"
        val asChosen = when (choice) {
            Routing.AUDIO_AUTO -> true
            Routing.AUDIO_PHONE -> dev == null || dev.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            else -> dev != null && describe(dev).key == choice
        }
        if (label != routed) Trace.state("audio routed to $label" + (if (asChosen) "" else " (chosen: ${Routing.choiceLabel(choice)}, not plugged in)") + ", ${channels}ch")
        routed = label
        onRoute?.invoke(label, asChosen)
    }

    @SuppressLint("MissingPermission") // the caller holds RECORD_AUDIO
    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        val device = chosenDevice()
        val input = device?.let(::describe)
        channels = Routing.channelsFor(wantedStereo, input)
        // The camera source on the phone's own microphones (it picks the one facing the lens, with the phone's own
        // wind and handling processing); MIC on a plugged-in device, which a USB interface takes as it comes.
        val source = if (device == null || device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
            MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.MIC

        val audioRecord = open(source, channels) ?: if (channels == 2) {
            Trace.refused("audio meter", "stereo refused on ${input?.label ?: "PHONE MIC"}, opening mono")
            channels = 1
            open(source, 1)
        } else null
        if (audioRecord == null) {
            running.set(false)
            return false
        }

        record = audioRecord
        if (device != null) runCatching { audioRecord.setPreferredDevice(device) }
        audioRecord.startRecording()
        Trace.state("audio meter running at $SAMPLE_RATE Hz, ${channels}ch, choice ${Routing.choiceLabel(choice)}" +
            ", asked for " + (input?.label ?: "PHONE MIC"))
        runCatching { audio.registerAudioDeviceCallback(deviceCallback, main) }
        main.postDelayed({ reportRoute() }, 300)

        val ch = channels
        worker = thread(name = "audio-meter") {
            val buffer = ByteArray(bufferBytes)
            while (running.get()) {
                val read = try {
                    audioRecord.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    break
                }
                if (read > 0) {
                    onLevel(Mechanism.rmsOfPcm16(buffer.copyOf(read)))
                    sink?.invoke(buffer, read)
                    NdiAudio.feed(buffer, read, ch, SAMPLE_RATE)
                }
            }
        }
        return true
    }

    private var bufferBytes = 0

    @SuppressLint("MissingPermission")
    private fun open(source: Int, channelCount: Int): AudioRecord? {
        val mask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Trace.refused("audio meter", "this phone reports no usable buffer size for ${channelCount}ch")
            return null
        }
        val r = try {
            AudioRecord(source, SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        } catch (t: Throwable) {
            Trace.fault("audio meter", t)
            return null
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            Trace.refused("audio meter", "the microphone would not initialise (${channelCount}ch)")
            return null
        }
        bufferBytes = minBuffer
        return r
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        sink = null
        runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
        main.removeCallbacksAndMessages(null)
        worker?.join(600)
        worker = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        onLevel(0f)
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        /** An Android input as [Routing] reads it. */
        fun describe(d: AudioDeviceInfo): Routing.Input =
            Routing.Input(d.id, d.type, d.productName?.toString().orEmpty(), d.channelCounts.toList())

        /** Every input the phone offers now, as settings lists them. */
        fun inputs(context: Context): List<Routing.Input> {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return Routing.choices(audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::describe))
        }
    }
}
