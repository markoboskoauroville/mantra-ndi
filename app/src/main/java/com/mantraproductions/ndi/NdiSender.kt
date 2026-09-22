package com.mantraproductions.ndi

import java.nio.ByteBuffer

/**
 * Thin Kotlin façade over the native bridge (`ndi_bridge.cpp`).
 *
 * [available] is false when the app was built without the NDI SDK dropped in.
 * Everything else — the capture, the encoder, the interface, the trace — still
 * works in that state, so there is an installable APK to test the screen side
 * with before the SDK is wired in. Nothing pretends to send; [create] answers
 * false and the state panel says why.
 */
object NdiSender {

    val available: Boolean = try {
        System.loadLibrary("ndi_bridge")
        true
    } catch (e: Throwable) {
        // Throwable, not Exception: a missing dependency of the .so arrives as
        // an UnsatisfiedLinkError, which is an Error and walks past a catch on
        // Exception.
        false
    }

    /** Opens the source. The name is what a receiver will see in its list. */
    fun create(sourceName: String): Boolean =
        if (available) nativeCreate(sourceName) else false

    fun destroy() {
        if (available) nativeDestroy()
    }

    /** Resolution and frame rate go on every NDI video frame header. */
    fun setVideoFormat(width: Int, height: Int, fpsNumerator: Int, fpsDenominator: Int = 1) {
        if (available) nativeSetVideoFormat(width, height, fpsNumerator, fpsDenominator)
    }

    /** SPS/PPS (and VPS for H.265), attached to every keyframe. Compressed mode only. */
    fun setVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?) {
        if (available) nativeSetVideoInfo(sps, pps, vps)
    }

    /** Forgets the parameter sets, because they describe a size that is gone. */
    fun clearVideoInfo() {
        if (available) nativeClearVideoInfo()
    }

    /**
     * One encoded access unit, on its way out as NDI HX.
     *
     * @param isPreviewStream false for the full-bandwidth stream, true for the
     *   low-resolution stream NDI expects alongside it on the compressed path.
     */
    fun sendCompressed(
        data: ByteArray,
        isKeyframe: Boolean,
        ptsUs: Long,
        isHevc: Boolean,
        isPreviewStream: Boolean = false
    ) {
        if (available) nativeSendCompressed(data, isKeyframe, ptsUs, isHevc, isPreviewStream)
    }

    /**
     * One whole RGBA frame, on its way out as full NDI.
     *
     * [buffer] must be direct — it is the ImageReader's own plane buffer — and
     * it must still be open when this returns, which is why the native call is
     * synchronous. [stride] is the reader's reported row stride in bytes and is
     * usually wider than width × 4.
     */
    fun sendRaw(buffer: ByteBuffer, width: Int, height: Int, stride: Int, ptsUs: Long) {
        if (available) nativeSendRaw(buffer, width, height, stride, ptsUs)
    }

    /** How many receivers are attached, or -1 if there is no sender. */
    fun connections(timeoutMs: Int = 0): Int =
        if (available) nativeConnections(timeoutMs) else -1

    /** Tally from the receiving mixer: bit 0 program, bit 1 preview, -1 none. */
    fun tally(timeoutMs: Int = 0): Int =
        if (available) nativeTally(timeoutMs) else -1

    private external fun nativeCreate(sourceName: String): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetVideoFormat(
        width: Int, height: Int, fpsNumerator: Int, fpsDenominator: Int
    )
    private external fun nativeSetVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?)
    private external fun nativeClearVideoInfo()
    private external fun nativeSendCompressed(
        data: ByteArray, isKeyframe: Boolean, ptsUs: Long, isHevc: Boolean, isPreviewStream: Boolean
    )
    private external fun nativeSendRaw(
        buffer: ByteBuffer, width: Int, height: Int, stride: Int, ptsUs: Long
    )
    private external fun nativeConnections(timeoutMs: Int): Int
    private external fun nativeTally(timeoutMs: Int): Int
}
