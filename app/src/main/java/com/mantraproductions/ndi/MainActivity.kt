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
import android.view.SurfaceHolder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
    private var manualExposure = false

    private val pump = ControlPump()
    private val ui = Handler(Looper.getMainLooper())
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
        setUpActions()

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

    override fun onBackPressed() {
        if (binding.paramBar.visibility == View.VISIBLE) closeControlBar()
        else super.onBackPressed()
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
                binding.statusText.text = "RECORDING SAVED"
            } else if (svc.startRecording()) {
                binding.statusText.text = "RECORDING"
            } else {
                binding.statusText.text = "GO LIVE FIRST"
            }
        }

        // Long press the record ring to start or stop the NDI stream, so the
        // bottom row stays two rings and a gear.
        binding.recordButton.setOnLongClickListener {
            val svc = service ?: return@setOnLongClickListener true
            if (svc.isStreaming) {
                svc.stopStreaming()
                binding.statusText.text = "IDLE"
            } else {
                val name = SourceIdentity.sanitize(identity.name)
                warnIfNameTaken(name)
                startForegroundService(Intent(this, NdiSendService::class.java))
                svc.startStreaming(name) { error ->
                    runOnUiThread { binding.statusText.text = error.uppercase() }
                }
                binding.statusText.text = "LIVE AS ${name.uppercase()}"
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
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun bindToService() {
        bindService(Intent(this, NdiSendService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun preparePipeline() {
        val svc = service ?: return
        val profile = activeProfile ?: return
        val ok = svc.prepare(profile) { error ->
            runOnUiThread { binding.statusText.text = error.uppercase() }
        }
        if (ok) {
            if (surfaceReady) svc.attachPreview(binding.preview)
            bindControlRanges()
            bindPump()
        }
    }

    private fun warnIfNameTaken(name: String) {
        if (!NdiFinder.available) return
        thread(name = "ndi-name-check") {
            NdiFinder.start()
            val existing = NdiFinder.sources(timeoutMs = 1200)
            NdiFinder.stop()
            if (SourceIdentity.clashesWith(name, existing)) {
                runOnUiThread { binding.statusText.text = "NAME ALREADY IN USE" }
            }
        }
    }
}
