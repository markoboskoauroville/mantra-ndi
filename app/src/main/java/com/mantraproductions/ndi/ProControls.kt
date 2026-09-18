package com.mantraproductions.ndi

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import com.pedro.encoder.input.sources.video.Camera2Source

/**
 * The manual-control layer.
 *
 * Camera2Source gives us named helpers for the common things (auto exposure,
 * AWB, focus, zoom, stabilization, lens selection) plus setCustomRequest,
 * which hands you the raw CaptureRequest.Builder. That last one is the real
 * lever: ISO, shutter and the fps lock are all just CaptureRequest keys, so
 * everything a pro camera app does to the sensor can be done through it.
 *
 * Every setter returns Boolean where the camera can refuse — a phone that
 * doesn't report MANUAL_SENSOR won't honour ISO/shutter, and pretending
 * otherwise just produces confusing footage.
 */
class ProControls(private val source: Camera2Source, private val cameraManager: CameraManager) {

    /** Sensor ranges for the currently open camera, or null if unavailable. */
    fun isoRange(): Range<Int>? = characteristics()
        ?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    fun exposureTimeRange(): Range<Long>? = characteristics()
        ?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    fun supportsManualSensor(): Boolean {
        val caps = characteristics()?.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return false
        return caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )
    }

    /**
     * Constant frame rate. This is the one that matters for NDI: receivers
     * expect a steady cadence, and the usual cause of a drifting rate is auto
     * exposure stretching the shutter past the frame interval in low light and
     * silently halving fps.
     *
     * Pinning AE_TARGET_FPS_RANGE to [fps, fps] tells the camera it may not do
     * that. Pair it with setDynamicFps(false) so RootEncoder doesn't adapt
     * either, and with a manual shutter if you want a guarantee rather than a
     * strong hint.
     */
    fun lockFrameRate(fps: Int): Boolean {
        source.setDynamicFps(false)
        return source.setCustomRequest { builder ->
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }
    }

    /**
     * Manual exposure, applied in one capture request.
     *
     * Two things here were learned the hard way.
     *
     * First, SENSOR_FRAME_DURATION must be set explicitly. With AE off the
     * camera hands frame duration to the caller along with exposure and gain,
     * and CONTROL_AE_TARGET_FPS_RANGE stops meaning anything, because it only
     * ever constrained the auto exposure routine. Leave frame duration unset
     * and the sensor derives it from the exposure, so a long shutter quietly
     * drops the camera to a few frames a second and the preview looks hung.
     *
     * Second, this is one setCustomRequest rather than a disable followed by a
     * set. Each call rebuilds the repeating request on the camera thread, so
     * doing it twice per fader movement doubled the work for no reason.
     */
    fun setManualExposure(iso: Int, shutterNs: Long, frameDurationNs: Long = 0L): Boolean {
        if (!supportsManualSensor()) return false
        // Never ask for an exposure longer than the frame it has to fit inside.
        val exposure = if (frameDurationNs > 0) minOf(shutterNs, frameDurationNs) else shutterNs
        return source.setCustomRequest { builder ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            if (frameDurationNs > 0) {
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
            }
        }
    }

    fun setAutoExposure() {
        source.enableAutoExposure()
    }

    /** Lock AE at its current reading without going fully manual. */
    fun lockExposure(): Boolean = source.enableExposureLock()

    fun unlockExposure() = source.disableExposureLock()

    /**
     * Manual white balance by colour temperature.
     *
     * Camera2 has no Kelvin control, so the temperature is converted to an
     * illuminant colour and then inverted into per-channel gains: warm light
     * means the sensor already sees plenty of red, so red gain drops and blue
     * gain rises to bring white back to neutral.
     *
     * Needs MANUAL_POST_PROCESSING; returns false on a camera that lacks it
     * rather than silently doing nothing.
     */
    fun setManualWhiteBalance(kelvin: Int): Boolean {
        if (!supportsManualWhiteBalance()) return false
        val gains = kelvinToGains(kelvin)
        return source.setCustomRequest { builder ->
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            // With AWB off the transform is whatever was last set, which may be
            // nothing. Identity keeps the gains doing the work on their own.
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
        }
    }

    fun supportsManualWhiteBalance(): Boolean {
        val caps = characteristics()?.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return false
        return caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        )
    }

    /** The scene presets this camera offers (daylight, cloudy, incandescent and so on). */
    fun whiteBalancePresets(): List<Int> = source.getAutoWhiteBalanceModesAvailable()

    fun setWhiteBalancePreset(mode: Int): Boolean = source.enableAutoWhiteBalance(mode)

    fun setAutoWhiteBalance(): Boolean =
        source.enableAutoWhiteBalance(CaptureRequest.CONTROL_AWB_MODE_AUTO)

    fun lockWhiteBalance(): Boolean = source.enableWhiteBalanceLock()

    fun unlockWhiteBalance() = source.disableWhiteBalanceLock()

    fun setManualFocus(distanceDiopters: Float): Boolean {
        source.disableAutoFocus()
        return source.setCustomRequest { builder ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distanceDiopters)
        }
    }

    fun setAutoFocus(): Boolean = source.enableAutoFocus()

    /**
     * Focus as a fraction of the lens travel, 0 at infinity and 1 at the
     * closest the lens reaches. The travel differs per phone, so the UI works
     * in a fraction and the dioptres are computed here.
     */
    fun setManualFocus(fraction: Float): Boolean {
        val closest = characteristics()
            ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (closest <= 0f) return false
        return setManualFocus(closest * fraction.coerceIn(0f, 1f))
    }

    fun supportsManualFocus(): Boolean =
        (characteristics()?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f

    // What the camera last reported, so leaving auto can start from the values
    // the auto routine had already settled on.
    @Volatile var lastIso: Int? = null
        private set
    @Volatile var lastExposureNs: Long? = null
        private set

    init {
        source.setCustomOnCaptureCompletedCallback { _, _, result ->
            lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            lastExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        }
    }

    fun setStabilization(enabled: Boolean) {
        if (enabled) {
            // Optical first where it exists, it doesn't crop or add latency.
            if (!source.enableOpticalVideoStabilization()) source.enableVideoStabilization()
        } else {
            source.disableOpticalVideoStabilization()
            source.disableVideoStabilization()
        }
    }

    fun setZoom(level: Float) = source.setZoom(level)

    fun zoomRange(): Range<Float> = source.getZoomRange()

    /** Every camera id including physical sub-lenses on API 28+ (ultrawide, tele). */
    fun availableLenses(): List<String> {
        val logical = source.camerasAvailable().toList()
        val physical = try {
            source.physicalCamerasAvailable()
        } catch (e: Throwable) {
            emptyList()
        }
        return (logical + physical).distinct()
    }

    fun openLens(cameraId: String) = source.openCameraId(cameraId)

    fun resolutionsFor(): List<Size> = source.getCameraResolutions(source.getCameraFacing())

    fun maxFpsFor(size: Size): Int = source.getMaxSupportedFps(size)

    /**
     * Apply a whole profile's sensor settings. Resolution/bitrate/fps belong
     * to prepareVideo and are handled by the service before this runs.
     */
    fun apply(profile: CaptureProfile) {
        lockFrameRate(profile.fps)
        val iso = profile.isoValue
        val shutter = profile.shutterNs
        if (iso != null && shutter != null) {
            setManualExposure(iso, shutter)
        } else {
            setAutoExposure()
        }
        val kelvin = profile.whiteBalanceKelvin
        when {
            kelvin != null -> setManualWhiteBalance(kelvin)
            profile.lockWhiteBalance -> lockWhiteBalance()
            else -> unlockWhiteBalance()
        }
        setStabilization(profile.videoStabilization)
    }

    /**
     * Live sensor readback, for showing what the camera actually settled on
     * rather than what was asked for. Pass null to stop.
     */
    fun observeSensorValues(callback: ((iso: Int?, exposureNs: Long?) -> Unit)?) {
        if (callback == null) {
            source.setCustomOnCaptureCompletedCallback(null)
            return
        }
        source.setCustomOnCaptureCompletedCallback { _, _, result ->
            callback(
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
            )
        }
    }

    /**
     * Approximates the colour of a black body at [kelvin], then inverts it into
     * camera gains normalised so the smallest is 1.0, which is what
     * COLOR_CORRECTION_GAINS expects.
     */
    private fun kelvinToGains(kelvin: Int): RggbChannelVector {
        val (r, g, b) = Mechanism.kelvinToGains(kelvin).let { Triple(it[0], it[1], it[2]) }
        return RggbChannelVector(r, g, g, b)
    }

    private fun characteristics(): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(source.getCurrentCameraId())
    } catch (e: Exception) {
        null
    }

    companion object {
        const val KELVIN_MIN = Mechanism.KELVIN_MIN
        const val KELVIN_MAX = Mechanism.KELVIN_MAX

        private val IDENTITY_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1
            )
        )
    }
}
