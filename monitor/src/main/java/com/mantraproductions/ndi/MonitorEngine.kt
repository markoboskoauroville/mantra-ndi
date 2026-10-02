package com.mantraproductions.ndi

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Receive side of the monitor.
 *
 * NDI hands us compressed H.264/HEVC, which goes straight into MediaCodec
 * configured against the output Surface. That means no pixel ever touches the
 * app: the decoder writes directly into the display buffer, which is why this
 * stays cheap enough to run on a phone at 1080p.
 *
 * The decoder can't be configured until the first keyframe arrives, since its
 * dimensions come from the stream rather than from us.
 */
/** How long a decoded frame stays believable before it is treated as stale. */
private const val SIGNAL_TIMEOUT_MS = 1500L

class MonitorEngine(
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onCameraState: (CameraState) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var worker: Thread? = null

    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredHevc = false

    fun start(sourceName: String) {
        if (running.get()) return
        running.set(true)

        worker = thread(name = "ndi-monitor") {
            if (!NdiReceiver.connect(sourceName)) {
                postStatus("Could not connect to $sourceName")
                running.set(false)
                return@thread
            }
            postStatus("Connected, waiting for video")
            captureLoop()
            NdiReceiver.disconnect()
            releaseCodec()
        }
    }

    @Volatile private var lastFrameAt = 0L

    /**
     * The timecode carried by the most recent frame, in 100ns units, or zero
     * if this source stamps none. Read straight off the picture, so a follower
     * is on the master's clock without decoding anything extra.
     */
    @Volatile var lastTimecode100ns = 0L
        private set

    /** The size of the picture arriving, so the view can be shaped to it. */
    @Volatile var lastWidth = 0
        private set
    @Volatile var lastHeight = 0
        private set

    /**
     * Whether anything has arrived recently enough to still be on screen.
     * A surface keeps its last frame forever, so silence has to be measured
     * rather than observed.
     */
    fun hasRecentFrame(): Boolean =
        lastFrameAt != 0L &&
            android.os.SystemClock.elapsedRealtime() - lastFrameAt < SIGNAL_TIMEOUT_MS

    internal fun markFrame() {
        lastFrameAt = android.os.SystemClock.elapsedRealtime()
    }

    fun stop() {
        // Stop claiming to watch something we are no longer watching, or the
        // camera keeps its yellow border after every monitor has gone.
        runCatching { NdiReceiver.setTally(onProgram = false, onPreview = true) }
        runCatching { NdiReceiver.setTally(onProgram = false, onPreview = false) }

        running.set(false)
        worker?.join(2000)
        worker = null
    }

    private fun captureLoop() {
        // 4 MB covers a 1080p keyframe with room to spare; oversized frames are
        // reported rather than silently truncated.
        val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        // Seven: size, pts, keyframe, spare, width, height, and the timecode
        // the sender stamped on this frame.
        val info = LongArray(7)
        var framesSeen = 0L
        var warnedUnsupported = false

        // Watching counts as preview. The source shows yellow for this.
        NdiReceiver.setTally(onProgram = false, onPreview = true)

        while (running.get()) {
            buffer.clear()
            when (NdiReceiver.capture(buffer, info, timeoutMs = 1000)) {
                NdiReceiver.KIND_VIDEO -> {
                    val size = info[0].toInt()
                    if (size <= 0) continue
                    val ptsUs = info[1]
                    val isKeyframe = info[2] == 1L
                    val isHevc = info[3] == 1L
                    val width = info[4].toInt()
                    val height = info[5].toInt()
                    lastTimecode100ns = info[6]
                    if (width > 0 && height > 0) {
                        lastWidth = width
                        lastHeight = height
                    }

                    if (codec == null || width != configuredWidth ||
                        height != configuredHeight || isHevc != configuredHevc
                    ) {
                        // Wait for a keyframe: configuring mid-GOP gives a
                        // decoder no parameter sets and a green mess on screen.
                        if (!isKeyframe) continue
                        if (!configureCodec(width, height, isHevc)) return
                    }

                    feedDecoder(buffer, size, ptsUs)
                    drainDecoder()

                    if (++framesSeen == 1L) postStatus("Receiving ${width}x$height")
                }

                NdiReceiver.KIND_UNSUPPORTED -> {
                    if (!warnedUnsupported) {
                        warnedUnsupported = true
                        postStatus("This source sends NDI High Bandwidth (SpeedHQ), which Android can't decode. Use an HX source.")
                    }
                }

                NdiReceiver.KIND_METADATA -> {
                    val size = info[0].toInt()
                    if (size > 0) {
                        val bytes = ByteArray(size)
                        buffer.position(0)
                        buffer.get(bytes)
                        CameraState.parse(String(bytes))?.let(onCameraState)
                    }
                }

                NdiReceiver.KIND_TOO_BIG ->
                    Log.w(TAG, "Frame larger than the capture buffer, dropped")
            }
        }
    }

    private fun configureCodec(width: Int, height: Int, isHevc: Boolean): Boolean {
        releaseCodec()
        val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            configuredWidth = width
            configuredHeight = height
            configuredHevc = isHevc
            true
        } catch (e: Exception) {
            Log.e(TAG, "Decoder setup failed", e)
            postStatus("No hardware decoder for ${if (isHevc) "H.265" else "H.264"} at ${width}x$height")
            false
        }
    }

    private fun feedDecoder(buffer: ByteBuffer, size: Int, ptsUs: Long) {
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(10_000)
            if (index < 0) return
            val input = c.getInputBuffer(index) ?: return
            input.clear()
            buffer.position(0)
            buffer.limit(size)
            input.put(buffer)
            c.queueInputBuffer(index, 0, size, ptsUs, 0)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder input failed", e)
        }
    }

    private fun drainDecoder() {
        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = c.dequeueOutputBuffer(bufferInfo, 0)
                if (index < 0) break
                // true = hand the frame to the Surface for display.
                // Rendered, so the surface now holds something real. This is
                // the only moment that can be trusted as "there is a picture".
                c.releaseOutputBuffer(index, true)
                markFrame()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder output failed", e)
        }
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Decoder stop failed", e)
        }
        codec?.release()
        codec = null
        configuredWidth = 0
        configuredHeight = 0
    }

    private fun postStatus(message: String) = onStatus(message)

    private companion object {
        const val TAG = "MonitorEngine"
    }
}
