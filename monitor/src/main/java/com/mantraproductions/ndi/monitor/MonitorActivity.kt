package com.mantraproductions.ndi.monitor

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mantraproductions.ndi.CameraCommand
import com.mantraproductions.ndi.CameraState
import com.mantraproductions.ndi.MonitorEngine
import com.mantraproductions.ndi.NdiFinder
import com.mantraproductions.ndi.NdiReceiver
import kotlin.concurrent.thread

/**
 * MANTRA MONITOR (v113): the camera's sister app, one NDI monitor for any NDI source on the network.
 *
 * Marko, 2.10.2026: "user can choose NDI stream very simple, double tapping in the middle of the screen. It reads all
 * the streams from the network." So: the picture fills the screen; a double tap in the middle opens THE SOURCES, every
 * NDI source the network announces, as words; a tap on one watches it; the choice is kept and comes back at the next
 * start. Nothing is recorded here. When the source is a Mantra Manual Camera (it answers with its state as NDI
 * metadata), the monitor will carry its controls and drive it (v114/v115); v113 says so on the status line.
 *
 * The CAMERA language: words without boxes, green = on, grey = off; the picture is sacred, so only the status line
 * sits on it, and the source list only while it is asked for.
 */
class MonitorActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    // v114: a TextureView, so the picture can keep its shape (letterboxed) and be turned in quarter turns
    private lateinit var surface: TextureView
    private var output: Surface? = null
    private lateinit var turnKey: TextView
    private var turns = 0
    private lateinit var status: TextView
    private lateinit var picker: ScrollView
    private lateinit var list: LinearLayout
    private var engine: MonitorEngine? = null
    private var sources: List<String> = emptyList()
    @Volatile private var finding = false
    private var current: String? = null
    private var cameraSeen = false

    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = TextureView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E8EA")); textSize = 11f
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000")); setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(28) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(40), dp(24), dp(16)) }
        picker = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#E60B0D10")); visibility = View.GONE; addView(list)
        }
        // TURN: a quarter turn per tap, kept per source (Marko's camera sends its sensor's landscape frame even when
        // the phone is upright; until the camera reports how it is held, the operator turns it once and it stays)
        turnKey = word("TURN", Color.parseColor("#7A8087"), 13f) {
            turns = (turns + 1) % 4
            current?.let { prefs.edit().putInt("turns:$it", turns).apply() }
            fit()
        }
        root.addView(turnKey, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(28)) })
        root.addView(picker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // A double tap in the MIDDLE of the screen (the central half) opens the sources; anywhere else does nothing,
        // so a hand holding the phone by its edges never opens it by accident.
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = root.width.toFloat(); val h = root.height.toFloat()
                if (e.x in w * 0.25f..w * 0.75f && e.y in h * 0.25f..h * 0.75f) showPicker(true)
                return true
            }
            override fun onDown(e: MotionEvent) = true
        })
        surface.setOnTouchListener { _, ev -> taps.onTouchEvent(ev) }

        surface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                output = Surface(t)
                val again = current ?: prefs.getString("source", null)
                if (again != null) watch(again) else say("Double tap in the middle to choose a source")
            }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = fit()
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                engine?.stop(); engine = null; output?.release(); output = null
                return true
            }
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
                // the picture's size is known only once frames arrive; fit when it changes
                val e = engine ?: return
                if (e.lastWidth != fittedW || e.lastHeight != fittedH) fit()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startFinding()
    }

    override fun onPause() {
        super.onPause()
        finding = false
        NdiFinder.stop()
    }

    /** The finder runs while the app is in front; the list on screen follows it. */
    private fun startFinding() {
        if (!NdiFinder.available) { say("The NDI runtime is not in this build"); return }
        if (!NdiFinder.start(this)) { say("NDI discovery would not start"); return }
        finding = true
        thread(name = "ndi-finder") {
            while (finding) {
                val found = NdiFinder.sources(2000)
                ui.post { sources = found; if (picker.visibility == View.VISIBLE) fillPicker() }
            }
        }
    }

    private fun showPicker(on: Boolean) {
        picker.visibility = if (on) View.VISIBLE else View.GONE
        if (on) fillPicker()
    }

    private fun word(text: String, colour: Int, size: Float, onTap: (() -> Unit)?) = TextView(this).apply {
        this.text = text; setTextColor(colour); textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(12))
        onTap?.let { t -> setOnClickListener { t() } }
    }

    private fun fillPicker() {
        list.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(word("SOURCES", Color.parseColor("#E8A33D"), 13f, null), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(word("✕", Color.parseColor("#F2DDB4"), 20f) { showPicker(false) })
        list.addView(head)
        if (sources.isEmpty()) {
            list.addView(word("looking for NDI on this network…", Color.parseColor("#7A8087"), 14f, null))
        }
        for (s in sources) {
            val on = s == current
            list.addView(word(s, if (on) Color.parseColor("#33D17A") else Color.parseColor("#F2DDB4"), 16f) {
                showPicker(false); watch(s)
            })
        }
    }

    private var fittedW = 0
    private var fittedH = 0

    /**
     * The picture in its own shape, centred, turned by [turns] quarter turns, as large as fits — never stretched.
     * A TextureView draws the buffer stretched over the whole view; the matrix takes it back to the video's own size,
     * turns it about its centre, and scales it to fit.
     */
    private fun fit() {
        val e = engine ?: return
        val vw = e.lastWidth.toFloat(); val vh = e.lastHeight.toFloat()
        val w = surface.width.toFloat(); val h = surface.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        fittedW = e.lastWidth; fittedH = e.lastHeight
        val sideways = turns % 2 == 1
        val cw = if (sideways) vh else vw
        val ch = if (sideways) vw else vh
        val scale = minOf(w / cw, h / ch)
        val m = Matrix()
        m.setScale(vw / w, vh / h)
        m.postTranslate(-vw / 2f, -vh / 2f)
        m.postRotate(90f * turns)
        m.postScale(scale, scale)
        m.postTranslate(w / 2f, h / 2f)
        surface.setTransform(m)
        turnKey.setTextColor(if (turns != 0) Color.parseColor("#33D17A") else Color.parseColor("#7A8087"))
    }

    private fun watch(source: String) {
        engine?.stop()
        current = source
        turns = prefs.getInt("turns:$source", 0)
        fittedW = 0; fittedH = 0
        cameraSeen = false
        prefs.edit().putString("source", source).apply()
        say("Connecting…")
        val out = output ?: run { say("No picture surface yet"); return }
        val e = MonitorEngine(out,
            onStatus = { msg -> ui.post { say(msg) } },
            onCameraState = { st -> ui.post { onCamera(st) } })
        engine = e
        e.start(source)
        // ask a Mantra camera for its state; any other source simply ignores the metadata
        ui.postDelayed({
            if (current == source) runCatching { NdiReceiver.sendCommand(CameraCommand(requestState = true)) }
        }, 1500)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCamera(st: CameraState) {
        if (!cameraSeen) {
            cameraSeen = true
            say("MANTRA CAMERA — remote control comes in the next build")
        }
    }

    private fun say(text: String) {
        status.text = (current?.let { "$it  ·  " } ?: "") + text
    }
}
