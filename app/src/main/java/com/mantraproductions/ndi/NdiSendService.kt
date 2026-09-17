package com.mantraproductions.ndi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.video.Camera2Source

/**
 * Owns the NDI stream so it survives MainActivity going away (screen off,
 * app backgrounded). Bind to it to start/stop and to attach a preview.
 */
class NdiSendService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): NdiSendService = this@NdiSendService
    }

    private val binder = LocalBinder()

    private var stream: NdiStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    var controls: ProControls? = null
        private set

    var isStreaming: Boolean = false
        private set

    // See README: the Advanced SDK trial caps a sender at 30 minutes without a
    // vendor ID, so we cycle the sender before that hits.
    private var reconnectThread: HandlerThread? = null
    private var reconnectHandler: Handler? = null
    private var currentSourceName: String? = null
    private val reconnectRunnable = Runnable { performScheduledReconnect() }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Prepare the pipeline without streaming, so preview works on its own. */
    fun prepare(profile: CaptureProfile, onError: (String) -> Unit): Boolean {
        if (stream != null) return true

        val ndiStream = NdiStream(applicationContext)
        val prepared = try {
            ndiStream.setVideoCodec(if (profile.useHevc) VideoCodec.H265 else VideoCodec.H264)
            ndiStream.prepareVideo(
                width = profile.width,
                height = profile.height,
                bitrate = profile.bitRate,
                fps = profile.fps,
                iFrameInterval = 2
            ) && ndiStream.prepareAudio(sampleRate = 48000, isStereo = false, bitrate = 128_000)
        } catch (e: IllegalArgumentException) {
            false
        }

        if (!prepared) {
            onError("This device can't encode ${profile.width}x${profile.height} @ ${profile.fps}")
            ndiStream.release()
            return false
        }

        // Keep the encoder pinned to the profile's fps rather than adapting.
        ndiStream.forceFpsLimit(true)
        // The NDI frame header carries resolution and rate on every frame.
        ndiStream.setVideoFormat(profile.width, profile.height, profile.fps)

        stream = ndiStream
        val source = ndiStream.videoSource as? Camera2Source
        if (source != null) {
            controls = ProControls(
                source,
                applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            ).also { it.apply(profile) }
        }
        return true
    }

    fun attachPreview(surfaceView: SurfaceView) {
        val s = stream ?: return
        if (!s.isOnPreview) s.startPreview(surfaceView)
    }

    fun detachPreview() {
        stream?.let { if (it.isOnPreview) it.stopPreview() }
    }

    fun startStreaming(sourceName: String, onError: (String) -> Unit) {
        val s = stream ?: run {
            onError("Pipeline not prepared")
            return
        }
        if (isStreaming) return

        startForeground(NOTIFICATION_ID, buildNotification(sourceName))

        // NDI discovery is mDNS over UDP multicast; Android drops multicast
        // packets on Wi-Fi unless something holds this lock.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("ndi-camera-mcast").apply {
            setReferenceCounted(true)
            acquire()
        }

        if (!NdiSender.available) {
            onError("Built without the NDI SDK — camera works, sending doesn't. See README.")
            releaseMulticastLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        try {
            s.startStream(sourceName)
        } catch (e: Exception) {
            onError(e.message ?: "Failed to start NDI sender")
            releaseMulticastLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        currentSourceName = sourceName
        startReconnectScheduler()
        isStreaming = true
    }

    fun stopStreaming() {
        stopReconnectScheduler()
        currentSourceName = null

        stream?.let { if (it.isStreaming) it.stopStream() }
        releaseMulticastLock()

        isStreaming = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Tear down the pipeline but keep the service alive (profile switches). */
    fun releasePipeline() {
        stopStreaming()
        controls?.observeSensorValues(null)
        controls = null
        stream?.release()
        stream = null
    }

    fun releaseAll() {
        stopStreaming()
        controls?.observeSensorValues(null)
        controls = null
        stream?.release()
        stream = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReconnectScheduler()
        stream?.release()
        stream = null
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun startReconnectScheduler() {
        val thread = HandlerThread("ndi-reconnect").also { it.start() }
        reconnectThread = thread
        val handler = Handler(thread.looper)
        reconnectHandler = handler
        handler.postDelayed(reconnectRunnable, RECONNECT_INTERVAL_MS)
    }

    private fun stopReconnectScheduler() {
        reconnectHandler?.removeCallbacks(reconnectRunnable)
        reconnectHandler = null
        reconnectThread?.quitSafely()
        reconnectThread = null
    }

    private fun performScheduledReconnect() {
        val sourceName = currentSourceName
        if (!isStreaming || sourceName == null) return

        // NdiSender.create() swaps in a fresh sender under a native lock, so
        // this is safe while the encoder threads are pushing frames. The
        // encoders keep running; only the NDI side blips.
        val ok = NdiSender.create(sourceName)
        Log.i(TAG, "Scheduled NDI reconnect ${if (ok) "succeeded" else "FAILED"}")
        // Force a keyframe so receivers can lock on again immediately.
        stream?.requestKeyframe()

        reconnectHandler?.postDelayed(reconnectRunnable, RECONNECT_INTERVAL_MS)
    }

    private fun buildNotification(sourceName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "NDI streaming", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NDI Camera")
            .setContentText("Sending as \"$sourceName\"")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NdiSendService"
        private const val CHANNEL_ID = "ndi_send"
        private const val NOTIFICATION_ID = 1

        // Comfortably under the documented 30-minute trial cap. Pointless if
        // you ever register a vendor ID — see README.
        private const val RECONNECT_INTERVAL_MS = 25L * 60L * 1000L
    }
}
