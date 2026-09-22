package com.mantraproductions.ndi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The camera, and the two rails of keys in the black beside it.
 *
 *     LENS 1..4   the physical lenses this phone actually has
 *     LGHT        the lamp
 *     AF          the focus director: hold, notice, rack over a beat
 *     PEAK        edge detector on the monitor only
 *     SNAP        the sensor's own frame as a DNG, uncorrected
 *     LOG         which log curve the phone's tone mapper is applying
 *     ROT         a quarter turn of the preview, by hand
 *     HX / FULL   which kind of NDI, or neither
 *
 *     1..11       the LUT slots, down the other side
 *
 * Grey is off and green is on, everywhere, with no exceptions — that is the
 * whole of the interface language and it is why the keys can be this small.
 * Dark is a key this phone cannot honour, left in place rather than hidden so
 * the rail does not reshuffle itself between a Pixel 7 and a Pixel 7 Pro.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var focusSquare: FocusSquareView
    private lateinit var status: TextView
    private lateinit var geometry: TextView
    private lateinit var stage: LinearLayout
    private lateinit var railLeft: LinearLayout
    private lateinit var railRight: LinearLayout

    private lateinit var pipeline: CameraPipeline
    private lateinit var slots: LutSlots
    private lateinit var settings: Settings
    private lateinit var focus: FocusDirector

    private var lenses: List<CameraCatalogue.Lens> = emptyList()
    private var activeLens = 0
    private var activeSlot = 0            // 0 means no LUT
    private var curveIndex = 0
    private var manualQuarterTurns = 0
    private var peaking = false
    private var bufferSize = Size(1920, 1080)
    private var pendingSlot = 0

    private val lensKeys = mutableListOf<RailButton>()
    private val slotKeys = mutableListOf<RailButton>()
    private lateinit var pageKey: RailButton
    private lateinit var gearKey: RailButton

    /** Which five of the eleven slots the right rail is showing. */
    private var slotPage = 0
    private lateinit var lightKey: RailButton
    private lateinit var focusKey: RailButton
    private lateinit var peakKey: RailButton
    private lateinit var snapKey: RailButton
    private lateinit var logKey: RailButton
    private lateinit var rotKey: RailButton
    private lateinit var hxKey: RailButton
    private lateinit var fullKey: RailButton

    private val ui = Handler(Looper.getMainLooper())

    private companion object {
        /** Five keys of slots, then the page key, then the gear. */
        const val PAGE_SIZE = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trace.open(this)
        Trace.markStart()
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        focusSquare = findViewById(R.id.focusSquare)
        status = findViewById(R.id.status)
        geometry = findViewById(R.id.geometry)
        stage = findViewById(R.id.stage)
        railLeft = findViewById(R.id.railLeft)
        railRight = findViewById(R.id.railRight)

        pipeline = CameraPipeline(this)
        slots = LutSlots(this)
        settings = Settings(this)
        lenses = CameraCatalogue.lenses(this)
        Trace.state(
            "lenses: " + lenses.joinToString {
                it.label + " [" + it.id + (it.physicalId?.let { p -> ":$p" } ?: "") + "]"
            }
        )

        focus = FocusDirector(
            controls = { if (pipeline.isRunning) pipeline.engine else null },
            onState = { s -> ui.post { focusSquare.state = s } }
        )

        buildRails()
        layoutForOrientation(resources.configuration.orientation)

        // Android draws its camera-in-use indicator over the top of the screen
        // whenever this app is doing its job, and on the first run it sat on
        // top of the HX key. The rails are inset out of the system's way.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        focusSquare.onMoved = { x, y ->
            focus.target = x to y
            focus.bounds = focusSquare.normalisedBounds()
        }
        focusSquare.onTapped = { focus.focusHereAndHold() }

        // The trace is the only instrument that reaches a phone with no cable,
        // so it is always one long press away rather than behind a menu.
        status.setOnLongClickListener { exportTrace(); true }

        pipeline.listener = object : CameraPipeline.Listener {
            override fun onReady(tenBit: Boolean, codec: String, size: Size) {
                ui.post {
                    bufferSize = size
                    holdBufferSize()
                    applyPreviewTransform()
                    refreshKeys()
                    say("${size.width}x${size.height} ${if (tenBit) "10-bit" else "8-bit"} $codec")
                }
            }
            override fun onError(message: String) {
                ui.post { say(message); Trace.refused("pipeline", message) }
            }
            override fun onRate(fps: Double, megabitsPerSecond: Double, connections: Int) {
                ui.post { showRate(fps, megabitsPerSecond, connections) }
            }
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {
                // The view has just taken the buffer size for itself.
                holdBufferSize()
                applyPreviewTransform()
            }
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit

            /**
             * The session's surface dies with the TextureView. The pipeline is
             * torn down here rather than left pointing at a surface that is
             * gone, which is what made the picture come back frozen.
             */
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                pipeline.stop()
                return true
            }
        }
    }

    // --- the rails -----------------------------------------------------------

    private fun buildRails() {
        for (i in 1..4) {
            val key = newKey("L$i") { chooseLens(i - 1) }
            lensKeys.add(key)
            railLeft.addView(key)
        }
        lightKey = newKey("LGHT") { toggleLight() }.also { railLeft.addView(it) }
        focusKey = newKey("AF") { toggleAutoFocus() }.also { railLeft.addView(it) }
        peakKey = newKey("PEAK") { togglePeaking() }.also { railLeft.addView(it) }
        snapKey = newKey("SNAP") { takeSnap() }.also { railLeft.addView(it) }
        logKey = newKey("LOG") { nextCurve() }.also { railLeft.addView(it) }
        rotKey = newKey("ROT") { turnPreview() }.also { railLeft.addView(it) }
        hxKey = newKey("HX") { toggleMode(CameraPipeline.Mode.HX) }.also { railLeft.addView(it) }
        fullKey = newKey("FULL") { toggleMode(CameraPipeline.Mode.FULL) }
            .also { railLeft.addView(it) }

        // Five slots at a time, not eleven. Eleven keys down the side of a
        // phone are each too small to hit with a thumb, and the right rail now
        // also has to carry the way into settings. So the slots are paged, and
        // the sixth key turns the page.
        for (i in 1..PAGE_SIZE) {
            val position = i
            val key = newKey("") { chooseSlot(slotFor(position)) }
            key.setOnLongClickListener { offerSlot(slotFor(position)); true }
            slotKeys.add(key)
            railRight.addView(key)
        }
        pageKey = newKey("") { turnSlotPage() }.also {
            it.glyph = RailButton.Glyph.DOWN
            railRight.addView(it)
        }
        gearKey = newKey("") { openSettings() }.also {
            it.glyph = RailButton.Glyph.GEAR
            railRight.addView(it)
        }
    }

    /** Which slot the key at [position] on the current page stands for, or 0. */
    private fun slotFor(position: Int): Int {
        val slot = slotPage * PAGE_SIZE + position
        return if (slot in 1..LutSlots.COUNT) slot else 0
    }

    private val pageCount: Int
        get() = (LutSlots.COUNT + PAGE_SIZE - 1) / PAGE_SIZE

    /**
     * The next five, wrapping at the end.
     *
     * The arrow points the way the next tap will go, which is down until there
     * is nothing below and then back up to the start — so the key always says
     * what it is about to do rather than where you happen to be.
     */
    private fun turnSlotPage() {
        slotPage = (slotPage + 1) % pageCount
        Trace.control("LUT page", slotPage + 1, "of $pageCount")
        refreshKeys()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun newKey(label: String, onTap: () -> Unit): RailButton =
        RailButton(this).apply {
            this.label = label
            setOnClickListener { onTap() }
        }

    /**
     * Across in landscape, down in portrait.
     *
     * The keys keep their order and their meaning; only the direction of the
     * rail changes, so a hand that has learned this camera keeps it when the
     * phone turns. Every key is re-sized here because a rail that runs across
     * wants square-ish keys and one that runs down wants wide ones.
     */
    private fun layoutForOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        stage.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        railLeft.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        railRight.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val thickness = (44 * resources.displayMetrics.density).toInt()
        for (rail in listOf(railLeft, railRight)) {
            val lp = rail.layoutParams as LinearLayout.LayoutParams
            if (landscape) {
                lp.width = thickness
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = thickness
            }
            rail.layoutParams = lp

            for (i in 0 until rail.childCount) {
                val child = rail.getChildAt(i)
                val margin = (2 * resources.displayMetrics.density).toInt()
                val klp = LinearLayout.LayoutParams(0, 0).apply {
                    if (landscape) {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = 0
                    } else {
                        width = 0
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    weight = 1f
                    setMargins(margin, margin, margin, margin)
                }
                child.layoutParams = klp
            }
        }

        val stageLp = (findViewById<View>(R.id.picture)).layoutParams as LinearLayout.LayoutParams
        if (landscape) {
            stageLp.width = 0; stageLp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            stageLp.width = ViewGroup.LayoutParams.MATCH_PARENT; stageLp.height = 0
        }
        stageLp.weight = 1f
        findViewById<View>(R.id.picture).layoutParams = stageLp

        applyPreviewTransform()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Trace.state("rotated: orientation=${newConfig.orientation}")
        layoutForOrientation(newConfig.orientation)
    }

    // --- the camera ----------------------------------------------------------

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (lenses.isEmpty()) { say("This phone reports no usable camera"); return }

        val texture = preview.surfaceTexture ?: return
        val lens = lenses.getOrNull(activeLens) ?: lenses.first()

        // The buffer size has to be right before the session is built.
        bufferSize = pipeline.previewSizeFor(lens.id)
        holdBufferSize()
        applyPreviewTransform()

        val curve = LogCurves.Curve.entries[curveIndex]
        focus.holdMs = settings.focusHoldMs
        focus.rampMs = settings.focusRackMs
        pipeline.start(
            cameraId = lens.id,
            physicalId = lens.physicalId,
            previewSurface = Surface(texture),
            sourceName = Mechanism.sanitizeSourceName(settings.sourceName),
            curve = curve,
            wantTenBit = settings.wantTenBit,
            bitRate = settings.bitRateMbps * 1_000_000
        )
        applyLook()
        refreshKeys()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else say("Camera permission refused, there is nothing to show")
    }

    override fun onResume() {
        super.onResume()
        // The camera session is lost while backgrounded even with a foreground
        // service, so it is rebuilt rather than tested for.
        if (preview.isAvailable && !pipeline.isRunning) openCamera()
        ui.post(rateTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(rateTick)
        focus.stop()
        pipeline.stop()
    }

    private val rateTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) pipeline.sampleRate()
            ui.postDelayed(this, 1000)
        }
    }

    /**
     * The picture: turned the right way up, and never stretched.
     *
     * Both halves of this have been wrong before, and they fail differently.
     * The wrong angle is obvious and everybody reports it. The wrong scale is
     * not — a stretched picture looks fine on a test pattern and only shows on
     * a face — and it has shipped repeatedly because the arithmetic lived here,
     * inside a call that can only be checked by holding a phone up to something
     * rectangular.
     *
     * So the arithmetic is not here any more. `Mechanism.previewRotation` and
     * `Mechanism.previewFit` are pure, and the test suite asserts the one thing
     * that matters: whatever the view, whatever the buffer and whichever way it
     * is turned, what reaches the screen has the buffer's own shape.
     *
     * The angle is the sensor's mounting against the phone's rotation, which is
     * right on nearly every device — and `ROT` adds quarter turns by hand for
     * the ones it is not, because a sideways picture is not something to debug
     * around on a shoot.
     */
    private fun applyPreviewTransform() {
        val vw = preview.width
        val vh = preview.height
        if (vw <= 0 || vh <= 0) return

        val displayDegrees = when (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        ) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensor = if (pipeline.isRunning) pipeline.engine.sensorOrientation else 90
        val front = pipeline.isRunning && pipeline.engine.isFrontFacing
        val auto = Mechanism.previewRotation(sensor, displayDegrees, front)
        val applied = ((auto + manualQuarterTurns * 90) % 360 + 360) % 360

        val scale = Mechanism.previewFit(vw, vh, bufferSize.width, bufferSize.height, applied)
        val matrix = Matrix()
        matrix.postRotate(applied.toFloat(), vw / 2f, vh / 2f)
        matrix.postScale(scale[0], scale[1], vw / 2f, vh / 2f)
        preview.setTransform(matrix)

        val line = "sensor $sensor · disp $displayDegrees · rot $applied" +
            (if (manualQuarterTurns != 0) " (auto $auto +${manualQuarterTurns * 90})" else "") +
            " · buf ${bufferSize.width}x${bufferSize.height} · view ${vw}x$vh"
        geometry.text = line
        Trace.control("preview geometry", line, applied)
    }

    /**
     * Puts the buffer size back, because the TextureView keeps taking it away.
     *
     * This is the stretch, and it is an interaction rather than a formula —
     * which is why six attempts at the arithmetic never fixed it. A TextureView
     * sets its SurfaceTexture's default buffer size to *the view's own pixel
     * size* whenever the view is laid out or resized. Set it once when the
     * camera opens and the next layout silently replaces 1920x1080 with
     * whatever shape the view happens to be, and the camera then scales its
     * output into that, which is a stretched picture arriving from below with
     * nothing in any log to say so.
     *
     * So it is re-asserted on every size change, and the numbers go on screen.
     */
    private fun holdBufferSize() {
        preview.surfaceTexture?.setDefaultBufferSize(bufferSize.width, bufferSize.height)
    }

    // --- the keys ------------------------------------------------------------

    private fun chooseLens(index: Int) {
        if (index >= lenses.size) return
        if (index == activeLens && pipeline.isRunning) return
        activeLens = index
        Trace.control("lens", index + 1, lenses[index].label)
        pipeline.stop()
        openCamera()
    }

    private fun toggleLight() {
        val on = !pipeline.engine.torchOn
        if (!pipeline.engine.setTorch(on)) say("This lens has no lamp")
        refreshKeys()
    }

    private fun toggleAutoFocus() {
        val next = if (focus.mode == FocusDirector.Mode.AUTO) FocusDirector.Mode.MANUAL
        else FocusDirector.Mode.AUTO
        focus.setMode(next)
        Trace.control("focus mode", next.name, next.name)
        refreshKeys()
    }

    private fun togglePeaking() {
        peaking = !peaking
        applyLook()
        refreshKeys()
    }

    private fun takeSnap() {
        if (!pipeline.snapAvailable) { say("No RAW target in this session"); return }
        snapKey.state = RailButton.State.ARMED
        val rotation = ((pipeline.engine.sensorOrientation) % 360 + 360) % 360
        pipeline.snap.take(pipeline.engine, rotation) { path ->
            ui.post {
                refreshKeys()
                say(if (path != null) "snap → $path" else "snap failed, see the trace")
            }
        }
    }

    private fun nextCurve() {
        curveIndex = (curveIndex + 1) % LogCurves.Curve.entries.size
        val curve = LogCurves.Curve.entries[curveIndex]
        val ok = pipeline.setLogCurve(curve)
        say(if (ok) "${curve.vendor} ${curve.displayName}" else "${curve.displayName} refused")
        refreshKeys()
    }

    private fun turnPreview() {
        manualQuarterTurns = (manualQuarterTurns + 1) % 4
        applyPreviewTransform()
        refreshKeys()
    }

    private fun toggleMode(want: CameraPipeline.Mode) {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        val next = if (pipeline.mode == want) CameraPipeline.Mode.OFF else want
        pipeline.setMode(next)
        refreshKeys()
    }

    // --- the LUT slots -------------------------------------------------------

    private fun chooseSlot(index: Int) {
        if (index == 0) return
        if (slots.cube(index) == null) { offerSlot(index); return }
        activeSlot = if (activeSlot == index) 0 else index
        applyLook()
        refreshKeys()
        Trace.control("LUT", index, if (activeSlot == 0) "off" else slots.slot(index).label)
    }

    private fun offerSlot(index: Int) {
        if (index == 0) return
        val slot = slots.slot(index)
        if (!slot.loaded) { pickFile(index); return }
        AlertDialog.Builder(this)
            .setTitle("Slot $index — ${slot.label}")
            .setMessage("${slot.size} cubed")
            .setPositiveButton("Replace") { _, _ -> pickFile(index) }
            .setNegativeButton("Empty it") { _, _ ->
                if (activeSlot == index) activeSlot = 0
                slots.clear(index); applyLook(); refreshKeys()
            }
            .setNeutralButton("Keep", null)
            .show()
    }

    private fun pickFile(index: Int) {
        pendingSlot = index
        openDocument.launch(arrayOf("*/*"))
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val index = pendingSlot
            pendingSlot = 0
            if (uri == null || index == 0) return@registerForActivityResult
            val size = slots.import(index, uri, displayNameOf(uri))
            if (size == null) say("Slot $index: that file is not a 3D cube")
            else {
                say("Slot $index: ${slots.slot(index).label}, $size cubed")
                activeSlot = index
                applyLook()
            }
            refreshKeys()
        }

    private fun displayNameOf(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * The monitor LUT and the peaking, in one shader pass.
     *
     * Both are on the View, which means both are on the monitor and neither is
     * on the wire. That is deliberate and it is what a broadcast camera does:
     * the stream carries the log picture the phone's tone mapper produced, and
     * the LUT is how the operator judges it. Baking a display LUT into the
     * stream would throw away the range the log curve was chosen to keep.
     */
    private fun applyLook() {
        val cube = if (activeSlot > 0) slots.cube(activeSlot) else null
        val ok = PreviewEffects.apply(
            view = preview,
            lut = cube != null,
            peak = peaking,
            peakColour = settings.peakColour,
            sensitivity = settings.peakSensitivity,
            uploaded = cube
        )
        if (!ok) {
            say("This phone will not run the preview shader")
            Trace.refused("preview shader", "rejected by the graphics layer")
            peaking = false
            activeSlot = 0
        }
    }

    // --- what the keys say ----------------------------------------------------

    private fun refreshKeys() {
        lensKeys.forEachIndexed { i, key ->
            val lens = lenses.getOrNull(i)
            key.state = when {
                lens == null -> RailButton.State.DEAD
                i == activeLens && pipeline.isRunning -> RailButton.State.ON
                else -> RailButton.State.OFF
            }
            key.sub = lens?.equivalentMm?.takeIf { it > 0 }?.let { "${it}mm" }
        }

        lightKey.state = when {
            !pipeline.isRunning || !pipeline.engine.hasFlash() -> RailButton.State.DEAD
            pipeline.engine.torchOn -> RailButton.State.ON
            else -> RailButton.State.OFF
        }
        focusKey.label = if (focus.mode == FocusDirector.Mode.AUTO) "AF" else "MF"
        focusKey.state =
            if (focus.mode == FocusDirector.Mode.AUTO) RailButton.State.ON
            else RailButton.State.OFF
        peakKey.state = if (peaking) RailButton.State.ON else RailButton.State.OFF
        snapKey.state =
            if (pipeline.snapAvailable) RailButton.State.OFF else RailButton.State.DEAD
        logKey.sub = LogCurves.Curve.entries[curveIndex].displayName.take(5)
        logKey.state =
            if (LogCurves.Curve.entries[curveIndex] == LogCurves.Curve.REC709)
                RailButton.State.OFF
            else RailButton.State.ON
        rotKey.sub = "${manualQuarterTurns * 90}"
        rotKey.state = if (manualQuarterTurns == 0) RailButton.State.OFF else RailButton.State.ON
        hxKey.state = when {
            pipeline.mode == CameraPipeline.Mode.HX -> RailButton.State.ON
            pipeline.isRunning && !pipeline.hxAvailable -> RailButton.State.DEAD
            else -> RailButton.State.OFF
        }
        fullKey.state = when {
            pipeline.mode == CameraPipeline.Mode.FULL -> RailButton.State.ON
            pipeline.isRunning && !pipeline.fullAvailable -> RailButton.State.DEAD
            else -> RailButton.State.OFF
        }

        slotKeys.forEachIndexed { i, key ->
            val index = slotFor(i + 1)
            if (index == 0) {
                // The last page of eleven is one slot and four blanks. Dark
                // rather than hidden, so the rail keeps its shape.
                key.label = ""
                key.sub = null
                key.state = RailButton.State.DEAD
                return@forEachIndexed
            }
            val slot = slots.slot(index)
            key.label = index.toString()
            key.sub = slot.label?.take(4)
            key.state =
                if (activeSlot == index) RailButton.State.ON else RailButton.State.OFF
        }

        // The page key carries which page it is on, because a rail showing
        // 6..10 and a rail showing 1..5 look identical at a glance otherwise.
        val last = slotPage == pageCount - 1
        pageKey.glyph = if (last) RailButton.Glyph.UP else RailButton.Glyph.DOWN
        pageKey.sub = "${slotPage + 1}/$pageCount"
        pageKey.state =
            if (activeSlot > 0 && (activeSlot - 1) / PAGE_SIZE != slotPage) RailButton.State.ARMED
            else RailButton.State.OFF
        gearKey.state = RailButton.State.OFF
    }

    // --- the status line ------------------------------------------------------

    private var lastSaid = ""

    private fun say(text: String) {
        lastSaid = text
        status.text = text
        Trace.state(text)
    }

    private fun showRate(fps: Double, mbps: Double, connections: Int) {
        val mode = when (pipeline.mode) {
            CameraPipeline.Mode.HX -> "NDI HX"
            CameraPipeline.Mode.FULL -> "NDI FULL"
            CameraPipeline.Mode.OFF -> "not sending"
        }
        val depth = if (pipeline.isTenBit) "10-bit" else "8-bit"
        // "raw" on the full path, because that number is what was handed to
        // the SDK and not what left the phone: full NDI compresses to SpeedHQ
        // on its way out, so the wire carries a fraction of it. On HX the
        // encoder's own output is measured and the number is the wire.
        val rate = if (pipeline.mode == CameraPipeline.Mode.FULL) {
            String.format("%.0f Mbit/s raw", mbps)
        } else {
            String.format("%.1f Mbit/s", mbps)
        }
        status.text = if (pipeline.mode == CameraPipeline.Mode.OFF) {
            "$mode · $depth · $lastSaid"
        } else {
            String.format(
                "%s · %s · %.1f fps · %s · %d watching",
                mode, depth, fps, rate, connections.coerceAtLeast(0)
            )
        }
    }

    private fun exportTrace() {
        val file = Trace.file()
        val text = file?.readText() ?: Trace.lines().joinToString("\n")
        val name = file?.name ?: "trace.txt"
        val where = Downloads.writeText(this, name, text)
        Toast.makeText(
            this,
            where?.let { "Trace → $it" } ?: "The trace could not be written",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline.stop()
    }
}
