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

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
        if (available) nativeSetAudioInfo(sampleRate, isStereo)
    }

    fun sendVideo(data: ByteArray, isKeyframe: Boolean, ptsUs: Long, isHevc: Boolean) {
        if (available) nativeSendVideo(data, isKeyframe, ptsUs, isHevc)
    }

    fun sendAudio(data: ByteArray, ptsUs: Long) {
        if (available) nativeSendAudio(data, ptsUs)
    }

    private external fun nativeCreate(sourceName: String): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?)
    private external fun nativeSetAudioInfo(sampleRate: Int, isStereo: Boolean)
    private external fun nativeSendVideo(data: ByteArray, isKeyframe: Boolean, ptsUs: Long, isHevc: Boolean)
    private external fun nativeSendAudio(data: ByteArray, ptsUs: Long)
}
