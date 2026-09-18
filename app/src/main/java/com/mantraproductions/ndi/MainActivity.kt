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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mantraproductions.ndi.databinding.ActivityMainBinding
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Camera mode.
 *
 * The image owns the screen. Settings live in a drawer that slides in over it
 * and closes when you touch the picture again, because on a shoot the thing
 * you look at is the frame, not the controls.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileStore: ProfileStore
    private lateinit var identity: SourceIdentity

    private var service: NdiSendService? = null
    private var bound = false
    private var surfaceReady = false

    private var profiles: List<CaptureProfile> = emptyList()
    private var activeProfile: CaptureProfile? = null

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

        identity = SourceIdentity(this)
        binding.sourceNameInput.setText(identity.name)

        profileStore = ProfileStore(this)
        profiles = profileStore.load()
        activeProfile = profileStore.selected()

        setUpDrawer()
        setUpProfileSpinner()
        setUpDials()
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
        requestPermissionsThenBind()
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onBackPressed() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) closeSettings()
        else super.onBackPressed()
    }

    // --- drawer ---

    private fun setUpDrawer() {
        binding.settingsButton.setOnClickListener { toggleSettings() }
        // The settings ring doubles as the audio meter, so nothing extra sits
        // on the frame just to show level.
        binding.settingsButton.showsLevel = true
    }

    /** The same ring opens and closes; nothing else on screen changes role. */
    private fun toggleSettings() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) closeSettings() else openSettings()
    }

    private fun openSettings() {
        binding.settingsOverlay.alpha = 0f
        binding.settingsOverlay.visibility = View.VISIBLE
        binding.settingsOverlay.animate().alpha(1f).setDuration(180).start()
        binding.settingsButton.ringColor = CircleButtonView.ACTIVE
        binding.settingsButton.glow = true
    }

    private fun closeSettings() {
        binding.settingsOverlay.animate().alpha(0f).setDuration(150).withEndAction {
            binding.settingsOverlay.visibility = View.GONE
        }.start()
        binding.settingsButton.ringColor = CircleButtonView.IDLE
        binding.settingsButton.glow = false

        // Name edits commit on close rather than needing a separate button.
        val name = SourceIdentity.sanitize(binding.sourceNameInput.text?.toString().orEmpty())
        binding.sourceNameInput.setText(name)
        identity.name = name
    }

    // --- controls ---

    private fun setUpDials() {
        binding.isoFader.label = "ISO"
        binding.shutterFader.label = "Shutter"
        binding.wbFader.label = "White balance"
        binding.zoomFader.label = "Zoom"

        binding.isoFader.onChange = { updateExposureLabels() }
        binding.isoFader.onRelease = { applyManualExposure() }
        binding.shutterFader.onChange = { updateExposureLabels() }
        binding.shutterFader.onRelease = { applyManualExposure() }

        binding.wbFader.max = ProControls.KELVIN_MAX - ProControls.KELVIN_MIN
        binding.wbFader.progress = 5600 - ProControls.KELVIN_MIN
        binding.wbFader.valueText = "5600 K"
        binding.wbFader.onChange = { binding.wbFader.valueText = "${ProControls.KELVIN_MIN + it} K" }
        binding.wbFader.onRelease = { applyWhiteBalance() }

        binding.zoomFader.max = 100
        binding.zoomFader.valueText = "1.0x"
        binding.zoomFader.onChange = { applyZoom(it) }

        binding.isoFader.isEnabled = false
        binding.shutterFader.isEnabled = false
        binding.wbFader.isEnabled = false

        binding.manualWbCheck.setOnCheckedChangeListener { _, checked ->
            val controls = service?.controls
            if (checked && controls?.supportsManualWhiteBalance() != true) {
                binding.manualWbCheck.isChecked = false
                binding.statusText.text = "No manual white balance on this camera"
                return@setOnCheckedChangeListener
            }
            binding.wbFader.isEnabled = checked
            if (checked) applyWhiteBalance() else controls?.setAutoWhiteBalance()
        }

        binding.manualExposureCheck.setOnCheckedChangeListener { _, checked ->
            val controls = service?.controls ?: return@setOnCheckedChangeListener
            if (checked && !controls.supportsManualSensor()) {
                binding.manualExposureCheck.isChecked = false
                binding.statusText.text = "This camera has no manual sensor control"
                return@setOnCheckedChangeListener
            }
            binding.isoFader.isEnabled = checked
            binding.shutterFader.isEnabled = checked
            if (checked) applyManualExposure() else controls.setAutoExposure()
        }

        binding.stabilizationCheck.setOnCheckedChangeListener { _, checked ->
            service?.controls?.setStabilization(checked)
        }
    }

    private fun bindControlRanges() {
        val controls = service?.controls ?: return
        controls.isoRange()?.let { range ->
            binding.isoFader.max = (range.upper - range.lower).coerceAtLeast(1)
            binding.isoFader.progress = ((range.upper - range.lower) / 4)
        }
        controls.exposureTimeRange()?.let {
            binding.shutterFader.max = 200
            binding.shutterFader.progress = 100
        }
        controls.zoomRange().let { range ->
            binding.zoomFader.valueText = String.format("%.1fx", range.lower)
        }
        updateExposureLabels()
    }

    private fun applyManualExposure() {
        val controls = service?.controls ?: return
        if (!binding.manualExposureCheck.isChecked) return
        val isoRange = controls.isoRange() ?: return
        val exposureRange = controls.exposureTimeRange() ?: return
        controls.setManualExposure(
            (isoRange.lower + binding.isoFader.progress).coerceIn(isoRange.lower, isoRange.upper),
            progressToShutter(binding.shutterFader.progress, exposureRange.lower, exposureRange.upper)
        )
        updateExposureLabels()
    }

    private fun updateExposureLabels() {
        val controls = service?.controls
        controls?.isoRange()?.let {
            binding.isoFader.valueText = "${it.lower + binding.isoFader.progress}"
        }
        controls?.exposureTimeRange()?.let {
            val ns = progressToShutter(binding.shutterFader.progress, it.lower, it.upper)
            binding.shutterFader.valueText = "1/${Mechanism.shutterDenominator(ns)}"
        }
    }

    private fun applyZoom(progress: Int) {
        val controls = service?.controls ?: return
        val range = controls.zoomRange()
        val zoom = range.lower + (progress / 100f) * (range.upper - range.lower)
        controls.setZoom(zoom)
        binding.zoomFader.valueText = String.format("%.1fx", zoom)
    }

    private fun progressToShutter(progress: Int, min: Long, max: Long): Long =
        Mechanism.shutterFromProgress(progress, 200, min, max)

    private fun applyWhiteBalance() {
        val kelvin = ProControls.KELVIN_MIN + binding.wbFader.progress
        service?.controls?.setManualWhiteBalance(kelvin)
        binding.wbFader.valueText = "$kelvin K"
    }

    // --- live actions ---

    private fun setUpActions() {
        binding.modeButton.setOnClickListener {
            service?.let { svc -> if (svc.isStreaming) svc.stopStreaming() }
            startActivity(Intent(this, MonitorActivity::class.java))
            finish()
        }

        binding.startStopButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isStreaming) {
                svc.stopStreaming()
            } else {
                val name = SourceIdentity.sanitize(binding.sourceNameInput.text?.toString().orEmpty())
                identity.name = name
                warnIfNameTaken(name)
                startForegroundService(Intent(this, NdiSendService::class.java))
                svc.startStreaming(name) { error ->
                    runOnUiThread { binding.statusText.text = "Error: $error" }
                }
            }
            refreshUi()
        }

        binding.recordButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isRecording) {
                svc.stopRecording()
                binding.statusText.text = "Recording saved"
            } else if (svc.startRecording()) {
                binding.statusText.text = "Recording"
            } else {
                binding.statusText.text = "Could not start recording, go live first"
            }
        }
    }

    /** Ten times a second, cheap enough and keeps the meter feeling live. */
    private fun refreshLiveIndicators() {
        val svc = service ?: return
        binding.settingsButton.level = Mechanism.rmsToMeterFraction(svc.audioLevel)

        binding.recordButton.ringColor =
            if (svc.isRecording) CircleButtonView.RECORDING else CircleButtonView.IDLE
        binding.recordButton.glow = svc.isRecording
        binding.recordTimer.visibility = if (svc.isRecording) View.VISIBLE else View.INVISIBLE
        if (svc.isRecording) {
            val seconds = svc.recordingElapsedSeconds
            binding.recordTimer.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
        }
        binding.tallyBorder.state = when {
            !svc.isStreaming -> TallyBorderView.State.OFF
            svc.tally < 0 -> TallyBorderView.State.OFF
            svc.tally and 1 != 0 -> TallyBorderView.State.PROGRAM
            svc.tally and 2 != 0 -> TallyBorderView.State.PREVIEW
            else -> TallyBorderView.State.CONNECTED
        }
    }

    // --- plumbing ---

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
        val ok = svc.prepare(profile) { error -> runOnUiThread { binding.statusText.text = error } }
        if (ok) {
            if (surfaceReady) svc.attachPreview(binding.preview)
            bindControlRanges()
        }
        refreshUi()
    }

    private fun setUpProfileSpinner() {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, profiles.map { it.name }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.profileSpinner.adapter = adapter
        binding.profileSpinner.setSelection(
            profiles.indexOfFirst { it.name == activeProfile?.name }.coerceAtLeast(0)
        )

        binding.profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val picked = profiles[pos]
                if (picked.name == activeProfile?.name) return
                activeProfile = picked
                profileStore.selectedName = picked.name
                binding.statusText.text = "${picked.name} selected"
                service?.let { svc ->
                    if (!svc.isStreaming) {
                        svc.releasePipeline()
                        preparePipeline()
                    }
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun warnIfNameTaken(name: String) {
        if (!NdiFinder.available) return
        thread(name = "ndi-name-check") {
            NdiFinder.start()
            val existing = NdiFinder.sources(timeoutMs = 1200)
            NdiFinder.stop()
            if (SourceIdentity.clashesWith(name, existing)) {
                runOnUiThread {
                    binding.statusText.text = "Another source is already called \"$name\""
                }
            }
        }
    }

    private fun refreshUi() {
        val streaming = service?.isStreaming == true
        binding.startStopButton.text = if (streaming) "Stop" else "Go live"
        binding.sourceNameInput.isEnabled = !streaming
        binding.profileSpinner.isEnabled = !streaming
        if (streaming) binding.statusText.text = "Live as \"${identity.name}\""
    }
}
