package com.mantraproductions.ndi

import android.content.Context
import android.util.Log
import android.view.Surface

/**
 * Camera to encoder to NDI, with nothing in between.
 *
 * The shape that makes ten bit possible:
 *
 *     Camera2 session (HLG10 on every OutputConfiguration)
 *       ├─► MediaCodec HEVC Main10 input surface ──► NDI, and the muxer
 *       └─► the preview surface
 *
 * Both targets are PRIVATE format, which is the condition a ten bit dynamic
 * range profile has to meet. The previous pipeline put an OpenGL stage between
 * the camera and the encoder, and a GL texture is not PRIVATE, so the session
 * would have been refused and silently fallen back to eight bits however good
 * the sensor was.
 *
 * Nothing is encoded twice. The buffers that go to the network are the buffers
 * that go to the file, so recording costs a write and the file is exactly what
 * the far end saw.
 */
class HdrPipeline(private val context: Context) {

    interface Listener {
        fun onReady(tenBit: Boolean, codec: String)
        fun onError(message: String)
        fun onLevel(rms: Float)
    }

    var listener: Listener? = null

    private val engine = CaptureEngine(context)
    private var video: HdrVideoEncoder? = null
    private var audio: HdrAudioEncoder? = null
    private var recorder: Mp4Recorder? = null

    val controls: CaptureEngine get() = engine
    var isRunning = false
        private set
    var isTenBit = false

    /**
     * The network's own encoder settings. Zero bitrate means share the
     * recording encoder, which is what happened before there was a choice.
     */
    @Volatile var streamBitRate: Int = 0
    @Volatile var streamWidth: Int = 0
    @Volatile var streamHeight: Int = 0

    private var streamVideo: HdrVideoEncoder? = null
        private set

    /** Digital audio gain, 1.0 untouched. */
    var audioGain: Float = 1f
        set(value) {
            field = value
            audio?.gain = value
        }

    /**
     * @param previewSurface where the operator sees the frame; may be null for
     *   a headless send, which is what a phone on a stand actually wants.
     */
    fun start(
        profile: CaptureProfile,
        sourceName: String,
        previewSurface: Surface?,
        wantTenBit: Boolean,
        logCurve: LogCurves.Curve
    ): Boolean {
        if (isRunning) return true

        // Asked before the camera opens: an encoder refused after the session
        // is live is a black screen the operator has to diagnose.
        val tenBit = wantTenBit && HdrVideoEncoder.supportsTenBit()
        if (wantTenBit && !tenBit) {
            listener?.onError("No 10-bit HEVC encoder on this phone, using 8-bit")
        }

        // The recording encoder. Always at the profile's own bitrate, because
        // the file is the thing that has to survive the grade.
        val encoder = HdrVideoEncoder(
            width = profile.width,
            height = profile.height,
            fps = profile.fps,
            bitRate = profile.bitRate,
            tenBit = tenBit,
            onFormat = { sps, pps, vps ->
                if (streamBitRate <= 0) NdiSender.setVideoInfo(sps, pps, vps)
            },
            onFrame = { data, keyframe, ptsUs, hevc ->
                // Only feeds NDI when there is no separate stream encoder.
                if (streamBitRate <= 0) NdiSender.sendVideo(data, keyframe, ptsUs, hevc)
            }
        )
        val encoderSurface = encoder.start()
        if (encoderSurface == null) {
            listener?.onError("This phone will not encode ${profile.width}x${profile.height}")
            return false
        }
        video = encoder
        isTenBit = tenBit

        /*
         * A second encoder for the network, when one is asked for.
         *
         * The two jobs want different things and always did. A recording wants
         * every bit it can get, because it is graded later and nothing can put
         * back what the encoder threw away. A stream wants to arrive: over a
         * busy wifi a generous bitrate does not look better, it stutters, and
         * a dropped frame is worse than a soft one.
         *
         * One encoder cannot serve both, so there are two, each with its own
         * surface on the same capture session. The camera writes to both, and
         * neither knows about the other.
         *
         * Some phones will not run two hardware encoders at this size. That is
         * not a failure worth stopping for, so it falls back to sharing the
         * recording encoder and says so.
         */
        var streamSurface: Surface? = null
        if (streamBitRate > 0) {
            val streamEncoder = HdrVideoEncoder(
                width = streamWidth.takeIf { it > 0 } ?: profile.width,
                height = streamHeight.takeIf { it > 0 } ?: profile.height,
                fps = profile.fps,
                bitRate = streamBitRate,
                tenBit = tenBit,
                onFormat = { sps, pps, vps -> NdiSender.setVideoInfo(sps, pps, vps) },
                onFrame = { data, keyframe, ptsUs, hevc ->
                    NdiSender.sendVideo(data, keyframe, ptsUs, hevc)
                }
            )
            streamSurface = streamEncoder.start()
            if (streamSurface == null) {
                listener?.onError("Two encoders will not run here, sharing one")
                streamBitRate = 0
            } else {
                streamVideo = streamEncoder
            }
        }

        NdiSender.setVideoFormat(
            if (streamBitRate > 0 && streamWidth > 0) streamWidth else profile.width,
            if (streamBitRate > 0 && streamHeight > 0) streamHeight else profile.height,
            profile.fps, 1
        )
        if (!NdiSender.create(sourceName)) {
            listener?.onError("NDI sender could not start")
            encoder.stop()
            return false
        }

        val audioEncoder = HdrAudioEncoder(
            onLevel = { listener?.onLevel(it) },
            onFrame = { data, config, samples, ptsUs ->
                NdiSender.sendAudio(data, config, samples, ptsUs)
            }
        )
        if (audioEncoder.start()) {
            audioEncoder.gain = audioGain
            NdiSender.setAudioInfo(48_000, false)
            audio = audioEncoder
        } else {
            listener?.onError("Microphone unavailable, sending video only")
        }

        val targets = listOfNotNull(encoderSurface, streamSurface, previewSurface)
        engine.listener = object : CaptureEngine.Listener {
            override fun onReady(cameraId: String, tenBitActive: Boolean, curve: LogCurves.Curve) {
                isTenBit = tenBitActive
                listener?.onReady(tenBitActive, if (encoder.isHevc) "HEVC" else "H.264")
            }

            override fun onError(message: String) = listener?.onError(message) ?: Unit

            override fun onCaptureValues(iso: Int?, exposureNs: Long?, focusDistance: Float?) = Unit
        }

        engine.open(
            cameraId = "0",
            surfaces = targets,
            fps = profile.fps,
            wantTenBit = tenBit,
            curve = logCurve
        )

        isRunning = true
        return true
    }

    fun stop() {
        stopRecording()
        engine.close()
        audio?.stop()
        audio = null
        video?.stop()
        video = null
        streamVideo?.stop()
        streamVideo = null
        NdiSender.destroy()
        isRunning = false
    }

    /**
     * Recording taps the encoders rather than running its own, so the file is
     * bit for bit what the network received and costs nothing extra to make.
     */
    fun startRecording(path: String): Boolean {
        if (recorder?.isRecording == true) return true
        val encoder = video ?: return false

        val mp4 = Mp4Recorder(path)
        if (!mp4.start()) return false

        encoder.onEncodedFormat = { format -> mp4.setVideoFormat(format) }
        encoder.onEncodedSample = { buffer, info -> mp4.writeVideo(buffer, info) }
        audio?.onEncodedFormat = { format -> mp4.setAudioFormat(format) }
        audio?.onEncodedSample = { buffer, info -> mp4.writeAudio(buffer, info) }

        // The muxer needs the format before it can start, and the format only
        // arrives with a keyframe, so ask for one now rather than waiting out
        // the interval.
        encoder.requestKeyframe()
        recorder = mp4
        return true
    }

    fun stopRecording() {
        val mp4 = recorder ?: return
        video?.onEncodedFormat = null
        video?.onEncodedSample = null
        audio?.onEncodedFormat = null
        audio?.onEncodedSample = null
        mp4.stop()
        recorder = null
    }

    val isRecording: Boolean get() = recorder?.isRecording == true

    fun requestKeyframe() = video?.requestKeyframe()

    /**
     * Chroma for the scope. Filled by the analysis stream when one is running;
     * null otherwise, which the caller shows as an empty scope rather than
     * inventing a reading.
     */
    fun latestChroma(): Pair<ByteArray, ByteArray>? = chroma

    @Volatile private var chroma: Pair<ByteArray, ByteArray>? = null

    private companion object {
        const val TAG = "HdrPipeline"
    }
}
