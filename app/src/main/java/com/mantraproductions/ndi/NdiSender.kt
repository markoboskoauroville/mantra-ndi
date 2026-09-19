package com.mantraproductions.ndi

import android.util.Log

/**
 * Thin Kotlin façade over the native NDI bridge (ndi_bridge.cpp).
 *
 * Frames arrive already compressed (H.264/H.265 + AAC) from RootEncoder's
 * hardware encoders; the native side packs them into
 * NDIlib_compressed_packet_t and hands them to the Advanced SDK.
 *
 * [available] is false when the app was built without the NDI SDK dropped in
 * (see README). Everything else in the app — camera, preview, manual
 * controls, profiles — still works in that state, you just can't send. That's
 * deliberate: it keeps CI green and gives you an installable APK to test the
 * camera side before the SDK paperwork is done.
 */
object NdiSender {

    val available: Boolean = try {
        System.loadLibrary("ndi_bridge")
        true
    } catch (e: Throwable) {
        Log.w("NdiSender", "libndi_bridge not present — NDI sending disabled")
        false
    }

    fun create(sourceName: String): Boolean =
        if (available) nativeCreate(sourceName) else false

    fun destroy() {
        if (available) nativeDestroy()
    }

    fun setVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?) {
        if (available) nativeSetVideoInfo(sps, pps, vps)
    }

    /** Resolution and frame rate go on every NDI video frame header. */
    fun setVideoFormat(width: Int, height: Int, fpsNumerator: Int, fpsDenominator: Int = 1) {
        if (available) nativeSetVideoFormat(width, height, fpsNumerator, fpsDenominator)
    }

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
        if (available) nativeSetAudioInfo(sampleRate, isStereo)
    }

    /**
     * @param isPreviewStream false for the full-bandwidth stream, true for the
     *   low-res stream NDI expects alongside it on the compressed path.
     */
    fun sendVideo(
        data: ByteArray,
        isKeyframe: Boolean,
        ptsUs: Long,
        isHevc: Boolean,
        isPreviewStream: Boolean = false
    ) {
        if (available) nativeSendVideo(data, isKeyframe, ptsUs, isHevc, isPreviewStream)
    }

    fun sendAudio(data: ByteArray, extraData: ByteArray, sampleCount: Int, ptsUs: Long) {
        if (available) nativeSendAudio(data, extraData, sampleCount, ptsUs)
    }

    /** Commands sent upstream by a connected Monitor, or null if none waiting. */
    fun captureMetadata(timeoutMs: Int = 500): String? =
        if (available) nativeCaptureMetadata(timeoutMs) else null

    /** Publishes camera state to every attached receiver. */
    fun addConnectionMetadata(xml: String) {
        if (available) nativeAddConnectionMetadata(xml)
    }

    /** Tally from the receiving mixer: bit 0 program, bit 1 preview, -1 none. */
    fun getTally(timeoutMs: Int = 1000): Int =
        if (available) nativeGetTally(timeoutMs) else -1

    /**
     * Stamps this source's frames with a real timecode.
     *
     * NDI carries a timecode on every frame, so a follower reads the master's
     * clock off the picture it just decoded and there is nothing left to line
     * up afterwards. That is why this beats sending the clock alongside.
     *
     * @param timecode100ns the clock now, in 100ns units since midnight
     * @param atPtsUs the encoder timestamp current when it was read
     *
     * Both, because the two clocks tick independently. Anchoring the pair lets
     * every later frame be stamped by how far its own timestamp has moved, so
     * the stamp advances with the video rather than with whenever this was
     * last called. Zero returns the source to reporting encoder time, which is
     * the honest answer for a camera following no clock.
     */
    fun setTimecode(timecode100ns: Long, atPtsUs: Long) {
        if (available) nativeSetTimecode(timecode100ns, atPtsUs)
    }

    private external fun nativeSetTimecode(timecode100ns: Long, atPtsUs: Long)
    private external fun nativeGetTally(timeoutMs: Int): Int
    private external fun nativeAddConnectionMetadata(xml: String)
    private external fun nativeCaptureMetadata(timeoutMs: Int): String?
    private external fun nativeCreate(sourceName: String): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?)
    private external fun nativeSetVideoFormat(width: Int, height: Int, fpsNumerator: Int, fpsDenominator: Int)
    private external fun nativeSetAudioInfo(sampleRate: Int, isStereo: Boolean)
    private external fun nativeSendVideo(
        data: ByteArray, isKeyframe: Boolean, ptsUs: Long, isHevc: Boolean, isPreviewStream: Boolean
    )
    private external fun nativeSendAudio(data: ByteArray, extraData: ByteArray, sampleCount: Int, ptsUs: Long)
}
