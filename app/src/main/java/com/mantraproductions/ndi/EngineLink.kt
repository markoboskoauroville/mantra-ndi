package com.mantraproductions.ndi

import android.util.Range

/**
 * Manual controls for the ten bit pipeline.
 *
 * This app has two capture paths. Eight bit runs through RootEncoder, which
 * hands over a Camera2Source that ProControls drives. Ten bit runs through
 * HdrPipeline straight into MediaCodec, because a GL stage cannot carry a ten
 * bit dynamic range profile, and its camera is a CaptureEngine.
 *
 * Only the first ever built a ProControls. So in ten bit the service's controls
 * were null, and every path that started `service?.controls?.` did nothing at
 * all: ISO, shutter, white balance, focus, zoom, stabilisation, the log curve
 * and the three way grade. The faders moved and the picture did not, which is
 * exactly the report, and it also explains why the same faders worked whenever
 * the phone happened to be in eight bit.
 *
 * CameraLink existed for precisely this, so the missing half is written here
 * rather than by teaching every caller about a second kind of camera.
 */
class EngineLink(val engine: CaptureEngine) : CameraLink {

    override val isRemote = false
    override val label = "This phone"

    override fun isoRange(): Range<Int>? = engine.isoRange()
    override fun exposureRange(): Range<Long>? = engine.exposureRange()

    override fun zoomRange(): Range<Float>? = engine.zoomRatioRange()

    override fun supportsManualSensor(): Boolean = engine.supportsManualSensor()

    /**
     * Colour temperature is set through the colour correction gains, which
     * every camera that reaches this pipeline has, so this is true wherever
     * the pipeline runs at all.
     */
    override fun supportsManualWhiteBalance(): Boolean = true

    override fun supportsManualFocus(): Boolean = engine.supportsManualFocus()

    override fun lastIso(): Int? = engine.lastIso
    override fun lastExposureNs(): Long? = engine.lastExposureNs

    override fun setManualExposure(iso: Int, shutterNs: Long, frameDurationNs: Long) {
        // The engine holds the frame duration on the repeating request, so
        // only the two numbers a person actually set are passed on.
        engine.setManualExposure(iso, shutterNs)
    }

    override fun setAutoExposure() {
        engine.setAutoExposure()
    }

    override fun setManualWhiteBalance(kelvin: Int) {
        engine.setManualWhiteBalance(kelvin)
    }

    override fun setAutoWhiteBalance() {
        engine.setAutoWhiteBalance()
    }

    override fun setFocusFraction(fraction: Float) {
        engine.setManualFocus(fraction)
    }

    override fun setAutoFocus() {
        engine.setAutoFocus()
    }

    override fun setZoom(ratio: Float) {
        engine.setZoom(ratio)
    }

    override fun setStabilisation(enabled: Boolean) {
        engine.setStabilisation(enabled)
    }

    /** Recording is the service's job in either pipeline, not the camera's. */
    override fun startRecording() = Unit
    override fun stopRecording() = Unit
}
