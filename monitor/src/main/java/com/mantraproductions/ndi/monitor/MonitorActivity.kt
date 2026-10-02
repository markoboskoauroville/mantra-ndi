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
import com.mantraproductions.ndi.RailButton
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
    // v117, REMOTE CONTROL: the camera's own keys, top and bottom, and its marks over the picture
    private lateinit var railTop: LinearLayout
    private lateinit var railBottom: LinearLayout
    private lateinit var overlay: MarksOverlay
    private var fitMatrix: Matrix? = null
    private var keysShown = ""
    private var remote = false
    // v118: the monitor's own numbers, and the camera's HX bit rate under the operator's thumb
    private lateinit var info: LinearLayout
    private lateinit var stats: TextView
    private lateinit var rateWord: TextView
    private var cameraMbps = 0
    private val rates = listOf(4, 8, 12, 16, 24, 32, 50)
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
        overlay = MarksOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(28) })
        railTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK); visibility = View.GONE }
        railBottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(railTop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP).apply { topMargin = dp(24) })
        root.addView(railBottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM).apply { bottomMargin = dp(48) })
        stats = word("", Color.parseColor("#E6E8EA"), 12f, null).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        rateWord = word("", Color.parseColor("#F2DDB4"), 14f, null).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            addView(stats, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(word("HX", Color.parseColor("#7A8087"), 12f, null))
            addView(word("  −  ", Color.parseColor("#F2DDB4"), 18f) { stepRate(-1) })
            addView(rateWord)
            addView(word("  +  ", Color.parseColor("#F2DDB4"), 18f) { stepRate(+1) })
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(48 + 56) })
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
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
            // v117: in remote mode a single tap goes to the camera, which moves its armed mark there and corrects
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!remote) return false
                val inv = Matrix(); if (fitMatrix?.invert(inv) != true) return true
                val p = floatArrayOf(e.x, e.y); inv.mapPoints(p)
                val u = p[0] / surface.width; val v = p[1] / surface.height
                if (u in 0f..1f && v in 0f..1f) NdiReceiver.sendCommand(CameraCommand(tapX = u, tapY = v))
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

    // v120: Wi-Fi out of power save while the monitor is in front (the stream came in bursts without it)
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onResume() {
        super.onResume()
        startFinding()
        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "mantra-monitor").apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onPause() {
        super.onPause()
        finding = false
        NdiFinder.stop()
        runCatching { wifiLock?.release() }
        wifiLock = null
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

    /** Once a second: what this monitor receives and shows, from the engine's running totals. */
    private var lastTotals = 0L to 0L
    private var lastTotalsAt = 0L
    private var lastEngine: MonitorEngine? = null
    private val statsTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 1000)
            val e = engine ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val t = e.totals()
            if (e !== lastEngine) { lastEngine = e; lastTotals = t; lastTotalsAt = now; return }
            val dt = ((now - lastTotalsAt).coerceAtLeast(1)) / 1000.0
            val fps = (t.first - lastTotals.first) / dt
            val mbps = (t.second - lastTotals.second) * 8 / dt / 1_000_000.0
            lastTotals = t; lastTotalsAt = now
            stats.text = String.format(java.util.Locale.ROOT, "MONITOR  %.1f fps · %.1f Mbit/s", fps, mbps)
            stats.setTextColor(if (fps < 1) Color.parseColor("#FF3B30") else Color.parseColor("#E6E8EA"))
            if (!remote) info.visibility = View.VISIBLE
        }
    }

    /** The HX keys: the camera's stream bit rate one step down or up; the camera answers with what it took. */
    private fun stepRate(dir: Int) {
        if (!remote) return
        val i = rates.indexOfFirst { it >= cameraMbps }.let { if (it < 0) rates.size - 1 else it }
        val next = rates[(i + dir).coerceIn(0, rates.size - 1)]
        NdiReceiver.sendCommand(CameraCommand(streamMbps = next))
        rateWord.text = "$next Mbit/s…"
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
        fitMatrix = m
        overlay.toScreen = m
        turnKey.setTextColor(if (turns != 0) Color.parseColor("#33D17A") else Color.parseColor("#7A8087"))
    }

    private fun watch(source: String) {
        engine?.stop()
        current = source
        turns = prefs.getInt("turns:$source", 0)
        remote = false; keysShown = ""; railTop.visibility = View.GONE; railBottom.visibility = View.GONE
        overlay.marks = emptyList(); turnKey.visibility = View.VISIBLE
        (status.layoutParams as FrameLayout.LayoutParams).topMargin = dp(28); status.requestLayout()
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

    /**
     * v117: a Mantra camera answered. From now on the monitor wears its interface: the same keys (RailButton, shared
     * with the camera), its status line, its marks; and it stands the picture up as the camera holds it.
     */
    private fun onCamera(st: CameraState) {
        if (!cameraSeen) {
            cameraSeen = true; remote = true; turnKey.visibility = View.GONE
            // the status line goes under the camera's top rail
            (status.layoutParams as FrameLayout.LayoutParams).topMargin = dp(24 + 56 + 2); status.requestLayout()
        }
        if (st.turns != turns) { turns = st.turns; fit() }
        status.text = st.status.ifEmpty { st.cameraName }
        info.visibility = View.VISIBLE
        if (st.streamMbps > 0) { cameraMbps = st.streamMbps; rateWord.text = "${st.streamMbps} Mbit/s" }
        if (st.keys != keysShown) { keysShown = st.keys; drawKeys(st.keys) }
        overlay.marks = st.marks.split("|").mapNotNull { m ->
            val f = m.split(","); if (f.size < 7) null else runCatching {
                MarksOverlay.Mark(f[0].toInt(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toInt(), f[6] == "1")
            }.getOrNull()
        }
    }

    /** The camera's keys, the left rail on top and the right rail at the bottom, each a tap that presses it there. */
    private fun drawKeys(line: String) {
        val parts = line.split("|")
        val split = parts.indexOf("--").let { if (it < 0) parts.size else it }
        fun fill(rail: LinearLayout, items: List<String>) {
            rail.removeAllViews()
            for (it in items) {
                val f = it.split("~"); if (f.size < 4) continue
                val name = f[0]
                val key = RailButton(this).apply {
                    label = f[1]
                    sub = f[2].ifEmpty { null }
                    state = runCatching { RailButton.State.valueOf(f[3]) }.getOrDefault(RailButton.State.OFF)
                    tint = f.getOrNull(4)?.toIntOrNull()
                    glyph = when (name) {
                        "MARK0" -> RailButton.Glyph.SQUARE; "MARK1" -> RailButton.Glyph.CIRCLE; "MARK2" -> RailButton.Glyph.TRIANGLE
                        "gear" -> RailButton.Glyph.GEAR; "camera" -> RailButton.Glyph.CAMERA
                        else -> RailButton.Glyph.NONE
                    }
                    marked = false
                    setOnClickListener { NdiReceiver.sendCommand(CameraCommand(key = name)) }
                }
                rail.addView(key, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            rail.visibility = if (rail.childCount > 0) View.VISIBLE else View.GONE
        }
        fill(railTop, parts.take(split))
        fill(railBottom, parts.drop(split + 1))
    }

    private fun say(text: String) {
        status.text = (current?.let { "$it  ·  " } ?: "") + text
    }
}
