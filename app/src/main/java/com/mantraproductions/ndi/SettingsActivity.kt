package com.mantraproductions.ndi

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)

        val sourceName = findViewById<EditText>(R.id.sourceName)
        sourceName.setText(settings.sourceName)

        findViewById<SwitchCompat>(R.id.tenBit).apply {
            isChecked = settings.wantTenBit
            setOnCheckedChangeListener { _, on -> settings.wantTenBit = on }
        }

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

        findViewById<TextView>(R.id.version).text =
            "Mantra NDI v${BuildConfig.VERSION_NAME}  ·  " +
                (if (NdiSender.available) "NDI SDK present" else "built without the NDI SDK")
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
