package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

/**
 * The capture layer, owned rather than borrowed.
 *
 * Two things are only reachable from the session and the request, which is why
 * this exists instead of a wrapper around somebody else's camera:
 *
 *  - **Ten bit.** A dynamic range profile is set on the OutputConfiguration
 *    before the session is created. There is no way to add it afterwards, and
 *    no library that builds its own session can be asked to.
 *  - **Log.** TONEMAP_MODE_CONTRAST_CURVE with a supplied TONEMAP_CURVE applies
 *    the curve inside the camera pipeline. No GPU pass, no shader, no extra
 *    copy of every frame. It is how a pro app records log without the battery
 *    cost, and it lives on the repeating request.
 *
 * Everything the operator touches lands on one builder and goes out as one
 * repeating request, so a fader movement costs a single call no matter how
 * many parameters it changed.
 */
class CaptureEngine(private val context: Context) {

    interface Listener {
        fun onReady(cameraId: String, tenBit: Boolean, logCurve: LogCurves.Curve)
        fun onError(message: String)
        /** Live sensor readback, so the UI can show what the camera settled on. */
        fun onCaptureValues(iso: Int?, exposureNs: Long?, focusDistance: Float?)
    }

    var listener: Listener? = null

    private var cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var characteristics: CameraCharacteristics? = null
    var capabilities: HdrCapabilities? = null
        private set

    private var cameraId: String = "0"
    private var targetFps: Int = 25
    private var activeCurve: LogCurves.Curve = LogCurves.Curve.REC709
    private var tenBitActive = false

    /** Set false and the camera runs its own exposure routine. */
    private var manualExposure = false
    private var manualWhiteBalance = false
    private var manualFocus = false

    // What the camera last reported, kept so that switching from auto to manual
    // can start from the values auto had settled on rather than from nothing.
    @Volatile var lastReportedIso: Int? = null
        private set
    @Volatile var lastReportedExposureNs: Long? = null
        private set
    @Volatile var lastReportedFocus: Float? = null
        private set

    // --- lifecycle -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun open(
        cameraId: String,
        surfaces: List<Surface>,
        fps: Int,
        wantTenBit: Boolean,
        curve: LogCurves.Curve
    ) {
        close()
        this.cameraId = cameraId
        this.targetFps = fps
        this.activeCurve = curve

        val thread = HandlerThread("capture-engine").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler

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
                    createSession(camera, surfaces, wantTenBit)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    listener?.onError("Camera error $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            listener?.onError("Camera permission not granted")
        }
    }

    private fun createSession(camera: CameraDevice, surfaces: List<Surface>, wantTenBit: Boolean) {
        val profile = if (wantTenBit) capabilities?.bestTenBitProfile() else null

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                startRepeating(camera, surfaces)
                listener?.onReady(cameraId, tenBitActive, activeCurve)
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                // A ten bit profile against a surface that cannot carry it is
                // the usual cause, so fall back once rather than leaving a
                // black screen and a log line nobody reads.
                if (tenBitActive) {
                    Log.w(TAG, "10-bit session refused, retrying in 8-bit")
                    tenBitActive = false
                    createSession(camera, surfaces, wantTenBit = false)
                } else {
                    listener?.onError("Camera session could not be configured")
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && profile != null) {
                tenBitActive = true
                val configs = surfaces.map { surface ->
                    OutputConfiguration(surface).apply { dynamicRangeProfile = profile }
                }
                val executor = Executor { command -> handler?.post(command) ?: command.run() }
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, configs, executor, callback
                    )
                )
            } else {
                tenBitActive = false
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, callback, handler)
            }
        } catch (e: Exception) {
            listener?.onError("Session setup failed: ${e.message}")
        }
    }

    private fun startRepeating(camera: CameraDevice, surfaces: List<Surface>) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            surfaces.forEach { request.addTarget(it) }

            // Constant frame rate. The fps range constrains the auto routine;
            // the frame duration is what actually holds when that routine is
            // off, so both are set and kept in step.
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            request.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                Mechanism.frameDurationForFps(targetFps)
            )

            builder = request
            applyToneCurve(request)
            apply()
        } catch (e: Exception) {
            listener?.onError("Could not start the preview: ${e.message}")
        }
    }

    fun close() {
        try {
            session?.stopRepeating()
        } catch (e: Exception) {
            // Already gone; nothing to stop.
        }
        session?.close()
        session = null
        device?.close()
        device = null
        builder = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // --- the one place a request is issued -----------------------------------

    /**
     * Sends the current builder. Every setter below mutates the builder and
     * calls this, so a movement that changes ISO and shutter together is one
     * request, not two.
     */
    @Volatile private var lastReportedTransform:
        android.hardware.camera2.params.ColorSpaceTransform? = null

    /** The last request the camera actually accepted, to fall back to. */
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
            Log.w(TAG, "Repeating request refused", e)
            CrashLog.trace("request REFUSED: " + e.javaClass.simpleName + " " + e.message)
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
            lastReportedIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            lastReportedExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            lastReportedFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            // Kept so a manual white balance can hand back a transform the
            // camera itself produced rather than a textbook identity.
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                ?.let { lastReportedTransform = it }
            listener?.onCaptureValues(lastReportedIso, lastReportedExposureNs, lastReportedFocus)
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
        return apply()
    }

    private fun applyToneCurve(request: CaptureRequest.Builder) {
        val caps = capabilities ?: return
        if (activeCurve == LogCurves.Curve.REC709 || !caps.supportsToneCurve) {
            request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            return
        }

        val points = caps.maxCurvePoints.coerceIn(2, 128)
        val samples = Mechanism.toneCurvePoints(activeCurve, points)
        val curve = TonemapCurve(samples, samples, samples)
        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        request.set(CaptureRequest.TONEMAP_CURVE, curve)
    }

    // --- manual controls ------------------------------------------------------

    /**
     * Clamped to what this sensor actually accepts.
     *
     * A repeating request carrying a value outside the reported range is not
     * refused politely: the camera stops delivering frames and the preview sits
     * frozen while the app carries on as though nothing happened. Asking the
     * characteristics costs nothing and makes that impossible.
     */
    fun setManualExposure(iso: Int, shutterNs: Long): Boolean {
        val request = builder ?: return false
        manualExposure = true

        val frameDuration = Mechanism.frameDurationForFps(targetFps)
        val sensitivity = isoRange()?.let { iso.coerceIn(it.lower, it.upper) } ?: iso
        val exposure = exposureRange()
            ?.let { shutterNs.coerceIn(it.lower, it.upper) }
            ?: shutterNs
        val safeExposure = minOf(exposure, frameDuration).coerceAtLeast(1_000L)

        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        request.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExposure)
        request.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
        CrashLog.trace("exposure iso=" + sensitivity + " shutter=" + safeExposure)
        return apply()
    }

    fun setAutoExposure(): Boolean {
        val request = builder ?: return false
        manualExposure = false
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
        return apply()
    }

    /**
     * White balance by the camera's own presets.
     *
     * Gains alone are half of white balance; the other half is a colour
     * correction matrix that is calibrated per sensor and per illuminant and
     * is not something an app can compute. Supplying our gains beside somebody
     * else's matrix leaves the two disagreeing, and on a Bayer sensor that
     * disagreement reads as green, because green is the channel with twice the
     * samples. That is what happened twice, and no slider position undid it
     * because the slider was only ever moving one of the two halves.
     *
     * A preset moves both together, calibrated, by the people who measured the
     * sensor. Six steps instead of a continuous sweep, and all six correct.
     */
    fun setManualWhiteBalance(kelvin: Int): Boolean {
        val request = builder ?: return false
        manualWhiteBalance = true
        val preset = Mechanism.awbPresetFor(kelvin)

        request.set(CaptureRequest.CONTROL_AWB_MODE, preset)
        // Back to the camera's own colour pipeline: it owns both halves again.
        request.set(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CaptureRequest.COLOR_CORRECTION_MODE_FAST
        )
        CrashLog.trace("wb kelvin=" + kelvin + " preset=" + preset)
        return apply()
    }

    fun setAutoWhiteBalance(): Boolean {
        val request = builder ?: return false
        manualWhiteBalance = false
        request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        return apply()
    }

    /**
     * Manual focus, in dioptres. Zero is infinity and the maximum is the
     * closest the lens goes, which differs per phone, so the UI works in a
     * fraction and this converts.
     */
    /**
     * Dioptres, clamped to this lens.
     *
     * Beyond the minimum focus distance the request is invalid, and an invalid
     * repeating request stops the camera rather than being ignored.
     */
    fun setManualFocus(fraction: Float): Boolean {
        val request = builder ?: return false
        manualFocus = true
        val dioptres = minimumFocusDistance() * fraction.coerceIn(0f, 1f)
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        request.set(CaptureRequest.LENS_FOCUS_DISTANCE, dioptres)
        CrashLog.trace("focus dioptres=" + dioptres)
        return apply()
    }

    fun setAutoFocus(): Boolean {
        val request = builder ?: return false
        manualFocus = false
        request.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        return apply()
    }

    fun setZoom(ratio: Float): Boolean {
        val request = builder ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = zoomRatioRange() ?: return false
            request.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                ratio.coerceIn(range.lower, range.upper)
            )
            return apply()
        }
        return false
    }

    fun setStabilisation(enabled: Boolean): Boolean {
        val request = builder ?: return false
        request.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (enabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        return apply()
    }

    /**
     * What the sensor last reported, for anything driving it from outside.
     *
     * The values were already being read for the listener; they were simply
     * not readable by anyone holding the engine, which is why a link over it
     * had nothing to seed a fader from.
     */
    val lastIso: Int? get() = lastReportedIso
    val lastExposureNs: Long? get() = lastReportedExposureNs
    val lastFocusDistance: Float? get() = lastReportedFocus

    // --- what this camera can do ---------------------------------------------

    /**
     * How far the sensor is turned relative to the phone's natural position.
     *
     * Ninety degrees on nearly every phone. RootEncoder applies this for the
     * eight bit path; the direct pipeline sends the camera straight to the
     * view, so without it the picture arrives on its side, which is what the
     * ten bit preview has been doing.
     */
    fun sensorOrientation(): Int =
        characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /** The angle this sensor is mounted at, which nothing was asking for. */
    val sensorOrientation: Int
        get() = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    val isFrontFacing: Boolean
        get() = characteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT

    fun isoRange(): Range<Int>? =
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    fun exposureRange(): Range<Long>? =
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    fun minimumFocusDistance(): Float =
        characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun supportsManualFocus(): Boolean = minimumFocusDistance() > 0f

    fun zoomRatioRange(): Range<Float>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null

    fun supportsManualSensor(): Boolean {
        val caps = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )
    }

    private fun supportsManualPostProcessing(): Boolean {
        val caps = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        )
    }

    val isTenBit: Boolean get() = tenBitActive

    private companion object {
        const val TAG = "CaptureEngine"


    }
}
