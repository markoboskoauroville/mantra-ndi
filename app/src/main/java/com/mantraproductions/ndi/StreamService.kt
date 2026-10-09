package com.mantraproductions.ndi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * v136: NDI IN THE BACKGROUND. Marko, 9.10.2026: *"If the camera application is in the background, NDI stream is
 * going on."* Android lets an app keep its camera and microphone open behind other apps (and with the screen off)
 * only from a foreground service of type camera | microphone, started while the app is in front, with a
 * notification that says so. This is that service: it holds nothing but the notification and a partial wake lock
 * (the CPU must not sleep under a stream with the screen off). The camera, the encoders and the sender stay where
 * they are, in the pipeline the activity keeps running; it runs exactly while NDI is streaming.
 */
class StreamService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Streaming NDI"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "NDI stream", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Mantra Manual Camera")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        try {
            ServiceCompat.startForeground(this, ID, n, type)
        } catch (t: Throwable) {
            Trace.fault("stream service", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mantra:ndi-stream").apply { setReferenceCounted(false); acquire() }
        }
        running = true
        Trace.state("stream service: foreground ($text)")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        wake = null
        running = false
        Trace.state("stream service: stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ndi-stream"
        private const val ID = 136
        private const val EXTRA_TEXT = "text"

        @Volatile var running = false
            private set

        /** From the activity while it is in front (Android refuses a camera service started from the background). */
        fun start(context: Context, text: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java).putExtra(EXTRA_TEXT, text))
            }.onFailure { Trace.fault("stream service start", it) }
        }

        fun stop(context: Context) {
            if (!running) return
            runCatching { context.stopService(Intent(context, StreamService::class.java)) }
        }
    }
}
