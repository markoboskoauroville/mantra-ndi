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

    /**
     * The characteristics of the lens actually being looked through.
     *
     * The same object as [characteristics] unless a physical sub-lens was
     * chosen, in which case it is that lens's own — which is where its
     * mounting angle, its focus range and its flash live.
     */
    private var lensCharacteristics: CameraCharacteristics? = null
    var capabilities: HdrCapabilities? = null
        private set

    var cameraId: String = "0"
        private set

    /**
     * The physical lens inside the opened logical camera, or null for it.
     *
     * A Pixel's ultra wide is not a camera you can open; it is a lens the back
     * camera owns. It is reached by naming it on each OutputConfiguration, so
     * the whole session looks through that lens rather than through whichever
     * one the logical camera's zoom logic felt like.
     */
    var physicalId: String? = null
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
        physicalId: String?,
        repeating: List<Surface>,
        deferred: List<Surface>,
        standard: List<Surface>,
        fps: Int,
        wantTenBit: Boolean,
        curve: LogCurves.Curve
    ) {
        close()
        this.cameraId = cameraId
        this.physicalId = physicalId
        this.lensCharacteristics = null
        this.targetFps = fps
        this.activeCurve = curve
        this.wantedRepeating = repeating
        this.wantedDeferred = deferred
        this.wantedStandard = standard

        val thread = HandlerThread("capture-engine").also { it.start() }
        this.thread = thread
        this.handler = Handler(thread.looper)

        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            capabilities = HdrCapabilities(characteristics!!)
            // A physical lens is mounted in its own right and may be turned
            // differently from the logical camera that owns it. Reading the
            // parent's angle and applying it to a sub-lens is a picture that
            // is upright on one lens and a quarter turn out on the next, with
            // nothing on screen to say why.
            lensCharacteristics = physicalId?.let {
                runCatching { cameraManager.getCameraCharacteristics(it) }.getOrNull()
            } ?: characteristics
        } catch (e: Exception) {
            listener?.onError("Could not read camera $cameraId: ${e.message}")
            return
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    Trace.step(
                        "camera $cameraId opened" +
                            (physicalId?.let { ", lens $it" } ?: "") +
                            ", mounted at ${sensorOrientation}°" +
                            ", rotate-and-crop modes " + rotateAndCropModes().joinToString(",")
                    )
                    // Focus, stated rather than assumed. Six versions of this
                    // app let a drag do nothing without a line anywhere to say
                    // the lens had no travel to give.
                    Trace.state(
                        "focus: lens travel " +
                            (lensCharacteristics?.get(
                                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                            ) ?: 0f) +
                            ", logical travel " +
                            (characteristics?.get(
                                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                            ) ?: 0f) +
                            ", AF modes " + afModes().joinToString(",") +
                            ", manual " + if (supportsManualFocus()) "yes" else "NO"
                    )
                    attempts = planAttempts(wantTenBit)
                    attemptIndex = 0
                    tryNextCombination(camera)
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

    /** One combination to offer the camera, and what to call it in the trace. */
    private data class Attempt(
        val label: String,
        val repeating: List<Surface>,
        val deferred: List<Surface>,
        val standard: List<Surface>,
        val tenBit: Boolean
    ) {
        val all: List<Surface> get() = repeating + deferred + standard
    }

    private var wantedRepeating: List<Surface> = emptyList()
    private var wantedDeferred: List<Surface> = emptyList()
    private var wantedStandard: List<Surface> = emptyList()
    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0

    /**
     * Every combination worth offering, best first.
     *
     * Camera2 does not say what it disliked. `onConfigureFailed` is one call
     * with no argument, so a session carrying four targets that is refused
     * tells you nothing about which of the four did it — and on the real Pixel
     * 7 the answer was a reader for a mode nobody had switched on, which took
     * the preview and the encoder down with it and left a black screen.
     *
     * The guaranteed combinations are also smaller than they look. Two
     * processed streams plus RAW plus a preview is beyond what any level below
     * LEVEL_3 promises, and a phone is free to refuse it for reasons of its
     * own even above that.
     *
     * So the targets are given up in the order they can most afford to be
     * lost: full NDI first, since HX is the mode that matters and the one the
     * hardware encoder makes for free; then RAW, since a snap is worth less
     * than a stream; then ten bit; and last of all the encoder, which leaves a
     * camera that shows a picture and cannot send it. Each refusal is named in
     * the trace, so the answer to "why is it 8-bit" is a fact rather than a
     * guess, and what survived decides which keys are lit.
     */
    private fun planAttempts(wantTenBit: Boolean): List<Attempt> {
        val encoder = wantedDeferred.take(1)
        val full = wantedDeferred.drop(1)
        val raw = wantedStandard

        val shapes = buildList {
            add(Triple("everything", wantedDeferred, raw))
            if (full.isNotEmpty()) add(Triple("without full NDI", encoder, raw))
            if (raw.isNotEmpty()) add(Triple("without full NDI or RAW", encoder, emptyList()))
            add(Triple("preview and encoder only", encoder, emptyList()))
            add(Triple("preview only", emptyList(), emptyList()))
        }.distinctBy { (_, d, st) -> d.size to st.size }

        // Ten bit is given up LAST, not first.
        //
        // This was backwards, and his Pixel 7 paid for it: the first attempt
        // (everything, 10-bit) was refused, the very next entry was
        // (everything, 8-bit) — which the camera took — and a phone that had
        // been giving 10-bit the version before silently dropped to 8, to make
        // room for a full-NDI reader and a 4032x3016 RAW stream he was not
        // using. Every shape is now tried at ten bit before any of them is
        // tried at eight.
        return buildList {
            if (wantTenBit) {
                for ((label, deferred, standard) in shapes) {
                    add(Attempt("$label, 10-bit", wantedRepeating, deferred, standard, true))
                }
            }
            for ((label, deferred, standard) in shapes) {
                add(Attempt("$label, 8-bit", wantedRepeating, deferred, standard, false))
            }
        }
    }

    /**
     * Offers the next combination, or gives up and says so.
     *
     * A camera that has refused a session is still open, so the next attempt
     * costs a session rather than a reopen.
     */
    private fun tryNextCombination(camera: CameraDevice) {
        val attempt = attempts.getOrNull(attemptIndex)
        if (attempt == null) {
            listener?.onError("No camera session this phone would accept")
            Trace.refused("camera session", "every combination was refused")
            return
        }

        liveSurfaces = attempt.repeating.toMutableList()
        standardSurfaces = attempt.standard
        allSurfaces = attempt.all
        tenBitActive = attempt.tenBit

        val profile = if (attempt.tenBit) capabilities?.bestTenBitProfile() else null
        if (attempt.tenBit && profile == null) {
            // Nothing to try here; the 8-bit twin is the next entry.
            attemptIndex++
            tryNextCombination(camera)
            return
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                Trace.state("session configured: ${attempt.label}")
                if (attemptIndex > 0) {
                    listener?.onError("Camera took ${attempt.label}")
                }
                startRepeating(camera)
                listener?.onReady(cameraId, tenBitActive, activeCurve)
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                Trace.refused("session", "${attempt.label} was refused")
                attemptIndex++
                tryNextCombination(camera)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && profile != null) {
                val configs = attempt.all.map { surface ->
                    OutputConfiguration(surface).apply {
                        // Every streaming target carries the profile, live or
                        // not. Only RAW is left at standard range: Bayer data
                        // has no transfer function to describe.
                        if (surface !in attempt.standard) dynamicRangeProfile = profile
                        applyPhysicalLens(this)
                    }
                }
                val executor = Executor { command -> handler?.post(command) ?: command.run() }
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, configs, executor, callback
                    )
                )
            } else if (physicalId != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                // Naming a lens needs OutputConfigurations, so the 8-bit path
                // takes the same route rather than the deprecated one.
                val configs = attempt.all.map {
                    OutputConfiguration(it).apply { applyPhysicalLens(this) }
                }
                val executor = Executor { command -> handler?.post(command) ?: command.run() }
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, configs, executor, callback
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(attempt.all, callback, handler)
            }
        } catch (e: Throwable) {
            Trace.fault("session ${attempt.label}", e)
            attemptIndex++
            tryNextCombination(camera)
        }
    }

    /**
     * Points one output at the chosen physical lens, if there is one.
     *
     * Silently nothing when the phone is too old or no lens was named, which
     * is the ordinary case: the logical camera then does what it always did.
     */
    private fun applyPhysicalLens(config: OutputConfiguration) {
        val physical = physicalId ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { config.setPhysicalCameraId(physical) }
            .onFailure { Trace.refused("physical lens $physical", Trace.describe(it)) }
    }

    /** True once the session actually carries this target. */
    fun isConfigured(surface: Surface?): Boolean = surface != null && surface in allSurfaces

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
            applyDistortionCorrection(request)

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

    /**
     * The LUT that goes down the wire, or none.
     *
     * Composed into the tone curve rather than drawn, because the tone mapper
     * is the only thing before the encoder that an app can reach. Setting null
     * puts the plain log curve back.
     */
    fun setWireLut(cube: CubeLut?): Boolean {
        wireLut = cube
        val request = builder ?: return false
        applyToneCurve(request)
        val ok = apply()
        Trace.control("LUT on the wire", cube?.title ?: "none", if (ok) "applied" else "refused")
        return ok
    }

    private var wireLut: CubeLut? = null

    val logCurve: LogCurves.Curve get() = activeCurve

    private fun applyToneCurve(request: CaptureRequest.Builder) {
        val caps = capabilities ?: return
        val cube = wireLut
        if (!caps.supportsToneCurve ||
            (activeCurve == LogCurves.Curve.REC709 && cube == null)
        ) {
            request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            return
        }
        val points = caps.maxCurvePoints.coerceIn(2, 128)
        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        if (cube == null) {
            val samples = Mechanism.toneCurvePoints(activeCurve, points)
            request.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(samples, samples, samples))
        } else {
            val (red, green, blue) = Mechanism.toneCurveThroughCube(activeCurve, cube, points)
            request.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(red, green, blue))
        }
    }

    /**
     * The lens's own distortion, undone by the phone that measured it.
     *
     * **This is very probably the "stretch".** An ultra wide is a fisheye with
     * the worst of it taken off: straight lines bow, and everything near an
     * edge is pulled outward. Camera2 hands over the sensor's raw geometry and
     * leaves the correction switched off unless it is asked for — while the
     * phone's own camera app always asks. So the same lens looks right in
     * Google's app and stretched in this one, which is exactly the complaint,
     * on exactly the lens he has had selected in every screenshot.
     *
     * It is not a scale factor and no transform could have fixed it: the
     * geometry readout says `squeeze 1.000` and is telling the truth. The
     * distortion is optical, it varies across the frame, and the only thing
     * that can undo it is the per-lens calibration the phone was measured with.
     *
     * HIGH_QUALITY where it is offered, FAST otherwise, and nothing at all on a
     * lens that does not list it — asking for a mode a camera did not advertise
     * stops the repeating request, which stops the preview.
     */
    private fun applyDistortionCorrection(request: CaptureRequest.Builder) {
        val modes = lensCharacteristics?.get(
            CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES
        ) ?: return
        val wanted = when {
            modes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) ->
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            modes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST) ->
                CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
            else -> {
                Trace.state("this lens corrects no distortion: modes " + modes.joinToString(","))
                return
            }
        }
        request.set(CaptureRequest.DISTORTION_CORRECTION_MODE, wanted)
        Trace.control(
            "distortion correction",
            "modes " + modes.joinToString(","),
            if (wanted == CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                "HIGH_QUALITY" else "FAST"
        )
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
        lensCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

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
        val request = builder ?: run {
            Trace.refused("focus", "the camera has no request to change")
            return false
        }
        val closest = minimumFocusDistance()
        val wanted = dioptres.coerceIn(0f, if (closest > 0f) closest else dioptres)
        // Focus regions belong to a tap on the picture. Left in place they are
        // what the camera goes back to the moment AF is switched on again, and
        // a rack that snaps back to the last tapped point is not a rack.
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        request.set(CaptureRequest.LENS_FOCUS_DISTANCE, wanted)
        val ok = apply()
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        Trace.control(
            "focus",
            String.format("%.3f dioptres (closest %.3f)", wanted, closest),
            if (ok) String.format("reached %.3f", lastFocusDistance ?: -1f) else "refused"
        )
        return ok
    }

    fun setContinuousFocus(): Boolean {
        val request = builder ?: return false
        request.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        return apply()
    }

    // --- manual exposure ------------------------------------------------------

    var manualExposure: Boolean = false
        private set

    /**
     * ISO and shutter together, clamped to what this sensor accepts.
     *
     * Clamped rather than trusted: a repeating request carrying a value outside
     * the reported range is not refused politely — the camera stops delivering
     * frames and the preview sits frozen while the app carries on as though
     * nothing happened. Asking the characteristics costs nothing and makes that
     * impossible.
     *
     * The shutter is also held at or under the frame duration, because a
     * shutter longer than a frame cannot be honoured at the frame rate and the
     * camera resolves the contradiction by dropping the rate — which on a live
     * stream is worse than a dark picture.
     */
    fun setManualExposure(iso: Int, shutterNs: Long): Boolean {
        val request = builder ?: return false
        manualExposure = true

        val frameDuration = Mechanism.frameDurationForFps(targetFps)
        val sensitivity = isoRange()?.let { iso.coerceIn(it.lower, it.upper) } ?: iso
        val exposure = exposureRange()?.let { shutterNs.coerceIn(it.lower, it.upper) } ?: shutterNs
        val safe = minOf(exposure, frameDuration).coerceAtLeast(1_000L)

        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        request.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safe)
        request.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
        val ok = apply()
        Trace.control("exposure", "iso $iso, ${Mechanism.formatShutter(shutterNs)}",
            if (ok) "iso $sensitivity, ${Mechanism.formatShutter(safe)}" else "refused")
        return ok
    }

    fun setAutoExposure(): Boolean {
        val request = builder ?: return false
        manualExposure = false
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
        val ok = apply()
        Trace.control("exposure", "auto", if (ok) "auto" else "refused")
        return ok
    }

    fun isoRange(): Range<Int>? =
        lensCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    fun exposureRange(): Range<Long>? =
        lensCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    /**
     * Every aperture this lens has.
     *
     * Usually exactly one. A phone lens has a fixed iris — the blades cost room
     * a phone does not have — so this is reported rather than driven, and the
     * key that would drive it says what it is instead of pretending to move.
     */
    fun apertures(): FloatArray =
        lensCharacteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?: FloatArray(0)

    fun setAperture(f: Float): Boolean {
        val available = apertures()
        if (available.size < 2) return false
        val request = builder ?: return false
        val nearest = available.minByOrNull { kotlin.math.abs(it - f) } ?: return false
        request.set(CaptureRequest.LENS_APERTURE, nearest)
        return apply()
    }

    /** Focus as a fraction of this lens's travel, 0 at infinity, 1 at closest. */
    fun setManualFocus(fraction: Float): Boolean {
        val closest = minimumFocusDistance()
        if (closest <= 0f) {
            Trace.refused("focus", "this lens has no focus travel; it is fixed")
            return false
        }
        return setFocusDistance(closest * fraction.coerceIn(0f, 1f))
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
        get() = lensCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    val isFrontFacing: Boolean
        get() = lensCharacteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT

    /**
     * Whether this camera can turn the picture inside its own pipeline.
     *
     * `SCALER_ROTATE_AND_CROP` is the one route to an upright *stream* that
     * costs nothing: it happens in the camera before the encoder, so it needs
     * no GPU stage — and a GPU stage is exactly what a ten bit session cannot
     * have, since a dynamic range profile is only legal against a PRIVATE or
     * P010 surface. Most phones offer only NONE and AUTO, so this is reported
     * rather than relied on.
     */
    fun rotateAndCropModes(): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics?.get(
                CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES
            ) ?: IntArray(0)
        } else IntArray(0)

    /**
     * How close this lens can be driven, in dioptres, or 0 for a fixed lens.
     *
     * **This is the focus bug.** It was read from the *physical* sub-lens
     * alone, and a Pixel's ultra wide reports 0 there because it has no focus
     * motor of its own. `setManualFocus` therefore refused before it reached
     * the camera, on every drag, and refused in silence: no request, no
     * `REFUSED` line, nothing in the trace at all. The column moved, the
     * number moved, and the lens never did — which is exactly what "focus
     * doesn't change focus" looks like from the outside.
     *
     * A capture request goes to the *logical* camera, not to the sub-lens, so
     * the logical camera's travel is what the request is validated against.
     * Whichever of the two reports travel is taken, and both go in the trace,
     * so a lens that genuinely cannot focus can be told apart from one this
     * app was asking the wrong question about.
     */
    fun minimumFocusDistance(): Float {
        val lens = lensCharacteristics
            ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val logical = characteristics
            ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return maxOf(lens, logical)
    }

    /** The AF modes this camera will actually accept. */
    fun afModes(): IntArray =
        characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)

    /**
     * Whether the lens can be driven by hand at all.
     *
     * Two things are needed and both have been assumed before: travel to drive
     * it through, and an `AF_MODE_OFF` the camera will take — without the
     * second, `LENS_FOCUS_DISTANCE` is ignored and the autofocus simply keeps
     * doing what it was doing.
     */
    fun supportsManualFocus(): Boolean =
        minimumFocusDistance() > 0f &&
            afModes().contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)

    fun characteristicsOrNull(): CameraCharacteristics? = characteristics

    val isTenBit: Boolean get() = tenBitActive
}
