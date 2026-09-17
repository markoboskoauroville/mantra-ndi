package com.mantraproductions.ndi

import android.content.Intent
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.ArrayAdapter
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
        val monitor = MonitorEngine(binding.videoSurface.holder.surface) { message ->
            runOnUiThread { binding.statusText.text = message }
        }
        monitor.start(sourceName)
        engine = monitor
        binding.connectButton.text = "Disconnect"
    }

    private fun stopPlayback() {
        engine?.stop()
        engine = null
        runOnUiThread { binding.connectButton.text = "Connect" }
    }
}
