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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        slots = LutSlots(this)

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

        // ROT, for the lens the camera was on.
        val lens = intent.getStringExtra(EXTRA_LENS)
        val rot = findViewById<RadioGroup>(R.id.rot)
        if (lens == null) {
            rot.addView(TextView(this).apply { text = "Open settings from the camera to turn a lens" })
        } else {
            val turns = settings.quarterTurnsFor(lens)
            (0..3).forEach { q ->
                rot.addView(RadioButton(this).apply {
                    id = 100 + q
                    text = "${q * 90}°"
                    textSize = 12f
                    isChecked = q == turns
                })
            }
            rot.setOnCheckedChangeListener { _, id -> settings.setQuarterTurnsFor(lens, id - 100) }
        }

        // Zebra from 50% to 100%.
        slider(
            R.id.zebra, R.id.zebraValue,
            value = settings.zebraLevel - 50,
            label = { "from ${it + 50}%" },
            onSet = { settings.zebraLevel = it + 50 }
        )

        val sourceName = findViewById<EditText>(R.id.sourceName)
        sourceName.setText(settings.sourceName)

        // Depth: two words, not a switch with a paragraph.
        //
        // A toggle asks "do you want ten bit?", which is a question with a
        // right answer, and then leaves the operator to work out what "off"
        // means. Two labelled choices say what the camera will do.
        val depth = findViewById<RadioGroup>(R.id.depth)
        listOf(8 to "8-bit", 10 to "10-bit").forEach { (bits, label) ->
            depth.addView(
                RadioButton(this).apply {
                    id = bits
                    text = label
                    textSize = 12f
                    isChecked = (bits == 10) == settings.wantTenBit
                }
            )
        }
        depth.setOnCheckedChangeListener { _, id -> settings.wantTenBit = id == 10 }

        // The resolutions this phone's lenses really publish, not a list of
        // numbers somebody typed. A resolution a lens does not have is a
        // session it refuses and a black screen the operator has to diagnose.
        val pipeline = CameraPipeline(this)
        val offered = CameraCatalogue.lenses(this)
            .flatMap { pipeline.widthsOffered(it.id, it.physicalId) }
            .distinct()
            .filter { it in intArrayOf(1280, 1920, 2560, 3840) }
            .sorted()
            .ifEmpty { listOf(1920) }
        val resolutions = findViewById<RadioGroup>(R.id.resolution)
        offered.forEach { width ->
            resolutions.addView(
                RadioButton(this).apply {
                    id = width
                    text = when (width) {
                        3840 -> "4K UHD"
                        2560 -> "1440p"
                        1920 -> "1080p"
                        else -> "720p"
                    }
                    textSize = 12f
                    isChecked = width == settings.captureWidth
                }
            )
        }
        // A phone that has lost the lens it was set for falls back rather than
        // showing nothing ticked and meaning something else.
        if (offered.none { it == settings.captureWidth }) {
            val nearest = offered.minByOrNull { kotlin.math.abs(it - settings.captureWidth) }
            if (nearest != null) {
                settings.captureWidth = nearest
                resolutions.check(nearest)
            }
        }
        resolutions.setOnCheckedChangeListener { _, id -> settings.captureWidth = id }

        // Frames a second, from what the lenses will actually hold. Same rule
        // as the resolutions: a rate a sensor cannot sustain is a session that
        // runs at something else and says nothing.
        val rates = CameraCatalogue.lenses(this)
            .flatMap { pipeline.frameRatesOffered(it.id, it.physicalId, settings.captureWidth) }
            .distinct()
            .sorted()
            .ifEmpty { listOf(30) }
        val frameRate = findViewById<RadioGroup>(R.id.frameRate)
        rates.forEach { fps ->
            frameRate.addView(
                RadioButton(this).apply {
                    id = fps
                    text = "$fps"
                    textSize = 12f
                    isChecked = fps == settings.fps
                }
            )
        }
        if (rates.none { it == settings.fps }) {
            val nearest = rates.minByOrNull { kotlin.math.abs(it - settings.fps) }
            if (nearest != null) { settings.fps = nearest; frameRate.check(nearest) }
        }
        frameRate.setOnCheckedChangeListener { _, id -> settings.fps = id }

        slider(
            R.id.bitRate, R.id.bitRateValue,
            value = settings.bitRateMbps - 2,
            label = { "${it + 2} Mbit/s" },
            onSet = { settings.bitRateMbps = it + 2 }
        )
        slider(
            R.id.hold, R.id.holdValue,
            value = (settings.focusHoldMs / 100).toInt(),
            label = { "hold  ${seconds(it)}" },
            onSet = { settings.focusHoldMs = it * 100L }
        )
        slider(
            R.id.rack, R.id.rackValue,
            value = (settings.focusRackMs / 100).toInt(),
            label = { "rack  ${seconds(it)}" },
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
        findViewById<TextView>(R.id.headerVersion).text = "v${BuildConfig.VERSION_NAME}"
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
