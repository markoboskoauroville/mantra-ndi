package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

/**
 * The capture layer, owned rather than borrowed.
 *
 * Three things are only reachable from the session and the request, which is
 * why this exists instead of a wrapper around somebody else's camera:
 *
 *  - **Ten bit.** A dynamic range profile is set on the OutputConfiguration
 *    before the session is created. There is no way to add it afterwards, and
 *    no library that builds its own session can be asked to.
 *  - **Log.** TONEMAP_MODE_CONTRAST_CURVE with a supplied TONEMAP_CURVE applies
 *    the curve inside the phone's own image pipeline. No GPU pass, no shader,
 *    no extra copy of every frame — and because it happens before the encoder,
 *    it is on the picture that goes down the wire, which a shader on the
 *    preview could never be.
 *  - **Which targets are live.** Full NDI costs a whole uncompressed frame per
 *    frame, so its reader is configured into the session and only added to the
 *    repeating request while the operator has asked for it.
 *
 * Everything the operator touches lands on one builder and goes out as one
 * repeating request, so a change costs a single call no matter how many
 * parameters it moved.
 */
class CaptureEngine(private val context: Context) {

    interface Listener {
        fun onReady(cameraId: String, tenBit: Boolean, logCurve: LogCurves.Curve)
        fun onError(message: String)
        /** Live sensor readback, so the UI can show what the camera settled on. */
        fun onCaptureValues(iso: Int?, exposureNs: Long?, focusDistance: Float?)
    }

    var listener: Listener? = null

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var characteristics: CameraCharacteristics? = null
    var capabilities: HdrCapabilities? = null
        private set

    var cameraId: String = "0"
        private set
    private var targetFps: Int = 30
    private var activeCurve: LogCurves.Curve = LogCurves.Curve.REC709
    private var tenBitActive = false

    /** Every surface the session was built with, live or not. */
    private var allSurfaces: List<Surface> = emptyList()
    /** The ones currently on the repeating request. */
    private var liveSurfaces: MutableList<Surface> = mutableListOf()
    /** Configured at standard range, never repeated into: the RAW reader. */
    private var standardSurfaces: List<Surface> = emptyList()

    @Volatile var lastIso: Int? = null
        private set
    @Volatile var lastExposureNs: Long? = null
        private set
    @Volatile var lastFocusDistance: Float? = null
        private set

    /** The last result the sensor produced, which a DNG needs to describe itself. */
    @Volatile var lastResult: TotalCaptureResult? = null
        private set

    // --- lifecycle -----------------------------------------------------------

    /**
     * @param repeating the surfaces that get every frame right now
     * @param deferred surfaces configured exactly like the repeating ones —
     *   same dynamic range profile — but not yet receiving frames. The encoder
     *   and the full-NDI reader live here until their key is pressed. They are
     *   NOT standard range: putting the encoder in with the RAW reader would
     *   configure it at eight bits and cap the whole stream, with a ten bit
     *   session reported on screen and nothing to say otherwise.
     * @param standard surfaces that cannot carry a ten bit profile at all. The
     *   RAW reader is the only one: Bayer data has no transfer function to
     *   describe.
     */
    @SuppressLint("MissingPermission")
    fun open(
        cameraId: String,
        repeating: List<Surface>,
        deferred: List<Surface>,
        standard: List<Surface>,
        fps: Int,
        wantTenBit: Boolean,
        curve: LogCurves.Curve
    ) {
        close()
        this.cameraId = cameraId
        this.targetFps = fps
        this.activeCurve = curve
        this.liveSurfaces = repeating.toMutableList()
        this.standardSurfaces = standard
        this.allSurfaces = repeating + deferred + standard

        val thread = HandlerThread("capture-engine").also { it.start() }
        this.thread = thread
        this.handler = Handler(thread.looper)

        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            capabilities = HdrCapabilities(characteristics!!)
        } catch (e: Exception) {
            listener?.onError("Could not read camera $cameraId: ${e.message}")
            return
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    Trace.step("camera $cameraId opened")
                    createSession(camera, wantTenBit)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    Trace.state("camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    listener?.onError("Camera error $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            listener?.onError("Camera permission not granted")
        } catch (e: Exception) {
            listener?.onError("Could not open camera $cameraId: ${e.message}")
        }
    }

    /**
     * The ten bit session, with one fallback.
     *
     * The RAW reader cannot carry a ten bit profile — Bayer data has no
     * transfer function to describe — so it is configured at standard range
     * beside the ten bit targets. Some phones refuse that mixture outright,
     * and the camera then configures nothing at all rather than saying which
     * target it disliked, so the retry drops the profile rather than the
     * surface: eight bit with a snap is worth more than ten bit without one,
     * and the trace says which was got.
     */
    private fun createSession(camera: CameraDevice, wantTenBit: Boolean) {
        val profile = if (wantTenBit) capabilities?.bestTenBitProfile() else null

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                Trace.state("session configured, ${if (tenBitActive) "10-bit" else "8-bit"}")
                startRepeating(camera)
                listener?.onReady(cameraId, tenBitActive, activeCurve)
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                if (tenBitActive) {
                    Trace.refused("10-bit session", "configure failed, retrying in 8-bit")
                    tenBitActive = false
                    createSession(camera, wantTenBit = false)
                } else {
                    listener?.onError("Camera session could not be configured")
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && profile != null) {
                tenBitActive = true
                val mixable = capabilities?.canMixWithStandard(profile) ?: false
                if (standardSurfaces.isNotEmpty() && !mixable) {
                    Trace.state("this camera will not mix RAW with a 10-bit profile")
                }
                val configs = allSurfaces.map { surface ->
                    OutputConfiguration(surface).apply {
                        // Every streaming target carries the profile, live or
                        // not. Only RAW is left at standard range.
                        if (surface !in standardSurfaces) dynamicRangeProfile = profile
                    }
                }
                Trace.state(
                    "session: ${allSurfaces.size - standardSurfaces.size} target(s) at 10-bit, " +
                        "${standardSurfaces.size} at standard range"
                )
                val executor = Executor { command -> handler?.post(command) ?: command.run() }
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, configs, executor, callback
                    )
                )
            } else {
                tenBitActive = false
                @Suppress("DEPRECATION")
                camera.createCaptureSession(allSurfaces, callback, handler)
            }
        } catch (e: Exception) {
            listener?.onError("Session setup failed: ${e.message}")
        }
    }

    /**
     * Exposure is the camera's own, and deliberately so.
     *
     * The phone's auto exposure has the whole sensor's metering behind it and
     * reacts faster than a hand on a fader. What it does not have is a ceiling
     * on how long it may hold the shutter open, and left alone it will drop to
     * fifteen frames a second in a dim room to keep the picture bright — which
     * on a live stream is worse than a dark picture. Pinning the range top and
     * bottom to the same number takes that choice away and leaves it the rest.
     */
    private fun startRepeating(camera: CameraDevice) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            liveSurfaces.forEach { request.addTarget(it) }

            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            request.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                Mechanism.frameDurationForFps(targetFps)
            )
            request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            builder = request
            applyToneCurve(request)
            apply()
        } catch (e: Exception) {
            listener?.onError("Could not start the preview: ${e.message}")
        }
    }

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        builder = null
        thread?.quitSafely()
        thread = null
        handler = null
        torchOn = false
    }

    val isOpen: Boolean get() = session != null

    // --- the one place a request is issued -----------------------------------

    private var lastGood: CaptureRequest? = null

    /**
     * Sends the request, and puts the last working one back if it is refused.
     *
     * A refused repeating request leaves the camera with nothing to repeat, so
     * the preview freezes and stays frozen. Restoring the previous one means a
     * value the sensor will not take costs the operator that one change rather
     * than the rest of the take.
     */
    private fun apply(): Boolean {
        val session = session ?: return false
        val request = builder ?: return false
        return try {
            val built = request.build()
            session.setRepeatingRequest(built, captureCallback, handler)
            lastGood = built
            true
        } catch (e: Throwable) {
            Trace.refused("repeating request", Trace.describe(e))
            runCatching {
                lastGood?.let { session.setRepeatingRequest(it, captureCallback, handler) }
            }
            false
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            r: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            lastExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            lastFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            lastResult = result
            listener?.onCaptureValues(lastIso, lastExposureNs, lastFocusDistance)

            pendingFocus?.let { waiting ->
                when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                        pendingFocus = null
                        waiting(true)
                    }
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        pendingFocus = null
                        waiting(false)
                    }
                    else -> Unit
                }
            }
        }
    }

    // --- which targets are live ----------------------------------------------

    /**
     * Turns a configured target on or off without rebuilding the session.
     *
     * Rebuilding would mean closing the camera and opening it again, which is
     * a black preview and about a second, every time the operator changes
     * their mind about full NDI. A target the repeating request does not name
     * simply receives nothing.
     */
    fun setTargetLive(surface: Surface, live: Boolean): Boolean {
        val request = builder ?: return false
        if (surface !in allSurfaces) return false
        if (live == (surface in liveSurfaces)) return true
        return try {
            if (live) {
                request.addTarget(surface)
                liveSurfaces.add(surface)
            } else {
                request.removeTarget(surface)
                liveSurfaces.remove(surface)
            }
            val ok = apply()
            Trace.control("target", if (live) "live" else "off", if (ok) "applied" else "refused")
            ok
        } catch (e: Throwable) {
            Trace.fault("target toggle", e)
            false
        }
    }

    // --- log, applied in the camera rather than on the GPU -------------------

    /**
     * Samples the log curve to as many points as this camera accepts and hands
     * it over as the tone curve. Any curve is legal, so this costs nothing per
     * frame: the camera was already tone mapping, it just uses ours instead.
     */
    fun setLogCurve(curve: LogCurves.Curve): Boolean {
        activeCurve = curve
        val request = builder ?: return false
        applyToneCurve(request)
        val ok = apply()
        Trace.control("log curve", curve.displayName, if (ok) "applied" else "refused")
        return ok
    }

    val logCurve: LogCurves.Curve get() = activeCurve

    private fun applyToneCurve(request: CaptureRequest.Builder) {
        val caps = capabilities ?: return
        if (activeCurve == LogCurves.Curve.REC709 || !caps.supportsToneCurve) {
            request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            return
        }
        val points = caps.maxCurvePoints.coerceIn(2, 128)
        val samples = Mechanism.toneCurvePoints(activeCurve, points)
        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        request.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(samples, samples, samples))
    }

    // --- the light ------------------------------------------------------------

    var torchOn: Boolean = false
        private set

    /**
     * The lamp, on the repeating request rather than through
     * `CameraManager.setTorchMode`.
     *
     * The manager's route is refused outright while this app holds the camera
     * open, which is exactly when a light is wanted. FLASH_MODE on the request
     * the camera is already repeating is the one that works.
     */
    fun setTorch(on: Boolean): Boolean {
        val request = builder ?: return false
        if (!hasFlash()) {
            Trace.refused("torch", "this lens has no lamp")
            return false
        }
        request.set(
            CaptureRequest.FLASH_MODE,
            if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        val ok = apply()
        if (ok) torchOn = on
        Trace.control("torch", on, if (ok) on else "refused")
        return ok
    }

    fun hasFlash(): Boolean =
        characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    // --- focus ----------------------------------------------------------------

    private var pendingFocus: ((Boolean) -> Unit)? = null

    /**
     * One autofocus search at a point, reported when it settles.
     *
     * The point is a fraction of the frame; the camera wants it in sensor
     * pixels, and the two differ by the crop the active array is under. A
     * trigger without a region focuses on the middle whatever was tapped,
     * which is what a focus box that does nothing looks like.
     */
    fun focusAtNormalisedPoint(x: Float, y: Float, onResult: (Boolean) -> Unit): Boolean {
        val request = builder ?: return false
        val array = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return false

        val half = 0.08f
        val left = ((x - half).coerceIn(0f, 1f) * array.width()).toInt()
        val top = ((y - half).coerceIn(0f, 1f) * array.height()).toInt()
        val right = ((x + half).coerceIn(0f, 1f) * array.width()).toInt()
        val bottom = ((y + half).coerceIn(0f, 1f) * array.height()).toInt()
        val region = MeteringRectangle(
            Rect(left, top, maxOf(right, left + 1), maxOf(bottom, top + 1)),
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        pendingFocus = onResult
        request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        if (!apply()) { pendingFocus = null; return false }

        // The trigger is a one-shot beside the repeating request; leaving it on
        // the repeating one re-triggers the search every single frame.
        return try {
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session?.capture(request.build(), captureCallback, handler)
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            true
        } catch (e: Throwable) {
            Trace.fault("focus trigger", e)
            pendingFocus = null
            false
        }
    }

    /** Stops the lens where it has arrived, so nothing moves it again. */
    fun lockFocusHere(): Boolean {
        val request = builder ?: return false
        val reached = lastFocusDistance
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        if (reached != null) request.set(CaptureRequest.LENS_FOCUS_DISTANCE, reached)
        return apply()
    }

    /**
     * The lens in dioptres, exactly where asked. This is what drives a rack.
     *
     * Clamped, because beyond the closest the lens goes the request is invalid,
     * and an invalid repeating request stops the camera rather than being
     * ignored.
     */
    fun setFocusDistance(dioptres: Float): Boolean {
        val request = builder ?: return false
        val closest = minimumFocusDistance()
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        request.set(
            CaptureRequest.LENS_FOCUS_DISTANCE,
            dioptres.coerceIn(0f, if (closest > 0f) closest else dioptres)
        )
        return apply()
    }

    fun setContinuousFocus(): Boolean {
        val request = builder ?: return false
        request.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        return apply()
    }

    // --- the snap -------------------------------------------------------------

    /**
     * One still into a target the repeating request never touches.
     *
     * TEMPLATE_STILL_CAPTURE rather than the live builder, because the snap is
     * meant to be the sensor's own frame: no tone curve, no log, nothing this
     * app has done to the picture. That is the whole point of a DNG.
     */
    fun captureStill(
        target: Surface,
        onResult: (TotalCaptureResult?) -> Unit
    ): Boolean {
        val camera = device ?: return false
        val s = session ?: return false
        return try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            request.addTarget(target)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            // The sensor's own numbers, untouched.
            request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            if (torchOn) request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)

            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    cs: CameraCaptureSession,
                    r: CaptureRequest,
                    result: TotalCaptureResult
                ) = onResult(result)

                override fun onCaptureFailed(
                    cs: CameraCaptureSession,
                    r: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Trace.refused("snap", "capture failed, reason ${failure.reason}")
                    onResult(null)
                }
            }, handler)
            true
        } catch (e: Throwable) {
            Trace.fault("snap", e)
            false
        }
    }

    // --- what this camera can do ---------------------------------------------

    val sensorOrientation: Int
        get() = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    val isFrontFacing: Boolean
        get() = characteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT

    fun minimumFocusDistance(): Float =
        characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun supportsManualFocus(): Boolean = minimumFocusDistance() > 0f

    fun characteristicsOrNull(): CameraCharacteristics? = characteristics

    val isTenBit: Boolean get() = tenBitActive
}
