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
import android.os.Environment
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.TextureView
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

    /**
     * The direct pipeline, used when ten bit is asked for.
     *
     * Kept alongside the proven one rather than replacing it. Ten bit needs the
     * camera to write straight into the encoder, and that is a different
     * pipeline with different failure modes on every phone model; making it
     * opt in means a bad surprise costs a setting rather than the whole app.
     */
    private var hdr: HdrPipeline? = null

    val isDirectPipeline: Boolean get() = hdr?.isRunning == true
    val isTenBitActive: Boolean get() = hdr?.isTenBit == true

    var isStreaming: Boolean = false
        private set

    private var activeProfile: CaptureProfile? = null

    // See README: the Advanced SDK trial caps a sender at 30 minutes without a
    // vendor ID, so we cycle the sender before that hits.
    private var reconnectThread: HandlerThread? = null
    private var reconnectHandler: Handler? = null
    private var currentSourceName: String? = null
    private val reconnectRunnable = Runnable { performScheduledReconnect() }

    // Remote control: a Monitor on another phone sends commands as NDI
    // metadata over the connection that already carries the video.
    private var commandThread: Thread? = null
    @Volatile private var commandsRunning = false

    var isRecording: Boolean = false
        private set
    private var recordStartedAt: Long = 0L

    /** Seconds since recording started, for the counter in the record button. */
    val recordingElapsedSeconds: Long
        get() = recordingElapsedMillis / 1000

    /**
     * How long the current take has run, to the millisecond, and holding its
     * final length after the take ends.
     *
     * Held rather than zeroed because the first thing anybody does when a take
     * stops is look at how long it was, and a counter that snaps back to zero
     * the instant you stop is a counter that answers that question never.
     */
    val recordingElapsedMillis: Long
        get() = when {
            isRecording -> System.currentTimeMillis() - recordStartedAt
            lastTakeMillis > 0 -> lastTakeMillis
            else -> 0L
        }

    @Volatile private var lastTakeMillis = 0L

    /** Live mic level, 0..1 linear RMS, tapped on the way to the encoder. */
    /** What arrives from the microphone, before the gain fader. */
    @Volatile var audioLevelIn: Float = 0f
        private set

    /** What leaves for the encoder, after it. */
    @Volatile var audioLevelOut: Float = 0f
        private set

    /** Kept for anything that only wants one number; it is the output. */
    val audioLevel: Float get() = audioLevelOut

    /** The encoder timestamp of the last frame sent, for anchoring timecode. */
    val lastVideoPtsUs: Long get() = stream?.lastVideoPtsUs ?: 0L

    /**
     * The meter runs whenever the encoder is not holding the microphone, so
     * levels are visible before a take rather than only during one. Only one
     * thing may own the mic, so the two take turns.
     */
    private val standaloneMeter = AudioMeter { level, isInput ->
        if (isInput) audioLevelIn = level else audioLevelOut = level
    }

    private val vuTap = VuTap { level, isInput ->
        if (isInput) audioLevelIn = level else audioLevelOut = level
    }

    /** Digital gain on the recorded audio, 1.0 being untouched. */
    /**
     * The chroma planes of the most recent frame, for the vectorscope.
     * Only the direct pipeline can supply these; the GL path never exposes a
     * frame the app can read.
     */
    fun latestChroma(): Pair<ByteArray, ByteArray>? = hdr?.latestChroma()

    fun setAudioGain(factor: Float) {
        vuTap.gain = factor
        standaloneMeter.gain = factor
        hdr?.audioGain = factor
    }

    /** Tally from the receiving mixer: bit 0 program, bit 1 preview, -1 none. */
    @Volatile var tally: Int = -1
        private set

    private var tallyThread: Thread? = null
    @Volatile private var tallyRunning = false
    var lastRecordingPath: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    /** Prepare the pipeline without streaming, so preview works on its own. */
    fun prepare(profile: CaptureProfile, onError: (String) -> Unit): Boolean {
        // Ten bit takes the direct route; everything else stays on the pipeline
        // that is already known to work on this phone.
        // Ten bit only where the phone actually has it. A setting left on from
        // another device, or turned on before the detection ran, must not send
        // an eight bit phone down a pipeline it cannot complete.
        val settings = AppSettings(applicationContext)
        if (settings.tenBitWanted && !DeviceProfile.tenBitCapable) {
            settings.tenBitWanted = false
            onError("This camera is 8-bit, staying on the standard pipeline")
        }
        if (settings.tenBitWanted && DeviceProfile.tenBitCapable) {
            return prepareDirect(profile, onError)
        }
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
        activeProfile = profile
        (ndiStream.audioSource as? com.pedro.encoder.input.sources.audio.MicrophoneSource)
            ?.setAudioEffect(vuTap)
        val source = ndiStream.videoSource as? Camera2Source
        if (source != null) {
            controls = ProControls(
                source,
                applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            ).also { controls ->
                controls.apply(profile)
                // The curve the operator chose, applied to the sensor itself so
                // it reaches preview, stream and recording at once.
                if (DeviceProfile.logCapable) {
                    controls.setLogCurve(AppSettings(applicationContext).logCurve)
                }
            }
        }
        return true
    }

    /**
     * Camera straight into the encoder, no GL stage, so a ten bit dynamic
     * range profile is legal on the session.
     */
    private fun prepareDirect(profile: CaptureProfile, onError: (String) -> Unit): Boolean {
        if (hdr?.isRunning == true) return true
        activeProfile = profile
        val pipeline = HdrPipeline(applicationContext)

        // The network's own quality, if one was asked for. Zero leaves the
        // stream sharing the recording encoder, which is what happened before
        // there was a choice.
        val settings = AppSettings(applicationContext)
        pipeline.cameraId = settings.selectedCameraId
        pipeline.streamBitRate = settings.streamMbps * 1_000_000
        if (settings.streamHalfSize && settings.streamMbps > 0) {
            // Halved on both axes, so a quarter of the pixels. Any other
            // fraction risks a size the encoder will not take.
            pipeline.streamWidth = profile.width / 2
            pipeline.streamHeight = profile.height / 2
        }

        pipeline.listener = object : HdrPipeline.Listener {
            override fun onReady(tenBit: Boolean, codec: String) {
                Log.i(TAG, "Direct pipeline: ${if (tenBit) "10-bit HLG" else "8-bit"} $codec")
            }

            override fun onError(message: String) = onError(message)

            override fun onLevel(rms: Float) { audioLevelOut = rms }
        }
        hdr = pipeline
        return true
    }

    fun attachPreview(textureView: TextureView) {
        hdr?.let { pipeline ->
            if (!pipeline.isRunning) {
                val profile = activeProfile ?: return
                val settings = AppSettings(applicationContext)
                val texture = textureView.surfaceTexture ?: return
                texture.setDefaultBufferSize(profile.width, profile.height)
                pipeline.start(
                    profile = profile,
                    sourceName = SourceIdentity(applicationContext).name,
                    previewSurface = Surface(texture),
                    wantTenBit = settings.tenBitWanted,
                    logCurve = settings.logCurve
                )
            }
            return
        }
        val s = stream ?: return
        if (!s.isOnPreview) s.startPreview(textureView)
    }

    fun detachPreview() {
        stream?.let { if (it.isOnPreview) it.stopPreview() }
    }

    fun startStreaming(sourceName: String, onError: (String) -> Unit) {
        if (isStreaming) return
        // The direct pipeline is already sending the moment it opens, because
        // the encoder is the camera's target rather than something downstream
        // of it. Going live is then only the foreground service and the tally.
        if (hdr?.isRunning == true) {
            startForeground(NOTIFICATION_ID, buildNotification(sourceName))
            currentSourceName = sourceName
            startReconnectScheduler()
            startCommandListener()
            startTallyListener()
            isStreaming = true
            return
        }
        val s = stream ?: run {
            onError("Pipeline not prepared")
            return
        }

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
        // The encoder takes the microphone now, so the standalone meter steps
        // aside; VuTap keeps the level coming from the audio actually encoded.
        standaloneMeter.stop()
        startReconnectScheduler()
        startCommandListener()
        startTallyListener()
        isStreaming = true
    }

    fun stopStreaming() {
        stopTallyListener()
        stopCommandListener()
        if (isRecording) stopRecording()
        stopReconnectScheduler()
        currentSourceName = null

        stream?.let { if (it.isStreaming) it.stopStream() }
        releaseMulticastLock()

        isStreaming = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Microphone is free again, so the meter goes back to reading it.
        startMeteringIfIdle()
    }

    /** Called by the activity on resume, and internally whenever a stream ends. */
    fun startMeteringIfIdle() {
        if (!isStreaming && !standaloneMeter.isRunning) standaloneMeter.start()
    }

    fun stopMetering() {
        standaloneMeter.stop()
    }

    /** Tear down the pipeline but keep the service alive (profile switches). */
    fun releasePipeline() {
        hdr?.stop()
        hdr = null
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
        standaloneMeter.stop()
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

    private fun startTallyListener() {
        tallyRunning = true
        tallyThread = kotlin.concurrent.thread(name = "ndi-tally") {
            while (tallyRunning) {
                // Blocks until tally changes or the timeout expires, so this
                // costs nothing while nothing is happening.
                tally = NdiSender.getTally(timeoutMs = 1000)
            }
        }
    }

    private fun stopTallyListener() {
        tallyRunning = false
        tallyThread?.join(1500)
        tallyThread = null
        tally = -1
    }

    private fun startCommandListener() {
        commandsRunning = true
        commandThread = kotlin.concurrent.thread(name = "ndi-commands") {
            while (commandsRunning) {
                val xml = NdiSender.captureMetadata(timeoutMs = 500) ?: continue
                val command = CameraCommand.parse(xml) ?: continue
                try {
                    applyCommand(command)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply remote command", e)
                }
            }
        }
    }

    private fun stopCommandListener() {
        commandsRunning = false
        commandThread?.join(1500)
        commandThread = null
    }

    private fun applyCommand(command: CameraCommand) {
        val ctrl = controls

        command.exposureMode?.let { mode ->
            if (mode == "auto") ctrl?.setAutoExposure()
        }
        // ISO and shutter only mean anything together, so apply them as a pair
        // using whatever the command didn't specify from the current profile.
        if (command.iso != null || command.shutterNs != null) {
            val profile = activeProfile
            val iso = command.iso ?: profile?.isoValue ?: 400
            val shutter = command.shutterNs ?: profile?.shutter180Ns() ?: 20_000_000L
            ctrl?.setManualExposure(iso, shutter)
        }
        command.whiteBalanceLock?.let { if (it) ctrl?.lockWhiteBalance() else ctrl?.unlockWhiteBalance() }
        command.stabilization?.let { ctrl?.setStabilization(it) }
        command.zoom?.let { ctrl?.setZoom(it) }
        command.whiteBalanceKelvin?.let { ctrl?.setManualWhiteBalance(it) }
        command.whiteBalanceAuto?.let { if (it) ctrl?.setAutoWhiteBalance() }

        // Focus by distance, by point, or handed back to the camera.
        command.focus?.let { ctrl?.setFocusFraction(it) }
        if (command.focusX != null && command.focusY != null) {
            ctrl?.focusAtNormalisedPoint(command.focusX, command.focusY) { focused ->
                Log.i(TAG, "Remote focus " + if (focused) "locked" else "failed")
                reportState()
            }
        }
        command.focusAuto?.let { if (it) ctrl?.setAutoFocus() }

        // A far camera should record flat too, or the two will not cut together.
        command.logCurve?.let { name ->
            LogCurves.Curve.values().firstOrNull { it.name == name }?.let { curve ->
                ctrl?.setLogCurve(curve)
            }
        }

        when (command.record) {
            "start" -> startRecording()
            "stop" -> stopRecording()
        }

        if (command.requestState || command.record != null) reportState()
    }

    /**
     * Records locally on the camera phone rather than at the monitor, so the
     * file is the full-quality encoder output with nothing lost to the network.
     * RootEncoder muxes the same encoded frames it is already streaming, so
     * this costs almost nothing on top.
     */
    fun startRecording(): Boolean {
        val s = stream ?: return false
        if (isRecording) return true
        // DCIM/Mantra NDI, so the footage lands in the gallery beside
        // everything else the phone shot rather than inside the app where an
        // uninstall would take it along.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        // Named after the camera, because on a multi camera shoot the filename
        // is the only thing that survives the card reader.
        val name = Mechanism.recordingFileName(
            currentSourceName ?: SourceIdentity(applicationContext).name, stamp
        )
        val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            MediaStoreOutput.FOLDER
        )
        if (!dir.exists()) dir.mkdirs()
        val path = java.io.File(dir, name).absolutePath
        hdr?.let { pipeline ->
            return if (pipeline.startRecording(path)) {
                lastRecordingPath = path
                recordStartedAt = System.currentTimeMillis()
                isRecording = true
                true
            } else false
        }
        return try {
            s.startRecord(path) { status -> Log.i(TAG, "Record status: " + status) }
            lastRecordingPath = path
            recordStartedAt = System.currentTimeMillis()
            isRecording = true
            Log.i(TAG, "Recording to " + path)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            false
        }
    }

    fun stopRecording() {
        if (isRecording) lastTakeMillis = System.currentTimeMillis() - recordStartedAt
        if (!isRecording) return
        hdr?.let {
            it.stopRecording()
            isRecording = false
            return
        }
        try {
            stream?.stopRecord()
        } catch (e: Exception) {
            Log.w(TAG, "Record stop failed", e)
        }
        isRecording = false
    }

    /** Tells the monitor what this camera can actually do and where it is now. */
    private fun reportState() {
        val ctrl = controls ?: return
        val isoRange = ctrl.isoRange()
        val shutterRange = ctrl.exposureTimeRange()
        val state = CameraState(
            isoMin = isoRange?.lower ?: 0,
            isoMax = isoRange?.upper ?: 0,
            shutterMinNs = shutterRange?.lower ?: 0,
            shutterMaxNs = shutterRange?.upper ?: 0,
            recording = isRecording,
            manualSupported = ctrl.supportsManualSensor(),
            whiteBalanceSupported = ctrl.supportsManualWhiteBalance(),
            whiteBalanceKelvin = activeProfile?.whiteBalanceKelvin,
            cameraName = currentSourceName ?: SourceIdentity(applicationContext).name
        )
        // Metadata added to the connection reaches every attached receiver.
        NdiSender.addConnectionMetadata(state.toXml())
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
