package com.mantraproductions.ndi

import com.pedro.common.socket.base.SocketType
import com.pedro.library.util.streamclient.StreamBaseClient

/**
 * StreamBase requires a StreamBaseClient. Its whole surface is about socket
 * tuning, auth, retry and congestion stats for RTMP/RTSP/SRT — the NDI SDK
 * owns all of that internally and exposes none of it to us, so almost
 * everything here is a deliberate no-op.
 *
 * The frame counters are real though: cheap to keep and genuinely useful for
 * telling "the stream is alive" from "the encoder stalled" when you're
 * staring at a black tile in Studio Monitor.
 */
class NdiStreamClient : StreamBaseClient() {

    @Volatile private var videoFrameCount: Long = 0
    @Volatile private var audioFrameCount: Long = 0

    internal fun countVideoFrame() { videoFrameCount++ }
    internal fun countAudioFrame() { audioFrameCount++ }

    override fun getSentVideoFrames(): Long = videoFrameCount
    override fun getSentAudioFrames(): Long = audioFrameCount
    override fun resetSentVideoFrames() { videoFrameCount = 0 }
    override fun resetSentAudioFrames() { audioFrameCount = 0 }

    // NDI does its own buffering/pacing — nothing meaningful to report.
    override fun getDroppedVideoFrames(): Long = 0
    override fun getDroppedAudioFrames(): Long = 0
    override fun resetDroppedVideoFrames() {}
    override fun resetDroppedAudioFrames() {}
    override fun getBytesSend(): Long = 0
    override fun resetBytesSend() {}
    override fun getCacheSize(): Int = 0
    override fun getItemsInCache(): Int = 0
    override fun getQueueBytesOut(): Long = 0
    override fun resizeCache(newSize: Int) {}
    override fun clearCache() {}
    override fun hasCongestion(percentUsed: Float): Boolean = false

    override fun getBitrateExponentialFactor(): Float = 1f
    override fun setBitrateExponentialFactor(factor: Float) {}

    // No endpoint, no auth, no reconnect: an NDI sender is discovered on the
    // LAN rather than connected to.
    override fun setAuthorization(user: String?, password: String?) {}
    override fun reTry(delay: Long, reason: String, backupUrl: String?): Boolean = false
    override fun setReTries(reTries: Int) {}
    override fun setCheckServerAlive(enabled: Boolean) {}
    override fun setSocketTimeout(timeout: Long) {}
    override fun setSocketType(type: SocketType) {}
    override fun setDelay(millis: Long) {}
    override fun setLogs(enabled: Boolean) {}
    override fun setOnlyAudio(onlyAudio: Boolean) {}
    override fun setOnlyVideo(onlyVideo: Boolean) {}
}
