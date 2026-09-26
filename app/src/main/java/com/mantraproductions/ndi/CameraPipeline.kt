package com.mantraproductions.ndi

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.ImageReader
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface

/**
 * Camera to the network, using nothing this phone did not already have.
 *
 *     Camera2, HLG10, one session
 *       ├─► the preview surface                     always live
 *       ├─► MediaCodec HEVC Main10 ──► NDI HX       live while HX is green
 *       ├─► ImageReader RGBA ──────► NDI full       live while FULL is green
 *       └─► ImageReader RAW_SENSOR ──► DngCreator   never repeated into
 *
 * Everything between the sensor and the wire is the phone's own silicon: the
 * ISP demosaics, denoises and white balances; the tone mapper applies the log
 * curve; the hardware encoder makes the H.265. Nothing is reimplemented and
 * nothing is copied through the CPU on the way past.
 *
 * All four targets are configured into one session at the start, and which of
 * them receive frames is decided by the repeating request. Rebuilding a
 * session to add a target means closing the camera and opening it again — a
 * black preview and about a second — every time the operator changes their
 * mind, and this app is built to have its mind changed while it is live.
 *
 * HX and full are mutually exclusive because an NDI source is one stream. A
 * receiver is either given compressed access units or whole frames; there is
 * no sender that offers both and no receiver that would know what to do with
 * it. So the two keys behave like the two ends of one switch.
 */
class CameraPipeline(private val context: Context) {

    interface Listener {
        fun onReady(tenBit: Boolean, codec: String, size: Size)
        fun onError(message: String)
        /** Frames and bits since the last call, for the status line. */
        fun onRate(fps: Double, megabitsPerSecond: Double, connections: Int)
    }

    var listener: Listener? = null

    val engine = CaptureEngine(context)
    val snap = Snap(context)

    enum class Mode { OFF, HX, FULL }

    @Volatile var mode: Mode = Mode.OFF
        private set

    var isRunning = false
        private set
    val isTenBit: Boolean get() = engine.isTenBit

    private var encoder: HdrVideoEncoder? = null
    private var encoderSurface: Surface? = null
    private var fullReader: ImageReader? = null
    private var fullThread: HandlerThread? = null
    private var sourceName: String = "Mantra Manual Camera"

    /**
     * The longest side to ask the camera for. 1920 unless settings say else.
     *
     * Set before the pipeline starts, because the size decides the session and
     * a session cannot be resized once it is built.
     */
    var maxWidth: Int = 1920
    private var size: Size = Size(1920, 1080)

    /**
     * How many frames a second are written and sent.
     *
     * It was 30, in the code, with nothing to say so. A frame rate is not a
     * detail of the implementation — it is the first thing anybody sets on a
     * camera, because it decides what the footage cuts with: 24 for film, 25
     * beside a 50Hz mains, 30 and 60 beside a 60Hz one, 50 and 60 for slow
     * motion. Set before the pipeline starts; a session cannot be re-timed
     * once it is built, any more than it can be resized.
     */
    var fps: Int = 30

    private var parameterSets: Triple<ByteArray, ByteArray?, ByteArray?>? = null

    // --- the take ------------------------------------------------------------
    private var recorder: Mp4Recorder? = null
    private var take: Recordings.Take? = null
    private var audio: AacEncoder? = null
    private var encodedVideoFormat: MediaFormat? = null

    /**
     * Whether a file is being written.
     *
     * Recording is deliberately independent of the two NDI keys. The encoder
     * exists whether or not anything is being sent, so a take costs the same
     * file write with HX green, with FULL green, or with the phone off the
     * network entirely — which is the case that matters, because a camera that
     * can only record while it streams is not a camera.
     */
    @Volatile var isRecording = false
        private set

    /** Where the current take is going, for the status line. */
    var takeName: String? = null
        private set

    private var multicast: WifiManager.MulticastLock? = null
    private var startedAtUs = 0L
    @Volatile private var frames = 0L
    @Volatile private var bits = 0L
    private var rateFrames = 0L
    private var rateBits = 0L
    private var rateAt = 0L

    /**
     * 1080p, or the nearest thing this lens offers.
     *
     * Not 4K, and the reason is the wire rather than the phone. NDI HX at 4K
     * needs about four times the bitrate to look the same, and the far end of
     * a Wi-Fi network in a hall is where that gets spent. 1080p is what a
     * vision mixer wants anyway.
     */
    /**
     * The size the preview buffer must be told about before the session is
     * built. A SurfaceTexture whose default buffer size is still the view's
     * pixel count produces a session configured for the wrong thing, and the
     * picture arrives stretched with nothing to say why.
     */
    /**
     * The 16:9 widths this lens publishes, largest first.
     *
     * The settings screen offers these rather than a hard-coded list, because
     * a resolution a lens does not have is a session it will refuse and a black
     * screen the operator has to diagnose.
     */
    fun widthsOffered(cameraId: String, physicalId: String? = null): List<Int> = try {
        val map = characteristicsOf(cameraId, physicalId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty()
        sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - 16.0 / 9.0) < 0.02 }
            .map { it.width }
            .distinct()
            .sortedDescending()
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * The frame rates this lens will actually hold, for the settings screen.
     *
     * Asked of the camera rather than typed into a list, for the same reason
     * the resolutions are: a rate the sensor cannot sustain at this size is a
     * session that configures and then quietly runs at something else, or
     * refuses outright and leaves a black screen to diagnose. Both the
     * camera's own list of steady ranges and the shortest frame duration it
     * publishes for this size have to agree before a rate is offered.
     */
    fun frameRatesOffered(cameraId: String, physicalId: String? = null, width: Int = 1920):
        List<Int> = try {
        val characteristics = characteristicsOf(cameraId, physicalId)
        val ranges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper }.orEmpty()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val chosen = map?.getOutputSizes(ImageFormat.PRIVATE)
            ?.firstOrNull { it.width == width }
            ?: map?.getOutputSizes(ImageFormat.PRIVATE)?.firstOrNull()
        val shortest = if (map != null && chosen != null) {
            runCatching { map.getOutputMinFrameDuration(ImageFormat.PRIVATE, chosen) }
                .getOrDefault(0L)
        } else 0L
        Mechanism.frameRatesFrom(ranges, shortest).ifEmpty { listOf(30) }
    } catch (t: Throwable) {
        listOf(30)
    }

    fun previewSizeFor(cameraId: String, physicalId: String? = null): Size = try {
        chooseSize(characteristicsOf(cameraId, physicalId))
    } catch (t: Throwable) {
        Size(1920, 1080)
    }

    /**
     * The characteristics of the lens actually being looked through.
     *
     * **This is the stretch.** An ultra wide is a *physical* sub-lens inside
     * the logical back camera, and it publishes its own list of output sizes —
     * often 4:3 only, because that is the shape of its sensor. Asking the
     * logical camera for its sizes and then handing one of them to a physical
     * lens gets a frame the lens never offered, and what arrives is its own
     * picture squeezed into the shape that was demanded. Nothing is refused
     * and nothing is logged; the picture is simply the wrong shape, on one
     * lens and not another, which is exactly how this has looked for six
     * versions.
     */
    private fun characteristicsOf(cameraId: String, physicalId: String?): CameraCharacteristics {
        val manager = context.getSystemService(Context.CAMERA_SERVICE)
            as android.hardware.camera2.CameraManager
        val wanted = physicalId ?: cameraId
        return runCatching { manager.getCameraCharacteristics(wanted) }
            .getOrElse { manager.getCameraCharacteristics(cameraId) }
    }

    /**
     * The best size this lens actually offers, at the shape its sensor is.
     *
     * 1080p is preferred and 16:9 is preferred, but neither is imposed. A lens
     * that only makes 4:3 gets a 4:3 frame, shown at 4:3 and sent at 4:3,
     * because the alternative is a squeezed picture — and a squeezed picture
     * is the fault this app has shipped most often.
     */
    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1920, 1080)

        fun aspect(s: Size) = s.width.toDouble() / s.height
        val widescreen = sizes.filter { kotlin.math.abs(aspect(it) - 16.0 / 9.0) < 0.02 }

        // The longest side he asked for, or the nearest this lens will give.
        val wanted = maxWidth
        val chosen =
            widescreen.firstOrNull { it.width == wanted }
                ?: widescreen.filter { it.width <= wanted }.maxByOrNull { it.width }
                // Nothing at or below what was asked for: the smallest 16:9
                // above it beats refusing to open the camera.
                ?: widescreen.minByOrNull { it.width }
                // No 16:9 at all on this lens: take its own shape rather than
                // demanding one it does not have.
                ?: sizes.filter { it.width <= wanted }
                    .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }
                ?: Size(1920, 1080)

        Trace.state(
            "lens offers ${sizes.size} sizes, ${widescreen.size} of them 16:9 " +
                "(" + widescreen.map { it.width }.distinct().sortedDescending()
                    .joinToString(",") + "); asked for $wanted, " +
                "chose ${chosen.width}x${chosen.height} " +
                "(${String.format("%.3f", aspect(chosen))})"
        )
        return chosen
    }

    /**
     * Opens the camera with every target it will ever need.
     *
     * @param previewSurface where the operator sees the picture
     * @param cameraId the lens the operator chose, never "whichever came first"
     */
    fun start(
        cameraId: String,
        physicalId: String?,
        previewSurface: Surface,
        sourceName: String,
        curve: LogCurves.Curve,
        wantTenBit: Boolean = true,
        bitRate: Int = BIT_RATE
    ): Boolean {
        if (isRunning) stop()
        this.sourceName = sourceName
        videoBitRate = bitRate

        val characteristics = try {
            characteristicsOf(cameraId, physicalId)
        } catch (t: Throwable) {
            listener?.onError("Could not read lens $cameraId")
            return false
        }

        size = chooseSize(characteristics)

        // Asked before the camera opens: an encoder refused after the session
        // is live is a black screen the operator has to diagnose.
        val tenBit = wantTenBit && HdrVideoEncoder.supportsTenBit()
        if (wantTenBit && !tenBit) {
            listener?.onError("No 10-bit HEVC encoder here, falling back to 8-bit")
        }

        val codec = HdrVideoEncoder(
            width = size.width,
            height = size.height,
            fps = fps,
            bitRate = bitRate,
            tenBit = tenBit,
            onFormat = { sps, pps, vps ->
                // Kept, because the encoder announces these exactly once and
                // going to full NDI and back clears them. Without this, the
                // second spell of HX sends frames no receiver can decode, and
                // a black picture from a source that is plainly sending is as
                // hard to read as a fault gets.
                parameterSets = Triple(sps, pps, vps)
                NdiSender.setVideoInfo(sps, pps, vps)
            },
            onFrame = { data, keyframe, ptsUs, hevc ->
                if (mode == Mode.HX) {
                    NdiSender.sendCompressed(data, keyframe, ptsUs, hevc)
                    frames++
                    bits += data.size.toLong() * 8
                }
            }
        )
        // The muxer needs the encoder's own format, and the encoder announces
        // it exactly once, long before anybody presses record. So it is kept.
        codec.onEncodedFormat = { format ->
            encodedVideoFormat = format
            recorder?.setVideoFormat(format)
        }
        codec.onEncodedSample = { buffer, info -> recorder?.writeVideo(buffer, info) }

        val surface = codec.start()
        if (surface == null) {
            listener?.onError("This phone will not encode ${size.width}x${size.height}")
            return false
        }
        encoder = codec
        encoderSurface = surface

        // Full NDI's reader. YUV_420_888, because a camera cannot write into
        // an RGBA reader at all and a session carrying one is refused outright
        // — which is what took the preview and the encoder down on the real
        // phone. Three buffers: one being filled, one being sent, one spare.
        val t = HandlerThread("ndi-full").also { it.start() }
        fullThread = t
        val reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 3
        )
        reader.setOnImageAvailableListener({ r -> onFullFrame(r) }, Handler(t.looper))
        fullReader = reader

        val rawSurface = snap.open(characteristics)

        startedAtUs = SystemClock.elapsedRealtimeNanos() / 1000
        rateAt = SystemClock.elapsedRealtime()

        engine.listener = object : CaptureEngine.Listener {
            override fun onReady(cameraId: String, tenBit: Boolean, logCurve: LogCurves.Curve) {
                listener?.onReady(tenBit, if (codec.isHevc) "HEVC" else "H.264", size)
            }
            override fun onError(message: String) { listener?.onError(message) }
            override fun onCaptureValues(iso: Int?, exposureNs: Long?, focusDistance: Float?) = Unit
        }

        engine.open(
            cameraId = cameraId,
            physicalId = physicalId,
            // The encoder and the full reader are configured but not live: the
            // preview is the only thing drawing until a key is pressed. They
            // are deferred, not standard — they must carry the same ten bit
            // profile as the preview or the stream is eight bit whatever the
            // screen says.
            repeating = listOf(previewSurface),
            deferred = listOf(surface, reader.surface),
            standard = listOfNotNull(rawSurface),
            fps = fps,
            wantTenBit = tenBit,
            curve = curve
        )

        isRunning = true
        Trace.step(
            "pipeline started ${size.width}x${size.height} @$fps on camera $cameraId" +
                (physicalId?.let { ", physical lens $it" } ?: "")
        )
        return true
    }

    /**
     * @param keepSource true while only the camera is being rebuilt — a lens
     *   change. The NDI source stays open and advertised, so a receiver sees a
     *   freeze rather than a source that disappeared and came back, which every
     *   mixer treats as a lost input.
     */
    fun stop(keepSource: Boolean = false) {
        if (isRecording) stopRecording()
        encodedVideoFormat = null
        if (keepSource) {
            // Leave the targets, keep the sender. The encoder is stopped below
            // and its parameter sets are re-sent when the mode is restored.
            val surface = encoderSurface
            if (surface != null) engine.setTargetLive(surface, false)
            fullReader?.surface?.let { engine.setTargetLive(it, false) }
            mode = Mode.OFF
            Trace.state("lens change: NDI source kept open")
        } else {
            setMode(Mode.OFF)
        }
        engine.close()
        encoder?.stop()
        encoder = null
        encoderSurface = null
        fullReader?.setOnImageAvailableListener(null, null)
        runCatching { fullReader?.close() }
        fullReader = null
        fullThread?.quitSafely()
        fullThread = null
        snap.close()
        isRunning = false
        Trace.step("pipeline stopped")
    }

    /**
     * The one switch behind the HX and FULL keys.
     *
     * The NDI source itself is opened on the way into a mode and closed on the
     * way out, so the phone appears in a receiver's source list exactly while
     * it is sending something. A source that is advertised while sending
     * nothing reads as a broken receiver at the far end, which cost a whole
     * evening on the screen share.
     */
    fun setMode(next: Mode): Boolean {
        if (next == mode) return true
        val encoderSurface = this.encoderSurface
        val fullSurface = fullReader?.surface

        // Leave the old one first, so the two are never both live.
        when (mode) {
            // Left live if a take is running: the file is fed by the encoder,
            // and stopping the stream must never stop the recording.
            Mode.HX -> if (!isRecording) encoderSurface?.let { engine.setTargetLive(it, false) }
            Mode.FULL -> fullSurface?.let { engine.setTargetLive(it, false) }
            Mode.OFF -> Unit
        }

        if (next == Mode.OFF) {
            NdiSender.clearVideoInfo()
            NdiSender.destroy()
            runCatching { multicast?.release() }
            multicast = null
            mode = Mode.OFF
            Trace.control("stream", "off", "off")
            return true
        }

        // Android drops multicast packets at the driver unless something holds
        // this, and NDI finds its sources over mDNS. Without it the phone
        // streams perfectly into a source list nobody can see.
        if (multicast == null) {
            multicast = runCatching {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifi.createMulticastLock("mantra-ndi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { Trace.fault("multicast lock", it) }.getOrNull()
            Trace.state("multicast lock " + if (multicast?.isHeld == true) "held" else "NOT held")
        }

        if (!NdiSender.available) {
            listener?.onError("Built without the NDI SDK, nothing can be sent")
            mode = Mode.OFF
            return false
        }

        // The header describes what is about to be sent, so it is set before
        // the source opens rather than after the first frame.
        NdiSender.setVideoFormat(size.width, size.height, fps, 1)
        if (mode == Mode.OFF && !NdiSender.create(sourceName)) {
            listener?.onError("NDI source could not be opened")
            return false
        }

        frames = 0; bits = 0; rateFrames = 0; rateBits = 0
        rateAt = SystemClock.elapsedRealtime()
        startedAtUs = SystemClock.elapsedRealtimeNanos() / 1000

        val target = if (next == Mode.HX) encoderSurface else fullSurface
        if (!engine.isConfigured(target)) {
            listener?.onError("This phone would not give a session with that target")
            NdiSender.destroy()
            mode = Mode.OFF
            return false
        }

        val ok = when (next) {
            Mode.HX -> {
                parameterSets?.let { (sps, pps, vps) -> NdiSender.setVideoInfo(sps, pps, vps) }
                encoderSurface?.let { engine.setTargetLive(it, true) } ?: false
            }
            Mode.FULL -> {
                NdiSender.clearVideoInfo()
                fullSurface?.let { engine.setTargetLive(it, true) } ?: false
            }
            Mode.OFF -> true
        }

        if (!ok) {
            listener?.onError("The camera refused that target")
            NdiSender.destroy()
            mode = Mode.OFF
            return false
        }
        mode = next
        if (next == Mode.HX) encoder?.requestKeyframe()
        Trace.control("stream", next.name, "green")
        return true
    }

    private fun onFullFrame(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            Trace.fault("acquireLatestImage", t)
            null
        } ?: return
        try {
            if (mode == Mode.FULL) {
                val y = image.planes[0]
                val u = image.planes[1]
                val v = image.planes[2]
                val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000 - startedAtUs
                NdiSender.sendYuv420(
                    y.buffer, y.rowStride,
                    u.buffer, u.rowStride,
                    v.buffer, v.rowStride,
                    u.pixelStride,
                    image.width, image.height, ptsUs
                )
                frames++
                // I420 on the wire: one byte of luma and half a byte of chroma
                // per pixel, whatever padding the reader used on the way in.
                bits += image.width.toLong() * image.height * 12
            }
        } catch (t: Throwable) {
            Trace.fault("full frame", t)
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * The rate, measured rather than assumed.
     *
     * This is the only instrument that reaches a phone with no cable attached,
     * and the answer to "why is it soft" is a number rather than a guess.
     */
    fun sampleRate() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - rateAt).coerceAtLeast(1L)
        val f = frames - rateFrames
        val b = bits - rateBits
        rateFrames = frames; rateBits = bits; rateAt = now
        listener?.onRate(
            f * 1000.0 / elapsed,
            b * 1000.0 / elapsed / 1_000_000.0,
            NdiSender.connections(0)
        )
    }

    fun setLogCurve(curve: LogCurves.Curve): Boolean = engine.setLogCurve(curve)

    /**
     * Whether each mode's target actually made it into the session.
     *
     * The camera may have refused the combination that carried it, and a key
     * that lights for a target the session does not have is a key that does
     * nothing. These answer the rail rather than the rail assuming.
     */
    val hxAvailable: Boolean get() = isRunning && engine.isConfigured(encoderSurface)
    val fullAvailable: Boolean get() = isRunning && engine.isConfigured(fullReader?.surface)
    val snapAvailable: Boolean get() = isRunning && snap.armed && engine.isConfigured(snap.surface)

    // --- recording ------------------------------------------------------------

    /** The last take started, for PLAY. */
    var lastTakeUri: android.net.Uri? = null
        private set

    /** The bit rate the take is written at, for the time left on the drive. */
    var videoBitRate: Int = BIT_RATE
        private set


    /**
     * Starts a take, and makes the encoder live if the stream was not already.
     *
     * @param meter the one reader on the microphone; its samples are forwarded
     *   to the sound encoder for as long as the take runs, so what is metered
     *   and what is in the file cannot disagree.
     * @return where the file is going, or null with the reason traced and said.
     */
    fun startRecording(context: Context, meter: AudioMeter?, folder: String? = null): String? {
        if (isRecording) return takeName
        if (!isRunning) {
            listener?.onError("The camera is not open")
            return null
        }
        val surface = encoderSurface
        if (surface == null || !engine.isConfigured(surface)) {
            listener?.onError("This phone's session has no encoder to record from")
            Trace.refused("recording", "the encoder target is not in the session")
            return null
        }

        val opened = Recordings.open(context, folder = folder) ?: run {
            listener?.onError("The file could not be created")
            return null
        }
        val file = Mp4Recorder(opened.fileDescriptor, fps)
        if (!file.opened) {
            opened.close(keep = false)
            listener?.onError("The muxer refused the file")
            return null
        }

        take = opened
        lastTakeUri = opened.uri
        recorder = file
        isRecording = true
        takeName = opened.where

        // If the encoder has already announced its format — and it has, unless
        // this is the first second after opening — the muxer can start now.
        encodedVideoFormat?.let { file.setVideoFormat(it) }

        if (meter != null) {
            // THE MONOTONIC CLOCK, whatever the sensor says. v83 stamped the
            // sound on the boot clock because the Pixel's sensor reports
            // REALTIME, and put it 7664 s (the phone's sleep) after the picture
            // in his take: frames going into a video encoder are converted to
            // the monotonic clock by the camera framework, whatever the
            // sensor's own source. System.nanoTime is that clock.
            val aac = AacEncoder(clockNs = { System.nanoTime() })
            aac.onFormat = { format -> recorder?.setAudioFormat(format) }
            aac.onSample = { buffer, info -> recorder?.writeAudio(buffer, info) }
            if (aac.start()) {
                audio = aac
                meter.sink = { pcm, bytes -> aac.feed(pcm, bytes) }
            } else {
                Trace.refused("recording", "no AAC encoder, the take will be silent")
            }
        } else {
            Trace.refused("recording", "no microphone, the take will be silent")
        }

        // The stream may be off, in which case the encoder is configured but
        // receiving nothing. A take needs it fed either way.
        if (mode != Mode.HX) engine.setTargetLive(surface, true)
        encoder?.requestKeyframe()

        Trace.control("record", "start", opened.where)
        return opened.where
    }

    /** @return where the file went, or null if nothing was written. */
    fun stopRecording(): String? {
        if (!isRecording) return null
        isRecording = false

        audio?.let { aac ->
            aac.onFormat = null
            aac.onSample = null
            aac.stop()
        }
        audio = null

        val file = recorder
        recorder = null
        val frames = file?.frames ?: 0
        val dropped = file?.dropped ?: 0
        file?.stop()

        // The encoder goes back to being fed only while HX is green.
        if (mode != Mode.HX) encoderSurface?.let { engine.setTargetLive(it, false) }

        val opened = take
        take = null
        val where = if (frames > 0) opened?.where else null
        opened?.close(keep = frames > 0)

        Trace.control(
            "record", "stop",
            if (frames > 0) "$frames frames, $dropped refused, ${opened?.name}"
            else "nothing was written, the file was removed"
        )
        takeName = null
        return where
    }

    /** Frames in the file so far, and frames the muxer would not take. */
    val recordedFrames: Long get() = recorder?.frames ?: 0
    val recordedDrops: Long get() = recorder?.dropped ?: 0
    val recordingIsWriting: Boolean get() = recorder?.isWriting == true

    private companion object {
        /**
         * 12 Mbit/s for 1080p HEVC, when nothing in settings says otherwise.
         *
         * Chosen against the wire rather than the picture: this is what a phone
         * reliably pushes across a hall's Wi-Fi without the receiver starting to
         * stutter, and HEVC at 1080p30 has nothing much left to gain above it.
         */
        const val BIT_RATE = 12_000_000
    }
}
