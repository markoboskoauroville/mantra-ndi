package com.mantraproductions.ndi

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.mantraproductions.ndi.databinding.ActivityMainBinding
import kotlin.concurrent.thread

/**
 * Camera mode.
 *
 * The image owns the screen. Two rings and a gear sit at the bottom, and when
 * a fader is wanted the rings step aside for a single row carrying one
 * parameter at a time. Four faders stacked over the frame was the thing that
 * made adjusting hard, so there is now never more than one.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileStore: ProfileStore
    private lateinit var identity: SourceIdentity
    private lateinit var appSettings: AppSettings

    private var service: NdiSendService? = null
    private var bound = false
    private var surfaceReady = false

    private var activeProfile: CaptureProfile? = null
    private var param: Mechanism.Param = Mechanism.Param.ISO

    // Fader positions, one per parameter, so cycling never loses a setting.
    private var isoProgress = 0
    private var shutterProgress = 100
    private var kelvinProgress = 5600 - ProControls.KELVIN_MIN
    private var zoomProgress = 0
    private var focusProgress = 0
    private var manualExposure = false
    private var manualWhiteBalance = false
    private var manualFocus = false
    private val focusDirector by lazy {
        FocusDirector(
            controls = { service?.controls },
            onState = { state -> runOnUiThread { binding.focusSquare.state = state } }
        )
    }
    private var gainProgress = 50

    /** The gains a drag started from, held for the length of that drag. */
    private var balanceBase: FloatArray? = null
    private var lastAppliedMode: AppMode? = null
    private var lastAppliedTenBit: Boolean? = null

    /** Timecode over audio: this phone either makes it, follows it, or neither. */
    private val ltcEngine = LtcEngine()



    /** The generator in the room, if there is one. */
    private val timecode by lazy { TimecodeSource(applicationContext) }

    /** Applied together, since one tone curve carries all three. */
    private var gradeDirty = false

    private val pump = ControlPump()

    /**
     * Where the controls land. Local drives this phone's sensor; remote sends
     * the same intentions to a camera on the network. Nothing else in this
     * screen knows the difference.
     */
    private var link: CameraLink? = null

    /**
     * The camera being driven, worked out now rather than remembered.
     *
     * This is why the manual controls kept not working. The link was cached
     * when the mode was applied, and in local mode that happens before the
     * pipeline exists, so `service.controls` was null and no link was ever
     * built. Every fader then returned early and moved nothing. It worked
     * whenever the ordering happened to come out the other way, which is worse
     * than never working, because it looks like a flaky camera rather than a
     * bug.
     *
     * Asked for on each use, so it cannot be stale.
     */
    private fun activeLink(): CameraLink? {
        val remote = link as? RemoteLink
        if (remote != null && appSettings.appMode == AppMode.REMOTE) return remote

        // Eight bit runs through RootEncoder and has a ProControls. Ten bit
        // runs straight into MediaCodec and has a CaptureEngine instead, and
        // asking only for the first is why every fader was inert in ten bit.
        service?.controls?.let { controls ->
            val cached = link
            if (cached is LocalLink && cached.controls === controls) return cached
            return LocalLink(controls).also { link = it }
        }
        service?.engineControls?.let { engine ->
            val cached = link
            if (cached is EngineLink && cached.engine === engine) return cached
            return EngineLink(engine).also { link = it }
        }
        return null
    }

    private var remoteEngine: MonitorEngine? = null
    private val ui = Handler(Looper.getMainLooper())
    private val clearStatus = Runnable { binding.statusText.text = defaultStatus() }
    private val tick = object : Runnable {
        override fun run() {
            try {
                refreshLiveIndicators()
                refreshSignal()
                pushTimecodeToNdi()
                refreshTimecode()
                refreshVectorscope()
                refreshWaveform()
            } catch (e: Throwable) {
                android.util.Log.w("MainActivity", "indicator refresh", e)
            }
            ui.postDelayed(this, 100)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as NdiSendService.LocalBinder).getService()
            bound = true
            preparePipeline()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) bindToService()
        else binding.statusText.text = "Camera and microphone access are required"
    }

    /**
     * Transient messages go away on their own. A camera that still says
     * "recording saved" four minutes later is a camera lying about its state,
     * and the operator stops reading the line at all.
     */
    private fun say(message: String, transient: Boolean = true) {
        binding.statusText.text = message.uppercase()
        ui.removeCallbacks(clearStatus)
        if (transient) ui.postDelayed(clearStatus, 3000)
    }

    private fun defaultStatus(): String {
        val svc = service
        return when {
            svc?.isRecording == true -> "RECORDING"
            svc?.isStreaming == true -> "LIVE AS ${identity.name.uppercase()}"
            else -> "IDLE"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpFullScreen()

        identity = SourceIdentity(this)
        appSettings = AppSettings(this)
        profileStore = ProfileStore(this)
        activeProfile = profileStore.selected()
        param = appSettings.lastParam

        // Each block is independent, and one refusing must not take the screen
        // with it. A camera app that will not open is worth less than a camera
        // app with one control missing.
        safely("control bar") { setUpControlBar() }
        safely("panel") { setUpVerticalPanel() }
        safely("vectorscope") { setUpVectorscope() }
        safely("actions") { setUpActions() }

        binding.preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                // A new texture, so the pipeline has to be built against it.
                // Attaching to a session that was configured for the old one
                // is how the picture came back frozen.
                CrashLog.trace("surface available " + w + "x" + h)
                surfaceReady = true
                activeProfile?.let { service?.prepare(it) { e -> say(e) } }
                service?.attachPreview(binding.preview)
                applyPreviewTransform()
            }

            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {
                // Rotating the phone resizes the view and not the buffer, so
                // without this the picture is stretched from here on.
                applyPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                // The session's output surface dies with this texture. Leaving
                // the pipeline running means it keeps writing to a surface
                // that no longer exists, and on return the new texture gets
                // nothing: a picture frozen on the last frame before the app
                // went away.
                CrashLog.trace("surface destroyed, releasing pipeline")
                surfaceReady = false
                runCatching { service?.releasePipeline() }
                surfaceReady = false
                service?.detachPreview()
                return true
            }

            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
    }

    /**
     * The stack stays on the right where the hand is; only the glyphs turn, so
     * a record ring never appears upside down while the layout stays put.
     */
    private val orientationWatcher by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val quarter = ((degrees + 45) / 90) % 4
                val rotation = when (quarter) {
                    1 -> -90f
                    2 -> 180f
                    3 -> 90f
                    else -> 0f
                }
                // The layout is fixed landscape, so the glyphs stay fixed too.
                // A hamburger turned on its side in a interface that did not
                // turn just reads as broken.
                if (iconRotation == rotation) return
                iconRotation = rotation
            }
        }
    }
    private var iconRotation = 0f

    /**
     * Keys forwarded by the accessibility service, which sees them before the
     * system does. The same handlers as the in-app keys, so a rocker press
     * behaves identically whichever route it took.
     */
    private val keyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(KeyService.EXTRA_ACTION)) {
                KeyService.ACTION_UP -> handleRocker(+1, intent.getIntExtra(KeyService.EXTRA_REPEAT, 0))
                KeyService.ACTION_DOWN -> handleRocker(-1, intent.getIntExtra(KeyService.EXTRA_REPEAT, 0))
                KeyService.ACTION_SHUTTER -> toggleRecording()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startLtc()
        startTimecode()
        KeyService.cameraInForeground = true
        ContextCompat.registerReceiver(
            this, keyReceiver, IntentFilter(KeyService.BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (orientationWatcher.canDetectOrientation()) orientationWatcher.enable()
        if (appSettings.timecodeEnabled) startTimecode()
        pump.start()
        requestPermissionsThenBind()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        CrashLog.trace("onResume")
        binding.focusSquare.boxSize = appSettings.focusBoxSize
        // A mode change in settings takes effect here, including handing the
        // preview from the camera to the decoder or back.
        // The bit depth decides the whole capture session: a ten bit pipeline
        // is a different encoder, a different colour space and different
        // OutputConfigurations. Changing it without rebuilding left the old
        // session running against the new settings, which is the frozen
        // picture, and the stretch afterwards was the transform still sized
        // for the session that had gone.
        val depthNow = appSettings.tenBitWanted
        if (appSettings.appMode != lastAppliedMode || depthNow != lastAppliedTenBit) {
            lastAppliedTenBit = depthNow
            // Modes are separate programs that happen to share a screen. Half
            // of one left running underneath the other is what produced a
            // local camera showing black: the decoder still held the surface
            // and the sensor could not have it. So everything goes, and the
            // new mode starts from nothing.
            CrashLog.trace("mode reset")
            resetForModeChange()
            CrashLog.trace("preparePipeline")
            preparePipeline()
        } else {
            CrashLog.trace("applyCameraSource")
            applyCameraSource()

            // Coming back from the background with the same surface produced
            // no events at all and a picture frozen on the last frame, because
            // the camera session had been taken away while the app was away
            // and nothing asked for it back. A session that is gone cannot be
            // detected reliably, so it is simply rebuilt: it costs a few
            // hundred milliseconds on a screen nobody is shooting with yet.
            if (appSettings.appMode == AppMode.LOCAL && surfaceReady) {
                CrashLog.trace("rebuilding session on resume")
                runCatching { service?.releasePipeline() }
                activeProfile?.let { p ->
                    service?.prepare(p) { e -> say(e) }
                    service?.attachPreview(binding.preview)
                }
            }
        }
        CrashLog.trace("log curve")
        applyLogCurve()
        CrashLog.trace("screen policy")
        applyScreenPolicy()

        // The overlay work is the newest part of this screen and the part most
        // likely to fail on a phone I cannot test on. A camera that will not
        // open because a waveform shader was rejected is a far worse failure
        // than a camera with no waveform, so this cannot take the app down.
        try {
            CrashLog.trace("refreshRail")
            refreshRail()
            CrashLog.trace("previewEffects")
            applyPreviewEffects()
            CrashLog.trace("lutButton")
            refreshLutButton()
            CrashLog.trace("overlays ok")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "overlays", e)
            appSettings.previewLut = false
            appSettings.focusPeaking = false
            appSettings.waveformChannels = emptySet()
            runCatching { binding.preview.setRenderEffect(null) }
            say("Overlays turned off after an error")
        }
        // Coming back from the background resizes the view without touching
        // the buffer, which is the other half of the stretch.
        // Three times, spread out: the buffer size changes when the session
        // starts, not when it is asked for, and one post lands too early on a
        // cold camera.
        listOf(0L, 600L, 1500L).forEach { delay ->
            binding.preview.postDelayed({ applyPreviewTransform() }, delay)
        }
        offerBatteryExemptionOnce()
        // Settings may have changed the profile while we were away.
        val picked = profileStore.selected()
        if (picked.name != activeProfile?.name) {
            activeProfile = picked
            service?.let { if (!it.isStreaming) { it.releasePipeline(); preparePipeline() } }
        }
    }

    override fun onStop() {
        super.onStop()
        ltcEngine.stop()
        remoteEngine?.stop()
        remoteEngine = null
        KeyService.cameraInForeground = false
        try {
            unregisterReceiver(keyReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered, which happens if onStart bailed early.
        }
        focusDirector.stop()
        timecode.stop()
        orientationWatcher.disable()
        pump.stop()
        ui.removeCallbacks(tick)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    /**
     * The volume rocker, given a job worth having.
     *
     * An editor works by keyboard and a camera operator works without looking,
     * so the two hardware keys this app is allowed to take are worth more than
     * another thing to tap. What they do depends on what is on screen, which
     * is the same rule a mixer follows: the keys act on whatever is in front
     * of you.
     *
     * Nothing here touches the power button. Android does not let an ordinary
     * app consume KEYCODE_POWER; the system takes it for the screen and the
     * power menu before any app sees it. There is no permission for it and no
     * flag that changes it, so the record shortcut lives on a long press of
     * the volume rocker instead, which is the closest key the platform allows.
     */
    private var rockerRunnable: Runnable? = null

    /**
     * Repeats on our own timer rather than on Android's.
     *
     * The repeat used to wait for the platform's key repeat count to pass
     * three, and the platform does not always send repeats at all, so on some
     * phones holding the rocker did exactly one step and then nothing. Worse,
     * count three toggled recording, so a hold that did register started a
     * take instead of moving the value.
     *
     * A third of a second before it starts, so a deliberate single press is
     * still a single step, then nine steps a second, which crosses a stop of
     * exposure in about the time a hand expects it to.
     */
    private fun startRockerRepeat(direction: Int) {
        if (rockerRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                nudgeRocker(direction)
                ui.postDelayed(this, 111)
            }
        }
        rockerRunnable = runnable
        ui.postDelayed(runnable, 340)
    }

    private fun stopRockerRepeat() {
        rockerRunnable?.let { ui.removeCallbacks(it) }
        rockerRunnable = null
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            stopRockerRepeat()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> +1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }

        handleRocker(direction, event.repeatCount)
        return true
    }

    /**
     * The rocker, held as well as pressed.
     *
     * Every repeat past the first moves the value, which is the whole point of
     * a rocker: a stop of exposure is many steps and tapping forty times is
     * not a control. Android's own repeats arrive at the system key rate,
     * which is slower than anybody wants here, so they are only the trigger.
     * The pacing is ours: one step every 110ms once a hold has settled.
     *
     * Three repeats still means record, so a take starts without looking at
     * the screen. That is checked before the hold begins, so a deliberate hold
     * to record does not also run the value away.
     */
    private fun handleRocker(direction: Int, repeatCount: Int) {
        // Only the first press is acted on here; the hold is our own timer.
        if (repeatCount > 0) return
        nudgeRocker(direction)
        startRockerRepeat(direction)
    }

    private fun nudgeRocker(direction: Int) {
        when {
            binding.verticalPanel.visibility == View.VISIBLE -> nudgeFocusedColumn(direction)
            binding.paramBar.visibility == View.VISIBLE -> binding.paramFader.let { fader ->
                fader.progress += direction * maxOf(1, fader.max / 100)
                onFaderMoved(fader.progress)
            }
            // Nothing open: the rocker is a zoom rocker, which is what it is
            // on every camcorder that ever had one.
            else -> {
                zoomProgress = (zoomProgress + direction * 2).coerceIn(0, 100)
                pushZoom(zoomProgress)
                say("Zoom ${binding.vZoom.valueText}")
            }
        }
    }


    /** The rocker drives whichever column was touched last. */
    private var focusedColumn: Mechanism.Param = Mechanism.Param.ISO

    /** The fader last touched, including the two the rocker cannot drive. */
    private var touchedFader: VerticalFaderView? = null

    private fun nudgeFocusedColumn(direction: Int) {
        val fader = when (focusedColumn) {
            Mechanism.Param.ISO -> binding.vIso
            Mechanism.Param.SHUTTER -> binding.vShutter
            Mechanism.Param.WHITE_BALANCE -> binding.vWhiteBalance
            Mechanism.Param.ZOOM -> binding.vZoom
            Mechanism.Param.FOCUS -> binding.vFocus
            Mechanism.Param.GAIN -> binding.vGain
        }
        fader.step(direction)
    }

    /**
     * Live on the network, which is a different decision from recording and
     * now has its own button rather than a hidden long press.
     *
     * The two are independent on purpose. A camera can be live and not
     * recording, which is most of a broadcast, or recording and not live,
     * which is most of a shoot, and neither should imply the other.
     */
    private fun toggleStreaming() {
        val svc = service ?: return
        if (link?.isRemote == true) {
            say("Remote mode: the far camera streams, not this one")
            return
        }
        if (svc.isStreaming) {
            svc.stopStreaming()
            say("Off air", transient = false)
        } else {
            val name = SourceIdentity.sanitize(identity.name)
            warnIfNameTaken(name)
            startForegroundService(Intent(this, NdiSendService::class.java))
            svc.startStreaming(name) { error -> runOnUiThread { say(error) } }
            say("Live as $name", transient = false)
        }
        refreshStreamButton()
    }

    /**
     * The sources on the network, listed here rather than in settings. In
     * monitor mode this is the only control that matters, so it is the one
     * control that is there.
     */
    private fun pickSourceOnScreen() {
        if (!NdiFinder.available) {
            say("This build has no NDI SDK")
            return
        }
        say("Looking for sources", transient = false)
        thread(name = "source-scan") {
            NdiFinder.start(applicationContext)
            val found = NdiFinder.sources(timeoutMs = 3000)
            NdiFinder.stop()
            runOnUiThread {
                if (found.isEmpty()) {
                    say("Nothing found. Try the network test in settings.")
                    return@runOnUiThread
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Watch")
                    .setItems(found.toTypedArray()) { _, which ->
                        appSettings.remoteSource = found[which]
                        remoteEngine?.stop()
                        remoteEngine = null
                        link = null
                        applyCameraSource()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun startTimecode() {
        // The scan permission is asked for only when timecode is wanted, since
        // a camera that demands Bluetooth before it will open is a camera
        // nobody trusts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            return
        }
        timecode.listener = object : TimecodeSource.Listener {
            override fun onTimecode(tc: Timecode, deviceName: String) = Unit
            override fun onLost() = Unit
            override fun onRawSeen(deviceName: String, hex: String, parsed: String?) {
                TimecodeLog.record(deviceName, hex, parsed)
            }
        }
        // Wrapped because a Bluetooth call can throw for a permission this app
        // does not hold, and no timecode source is worth closing a camera over.
        val started = try {
            timecode.start()
        } catch (e: Throwable) {
            CrashLog.trace("bluetooth refused: " + e.javaClass.simpleName)
            false
        }
        if (!started) say("No Bluetooth timecode; LTC over audio still works")
    }

    /**
     * Ten times a second, which is enough for a display at arm's length and
     * far cheaper than the frame rate it is counting.
     */
    /**
     * Starts whichever half of the timecode job this phone has been given.
     *
     * A master needs no permission beyond audio output. A follower needs the
     * microphone, and asking for it only when timecode is switched on keeps a
     * camera app from demanding a microphone before it will open.
     */
    private fun startLtc() {
        val role = appSettings.ltcRole
        if (role == LtcEngine.Role.OFF) {
            ltcEngine.stop()
            return
        }
        ltcEngine.rate = appSettings.ltcRate
        ltcEngine.start(role)
        ltcEngine.lastError?.let { say(it) }
    }

    /**
     * Hands the clock to the NDI sender, so every frame carries it.
     *
     * Once a second is enough. The stamp is anchored rather than set, so the
     * native side advances it with each frame's own timestamp; calling more
     * often would only re-anchor to the same clock and add jitter from
     * whenever this happened to run.
     */
    /**
     * Whether there is anything on the wire, checked on the tick.
     *
     * A remote picture that stops does not clear itself: the last decoded
     * frame stays on the surface and looks exactly like a working picture of a
     * still scene. So the absence is measured and covered over, and the source
     * name goes with it, because a name over a dead feed reads as a live one.
     */
    private fun refreshSignal() {
        if (appSettings.appMode == AppMode.LOCAL) {
            binding.noSignal.visibility = View.GONE
            return
        }
        val engine = remoteEngine
        val alive = engine != null && engine.hasRecentFrame()
        binding.noSignal.expected = appSettings.remoteSource
        binding.noSignal.visibility = if (alive) View.GONE else View.VISIBLE
        binding.remoteBorder.visibility = if (alive) View.VISIBLE else View.GONE
    }

    private fun pushTimecodeToNdi() {
        if (service?.isStreaming != true) return
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (now - lastTimecodePushAt < 1_000_000_000L) return
        lastTimecodePushAt = now

        val tc = ltcEngine.clock.now(now)
        if (tc == null) {
            NdiSender.setTimecode(0L, 0L)
            return
        }
        NdiSender.setTimecode(Timecode.to100ns(tc), service?.lastVideoPtsUs ?: 0L)
    }

    private var lastTimecodePushAt = 0L

    private val MASTER_GREEN = android.graphics.Color.parseColor("#12C46A")
    private val FOLLOW_WHITE = android.graphics.Color.parseColor("#F2F4F6")
    private val INTERNAL_GREY = android.graphics.Color.parseColor("#8C99A6")

    /**
     * The clock, and what kind of clock it is.
     *
     * Three states, told apart by colour and by three letters, because either
     * one alone fails somebody: a colour is unreadable in sunlight through a
     * loupe and useless to anyone who cannot separate green from white, and a
     * label alone is not readable at a glance mid take.
     *
     *   MST  green      this phone is the master, everything follows it
     *   SYC  white      following a master, so the whole set agrees
     *   INT  outline    free running on its own, agreeing with nobody
     *
     * The outline matters most. Internal is the state that looks exactly like
     * being in sync while not being in sync, and it is worth making it look
     * unfinished on purpose.
     */
    /**
     * The clock, and what kind of clock it is.
     *
     * A colour alone would be ambiguous, so there is a word under it as well.
     * Green and MST is this phone generating the clock everything else follows.
     * White and SYC is following somebody else's. Dim outline and INT is a
     * free running internal count that nothing else knows about, which is
     * useful for slating a single camera and misleading if mistaken for sync.
     *
     * The distinction matters most at exactly the moment it is easiest to get
     * wrong: a follower that has lost its master keeps counting, and without
     * this it looks identical to one still locked.
     */
    /**
     * What kind of clock this is, which is the thing worth knowing at a glance.
     *
     * Three states, and they are genuinely different jobs rather than degrees
     * of the same one:
     *
     *   MST  this phone is the clock everything else follows
     *   SYC  following somebody else's clock, so the numbers agree with theirs
     *   INT  a clock of its own, agreeing with nothing
     *
     * INT is the one that needs saying out loud. An internal clock looks
     * exactly like a synced one, counts just as convincingly, and is worth
     * nothing in an edit. Hollow rather than filled, so it reads as unattached.
     */
    private enum class ClockKind(val label: String, val colour: String) {
        MASTER("MST", "#12C46A"),
        FOLLOWING("SYC", "#FFFFFF"),
        INTERNAL("INT", "#FFFFFF")
    }

    private fun clockKind(): ClockKind = when {
        appSettings.ltcRole == LtcEngine.Role.MASTER -> ClockKind.MASTER
        remoteEngine?.lastTimecode100ns?.let { it > 0L } == true -> ClockKind.FOLLOWING
        appSettings.ltcRole == LtcEngine.Role.FOLLOW && ltcEngine.isLocked -> ClockKind.FOLLOWING
        else -> ClockKind.INTERNAL
    }

    /**
     * The clock, and what kind of clock it is.
     *
     * Colour and three letters say the same thing twice on purpose, because
     * either one alone fails: colour is invisible to somebody who cannot
     * separate green from white, and three small letters are unreadable at
     * arm's length on a gimbal. Together they survive both.
     *
     *   MST, green         this phone is the master and is stamping frames
     *   SYC, white         following a master's clock off the stream
     *   INT, grey outline  a clock of its own, agreeing with nothing
     */
    private fun refreshTimecode() {
        if (!appSettings.showTimecode) {
            binding.timecodeView.visibility = View.GONE
            return
        }
        binding.timecodeView.visibility = View.VISIBLE
        val view = binding.timecodeView
        val nanos = android.os.SystemClock.elapsedRealtimeNanos()
        val rate = appSettings.ltcRate
        val wanted = appSettings.timecodeSource
        val watching = appSettings.remoteSource

        // A stamp on the incoming picture beats anything decoded separately:
        // it arrived attached to the frame, so it cannot have drifted from it.
        // Honoured only from the device that was asked for, or two cameras
        // following two different masters look identical.
        val streamTc = remoteEngine
            ?.takeIf { wanted == null || wanted == watching }
            ?.lastTimecode100ns
            ?.takeIf { it > 0L }
            ?.let { Timecode.from100ns(it, rate) }

        val isMaster = appSettings.ltcRole == LtcEngine.Role.MASTER
        val ltcTc = ltcEngine.clock.now(nanos)

        // Internal is not "no timecode", it is this device's own clock running
        // free. A display that sits still is not a clock, which is why this
        // never falls through to a dash.
        val running = streamTc ?: ltcTc ?: Timecode.timeOfDay(rate)

        view.sizeSp = appSettings.timecodeSize.toFloat()
        view.showBackground = appSettings.timecodePlate
        view.timecode = running.toString()

        // The big number is the take, the small one is the file's timecode.
        val svcNow = service
        view.rolling = svcNow?.isRecording == true
        view.duration = Timecode.fromDuration(
            svcNow?.recordingElapsedMillis ?: 0L, rate
        ).toString()

        view.sync = when {
            isMaster -> TimecodeView.Sync.MASTER
            streamTc != null || ltcEngine.isLocked -> TimecodeView.Sync.EXTERNAL
            else -> TimecodeView.Sync.INTERNAL
        }
        view.sourceName = when {
            streamTc != null -> watching.orEmpty()
            ltcEngine.isLocked && !isMaster -> "LTC AUDIO"
            wanted != null && !isMaster -> wanted + " ?"
            // On internal, this device's own name, because the question the
            // line answers is whose clock this is.
            else -> SourceIdentity.sanitize(identity.name)
        }

        val svc = service
        view.status = when {
            appSettings.appMode == AppMode.MONITOR -> TimecodeView.Status.WATCHING
            svc == null -> TimecodeView.Status.IDLE
            svc.isRecording && svc.isStreaming -> TimecodeView.Status.BOTH
            svc.isRecording -> TimecodeView.Status.RECORDING
            svc.isStreaming -> TimecodeView.Status.STREAMING
            else -> TimecodeView.Status.IDLE
        }

        // What is left and what has been lost. Both fail silently otherwise:
        // the card fills and the file simply stops, or the encoder falls
        // behind and an edit finds the stutter months later.
        val mbps = appSettings.recordMbps
        view.fields = appSettings.timecodeFields
        view.modeLabel = appSettings.appMode.short
        view.modeLabel = when (appSettings.appMode) {
            AppMode.LOCAL -> "LOC"
            AppMode.REMOTE -> "REM"
            AppMode.MONITOR -> "MON"
            AppMode.SYSTEM -> ""
        }
        view.healthLine = RecordingHealth.summary(mbps)
        view.healthLevel = maxOf(
            RecordingHealth.spaceLevel(RecordingHealth.secondsRemaining(mbps)),
            RecordingHealth.dropLevel(RecordingHealth.dropPercent)
        )

        view.formatLine = activeProfile?.let { p ->
            val curve = appSettings.logCurve.displayName
            val depth = if (appSettings.tenBitWanted && DeviceProfile.tenBitCapable) "10-bit"
                else "8-bit"
            curve + "   " + p.width + "x" + p.height + "   " + p.fps + "p   " + depth
        }.orEmpty()

        // Position is a bias rather than two layouts, so switching it cannot
        // leave the other one behind.
        val params = view.layoutParams
                as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val bias = if (appSettings.timecodeAtTop) 0f else 1f
        if (params.verticalBias != bias) {
            params.verticalBias = bias
            view.layoutParams = params
        }
    }

    private fun refreshStreamButton() {
        val streaming = service?.isStreaming == true
        binding.streamButton.ringColor =
            if (streaming) CircleButtonView.STREAMING else CircleButtonView.STREAM_IDLE
        binding.streamButton.glow = streaming
    }

    private fun toggleRecording() {
        val remote = link as? RemoteLink
        if (remote != null) {
            val recording = remote.state?.recording == true
            if (recording) remote.stopRecording() else remote.startRecording()
            say(if (recording) "Asked ${remote.label} to stop" else "Asked ${remote.label} to record")
            return
        }
        val svc = service ?: return
        // A clean frame is the point of recording, so the controls go away.
        if (!svc.isRecording && binding.verticalPanel.visibility == View.VISIBLE) {
            toggleVerticalPanel()
        }
        if (svc.isRecording) {
            svc.stopRecording()
            say("Saved to DCIM/${MediaStoreOutput.FOLDER}")
        } else if (svc.startRecording()) {
            // Written beside the file rather than guessed at later: the start
            // timecode is the one number that makes a clip line up with every
            // other camera, and it is unrecoverable once the take is over.
            timecode.now()?.let { tc ->
                svc.lastRecordingPath?.let { path ->
                    TimecodeLog.writeSidecar(path, tc, timecode.deviceName)
                }
            }
            say("Recording", transient = false)
        } else {
            say("Go live first")
        }
    }

    override fun onBackPressed() {
        when {
            binding.verticalPanel.visibility == View.VISIBLE -> toggleVerticalPanel()
            binding.paramBar.visibility == View.VISIBLE -> closeControlBar()
            else -> super.onBackPressed()
        }
    }

    private fun setUpFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.controlRow.updatePadding(bottom = bars.bottom, right = bars.right)
            binding.paramBar.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            binding.statusText.updatePadding(left = bars.left)
            binding.vuHairline.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    // --- the single fader ---------------------------------------------------

    private fun setUpControlBar() {
        binding.paramCycleButton.ringColor = CircleButtonView.ACTIVE
        binding.paramCloseButton.ringColor = CircleButtonView.IDLE
        binding.paramCloseButton.symbol = "\u00D7"

        binding.paramCycleButton.setOnClickListener {
            param = param.next()
            appSettings.lastParam = param
            showParam()
        }
        binding.paramCloseButton.setOnClickListener { closeControlBar() }

        binding.paramFader.onChange = { onFaderMoved(it) }
        binding.paramFader.onRelease = { pump.flush() }
    }

    private fun openControlBar() {
        binding.controlRow.visibility = View.GONE
        binding.paramBar.visibility = View.VISIBLE
        binding.paramBar.alpha = 0f
        binding.paramBar.animate().alpha(1f).setDuration(140).start()
        // Reaching for a fader means wanting the sensor, not the auto routine.
        // Unconditional. The log showed manual=false on every exposure move
        // with the link, the engine and both ranges all present, because this
        // phone reports supportsManualSensor false and takes the request
        // anyway. The camera is the authority on what it accepts, and a
        // refused request now restores the last good one, so asking and being
        // told no costs nothing while not asking costs the whole control.
        manualExposure = true
        showParam()
    }

    private fun closeControlBar() {
        binding.paramBar.animate().alpha(0f).setDuration(120).withEndAction {
            binding.paramBar.visibility = View.GONE
            binding.controlRow.visibility = View.VISIBLE
        }.start()
    }

    private fun showParam() {
        val controls = service?.controls
        binding.paramCycleButton.centerText = when (param) {
            Mechanism.Param.ISO -> "ISO"
            Mechanism.Param.SHUTTER -> "SH"
            Mechanism.Param.WHITE_BALANCE -> "WB"
            Mechanism.Param.ZOOM -> "Z"
            Mechanism.Param.FOCUS -> "F"
            Mechanism.Param.GAIN -> "G"
        }
        binding.paramFader.label = param.label

        when (param) {
            Mechanism.Param.ISO -> {
                val range = controls?.isoRange()
                binding.paramFader.isEnabled = range != null
                if (range != null) {
                    binding.paramFader.max = (range.upper - range.lower).coerceAtLeast(1)
                    binding.paramFader.progress = isoProgress
                }
            }

            Mechanism.Param.SHUTTER -> {
                binding.paramFader.max = 200
                binding.paramFader.isEnabled = shutterRange() != null
                binding.paramFader.progress = shutterProgress
            }

            Mechanism.Param.WHITE_BALANCE -> {
                binding.paramFader.max = ProControls.KELVIN_MAX - ProControls.KELVIN_MIN
                binding.paramFader.isEnabled = controls?.supportsManualWhiteBalance() == true
                binding.paramFader.progress = kelvinProgress
            }

            Mechanism.Param.ZOOM -> {
                binding.paramFader.max = 100
                binding.paramFader.isEnabled = true
                binding.paramFader.progress = zoomProgress
            }

            Mechanism.Param.FOCUS -> {
                binding.paramFader.max = 100
                binding.paramFader.isEnabled = true
                binding.paramFader.progress = focusProgress
            }

            Mechanism.Param.GAIN -> {
                binding.paramFader.max = 100
                binding.paramFader.isEnabled = true
                binding.paramFader.progress = gainProgress
            }
        }
        updateFaderValue()
    }

    private fun onFaderMoved(progress: Int) {
        when (param) {
            Mechanism.Param.ISO -> { isoProgress = progress; pushExposure() }
            Mechanism.Param.SHUTTER -> { shutterProgress = progress; pushExposure() }
            Mechanism.Param.WHITE_BALANCE -> {
                kelvinProgress = progress
                pump.setKelvin(ProControls.KELVIN_MIN + progress)
            }
            Mechanism.Param.ZOOM -> { zoomProgress = progress; pushZoom(progress) }
            Mechanism.Param.FOCUS -> { focusProgress = progress; manualFocus = true; pushFocus() }
            Mechanism.Param.GAIN -> { gainProgress = progress; applyGain() }
        }
        updateFaderValue()
    }

    private fun updateFaderValue() {
        val controls = service?.controls
        binding.paramFader.valueText = when (param) {
            Mechanism.Param.FOCUS -> if (focusProgress >= 99) "\u221E" else "$focusProgress%"
            Mechanism.Param.GAIN -> binding.vGain.valueText
            Mechanism.Param.ISO ->
                controls?.isoRange()?.let { "${it.lower + isoProgress}" } ?: "--"

            Mechanism.Param.SHUTTER -> shutterRange()?.let { range ->
                Mechanism.formatShutter(
                    Mechanism.shutterFromProgress(shutterProgress, 200, range.first, range.second)
                )
            } ?: "--"

            Mechanism.Param.WHITE_BALANCE -> "${ProControls.KELVIN_MIN + kelvinProgress} K"

            Mechanism.Param.ZOOM -> controls?.zoomRange()?.let { range ->
                String.format("%.1fx", range.lower + (zoomProgress / 100f) * (range.upper - range.lower))
            } ?: "--"
        }
    }

    /** Never longer than one frame interval, whatever the sensor claims. */
    private fun shutterRange(): Pair<Long, Long>? {
        val sensor = activeLink()?.exposureRange() ?: return null
        return Mechanism.shutterRangeForFps(activeProfile?.fps ?: 25, sensor.lower, sensor.upper)
    }

    /**
     * The pump drives whichever camera is running, not whichever one existed
     * when this app was first written.
     *
     * It bound straight to ProControls and gave up if there was none, and in
     * ten bit there never is: that path has a CaptureEngine instead. So the
     * pump had no bindings at all and every coalesced move went into nothing.
     * Bound through the link, it reaches either.
     */
    private fun bindPump() {
        val frameDuration = Mechanism.frameDurationForFps(activeProfile?.fps ?: 25)
        pump.applyExposure = { iso, shutterNs ->
            activeLink()?.setManualExposure(iso, shutterNs, frameDuration)
        }
        pump.applyWhiteBalance = { kelvin -> activeLink()?.setManualWhiteBalance(kelvin) }
        pump.applyZoom = { zoom -> activeLink()?.setZoom(zoom) }
    }

    /**
     * Exposure, to whichever camera is actually being driven.
     *
     * This was the bug behind a remote camera that showed a picture and
     * ignored every fader. CameraLink was built so the panel would not have to
     * know where the sensor is, and then the panel went straight to the local
     * controls anyway, so every move landed on the phone in the operator's
     * hand rather than the one across the room.
     */
    private fun pushExposure() {
        // Everything that can make a fader do nothing, in one line. Each of
        // these has been the cause at least once this week, and from here
        // there is no way to tell which without being told.
        val probe = activeLink()
        CrashLog.trace(
            "pushExposure manual=" + manualExposure +
                " link=" + (probe?.javaClass?.simpleName ?: "NONE") +
                " engine=" + (service?.engineControls != null) +
                " controls=" + (service?.controls != null) +
                " iso=" + (probe?.isoRange() != null) +
                " shutter=" + (shutterRange() != null)
        )

        if (!manualExposure) return
        val active = activeLink() ?: return
        val isoRange = active.isoRange() ?: return
        val range = shutterRange() ?: return
        val iso = (isoRange.lower + isoProgress).coerceIn(isoRange.lower, isoRange.upper)
        val shutter = Mechanism.shutterFromProgress(
            shutterProgress, 200, range.first, range.second
        )

        if (active.isRemote) {
            // Over the wire there is no pump: a command is one small message
            // and coalescing them would only add latency to a slider that is
            // already a round trip away.
            active.setManualExposure(
                iso, shutter, Mechanism.frameDurationForFps(activeProfile?.fps ?: 25)
            )
        } else {
            pump.setExposure(iso, shutter)
        }
    }

    private fun pushZoom(progress: Int) {
        val active = activeLink() ?: return
        val range = active.zoomRange() ?: return
        val ratio = range.lower + (progress / 100f) * (range.upper - range.lower)
        if (active.isRemote) active.setZoom(ratio) else pump.setZoom(ratio)
    }

    private fun bindControlRanges() {
        val controls = service?.controls ?: return
        controls.isoRange()?.let { isoProgress = (it.upper - it.lower) / 4 }
        shutterRange()?.let { range ->
            shutterProgress = Mechanism.progressForShutter(
                Mechanism.shutter180Ns(activeProfile?.fps ?: 25), 200, range.first, range.second
            )
        }
        controls.setStabilization(appSettings.stabilisation)
    }

    // --- every control at once, in columns ---------------------------------

    /**
     * A column is a reset, a track, and two steppers.
     *
     * R rather than A, because the panel is already a row of A's and one more
     * would say nothing. It puts that one control back to what the camera
     * itself decided, which is the only reset worth having on a camera: not a
     * factory default, but this scene, measured now.
     *
     * The steppers sit under the track rather than at its ends. A thumb rests
     * at the bottom of a phone, and asking it to travel to the top of a column
     * for finer control is asking it to leave the frame.
     */
    private fun wireColumn(
        reset: CircleButtonView,
        plus: CircleButtonView,
        minus: CircleButtonView,
        fader: VerticalFaderView,
        onReset: () -> Unit
    ) {
        reset.centerText = "R"
        reset.ringColor = CircleButtonView.IDLE
        reset.setOnClickListener {
            reset.busy = true
            onReset()
            ui.postDelayed({ reset.busy = false }, 900)
        }

        plus.centerText = "+"
        minus.centerText = "\u2212"
        for ((button, direction) in listOf(plus to 1, minus to -1)) {
            button.ringColor = CircleButtonView.ACTIVE
            button.setOnClickListener { fader.step(direction) }
            // Held down it repeats, because a stop of correction is many steps
            // and nobody should tap forty times for it.
            button.setOnLongClickListener {
                repeatStep(button, fader, direction)
                true
            }
        }
    }

    private fun repeatStep(button: CircleButtonView, fader: VerticalFaderView, direction: Int) {
        val runnable = object : Runnable {
            override fun run() {
                if (!button.isPressed) return
                fader.step(direction)
                ui.postDelayed(this, 55)
            }
        }
        ui.post(runnable)
    }

    private fun setUpVerticalPanel() {
        val faders = listOf(
            binding.vIso to Mechanism.Param.ISO,
            binding.vShutter to Mechanism.Param.SHUTTER,
            binding.vWhiteBalance to Mechanism.Param.WHITE_BALANCE,
            binding.vZoom to Mechanism.Param.ZOOM
        )
        faders.forEach { (fader, which) ->
            // Short labels: a column is 62dp and "SHUTTER" is wider than that,
            // which is how the headings ended up overlapping each other.
            fader.label = when (which) {
                Mechanism.Param.ISO -> "ISO"
                Mechanism.Param.SHUTTER -> "SHUT"
                Mechanism.Param.WHITE_BALANCE -> "WB"
                Mechanism.Param.ZOOM -> "ZOOM"
                Mechanism.Param.FOCUS -> "FOCUS"
                Mechanism.Param.GAIN -> "GAIN"
            }
            fader.onChange = { onVerticalMoved(which, it) }
            fader.onRelease = { pump.flush() }
            fader.onTouchedWhileAutomatic = { leaveAuto(which) }
            fader.setOnTouchListener { view, _ ->
                touchedFader = view as? VerticalFaderView
                // Touching a column is also choosing it for the rocker, so the
                // hardware keys follow the hand rather than a separate choice.
                focusedColumn = which
                refreshColumnFocus()
                false
            }
        }

        binding.vGain.label = "GAIN"
        binding.vGain.max = 100
        binding.vGain.showsCentre = true
        binding.vGain.progress = gainProgress
        binding.vGain.onChange = { gainProgress = it; applyGain() }
        binding.vFocus.label = "FOCUS"
        binding.vFocus.max = 100
        binding.vFocus.onChange = { focusProgress = it; pushFocus() }
        binding.vFocus.onRelease = { pump.flush() }
        binding.vFocus.onTouchedWhileAutomatic = { leaveAuto(null) }

        // The A circles are gone from the columns. Snapping a control back to
        // what the camera detected is a two second press on the control
        // itself, which is one less thing on the frame and a bigger target
        // than any button could be.
        listOf(
            binding.vIso to Mechanism.Param.ISO,
            binding.vShutter to Mechanism.Param.SHUTTER,
            binding.vWhiteBalance to Mechanism.Param.WHITE_BALANCE,
            binding.vZoom to Mechanism.Param.ZOOM
        ).forEach { (fader, which) -> fader.onSnapToAuto = { snapToAuto(which) } }
        binding.vFocus.onSnapToAuto = { focusOnSquare() }

        wireColumn(binding.rIso, binding.plusIso, binding.minusIso, binding.vIso) {
            snapToAuto(Mechanism.Param.ISO)
        }
        wireColumn(
            binding.rShutter, binding.plusShutter, binding.minusShutter, binding.vShutter
        ) { snapToAuto(Mechanism.Param.SHUTTER) }
        wireColumn(
            binding.rWhiteBalance, binding.plusWhiteBalance,
            binding.minusWhiteBalance, binding.vWhiteBalance
        ) { snapToAuto(Mechanism.Param.WHITE_BALANCE) }
        wireColumn(binding.rFocus, binding.plusFocus, binding.minusFocus, binding.vFocus) {
            focusOnSquare()
        }
        wireColumn(binding.rZoom, binding.plusZoom, binding.minusZoom, binding.vZoom) {
            snapToAuto(Mechanism.Param.ZOOM)
        }
        wireColumn(binding.rGain, binding.plusGain, binding.minusGain, binding.vGain) {
            gainProgress = 50
            applyGain()
            refreshVerticalPanel()
        }
        binding.vGain.onSnapToAuto = {
            gainProgress = 50
            applyGain()
            refreshVerticalPanel()
            say("Gain back to unity")
        }

        binding.focusSquare.onMoved = { x, y ->
            focusDirector.target = x to y
            focusDirector.bounds = binding.focusSquare.normalisedBounds()
            binding.focusSquare.state = FocusSquareView.State.IDLE
        }
        // A tap on the box means focus here. Dragging moves it, tapping fires
        // it, and a long press cycles its size, so the box is the whole focus
        // interface and the circle only says which mode it is in.
        // Long press the box: ask what is in the frame and focus by name.
        binding.focusSquare.setOnLongClickListener {
            askVisionForSubjects()
            true
        }
        binding.focusSquare.onTapped = {
            // One gesture, one meaning: a tap on the box focuses there.
            focusDirector.focusHereAndHold()
        }

        // One press puts every control back on automatic at once.
        binding.autoAllButton.centerText = "VA"
        binding.autoAllButton.setOnClickListener {
            binding.autoAllButton.busy = true
            returnToAuto()
            ui.postDelayed({ binding.autoAllButton.busy = false }, 950)
        }
    }

    /**
     * The panel and the focus box are never both up.
     *
     * They were fighting: the box covers the image so it can be dragged
     * anywhere, and the columns want the right of the same image, so a touch
     * belonged to whichever happened to be on top. Now a tap swaps them. The
     * box is the resting state, because focus is the thing being judged
     * continuously and exposure is the thing being set occasionally.
     */
    private fun toggleVerticalPanel() {
        val opening = binding.verticalPanel.visibility != View.VISIBLE
        binding.verticalPanel.visibility = if (opening) View.VISIBLE else View.GONE
        binding.autoAllButton.visibility = if (opening) View.VISIBLE else View.GONE
        binding.focusSquare.visibility = if (opening) View.GONE else View.VISIBLE
        if (opening) {
            if (binding.paramBar.visibility == View.VISIBLE) closeControlBar()
            seedFromCamera()
            refreshVerticalPanel()
        }
    }

    /**
     * Going back to automatic and letting the camera find an exposure is the
     * fastest way to a good starting point, which is exactly what the operator
     * wants before taking manual control again. So auto is not just a mode, it
     * is the seed: whatever it settles on becomes the manual position.
     */
    private fun returnToAuto() {
        val controls = service?.controls ?: return
        controls.setAutoExposure()
        controls.setAutoWhiteBalance()
        controls.setAutoFocus()
        manualExposure = false
        manualWhiteBalance = false
        manualFocus = false
        say("Auto, re-reading the scene")
        // Everything is re-detected, not merely handed back: the whole point
        // of pressing this is that the last answers were wrong.
        binding.vectorscope.reset()
        resetGrade()
        ui.postDelayed({
            seedFromCamera()
            controls.holdMeasuredWhiteBalance()?.let {
                manualWhiteBalance = true
                kelvinProgress = Mechanism.progressForKelvin(controls.heldKelvin, 100)
            }
            manualExposure = true
            pushExposure()
            refreshVerticalPanel()
            say("Everything set from the camera")
        }, 1100)
    }

    private fun leaveAuto(which: Mechanism.Param?) {
        val controls = service?.controls ?: return
        when (which) {
            Mechanism.Param.WHITE_BALANCE -> manualWhiteBalance = true
            Mechanism.Param.ISO, Mechanism.Param.SHUTTER ->
                if (controls.supportsManualSensor()) manualExposure = true
            else -> manualFocus = true
        }
        refreshVerticalPanel()
    }

    /** Takes the camera's own current readings as the manual starting point. */
    private fun seedFromCamera() {
        val controls = service?.controls ?: return
        val iso = controls.lastIso
        val exposure = controls.lastExposureNs
        controls.isoRange()?.let { range ->
            if (iso != null) isoProgress = (iso - range.lower).coerceIn(0, range.upper - range.lower)
        }
        shutterRange()?.let { range ->
            if (exposure != null) {
                shutterProgress =
                    Mechanism.progressForShutter(exposure, 200, range.first, range.second)
            }
        }
    }

    /**
     * Lights the column the rocker is driving.
     *
     * One at a time, set here rather than by each fader watching a shared
     * value, so two can never both believe they are the chosen one.
     */
    private fun refreshColumnFocus() {
        listOf(
            Mechanism.Param.ISO to binding.vIso,
            Mechanism.Param.SHUTTER to binding.vShutter,
            Mechanism.Param.WHITE_BALANCE to binding.vWhiteBalance,
            Mechanism.Param.ZOOM to binding.vZoom,
            Mechanism.Param.FOCUS to binding.vFocus,
            Mechanism.Param.GAIN to binding.vGain
        ).forEach { (which, fader) ->
            fader.focused = which == focusedColumn
        }
        // Focus and gain were excluded here, so selecting either lit nothing
        // and looked like a column that could not be chosen at all.
        // Focus and gain are not rocker parameters, so they can never be the
        // focused column and were lighting for nobody. They light when they
        // are the fader being touched, which is what the operator means by
        // selected.
        binding.vFocus.focused = focusedColumn == Mechanism.Param.FOCUS
        binding.vGain.focused = focusedColumn == Mechanism.Param.GAIN
    }

    private fun refreshVerticalPanel() {
        val controls = service?.controls
        refreshColumnFocus()
        binding.vIso.automatic = !manualExposure
        binding.vShutter.automatic = !manualExposure
        binding.vWhiteBalance.automatic = !manualWhiteBalance
        binding.vFocus.automatic = !manualFocus
        binding.vZoom.automatic = false

        controls?.isoRange()?.let {
            binding.vIso.max = (it.upper - it.lower).coerceAtLeast(1)
            binding.vIso.progress = isoProgress
            binding.vIso.valueText = "${it.lower + isoProgress}"
        }
        binding.vShutter.max = 200
        binding.vShutter.progress = shutterProgress
        shutterRange()?.let { range ->
            binding.vShutter.valueText = Mechanism.formatShutter(
                Mechanism.shutterFromProgress(shutterProgress, 200, range.first, range.second)
            )
        }
        // A working range rather than the sensor's, so the useful part of the
        // fader is the whole fader.
        binding.vWhiteBalance.max = 100
        binding.vWhiteBalance.showsCentre = true
        binding.vWhiteBalance.marks = Mechanism.presetPositions(100)
        binding.vWhiteBalance.progress = kelvinProgress
        binding.vWhiteBalance.valueText = "${Mechanism.kelvinFromProgress(kelvinProgress, 100)} K"

        binding.vGain.progress = gainProgress
        binding.vGain.meterLevel = service?.let { Mechanism.rmsToMeterFraction(it.audioLevel) }
        binding.vGain.valueText = "%+.0f dB".format(Mechanism.gainDbFromProgress(gainProgress, 100))

        binding.vFocus.progress = focusProgress
        binding.vFocus.valueText = if (focusProgress == 0) "\u221E" else "$focusProgress%"

        binding.vZoom.max = 100
        binding.vZoom.progress = zoomProgress
        controls?.zoomRange()?.let {
            binding.vZoom.valueText = String.format(
                "%.1fx", it.lower + (zoomProgress / 100f) * (it.upper - it.lower)
            )
        }
    }

    private fun onVerticalMoved(which: Mechanism.Param, progress: Int) {
        // Moving a fader is the request. It used to be ignored unless manual
        // had already been switched on somewhere else, so the panel worked
        // after pressing A or opening the single fader bar and did nothing at
        // all otherwise, which is why the controls seemed to come and go.
        val controls = service?.controls
        when (which) {
            Mechanism.Param.ISO, Mechanism.Param.SHUTTER ->
                manualExposure = true
            Mechanism.Param.WHITE_BALANCE -> manualWhiteBalance = true
            Mechanism.Param.FOCUS -> manualFocus = true
            Mechanism.Param.ZOOM, Mechanism.Param.GAIN -> Unit
        }

        when (which) {
            Mechanism.Param.ISO -> { isoProgress = progress; pushExposure() }
            Mechanism.Param.SHUTTER -> { shutterProgress = progress; pushExposure() }
            Mechanism.Param.WHITE_BALANCE -> {
                // Magnetic: near tungsten or daylight the fader lands exactly,
                // because two shots that nearly match do not match.
                val asked = Mechanism.kelvinFromProgress(progress, 100)
                val snapped = Mechanism.snapKelvin(asked)
                kelvinProgress =
                    if (snapped != asked) Mechanism.progressForKelvin(snapped, 100) else progress
                if (manualWhiteBalance) {
                    val kelvin = Mechanism.kelvinFromProgress(kelvinProgress, 100)
                    val active = activeLink()
                    if (active?.isRemote == true) {
                        active.setManualWhiteBalance(kelvin)
                    } else {
                        // Locally this shifts the camera's own measurement
                        // rather than substituting a textbook answer for it.
                        // Nudging the camera's own measurement only exists on
                        // the eight bit controls; the engine sets the gains
                        // directly, which reaches the same place.
                        val local = service?.controls
                        if (local != null) local.nudgeWhiteBalanceTo(kelvin)
                        else active?.setManualWhiteBalance(kelvin)
                    }
                }
            }
            Mechanism.Param.ZOOM -> { zoomProgress = progress; pushZoom(progress) }
            Mechanism.Param.FOCUS -> { focusProgress = progress; manualFocus = true; pushFocus() }
            Mechanism.Param.GAIN -> { gainProgress = progress; applyGain() }
        }
        refreshVerticalPanel()
    }

    /**
     * Runs the camera's own routine for one moment, takes the number it
     * arrives at, and hands control straight back.
     */
    /**
     * Puts one control back where the camera would have it: run the camera's
     * own routine for a moment, take the answer, stay manual.
     */
    private fun snapToAuto(which: Mechanism.Param) {
        val controls = service?.controls ?: return
        when (which) {
            // Focus and gain have no camera side automatic to return to: one
            // is a lens position and the other is a number applied to audio.
            Mechanism.Param.FOCUS -> focusOnSquare()
            Mechanism.Param.GAIN -> {
                gainProgress = 50
                applyGain()
                refreshVerticalPanel()
            }

            Mechanism.Param.ISO, Mechanism.Param.SHUTTER -> {
                controls.setAutoExposure()
                say("Reading the scene")
                ui.postDelayed({
                    seedFromCamera()
                    manualExposure = true
                    pushExposure()
                    refreshVerticalPanel()
                    say("Set from the camera")
                }, 800)
            }

            Mechanism.Param.WHITE_BALANCE -> {
                controls.setAutoWhiteBalance()
                say("Balancing")
                ui.postDelayed({
                    val held = controls.holdMeasuredWhiteBalance()
                    if (held == null) {
                        say("This camera does not report its balance")
                        controls.setAutoWhiteBalance()
                    } else {
                        manualWhiteBalance = true
                        kelvinProgress = Mechanism.progressForKelvin(controls.heldKelvin, 100)
                        say("Balanced at ${controls.heldKelvin}K")
                    }
                    refreshVerticalPanel()
                }, 1000)
            }

            Mechanism.Param.ZOOM -> {
                zoomProgress = 0
                pushZoom(0)
                refreshVerticalPanel()
            }
        }
    }

    private fun suggestFor(which: Mechanism.Param, button: CircleButtonView) {
        val controls = service?.controls ?: return
        button.busy = true
        when (which) {
            Mechanism.Param.FOCUS -> { focusOnSquare(); button.busy = false }
            Mechanism.Param.GAIN -> {
                gainProgress = 50
                applyGain()
                refreshVerticalPanel()
                button.busy = false
            }

            Mechanism.Param.ISO, Mechanism.Param.SHUTTER -> {
                controls.setAutoExposure()
                ui.postDelayed({
                    seedFromCamera()
                    manualExposure = true
                    pushExposure()
                    refreshVerticalPanel()
                    button.busy = false
                }, 800)
            }

            Mechanism.Param.WHITE_BALANCE -> {
                // Let the camera look at the frame and solve grey, then keep
                // that solution. Its gains are the balance itself; a Kelvin
                // number is only how the fader reads it back.
                controls.setAutoWhiteBalance()
                ui.postDelayed({
                    val held = controls.holdMeasuredWhiteBalance()
                    if (held == null) {
                        say("This camera does not report its white balance")
                        controls.setAutoWhiteBalance()
                    } else {
                        manualWhiteBalance = true
                        val kelvin = Mechanism.kelvinFromGains(held[0], held[1], held[2])
                        kelvinProgress = Mechanism.progressForKelvin(kelvin, 100)
                        say("Balanced, holding ${kelvin}K")
                    }
                    refreshVerticalPanel()
                    button.busy = false
                }, 900)
            }

            Mechanism.Param.ZOOM -> {
                zoomProgress = 0
                pushZoom(0)
                refreshVerticalPanel()
                button.busy = false
            }
        }
    }

    /**
     * Focus where the box is, then hold it. Focus as a place rather than a
     * number: nobody thinks in dioptres, everybody can point at a face.
     */
    private fun focusOnSquare() {
        // Remote focuses through the same gesture: the box is a place, and a
        // place travels over the wire as two numbers.
        (link as? RemoteLink)?.let { remote ->
            binding.focusSquare.visibility = View.VISIBLE
            binding.focusSquare.state = FocusSquareView.State.SEEKING
            binding.focusSquare.state = FocusSquareView.State.SEEKING
            remote.focusAtPoint(binding.focusSquare.centreX, binding.focusSquare.centreY)
            ui.postDelayed({
                binding.focusSquare.state = FocusSquareView.State.LOCKED
                say("Asked ${remote.label} to focus there")
            }, 1200)
            return
        }
        val controls = service?.controls ?: return
        if (binding.focusSquare.visibility != View.VISIBLE) {
            binding.focusSquare.visibility = View.VISIBLE
        }
        binding.focusSquare.state = FocusSquareView.State.SEEKING
        binding.focusSquare.state = FocusSquareView.State.SEEKING

        val started = controls.focusOnRegion(binding.focusSquare.normalisedBounds()) { focused ->
            runOnUiThread {
                binding.focusSquare.state =
                    if (focused) FocusSquareView.State.LOCKED else FocusSquareView.State.FAILED
                if (focused) {
                    controls.lockFocusHere()
                    manualFocus = true
                    controls.lastFocusDistance?.let { distance ->
                        val closest = maxOf(controls.minimumFocusDistanceOrZero(), 0.0001f)
                        focusProgress = ((distance / closest) * 100).toInt().coerceIn(0, 100)
                    }
                    refreshVerticalPanel()
                    say("Focus locked")
                } else {
                    say("Could not focus there")
                }
            }
        }
        if (!started) {
            binding.focusSquare.state = FocusSquareView.State.FAILED
            say("This camera has no focus control")
        }
    }

    private fun applyGain() {
        val db = Mechanism.gainDbFromProgress(gainProgress, 100)
        service?.setAudioGain(Mechanism.gainFactor(db))
        binding.vGain.valueText = "%+.0f dB".format(db)
    }

    private fun pushFocus() {
        // Same rule: reaching for the focus fader is the request for manual.
        manualFocus = true
        activeLink()?.setFocusFraction(focusProgress / 100f)
    }

    // --- live actions -------------------------------------------------------

    private fun setUpActions() {
        binding.settingsButton.symbol = "\u2261"
        binding.gearButton.symbol = "\u2699"
        // A gear reads as a gear. A ring around it is one more shape competing
        // with the frame for nothing.
        binding.gearButton.showRing = false

        // The menu is the controls, and pressing it again puts them away. The
        // focus box used to swallow the tap that did this, which left no way
        // out at all.
        binding.focusModeButton.setOnClickListener {
            val next = if (focusDirector.mode == FocusDirector.Mode.MANUAL) {
                FocusDirector.Mode.AUTO
            } else {
                FocusDirector.Mode.MANUAL
            }
            focusDirector.setMode(next)
            refreshFocusButton()
            say(
                if (next == FocusDirector.Mode.AUTO) "Autofocus, checking every 2s"
                else "Single focus, tap the box to set it"
            )
        }
        refreshFocusButton()

        binding.settingsButton.setOnClickListener { toggleVerticalPanel() }
        binding.settingsButton.setOnLongClickListener { openControlBar(); true }
        binding.gearButton.setOnClickListener { startActivity(SettingsActivity.intent(this)) }
        binding.gearButton.setOnLongClickListener {
            toggleGradePanel()
            true
        }

        // Picking what to watch belongs on the screen that watches it, not
        // three taps away in settings.
        binding.sourceButton.centerText = "SRC"
        binding.sourceButton.ringColor = CircleButtonView.ACTIVE
        binding.sourceButton.setOnClickListener { pickSourceOnScreen() }
        // Long press rescans from scratch, for when a camera has only just
        // come up and was not on the network the first time we looked.
        binding.sourceButton.setOnLongClickListener {
            say("Rescanning", transient = false)
            pickSourceOnScreen()
            true
        }

        // A tap says what it would do; a hold does it. An accidental tap that
        // kills a live stream is worse than an extra second of deliberation,
        // and these two sit beside buttons that are pressed constantly.
        CrashLog.trace("rail")
        setUpOverlayRail()

        binding.killButton.setOnClickListener {
            say("Hold X to shut down completely")
        }
        binding.killButton.setOnLongClickListener {
            killApp()
            true
        }
        // Tap the format field to turn the picture a quarter, for when the
        // automatic answer is wrong on this phone.
        binding.timecodeView.setOnClickListener {
            appSettings.previewRotationOffset = appSettings.previewRotationOffset + 90
            applyPreviewTransform()
            say("Preview turned " + appSettings.previewRotationOffset)
        }

        binding.resetButton.setOnClickListener {
            say("Hold RST to reset to a safe state")
        }
        binding.resetButton.setOnLongClickListener {
            resetToSafeState()
            true
        }

        binding.streamButton.centerText = "NDI"
        binding.streamButton.setOnClickListener { toggleStreaming() }

        binding.recordButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isRecording) {
                svc.stopRecording()
                say("Saved to DCIM/${MediaStoreOutput.FOLDER}")
            } else if (svc.startRecording()) {
                say("Recording", transient = false)
            } else {
                say("Go live first")
            }
        }

        // Kept as a shortcut for a hand that already knows it, but the button
        // above is the way it is meant to be found.
        binding.recordButton.setOnLongClickListener {
            val svc = service ?: return@setOnLongClickListener true
            if (svc.isStreaming) {
                svc.stopStreaming()
                say("Idle", transient = false)
            } else {
                val name = SourceIdentity.sanitize(identity.name)
                warnIfNameTaken(name)
                startForegroundService(Intent(this, NdiSendService::class.java))
                svc.startStreaming(name) { error ->
                    runOnUiThread { say(error) }
                }
                say("Live as $name", transient = false)
            }
            true
        }
    }

    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Could not set up $what", e)
            say("$what unavailable")
        }
    }

    /**
     * The other way to aim focus: the model lists what it sees, the operator
     * picks the thing they meant, and the box goes there. Faster than aiming a
     * rectangle at a face across a road, and it is the same focus afterwards.
     */
    private fun askVisionForSubjects() {
        val store = KeyRingStore(this)
        if (store.load().isEmpty()) {
            say("Import AI focus keys in settings")
            return
        }
        if (!binding.preview.isAvailable) return

        val frame = binding.preview.getBitmap(640, 360)
        if (frame == null) {
            say("No frame to look at")
            return
        }

        say("Looking at the frame", transient = false)
        VisionFocus.findSubjectsWithRing(
            store = store,
            frame = frame,
            onResult = { subjects ->
                runOnUiThread {
                    say("Pick a subject")
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Focus on")
                        .setItems(subjects.map { it.label }.toTypedArray()) { _, which ->
                            val picked = subjects[which]
                            binding.focusSquare.moveTo(picked.x, picked.y)
                            focusDirector.target = picked.x to picked.y
                            focusDirector.focusHereAndHold()
                            say("Focusing on ${picked.label}")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
            onError = { message -> runOnUiThread { say(message) } }
        )
    }

    /**
     * AF or SF, which is what the two modes actually are. A and M were
     * borrowed from a lens barrel and neither one said what happens: AF checks
     * and follows, SF focuses once on the box and holds it there.
     */
    /**
     * Lift, gamma and gain, sent as one curve.
     *
     * Applied on release rather than on every move: the grade is three curves
     * of up to a hundred and twenty eight points each, and rebuilding them per
     * touch event would put the same load on the camera thread that the
     * Planckian search used to. A finger sees the change when it lifts, which
     * for a grade is the moment anybody judges it anyway.
     */
    private fun setUpGradeWheels() {
        val wheels = listOf(
            binding.wheelLift to "Lift",
            binding.wheelGamma to "Gamma",
            binding.wheelGain to "Gain"
        )
        wheels.forEach { (wheel, name) ->
            wheel.label = name
            wheel.onChanged = { gradeDirty = true }
            wheel.onReleased = { applyGrade() }
        }
    }

    /**
     * The curve, applied every time this screen comes up.
     *
     * It was only ever set when the pipeline was first built, so choosing
     * S-Log3 in settings changed a stored value and nothing else, and the
     * image stayed exactly as it was. Nothing anywhere corrects log back to
     * Rec.709; the flat picture simply never arrived.
     */
    /**
     * A camera that sleeps mid take is not a camera, so the flag rather than a
     * wake lock: it applies to this window only and goes when the window does,
     * which is what a wake lock so often fails to do.
     */
    /**
     * Keeps the picture the shape it was shot.
     *
     * A TextureView stretches its buffer to its own bounds and asks nobody.
     * Rotate the phone, or send the app away and bring it back, and the view
     * is resized while the buffer is not, so the picture is stretched and
     * stays stretched because nothing recalculates it. This runs on every
     * event that can change either one.
     */
    private fun applyPreviewTransform() {
        val view = binding.preview
        if (view.width <= 0 || view.height <= 0) {
            CrashLog.trace("transform skipped: view not laid out")
            return
        }

        // The tap produced no trace line at all, which means this returned
        // before reaching the maths. A missing profile is not a reason to
        // leave the picture sideways, so the view's own shape stands in.
        val size = currentVideoSize() ?: (view.width to view.height).also {
            CrashLog.trace("transform: no profile, using view shape")
        }
        val scale = Mechanism.previewTransform(
            view.width, view.height, size.first, size.second, fill = false
        )

        val cx = view.width / 2f
        val cy = view.height / 2f
        val matrix = android.graphics.Matrix()
        matrix.setScale(scale[0], scale[1], cx, cy)

        // The sensor is mounted turned, and the direct pipeline hands the
        // camera to the view untouched, so the picture arrives on its side.
        // RootEncoder does this for the eight bit path and nothing was doing
        // it for ten bit.
        val turn = previewRotation()
        if (turn != 0) {
            matrix.postRotate(turn.toFloat(), cx, cy)
            // A quarter turn puts the long edge against the short one, so the
            // rotated buffer has to be scaled back to cover the view.
            if (turn % 180 != 0 && view.height > 0 && view.width > 0) {
                val cover = maxOf(
                    view.width.toFloat() / view.height,
                    view.height.toFloat() / view.width
                )
                matrix.postScale(cover, cover, cx, cy)
            }
        }
        view.setTransform(matrix)
    }

    /**
     * How far the preview has to be turned, if anyone is turning it.
     *
     * Only the direct pipeline needs this. RootEncoder already orients its own
     * output, so applying it there would turn a correct picture wrong.
     */
    private fun previewRotation(): Int {
        val engine = service?.engineControls ?: return 0
        val display = when (windowManager.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return ((engine.sensorOrientation() - display) + 360) % 360
    }

    /** What is actually filling the preview, whichever mode this is. */
    private fun currentVideoSize(): Pair<Int, Int>? {
        remoteEngine?.let { engine ->
            val w = engine.lastWidth
            val h = engine.lastHeight
            if (w > 0 && h > 0) return w to h
        }
        val profile = activeProfile ?: return null
        // A portrait view of a landscape sensor is the usual case, and the
        // sensor's numbers are always given the long way round.
        return if (binding.preview.height > binding.preview.width) {
            profile.height to profile.width
        } else {
            profile.width to profile.height
        }
    }

    private fun applyScreenPolicy() {
        if (appSettings.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Asked once, on the screen where it matters, and never again.
     *
     * Android stops work for apps it thinks are idle, and a phone streaming
     * from a shelf looks exactly like an idle one: screen off, nobody touching
     * it. The stream stops and the operator hears about it from the mixer.
     */
    private fun offerBatteryExemptionOnce() {
        if (!PowerPolicy.shouldAsk(this, appSettings)) return
        appSettings.batteryAsked = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Let this app keep running")
            .setMessage(
                "Android stops background work for apps it decides are idle, and " +
                    "a phone streaming from a shelf looks idle to it. Allowing " +
                    "unrestricted battery use keeps a stream or a recording alive " +
                    "when the screen goes off.\n\nYou can change this later in " +
                    "Settings, under System."
            )
            .setPositiveButton("Allow") { _, _ ->
                if (!PowerPolicy.requestExemption(this)) {
                    PowerPolicy.openBatterySettings(this)
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * The monitor LUT: correction on this screen, never on what leaves.
     *
     * One tap to compare. Grey is the picture the sensor is actually sending,
     * green is that picture corrected for this display, and nothing about the
     * stream or the recording changes either way.
     */
    private fun toggleLut() {
        if (!PreviewEffects.supported) {
            say("This phone cannot correct the preview; it needs Android 13")
            return
        }
        appSettings.previewLut = !appSettings.previewLut
        val ok = applyPreviewEffects()
        if (!ok) {
            appSettings.previewLut = false
            say("Could not apply the correction")
        }
        refreshLutButton()
    }

    /**
     * Both effects go on in one call, because a View has one render effect and
     * two features that each set their own would silently cancel each other.
     */
    private var loadedLutCurve: LogCurves.Curve? = null
    private var loadedLut: CubeLut? = null

    /**
     * The cube for the curve in use, read once and kept until the curve
     * changes. Parsing 35,937 triples on every touch event would be its own
     * kind of bug.
     */
    private fun lutForCurrentCurve(): CubeLut? {
        val curve = appSettings.logCurve
        if (loadedLutCurve == curve) return loadedLut
        loadedLutCurve = curve
        loadedLut = appSettings.lutForCurve(curve)?.let { uri ->
            try {
                contentResolver.openInputStream(android.net.Uri.parse(uri))
                    ?.bufferedReader()?.use { CubeLut.parse(it.readText()) }
            } catch (e: Exception) {
                null
            }
        }
        return loadedLut
    }

    private fun applyPreviewEffects(): Boolean = PreviewEffects.apply(
        view = binding.preview,
        curve = appSettings.logCurve,
        lut = appSettings.previewLut,
        peak = appSettings.focusPeaking,
        peakColour = appSettings.peakColour,
        sensitivity = appSettings.peakSensitivity,
        waveform = appSettings.waveformChannels,
        uploaded = lutForCurrentCurve()
    )

    private fun togglePeaking() {
        if (!PreviewEffects.supported) {
            say("Peaking needs Android 13 on this phone")
            return
        }
        appSettings.focusPeaking = !appSettings.focusPeaking
        if (!applyPreviewEffects()) {
            appSettings.focusPeaking = false
            say("Could not apply peaking")
        }
        refreshLutButton()
    }

    /**
     * The left bar: everything drawn on the picture, each a toggle.
     *
     * Two bars rather than one column, because the column had grown past the
     * bottom of the screen and the gear had gone off the end of it. Splitting
     * by kind rather than by size also means a thumb learns where a thing is:
     * what is drawn on the picture is on the left, what the camera does is on
     * the right.
     */
    private fun setUpOverlayRail() {
        binding.tglLut.setOnClickListener { toggleLut() }
        binding.tglPeak.setOnClickListener { togglePeaking() }
        binding.tglPeak.setOnLongClickListener { cyclePeakSensitivity(); true }

        binding.tglWave.setOnClickListener {
            // Cycles off, luma, then the full parade, since those are the
            // three anybody switches between while shooting.
            appSettings.waveformChannels = when {
                appSettings.waveformChannels.isEmpty() ->
                    setOf(Mechanism.WaveformChannel.LUMA)
                appSettings.waveformChannels.size == 1 -> setOf(
                    Mechanism.WaveformChannel.RED,
                    Mechanism.WaveformChannel.GREEN,
                    Mechanism.WaveformChannel.BLUE
                )
                else -> emptySet()
            }
            applyPreviewEffects()
            refreshRail()
        }

        binding.tglScope.setOnClickListener {
            appSettings.vectorscopeVisible = !appSettings.vectorscopeVisible
            refreshVectorscope()
            refreshRail()
        }
        binding.tglGrade.setOnClickListener { toggleGradePanel(); refreshRail() }
        binding.tglFocusBox.setOnClickListener {
            val showing = binding.focusSquare.visibility == View.VISIBLE
            binding.focusSquare.visibility = if (showing) View.GONE else View.VISIBLE
            refreshRail()
        }
        binding.tglBurn.setOnClickListener {
            appSettings.showTimecode = !appSettings.showTimecode
            refreshTimecode()
            refreshRail()
        }
    }

    /** Lit means on. Nothing has to be turned off to find out what is on. */
    private fun refreshRail() {
        fun light(view: android.widget.TextView, on: Boolean, colour: String = "#12C46A") {
            view.setTextColor(
                android.graphics.Color.parseColor(if (on) colour else "#6E7A86")
            )
        }

        val curve = appSettings.logCurve
        val lutAvailable = PreviewEffects.supported && curve != LogCurves.Curve.REC709
        binding.tglLut.alpha = if (lutAvailable) 1f else 0.35f
        light(binding.tglLut, lutAvailable && appSettings.previewLut)

        // Green for on, grey for off, the same as every other toggle. The
        // peaking colour belongs on the edges it marks, not on its own button:
        // a bar where each switch lights differently is a bar that has to be
        // read rather than glanced at.
        binding.tglPeak.alpha = if (PreviewEffects.supported) 1f else 0.35f
        light(binding.tglPeak, PreviewEffects.supported && appSettings.focusPeaking)

        light(binding.tglWave, appSettings.waveformChannels.isNotEmpty())
        light(binding.tglScope, appSettings.vectorscopeVisible)
        light(binding.tglGrade, binding.gradePanel.visibility == View.VISIBLE)
        light(binding.tglFocusBox, binding.focusSquare.visibility == View.VISIBLE)
        light(binding.tglBurn, appSettings.showTimecode)

        // Nothing on the left bar means anything without a picture of our own.
        val hasLens = appSettings.appMode != AppMode.MONITOR
        binding.tglGrade.visibility = if (hasLens) View.VISIBLE else View.GONE
        binding.tglFocusBox.visibility = binding.tglGrade.visibility
    }

    private fun refreshLutButton() = refreshRail()

    private fun cyclePeakSensitivity() {
        val steps = listOf(30, 50, 75)
        val next = steps.firstOrNull { it > appSettings.peakSensitivity } ?: steps.first()
        appSettings.peakSensitivity = next
        applyPreviewEffects()
        say("Peaking sensitivity " + next)
    }

    /**
     * Everything down, and the process with it.
     *
     * Android will not reliably stop a foreground service and free a camera
     * just because an activity finished, and a half released NDI sender keeps
     * announcing a source that no longer sends anything, which on a mixer
     * looks like a camera that has frozen rather than one that has gone. So
     * this tears the pieces down in order and then ends the process, which is
     * the only way to be certain the native sender and the camera are both
     * genuinely released.
     */
    private fun killApp() {
        say("Shutting down", transient = false)
        runCatching { ltcEngine.stop() }
        runCatching { remoteEngine?.stop() }
        runCatching { focusDirector.stop() }
        runCatching { service?.stopStreaming() }
        runCatching { service?.stopRecording() }
        runCatching { service?.releasePipeline() }
        runCatching { if (bound) unbindService(connection) }
        runCatching { stopService(Intent(this, NdiSendService::class.java)) }

        // A moment for the service to finish its own teardown, then go.
        ui.postDelayed({
            finishAffinity()
            kotlin.system.exitProcess(0)
        }, 400)
    }

    /**
     * Back to a state that works, without losing what makes this phone this
     * camera.
     *
     * The point is recovering from something gone wrong on set, so it restarts
     * the pipeline rather than telling the operator to do it. Identity, keys,
     * lens and timecode role survive: wiping those in a hurry is worse than
     * whatever went wrong.
     */
    private fun resetToSafeState() {
        say("Resetting", transient = false)

        appSettings.logCurve = LogCurves.Curve.REC709
        appSettings.tenBitWanted = false
        appSettings.previewLut = false
        appSettings.focusPeaking = false
        appSettings.waveformChannels = emptySet()
        appSettings.vectorscopeVisible = false
        appSettings.stabilisation = false
        appSettings.streamMbps = 0
        appSettings.streamHalfSize = false
        profileStore.applyFormat(
            width = 1920, height = 1080, fps = 25,
            bitRate = 25_000_000, useHevc = false
        )

        runCatching { service?.stopStreaming() }
        runCatching { binding.gradePanel.visibility = View.GONE }
        runCatching { binding.vectorscope.visibility = View.GONE }
        runCatching { applyPreviewEffects() }

        resetForModeChange()
        preparePipeline()
        refreshLutButton()
        say("1080p25, Rec.709, overlays off", transient = false)
    }

    private fun applyLogCurve() {
        val curve = appSettings.logCurve
        val active = activeLink()
        if (active?.isRemote == true) {
            (active as? RemoteLink)?.setLogCurve(curve)
            return
        }
        if (!DeviceProfile.logCapable) return
        // Either pipeline, since only one of the two exists at a time.
        service?.controls?.setLogCurve(curve)
        service?.engineControls?.setLogCurve(curve)
    }

    private fun applyGrade() {
        val controls = service?.controls ?: return
        if (!DeviceProfile.logCapable) {
            say("This camera will not take a custom curve, so grading is unavailable")
            return
        }
        val lift = Mechanism.liftFrom(
            Mechanism.wheelToChannels(binding.wheelLift.angle, binding.wheelLift.radius),
            binding.wheelLift.master
        )
        val gamma = Mechanism.gammaFrom(
            Mechanism.wheelToChannels(binding.wheelGamma.angle, binding.wheelGamma.radius),
            binding.wheelGamma.master
        )
        val gain = Mechanism.gainFrom(
            Mechanism.wheelToChannels(binding.wheelGain.angle, binding.wheelGain.radius),
            binding.wheelGain.master
        )
        controls.setGrade(appSettings.logCurve, lift, gamma, gain)
        gradeDirty = false
    }

    private fun toggleGradePanel() {
        val opening = binding.gradePanel.visibility != View.VISIBLE
        if (opening) {
            if (binding.verticalPanel.visibility == View.VISIBLE) toggleVerticalPanel()
            if (binding.paramBar.visibility == View.VISIBLE) closeControlBar()
            binding.focusSquare.visibility = View.GONE
            say("Drag a wheel, double tap one to neutral", transient = false)
        } else {
            binding.focusSquare.visibility = View.VISIBLE
            say("Grade held")
        }
        binding.gradePanel.visibility = if (opening) View.VISIBLE else View.GONE
    }

    private fun resetGrade() {
        binding.wheelLift.setNeutral()
        binding.wheelGamma.setNeutral()
        binding.wheelGain.setNeutral()
        val (lift, gamma, gain) = Mechanism.neutralGrade()
        service?.controls?.setGrade(appSettings.logCurve, lift, gamma, gain)
    }

    private fun refreshFocusButton() {
        binding.focusModeButton.centerText =
            if (focusDirector.mode == FocusDirector.Mode.AUTO) "AF" else "SF"
    }

    private fun refreshLiveIndicators() {
        val svc = service ?: return
        if (appSettings.appMode == AppMode.MONITOR) {
            // No microphone of ours in this mode, and no tally about us.
            binding.vuHairline.setLevel(0f)
            binding.tallyBorder.state = TallyBorderView.State.OFF
            return
        }

        // Top hairline is what the microphone hears; the gain fader's own
        // meter is what the encoder is given. The fader sits between them.
        binding.vuHairline.setLevel(Mechanism.rmsToMeterFraction(svc.audioLevelIn))
        val level = Mechanism.rmsToMeterFraction(svc.audioLevelOut)

        // The gain fader's own meter was only redrawn when the panel was
        // rebuilt, which happens when a control changes and not otherwise, so
        // it sat frozen at whatever the level had been when the panel opened.
        // It belongs on the tick with every other live reading.
        if (binding.verticalPanel.visibility == View.VISIBLE) {
            binding.vGain.meterLevel = level
        }

        binding.recordButton.ringColor =
            if (svc.isRecording) CircleButtonView.RECORDING else CircleButtonView.RECORD_IDLE
        refreshStreamButton()
        binding.recordButton.glow = svc.isRecording
        binding.recordButton.centerText =
            if (svc.isRecording) Mechanism.recordLabel(svc.recordingElapsedSeconds) else ""

        binding.tallyBorder.state = when {
            !svc.isStreaming || svc.tally < 0 -> TallyBorderView.State.OFF
            svc.tally and 1 != 0 -> TallyBorderView.State.PROGRAM
            svc.tally and 2 != 0 -> TallyBorderView.State.PREVIEW
            else -> TallyBorderView.State.CONNECTED
        }
    }

    /**
     * Local or remote, decided in settings and applied here. Remote points the
     * monitor at the chosen source, shows the red frame, and swaps the control
     * link; everything else on this screen is unchanged, which is the point.
     */
    /**
     * Local, remote or monitor, and the difference is which of two things
     * fills the preview: this phone's sensor, or a decoder fed from the
     * network. Never both.
     */
    /**
     * Everything down, before anything comes up.
     *
     * Deliberately blunt. Each of these was, at some point, the thing left
     * running that broke the next mode, and a teardown that tries to be clever
     * about which ones matter is a teardown that will be wrong again.
     */
    private fun resetForModeChange() {
        lastAppliedMode = appSettings.appMode

        remoteEngine?.stop()
        remoteEngine = null
        service?.releasePipeline()
        link = null

        focusDirector.stop()
        pump.flush()
        balanceBase = null
        manualExposure = false
        manualWhiteBalance = false
        manualFocus = false

        binding.verticalPanel.visibility = View.GONE
        binding.autoAllButton.visibility = View.GONE
        binding.paramBar.visibility = View.GONE
        binding.gradePanel.visibility = View.GONE
        binding.vectorscope.visibility = View.GONE
        binding.remoteBorder.visibility = View.GONE
        binding.noSignal.visibility = View.GONE
        binding.tallyBorder.state = TallyBorderView.State.OFF
        // The mode lives on the status line now; announcing it across the
        // picture as well was the overlap.
    }

    private fun applyCameraSource() {
        val mode = appSettings.appMode

        if (mode == AppMode.LOCAL) {
            remoteEngine?.stop()
            remoteEngine = null
            binding.remoteBorder.visibility = View.GONE
            link = null; activeLink()
            applyModeToInterface(mode)
            return
        }

        val source = appSettings.remoteSource
        if (source == null) {
            say("Pick a source in settings", transient = false)
            applyModeToInterface(mode)
            return
        }

        // Already watching this one; restarting would only blank the picture.
        if (remoteEngine != null && (link as? RemoteLink)?.label == source) {
            applyModeToInterface(mode)
            return
        }

        remoteEngine?.stop()
        remoteEngine = null

        val remote = RemoteLink(source)
        link = remote
        binding.remoteBorder.sourceName = source
        binding.remoteBorder.visibility = View.VISIBLE
        applyModeToInterface(mode)

        if (!surfaceReady) return
        val texture = binding.preview.surfaceTexture ?: return
        // Without this the texture keeps the view's own size, so the camera
        // scales its frame into a view shaped buffer and the picture arrives
        // stretched. RootEncoder does this for the eight bit path; the direct
        // pipeline had nobody doing it.
        activeProfile?.let { texture.setDefaultBufferSize(it.width, it.height) }
        val engine = MonitorEngine(
            surface = Surface(texture),
            onStatus = { message -> runOnUiThread { say(message) } },
            onCameraState = { state ->
                remote.state = state
                runOnUiThread { refreshVerticalPanel() }
            }
        )
        engine.start(source)
        remoteEngine = engine
        say("Watching $source", transient = false)

        if (mode == AppMode.REMOTE) {
            // Ask the camera to describe itself, and put it on the same curve,
            // or the two cameras will not cut together.
            binding.root.postDelayed({
                NdiReceiver.sendCommand(CameraCommand(requestState = true))
                remote.setLogCurve(appSettings.logCurve)
            }, 1500)
        }
    }

    /**
     * What is on screen, decided by the mode rather than by what happens to be
     * left over from the last one.
     *
     * Monitor has no lens anywhere, so it has no focus box, no control
     * columns, no record and no stream: a monitor that offers to change
     * exposure is lying about what it can do. It keeps the scopes, because
     * they read pixels and there are pixels.
     */
    private fun applyModeToInterface(mode: AppMode) {
        val hasLens = mode == AppMode.LOCAL || mode == AppMode.REMOTE
        val shootsHere = mode == AppMode.LOCAL

        binding.focusModeButton.visibility = if (hasLens) View.VISIBLE else View.GONE
        binding.recordButton.visibility = if (shootsHere) View.VISIBLE else View.GONE
        binding.streamButton.visibility = if (shootsHere) View.VISIBLE else View.GONE
        binding.settingsButton.visibility = if (hasLens) View.VISIBLE else View.GONE
        binding.sourceButton.visibility = if (shootsHere) View.GONE else View.VISIBLE

        if (!hasLens) {
            binding.focusSquare.visibility = View.GONE
            binding.verticalPanel.visibility = View.GONE
            binding.autoAllButton.visibility = View.GONE
            binding.paramBar.visibility = View.GONE
            binding.gradePanel.visibility = View.GONE
        } else if (binding.verticalPanel.visibility != View.VISIBLE &&
            binding.vectorscope.visibility != View.VISIBLE
        ) {
            binding.focusSquare.visibility = View.VISIBLE
        }
    }

    /**
     * Balance by eye, on the scope, the way it is done at a desk. The marker
     * is dragged until the cloud sits on the centre, and what that produces is
     * a change in camera gains rather than a filter on the picture: the
     * correction lives in the sensor, so it is in the stream and the recording
     * as well as on screen.
     */
    private fun setUpVectorscope() {
        // One base per drag, taken when the finger lands. Reading it again on
        // every move event meant each one corrected the previous correction,
        // so a sweep multiplied itself fifty times and the centre stopped
        // meaning neutral.
        binding.vectorscope.onBalanceStarted = {
            val controls = service?.controls
            balanceBase = controls?.heldGains
                ?: controls?.lastAwbGains
                ?: floatArrayOf(1f, 1f, 1f)
        }

        binding.vectorscope.onBalanceMoved = { du, dv ->
            val controls = service?.controls
            val base = balanceBase
            if (controls != null && base != null) {
                // Applied, not stored: the base must survive the whole drag.
                controls.applyBalanceGains(
                    Mechanism.gainsFromChromaOffset(base, du, dv), commit = false
                )
            }
        }
        binding.vectorscope.onBalanceReleased = {
            val controls = service?.controls
            val base = balanceBase
            if (controls != null && base != null) {
                controls.applyBalanceGains(
                    Mechanism.gainsFromChromaOffset(
                        base, binding.vectorscope.offsetU, binding.vectorscope.offsetV
                    ),
                    commit = true
                )
            }
            balanceBase = null
            manualWhiteBalance = true
            say("Balance held")
        }
        binding.vectorscope.onResetBalance = { resetBalance() }
        binding.vectorscope.onDismiss = { hideVectorscope() }
    }

    private var scopeBitmap: Bitmap? = null
    private var scopePixels: IntArray? = null
    private var lastScopeAt = 0L

    /**
     * Sampled from the preview, four times a second.
     *
     * A hundred and sixty by ninety is about fourteen thousand pixels, read at
     * every third one, which is plenty for a scope and a rounding error next
     * to encoding a frame. The bitmap and the pixel array are allocated once
     * and reused, because allocating either at four hertz would be visible in
     * the garbage collector long before it was visible on screen.
     */
    /**
     * Back to whatever the camera itself had decided. Without this a balance
     * dragged too far is a one way door, and the only escape is closing the
     * app.
     */
    private fun resetBalance() {
        val controls = service?.controls
        balanceBase = null
        binding.vectorscope.reset()
        if (controls == null) return
        controls.setAutoWhiteBalance()
        ui.postDelayed({
            controls.holdMeasuredWhiteBalance()
            manualWhiteBalance = true
            kelvinProgress = Mechanism.progressForKelvin(controls.heldKelvin, 100)
            refreshVerticalPanel()
            say("Balance back to the camera's own")
        }, 900)
    }

    /**
     * The scope owns the screen while it is up. A scope competing with six
     * faders for the same glass is a scope nobody can read, and the controls
     * were landing on top of it.
     */
    private fun showVectorscope() {
        if (binding.verticalPanel.visibility == View.VISIBLE) toggleVerticalPanel()
        if (binding.paramBar.visibility == View.VISIBLE) closeControlBar()
        binding.focusSquare.visibility = View.GONE
        binding.controlRow.visibility = View.GONE
        binding.vectorscope.visibility = View.VISIBLE
        say("Drag to balance, double tap to reset, long press to close", transient = false)
    }

    private fun hideVectorscope() {
        // The setting is cleared first. The refresh runs ten times a second
        // and would otherwise re-open the scope on the very next tick, which
        // is exactly what made it impossible to close.
        appSettings.vectorscopeVisible = false
        binding.vectorscope.reset()
        binding.vectorscope.visibility = View.GONE
        binding.controlRow.visibility = View.VISIBLE
        binding.focusSquare.visibility = View.VISIBLE
        say("Balance locked")
    }

    private var waveBitmap: android.graphics.Bitmap? = null
    private var wavePixels: IntArray? = null
    private var lastWaveAt = 0L

    /**
     * The trace, from the same preview the scope reads.
     *
     * Two hundred and forty columns across, which is one trace column per
     * roughly five screen pixels: finer than that is detail nobody reads off a
     * waveform, and coarser loses the alignment that makes it worth overlaying.
     */
    /**
     * The trace is drawn by the preview shader now, so this only keeps the
     * sharpness reading that focus depends on.
     *
     * The waveform used to pull a bitmap off the preview every fifth of a
     * second and count 130,000 pixels on the main thread. The shader does the
     * same work in the pass that was already happening, and column for column
     * by construction rather than by matching two scales: the trace samples
     * column x of the picture at column x of itself, in one coordinate space.
     */
    private fun refreshWaveform() {
        if (!binding.preview.isAvailable) return
        val now = System.currentTimeMillis()
        if (now - lastWaveAt < 200) return
        lastWaveAt = now

        // Focus still needs a real measurement, and it needs pixels to do it.
        if (focusDirector.mode != FocusDirector.Mode.AUTO) return

        val bitmap = waveBitmap
            ?: android.graphics.Bitmap.createBitmap(
                240, 135, android.graphics.Bitmap.Config.ARGB_8888
            ).also { waveBitmap = it }
        val pixels = wavePixels ?: IntArray(240 * 135).also { wavePixels = it }

        try {
            binding.preview.getBitmap(bitmap) ?: return
            bitmap.getPixels(pixels, 0, 240, 0, 0, 240, 135)
        } catch (e: Exception) {
            return
        }

        focusDirector.currentSharpness =
            Mechanism.sharpness(pixels, 240, 135, binding.focusSquare.normalisedBounds())
        focusDirector.holdMs = appSettings.focusHoldMs
        focusDirector.rampMs = appSettings.focusRampMs
    }

    private fun refreshVectorscope() {
        val wanted = appSettings.vectorscopeVisible
        if (wanted && binding.vectorscope.visibility != View.VISIBLE) showVectorscope()
        if (!wanted && binding.vectorscope.visibility == View.VISIBLE) hideVectorscope()
        if (!wanted || !binding.preview.isAvailable) return

        val now = System.currentTimeMillis()
        if (now - lastScopeAt < 250) return
        lastScopeAt = now

        val bitmap = scopeBitmap ?: Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            .also { scopeBitmap = it }
        val pixels = scopePixels ?: IntArray(160 * 90).also { scopePixels = it }

        try {
            binding.preview.getBitmap(bitmap) ?: return
            bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90)
        } catch (e: Exception) {
            return
        }

        val centroid = Mechanism.chromaCentroidFromArgb(pixels)
        binding.vectorscope.setFrame(
            Mechanism.vectorscopeFromArgb(pixels), 64, centroid[0], centroid[1]
        )
    }

    // --- plumbing -----------------------------------------------------------

    private fun requestPermissionsThenBind() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        // From Android 10 an app writes its own media into DCIM without asking;
        // below that the folder needs the old permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun bindToService() {
        bindService(Intent(this, NdiSendService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Only one thing may write to the preview.
     *
     * This was the bug behind a remote camera whose name appeared and whose
     * picture never did. The local camera attached itself to the preview the
     * moment the service connected, whatever the mode, and the NDI decoder
     * then opened a second Surface on the same SurfaceTexture. Two producers,
     * one consumer: the camera wins and the decoded frames go nowhere. The
     * name still arrived because it comes over metadata rather than over the
     * video.
     *
     * The mode now decides who owns it, once, and the loser is not started.
     */
    private fun preparePipeline() {
        if (appSettings.appMode != AppMode.LOCAL) {
            // Not our camera's screen. Make sure it is not holding anything.
            service?.releasePipeline()
            applyCameraSource()
            return
        }
        val svc = service ?: return
        val profile = activeProfile ?: return
        svc.pipelineReady = {
            runOnUiThread {
                applyPreviewTransform()
                bindControlRanges()
                refreshVerticalPanel()
            }
        }
        val ok = svc.prepare(profile) { error ->
            runOnUiThread { say(error) }
        }
        if (ok) {
            if (surfaceReady) svc.attachPreview(binding.preview)
            bindControlRanges()
            bindPump()
        }
        // Levels before the take, not only during it.
        svc.startMeteringIfIdle()
    }

    private fun warnIfNameTaken(name: String) {
        if (!NdiFinder.available) return
        thread(name = "ndi-name-check") {
            NdiFinder.start(applicationContext)
            val existing = NdiFinder.sources(timeoutMs = 1200)
            NdiFinder.stop()
            if (SourceIdentity.clashesWith(name, existing)) {
                runOnUiThread { say("Name already in use") }
            }
        }
    }
}
