package com.mantraproductions.ndi

import android.util.Range

/**
 * One set of controls, two places they can land.
 *
 * The camera screen should not care whether the sensor it is driving is in
 * this phone or in another one on the network. Both answer the same questions,
 * so both are asked through this, and the only thing that changes between
 * local and remote is which implementation is behind it.
 *
 * Remote answers are necessarily thinner: a camera across the network reports
 * its ranges through NDI metadata and there is a round trip before anything
 * comes back, so a remote link returns what it was last told rather than what
 * is true this millisecond. That is honest and it is enough, because the
 * faders are corrections rather than absolutes.
 */
interface CameraLink {

    val isRemote: Boolean

    /** Empty for a local camera, the source name for a remote one. */
    val label: String

    fun isoRange(): Range<Int>?
    fun exposureRange(): Range<Long>?
    fun zoomRange(): Range<Float>?
    fun supportsManualSensor(): Boolean
    fun supportsManualWhiteBalance(): Boolean
    fun supportsManualFocus(): Boolean

    /** What the camera last reported it was using. */
    fun lastIso(): Int?
    fun lastExposureNs(): Long?

    fun setManualExposure(iso: Int, shutterNs: Long, frameDurationNs: Long)
    fun setAutoExposure()
    fun setManualWhiteBalance(kelvin: Int)
    fun setAutoWhiteBalance()
    fun setFocusFraction(fraction: Float)
    fun setAutoFocus()
    fun setZoom(ratio: Float)
    fun setStabilisation(enabled: Boolean)

    fun startRecording()
    fun stopRecording()
}

/** The sensor in this phone. */
class LocalLink(private val controls: ProControls) : CameraLink {

    override val isRemote = false
    override val label = ""

    override fun isoRange(): Range<Int>? = controls.isoRange()
    override fun exposureRange(): Range<Long>? = controls.exposureTimeRange()
    override fun zoomRange(): Range<Float> = controls.zoomRange()
    override fun supportsManualSensor() = controls.supportsManualSensor()
    override fun supportsManualWhiteBalance() = controls.supportsManualWhiteBalance()
    override fun supportsManualFocus() = controls.supportsManualFocus()

    override fun lastIso() = controls.lastIso
    override fun lastExposureNs() = controls.lastExposureNs

    override fun setManualExposure(iso: Int, shutterNs: Long, frameDurationNs: Long) {
        controls.setManualExposure(iso, shutterNs, frameDurationNs)
    }

    override fun setAutoExposure() { controls.setAutoExposure() }
    override fun setManualWhiteBalance(kelvin: Int) { controls.setManualWhiteBalance(kelvin) }
    override fun setAutoWhiteBalance() { controls.setAutoWhiteBalance() }
    override fun setFocusFraction(fraction: Float) { controls.setFocusFraction(fraction) }
    override fun setAutoFocus() { controls.setAutoFocus() }
    override fun setZoom(ratio: Float) { controls.setZoom(ratio) }
    override fun setStabilisation(enabled: Boolean) { controls.setStabilization(enabled) }

    // Recording on a local camera belongs to the service, which owns the muxer.
    override fun startRecording() {}
    override fun stopRecording() {}
}

/**
 * A camera on the network, driven by the metadata channel that already carries
 * the video. State arrives from the camera's own reports; until the first one
 * lands the ranges are null and the panel dims itself, which is the truth.
 */
class RemoteLink(private val sourceName: String) : CameraLink {

    override val isRemote = true
    override val label: String get() = state?.cameraName?.ifEmpty { sourceName } ?: sourceName

    /** Updated by the monitor engine each time the camera reports itself. */
    @Volatile var state: CameraState? = null

    override fun isoRange(): Range<Int>? = state
        ?.takeIf { it.isoMax > it.isoMin }
        ?.let { Range(it.isoMin, it.isoMax) }

    override fun exposureRange(): Range<Long>? = state
        ?.takeIf { it.shutterMaxNs > it.shutterMinNs }
        ?.let { Range(it.shutterMinNs, it.shutterMaxNs) }

    // Zoom is not reported over the wire yet, so a sane span rather than a lie.
    override fun zoomRange(): Range<Float> = Range(1f, 10f)

    override fun supportsManualSensor() = state?.manualSupported ?: false
    override fun supportsManualWhiteBalance() = state?.whiteBalanceSupported ?: false
    override fun supportsManualFocus() = state != null

    override fun lastIso() = state?.iso
    override fun lastExposureNs() = state?.shutterNs

    override fun setManualExposure(iso: Int, shutterNs: Long, frameDurationNs: Long) {
        send(CameraCommand(iso = iso, shutterNs = shutterNs, exposureMode = "manual"))
    }

    override fun setAutoExposure() = send(CameraCommand(exposureMode = "auto"))
    override fun setManualWhiteBalance(kelvin: Int) = send(CameraCommand(whiteBalanceKelvin = kelvin))
    override fun setAutoWhiteBalance() = send(CameraCommand(whiteBalanceAuto = true))
    override fun setFocusFraction(fraction: Float) = Unit // no remote focus command yet
    override fun setAutoFocus() = Unit
    override fun setZoom(ratio: Float) = send(CameraCommand(zoom = ratio))
    override fun setStabilisation(enabled: Boolean) = send(CameraCommand(stabilization = enabled))

    override fun startRecording() = send(CameraCommand(record = "start"))
    override fun stopRecording() = send(CameraCommand(record = "stop"))

    private fun send(command: CameraCommand) {
        NdiReceiver.sendCommand(command)
    }
}
