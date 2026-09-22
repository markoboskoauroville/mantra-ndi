package com.mantraproductions.ndi

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The encoder the camera writes into directly.
 *
 * Ten bit is the whole reason this exists. A dynamic range profile can only be
 * set on an OutputConfiguration at session creation, and it is only legal
 * against a surface whose format is PRIVATE or P010. A MediaCodec input
 * surface is PRIVATE; an OpenGL SurfaceTexture is not, which is why routing
 * frames through a GL stage caps the whole pipeline at eight bits no matter
 * what the sensor can do.
 *
 * So the camera targets this encoder's surface and nothing sits between them.
 * No GL pass, no copy, no conversion. The frames that leave here are the
 * frames the sensor made.
 *
 * HLG rather than HDR10: it needs no static metadata, it is what Android
 * requires any ten bit camera to support, and it survives being watched on an
 * ordinary screen, which HDR10 does not.
 */
class HdrVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val tenBit: Boolean,
    private val onFormat: (sps: ByteArray, pps: ByteArray?, vps: ByteArray?) -> Unit,
    private val onFrame: (data: ByteArray, isKeyframe: Boolean, ptsUs: Long, isHevc: Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    /** True once configured: ten bit forces HEVC, since AVC has no Main10 here. */
    val isHevc: Boolean get() = tenBit || preferHevc

    private var preferHevc = false

    /**
     * @return the surface the camera session should target, or null if this
     *   device cannot encode what was asked for.
     */
    fun start(): Surface? {
        val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )

            if (tenBit) {
                // Main10 and the HLG colour description together. Without the
                // description the file decodes as ordinary Rec.709 and the
                // picture comes back washed out, which looks like a grading
                // mistake rather than a missing tag.
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                )
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

        return try {
            val c = MediaCodec.createEncoderByType(mime)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            running.set(true)
            worker = thread(name = "hdr-video-encoder") { drain() }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "Could not start a ${if (tenBit) "10-bit HEVC" else "8-bit"} encoder", e)
            null
        }
    }

    fun stop() {
        running.set(false)
        worker?.join(1500)
        worker = null
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "encoder stop", e)
        }
        codec?.release()
        codec = null
    }

    fun requestKeyframe() {
        try {
            codec?.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        } catch (e: Exception) {
            // Not every encoder honours this; the periodic keyframe still comes.
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val c = codec ?: return

        while (running.get()) {
            val index = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val output = c.outputFormat
                // Parameter sets arrive in the format rather than as a frame on
                // most encoders, and NDI wants them as extra data on keyframes.
                val sps = output.getByteBuffer("csd-0")?.toArray()
                val pps = output.getByteBuffer("csd-1")?.toArray()
                val vps = output.getByteBuffer("csd-2")?.toArray()
                if (sps != null) {
                    // H.265 reports vps in csd-0; the ordering differs by codec.
                    if (isHevc) onFormat(sps, pps, vps) else onFormat(sps, pps, null)
                }
                continue
            }
            if (index < 0) continue

            val buffer = c.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Config sent through onFormat already.
                    c.releaseOutputBuffer(index, false)
                    continue
                }
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)

                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                onFrame(
                    bytes,
                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                    info.presentationTimeUs,
                    isHevc
                )
            }
            try {
                c.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                break
            }
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
    }

    private fun ByteBuffer.toArray(): ByteArray {
        val dup = duplicate()
        dup.rewind()
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }

    companion object {
        private const val TAG = "HdrVideoEncoder"

        /**
         * Whether this phone can encode ten bit HEVC at all. Asked before a
         * session is built, because a refused encoder after the camera is open
         * is a black screen the operator has to diagnose.
         */
        fun supportsTenBit(): Boolean = try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                        .profileLevels.any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                        }
            }
        } catch (e: Exception) {
            false
        }
    }
}
