package com.mantraproductions.ndi

import android.content.Context
import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import java.nio.ByteBuffer

/**
 * NDI output for RootEncoder.
 *
 * RootEncoder's own protocol classes (RtmpStream, RtspStream, SrtStream,
 * UdpStream) all work the same way: subclass StreamBase, and forward the
 * encoded frames the library hands you into whatever transport you want.
 * This does exactly that, except the transport is the NDI Advanced SDK via
 * [NdiSender].
 *
 * Everything upstream — Camera2, the hardware H.264/H.265 encoder, the AAC
 * encoder, rotation handling, the GL preview path — is RootEncoder's, which
 * is the whole point of rebasing onto it.
 *
 * Note that [startStream] still takes an endPoint String; NDI has no
 * endpoint, so we reuse it as the NDI source name (what shows up in
 * Studio Monitor / OBS / vMix).
 */
class NdiStream(
    context: Context,
    videoSource: VideoSource,
    audioSource: AudioSource
) : StreamBase(context, videoSource, audioSource) {

    /** The encoder timestamp of the most recent frame sent. */
    @Volatile var lastVideoPtsUs: Long = 0L
        private set


    constructor(context: Context) : this(context, Camera2Source(context), MicrophoneSource())

    private val streamClient = NdiStreamClient()

    /** Set before startStream if you want H.265 instead of H.264. */
    var useHevc: Boolean = false
        private set

    override fun getStreamClient(): NdiStreamClient = streamClient

    override fun setVideoCodecImp(codec: VideoCodec) {
        useHevc = codec == VideoCodec.H265
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        // NDI's compressed audio path is AAC; RootEncoder defaults to AAC so
        // nothing to do here. G711/OPUS would need transcoding — don't.
    }

    private var audioSampleRate: Int = 48000
    private var aacConfig: ByteArray = ByteArray(0)

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
        audioSampleRate = sampleRate
        NdiSender.setAudioInfo(sampleRate, isStereo)
    }

    /** Resolution and frame rate for the NDI frame headers. */
    fun setVideoFormat(width: Int, height: Int, fps: Int) {
        NdiSender.setVideoFormat(width, height, fps, 1)
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        // SPS/PPS (+VPS for H.265) become the "extra data" NDI wants attached
        // to keyframes. Cached native-side so each keyframe can carry it.
        NdiSender.setVideoInfo(sps.toByteArray(), pps?.toByteArray(), vps?.toByteArray())
    }

    override fun startStreamImp(endPoint: String) {
        val sourceName = endPoint.ifBlank { "NDI Camera" }
        if (!NdiSender.create(sourceName)) {
            throw IllegalStateException("NDIlib_send_create failed — libndi.so missing for this ABI?")
        }
    }

    override fun stopStreamImp() {
        NdiSender.destroy()
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        // Remembered so the timecode can be anchored against the same clock
        // the frames are stamped with, rather than against a reading taken at
        // some other moment.
        lastVideoPtsUs = info.presentationTimeUs
        NdiSender.sendVideo(videoBuffer.toByteArray(info), isKeyframe, info.presentationTimeUs, useHevc)
        streamClient.countVideoFrame()
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = audioBuffer.toByteArray(info)
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // AudioSpecificConfig, a couple of bytes; NDI wants it as extra data.
            aacConfig = bytes
            return
        }
        // AAC-LC is always 1024 samples per frame.
        NdiSender.sendAudio(bytes, aacConfig, AAC_SAMPLES_PER_FRAME, info.presentationTimeUs)
        streamClient.countAudioFrame()
    }

    private companion object {
        const val AAC_SAMPLES_PER_FRAME = 1024
    }

    private fun ByteBuffer.toByteArray(info: MediaCodec.BufferInfo? = null): ByteArray {
        val dup = duplicate()
        if (info != null) {
            dup.position(info.offset)
            dup.limit(info.offset + info.size)
        } else {
            dup.rewind()
        }
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }
}
