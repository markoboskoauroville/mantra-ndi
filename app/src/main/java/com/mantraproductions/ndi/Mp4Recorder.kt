package com.mantraproductions.ndi

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes the frames that are already being encoded, to a file.
 *
 * Nothing is encoded twice. The same buffers that go to the network go to the
 * muxer, so recording costs a file write and no more, and the file is exactly
 * what the far end received rather than a second, slightly different render of
 * the same moment.
 *
 * The muxer cannot start until it has the real output format, which arrives
 * from the encoder rather than from anything we chose, so frames before that
 * are dropped on purpose. At a two second keyframe interval that is a fraction
 * of a second and it is always the correct fraction: starting a file mid-GOP
 * produces one no editor will open.
 *
 * Opened on a descriptor rather than a path, because from Android 10 an app
 * cannot write a path into the public folders at all — it is refused silently,
 * which is how this project once shipped ten versions believing there was a
 * crash log. [Recordings] hands over the descriptor MediaStore gave it.
 */
class Mp4Recorder(descriptor: FileDescriptor) {

    private var muxer: MediaMuxer? =
        runCatching { MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            .onFailure { Trace.fault("muxer open", it) }
            .getOrNull()

    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false

    private val written = AtomicLong(0)
    private val refused = AtomicLong(0)

    /**
     * A file must open on a keyframe.
     *
     * The muxer starts whenever the format arrives, which is very unlikely to
     * be a group boundary, and an MP4 whose first sample is a P-frame opens in
     * nothing: the decoder has no reference for it. So samples are let through
     * only from the first keyframe onwards. At a two second interval that is a
     * fraction of a second and it is always the correct fraction.
     */
    private var seenKeyframe = false

    /** Frames in the file, and frames the muxer would not take. */
    val frames: Long get() = written.get()
    val dropped: Long get() = refused.get()

    /** True once the muxer has a video format and the file is really being written. */
    val isWriting: Boolean get() = started

    val opened: Boolean get() = muxer != null

    @Synchronized
    fun setVideoFormat(format: MediaFormat) {
        val m = muxer ?: return
        if (videoTrack >= 0) return
        videoTrack = runCatching { m.addTrack(format) }
            .onFailure { Trace.fault("muxer video track", it) }
            .getOrDefault(-1)
        maybeStart()
    }

    @Synchronized
    fun setAudioFormat(format: MediaFormat) {
        val m = muxer ?: return
        if (audioTrack >= 0 || started) return
        // Audio may be ready first; the muxer will not start without video, so
        // the track is added and the file waits rather than starting without it.
        audioTrack = runCatching { m.addTrack(format) }
            .onFailure { Trace.fault("muxer audio track", it) }
            .getOrDefault(-1)
        maybeStart()
    }

    private fun maybeStart() {
        if (started || videoTrack < 0) return
        runCatching { muxer?.start(); started = true }
            .onFailure { Trace.fault("muxer start", it) }
    }

    @Synchronized
    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || videoTrack < 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        if (!seenKeyframe) {
            if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return
            seenKeyframe = true
        }
        try {
            muxer?.writeSampleData(videoTrack, buffer, info)
            written.incrementAndGet()
        } catch (t: Throwable) {
            // A refused sample is a frame that is not in the file. It was
            // logged and forgotten before, which is how a take comes back from
            // a shoot with gaps nobody knew about. Counted, and said on screen.
            refused.incrementAndGet()
        }
    }

    @Synchronized
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Sound before the first picture has nothing to sit against, and some
        // players treat it as a negative offset for the whole track.
        if (!started || audioTrack < 0 || !seenKeyframe) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        runCatching { muxer?.writeSampleData(audioTrack, buffer, info) }
    }

    @Synchronized
    fun stop() {
        // A file with no samples cannot be stopped cleanly; it is released and
        // discarded rather than crashing the take that came after it.
        if (started) runCatching { muxer?.stop() }
            .onFailure { Trace.fault("muxer stop", it) }
        runCatching { muxer?.release() }
        muxer = null
        started = false
        seenKeyframe = false
        videoTrack = -1
        audioTrack = -1
    }
}
