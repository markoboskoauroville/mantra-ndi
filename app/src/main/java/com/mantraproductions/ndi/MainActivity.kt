package com.mantraproductions.ndi

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mantraproductions.ndi.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileStore: ProfileStore

    private var service: NdiSendService? = null
    private var bound = false
    private var surfaceReady = false

    private var profiles: List<CaptureProfile> = emptyList()
    private var activeProfile: CaptureProfile? = null

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
        if (results.values.all { it }) {
            bindToService()
        } else {
            binding.statusText.text = "Camera and microphone access are required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileStore = ProfileStore(this)
        profiles = profileStore.load()
        activeProfile = profileStore.selected()

        setUpProfileSpinner()
        setUpControls()

        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                service?.attachPreview(binding.preview)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                // Streaming continues in the service; only the local view goes away.
                service?.detachPreview()
            }
        })

        binding.startStopButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isStreaming) {
                svc.stopStreaming()
            } else {
                val name = binding.sourceNameInput.text?.toString()?.ifBlank { "Mantra NDI" } ?: "Mantra NDI"
                startForegroundService(Intent(this, NdiSendService::class.java))
                svc.startStreaming(name) { error ->
                    runOnUiThread { binding.statusText.text = "Error: $error" }
                }
            }
            refreshUi()
        }
    }

    override fun onStart() {
        super.onStart()
        requestPermissionsThenBind()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

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
            runOnUiThread { binding.statusText.text = error }
        }
        if (ok) {
            if (surfaceReady) svc.attachPreview(binding.preview)
            bindControlRanges()
            showLiveSensorValues()
        }
        refreshUi()
    }

    private fun setUpProfileSpinner() {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, profiles.map { it.name }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.profileSpinner.adapter = adapter

        val index = profiles.indexOfFirst { it.name == activeProfile?.name }.coerceAtLeast(0)
        binding.profileSpinner.setSelection(index)

        binding.profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val picked = profiles[pos]
                if (picked.name == activeProfile?.name) return
                activeProfile = picked
                profileStore.selectedName = picked.name
                // Resolution/fps live in prepareVideo, so a profile switch needs
                // the whole pipeline rebuilt rather than a live tweak.
                binding.statusText.text = "Switched to ${picked.name} — restart streaming to apply"
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

    private fun setUpControls() {
        binding.manualExposureCheck.setOnCheckedChangeListener { _, checked ->
            val controls = service?.controls ?: return@setOnCheckedChangeListener
            if (checked) {
                if (!controls.supportsManualSensor()) {
                    binding.manualExposureCheck.isChecked = false
                    binding.statusText.text = "This camera doesn't report MANUAL_SENSOR"
                    return@setOnCheckedChangeListener
                }
                applyManualExposure()
            } else {
                controls.setAutoExposure()
            }
            binding.isoSeek.isEnabled = checked
            binding.shutterSeek.isEnabled = checked
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && binding.manualExposureCheck.isChecked) applyManualExposure()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        binding.isoSeek.setOnSeekBarChangeListener(listener)
        binding.shutterSeek.setOnSeekBarChangeListener(listener)

        binding.stabilizationCheck.setOnCheckedChangeListener { _, checked ->
            service?.controls?.setStabilization(checked)
        }

        binding.isoSeek.isEnabled = false
        binding.shutterSeek.isEnabled = false
    }

    /** Map the seekbars onto whatever this particular sensor actually supports. */
    private fun bindControlRanges() {
        val controls = service?.controls ?: return
        val iso = controls.isoRange()
        val exposure = controls.exposureTimeRange()

        if (iso != null) {
            binding.isoSeek.max = (iso.upper - iso.lower).coerceAtLeast(1)
            binding.isoSeek.progress = ((iso.lower + iso.upper) / 4).coerceIn(0, binding.isoSeek.max)
        }
        if (exposure != null) {
            // 200 steps across the usable shutter range, log-ish feel via squaring.
            binding.shutterSeek.max = 200
            val profile = activeProfile
            val target = profile?.shutter180Ns() ?: 20_000_000L
            binding.shutterSeek.progress = shutterToProgress(target, exposure.lower, exposure.upper)
        }
        updateControlLabels()
    }

    private fun applyManualExposure() {
        val controls = service?.controls ?: return
        val isoRange = controls.isoRange() ?: return
        val exposureRange = controls.exposureTimeRange() ?: return

        val iso = isoRange.lower + binding.isoSeek.progress
        val shutter = progressToShutter(binding.shutterSeek.progress, exposureRange.lower, exposureRange.upper)
        controls.setManualExposure(iso.coerceIn(isoRange.lower, isoRange.upper), shutter)
        updateControlLabels()
    }

    private fun updateControlLabels() {
        val controls = service?.controls
        val isoRange = controls?.isoRange()
        val exposureRange = controls?.exposureTimeRange()

        if (isoRange != null) {
            binding.isoLabel.text = "ISO ${isoRange.lower + binding.isoSeek.progress}"
        }
        if (exposureRange != null) {
            val ns = progressToShutter(binding.shutterSeek.progress, exposureRange.lower, exposureRange.upper)
            val denominator = (1_000_000_000.0 / ns).roundToInt()
            binding.shutterLabel.text = "Shutter 1/$denominator"
        }
    }

    private fun shutterToProgress(ns: Long, min: Long, max: Long): Int {
        val clamped = ns.coerceIn(min, max)
        val fraction = (clamped - min).toDouble() / (max - min).coerceAtLeast(1).toDouble()
        return (Math.sqrt(fraction) * 200).roundToInt().coerceIn(0, 200)
    }

    private fun progressToShutter(progress: Int, min: Long, max: Long): Long {
        val fraction = (progress / 200.0).let { it * it }
        return (min + (fraction * (max - min))).toLong().coerceIn(min, max)
    }

    private fun showLiveSensorValues() {
        service?.controls?.observeSensorValues { iso, exposureNs ->
            if (binding.manualExposureCheck.isChecked) return@observeSensorValues
            val shutter = exposureNs?.let { "1/${(1_000_000_000.0 / it).roundToInt()}" } ?: "-"
            runOnUiThread {
                binding.isoLabel.text = "ISO ${iso ?: "-"} (auto)"
                binding.shutterLabel.text = "Shutter $shutter (auto)"
            }
        }
    }

    private fun refreshUi() {
        val streaming = service?.isStreaming == true
        binding.startStopButton.text = if (streaming) "Stop streaming" else "Start streaming"
        binding.sourceNameInput.isEnabled = !streaming
        binding.profileSpinner.isEnabled = !streaming
        if (streaming) {
            binding.statusText.text = "Streaming as \"${binding.sourceNameInput.text}\""
        } else if (binding.statusText.text.startsWith("Streaming")) {
            binding.statusText.text = "Idle"
        }
    }
}
