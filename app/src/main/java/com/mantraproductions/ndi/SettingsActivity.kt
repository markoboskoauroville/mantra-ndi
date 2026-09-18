package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mantraproductions.ndi.databinding.ActivitySettingsBinding
import java.io.File

/**
 * Everything that is not touched while shooting.
 *
 * Laid out like the phone's own settings on purpose: grouped cards, a title
 * and its current value on every row. Nothing here needs learning, and none of
 * it sits on top of the image.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppSettings
    private lateinit var profileStore: ProfileStore
    private lateinit var identity: SourceIdentity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppSettings(this)
        profileStore = ProfileStore(this)
        identity = SourceIdentity(this)

        binding.rowProfile.setOnClickListener { pickProfile() }
        binding.rowSourceName.setOnClickListener { editSourceName() }
        binding.rowBitDepth.setOnClickListener { pickBitDepth() }
        binding.rowLogCurve.setOnClickListener { pickLogCurve() }
        binding.rowExportLut.setOnClickListener { exportLut() }
        binding.rowLoadLut.setOnClickListener { loadLut() }
        binding.rowHistogram.setOnClickListener {
            prefs.histogramVisible = !prefs.histogramVisible
            refresh()
        }
        binding.rowStabilisation.setOnClickListener {
            prefs.stabilisation = !prefs.stabilisation
            refresh()
        }
        binding.rowCamera.setOnClickListener { showCameraCapabilities() }

        refresh()
    }

    private fun refresh() {
        binding.valueProfile.text = profileStore.selected().let {
            "${it.name}, ${it.width}x${it.height} at ${it.fps}"
        }
        binding.valueSourceName.text = identity.name
        binding.valueBitDepth.text = when {
            !prefs.tenBitWanted -> "8-bit"
            else -> "10-bit HDR where the camera allows it"
        }
        binding.valueLogCurve.text = prefs.logCurve.let {
            if (it == LogCurves.Curve.REC709) "None, straight Rec.709"
            else "${it.vendor} ${it.displayName}, grey at ${"%.3f".format(LogCurves.middleGrey(it))}"
        }
        binding.valueHistogram.text = if (prefs.histogramVisible) "Shown" else "Hidden"
        binding.valueStabilisation.text = if (prefs.stabilisation) "On" else "Off"
        binding.valueCamera.text = capabilitySummary()
        binding.valueAbout.text = "Mantra NDI v${BuildConfig.VERSION_NAME}" +
                if (BuildConfig.NDI_SDK_PRESENT) "" else ", built without the NDI SDK"
        binding.valueLoadLut.text = prefs.monitorLutName ?: "None loaded"
    }

    private fun pickProfile() {
        val profiles = profileStore.load()
        choose("Capture profile", profiles.map { it.name }) { index ->
            profileStore.selectedName = profiles[index].name
            refresh()
        }
    }

    private fun pickBitDepth() {
        choose("Bit depth", listOf("8-bit", "10-bit HDR where available")) { index ->
            prefs.tenBitWanted = index == 1
            refresh()
        }
    }

    private fun pickLogCurve() {
        val curves = LogCurves.Curve.values()
        val labels = curves.map {
            if (it == LogCurves.Curve.REC709) "None, straight Rec.709"
            else "${it.vendor} ${it.displayName}"
        }
        choose("Log curve", labels) { index ->
            prefs.logCurve = curves[index]
            refresh()
        }
    }

    /**
     * Written out when asked for, computed from the curve's constants. Nothing
     * is stored in the app, so any curve at any size is available and none of
     * them cost space until somebody wants one.
     */
    private fun exportLut() {
        val from = prefs.logCurve
        if (from == LogCurves.Curve.REC709) {
            toast("Pick a log curve first, there is nothing to undo")
            return
        }
        choose("LUT size", listOf("17, small and fast", "33, the usual", "65, finest")) { index ->
            val size = listOf(17, 33, 65)[index]
            try {
                val name = CubeLut.fileName(from, LogCurves.Curve.REC709, size)
                val target = MediaStoreOutput.writeText(
                    this, name, CubeLut.generate(from, LogCurves.Curve.REC709, size)
                )
                if (target == null) toast("Could not write the LUT")
                else toast("Written to ${target.shortLocation}")
            } catch (e: Exception) {
                toast("Could not write the LUT: ${e.message}")
            }
        }
    }

    private fun loadLut() {
        // A picker belongs here; until then the app reads whatever the operator
        // has dropped into its own LUTs folder, which needs no permission.
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            ),
            MediaStoreOutput.FOLDER
        )
        val cubes = dir.listFiles { f -> f.name.endsWith(".cube", ignoreCase = true) }
        if (cubes.isNullOrEmpty()) {
            toast("Put a .cube file in DCIM/${MediaStoreOutput.FOLDER}")
            return
        }
        choose("Monitor LUT", listOf("None") + cubes.map { it.name }) { index ->
            if (index == 0) {
                prefs.monitorLutName = null
                refresh()
                return@choose
            }
            val file = cubes[index - 1]
            val parsed = CubeLut.parse(file.readText())
            if (parsed == null) {
                toast("That file is not a 3D cube LUT this app can read")
            } else {
                prefs.monitorLutName = "${file.name}, ${parsed.size} cube"
                refresh()
            }
        }
    }

    private fun showCameraCapabilities() {
        AlertDialog.Builder(this)
            .setTitle("This camera")
            .setMessage(capabilityDetail())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun capabilitySummary(): String = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull() ?: return "No camera"
        HdrCapabilities(manager.getCameraCharacteristics(id)).summary()
    } catch (e: Exception) {
        "Unavailable"
    }

    private fun capabilityDetail(): String = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        buildString {
            for (id in manager.cameraIdList) {
                val caps = HdrCapabilities(manager.getCameraCharacteristics(id))
                append("Camera ").append(id).append('\n')
                append("  ").append(caps.summary()).append('\n')
                append("  10-bit profiles: ").append(caps.supportedProfiles.size).append('\n')
                append("  tone curve points: ").append(caps.maxCurvePoints).append("\n\n")
            }
        }
    } catch (e: Exception) {
        "Could not read the camera: ${e.message}"
    }

    private fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
            ) { _, which -> onPick(which) }
            .show()
    }

    private fun editSourceName() {
        val input = android.widget.EditText(this).apply { setText(identity.name) }
        AlertDialog.Builder(this)
            .setTitle("NDI source name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                identity.name = input.text.toString()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
}
