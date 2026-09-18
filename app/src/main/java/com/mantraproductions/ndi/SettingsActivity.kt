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

        binding.rowSource.setOnClickListener { pickCameraSource() }
        binding.rowProfile.setOnClickListener { pickProfile() }
        binding.rowSourceName.setOnClickListener { editSourceName() }
        binding.rowBitDepth.setOnClickListener { pickBitDepth() }
        binding.rowLogCurve.setOnClickListener { pickLogCurve() }
        binding.rowExportLut.setOnClickListener { exportLut() }
        binding.rowLoadLut.setOnClickListener { loadLut() }
        binding.rowHistogram.setOnClickListener {
            prefs.vectorscopeVisible = !prefs.vectorscopeVisible
            refresh()
        }
        binding.rowStabilisation.setOnClickListener {
            prefs.stabilisation = !prefs.stabilisation
            refresh()
        }
        binding.rowCamera.setOnClickListener { showCameraCapabilities() }
        binding.rowDetect.setOnClickListener { showFullReport() }
        binding.rowKeys.setOnClickListener { showKeys() }
        binding.rowFocusBox.setOnClickListener { pickFocusBox() }
        binding.rowAiFocus.setOnClickListener { editApiKey() }

        refresh()
    }

    private fun refresh() {
        binding.valueSource.text = if (prefs.remoteMode) {
            "Remote: ${prefs.remoteSource ?: "none picked"}"
        } else {
            "Local, this phone"
        }
        binding.valueProfile.text = profileStore.selected().let {
            "${it.name}, ${it.width}x${it.height} at ${it.fps}"
        }
        binding.valueSourceName.text = identity.name
        binding.valueBitDepth.text = when {
            !prefs.tenBitWanted -> "8-bit, proven pipeline"
            !DeviceProfile.tenBitCapable -> "10-bit asked for, this phone is 8-bit"
            else -> "10-bit HLG, camera straight into the encoder"
        }
        binding.valueLogCurve.text = prefs.logCurve.let {
            if (it == LogCurves.Curve.REC709) "None, straight Rec.709"
            else "${it.vendor} ${it.displayName}, grey at ${"%.3f".format(LogCurves.middleGrey(it))}"
        }
        binding.valueHistogram.text = if (prefs.vectorscopeVisible) {
            "Shown. Drag the marker to balance"
        } else {
            "Hidden"
        }
        binding.valueStabilisation.text = if (prefs.stabilisation) "On" else "Off"
        binding.valueCamera.text = capabilitySummary()
        binding.valueFocusBox.text = when (prefs.focusBoxSize) {
            FocusSquareView.Size.SMALL -> "Small"
            FocusSquareView.Size.MEDIUM -> "Medium"
            FocusSquareView.Size.LARGE -> "Large"
            FocusSquareView.Size.FULL -> "Full screen"
        }
        binding.valueAiFocus.text = if (prefs.groqApiKey == null) {
            "Not set. Long press the focus box to name what to focus on"
        } else {
            "Key stored on this phone only"
        }
        binding.valueKeys.text = if (KeyService.isEnabled(this)) {
            "On. Volume rocker drives the camera"
        } else {
            "Off. Tap to enable, then the rocker works everywhere"
        }
        binding.valueAbout.text = "Mantra NDI v${BuildConfig.VERSION_NAME}" +
                if (BuildConfig.NDI_SDK_PRESENT) "" else ", built without the NDI SDK"
        binding.valueLoadLut.text = prefs.monitorLutName ?: "None loaded"
    }

    /**
     * Local or remote. Remote asks the network who is out there rather than
     * making the operator type a name, because the whole point of NDI is that
     * the sources announce themselves.
     */
    private fun pickCameraSource() {
        choose("Camera", listOf("Local, this phone", "Remote, over NDI")) { index ->
            if (index == 0) {
                prefs.remoteMode = false
                refresh()
                return@choose
            }
            if (!NdiFinder.available) {
                toast("This build has no NDI SDK, so there is nothing to find")
                return@choose
            }
            toast("Looking for cameras")
            kotlin.concurrent.thread(name = "remote-scan") {
                NdiFinder.start()
                val found = NdiFinder.sources(timeoutMs = 3000)
                NdiFinder.stop()
                runOnUiThread {
                    if (found.isEmpty()) {
                        toast("No sources found. Same network as the camera?")
                    } else {
                        choose("Which camera", found) { pick ->
                            prefs.remoteSource = found[pick]
                            prefs.remoteMode = true
                            refresh()
                        }
                    }
                }
            }
        }
    }

    private fun pickFocusBox() {
        val sizes = FocusSquareView.Size.values()
        choose("Focus box size", listOf("Small", "Medium", "Large", "Full screen")) { index ->
            prefs.focusBoxSize = sizes[index]
            refresh()
        }
    }

    /**
     * Typed in, never built in. This app's releases are public, and a key
     * compiled into an APK can be read straight back out of it by anybody who
     * downloads one.
     */
    private fun editApiKey() {
        val input = android.widget.EditText(this).apply {
            setText(prefs.groqApiKey.orEmpty())
            hint = "Groq API key"
        }
        AlertDialog.Builder(this)
            .setTitle("AI focus key")
            .setMessage("Stored on this phone only. It is never included in a build.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.groqApiKey = input.text.toString()
                refresh()
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.groqApiKey = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickProfile() {
        val profiles = profileStore.load()
        choose("Capture profile", profiles.map { it.name }) { index ->
            profileStore.selectedName = profiles[index].name
            refresh()
        }
    }

    private fun pickBitDepth() {
        val tenBitPossible = DeviceProfile.tenBitCapable
        choose(
            "Bit depth",
            listOf(
                "8-bit, the proven pipeline",
                if (tenBitPossible) "10-bit HLG, direct pipeline" else "10-bit, not supported here"
            )
        ) { index ->
            if (index == 1 && !tenBitPossible) {
                toast("This phone's camera is 8-bit")
                return@choose
            }
            prefs.tenBitWanted = index == 1
            refresh()
            if (index == 1) {
                toast("Camera writes straight into the encoder. Restart the camera screen.")
            }
        }
    }

    private fun pickLogCurve() {
        val curves = LogCurves.Curve.values()
        val labels = curves.map {
            if (it == LogCurves.Curve.REC709) "None, straight Rec.709"
            else "${it.vendor} ${it.displayName}"
        }
        if (!DeviceProfile.logCapable) {
            toast("This camera will not take a custom tone curve, so log is unavailable")
            return
        }
        choose("Log curve", labels) { index ->
            prefs.logCurve = curves[index]
            refresh()
            // Applied when the camera screen comes back up, since the curve
            // lives on the capture request rather than in a file.
            toast("Applied to the image when you return to the camera")
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

    private fun capabilitySummary(): String = DeviceProfile.summary.ifEmpty { legacySummary() }

    private fun legacySummary(): String = try {
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
