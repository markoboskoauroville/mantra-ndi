package com.mantraproductions.ndi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mantraproductions.ndi.databinding.ActivityMainBinding

/**
 * Phase zero. No camera. The whole screen is the answer to one question:
 * what is this app doing, right now, on the phone in my hand.
 *
 * The file is the record and the screen is the way to read it without a
 * cable, without a file manager that can see Android/data, and without
 * waiting for the next build. Getting the state out of the phone is the
 * hardest part of this project, which is why it is the first part.
 *
 * Two buttons crash the app on purpose, and there are two of them rather than
 * one because they prove different things. An exception proves the handler
 * runs and the report lands in both places. An Error proves the handler was
 * not narrowed to Exception somewhere along the way, which is how a rejected
 * shader escaped the last build's reporter.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding

    /** Repaints the trace on screen. It must never write to the trace itself. */
    private val repaint = object : Runnable {
        override fun run() {
            showTrace()
            ui.root.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.step("activity onCreate, savedInstanceState=" + (savedInstanceState != null))
        super.onCreate(savedInstanceState)

        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        // Half one of system-bars.md: let the picture reach the glass.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Half two, the half that gets forgotten: put the controls back
        // inside. Padding the content, never the root, so the picture layer
        // underneath keeps the whole screen when phase 1 fills it. The values
        // are traced, because a control under a bar is invisible in a
        // screenshot taken on a desk and obvious in a number.
        ViewCompat.setOnApplyWindowInsetsListener(ui.content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            Trace.state(
                "insets applied  left=${bars.left} top=${bars.top} " +
                    "right=${bars.right} bottom=${bars.bottom}"
            )
            insets
        }

        ui.version.text = "v" + BuildConfig.VERSION_NAME + "  phase 0"

        ui.throwException.setOnClickListener {
            Trace.control("crash", "exception", "throwing", "on purpose")
            throw IllegalStateException("Deliberate crash from the panel, to test the reporter")
        }

        // Thrown as an Error on purpose. If this one produces no report and
        // the exception above does, the handler has been narrowed somewhere.
        ui.throwError.setOnClickListener {
            Trace.control("crash", "error", "throwing", "on purpose, as an Error")
            throw UnsatisfiedLinkError("Deliberate Error from the panel, to test catch Throwable")
        }

        ui.export.setOnClickListener { exportTrace() }
        ui.copyPath.setOnClickListener { copyPath() }

        describeCameras()
        Trace.step("activity onCreate finished")
    }

    override fun onStart() {
        super.onStart()
        Trace.step("activity onStart")
    }

    override fun onResume() {
        super.onResume()
        Trace.step("activity onResume, rotation=" + rotationName())
        showStatus()
        ui.root.post(repaint)
    }

    override fun onPause() {
        Trace.step("activity onPause")
        ui.root.removeCallbacks(repaint)
        super.onPause()
    }

    override fun onStop() {
        Trace.step("activity onStop")
        super.onStop()
    }

    override fun onDestroy() {
        Trace.step("activity onDestroy, finishing=$isFinishing")
        super.onDestroy()
    }

    /**
     * The activity handles rotation itself rather than being recreated, so
     * this is where a turn of the phone shows up. Phase 1 will hang the
     * preview's orientation off this; phase zero writes the numbers down so
     * that when phase 1 gets it wrong, the trace already says what the phone
     * reported.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val shape = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
            "landscape" else "portrait"
        Trace.state("configuration changed  $shape  rotation=" + rotationName())
        showStatus()
    }

    // --- the screen --------------------------------------------------------

    private fun showStatus() {
        val f = Trace.file()
        val crashes = CrashLog.reports(this)
        val where = if (Trace.onPrivateStorage()) "private storage (no external)"
        else "Android/data/$packageName/files"

        val text = StringBuilder()
        text.append("trace      ").append(f?.name ?: "NOT WRITING")
            .append("   ").append(TraceFormat.bytes(Trace.bytes()))
            .append("   ").append(Trace.lineCount()).append(" lines\n")
        text.append("folder     ").append(where).append('\n')
        text.append("reports    ").append(Downloads.folder()).append('\n')
        text.append("crashes    ").append(
            if (crashes.isEmpty()) "none on this phone"
            else crashes.size.toString() + ", newest " + crashes.first().name
        )
        ui.status.text = text
    }

    /**
     * Colour carries state and nothing else: a refusal or a fault is red, a
     * control change is amber, everything else is the ordinary ink.
     */
    private fun showTrace() {
        val lines = Trace.lines()
        val out = SpannableStringBuilder()
        for (line in lines) {
            val start = out.length
            out.append(line).append('\n')
            val colour = when {
                line.contains(TraceFormat.FAULT) || line.contains(TraceFormat.REFUSED) ->
                    getColor(R.color.fault)
                line.contains(TraceFormat.CONTROL) -> getColor(R.color.amber)
                else -> 0
            }
            if (colour != 0) {
                out.setSpan(
                    ForegroundColorSpan(colour), start, out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        ui.trace.text = out
        ui.scroll.post { ui.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // --- the two file actions ----------------------------------------------

    private fun exportTrace() {
        val f = Trace.file()
        if (f == null) {
            Trace.refused("export", "there is no trace file to copy")
            say("No trace file to export")
            return
        }
        val text = try {
            f.readText()
        } catch (t: Throwable) {
            Trace.fault("could not read the trace back", t)
            say("Could not read the trace")
            return
        }
        val where = Downloads.writeText(this, f.name, text)
        if (where == null) {
            Trace.refused("export", "MediaStore would not take the file")
            say("Downloads refused the file")
        } else {
            Trace.state("trace exported to $where")
            say(where)
        }
    }

    private fun copyPath() {
        val path = Trace.file()?.absolutePath ?: getExternalFilesDir(null)?.absolutePath ?: ""
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("Mantra NDI trace", path))
        Trace.state("path copied to clipboard")
        say(path)
    }

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- facts phase 1 will need, read without opening anything ------------

    /**
     * Every camera's mounting angle, written down at startup.
     *
     * Camera2 delivers frames at the sensor's mounting angle and nothing
     * corrects that for a TextureView. It was never solved in the last build
     * across six attempts. Reading the characteristics needs no permission and
     * opens nothing, so the number that phase 1 has to get right is in the
     * trace from the first version rather than being guessed at.
     */
    private fun describeCameras() {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
                val levels = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val manual = levels?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                ) ?: false
                Trace.state(
                    "camera $id  $facing  sensor orientation $orientation  " +
                        "reports manual sensor $manual"
                )
            }
            // REBUILD.md §6: this phone answers false and takes manual
            // requests anyway, so nothing may be gated on that flag. It is
            // traced as a reading, not as a decision.
        } catch (t: Throwable) {
            Trace.fault("could not read the camera list", t)
        }
    }

    private fun rotationName(): String {
        val r = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: -1
            } else {
                @Suppress("DEPRECATION")
                val legacy = windowManager.defaultDisplay
                legacy.rotation
            }
        } catch (t: Throwable) {
            -1
        }
        return when (r) {
            0 -> "0"
            1 -> "90"
            2 -> "180"
            3 -> "270"
            else -> "unknown"
        }
    }

    private companion object {
        const val REFRESH_MS = 300L
    }
}
