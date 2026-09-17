package com.mantraproductions.ndi

import java.nio.ByteBuffer

/**
 * Receives one NDI source as compressed frames.
 *
 * Video arrives as H.264/HEVC with the NDI packet header already stripped
 * native-side, ready to hand to MediaCodec. SpeedHQ senders report
 * [KIND_UNSUPPORTED] because Android has no decoder for it.
 */
object NdiReceiver {

    const val KIND_NONE = 0
    const val KIND_VIDEO = 1
    const val KIND_AUDIO = 2
    const val KIND_UNSUPPORTED = 3
    const val KIND_TOO_BIG = 4
    const val KIND_METADATA = 5

    val available: Boolean get() = NdiSender.available

    fun connect(sourceName: String): Boolean =
        if (available) nativeConnect(sourceName) else false

    fun disconnect() {
        if (available) nativeDisconnect()
    }

    /**
     * @param buffer direct ByteBuffer the frame is written into
     * @param info filled with size, ptsUs, isKeyframe, isHevc, width, height
     * @return one of the KIND_ constants
     */
    fun capture(buffer: ByteBuffer, info: LongArray, timeoutMs: Int = 1000): Int =
        if (available) nativeCapture(buffer, info, timeoutMs) else KIND_NONE

    /** Sends a control command upstream to the camera. */
    fun sendCommand(command: CameraCommand): Boolean =
        if (available) nativeSendMetadata(command.toXml()) else false

    private external fun nativeSendMetadata(xml: String): Boolean
    private external fun nativeConnect(sourceName: String): Boolean
    private external fun nativeDisconnect()
    private external fun nativeCapture(buffer: ByteBuffer, info: LongArray, timeoutMs: Int): Int
}
