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
import android.view.SurfaceHolder
import android.view.View
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
    private var gainProgress = 50

    private val pump = ControlPump()
    private val ui = Handler(Looper.getMainLooper())
    private val clearStatus = Runnable { binding.statusText.text = defaultStatus() }
    private val tick = object : Runnable {
        override fun run() {
            refreshLiveIndicators()
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

        setUpControlBar()
        setUpVerticalPanel()
        setUpActions()

        binding.preview.setOnClickListener { toggleVerticalPanel() }

        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                service?.attachPreview(binding.preview)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                service?.detachPreview()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        pump.start()
        requestPermissionsThenBind()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed the profile while we were away.
        val picked = profileStore.selected()
        if (picked.name != activeProfile?.name) {
            activeProfile = picked
            service?.let { if (!it.isStreaming) { it.releasePipeline(); preparePipeline() } }
        }
    }

    override fun onStop() {
        super.onStop()
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

        // Held down: record, so a take can start without looking at the screen.
        if (event.repeatCount == 3) {
            toggleRecording()
            return true
        }
        if (event.repeatCount > 0) return true

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
        return true
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
        val svc = service ?: return
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

    private fun setUpVerticalPanel() {
        val faders = listOf(
            binding.vIso to Mechanism.Param.ISO,
            binding.vShutter to Mechanism.Param.SHUTTER,
            binding.vWhiteBalance to Mechanism.Param.WHITE_BALANCE,
            binding.vZoom to Mechanism.Param.ZOOM
        )
        faders.forEach { (fader, which) ->
            fader.label = which.label
            fader.onChange = { onVerticalMoved(which, it) }
            fader.onRelease = { pump.flush() }
            fader.onTouchedWhileAutomatic = { leaveAuto(which) }
            fader.setOnTouchListener { _, _ -> focusedColumn = which; false }
        }

        binding.vGain.label = "Gain"
        binding.vGain.max = 100
        binding.vGain.showsCentre = true
        binding.vGain.progress = gainProgress
        binding.vGain.onChange = { gainProgress = it; applyGain() }
        binding.aGain.symbol = "A"
        binding.aGain.ringColor = CircleButtonView.IDLE
        binding.aGain.setOnClickListener {
            gainProgress = 50
            applyGain()
            refreshVerticalPanel()
            say("Gain back to unity")
        }

        binding.vFocus.label = "Focus"
        binding.vFocus.max = 100
        binding.vFocus.onChange = { focusProgress = it; pushFocus() }
        binding.vFocus.onRelease = { pump.flush() }
        binding.vFocus.onTouchedWhileAutomatic = { leaveAuto(null) }

        // An A over each column. Pressing it asks the camera what it would
        // choose, drops that value onto the fader, and stays manual. It is a
        // recommendation, not a mode: the operator keeps the control and starts
        // from somewhere sensible instead of from wherever the fader was left.
        listOf(
            binding.aIso to Mechanism.Param.ISO,
            binding.aShutter to Mechanism.Param.SHUTTER,
            binding.aWhiteBalance to Mechanism.Param.WHITE_BALANCE,
            binding.aZoom to Mechanism.Param.ZOOM
        ).forEach { (button, which) ->
            button.symbol = "A"
            button.ringColor = CircleButtonView.IDLE
            button.setOnClickListener { suggestFor(which) }
        }
        binding.aFocus.symbol = "A"
        binding.aFocus.ringColor = CircleButtonView.IDLE
        binding.aFocus.setOnClickListener { focusOnSquare() }

        binding.focusSquare.onMoved = { _, _ ->
            binding.focusSquare.state = FocusSquareView.State.IDLE
        }

        binding.autoModeCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) returnToAuto() else leaveAuto(null)
        }
    }

    private fun toggleVerticalPanel() {
        if (binding.verticalPanel.visibility == View.VISIBLE) {
            binding.verticalPanel.visibility = View.GONE
            binding.autoModeCheck.visibility = View.GONE
            binding.focusSquare.visibility = View.GONE
            return
        }
        // The single fader and the full panel are two answers to the same
        // question, so never both.
        if (binding.paramBar.visibility == View.VISIBLE) closeControlBar()
        seedFromCamera()
        binding.verticalPanel.visibility = View.VISIBLE
        binding.autoModeCheck.visibility = View.VISIBLE
        if (service?.controls?.supportsManualFocus() == true) {
            binding.focusSquare.visibility = View.VISIBLE
        }
        refreshVerticalPanel()
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
        say("Auto, finding exposure")
        // Give the routine a moment to settle, then take its answer.
        ui.postDelayed({ seedFromCamera(); refreshVerticalPanel() }, 900)
    }

    private fun leaveAuto(which: Mechanism.Param?) {
        val controls = service?.controls ?: return
        binding.autoModeCheck.isChecked = false
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
                kelvinProgress = progress
                if (manualWhiteBalance) {
                    pump.setKelvin(Mechanism.kelvinFromProgress(progress, 100))
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
    private fun suggestFor(which: Mechanism.Param) {
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
                    say("Set from auto, still manual")
                }, 800)
            }

            Mechanism.Param.WHITE_BALANCE -> {
                controls.setAutoWhiteBalance()
                say("Reading the light")
                ui.postDelayed({
                    // Auto white balance reports no Kelvin, so the daylight
                    // anchor is the honest starting point to hand back.
                    kelvinProgress = Mechanism.progressForKelvin(
                        Mechanism.KELVIN_WORKING_CENTRE, 100
                    )
                    manualWhiteBalance = true
                    controls.setManualWhiteBalance(
                        Mechanism.kelvinFromProgress(kelvinProgress, 100)
                    )
                    refreshVerticalPanel()
                    say("Set to ${Mechanism.KELVIN_WORKING_CENTRE}K, still manual")
                }, 700)
            }

            Mechanism.Param.ZOOM -> {
                zoomProgress = 0
                pushZoom(0)
                refreshVerticalPanel()
            }
        }
    }

    /**
     * Focus where the box is, then hold it. Focus as a place rather than a
     * number: nobody thinks in dioptres, everybody can point at a face.
     */
    private fun focusOnSquare() {
        val controls = service?.controls ?: return
        if (binding.focusSquare.visibility != View.VISIBLE) {
            binding.focusSquare.visibility = View.VISIBLE
        }
        binding.focusSquare.state = FocusSquareView.State.SEEKING
        say("Focusing")

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
        service?.controls?.setFocusFraction(focusProgress / 100f)
    }

    // --- live actions -------------------------------------------------------

    private fun setUpActions() {
        binding.settingsButton.symbol = "\u2261"
        binding.gearButton.symbol = "\u2699"

        binding.settingsButton.setOnClickListener { openControlBar() }
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

    private fun refreshLiveIndicators() {
        val svc = service ?: return

        binding.vuHairline.setLevel(Mechanism.rmsToMeterFraction(svc.audioLevel))

        binding.recordButton.ringColor =
            if (svc.isRecording) CircleButtonView.RECORDING else CircleButtonView.IDLE
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
