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

    /**
     * Opens the source. The name is what a receiver will see in its list. [configJson] (v135) is the NDI JSON
     * configuration for this sender, [Routing.ndiConfig]: the one adapter it may use. Null leaves it to the SDK.
     */
    fun create(sourceName: String, configJson: String? = null): Boolean =
        if (available) nativeCreate(sourceName, configJson) else false

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
     * One whole camera frame, on its way out as full NDI.
     *
     * The planes must be the ImageReader's own direct buffers and the Image
     * must still be open when this returns, which is why the native call is
     * synchronous. Every stride comes from the reader; none of them is ever
     * width, and the chroma planes carry a pixel stride too.
     */
    fun sendYuv420(
        y: ByteBuffer, yStride: Int,
        u: ByteBuffer, uStride: Int,
        v: ByteBuffer, vStride: Int,
        uvPixelStride: Int,
        width: Int, height: Int, ptsUs: Long,
        /** v136: quarter turns clockwise, so full NDI follows the phone's orientation too. */
        turns: Int = 0
    ) {
        if (available) {
            nativeSendYuv420(
                y, yStride, u, uStride, v, vStride, uvPixelStride, width, height, ptsUs, turns
            )
        }
    }

    /** How many receivers are attached, or -1 if there is no sender. */
    fun connections(timeoutMs: Int = 0): Int =
        if (available) nativeConnections(timeoutMs) else -1

    /** Tally from the receiving mixer: bit 0 program, bit 1 preview, -1 none. */
    fun tally(timeoutMs: Int = 0): Int =
        if (available) nativeTally(timeoutMs) else -1

    /**
     * v135: one buffer of 16-bit interleaved PCM into the stream, beside the picture. False when there is no sender
     * (between modes, or mid-rebuild), which drops that buffer and nothing else.
     */
    fun sendAudio(pcm: ByteArray, bytes: Int, channels: Int, sampleRate: Int): Boolean =
        if (available) runCatching { nativeSendAudio(pcm, bytes, channels, sampleRate) }.getOrDefault(false) else false

    private external fun nativeCreate(sourceName: String, configJson: String?): Boolean
    private external fun nativeSendAudio(pcm: ByteArray, bytes: Int, channels: Int, sampleRate: Int): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetVideoFormat(
        width: Int, height: Int, fpsNumerator: Int, fpsDenominator: Int
    )
    private external fun nativeSetVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?)
    private external fun nativeClearVideoInfo()
    private external fun nativeSendCompressed(
        data: ByteArray, isKeyframe: Boolean, ptsUs: Long, isHevc: Boolean, isPreviewStream: Boolean
    )
    private external fun nativeSendYuv420(
        y: ByteBuffer, yStride: Int,
        u: ByteBuffer, uStride: Int,
        v: ByteBuffer, vStride: Int,
        uvPixelStride: Int,
        width: Int, height: Int, ptsUs: Long, turns: Int
    )
    private external fun nativeConnections(timeoutMs: Int): Int

    /** v117, REMOTE CONTROL: the next command a monitor sent up this source, or null. Never blocks. */
    fun pollCommand(): String? = if (available) runCatching { nativePollMetadata() }.getOrNull() else null

    /** v117: the camera's state to every receiver. */
    fun sendState(xml: String): Boolean = if (available) runCatching { nativeSendMetadata(xml) }.getOrDefault(false) else false

    private external fun nativePollMetadata(): String?
    private external fun nativeSendMetadata(xml: String): Boolean
    private external fun nativeTally(timeoutMs: Int): Int
}
