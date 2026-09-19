package com.mantraproductions.ndi

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

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
 */
class Mp4Recorder(private val path: String) {

    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var pendingAudioFormat: MediaFormat? = null

    @Volatile var isRecording = false
        private set

    fun start(): Boolean = try {
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        isRecording = true
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not open $path", e)
        isRecording = false
        false
    }

    @Synchronized
    fun setVideoFormat(format: MediaFormat) {
        val m = muxer ?: return
        if (videoTrack >= 0) return
        videoTrack = m.addTrack(format)
        maybeStart()
    }

    @Synchronized
    fun setAudioFormat(format: MediaFormat) {
        val m = muxer ?: return
        if (audioTrack >= 0) return
        // Audio may be ready first; the muxer will not start without video, so
        // hold it rather than starting a file with a track missing.
        pendingAudioFormat = format
        audioTrack = m.addTrack(format)
        maybeStart()
    }

    private fun maybeStart() {
        if (started || videoTrack < 0) return
        try {
            muxer?.start()
            started = true
        } catch (e: Exception) {
            Log.e(TAG, "Muxer refused to start", e)
        }
    }

    @Synchronized
    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || videoTrack < 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        try {
            muxer?.writeSampleData(videoTrack, buffer, info)
            RecordingHealth.frameDelivered()
        } catch (e: Exception) {
            // A refused sample is a frame that is not in the file. It was
            // logged and forgotten before, which is how a take comes back from
            // a shoot with gaps nobody knew about.
            RecordingHealth.frameDropped()
            Log.w(TAG, "video sample", e)
        }
    }

    @Synchronized
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || audioTrack < 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        try {
            muxer?.writeSampleData(audioTrack, buffer, info)
        } catch (e: Exception) {
            Log.w(TAG, "audio sample", e)
        }
    }

    @Synchronized
    fun stop() {
        isRecording = false
        try {
            if (started) muxer?.stop()
        } catch (e: Exception) {
            // A file with no samples cannot be stopped cleanly; it is discarded.
            Log.w(TAG, "muxer stop", e)
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "muxer release", e)
        }
        muxer = null
        started = false
        videoTrack = -1
        audioTrack = -1
    }

    private companion object {
        const val TAG = "Mp4Recorder"
    }
}
