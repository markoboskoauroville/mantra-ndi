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
     * Manual exposure. Passing both ISO and shutter switches AE off entirely,
     * which is what you want for anything being cut together later — no
     * mid-shot exposure ramps.
     */
    fun setManualExposure(iso: Int, shutterNs: Long): Boolean {
        if (!supportsManualSensor()) return false
        source.disableAutoExposure()
        return source.setCustomRequest { builder ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
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
        val t = kelvin.coerceIn(KELVIN_MIN, KELVIN_MAX) / 100.0

        val red = if (t <= 66) 255.0
        else (329.698727446 * Math.pow(t - 60, -0.1332047592)).coerceIn(0.0, 255.0)

        val green = if (t <= 66) (99.4708025861 * Math.log(t) - 161.1195681661).coerceIn(0.0, 255.0)
        else (288.1221695283 * Math.pow(t - 60, -0.0755148492)).coerceIn(0.0, 255.0)

        val blue = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> (138.5177312231 * Math.log(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        // Guard against a zero channel before inverting.
        val r = red.coerceAtLeast(1.0)
        val g = green.coerceAtLeast(1.0)
        val b = blue.coerceAtLeast(1.0)

        var rGain = (255.0 / r).toFloat()
        var gGain = (255.0 / g).toFloat()
        var bGain = (255.0 / b).toFloat()

        val smallest = minOf(rGain, gGain, bGain)
        rGain /= smallest
        gGain /= smallest
        bGain /= smallest

        return RggbChannelVector(rGain, gGain, gGain, bGain)
    }

    private fun characteristics(): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(source.getCurrentCameraId())
    } catch (e: Exception) {
        null
    }

    companion object {
        const val KELVIN_MIN = 2000
        const val KELVIN_MAX = 10000

        private val IDENTITY_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1
            )
        )
    }
}
