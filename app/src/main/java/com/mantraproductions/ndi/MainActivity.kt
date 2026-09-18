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

    private val pump = ControlPump()

    /**
     * Where the controls land. Local drives this phone's sensor; remote sends
     * the same intentions to a camera on the network. Nothing else in this
     * screen knows the difference.
     */
    private var link: CameraLink? = null
    private var remoteEngine: MonitorEngine? = null
    private val ui = Handler(Looper.getMainLooper())
    private val clearStatus = Runnable { binding.statusText.text = defaultStatus() }
    private val tick = object : Runnable {
        override fun run() {
            try {
                refreshLiveIndicators()
                refreshVectorscope()
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

        binding.preview.setOnClickListener { toggleVerticalPanel() }

        binding.preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                service?.attachPreview(binding.preview)
            }

            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
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
        KeyService.cameraInForeground = true
        ContextCompat.registerReceiver(
            this, keyReceiver, IntentFilter(KeyService.BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (orientationWatcher.canDetectOrientation()) orientationWatcher.enable()
        pump.start()
        requestPermissionsThenBind()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        binding.focusSquare.boxSize = appSettings.focusBoxSize
        applyCameraSource()
        // Settings may have changed the profile while we were away.
        val picked = profileStore.selected()
        if (picked.name != activeProfile?.name) {
            activeProfile = picked
            service?.let { if (!it.isStreaming) { it.releasePipeline(); preparePipeline() } }
        }
    }

    override fun onStop() {
        super.onStop()
        remoteEngine?.stop()
        remoteEngine = null
        KeyService.cameraInForeground = false
        try {
            unregisterReceiver(keyReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered, which happens if onStart bailed early.
        }
        focusDirector.stop()
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
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> +1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }

        handleRocker(direction, event.repeatCount)
        return true
    }

    private fun handleRocker(direction: Int, repeatCount: Int) {
        // Held down: record, so a take can start without looking at the screen.
        if (repeatCount == 3) {
            toggleRecording()
            return
        }
        if (repeatCount > 0) return

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

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true
        else super.onKeyUp(keyCode, event)

    /** The rocker drives whichever column was touched last. */
    private var focusedColumn: Mechanism.Param = Mechanism.Param.ISO

    private fun nudgeFocusedColumn(direction: Int) {
        val fader = when (focusedColumn) {
            Mechanism.Param.ISO -> binding.vIso
            Mechanism.Param.SHUTTER -> binding.vShutter
            Mechanism.Param.WHITE_BALANCE -> binding.vWhiteBalance
            Mechanism.Param.ZOOM -> binding.vZoom
        }
        fader.step(direction)
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
        if (service?.controls?.supportsManualSensor() == true) manualExposure = true
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
        }
        updateFaderValue()
    }

    private fun updateFaderValue() {
        val controls = service?.controls
        binding.paramFader.valueText = when (param) {
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
        val sensor = service?.controls?.exposureTimeRange() ?: return null
        return Mechanism.shutterRangeForFps(activeProfile?.fps ?: 25, sensor.lower, sensor.upper)
    }

    private fun bindPump() {
        val controls = service?.controls ?: return
        val frameDuration = Mechanism.frameDurationForFps(activeProfile?.fps ?: 25)
        pump.applyExposure = { iso, shutterNs ->
            controls.setManualExposure(iso, shutterNs, frameDuration)
        }
        pump.applyWhiteBalance = { kelvin -> controls.setManualWhiteBalance(kelvin) }
        pump.applyZoom = { zoom -> controls.setZoom(zoom) }
    }

    private fun pushExposure() {
        if (!manualExposure) return
        val controls = service?.controls ?: return
        val isoRange = controls.isoRange() ?: return
        val range = shutterRange() ?: return
        pump.setExposure(
            (isoRange.lower + isoProgress).coerceIn(isoRange.lower, isoRange.upper),
            Mechanism.shutterFromProgress(shutterProgress, 200, range.first, range.second)
        )
    }

    private fun pushZoom(progress: Int) {
        val range = service?.controls?.zoomRange() ?: return
        pump.setZoom(range.lower + (progress / 100f) * (range.upper - range.lower))
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
            }
            fader.onChange = { onVerticalMoved(which, it) }
            fader.onRelease = { pump.flush() }
            fader.onTouchedWhileAutomatic = { leaveAuto(which) }
            fader.setOnTouchListener { _, _ -> focusedColumn = which; false }
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

    private fun refreshVerticalPanel() {
        val controls = service?.controls
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
                    // Shifts the camera's own measurement warmer or cooler
                    // rather than substituting a textbook answer for it.
                    service?.controls?.nudgeWhiteBalanceTo(
                        Mechanism.kelvinFromProgress(kelvinProgress, 100)
                    )
                }
            }
            Mechanism.Param.ZOOM -> { zoomProgress = progress; pushZoom(progress) }
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
        if (!manualFocus) return
        val fraction = focusProgress / 100f
        val remote = link as? RemoteLink
        if (remote != null) remote.setFocusFraction(fraction)
        else service?.controls?.setFocusFraction(fraction)
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
            say(if (next == FocusDirector.Mode.AUTO) "Focus checking every 2s" else "Focus locked")
        }
        refreshFocusButton()

        binding.settingsButton.setOnClickListener { toggleVerticalPanel() }
        binding.settingsButton.setOnLongClickListener { openControlBar(); true }
        binding.gearButton.setOnClickListener { startActivity(SettingsActivity.intent(this)) }

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

        // Long press the record ring to start or stop the NDI stream, so the
        // bottom row stays two rings and a gear.
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

    private fun refreshFocusButton() {
        binding.focusModeButton.centerText =
            if (focusDirector.mode == FocusDirector.Mode.AUTO) "A" else "M"
    }

    private fun refreshLiveIndicators() {
        val svc = service ?: return

        val level = Mechanism.rmsToMeterFraction(svc.audioLevel)
        binding.vuHairline.setLevel(level)

        // The gain fader's own meter was only redrawn when the panel was
        // rebuilt, which happens when a control changes and not otherwise, so
        // it sat frozen at whatever the level had been when the panel opened.
        // It belongs on the tick with every other live reading.
        if (binding.verticalPanel.visibility == View.VISIBLE) {
            binding.vGain.meterLevel = level
        }

        binding.recordButton.ringColor =
            if (svc.isRecording) CircleButtonView.RECORDING else CircleButtonView.RECORD_IDLE
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
    private fun applyCameraSource() {
        val wantRemote = appSettings.remoteMode && appSettings.remoteSource != null
        if (wantRemote == (link?.isRemote == true) && link != null) return

        remoteEngine?.stop()
        remoteEngine = null

        if (!wantRemote) {
            binding.remoteBorder.visibility = View.GONE
            service?.controls?.let { link = LocalLink(it) }
            return
        }

        val source = appSettings.remoteSource ?: return
        val remote = RemoteLink(source)
        link = remote
        binding.remoteBorder.sourceName = source
        binding.remoteBorder.visibility = View.VISIBLE
        say("Remote: $source", transient = false)

        if (!surfaceReady) return
        // The remote picture is decoded into the same TextureView the local
        // camera draws to, so the scope reads a remote camera exactly as it
        // reads this one.
        val texture = binding.preview.surfaceTexture ?: return
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
        // Ask the camera to describe itself, and put it on the same curve, or
        // the two cameras will not cut together.
        binding.root.postDelayed({
            NdiReceiver.sendCommand(CameraCommand(requestState = true))
            remote.setLogCurve(appSettings.logCurve)
        }, 1500)
    }

    /**
     * Balance by eye, on the scope, the way it is done at a desk. The marker
     * is dragged until the cloud sits on the centre, and what that produces is
     * a change in camera gains rather than a filter on the picture: the
     * correction lives in the sensor, so it is in the stream and the recording
     * as well as on screen.
     */
    private fun setUpVectorscope() {
        binding.vectorscope.onBalanceMoved = { du, dv ->
            val controls = service?.controls
            if (controls != null) {
                // Neutral is a valid starting point. Waiting for the camera to
                // have reported gains meant a drag did nothing at all on a
                // camera that had not been asked to balance yet, which read as
                // the scope being broken.
                val base = controls.heldGains
                    ?: controls.lastAwbGains
                    ?: floatArrayOf(1f, 1f, 1f)
                controls.applyBalanceGains(Mechanism.gainsFromChromaOffset(base, du, dv))
            }
        }
        binding.vectorscope.onBalanceReleased = {
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

    private fun preparePipeline() {
        val svc = service ?: return
        val profile = activeProfile ?: return
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
            NdiFinder.start()
            val existing = NdiFinder.sources(timeoutMs = 1200)
            NdiFinder.stop()
            if (SourceIdentity.clashesWith(name, existing)) {
                runOnUiThread { say("Name already in use") }
            }
        }
    }
}
