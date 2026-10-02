package com.mantraproductions.ndi

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * What is decided once, off the camera screen.
 *
 * Everything written here takes effect the next time the camera opens, which
 * is when this screen is left — the activity rebuilds its session on resume
 * anyway, because a camera session does not survive being backgrounded even
 * with a foreground service. So there is nothing to apply and no apply button:
 * a value is saved as it is moved.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var slots: LutSlots

    companion object {
        /** The lens the camera screen was on, for ROT. */
        const val EXTRA_LENS = "lens"
        const val EXTRA_LENS_NAME = "lensName"
        /** The newest signed APK lives here (CI publishes every version as a release). */
        const val LATEST_RELEASE = "https://github.com/markoboskoauroville/mantra-ndi/releases/latest"
    }

    /** The system's folder picker: any drive, a USB SSD included. */
    private val pickFolder =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree == null) return@registerForActivityResult
            // Kept across restarts, or the next take would have nowhere to go.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { Trace.fault("record folder", it) }
            settings.recordFolder = tree.toString()
            Trace.control("record folder", Recordings.where(this, tree.toString()), "chosen")
            showFolder()
        }

    /** A .cube into the first free slot. */
    private val pickLut =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val free = (1..LutSlots.COUNT).firstOrNull { !slots.slot(it).loaded }
            if (free == null) {
                Toast.makeText(this, "All ${LutSlots.COUNT} places are full — remove one first", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val name = runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull()
            val size = slots.import(free, uri, name)
            Toast.makeText(
                this,
                if (size == null) "That file is not a 3D cube LUT" else "Added ${slots.slot(free).label}, $size cubed",
                Toast.LENGTH_LONG
            ).show()
            showLuts()
        }

    /**
     * The four outputs (v91). A switch arms its destination and opens its
     * panel; off, the panel is hidden. USB and YouTube stay in place, dark,
     * until the versions that build them (a key that cannot work stays where
     * it will be, dark — the camera's language).
     */
    private fun switchboard() {
        fun bind(switchId: Int, panelId: Int, armed: Boolean, onSet: ((Boolean) -> Unit)?) {
            val sw = findViewById<android.widget.Switch>(switchId)
            val panel = findViewById<View>(panelId)
            sw.isChecked = armed
            panel.visibility = if (armed) View.VISIBLE else View.GONE
            if (onSet == null) {
                sw.isEnabled = false
                // The panel says when it arrives, so it opens on a tap of the row.
                (sw.parent as View).setOnClickListener {
                    panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return
            }
            sw.setOnCheckedChangeListener { _, on ->
                onSet(on)
                panel.visibility = if (on) View.VISIBLE else View.GONE
                Trace.control("output", resources.getResourceEntryName(switchId), if (on) "armed" else "off")
            }
        }
        bind(R.id.armFile, R.id.panelFile, settings.armFile) { settings.armFile = it }
        bind(R.id.armUsb, R.id.panelUsb, false, null)
        bind(R.id.armNdi, R.id.panelNdi, settings.armNdi) { settings.armNdi = it }
        bind(R.id.armYoutube, R.id.panelYoutube, false, null)

        val kind = findViewById<RadioGroup>(R.id.ndiKind)
        listOf(1 to "HX", 2 to "FULL").forEach { (k, name) ->
            kind.addView(RadioButton(this).apply {
                id = 200 + k
                text = name
                textSize = 13f
                isChecked = settings.ndiKind == k
            })
        }
        kind.setOnCheckedChangeListener { _, id -> settings.ndiKind = id - 200 }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        slots = LutSlots(this)

        switchboard()

        // Where takes go.
        showFolder()
        findViewById<Button>(R.id.chooseFolder).setOnClickListener {
            runCatching { pickFolder.launch(null) }
                .onFailure { Toast.makeText(this, "This phone has no folder picker", Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.defaultFolder).setOnClickListener {
            settings.recordFolder = null
            showFolder()
        }

        // The LUT library.
        showLuts()
        findViewById<Button>(R.id.addLut).setOnClickListener { pickLut.launch(arrayOf("*/*")) }

        // v106: the outputs line on the camera, shown or hidden
        findViewById<android.widget.Switch>(R.id.showOutputs).apply {
            isChecked = settings.showOutputs
            setOnCheckedChangeListener { _, on -> settings.showOutputs = on }
        }
        // v104: the light at the top, and the three marks' sizes first (ROT, "Turn the preview", is gone).
        val torch = findViewById<android.widget.ImageButton>(R.id.torch)
        fun paintTorch() = torch.setColorFilter(if (settings.torch) RailButton.GREEN else RailButton.GREY)
        paintTorch()
        torch.setOnClickListener {
            settings.torch = !settings.torch
            paintTorch()
            // v111: the light comes on NOW. While settings shows, the camera screen has let the camera go, so the
            // phone's own torch switch works here; back on the camera, the camera takes the lamp over (v104).
            lampNow(settings.torch)
        }
        // v111: the three marks in one line, each its icon and its size as a drop-down in steps of 25 % ("You go
        // by 25% that's the scale"). 25 % is the smallest a mark may be, 100 % the largest.
        val quarters = listOf("25%", "50%", "75%", "100%")
        fun size(spinner: Int, icon: Int, glyph: RailButton.Glyph, now: Float, lo: Float, hi: Float, set: (Float) -> Unit) {
            findViewById<RailButton>(icon).apply { this.glyph = glyph; state = RailButton.State.SHOWN; isClickable = false }
            val sp = findViewById<android.widget.Spinner>(spinner)
            sp.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, quarters)
            val at = (((now - lo) / (hi - lo)) * 4f - 1f).let { Math.round(it) }.coerceIn(0, 3)
            sp.setSelection(at, false)
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, i: Int, id: Long) = set(lo + (hi - lo) * (i + 1) / 4f)
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        }
        size(R.id.sizeSquare, R.id.iconSquare, RailButton.Glyph.SQUARE, settings.focusBoxSize, Mechanism.BOX_MIN, 1f) { settings.focusBoxSize = it }
        size(R.id.sizeCircle, R.id.iconCircle, RailButton.Glyph.CIRCLE, settings.circleSize, 0.06f, 0.5f) { settings.circleSize = it }
        size(R.id.sizeTriangle, R.id.iconTriangle, RailButton.Glyph.TRIANGLE, settings.wbSize, 0.06f, 0.5f) { settings.wbSize = it }

        // v111: THE TABS instead of one long scroll. The last one chosen comes back.
        val tabs = listOf(R.id.tabCamera to R.id.pageCamera, R.id.tabOutputs to R.id.pageOutputs,
            R.id.tabDisplay to R.id.pageDisplay, R.id.tabMore to R.id.pageMore)
        val prefs = getSharedPreferences("settings_screen", MODE_PRIVATE)
        fun showTab(n: Int) {
            tabs.forEachIndexed { i, (t, pg) ->
                findViewById<TextView>(t).setTextColor(if (i == n) RailButton.GREEN else RailButton.GREY)
                findViewById<View>(pg).visibility = if (i == n) View.VISIBLE else View.GONE
            }
            prefs.edit().putInt("tab", n).apply()
        }
        tabs.forEachIndexed { i, (t, _) -> findViewById<TextView>(t).setOnClickListener { showTab(i) } }
        showTab(prefs.getInt("tab", 0).coerceIn(0, tabs.size - 1))

        // Zebra from 50% to 100%.
        slider(
            R.id.zebra, R.id.zebraValue,
            value = settings.zebraLevel - 50,
            label = { "from ${it + 50}%" },
            onSet = { settings.zebraLevel = it + 50 }
        )

        val sourceName = findViewById<EditText>(R.id.sourceName)
        sourceName.setText(settings.sourceName)

        // v111, QUICK: frame rate, depth and resolution as ONE key each; a tap steps to the next value (Marko,
        // 2.10.2026: "all buttons now will be toggle. So we need only one button per setting"). The lists are what
        // this phone's lenses really publish, as before: a value a lens does not have is never offered.
        val pipeline = CameraPipeline(this)
        val offered = CameraCatalogue.lenses(this)
            .flatMap { pipeline.widthsOffered(it.id, it.physicalId) }
            .distinct()
            .filter { it in intArrayOf(1280, 1920, 2560, 3840) }
            .sorted()
            .ifEmpty { listOf(1920) }
        if (offered.none { it == settings.captureWidth }) {
            offered.minByOrNull { kotlin.math.abs(it - settings.captureWidth) }?.let { settings.captureWidth = it }
        }
        fun ratesNow() = CameraCatalogue.lenses(this)
            .flatMap { pipeline.frameRatesOffered(it.id, it.physicalId, settings.captureWidth) }
            .distinct().sorted().ifEmpty { listOf(30) }
        fun fitRate() {
            val rates = ratesNow()
            if (rates.none { it == settings.fps }) rates.minByOrNull { kotlin.math.abs(it - settings.fps) }?.let { settings.fps = it }
        }
        fitRate()
        val fpsKey = findViewById<Button>(R.id.fpsToggle)
        val depthKey = findViewById<Button>(R.id.depthToggle)
        val resKey = findViewById<Button>(R.id.resToggle)
        fun resName(w: Int) = when (w) { 3840 -> "4K"; 2560 -> "1440p"; 1920 -> "1080p"; else -> "720p" }
        fun paintQuick() {
            fpsKey.text = "${settings.fps} FPS"
            depthKey.text = if (settings.wantTenBit) "10-BIT" else "8-BIT"
            resKey.text = resName(settings.captureWidth)
        }
        paintQuick()
        fpsKey.setOnClickListener {
            val rates = ratesNow()
            settings.fps = rates[(rates.indexOf(settings.fps) + 1) % rates.size]
            paintQuick()
        }
        depthKey.setOnClickListener { settings.wantTenBit = !settings.wantTenBit; paintQuick() }
        resKey.setOnClickListener {
            settings.captureWidth = offered[(offered.indexOf(settings.captureWidth) + 1) % offered.size]
            fitRate()                      // a size can change which rates the lenses hold
            paintQuick()
        }

        slider(
            R.id.bitRate, R.id.bitRateValue,
            value = settings.bitRateMbps - 2,
            label = { "${it + 2} Mbit/s" },
            onSet = { settings.bitRateMbps = it + 2 }
        )
        slider(
            R.id.streamBitRate, R.id.streamBitRateValue,
            value = settings.streamMbps - 2,
            label = { "${it + 2} Mbit/s" },
            onSet = { settings.streamMbps = it + 2 }
        )
        val path = findViewById<RadioGroup>(R.id.picturePath)
        listOf(true to "GPU STAGE", false to "DIRECT").forEach { (gpu, name) ->
            path.addView(RadioButton(this).apply {
                id = if (gpu) 301 else 302
                text = name
                textSize = 13f
                isChecked = settings.gpuStage == gpu
            })
        }
        path.setOnCheckedChangeListener { _, id -> settings.gpuStage = id == 301 }
        slider(R.id.trackSearch, R.id.trackSearchValue, value = settings.trackSearch - 15,
            label = { String.format(java.util.Locale.ROOT, "search zone  %.1f x the box", (it + 15) / 10.0) },
            onSet = { settings.trackSearch = it + 15 })
        slider(R.id.trackEvery, R.id.trackEveryValue, value = settings.trackEvery - 1,
            label = { if (it == 0) "search every frame" else "search every ${it + 1} frames" },
            onSet = { settings.trackEvery = it + 1 })
        slider(R.id.trackConfidence, R.id.trackConfidenceValue, value = settings.trackConfidence - 20,
            label = { "LOST below ${it + 20}% match" },
            onSet = { settings.trackConfidence = it + 20 })
        slider(R.id.trackTolerance, R.id.trackToleranceValue, value = settings.trackTolerance - 1,
            label = { "refocus after moving ${it + 1}% of the frame" },
            onSet = { settings.trackTolerance = it + 1 })
        slider(R.id.trackFollow, R.id.trackFollowValue, value = settings.trackFollow - 10,
            label = { "mark follows ${it + 10}% of the way per search" },
            onSet = { settings.trackFollow = it + 10 })
        slider(
            R.id.hold, R.id.holdValue,
            value = (settings.focusHoldMs / 100).toInt(),
            label = { "hold  ${seconds(it)}" },
            onSet = { settings.focusHoldMs = it * 100L }
        )
        slider(
            R.id.rack, R.id.rackValue,
            value = (settings.focusRackMs / 100).toInt(),
            label = { "ramp  ${seconds(it)}" },
            onSet = { settings.focusRackMs = it * 100L }
        )
        slider(
            R.id.peak, R.id.peakValue,
            value = settings.peakSensitivity,
            label = { "sensitivity  $it" },
            onSet = { settings.peakSensitivity = it }
        )

        val colours = findViewById<RadioGroup>(R.id.peakColour)
        PreviewEffects.PeakColour.entries.forEach { colour ->
            colours.addView(
                RadioButton(this).apply {
                    id = colour.ordinal + 1
                    text = colour.label
                    setTextColor(colour.colour)
                    textSize = 12f
                    isChecked = colour == settings.peakColour
                }
            )
        }
        colours.setOnCheckedChangeListener { _, id ->
            PreviewEffects.PeakColour.entries.getOrNull(id - 1)?.let {
                settings.peakColour = it
            }
        }

        // The addresses, cable first. Re-read on resume, because the whole
        // point is that he plugs the cable in while this screen is open.
        showAddresses()
        findViewById<Button>(R.id.usbSettings).setOnClickListener {
            // The tethering panel, and a fall back to the settings root when a
            // phone does not expose it — never a dead key.
            val tried = listOf(
                Intent().setClassName(
                    "com.android.settings",
                    "com.android.settings.TetherSettings"
                ),
                Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
                Intent(android.provider.Settings.ACTION_SETTINGS)
            )
            for (intent in tried) {
                if (runCatching { startActivity(intent); true }.getOrDefault(false)) return@setOnClickListener
            }
            Toast.makeText(this, "Could not open the phone's settings", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.exportTrace).setOnClickListener {
            val file = Trace.file()
            val text = file?.readText() ?: Trace.lines().joinToString("\n")
            val where = Downloads.writeText(this, file?.name ?: "trace.txt", text)
            Toast.makeText(
                this,
                where?.let { p -> "Trace → $p" } ?: "The trace could not be written",
                Toast.LENGTH_LONG
            ).show()
        }

        // MIN, one key.
        //
        // It was two, MINIMAL and VERBOSE, and two keys for one two-state thing
        // is a question asked twice. One key that is green while the screen is
        // quiet says the same thing in a quarter of the room — *"just the three
        // letters MIN. When I press this, all help text disappears."*
        //
        // Every hint carries a tag rather than being listed by id, so a hint
        // added later is covered without anybody remembering to add it here.
        val minimal = findViewById<Button>(R.id.minimal)
        fun showHints(on: Boolean) {
            settings.verboseSettings = on
            hints(findViewById(android.R.id.content)).forEach {
                it.visibility = if (on) View.VISIBLE else View.GONE
            }
            // Grey is off and green is on, here as on the rails. Green means
            // the screen is minimal, which is what the key does.
            minimal.setTextColor(if (on) resources.getColor(R.color.quiet, theme) else RailButton.GREEN)
        }
        minimal.setOnClickListener { showHints(!settings.verboseSettings) }
        showHints(settings.verboseSettings)

        // The version, at the top, where he looks for it after an install.
        // v94, a hidden link: *"If I click on the version number, it will take me
        // to the GitHub latest release APK page."* It looks like the number and
        // nothing else; a tap opens the latest release, where the APK is.
        findViewById<TextView>(R.id.headerVersion).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(LATEST_RELEASE)))
                }.onFailure { Toast.makeText(this@SettingsActivity, "No browser to open $LATEST_RELEASE", Toast.LENGTH_LONG).show() }
                Trace.control("version", "tapped", "opens $LATEST_RELEASE")
            }
        }
        findViewById<TextView>(R.id.version).text =
            if (NdiSender.available) "NDI SDK present" else "built without the NDI SDK"
    }

    private fun showFolder() {
        val folder = settings.recordFolder
        val free = Recordings.freeBytes(this, folder)
        findViewById<TextView>(R.id.recordFolder).text =
            Recordings.where(this, folder) + "\n" +
                (free?.let { Mechanism.formatFree(it) + " free" } ?: "not reachable — plugged in?")
    }

    /** One row per loaded LUT: its name, its size, and a key to remove it. */
    private fun showLuts() {
        val list = findViewById<android.widget.LinearLayout>(R.id.lutList)
        list.removeAllViews()
        val loaded = (1..LutSlots.COUNT).map { slots.slot(it) }.filter { it.loaded }
        if (loaded.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No LUTs yet"
                setTextColor(resources.getColor(R.color.quiet, theme))
            })
        }
        loaded.forEach { slot ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${slot.label}  ·  ${slot.size}³"
                textSize = 13f
                setTextColor(RailButton.GREEN)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "REMOVE"
                textSize = 11f
                setOnClickListener { slots.clear(slot.index); showLuts() }
            })
            list.addView(row)
        }
        findViewById<Button>(R.id.addLut).isEnabled = loaded.size < LutSlots.COUNT
    }

    /** Every view in the tree tagged as help text. */
    private fun hints(root: View): List<View> = when {
        root.tag == "hint" -> listOf(root)
        root is ViewGroup -> (0 until root.childCount).flatMap { hints(root.getChildAt(it)) }
        else -> emptyList()
    }

    override fun onResume() {
        super.onResume()
        showAddresses()
        showFolder()
    }

    /**
     * Where a receiver should be pointed.
     *
     * NDI finds its sources by mDNS, and a link that was made thirty seconds
     * ago by plugging in a cable is exactly where a receiver is most likely
     * not to hear it. Every receiver has a box to type an address into; none
     * of them can guess one.
     */
    private fun showAddresses() {
        val addresses = UsbLink.addresses()
        val readout = findViewById<TextView>(R.id.usbState)
        readout.text = if (addresses.isEmpty()) {
            "No network — nothing can reach this phone."
        } else {
            (if (UsbLink.usbUp()) "The cable is up.\n" else "No cable. Turn USB tethering on.\n") +
                addresses.joinToString("\n") { "  " + it.label }
        }
        readout.setTextColor(
            if (UsbLink.usbUp()) RailButton.GREEN else resources.getColor(R.color.sand, theme)
        )
    }

    /**
     * The name is saved on the way out rather than on every keystroke.
     *
     * A name saved letter by letter is a different NDI source announced letter
     * by letter, and every receiver on the network watching a list of them
     * appear and vanish.
     */
    override fun onPause() {
        super.onPause()
        settings.sourceName = findViewById<EditText>(R.id.sourceName).text.toString()
    }

    private fun seconds(tenths: Int): String =
        if (tenths == 0) "snap" else String.format("%.1f s", tenths / 10.0)

    /** The phone's lamp through CameraManager, on the first back camera that has one. Silent where it cannot. */
    private fun lampNow(on: Boolean) {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        runCatching {
            val id = cm.cameraIdList.firstOrNull { cid ->
                val c = cm.getCameraCharacteristics(cid)
                c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            cm.setTorchMode(id, on)
            Trace.control("torch", on, "settings, through the camera manager")
        }.onFailure { Trace.refused("torch", "settings: " + Trace.describe(it)) }
    }

    private fun slider(
        barId: Int,
        valueId: Int,
        value: Int,
        label: (Int) -> String,
        onSet: (Int) -> Unit
    ) {
        val bar = findViewById<SeekBar>(barId)
        val readout = findViewById<TextView>(valueId)
        bar.progress = value
        readout.text = label(value)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, progress: Int, fromUser: Boolean) {
                readout.text = label(progress)
                if (fromUser) onSet(progress)
            }
            override fun onStartTrackingTouch(b: SeekBar?) = Unit
            override fun onStopTrackingTouch(b: SeekBar?) = Unit
        })
    }
}
