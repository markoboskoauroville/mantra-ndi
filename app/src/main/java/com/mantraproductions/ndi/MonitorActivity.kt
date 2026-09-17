package com.mantraproductions.ndi

import android.content.Intent
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.mantraproductions.ndi.databinding.ActivityMonitorBinding
import kotlin.concurrent.thread

/**
 * Monitor mode: find NDI sources on the network and play one back.
 *
 * Discovery runs on its own thread because the SDK's find call blocks until
 * the source list changes.
 */
class MonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitorBinding
    private lateinit var adapter: ArrayAdapter<String>

    private var engine: MonitorEngine? = null
    private var sources: List<String> = emptyList()
    private var discovering = false
    private var surfaceReady = false

    /** Capabilities reported by the camera, so sliders match that sensor. */
    private var cameraState: CameraState? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf())
        binding.sourceList.adapter = adapter

        binding.videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopPlayback()
            }
        })

        binding.modeButton.setOnClickListener {
            stopPlayback()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.refreshButton.setOnClickListener { refreshSources() }

        setUpRemoteControls()

        binding.connectButton.setOnClickListener {
            val position = binding.sourceList.checkedItemPosition
            if (position < 0 || position >= sources.size) {
                binding.statusText.text = "Pick a source first"
                return@setOnClickListener
            }
            if (engine != null) {
                stopPlayback()
                binding.connectButton.text = "Connect"
                binding.statusText.text = "Stopped"
            } else {
                connectTo(sources[position])
            }
        }

        if (!NdiReceiver.available) {
            binding.statusText.text = "Built without the NDI SDK, monitor unavailable"
            binding.connectButton.isEnabled = false
            binding.refreshButton.isEnabled = false
        }
    }

    override fun onStart() {
        super.onStart()
        if (NdiFinder.available) {
            NdiFinder.start()
            refreshSources()
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
        NdiFinder.stop()
    }

    private fun refreshSources() {
        if (discovering || !NdiFinder.available) return
        discovering = true
        binding.statusText.text = "Looking for sources"

        thread(name = "ndi-discovery") {
            val found = NdiFinder.sources(timeoutMs = 3000)
            runOnUiThread {
                discovering = false
                sources = found
                adapter.clear()
                adapter.addAll(found)
                adapter.notifyDataSetChanged()
                binding.statusText.text = when {
                    found.isEmpty() -> "No sources found. Same network as the sender?"
                    else -> "${found.size} source${if (found.size == 1) "" else "s"} found"
                }
            }
        }
    }

    private fun connectTo(sourceName: String) {
        if (!surfaceReady) {
            binding.statusText.text = "Display not ready yet"
            return
        }
        val monitor = MonitorEngine(
            surface = binding.videoSurface.holder.surface,
            onStatus = { message -> runOnUiThread { binding.statusText.text = message } },
            onCameraState = { state -> runOnUiThread { applyCameraState(state) } }
        )
        monitor.start(sourceName)
        engine = monitor
        binding.connectButton.text = "Disconnect"
        // Ask the camera what it can do, so the sliders map to its real ranges
        // instead of guessed ones. Give the connection a moment to establish.
        binding.root.postDelayed({
            NdiReceiver.sendCommand(CameraCommand(requestState = true))
        }, 1500)
    }

    private fun setUpRemoteControls() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateRemoteLabels()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            // Only send on release: dragging would otherwise fire a command per pixel.
            override fun onStopTrackingTouch(sb: SeekBar?) = sendExposure()
        }
        binding.remoteIsoSeek.setOnSeekBarChangeListener(listener)
        binding.remoteShutterSeek.setOnSeekBarChangeListener(listener)

        binding.remoteAutoExposure.setOnCheckedChangeListener { _, checked ->
            binding.remoteIsoSeek.isEnabled = !checked && cameraState?.manualSupported == true
            binding.remoteShutterSeek.isEnabled = !checked && cameraState?.manualSupported == true
            if (checked) {
                NdiReceiver.sendCommand(CameraCommand(exposureMode = "auto"))
            } else {
                sendExposure()
            }
        }

        binding.recordButton.setOnClickListener {
            val action = if (recording) "stop" else "start"
            if (NdiReceiver.sendCommand(CameraCommand(record = action))) {
                binding.statusText.text =
                    if (recording) "Asked camera to stop recording" else "Asked camera to record"
            } else {
                binding.statusText.text = "Command failed, is the camera still connected?"
            }
        }
    }

    private fun applyCameraState(state: CameraState) {
        cameraState = state
        recording = state.recording

        binding.recordButton.isEnabled = true
        binding.recordButton.text = if (state.recording) "Stop recording" else "Record on camera"
        if (state.cameraName.isNotEmpty()) {
            binding.statusText.text = "Controlling ${state.cameraName}"
        }

        val manual = state.manualSupported
        binding.remoteAutoExposure.isEnabled = manual
        if (!manual) {
            binding.remoteIsoLabel.text = "ISO (camera has no manual sensor)"
            return
        }
        binding.remoteIsoSeek.max = (state.isoMax - state.isoMin).coerceAtLeast(1)
        binding.remoteShutterSeek.max = 200
        state.iso?.let { binding.remoteIsoSeek.progress = (it - state.isoMin).coerceAtLeast(0) }
        updateRemoteLabels()
    }

    private fun sendExposure() {
        val state = cameraState ?: return
        if (binding.remoteAutoExposure.isChecked || !state.manualSupported) return
        NdiReceiver.sendCommand(
            CameraCommand(
                iso = state.isoMin + binding.remoteIsoSeek.progress,
                shutterNs = progressToShutter(binding.remoteShutterSeek.progress, state),
                exposureMode = "manual"
            )
        )
    }

    private fun updateRemoteLabels() {
        val state = cameraState ?: return
        binding.remoteIsoLabel.text = "ISO ${state.isoMin + binding.remoteIsoSeek.progress}"
        val ns = progressToShutter(binding.remoteShutterSeek.progress, state)
        if (ns > 0) {
            binding.remoteShutterLabel.text = "Shutter 1/${(1_000_000_000.0 / ns).toInt()}"
        }
    }

    private fun progressToShutter(progress: Int, state: CameraState): Long {
        if (state.shutterMaxNs <= state.shutterMinNs) return 0
        val fraction = (progress / 200.0).let { it * it }
        return (state.shutterMinNs + fraction * (state.shutterMaxNs - state.shutterMinNs)).toLong()
    }

    private fun stopPlayback() {
        engine?.stop()
        engine = null
        runOnUiThread { binding.connectButton.text = "Connect" }
    }
}
