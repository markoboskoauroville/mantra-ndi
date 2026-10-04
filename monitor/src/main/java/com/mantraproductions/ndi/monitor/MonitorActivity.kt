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
import android.content.pm.ActivityInfo
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
import com.mantraproductions.ndi.Link
import com.mantraproductions.ndi.LinkClient
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
    // v131, MANTRA LINK: the camera's own window over our socket (LinkClient); its cameras found by NSD
    private var link: LinkClient? = null
    private val linkHosts = mutableMapOf<String, String>()        // "LINK name" -> host
    private lateinit var cleanRec: TextView
    private lateinit var cleanTime: TextView
    private lateinit var cleanBar: LinearLayout
    private lateinit var cleanSaid: TextView
    private var saidAt = ""
    private var linkClean = false
    private var nsd: android.net.nsd.NsdManager? = null
    private var nsdListener: android.net.nsd.NsdManager.DiscoveryListener? = null

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
        // v131: the CLEAN monitor: the picture, the record key and its counter, nothing else
        cleanRec = word("●", Color.parseColor("#FF3B30"), 44f) { link?.key("REC") }.apply { setPadding(0, 0, 0, 0) }
        cleanTime = word("", Color.WHITE, 22f, null)
        // v133: the record key never moves: an empty slot on its left as wide as the counter's on its right (v132's
        // bar grew when the counter appeared and pushed the key aside; the press to stop missed it)
        cleanBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(cleanRec, LinearLayout.LayoutParams(dp(96), dp(96)))
            cleanRec.gravity = Gravity.CENTER
            addView(cleanTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(cleanBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(24) })
        // v132: in CLEAN, what the camera just said (a refusal, a mode) for three seconds above the record key
        cleanSaid = word("", Color.parseColor("#E6E8EA"), 13f, null).apply {
            gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#88000000")); setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
        }
        root.addView(cleanSaid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(110) })
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
        surface.setOnTouchListener { _, ev -> if (link != null) linkTouch(ev) else taps.onTouchEvent(ev) }

        surface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                output = Surface(t)
                // `adb shell am start … --es link 192.168.1.102` connects straight to a camera (tests, the emulator)
                intent?.getStringExtra("link")?.let { h -> linkHosts["LINK $h"] = h; current = "LINK $h" }
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
        nsdListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }; nsdListener = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    /** The finder runs while the app is in front; the list on screen follows it. */
    /** v131: Mantra cameras announce their link on the network (NSD); each becomes a source "LINK name". */
    private fun findLinks() {
        val m = getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager
        nsd = m
        val l = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: android.net.nsd.NsdServiceInfo) {}
            override fun onServiceFound(i: android.net.nsd.NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching { m.resolveService(i, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(x: android.net.nsd.NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(x: android.net.nsd.NsdServiceInfo) {
                        val h = x.host?.hostAddress ?: return
                        ui.post { linkHosts["LINK ${x.serviceName}"] = h; if (picker.visibility == View.VISIBLE) fillPicker() }
                    }
                }) }
            }
        }
        runCatching { m.discoverServices(Link.SERVICE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }

    private fun startFinding() {
        if (nsdListener == null) findLinks()
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
    private var linkLast = 0L to 0L
    private val statsTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 1000)
            link?.let { lk ->
                val t = lk.frames.get() to lk.bytes.get()
                android.util.Log.i("MonitorStats", String.format(java.util.Locale.ROOT, "link %d fps, %.1f Mbit/s",
                    t.first - linkLast.first, (t.second - linkLast.second) * 8 / 1e6))
                linkLast = t
                return
            }
            val e = engine ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val t = e.totals()
            if (e !== lastEngine) { lastEngine = e; lastTotals = t; lastTotalsAt = now; return }
            val dt = ((now - lastTotalsAt).coerceAtLeast(1)) / 1000.0
            val fps = (t.first - lastTotals.first) / dt
            val mbps = (t.second - lastTotals.second) * 8 / dt / 1_000_000.0
            lastTotals = t; lastTotalsAt = now
            android.util.Log.i("MonitorStats", String.format(java.util.Locale.ROOT,
                "shown %.1f fps, %.1f Mbit/s, arrived %d, no room %d", fps, mbps, e.arrived.get(), e.noRoom.get()))
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
        for (name in linkHosts.keys.sorted()) {
            val on = name == current
            list.addView(word(name, if (on) Color.parseColor("#33D17A") else Color.parseColor("#F2DDB4"), 16f) {
                showPicker(false); watch(name)
            })
        }
        if (sources.isEmpty() && linkHosts.isEmpty()) {
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
        val lk = link
        val e = engine
        if (lk == null && e == null) return
        val vw = (lk?.width ?: e!!.lastWidth).toFloat(); val vh = (lk?.height ?: e!!.lastHeight).toFloat()
        val w = surface.width.toFloat(); val h = surface.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        fittedW = vw.toInt(); fittedH = vh.toInt()
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
        engine?.stop(); engine = null
        link?.stop(); link = null; linkClean = false; cleanBar.visibility = View.GONE; status.visibility = View.VISIBLE
        if (source.startsWith("LINK ")) { watchLink(source); return }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
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
     * v131: the camera's own window, over Mantra Link. Every key and its settings are on the picture itself and
     * every touch goes back to it; the monitor adds nothing of its own except, in CLEAN, the record key and counter.
     */
    private fun watchLink(source: String) {
        val host = linkHosts[source] ?: source.removePrefix("LINK ").takeIf { it.contains('.') } ?: run { say("Not found: $source"); return }
        current = source
        prefs.edit().putString("source", source).apply()
        turns = 0
        remote = false; railTop.visibility = View.GONE; railBottom.visibility = View.GONE; info.visibility = View.GONE
        overlay.marks = emptyList(); turnKey.visibility = View.GONE
        status.visibility = View.GONE
        fittedW = 0; fittedH = 0
        val out = output ?: run { say("No picture surface yet"); return }
        val c = LinkClient(host, Link.PORT, out,
            onStatus = { msg -> ui.post { status.visibility = View.VISIBLE; say(msg); ui.removeCallbacks(hideStatus); ui.postDelayed(hideStatus, 2500) } },
            onSize = { w, h -> ui.post { followCamera(w, h); fit() } },
            onState = { st -> ui.post { linkState(st) } })
        link = c
        c.start()
    }

    /**
     * v134: over the link the monitor holds the camera's orientation (Marko, 4.10.2026: "the noting phone is always
     * following the orientation of the pixel phone ... Now a phone is vertical and view is horizontal ... we have just
     * tiny tiny view"). The camera's window upright → this screen upright; the window across → this screen across.
     * Its own sensor no longer decides, so a monitor lying on a table never shows a portrait camera as a sliver.
     */
    private fun followCamera(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val want = if (h > w) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }

    private val hideStatus = Runnable { if (link != null) status.visibility = View.GONE }
    private val hideSaid = Runnable { cleanSaid.visibility = View.GONE }

    private fun linkState(st: Map<String, String>) {
        val clean = st["clean"] == "1"
        if (clean != linkClean) { linkClean = clean; cleanBar.visibility = if (clean) View.VISIBLE else View.GONE }
        val at = st["msgAt"] ?: ""
        if (at != saidAt) {
            val first = saidAt.isEmpty()
            saidAt = at
            val msg = st["msg"].orEmpty()
            if (!first && clean && msg.isNotBlank()) {
                cleanSaid.text = msg; cleanSaid.visibility = View.VISIBLE
                ui.removeCallbacks(hideSaid); ui.postDelayed(hideSaid, 3000)
            }
        }
        if (!clean) cleanSaid.visibility = View.GONE
        val rec = st["rec"] == "1"
        cleanRec.text = if (rec) "■" else "●"
        val ms = st["since"]?.toLongOrNull() ?: 0
        cleanTime.text = if (rec) String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60) else ""
    }

    private var cleanTapAt = 0L

    /** Every finger to the camera, as fractions of its picture; three fingers open the sources; CLEAN: a double tap returns. */
    private fun linkTouch(ev: MotionEvent): Boolean {
        val c = link ?: return false
        if (linkClean) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - cleanTapAt < 350) c.mode(false)
                cleanTapAt = now
            }
            return true
        }
        if (ev.pointerCount >= 3) {
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                c.touch(MotionEvent.ACTION_CANCEL, 0, intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f))
                showPicker(true)
            }
            return true
        }
        val inv = Matrix(); if (fitMatrix?.invert(inv) != true) return true
        val n = ev.pointerCount
        val ids = IntArray(n); val xs = FloatArray(n); val ys = FloatArray(n)
        for (i in 0 until n) {
            val p = floatArrayOf(ev.getX(i), ev.getY(i)); inv.mapPoints(p)
            ids[i] = ev.getPointerId(i)
            xs[i] = (p[0] / surface.width).coerceIn(0f, 1f); ys[i] = (p[1] / surface.height).coerceIn(0f, 1f)
        }
        c.touch(ev.actionMasked, ev.actionIndex, ids, xs, ys)
        return true
    }

    @Deprecated("the back key goes to the camera in link mode")
    override fun onBackPressed() {
        val c = link
        if (c != null && picker.visibility != View.VISIBLE) { c.key("BACK"); return }
        if (picker.visibility == View.VISIBLE) { showPicker(false); return }
        @Suppress("DEPRECATION") super.onBackPressed()
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
