package com.mantraproductions.ndi

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.ImageReader
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
    private var sourceName: String = "Mantra NDI"
    private var size: Size = Size(1920, 1080)
    private var fps: Int = 30

    private var parameterSets: Triple<ByteArray, ByteArray?, ByteArray?>? = null
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
    fun previewSizeFor(cameraId: String): Size = try {
        chooseSize(
            (context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager)
                .getCameraCharacteristics(cameraId)
        )
    } catch (t: Throwable) {
        Size(1920, 1080)
    }

    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty()
        return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
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

        val characteristics = try {
            (context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager)
                .getCameraCharacteristics(cameraId)
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

    fun stop() {
        setMode(Mode.OFF)
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
            Mode.HX -> encoderSurface?.let { engine.setTargetLive(it, false) }
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
