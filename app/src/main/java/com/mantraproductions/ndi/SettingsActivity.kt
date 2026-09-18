package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
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

    /**
     * The system picker, so a LUT can come from anywhere: Downloads, Drive, a
     * USB stick, wherever the colourist sent it. Going through the picker also
     * means no storage permission is involved, because the operator granting
     * access to one file is the permission.
     */
    private val lutPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            val parsed = text?.let { CubeLut.parse(it) }
            if (parsed == null) {
                toast("That file is not a 3D cube LUT this app can read")
            } else {
                // Copied in, because a picked uri is not guaranteed to still
                // resolve next launch and a monitor LUT has to survive one.
                val copy = File(lutFolder(), uri.lastPathSegment?.substringAfterLast('/')
                    ?.takeIf { it.endsWith(".cube", true) } ?: "monitor.cube")
                copy.writeText(text)
                prefs.monitorLutName = "${copy.name}, ${parsed.size} cube"
                refresh()
                toast("Loaded ${parsed.title}, ${parsed.size} cube")
            }
        } catch (e: Exception) {
            toast("Could not read that file: ${e.message}")
        }
    }

    private fun lutFolder(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            ),
            MediaStoreOutput.FOLDER
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

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
        binding.rowDetect.setOnClickListener { showFullReport() }
        binding.rowKeys.setOnClickListener { showKeys() }

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
        binding.valueKeys.text = if (KeyService.isEnabled(this)) {
            "On. Volume rocker drives the camera"
        } else {
            "Off. Tap to enable, then the rocker works everywhere"
        }
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
        val known = lutFolder().listFiles { f -> f.name.endsWith(".cube", true) }.orEmpty()
        val options = listOf("Browse the phone\u2026", "None") + known.map { it.name }
        choose("Monitor LUT", options) { index ->
            when (index) {
                // Any MIME, because .cube is routinely served as octet-stream
                // and filtering on it hides the file the operator came for.
                0 -> lutPicker.launch(arrayOf("*/*"))
                1 -> { prefs.monitorLutName = null; refresh() }
                else -> {
                    val file = known[index - 2]
                    val parsed = CubeLut.parse(file.readText())
                    if (parsed == null) toast("Not a 3D cube LUT this app can read")
                    else {
                        prefs.monitorLutName = "${file.name}, ${parsed.size} cube"
                        refresh()
                    }
                }
            }
        }
    }

    /**
     * The whole picture in one scrollable block, and offered as a file as
     * well, because this is the thing worth keeping beside footage when a
     * shot turns out to have been impossible on that phone.
     */
    private fun showFullReport() {
        val report = DeviceReport.build(this)
        val view = android.widget.ScrollView(this).apply {
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = report
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#CFD8DC"))
                setPadding(40, 30, 40, 30)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Camera features")
            .setView(view)
            .setPositiveButton("Close", null)
            .setNeutralButton("Save") { _, _ ->
                val name = "MantraNDI_device_${Build.MODEL.replace(' ', '_')}.txt"
                val target = MediaStoreOutput.writeText(this, name, report)
                toast(if (target == null) "Could not write it" else "Saved to ${target.shortLocation}")
            }
            .show()
    }

    /**
     * Whether the power key reaches an app at all is undocumented and differs
     * between builds, so this shows what this phone has actually delivered
     * rather than asserting either way.
     */
    private fun showKeys() {
        val message = buildString {
            append(if (KeyService.isEnabled(this@SettingsActivity)) "Service is on.\n\n"
                   else "Service is off. Enable Mantra NDI in Accessibility.\n\n")
            append("Keys this phone has delivered:\n")
            append(KeyService.seenKeyReport())
            append("\n\nVolume rocker: adjusts whatever is on screen, ")
            append("zooms when nothing is, records when held.")
        }
        AlertDialog.Builder(this)
            .setTitle("Hardware keys")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNeutralButton("Accessibility settings") { _, _ ->
                startActivity(KeyService.settingsIntent())
            }
            .show()
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
