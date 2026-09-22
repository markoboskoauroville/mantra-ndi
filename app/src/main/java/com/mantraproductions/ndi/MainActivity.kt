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
 *     M / CTRL    manual exposure, and the columns on the picture
 *     HX / FULL   which kind of NDI, or neither
 *
 *     REC         the take, at the top of the other rail, and the LUT
 *     1..11       slots below it
 *
 * The audio meter runs down the inside edge of the picture whenever the app
 * does, because a dead microphone found after the take is a take that happens
 * again.
 *
 * Grey is off and green is on, everywhere, with no exceptions — that is the
 * whole of the interface language and it is why the keys can be this small.
 * Dark is a key this phone cannot honour, left in place rather than hidden so
 * the rail does not reshuffle itself between a Pixel 7 and a Pixel 7 Pro.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var focusSquare: FocusSquareView
    private lateinit var zones: ControlZones
    private lateinit var status: TextView
    private lateinit var geometry: TextView
    private lateinit var vu: VuMeterView
    private lateinit var iris: TextView
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

    /** The camera's own turn, as last decoded from the texture matrix. */
    private var lastProducerDegrees = -1
    private var peaking = false
    private var bufferSize = Size(1920, 1080)
    private var pendingSlot = 0

    /** Manual exposure, and whether the invisible columns are listening. */
    private var manual = false
    private var zonesOn = false
    private var iso = 400
    private var shutterNs = 1_000_000_000L / 60
    private var focusFraction = 0f

    /** Colour temperature: tungsten at the left of the fader, daylight right. */
    private var wbAuto = true
    private var wbKelvin = 5600

    /**
     * The microphone, held for as long as the app is in front.
     *
     * One reader, not two. The old build gave the mic to a meter and took it
     * back for the encoder when a take started, and a handover is a thing that
     * can fail — when it failed, the take had no sound and the meter said it
     * did. Now the meter owns it and the take borrows its samples.
     */
    private var meter: AudioMeter? = null
    private var recordingSince = 0L
    private var microphoneAsked = false

    private val lensKeys = mutableListOf<RailButton>()
    private val slotKeys = mutableListOf<RailButton>()
    private lateinit var recKey: RecordButtonView
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
    private lateinit var manualKey: RailButton
    private lateinit var ctrlKey: RailButton
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
        zones = findViewById(R.id.zones)
        status = findViewById(R.id.status)
        geometry = findViewById(R.id.geometry)
        vu = findViewById(R.id.vu)
        iris = findViewById(R.id.iris)
        stage = findViewById(R.id.stage)
        railLeft = findViewById(R.id.railLeft)
        railRight = findViewById(R.id.railRight)

        pipeline = CameraPipeline(this)
        slots = LutSlots(this)
        settings = Settings(this)
        manualQuarterTurns = settings.quarterTurns
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

        zones.onDrag = { index, delta -> nudge(index, delta) }
        zones.onGrab = { refreshZones() }
        zones.onTap = { index -> handBack(index) }

        // The trace is the only instrument that reaches a phone with no cable,
        // so it is always one long press away rather than behind a menu.
        status.setOnLongClickListener { exportTrace(); true }

        pipeline.listener = object : CameraPipeline.Listener {
            override fun onReady(tenBit: Boolean, codec: String, size: Size) {
                ui.post {
                    bufferSize = size
                    holdBufferSize()
                    applyPreviewTransform()
                    say("${size.width}x${size.height} ${if (tenBit) "10-bit" else "8-bit"} $codec")
                    if (restoreMode != CameraPipeline.Mode.OFF) {
                        val want = restoreMode
                        restoreMode = CameraPipeline.Mode.OFF
                        pipeline.setMode(want)
                    }
                    refreshKeys()
                }
            }
            override fun onError(message: String) {
                ui.post { say(message); Trace.refused("pipeline", message) }
            }
            override fun onRate(fps: Double, megabitsPerSecond: Double, connections: Int) {
                ui.post { showRate(fps, megabitsPerSecond, connections); refreshZones() }
            }
        }

        // THE STRETCH.
        //
        // `setTransform` is in the view's own coordinates and it does not
        // follow the view when the view changes size. The transform was being
        // computed on surface-size changes only — and a TextureView inside a
        // weighted LinearLayout is measured more than once before it settles,
        // so the matrix could be worked out against one width and left applied
        // to another. Scaled about the centre, that is exactly a squeeze, and
        // it explains the thing that made no sense: the NDI output was normal
        // while the preview was not, from the same buffer, with the readout
        // reporting squeeze 1.000 — because by the time it printed, the numbers
        // were right and the matrix was old.
        //
        // The transform is now recomputed whenever the view is laid out, which
        // is the only moment that can ever invalidate it.
        preview.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                holdBufferSize()
                applyPreviewTransform()
            }
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {
                // The view has just taken the buffer size for itself.
                holdBufferSize()
                applyPreviewTransform()
            }
            /**
             * The producer's transform is not known until frames are flowing.
             *
             * It is empty before the first frame and it can change when the
             * camera reconfigures, so the one moment it can be trusted is on a
             * frame. Decoded here and acted on only when it actually changes,
             * which is a handful of times in a session rather than thirty times
             * a second.
             */
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
                val m = FloatArray(16)
                runCatching { t.getTransformMatrix(m) }
                if (Mechanism.producerRotation(m) != lastProducerDegrees) applyPreviewTransform()
            }

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
        manualKey = newKey("M") { toggleManual() }.also { railLeft.addView(it) }
        ctrlKey = newKey("CTRL") { toggleZones() }.also { railLeft.addView(it) }
        hxKey = newKey("HX") { toggleMode(CameraPipeline.Mode.HX) }.also { railLeft.addView(it) }
        fullKey = newKey("FULL") { toggleMode(CameraPipeline.Mode.FULL) }
            .also { railLeft.addView(it) }

        // Record, at the top of the right rail where a thumb already is.
        //
        // Not a key like the others on purpose: it is the only control on this
        // camera whose state has to be readable without being read, so it is a
        // red circle that fills and counts rather than a word that turns green.
        recKey = RecordButtonView(this).also {
            it.setOnClickListener { toggleRecording() }
            railRight.addView(it)
        }

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
     * Across in landscape, down in portrait. Both, like any app on a phone.
     *
     * The keys keep their order and their meaning; only the direction of the
     * rail changes, so a hand that has learned this camera keeps it when the
     * phone turns. Every key is re-sized here because a rail that runs down the
     * side wants square-ish keys and one that runs across the top wants wide
     * ones.
     *
     * This was locked to landscape for two versions, to get the rotation
     * argument down to one case while it was being solved. It is solved — the
     * camera's own quarter turn is read and taken off — so the lock has done
     * its job and the phone can be held either way again.
     */
    private fun layoutForOrientation(orientation: Int) {
        val landscape = orientation != Configuration.ORIENTATION_PORTRAIT
        stage.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        railLeft.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        railRight.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val thickness = (44 * resources.displayMetrics.density).toInt()
        val margin = (2 * resources.displayMetrics.density).toInt()
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
                rail.getChildAt(i).layoutParams = LinearLayout.LayoutParams(
                    if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    weight = 1f
                    setMargins(margin, margin, margin, margin)
                }
            }
        }

        val stageLp = (findViewById<View>(R.id.picture)).layoutParams as LinearLayout.LayoutParams
        if (landscape) {
            stageLp.width = 0
            stageLp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            stageLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            stageLp.height = 0
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
            // Both at once. Asking for the microphone later, at the moment the
            // record key is pressed, is a dialog in the middle of a take.
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                1
            )
            return
        }
        if (lenses.isEmpty()) { say("This phone reports no usable camera"); return }

        val texture = preview.surfaceTexture ?: return
        val lens = lenses.getOrNull(activeLens) ?: lenses.first()

        // The resolution first: it decides the session, and a session cannot
        // be resized once it is built.
        pipeline.maxWidth = settings.captureWidth
        // The buffer size has to be right before the session is built.
        bufferSize = pipeline.previewSizeFor(lens.id, lens.physicalId)
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
        if (requestCode == 2) {
            // The microphone alone. The camera is already running; only the
            // meter and the sound on a take were waiting for this.
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMeter()
            else say("No microphone: takes will be silent and the meter is dark")
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
            startMeter()
        } else {
            say("Camera permission refused, there is nothing to show")
        }
    }

    override fun onResume() {
        super.onResume()
        // The camera session is lost while backgrounded even with a foreground
        // service, so it is rebuilt rather than tested for.
        if (preview.isAvailable && !pipeline.isRunning) openCamera()
        startMeter()
        ui.post(rateTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(rateTick)
        focus.stop()
        // The file is closed before the camera goes, not after: a take whose
        // encoder disappeared underneath it has no moov atom and opens nowhere.
        if (pipeline.isRecording) stopRecording()
        pipeline.stop()
        meter?.stop()
        meter = null
        vu.dead = true
        vu.reset()
        refreshKeys()
    }

    /**
     * The meter runs whenever the app is in front, take or no take.
     *
     * That is the whole point of having one. A microphone that is dead, muted
     * at the socket, or pointed at nothing is something to find out about
     * before the take, and a meter that only appears once recording starts
     * reports it afterwards, when it is a reshoot rather than a fix.
     */
    private fun startMeter() {
        if (meter?.isRunning == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            vu.dead = true
            Trace.refused("audio meter", "no microphone permission")
            // Asked here rather than only beside the camera.
            //
            // On a phone that already had this app installed, the camera was
            // granted long ago, so the pair of permissions beside `openCamera`
            // is never reached and the microphone is never asked for at all —
            // which is why his first take came out silent with "no microphone
            // permission" in the trace and no dialog in sight. Asked once.
            if (!microphoneAsked) {
                microphoneAsked = true
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 2
                )
            }
            refreshKeys()
            return
        }
        val m = AudioMeter { rms -> ui.post { vu.setLevel(rms) } }
        if (m.start()) {
            meter = m
            vu.dead = false
        } else {
            vu.dead = true
        }
        refreshKeys()
    }

    private val rateTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) pipeline.sampleRate()
            if (pipeline.isRecording) {
                recKey.elapsedSeconds =
                    (android.os.SystemClock.elapsedRealtime() - recordingSince) / 1000
            }
            ui.postDelayed(this, 1000)
        }
    }

    // --- the take -------------------------------------------------------------

    /**
     * Record, independent of both NDI keys.
     *
     * A camera that can only record while it is streaming is not a camera. The
     * encoder is in the session whether or not anything is being sent, so the
     * file costs a write and the same frames go to the wire and to the card.
     */
    private fun toggleRecording() {
        if (pipeline.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        if (meter == null) say("No microphone: this take will have no sound")
        val where = pipeline.startRecording(this, meter) ?: run { refreshKeys(); return }
        recordingSince = android.os.SystemClock.elapsedRealtime()
        recKey.elapsedSeconds = 0
        recKey.recording = true
        say("Recording → $where")
        refreshKeys()
    }

    private fun stopRecording() {
        val frames = pipeline.recordedFrames
        val drops = pipeline.recordedDrops
        val where = pipeline.stopRecording()
        recKey.recording = false
        recKey.elapsedSeconds = 0
        say(
            when {
                where == null -> "Nothing was written; the file was removed"
                drops > 0 -> "Saved → $where ($frames frames, $drops refused)"
                else -> "Saved → $where ($frames frames)"
            }
        )
        refreshKeys()
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

        // What the camera already did, before we were handed the frame.
        //
        // A TextureView applies its producer's transform matrix before any of
        // this runs, and this phone's camera puts a quarter turn in it. Seven
        // attempts at the angle argued about sensor against display and every
        // one of them was adding a correct rotation on top of one that was
        // already there. It is read rather than assumed, so a phone whose
        // camera turns nothing is left exactly as it was.
        val producer = FloatArray(16)
        runCatching { preview.surfaceTexture?.getTransformMatrix(producer) }
        val producerDegrees = Mechanism.producerRotation(producer)
        lastProducerDegrees = producerDegrees

        val auto = Mechanism.previewRotation(sensor, displayDegrees, front, producerDegrees)
        val applied = ((auto + manualQuarterTurns * 90) % 360 + 360) % 360

        // THE SQUASH.
        //
        // A producer transform moves texture coordinates, not the view: the
        // quad is still drawn at the view's own size, so a camera that
        // transposes the frame hands over content whose width and height have
        // swapped while the quad has not. v76 took the turn off the angle and
        // the picture came up the right way round — which is why it looked so
        // nearly right — but the fit was still computed against 1920x1080 when
        // 1080x1920 had arrived, and left the picture in a strip down the
        // middle instead of filling the frame edge to edge.
        val effective = Mechanism.effectiveBuffer(
            bufferSize.width, bufferSize.height, producerDegrees
        )
        val scale = Mechanism.previewFit(vw, vh, effective[0], effective[1], applied)
        val matrix = Matrix()
        matrix.postRotate(applied.toFloat(), vw / 2f, vh / 2f)
        matrix.postScale(scale[0], scale[1], vw / 2f, vh / 2f)
        preview.setTransform(matrix)

        // Measured on what actually reaches the screen, turned and fitted,
        // against the shape the picture is really meant to be.
        val shown = Mechanism.displayedAspect(vw, vh, effective[0], effective[1], applied)
        val squeeze = if (shown > 0.0) shown / (bufferSize.width.toDouble() / bufferSize.height)
        else 1.0

        val line = "sensor $sensor · disp $displayDegrees · cam $producerDegrees · rot $applied" +
            (if (manualQuarterTurns != 0) " (auto $auto +${manualQuarterTurns * 90})" else "") +
            " · buf ${bufferSize.width}x${bufferSize.height} · view ${vw}x$vh" +
            " · squeeze " + String.format("%.3f", squeeze) +
            (if (kotlin.math.abs(squeeze - 1.0) > 0.01) "  STRETCHED" else "")
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
        // And the box takes the picture's shape, rather than the picture being
        // made to take the box's.
        findViewById<AspectFrame>(R.id.picture).aspect =
            bufferSize.width.toDouble() / bufferSize.height
    }

    // --- the keys ------------------------------------------------------------

    /**
     * A new lens, without losing the stream.
     *
     * Changing lens means a new capture session, and a new session used to mean
     * the NDI source closed and the mode went back to off — so every lens
     * change was a dropped source at the far end and a key he had to press
     * again. The mode is remembered across the rebuild and put back the moment
     * the camera is ready, and the NDI source itself is kept open, so a
     * receiver sees a short freeze rather than a source that vanished.
     */
    private fun chooseLens(index: Int) {
        if (index >= lenses.size) return
        if (index == activeLens && pipeline.isRunning) return
        activeLens = index
        restoreMode = pipeline.mode
        Trace.control("lens", index + 1, lenses[index].label + ", keeping " + restoreMode)
        pipeline.stop(keepSource = restoreMode != CameraPipeline.Mode.OFF)
        openCamera()
    }

    /** The mode to put back once the new lens is live. */
    private var restoreMode: CameraPipeline.Mode = CameraPipeline.Mode.OFF

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

    /**
     * Manual exposure, or the phone's own.
     *
     * Leaving auto starts from the values auto had already settled on rather
     * than from a number written here, so the picture does not jump the moment
     * the key is pressed — which is the whole reason an operator distrusts a
     * manual switch.
     */
    private fun toggleManual() {
        manual = !manual
        if (manual) {
            iso = pipeline.engine.lastIso ?: iso
            shutterNs = pipeline.engine.lastExposureNs ?: shutterNs
            pipeline.engine.setManualExposure(iso, shutterNs)
        } else {
            pipeline.engine.setAutoExposure()
        }
        refreshZones()
        refreshKeys()
    }

    /**
     * The columns on, and the focus box off.
     *
     * Exclusive on purpose: a tap on the picture that could mean "focus here"
     * and could mean "start changing ISO" is a tap that means neither.
     */
    private fun toggleZones() {
        zonesOn = !zonesOn
        zones.visibility = if (zonesOn) View.VISIBLE else View.GONE
        iris.visibility = if (zonesOn) View.VISIBLE else View.GONE
        focusSquare.visibility = if (zonesOn) View.GONE else View.VISIBLE
        Trace.control("controls", if (zonesOn) "on" else "off",
            if (zonesOn) "focus box put away" else "focus box back")
        refreshZones()
        refreshKeys()
    }

    /**
     * One fader moved. Right is more of everything.
     *
     * ISO and shutter move in stops rather than in units, because a stop is
     * what an operator thinks in and a linear sweep across 50..12800 spends
     * nine tenths of its travel in a range nobody uses. A full sweep of the
     * track is six stops, which is the whole of a usable range in one gesture
     * and still fine enough to place a value with a thumb.
     */
    private fun nudge(index: Int, delta: Float) {
        val engine = pipeline.engine
        when (index) {
            0 -> {
                // Dragging a fader takes it, rather than doing nothing until a
                // key somewhere else has been pressed first. A fader that has
                // to be armed is a fader that looks broken.
                if (!manual) { toggleManual(); if (!manual) return }
                val range = engine.isoRange() ?: return
                iso = (iso * Math.pow(2.0, (delta * 6f).toDouble())).toInt()
                    .coerceIn(range.lower, range.upper)
                engine.setManualExposure(iso, shutterNs)
            }
            1 -> {
                if (!manual) { toggleManual(); if (!manual) return }
                val range = engine.exposureRange() ?: return
                shutterNs = (shutterNs * Math.pow(2.0, (delta * 6f).toDouble())).toLong()
                    .coerceIn(range.lower, range.upper)
                engine.setManualExposure(iso, shutterNs)
            }
            2 -> {
                // A lens with no focus motor is said outright rather than
                // letting the number move while the picture does not. This was
                // the whole of "focus doesn't change focus at all": the ultra
                // wide is fixed, and the drag was refused in silence.
                if (!engine.supportsManualFocus()) {
                    say("This lens is fixed focus; there is nothing to pull")
                    return
                }
                focusFraction = (focusFraction + delta).coerceIn(0f, 1f)
                if (focus.mode == FocusDirector.Mode.AUTO) {
                    focus.setMode(FocusDirector.Mode.MANUAL)
                    refreshKeys()
                }
                engine.setManualFocus(focusFraction)
            }
            3 -> {
                // Left is tungsten, right is daylight — the way the numbers on
                // a colour meter run, and the way every white balance dial ever
                // made is laid out.
                val at = (WhiteBalance.travel(wbKelvin) + delta).coerceIn(0f, 1f)
                wbKelvin = WhiteBalance.kelvinAt(at)
                wbAuto = false
                engine.setWhiteBalanceKelvin(wbKelvin)
            }
        }
        refreshZones()
    }

    /**
     * A row tapped: hand that parameter back to the camera, or take it.
     *
     * There is no room on the rail for a key per parameter and there should not
     * be one — the place to say "you take this" about white balance is the
     * white balance fader. The two that already have keys behave the same way
     * from either end.
     */
    private fun handBack(index: Int) {
        when (index) {
            0, 1 -> toggleManual()
            2 -> if (pipeline.engine.supportsManualFocus()) toggleAutoFocus()
                 else say("This lens is fixed focus; there is nothing to pull")
            3 -> {
                wbAuto = !wbAuto
                if (wbAuto) {
                    pipeline.engine.setAutoWhiteBalance()
                    say("White balance: the camera's own")
                } else {
                    pipeline.engine.setWhiteBalanceKelvin(wbKelvin)
                    say(
                        "White balance: ${WhiteBalance.format(wbKelvin)}" +
                            if (pipeline.engine.whiteBalanceIsContinuous) ""
                            else ", nearest preset — this sensor publishes no calibration"
                    )
                }
                refreshKeys()
            }
        }
        refreshZones()
    }

    /**
     * What the four columns currently say.
     *
     * The numbers are the camera's own answers wherever the camera has one —
     * the ISO and the shutter it settled on, the distance the lens actually
     * reached — rather than the value that was asked for. A column that echoes
     * the request agrees with itself whatever the lens is doing, which is how
     * a focus control that never moved anything looked correct for six
     * versions.
     */
    private fun refreshZones() {
        val engine = pipeline.engine
        val running = pipeline.isRunning
        val apertures = engine.apertures()
        val liveIso = engine.lastIso ?: iso
        val liveShutter = engine.lastExposureNs ?: shutterNs

        val canFocus = running && engine.supportsManualFocus()
        val reached = engine.lastFocusDistance
        val focusText = when {
            !running -> "—"
            // No focus motor on this lens. An ultra wide on most phones.
            !canFocus -> "FIXED"
            focus.mode == FocusDirector.Mode.AUTO -> "AUTO"
            reached != null && reached > 0.01f -> String.format("%.2fm", 1f / reached)
            else -> "∞"
        }

        // Four faders, and every one of them has a track.
        //
        // The iris used to be here with no track and the width of one, which is
        // a row of empty space where a control should be. A phone has one
        // aperture; it is a fact about the lens, so it is a readout in the
        // corner beside the rest of the facts, not a fader that cannot move.
        zones.zones = listOf(
            ControlZones.Zone(
                "ISO", liveIso.toString(), true,
                engine.isoRange()?.let {
                    travel(liveIso.toDouble(), it.lower.toDouble(), it.upper.toDouble())
                } ?: 0f
            ),
            ControlZones.Zone(
                "SHUTTER", Mechanism.formatShutter(liveShutter), true,
                engine.exposureRange()?.let {
                    // Longer is more light, so the knob travels the way the
                    // picture brightens: right is a slower shutter.
                    travel(liveShutter.toDouble(), it.lower.toDouble(), it.upper.toDouble())
                } ?: 0f
            ),
            ControlZones.Zone("FOCUS", focusText, canFocus, focusFraction),
            // Tungsten on the left, daylight on the right. Dragging it takes it
            // off auto; a tap hands it back.
            ControlZones.Zone(
                "WB",
                if (wbAuto) "AUTO" else WhiteBalance.format(wbKelvin),
                running,
                WhiteBalance.travel(wbKelvin)
            )
        )

        iris.text = apertures.firstOrNull()?.let { String.format("IRIS  f/%.2f", it) } ?: ""
        iris.visibility = if (zonesOn && apertures.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Where a value sits in its own travel, 0..1, on a log scale.
     *
     * Log because these are stops. A knob placed linearly across 50..12800
     * spends nine tenths of the track above ISO 1600 and never leaves the left
     * edge in a room, which tells the eye nothing.
     */
    private fun travel(value: Double, low: Double, high: Double): Float {
        if (value <= 0.0 || low <= 0.0 || high <= low) return 0f
        val span = Math.log(high / low)
        if (span <= 0.0) return 0f
        return (Math.log(value.coerceIn(low, high) / low) / span).toFloat().coerceIn(0f, 1f)
    }

    private fun turnPreview() {
        manualQuarterTurns = (manualQuarterTurns + 1) % 4
        // Kept, so a phone whose sensor is mounted unusually is corrected once
        // rather than at the start of every shoot.
        settings.quarterTurns = manualQuarterTurns
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
        // The temperature belongs to the operator, not to the session. A new
        // lens rebuilds the request from the template, so a chosen white
        // balance has to be put back or it silently reverts to auto.
        if (!wbAuto && pipeline.isRunning) pipeline.engine.setWhiteBalanceKelvin(wbKelvin)
        val cube = if (activeSlot > 0) slots.cube(activeSlot) else null
        // The monitor gets the cube exactly; the wire gets as much of it as a
        // tone curve can carry, which is its tone and its colour balance.
        if (pipeline.isRunning) pipeline.engine.setWireLut(cube)
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
        manualKey.state = if (manual) RailButton.State.ON else RailButton.State.OFF
        manualKey.sub = if (manual) "MAN" else "AUTO"
        ctrlKey.state = if (zonesOn) RailButton.State.ON else RailButton.State.OFF
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

        recKey.dead = !pipeline.isRunning
        recKey.recording = pipeline.isRecording
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
