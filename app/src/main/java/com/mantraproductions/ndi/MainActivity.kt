package com.mantraproductions.ndi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The camera, and the two rails of keys in the black beside it.
 *
 *     LENS 1..4   the physical lenses this phone actually has
 *     AF          the focus director: hold, notice, rack over a beat
 *     PEAK        edge detector on the monitor only
 *     LOG         which log curve the phone's tone mapper is applying
 *     ROT         a quarter turn of the preview, by hand, remembered per lens
 *     M / CTRL    manual exposure, and the zones on the picture
 *     NDI         off, HX, full, off — one stream, so one key
 *     FULL        the clean feed: the picture and nothing else on the glass
 *
 *     REC         the take, at the top of the other rail
 *     LGHT        the lamp
 *     SNAP        the sensor's own frame as a DNG, uncorrected
 *     1..11       the LUT slots, five at a time, then the gear
 *
 * The lamp and the stills key moved to the right rail in v80: two keys fewer
 * on the left is two keys' worth of height shared among the ten that are left,
 * and they belong beside record anyway — they are what a right thumb reaches
 * for without taking the left hand off the lens keys. Nothing is drawn round a
 * key any more either. A box costs an outline, a corner, an inset and a margin,
 * and every one of those is taken off the word inside it.
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
    private lateinit var wbBox: MarkView
    private lateinit var exposureCircle: MarkView
    private lateinit var zones: ControlZones
    private lateinit var fullScreenCatcher: View
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
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

    /**
     * Whether the monitor shows the picture mirrored (the camera's producer
     * transform on the direct path; never on the GPU stage, which draws the
     * raw buffer). Screen and sensor are mapped through this, not through
     * "front camera", which v97 made two different questions.
     */
    private var previewMirrored = false

    /** TRK (v99): the focus box is a tracking mark. */
    private var tracking = false
    private var lastFocusX = 0.5f
    private var lastFocusY = 0.5f
    private var peaking = false
    private var bufferSize = Size(1920, 1080)

    /** Manual exposure, and whether the zones on the picture are listening. */
    /**
     * A / M per parameter (v87). ISO and shutter each have their own switch;
     * focus is the focus director's mode. The M key's AUTO / HM / FM is read
     * off these three, never stored beside them. White balance is not one of
     * them since v90: it is always a held number, and its A is a measurement.
     */
    private var isoAuto = true
    private var shutterAuto = true

    /** The last half-manual set, for the M key to put back. */
    private var lastHalf: BooleanArray? = null

    /** The brightness the half-manual loop holds, and whether the camera does it itself. */
    private var loopTarget = 0.0
    private var loopNative = false
    private var loopSentIso = -1
    private var loopSentShutterNs = -1L
    private var zonesOn = false

    /**
     * The clean feed: the picture and nothing else at all.
     *
     * He broadcasts this phone by mirroring its screen rather than over NDI,
     * and everything this app draws goes down that wire with the picture — the
     * rails, the status line, the geometry, the audio meter. So `FULL` takes
     * all of it off, and the phone's own status and navigation bars with it,
     * and leaves a picture on black. A double tap anywhere brings the camera
     * back; so does the back key.
     *
     * Nothing about the camera changes: a take goes on being written and NDI
     * goes on being sent while this is on. It is a mode of the *screen*.
     */
    private var fullScreen = false
    private var iso = 400
    private var shutterNs = 1_000_000_000L / 60
    private var focusFraction = 0f

    /**
     * Where each zone's knob is, 0..1, as the thumb left it.
     *
     * **This is "the slider stops two thirds of the way along and is very
     * strange to move".** The knob used to be drawn from the value the camera
     * reported, while the drag moved a fixed six stops per swipe — two
     * different instruments wearing one hat. The camera will not expose for
     * longer than a frame, so at 30fps it clamps at 1/30 whatever the sensor
     * says it can do, and 1/30 is two thirds of the way along a range that
     * reaches 1/92030 at the other end. A third of the track could not be
     * reached and nothing on the screen said why.
     *
     * So the position is the instrument: the whole track is the whole of the
     * range the camera will honour, the knob goes exactly where the thumb puts
     * it, and the value is read off the position. Seeded from what the camera
     * is doing whenever it is taken over, so nothing jumps.
     */
    private var isoPosition = 0.5f
    private var shutterPosition = 0.5f

    /** Said once per lens, not once per drag. */
    private var focusComplaintFor = ""

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
    private lateinit var recKey: RecordButtonView
    private lateinit var gearKey: RailButton
    private lateinit var playKey: RailButton
    private lateinit var shootKey: RailButton
    private lateinit var lutKey: RailButton
    private lateinit var falseKey: RailButton
    private lateinit var zebraKey: RailButton
    private lateinit var storageKey: RailButton
    private lateinit var timecode: TextView

    /** The two exposure tools beside peaking. Monitor only, like peaking. */
    private var falseColour = false
    private var zebra = false
    private lateinit var focusKey: RailButton
    private lateinit var lensChooser: RailButton
    private var lensesOpen = false
    private var markKeys: List<RailButton> = emptyList()
    private lateinit var peakKey: RailButton
    private lateinit var snapKey: RailButton
    private lateinit var logKey: RailButton
    private lateinit var manualKey: RailButton
    private lateinit var ctrlKey: RailButton
    private lateinit var screenKey: RailButton

    private val ui = Handler(Looper.getMainLooper())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trace.open(this)
        Trace.markStart()
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        focusSquare = findViewById(R.id.focusSquare)
        wbBox = findViewById(R.id.wbBox)
        exposureCircle = findViewById(R.id.exposureCircle)
        zones = findViewById(R.id.zones)
        fullScreenCatcher = findViewById(R.id.fullScreenCatcher)
        status = findViewById(R.id.status)
        telemetry = findViewById(R.id.telemetry)
        vu = findViewById(R.id.vu)
        iris = findViewById(R.id.iris)
        stage = findViewById(R.id.stage)
        railLeft = findViewById(R.id.railLeft)
        railRight = findViewById(R.id.railRight)
        timecode = findViewById(R.id.timecode)
        showTimecode(false)

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
            onState = { s -> ui.post {
                focusSquare.state = s
                when (s) {
                    FocusSquareView.State.LOCKED -> { focusLockedAt = focusLockText(); say("Focus locked") }
                    FocusSquareView.State.SEEKING -> focusLockedAt = null
                    else -> Unit
                }
            } }
        )

        buildRails()
        startLink()
        applyShootMode()
        layoutForOrientation(resources.configuration.orientation)

        // Android draws its camera-in-use indicator over the top of the screen
        // whenever this app is doing its job, and on the first run it sat on
        // top of the HX key. The rails are inset out of the system's way.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        focusSquare.size = settings.focusBoxSize
        focusSquare.onMoved = { x, y ->
            focus.target = x to y
            focus.bounds = focusSquare.normalisedBounds()
            updateFocusRegion()
            // A tap or a drag in TRK is a new subject: the box becomes the new pattern.
            if (tracking) startTrack()
        }
        focusSquare.onResized = { size ->
            settings.focusBoxSize = size
            if (tracking) startTrack()
        }
        pipeline.trackListener = GpuStage.TrackListener { x, y, score -> ui.post { onTracked(x, y, score) } }
        focusSquare.onTapped = { focus.focusHereAndHold(); alsoUnder(focusSquare.tapX, focusSquare.tapY, 0) }
        // v101: the white balance triangle. A tap balances on what is inside; where it is dragged is kept.
        wbBox.kind = MarkView.Kind.TRIANGLE
        wbBox.size = settings.wbSize
        wbBox.post { wbBox.moveTo(settings.wbBoxX, settings.wbBoxY) }
        // v103: the exposure circle. A tap meters there and locks; where it is dragged is kept.
        exposureCircle.kind = MarkView.Kind.CIRCLE
        exposureCircle.size = settings.circleSize
        exposureCircle.post { exposureCircle.moveTo(settings.circleX, settings.circleY) }
        exposureCircle.onMoved = { x, y -> settings.circleX = x; settings.circleY = y; exposureCircle.state = MarkView.State.IDLE }
        exposureCircle.onTapped = { meterExposure(); alsoUnder(exposureCircle.tapX, exposureCircle.tapY, 1) }
        focusSquare.onStateChanged = { refreshKeys() }
        exposureCircle.onStateChanged = { refreshKeys() }
        wbBox.onStateChanged = { refreshKeys() }
        focusSquare.onTapFor = { x, y -> tapAt(x, y) }
        refreshMarks()
        wbBox.onMoved = { x, y -> settings.wbBoxX = x; settings.wbBoxY = y }
        wbBox.onTapped = { spotWhiteBalance(); alsoUnder(wbBox.tapX, wbBox.tapY, 2) }

        onBackPressedDispatcher.addCallback(this, leaveFullScreen)
        // The clean feed's only control: two taps anywhere on the glass. One
        // tap is what a phone gets by accident while it is being carried.
        val leaving = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    setFullScreen(false)
                    return true
                }
                // One tap focuses where it lands, box or no box. Confirmed
                // single, so the first half of the double tap never racks.
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    val picture = findViewById<View>(R.id.picture)
                    val at = IntArray(2)
                    picture.getLocationOnScreen(at)
                    val x = (e.rawX - at[0]) / picture.width.coerceAtLeast(1)
                    val y = (e.rawY - at[1]) / picture.height.coerceAtLeast(1)
                    // The black round the picture is not a place to focus on.
                    // v105: a tap serves the armed shape, in the clean feed as everywhere
                    if (x in 0f..1f && y in 0f..1f) tapAt(x, y)
                    return true
                }
                override fun onDown(e: android.view.MotionEvent) = true
            }
        )
        fullScreenCatcher.setOnTouchListener { view, event ->
            view.performClick()
            leaving.onTouchEvent(event)
        }

        zones.onDrag = { index, delta -> nudge(index, delta) }
        zones.onGrab = { refreshZones() }
        zones.onToggle = { index -> toggleParam(index) }
        zones.onPreset = { index, preset -> applyPreset(index, preset) }
        zones.onSingleTap = { x, y -> tapAt(x, y) }

        // The trace is the only instrument that reaches a phone with no cable,
        // so it is always one long press away rather than behind a menu.
        status.setOnLongClickListener { exportTrace(); true }

        // v104: any change that stopped the picture was taken back by the engine; say so, in his words
        pipeline.engine.onRequestFroze = {
            ui.post {
                say("That setting is not available on this phone — put back as it was")
                refreshZones(); refreshKeys()
            }
        }
        pipeline.engine.onCurveRefused = { refused, back ->
            ui.post {
                curveIndex = back.ordinal
                refusedCurves.add(lensKey() + "/" + refused.name)
                settings.refusedCurves = refusedCurves
                risk = null
                say("${curveLabel(refused)} is not supported on this lens — back to ${curveLabel(back)}")
                refreshKeys()
            }
        }

        pipeline.listener = object : CameraPipeline.Listener {
            override fun onReady(tenBit: Boolean, codec: String, size: Size) {
                ui.post {
                    bufferSize = size
                    holdBufferSize()
                    applyPreviewTransform()
                    say("${size.width}x${size.height} ${if (tenBit) "10-bit" else "8-bit"} $codec")
                    reopenTries = -1                // v116: up again, the recovery is over
                    // v104: the light, switched in settings, is put on whenever the camera opens
                    if (settings.torch) pipeline.engine.setTorch(true)
                    restoreMode = CameraPipeline.Mode.OFF
                    // v130: NDI runs whenever it is armed, from the moment the camera is up (item 47)
                    followNdiSwitch()
                    // A new session starts from the template, which is all
                    // automatic: his A / M switches are put back on it.
                    if (!isoAuto || !shutterAuto) applyExposure()
                    focus.resume()
                    // A new session is a new GPU stage: the mark is set again where it is.
                    if (tracking) ui.postDelayed({ if (tracking) startTrack() }, 400)
                    loadWbCurve()
                    if (!wbMeasuredOnce) probeWhiteBalance()
                    refreshZones()
                    refreshKeys()
                }
            }
            override fun onError(message: String) {
                ui.post {
                    say(message); Trace.refused("pipeline", message)
                    // v115: a dead camera is opened again, and a risky step that killed it is taken back
                    if (message.startsWith("Camera error")) onCameraDied(message)
                    else retryOpen(message)
                }
            }
            override fun onRate(fps: Double, megabitsPerSecond: Double, connections: Int) {
                ui.post { showRate(fps, megabitsPerSecond, connections); refreshZones(); watchEncoder(fps) }
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
        // As many lens keys as this phone has lenses, and no more. Four were
        // always drawn with the missing ones dark; a key for a lens that does
        // not exist is not a control.
        // v103: ONE key, L (Marko, 2.10.2026: "This lenses chooser is taking too much space ... L, only one
        // icon. When user press L, they uncollapse. User choose one lens and then it collapse again"). Its
        // whisper is the lens in use; the lens keys appear beside it only while it is open.
        lensChooser = newKey("L") { openDrawer(!lensesOpen) }.also { railLeft.addView(it) }
        // v130, THE LENS DRAWER (Marko, 3.10.2026: "when I press lens, it should actually take all available space
        // ... all other icons are gone ... millimeters ... next to the letter ... aperture opening for each lens and
        // resolution"). Each lens key reads "L1 24mm" over "f/1.85 · 12MP · 4K".
        for (i in 1..lenses.size) {
            val lens = lenses[i - 1]
            val mm = lens.equivalentMm.takeIf { it > 0 }?.let { " ${it}mm" } ?: ""
            val video = runCatching { pipeline.widthsOffered(lens.id, lens.physicalId).maxOrNull() }.getOrNull()
            val res = when { video == null -> null; video >= 3840 -> "4K"; video >= 2560 -> "1440p"; video >= 1920 -> "1080p"; else -> "720p" }
            val sub = listOfNotNull(
                lens.aperture.takeIf { it > 0f }?.let { "f/" + String.format(java.util.Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.') },
                lens.megapixels.takeIf { it > 0 }?.let { "${it}MP" },
                res
            ).joinToString(" · ")
            val key = newKey("L$i$mm") { openDrawer(false); chooseLens(i - 1) }
            key.sub = sub.ifEmpty { null }
            key.scale = 1.35f
            lensKeys.add(key)
            railLeft.addView(key)
        }
        // v103: the three marks' keys, drawn as their marks. Grey = not on the picture, white = on it,
        // orange = the one a pinch resizes (only one). A tap steps grey → white → orange → grey.
        markKeys = listOf(RailButton.Glyph.SQUARE, RailButton.Glyph.CIRCLE, RailButton.Glyph.TRIANGLE).mapIndexed { i, g ->
            newKey("") { stepMark(i) }.also { it.glyph = g; railLeft.addView(it) }
        }
        focusKey = newKey("AF") { toggleAutoFocus() }.also { railLeft.addView(it) }
        logKey = newKey("LOG") { nextCurve() }.also { railLeft.addView(it) }
        manualKey = newKey("M") { nextCameraMode() }.also { railLeft.addView(it) }
        ctrlKey = newKey("CTRL") { toggleZones() }.also { railLeft.addView(it) }
        // The clean feed, for a phone that is being broadcast by its screen.
        // v131: FULL pressed by the monitor's finger (Mantra Link) makes the MONITOR clean, not this screen
        // (Marko, 3.10.2026: "full screen button to make it full screen on the phone, which I'm not there. Doesn't
        // make any sense"): the monitor then shows the picture, the record key and its counter.
        screenKey = newKey("FULL") {
            if (LinkServer.fromMonitorJustNow()) LinkServer.setClean(!LinkServer.clean) else setFullScreen(!fullScreen)
        }
            .also { railLeft.addView(it) }

        // THE RIGHT RAIL, v85: the take, the look, the measuring tools, and
        // what is left on the drive.
        //
        // *"Right side is record button, preview file button, portrait or
        // landscape shooting, and the LUT switcher ... and the measuring tools
        // ... and peaking, so everything is in one place ... and at the bottom
        // the free space and how much time we can still record."* The eleven
        // LUT slots went to settings; one key here switches between the ones
        // loaded there.
        recKey = RecordButtonView(this).also {
            it.setOnClickListener { toggleRecording() }
            railRight.addView(it)
        }
        playKey = newKey("PLAY") { playLastTake() }.also { railRight.addView(it) }
        // SNAP is the app's own icon (v88): the still is this camera's picture.
        snapKey = newKey("") { takeSnap() }.also {
            it.glyph = RailButton.Glyph.CAMERA
            railRight.addView(it)
        }
        shootKey = newKey("PORTRAIT") { nextShoot() }.also { railRight.addView(it) }
        lutKey = newKey("LUT") { nextLut() }.also {
            it.setOnLongClickListener { openSettings(); true }
            railRight.addView(it)
        }
        peakKey = newKey("PEAK") { togglePeaking() }.also { railRight.addView(it) }
        falseKey = newKey("FALSE") { falseColour = !falseColour; applyLook(); refreshKeys() }
            .also { railRight.addView(it) }
        zebraKey = newKey("ZEBRA") { zebra = !zebra; applyLook(); refreshKeys() }
            .also { railRight.addView(it) }
        gearKey = newKey("") { openSettings() }.also {
            it.glyph = RailButton.Glyph.GEAR
            railRight.addView(it)
        }
        // Not a switch: a readout that opens where the takes go.
        storageKey = newKey("—") { openSettings() }.also { railRight.addView(it) }
    }

    /** v130: the lens drawer opens over the whole left rail (wider in landscape, taller upright) and closes again. */
    private fun openDrawer(open: Boolean) {
        lensesOpen = open
        refreshKeys()
        layoutForOrientation(resources.configuration.orientation)
    }

    /** v131, MANTRA LINK (LinkServer, Link.kt): the monitor sees this window and plays its touches into it. */
    private fun startLink() {
        LinkServer.start(application)
        LinkServer.onKey = { name -> pressKey(name) }
        LinkServer.onModeChanged = { refreshKeys() }
        LinkServer.stateProvider = {
            mapOf(
                "rec" to if (rolling) "1" else "0",
                "since" to if (rolling) (android.os.SystemClock.elapsedRealtime() - recordingSince).toString() else "0",
                "name" to settings.sourceName
            )
        }
        // the clean picture: the preview as it is drawn (TextureView.getBitmap, on the UI thread)
        LinkServer.cleanSource = { w, h ->
            var b: android.graphics.Bitmap? = null
            val done = java.util.concurrent.CountDownLatch(1)
            ui.post {
                b = runCatching {
                    if (!preview.isAvailable || preview.width <= 0) null else {
                        val k = minOf(w.toFloat() / preview.width, h.toFloat() / preview.height)
                        preview.getBitmap((preview.width * k).toInt().coerceAtLeast(2), (preview.height * k).toInt().coerceAtLeast(2))
                    }
                }.getOrNull()
                done.countDown()
            }
            done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            b
        }
    }

    private fun openSettings() {
        // The lens in use, so ROT in settings turns this lens and no other.
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_LENS, lensKey())
                .putExtra(SettingsActivity.EXTRA_LENS_NAME, lenses.getOrNull(activeLens)?.label)
        )
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

        val density = resources.displayMetrics.density
        val margin = (2 * density).toInt()
        for (rail in listOf(railLeft, railRight)) {
            // v130: the open lens drawer takes room for its words, across in landscape and down when upright
            val thickness = ((if (rail === railLeft && lensesOpen) (if (landscape) 150 else 72) else 44) * density).toInt()
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
        if (fullScreen) hideSystemBars(true)
    }

    /**
     * **The half turn: landscape, the other way up.**
     *
     * *"If I turn my phone upside down in landscape mode, the phone doesn't
     * follow."* It cannot, and nothing in this app was ever going to hear
     * about it. Turning a phone end for end takes the display from 90° to 270°
     * and changes **nothing else**: the configuration's orientation is
     * landscape either way, the window is the same size, so no configuration
     * change arrives and no layout change arrives — and the preview transform
     * is only ever recomputed when one of those two does. The picture is left
     * standing on its head with no event anywhere to say so.
     *
     * A display listener is the one thing that does hear it. It fires for any
     * change to the display including a rotation that changes nothing else,
     * which is exactly and only the case that was missing.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (preview.isAvailable) applyPreviewTransform()
        }
    }

    private val displays: DisplayManager?
        get() = getSystemService(DisplayManager::class.java)

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

        // The resolution and the frame rate first: they decide the session,
        // and a session can be neither resized nor re-timed once it is built.
        pipeline.maxWidth = settings.captureWidth
        pipeline.fps = settings.fps
        // Each lens carries its own correction. A front camera is a separate
        // sensor in a separate hole and is not always mounted the same way
        // round as the rear ones, so one global quarter turn meant fixing the
        // selfie lens broke the other three.
        // v104: "remove interface orientation from the settings. It can be always at 0" (Marko, 2.10.2026)
        manualQuarterTurns = 0
        focusComplaintFor = ""
        // The buffer size has to be right before the session is built.
        bufferSize = pipeline.previewSizeFor(lens.id, lens.physicalId)
        holdBufferSize()
        applyPreviewTransform()

        val curve = LogCurves.Curve.entries[curveIndex]
        focus.holdMs = settings.focusHoldMs
        focus.rampMs = settings.focusRackMs
        pipeline.streamBitRate = settings.streamMbps * 1_000_000
        pipeline.start(
            cameraId = lens.id,
            physicalId = lens.physicalId,
            previewSurface = Surface(texture),
            sourceName = Mechanism.sanitizeSourceName(settings.sourceName),
            curve = curve,
            wantTenBit = settings.wantTenBit,
            bitRate = settings.bitRateMbps * 1_000_000,
            useGpuStage = settings.gpuStage
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
        ui.removeCallbacks(logExposureTick)
        ui.post(logExposureTick)
        ui.removeCallbacks(remoteTick)
        ui.post(remoteTick)
        displays?.registerDisplayListener(displayListener, ui)
        if (fullScreen) hideSystemBars(true)
        // The camera session is lost while backgrounded even with a foreground
        // service, so it is rebuilt rather than tested for.
        if (preview.isAvailable && !pipeline.isRunning) openCamera()
        // v106: the outputs line, shown or hidden in settings
        telemetry.visibility = if (!fullScreen && settings.showOutputs) View.VISIBLE else View.GONE
        placeStatus()
        // v104: back from settings — the marks take their sizes from there, and the light follows its switch
        focusSquare.size = settings.focusBoxSize
        exposureCircle.size = settings.circleSize
        wbBox.size = settings.wbSize
        if (pipeline.isRunning && pipeline.engine.hasFlash()) pipeline.engine.setTorch(settings.torch)
        // Settings may have removed the LUT that was on.
        if (activeSlot > 0 && !slots.slot(activeSlot).loaded) { activeSlot = 0; applyLook() }
        refreshStorage()
        refreshKeys()
        startMeter()
        ui.post(rateTick)
    }

    override fun onPause() {
        super.onPause()
        runCatching { displays?.unregisterDisplayListener(displayListener) }
        ui.removeCallbacks(rateTick)
        ui.removeCallbacks(focusWatch)
        ui.removeCallbacks(logExposureTick)
        ui.removeCallbacks(remoteTick)
        focus.stop()
        // The file is closed before the camera goes, not after: a take whose
        // encoder disappeared underneath it has no moov atom and opens nowhere.
        if (rolling) stopAll()
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

    private var storageTicks = 0

    private val rateTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) pipeline.sampleRate()
            if (++storageTicks % 5 == 0) refreshStorage()
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
    /** Which way the screen is turned, in degrees (Surface.ROTATION_*). */
    private fun displayDegrees(): Int = when (
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
    ) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun toggleRecording() {
        if (rolling) stopAll() else startAll()
    }

    /** The master trigger is engaged: every armed destination is running (v91). */
    private var rolling = false

    /**
     * THE RECORD KEY (v130): the take, and only the take.
     *
     * v91 started every armed destination from this key. Marko, 3.10.2026: *"NDI Stream should start actually as
     * soon as it's enabled. It's not any more connected with the record button."* So NDI follows its switch in
     * settings ([followNdiSwitch]) and the record key records the file; the record-run timecode counts from here.
     */
    private fun startAll() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        if (!settings.armFile) {
            say("FILE is not armed — arm it in settings (the gear)")
            return
        }
        rolling = true
        recordingSince = android.os.SystemClock.elapsedRealtime()
        if (!startRecording()) { rolling = false; refreshKeys(); return }
        Trace.control("record", "start", "FILE")
        showTimecode(true)
        refreshTelemetry()
        refreshKeys()
    }

    /** The take stops; NDI goes on as long as it is armed. */
    private fun stopAll() {
        rolling = false
        if (pipeline.isRecording) stopRecording()
        Trace.control("record", "stop", "FILE")
        showTimecode(false)
        refreshTelemetry()
        refreshKeys()
    }

    /**
     * v130: the NDI stream follows its switch, not the record key. Armed: it runs as soon as the camera is open, the
     * kind chosen in settings (HX or FULL). Not armed: it stops. Called whenever the camera comes up.
     */
    private fun followNdiSwitch() {
        if (!pipeline.isRunning) return
        if (!settings.armNdi) {
            if (pipeline.mode != CameraPipeline.Mode.OFF) pipeline.setMode(CameraPipeline.Mode.OFF)
            return
        }
        val want = if (settings.ndiKind == 2) CameraPipeline.Mode.FULL else CameraPipeline.Mode.HX
        if (pipeline.mode == want) return
        val possible = if (want == CameraPipeline.Mode.FULL) pipeline.fullAvailable else pipeline.hxAvailable
        if (!possible) {
            say("${if (want == CameraPipeline.Mode.FULL) "Full NDI" else "NDI HX"} is not possible on this lens")
            return
        }
        pipeline.setMode(want)
        Trace.control("ndi", want.name, "armed in settings")
        refreshTelemetry()
    }

    private fun startRecording(): Boolean {
        if (!pipeline.isRunning) { say("The camera is not open"); return false }
        if (meter == null) say("No microphone: this take will have no sound")
        val where = pipeline.startRecording(
            this, meter, settings.recordFolder,
            Mechanism.recordingRotation(
                pipeline.engine.sensorOrientation, displayDegrees(),
                pipeline.engine.isFrontFacing, manualQuarterTurns
            )
        )
            ?: run { refreshKeys(); return false }
        pipeline.lastTakeUri?.let { settings.lastTake = it.toString() }
        say("Recording → $where")
        return true
    }

    private fun stopRecording() {
        val frames = pipeline.recordedFrames
        val drops = pipeline.recordedDrops
        val where = pipeline.stopRecording()
        refreshStorage()
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

        val displayDegrees = displayDegrees()
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
        val producerMirrored = Mechanism.producerMirrored(producer)
        lastProducerDegrees = producerDegrees
        previewMirrored = producerMirrored

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
        // THE SELFIE CAMERA, UPSIDE DOWN.
        //
        // A front camera hands over a *mirrored* frame, and a mirror is not a
        // rotation: the old decoder saw the negative entry in the matrix and
        // answered "half a turn", so half a turn that was never there was
        // taken off the angle and lens four came up upside down while the three
        // rear lenses were right. The mirror is read separately now and put
        // back here, before the rotation — this is a broadcast camera and the
        // monitor has to show what the wire is carrying. The encoder is fed the
        // camera buffer directly and never sees this matrix at all.
        if (producerMirrored) matrix.postScale(-1f, 1f, vw / 2f, vh / 2f)
        matrix.postRotate(applied.toFloat(), vw / 2f, vh / 2f)
        matrix.postScale(scale[0], scale[1], vw / 2f, vh / 2f)
        preview.setTransform(matrix)

        // THE PORTRAIT STRIP.
        //
        // The box was given the buffer's own shape, 16:9 always. In landscape
        // that is right, because the camera's quarter turn and ours cancel. Held
        // upright they do not: the total is a quarter, what arrives is 9:16, and
        // a 9:16 picture fitted inside a 16:9 box is a strip with black on all
        // four sides. The box is told the total now.
        findViewById<AspectFrame>(R.id.picture).aspect =
            Mechanism.shownAspect(bufferSize.width, bufferSize.height, producerDegrees, applied)

        // Measured on what actually reaches the screen, turned and fitted,
        // against the shape the picture is really meant to be.
        val shown = Mechanism.displayedAspect(vw, vh, effective[0], effective[1], applied)
        val wanted = Mechanism.shownAspect(
            bufferSize.width, bufferSize.height, producerDegrees, applied
        )
        val squeeze = if (shown > 0.0 && wanted > 0.0) shown / wanted else 1.0

        val line = "sensor $sensor · disp $displayDegrees · cam $producerDegrees" +
            (if (producerMirrored) " mirrored" else "") +
            (if (front) " front" else "") + " · rot $applied" +
            (if (manualQuarterTurns != 0) " (auto $auto +${manualQuarterTurns * 90})" else "") +
            " · buf ${bufferSize.width}x${bufferSize.height} · view ${vw}x$vh" +
            " · squeeze " + String.format("%.3f", squeeze) +
            (if (kotlin.math.abs(squeeze - 1.0) > 0.01) "  STRETCHED" else "")
        Trace.control("preview geometry", line, applied)
        updateFocusRegion()
    }

    /**
     * The focus box, handed to the camera in the sensor's own coordinates.
     *
     * Recomputed whenever the box moves or is pinched and whenever the picture
     * is turned, because those are the three things that change which part of
     * the sensor is under the box.
     */
    private fun updateFocusRegion() {
        if (!pipeline.isRunning) return
        val engine = pipeline.engine
        val displayDegrees = displayDegrees()
        val front = engine.isFrontFacing
        val degrees = Mechanism.sensorToViewDegrees(
            engine.sensorOrientation, displayDegrees, front, manualQuarterTurns
        )
        val b = focusSquare.normalisedBounds()
        val onStream = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], degrees, previewMirrored)
        val array = engine.activeArray()
        engine.focusRegion = if (array == null) onStream else Mechanism.streamRegionToArray(
            onStream, bufferSize.width, bufferSize.height, array.width(), array.height()
        )
    }

    /**
     * Focus on a point of the picture, whether or not the box is showing.
     *
     * The box goes there too, invisibly, so that when it comes back it is on
     * what was focused on, and its size is the size of the region used.
     */
    private fun focusAt(x: Float, y: Float) {
        if (!pipeline.isRunning) return
        focusSquare.placeAt(x, y)
        Trace.control("focus tap", String.format("%.2f,%.2f", x, y),
            if (fullScreen) "clean feed" else if (zonesOn) "zones up" else "box")
        focus.focusHereAndHold()
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
        // The box's shape is set by applyPreviewTransform, which is the only
        // place that knows the total turn. Setting it here from the buffer
        // alone is what left the picture in a strip when the phone was upright.
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

    /**
     * The focus key: AF → TRK → MF → AF (v99). TRK is automatic focus whose
     * box follows a subject; it needs the GPU stage, and without it the key
     * steps straight from AF to MF and says why.
     */
    private fun toggleAutoFocus() {
        when {
            focus.mode == FocusDirector.Mode.AUTO && !tracking -> {
                if (pipeline.usesGpuStage) {
                    startTrack()
                    refreshKeys()
                    return
                }
                say("Tracking needs the GPU stage — Settings, Picture path")
                focus.setMode(FocusDirector.Mode.MANUAL)
            }
            tracking -> {
                stopTrack()
                focus.setMode(FocusDirector.Mode.MANUAL)
            }
            else -> focus.setMode(FocusDirector.Mode.AUTO)
        }
        Trace.control("focus mode", focus.mode.name, focus.mode.name)
        refreshKeys()
    }

    // --- tracking focus (v99) ---------------------------------------------------------

    /** The stream's degrees from the screen, the same sum [updateFocusRegion] uses. */
    private fun viewDegrees(): Int {
        val engine = pipeline.engine
        return Mechanism.sensorToViewDegrees(
            engine.sensorOrientation, displayDegrees(), engine.isFrontFacing, manualQuarterTurns
        )
    }

    /** The box, as it is now, becomes the pattern; the GPU stage starts looking for it. */
    private fun startTrack() {
        if (!pipeline.isRunning) return
        if (focus.mode != FocusDirector.Mode.AUTO) focus.setMode(FocusDirector.Mode.AUTO)
        val b = focusSquare.normalisedBounds()
        val s = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], viewDegrees(), previewMirrored)
        val cx = (s[0] + s[2]) / 2f
        val cy = (s[1] + s[3]) / 2f
        val search = settings.trackSearch / 10f
        if (!pipeline.startTracking(
                cx, cy, kotlin.math.abs(s[2] - s[0]), kotlin.math.abs(s[3] - s[1]),
                settings.trackEvery, search, settings.trackConfidence / 100f
            )
        ) {
            say("Tracking needs the GPU stage — Settings, Picture path")
            return
        }
        tracking = true
        focusSquare.searchFactor = search
        focusSquare.lost = false
        focusSquare.tracking = true
        lastFocusX = focusSquare.centreX
        lastFocusY = focusSquare.centreY
        focus.focusHereAndHold()
        say("Tracking")
    }

    private fun stopTrack() {
        if (!tracking) return
        tracking = false
        pipeline.stopTracking()
        focusSquare.tracking = false
        focusSquare.lost = false
    }

    /**
     * Where the GPU found the pattern. Below the confidence the mark says
     * LOST and stays; above it the mark follows by the set fraction, focus is
     * told where the subject is, and asked again only past the tolerance.
     */
    private fun onTracked(x: Float, y: Float, score: Float) {
        if (!tracking) return
        val lost = score < settings.trackConfidence / 100f
        focusSquare.lost = lost
        if (lost) return
        val v = Mechanism.streamPointToView(x, y, viewDegrees(), previewMirrored)
        val speed = settings.trackFollow / 100f
        val nx = Mechanism.follow(focusSquare.centreX, v[0], speed)
        val ny = Mechanism.follow(focusSquare.centreY, v[1], speed)
        focusSquare.moveTo(nx, ny)
        focus.target = nx to ny
        focus.bounds = focusSquare.normalisedBounds()
        updateFocusRegion()
        if (Mechanism.trackRefocus(lastFocusX, lastFocusY, nx, ny, settings.trackTolerance / 100f)) {
            lastFocusX = nx
            lastFocusY = ny
            focus.focusHereAndHold()
        }
    }

    private fun togglePeaking() {
        peaking = !peaking
        applyLook()
        refreshKeys()
    }

    /**
     * SNAP: the picture as a PNG, beside the takes.
     *
     * Taken from the preview at the camera's own resolution rather than the
     * screen's, the right way up, without the rails, the zones, the peaking
     * or the monitor LUT (those are on the View, not in the texture). The
     * compression is off the main thread: a 4K PNG takes most of a second.
     */
    private fun takeSnap() {
        if (!pipeline.isRunning || !preview.isAvailable) { say("The camera is not open"); return }
        val vw = preview.width
        val vh = preview.height
        if (vw <= 0 || vh <= 0) return
        val long = maxOf(bufferSize.width, bufferSize.height)
        val (w, h) = if (vw >= vh) long to (long.toLong() * vh / vw).toInt()
                     else (long.toLong() * vw / vh).toInt() to long
        val bitmap = runCatching { preview.getBitmap(w, h) }.getOrNull()
            ?: run { say("The picture could not be read"); return }
        snapKey.state = RailButton.State.ARMED
        Thread({
            val where = Recordings.savePng(this, bitmap, folder = settings.recordFolder)
            bitmap.recycle()
            ui.post {
                refreshKeys()
                say(if (where != null) "Still → $where" else "The still was not written, see the trace")
            }
        }, "still-png").start()
        Trace.control("still", "${w}x$h", "PNG")
    }

    /** Curves a lens froze on, "lens/CURVE": stepped over from then on, and since v115 kept across starts. */
    private val refusedCurves by lazy { settings.refusedCurves.toMutableSet() }

    /**
     * v115: THE LAST RISKY STEP, so a fatal camera error that follows it can be blamed on it. Marko, 2.10.2026: "say
     * this feature is not available and go back to the old settings." MEASURED on the Nothing Phone 2a: a log curve
     * made its camera stop (no frame), then the driver closed the camera ("Camera error 4"), and the app went on
     * sending to a closed camera — the freeze he saw.
     */
    private var risk: Triple<String, Long, () -> Unit>? = null
    private val reopenTimes = ArrayDeque<Long>()

    private fun onCameraDied(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val r = risk
        risk = null
        if (r != null && now - r.second < 10_000) {
            r.third()                                    // forget the step, put the old value back
            say("${r.first} is not available on this phone — back as it was")
            Trace.refused("camera died", "$message after ${r.first}: taken back, the camera opened again")
        } else {
            say("The camera stopped ($message) — opened again")
        }
        // never a loop: at most three reopenings a minute
        while (reopenTimes.isNotEmpty() && now - reopenTimes.first() > 60_000) reopenTimes.removeFirst()
        if (reopenTimes.size >= 3) { say("The camera keeps stopping — not opened again; reopen the app"); return }
        reopenTimes.addLast(now)
        restoreMode = pipeline.mode
        pipeline.stop(keepSource = restoreMode != CameraPipeline.Mode.OFF)
        reopenTries = 0
        ui.postDelayed({ if (preview.isAvailable) openCamera() }, 600)
    }

    /**
     * v116: after a driver crash the phone's camera service restarts, and an open in the first seconds answers "Could
     * not read lens 0" (MEASURED on the Nothing Phone 2a, v115). So the reopening tries again, later each time.
     */
    private var reopenTries = -1

    private fun retryOpen(message: String): Boolean {
        if (reopenTries < 0 || reopenTries >= 4) return false
        if (!message.startsWith("Could not read") && !message.startsWith("Could not open")) return false
        reopenTries++
        val wait = 1500L * reopenTries
        say("The camera is restarting — trying again in ${wait / 1000.0} s")
        ui.postDelayed({ if (preview.isAvailable && !pipeline.isRunning) openCamera() }, wait)
        return true
    }

    private var previousCurve = LogCurves.Curve.REC709

    private fun nextCurve() {
        previousCurve = LogCurves.Curve.entries[curveIndex]
        val count = LogCurves.Curve.entries.size
        for (step in 1..count) {
            curveIndex = (curveIndex + 1) % count
            val name = LogCurves.Curve.entries[curveIndex].name
            if (lensKey() + "/" + name !in refusedCurves) break
        }
        val curve = LogCurves.Curve.entries[curveIndex]
        val before = previousCurve
        risk = Triple(curveLabel(curve), android.os.SystemClock.uptimeMillis()) {
            refusedCurves.add(lensKey() + "/" + curve.name)
            settings.refusedCurves = refusedCurves
            curveIndex = before.ordinal
            pipeline.engine.forgetCurve(before)
            refreshKeys()
        }
        previousCurve = curve
        val ok = pipeline.setLogCurve(curve)
        say(if (ok) (if (curve == LogCurves.Curve.REC709) curveLabel(curve) + ", the camera's own curve"
                     else "${curve.vendor} ${curve.displayName}") else "${curveLabel(curve)} refused")
        refreshKeys()
    }

    /** The four switches: ISO, shutter, focus, white balance; true = A. */
    private fun switches(): BooleanArray = booleanArrayOf(
        isoAuto, shutterAuto, focus.mode == FocusDirector.Mode.AUTO
    )

    /**
     * M: AUTO → HM → FM → AUTO.
     *
     * *"Now we have modes: full manual, half manual."* HM puts back the last
     * half-manual set he made with the switches (or manual exposure with
     * automatic focus, the first time). Into AUTO, white balance is measured
     * once, the way pressing its A does.
     */
    private fun nextCameraMode() {
        val now = switches()
        if (Mechanism.cameraMode(now) == "HM") lastHalf = now.copyOf()
        val next = Mechanism.nextCameraMode(now, lastHalf)
        for (i in next.indices) if (next[i] != now[i]) setParamAuto(i, next[i], quiet = true)
        if (Mechanism.cameraMode(next) == "AUTO" && !wbAuto) probeWhiteBalance()
        say("Mode " + Mechanism.cameraMode(switches()))
        refreshZones()
        refreshKeys()
    }

    /** The A / M switch at the head of a fader. */
    private fun toggleParam(index: Int) {
        // WB's switch is a button (v90): A measures once and comes back to M.
        if (index == 3) { if (!wbAuto) probeWhiteBalance(); return }
        val now = switches()
        setParamAuto(index, !now[index], quiet = false)
        if (Mechanism.cameraMode(switches()) == "HM") lastHalf = switches()
        refreshZones()
        refreshKeys()
    }

    /**
     * One parameter to automatic or manual.
     *
     * Leaving auto starts from the value auto had settled on, so the picture
     * does not jump; white balance from A to M reads the camera's own answer
     * once and keeps it (the probe), which is what the old double tap did.
     */
    private fun setParamAuto(index: Int, auto: Boolean, quiet: Boolean) {
        val engine = pipeline.engine
        when (index) {
            0, 1 -> {
                if (!auto) {
                    iso = engine.lastIso ?: iso
                    shutterNs = engine.lastExposureNs ?: shutterNs
                    seedZonePositions()
                }
                if (index == 0) isoAuto = auto else shutterAuto = auto
                applyExposure()
                if (!quiet) say((if (index == 0) "ISO " else "Shutter ") + if (auto) "automatic" else "manual")
            }
            2 -> when {
                !engine.supportsManualFocus() -> if (!quiet) sayFixedFocus()
                (focus.mode == FocusDirector.Mode.AUTO) != auto || (!auto && tracking) -> {
                    // Straight to A or M: the rail key's cycle passes through TRK.
                    if (!auto) stopTrack()
                    focus.setMode(if (auto) FocusDirector.Mode.AUTO else FocusDirector.Mode.MANUAL)
                    Trace.control("focus mode", focus.mode.name, focus.mode.name)
                    if (!quiet) say("Focus " + if (auto) "automatic" else "manual")
                }
            }
            3 -> if (auto && !wbAuto) probeWhiteBalance()
        }
    }

    /**
     * Exposure from the two switches.
     *
     * Both A is the camera's auto exposure; both M is the operator's. One of
     * each is half manual: the phone's own ISO or shutter priority where the
     * lens offers it (Android 16), otherwise this app's loop, which moves the
     * automatic half to hold the brightness auto exposure had reached.
     */
    private fun applyExposure() {
        val engine = pipeline.engine
        releaseCircle(backToAuto = false)
        ui.removeCallbacks(priorityLoop)
        loopNative = false
        when (Mechanism.exposureMode(isoAuto, shutterAuto)) {
            Mechanism.Exposure.AUTO -> engine.setAutoExposure()
            Mechanism.Exposure.MANUAL -> engine.setManualExposure(iso, shutterNs)
            Mechanism.Exposure.ISO_PRIORITY, Mechanism.Exposure.SHUTTER_PRIORITY -> {
                val mode = if (!isoAuto) 1 else 2
                if (engine.hasNativePriority(mode) && engine.setPriorityExposure(mode, iso, shutterNs)) {
                    loopNative = true
                } else {
                    engine.setManualExposure(iso, shutterNs)
                    loopSentIso = iso
                    loopSentShutterNs = shutterNs
                    loopTarget = sampleBrightness() ?: 0.0
                    Trace.control(
                        "exposure", if (mode == 1) "ISO priority" else "shutter priority",
                        "the app's loop, holding brightness " + String.format("%.3f", loopTarget)
                    )
                    ui.postDelayed(priorityLoop, 300)
                }
            }
        }
    }

    /**
     * The half-manual loop, four times a second: read the picture's middle,
     * move the automatic half a fraction of a stop towards the target.
     */
    private val priorityLoop = object : Runnable {
        override fun run() {
            val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
            if (loopNative || !pipeline.isRunning ||
                (mode != Mechanism.Exposure.ISO_PRIORITY && mode != Mechanism.Exposure.SHUTTER_PRIORITY)
            ) return
            val measured = sampleBrightness()
            if (measured != null && loopTarget <= 0.0) loopTarget = measured
            if (measured != null && loopTarget > 0.0) {
                val engine = pipeline.engine
                // v129, THE RAMP: the loop chooses where the automatic half should go; the spring walks it
                // there every frame (v128 stepped it four times a second: ISO 247 → 259 → 280 → 322 → 374)
                val current = kotlin.math.exp(springX.takeIf { springLive } ?: kotlin.math.ln(autoHalf(mode)))
                if (mode == Mechanism.Exposure.ISO_PRIORITY) {
                    shutterBounds()?.let { (low, high) ->
                        loopGoal = Mechanism.priorityStep(current, measured, loopTarget, low.toDouble(), high.toDouble())
                    }
                } else {
                    engine.isoRange()?.let {
                        loopGoal = Mechanism.priorityStep(current, measured, loopTarget, it.lower.toDouble(), it.upper.toDouble())
                    }
                }
                if (!springLive) { springX = kotlin.math.ln(autoHalf(mode)); springV = 0.0; springLive = true; ui.post(springTick) }
            }
            ui.postDelayed(this, 250)
        }
    }

    /** v129: where the loop wants the automatic half (ISO or shutter), and the spring that walks it there. */
    private var loopGoal = 0.0
    private var springX = 0.0
    private var springV = 0.0
    private var springLive = false
    /** True while a preset key's ramp carries the automatic half itself (exposure held constant). */
    private var presetCarries = false

    private fun autoHalf(mode: Mechanism.Exposure): Double =
        if (mode == Mechanism.Exposure.ISO_PRIORITY) shutterNs.toDouble() else iso.toDouble()

    /** Every frame: the automatic half follows the loop's goal on a critically damped spring, eased in and out. */
    private val springTick = object : Runnable {
        override fun run() {
            val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
            if (loopNative || !pipeline.isRunning ||
                (mode != Mechanism.Exposure.ISO_PRIORITY && mode != Mechanism.Exposure.SHUTTER_PRIORITY)
            ) { springLive = false; return }
            if (!presetCarries && loopGoal > 0.0) {
                val (x, v) = Mechanism.springStep(springX, springV, kotlin.math.ln(loopGoal), SPRING_FRAME_S, SPRING_OMEGA)
                springX = x; springV = v
                if (mode == Mechanism.Exposure.ISO_PRIORITY) shutterNs = kotlin.math.exp(x).toLong()
                else iso = Math.round(kotlin.math.exp(x)).toInt()
                if (Mechanism.exposureChanged(iso, shutterNs, loopSentIso, loopSentShutterNs)) {
                    pipeline.engine.setManualExposure(iso, shutterNs, trace = false)
                    loopSentIso = iso
                    loopSentShutterNs = shutterNs
                    refreshZones()
                }
            }
            ui.postDelayed(this, (SPRING_FRAME_S * 1000).toLong())
        }
    }

    /**
     * The picture's brightness in the middle, 0..1, from a 24x14 read of the
     * preview: the scaling is done by the GPU, a few dozen pixels are averaged
     * here.
     */
    /**
     * The grey card: the mean signal inside the focus box (the middle fifth of
     * the picture when the box is put away), 0..1 as the curve encoded it.
     * Read off the monitor texture — the picture the camera made, before the
     * monitor LUT and the tools, which live on the View.
     */
    private fun sampleGreyCard(): Double? {
        if (!preview.isAvailable) return null
        val w = 48
        val h = 27
        val bmp = runCatching { preview.getBitmap(w, h) }.getOrNull() ?: return null
        // v103: the exposure circle is where exposure is read, when it is on the picture
        val b = if (exposureCircle.visibility == View.VISIBLE) exposureCircle.normalisedBounds()
            else if (focusSquare.visibility == View.VISIBLE) focusSquare.normalisedBounds()
            else floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f)
        val x0 = (b[0] * w).toInt().coerceIn(0, w - 1)
        val x1 = (b[2] * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (b[1] * h).toInt().coerceIn(0, h - 1)
        val y1 = (b[3] * h).toInt().coerceIn(y0 + 1, h)
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bmp.getPixel(x, y)
            // Rec.709 luma weights, on the encoded signal, as a waveform reads it.
            sum += (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) +
                0.0722 * android.graphics.Color.blue(c)) / 255.0
            n++
        }
        bmp.recycle()
        return if (n == 0) null else sum / n
    }

    /**
     * v124: THE ENCODER WATCHDOG. While NDI goes out, an encoder that gives no frame for two reports running is
     * restarted with the whole pipeline (at most three times a minute). MEASURED 2.10.2026: after a live bit-rate drop
     * the Pixel's HEVC encoder went silent with no error, and the monitor saw nothing until the camera was restarted.
     */
    private var quietReports = 0

    private fun watchEncoder(fps: Double) {
        if (!pipeline.isRunning || pipeline.mode == CameraPipeline.Mode.OFF) { quietReports = 0; return }
        quietReports = if (fps < 0.5) quietReports + 1 else 0
        if (quietReports < 2) return
        quietReports = 0
        val now = android.os.SystemClock.uptimeMillis()
        while (reopenTimes.isNotEmpty() && now - reopenTimes.first() > 60_000) reopenTimes.removeFirst()
        if (reopenTimes.size >= 3) { say("The encoder keeps stopping — not restarted again"); return }
        reopenTimes.addLast(now)
        Trace.refused("encoder", "no frame out of the encoder while streaming: the pipeline is restarted")
        say("The encoder stopped — restarted")
        restoreMode = pipeline.mode
        pipeline.stop(keepSource = true)
        ui.postDelayed({ if (preview.isAvailable) openCamera() }, 400)
    }

    // --- REMOTE CONTROL (v117) -------------------------------------------------
    //
    // Marko, 2.10.2026: "this camera is just giving all the same interface to remote user, but nothing is recorded.
    // Everything is remote controlling"; "remote control is a priority". Mantra Monitor sends KEYS by name and TAPS on
    // the picture up the NDI stream as metadata; the camera does exactly what its own key or tap does. Twice a second,
    // and at once after a command, it sends back what the monitor needs to draw the same interface: the keys with
    // their labels and colours, the status line, the marks, and how the stream is turned.

    private var remoteSent = 0L
    private var remoteDirty = false

    private val remoteTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 100)
            if (!pipeline.isRunning || pipeline.mode == CameraPipeline.Mode.OFF) return
            var n = 0
            while (n++ < 20) {
                val xml = NdiSender.pollCommand() ?: break
                if (xml.contains(CameraState.ROOT)) continue          // our own state, never a command
                val c = CameraCommand.parse(xml) ?: continue
                Trace.control("remote", xml.take(120), "from the monitor")
                c.key?.let { pressKey(it) }
                if (c.tapX != null && c.tapY != null) streamToView(c.tapX, c.tapY)?.let { (x, y) -> tapAt(x, y) }
                c.streamMbps?.let { m ->
                    val v = m.coerceIn(2, 100)
                    settings.streamMbps = v
                    pipeline.setStreamBitRateLive(v * 1_000_000)
                    say("Streaming $v Mbit/s (set from the monitor)")
                }
                remoteDirty = true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (remoteDirty || now - remoteSent > 500) {
                remoteDirty = false
                remoteSent = now
                NdiSender.sendState(remoteState().toXml())
            }
        }
    }

    /** The quarter turns from the stream to this screen, and back. */
    private fun streamDegrees(): Int {
        val e = pipeline.engine
        return Mechanism.sensorToViewDegrees(e.sensorOrientation, displayDegrees(), e.isFrontFacing, manualQuarterTurns)
    }

    private fun streamToView(u: Float, v: Float): Pair<Float, Float>? {
        if (!pipeline.isRunning) return null
        val p = Mechanism.viewRegionToSensor(u, v, u, v, (360 - streamDegrees()) % 360, false)
        val x = if (previewMirrored) 1f - p[0] else p[0]
        return x to p[1]
    }

    /** A key pressed from the monitor: the very key on this screen, clicked. */
    private fun pressKey(name: String) {
        when {
            name == "REC" -> recKey.performClick()
            name.startsWith("MARK") -> name.removePrefix("MARK").toIntOrNull()?.let { markKeys.getOrNull(it)?.performClick() }
            else -> {
                val all = (0 until railLeft.childCount).map { railLeft.getChildAt(it) } +
                    (0 until railRight.childCount).map { railRight.getChildAt(it) }
                all.filterIsInstance<RailButton>().firstOrNull { keyName(it) == name }?.performClick()
                    ?: Trace.refused("remote", "no key called $name")
            }
        }
    }

    private fun keyName(k: RailButton): String = when (k.glyph) {
        RailButton.Glyph.SQUARE -> "MARK0"
        RailButton.Glyph.CIRCLE -> "MARK1"
        RailButton.Glyph.TRIANGLE -> "MARK2"
        RailButton.Glyph.NONE -> k.label
        else -> k.glyph.name.lowercase()
    }

    private fun remoteState(): CameraState {
        fun keyLine(k: RailButton) = listOf(keyName(k), k.label, k.sub ?: "", k.state.name, k.tint?.toString() ?: "").joinToString("~")
        val left = (0 until railLeft.childCount).map { railLeft.getChildAt(it) }
            .filterIsInstance<RailButton>().filter { it.visibility == View.VISIBLE }.map { keyLine(it) }
        val right = (0 until railRight.childCount).map { railRight.getChildAt(it) }
            .filterIsInstance<RailButton>().filter { it.visibility == View.VISIBLE }.map { keyLine(it) }
        val keys = (left + listOf("--") + right + listOf("REC~REC~~${if (rolling) "ARMED" else "OFF"}~")).joinToString("|")
        val d = streamDegrees()
        fun mark(kind: Int, b: FloatArray, colour: Int, shown: Boolean): String {
            val s = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], d, previewMirrored)
            return String.format(java.util.Locale.ROOT, "%d,%.4f,%.4f,%.4f,%.4f,%d,%d", kind, s[0], s[1], s[2], s[3], colour, if (shown) 1 else 0)
        }
        val marks = listOf(
            mark(0, focusSquare.normalisedBounds(), focusSquare.colour(), settings.marksShown and 1 != 0),
            mark(1, exposureCircle.normalisedBounds(), exposureCircle.colour(), settings.marksShown and 2 != 0),
            mark(2, wbBox.normalisedBounds(), wbBox.colour(), settings.marksShown and 4 != 0)
        ).joinToString("|")
        return CameraState(
            recording = rolling, cameraName = settings.sourceName, status = status.text.toString(),
            keys = keys, marks = marks, armed = settings.pinchTarget, turns = (d / 90) % 4,
            streamMbps = pipeline.actualStreamBitRate / 1_000_000
        )
    }

    // --- the three marks (v103) ------------------------------------------------

    /** Grey → white → orange → grey, for the square (0), the circle (1), the triangle (2). */
    private fun stepMark(i: Int) {
        val bit = 1 shl i
        val shown = settings.marksShown and bit != 0
        when {
            !shown -> settings.marksShown = settings.marksShown or bit
            settings.pinchTarget != i -> settings.pinchTarget = i
            else -> {
                settings.marksShown = settings.marksShown and bit.inv()
                // the orange goes to the next mark still on the picture, or stays (a pinch with none is nothing)
                (0..2).firstOrNull { settings.marksShown and (1 shl it) != 0 }?.let { settings.pinchTarget = it }
                if (i == 1 && pipeline.isRunning) { releaseCircle(backToAuto = true); exposureCircle.state = MarkView.State.IDLE }
            }
        }
        Trace.control("marks", "shown ${settings.marksShown}, pinch ${settings.pinchTarget}", "key ${i}")
        say(lastSaid)
        refreshMarks()
        refreshKeys()
    }

    /** What is drawn and what takes a pinch, from the keys' state, the controls and the clean feed. */
    private fun refreshMarks() {
        val canShow = !zonesOn && !fullScreen
        val shown = settings.marksShown
        // the square always takes the tap that focuses; grey only stops it being drawn
        focusSquare.visibility = if (canShow) View.VISIBLE else View.GONE
        focusSquare.drawn = shown and 1 != 0
        exposureCircle.visibility = if (canShow && shown and 2 != 0) View.VISIBLE else View.GONE
        wbBox.visibility = if (canShow && shown and 4 != 0) View.VISIBLE else View.GONE
        focusSquare.pinchResizesMe = settings.pinchTarget == 0
        focusSquare.tapMovesMe = settings.pinchTarget == 0
    }

    /**
     * v105: a tap on the picture serves the ARMED shape (the orange key): it moves there, corrects, locks.
     * Square: focus there. Circle: exposure read there. Triangle: white balance from there.
     */
    private fun tapAt(x: Float, y: Float) {
        // v125: a tap on one or more marks sets every one of them where they are (the monitor's taps too)
        // The armed mark still goes where the finger is, unless it is already there.
        val under = marksUnder(x, y)
        if (settings.pinchTarget.coerceIn(0, 2) in under) {
            Trace.control("tap", String.format("%.2f,%.2f", x, y), "on marks " + under.joinToString())
            under.forEach { fireMark(it) }
            return
        }
        if (under.isNotEmpty()) {
            Trace.control("tap", String.format("%.2f,%.2f", x, y), "also on marks " + under.joinToString())
            under.forEach { fireMark(it) }
        }
        when (settings.pinchTarget) {
            1 -> {
                exposureCircle.moveTo(x, y)
                settings.circleX = exposureCircle.centreX; settings.circleY = exposureCircle.centreY
                Trace.control("tap", String.format("%.2f,%.2f", x, y), "exposure circle")
                meterExposure()
            }
            2 -> {
                wbBox.moveTo(x, y)
                settings.wbBoxX = wbBox.centreX; settings.wbBoxY = wbBox.centreY
                Trace.control("tap", String.format("%.2f,%.2f", x, y), "white balance triangle")
                spotWhiteBalance()
            }
            else -> focusAt(x, y)
        }
    }

    /**
     * v125, OVERLAP: *"If they're overlapping and the user clicks basically on all three at the same time,
     * all three are doing their thing."* Which marks (0 square, 1 circle, 2 triangle) are under the point,
     * on them or within a finger's reach. A grey (hidden) mark is not under anything.
     */
    private fun marksUnder(x: Float, y: Float): List<Int> = buildList {
        if (focusSquare.hits(x, y)) add(0)
        if (exposureCircle.hits(x, y)) add(1)
        if (wbBox.hits(x, y)) add(2)
    }

    private fun fireMark(i: Int) = when (i) {
        0 -> focus.focusHereAndHold()
        1 -> meterExposure()
        else -> spotWhiteBalance()
    }

    /** The mark that took the touch has fired; the others under the same finger fire with it. */
    private fun alsoUnder(x: Float, y: Float, took: Int) {
        val others = marksUnder(x, y) - took
        if (others.isEmpty()) return
        Trace.control("tap", String.format("%.2f,%.2f", x, y), "overlap: mark $took and " + others.joinToString())
        others.forEach { fireMark(it) }
    }

    /** A pinch anywhere resizes the orange mark, when that is the circle or the triangle. */
    private val markPinch by lazy {
        android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                when (settings.pinchTarget) {
                    1 -> exposureCircle.size *= d.scaleFactor
                    2 -> wbBox.size *= d.scaleFactor
                }
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                when (settings.pinchTarget) {
                    1 -> settings.circleSize = exposureCircle.size
                    2 -> settings.wbSize = wbBox.size
                }
                Trace.control("mark size", settings.pinchTarget, "pinched")
            }
        }).apply { isQuickScaleEnabled = false }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (settings.pinchTarget != 0 && !zonesOn && !fullScreen) markPinch.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** The circle, as the camera's own region: the same turn from screen to sensor as the focus box. */
    private fun sensorRegionOf(b: FloatArray): FloatArray? {
        if (!pipeline.isRunning) return null
        val engine = pipeline.engine
        val degrees = Mechanism.sensorToViewDegrees(engine.sensorOrientation, displayDegrees(), engine.isFrontFacing, manualQuarterTurns)
        val onStream = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], degrees, previewMirrored)
        val array = engine.activeArray() ?: return onStream
        return Mechanism.streamRegionToArray(onStream, bufferSize.width, bufferSize.height, array.width(), array.height())
    }

    /** v125: the circle's ramps; true while automatic exposure is held by the circle at the ramped values. */
    private val exposureRamp by lazy { Ramp(ui) }
    private var circleHolds = false
    private var circleTicket = 0
    /**
     * v126: how many stops the picture moves for a stop of exposure, per curve, learnt round to round. The
     * phone's own tone curve answered 0.58 (Pixel 7, 2.10.2026 10:02), so v125 needed a second ramp after a
     * pause; with this the next tap arrives in one move.
     */
    private val exposureResponse = HashMap<Int, Double>()
    private var circleLastLinear = 0.0
    private var circleLastStops = 0.0

    /** A tap on the circle: exposure is read there and locks. Orange while it reads, green when locked. */
    private fun meterExposure() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        val engine = pipeline.engine
        // v125, THE RAMP: on automatic exposure the circle is read off the picture (which changes nothing)
        // and the exposure walks there, eased in and out, instead of the camera's own jump
        if (Mechanism.exposureMode(isoAuto, shutterAuto) == Mechanism.Exposure.AUTO && settings.focusRackMs > 0 &&
            engine.lastIso != null && engine.lastExposureNs != null && engine.isoRange() != null
        ) {
            exposureCircle.state = MarkView.State.MEASURING
            exposureLockedAt = null
            say("Exposure: reading the circle…")
            exposureRound(1, ++circleTicket)
            return
        }
        if (engine.manualExposure) {
            exposureCircle.state = MarkView.State.NEUTRAL
            say("Manual exposure: the circle is where the grey card is read")
            return
        }
        engine.exposureRegion = sensorRegionOf(exposureCircle.normalisedBounds())
        exposureCircle.state = MarkView.State.MEASURING
        exposureLockedAt = null
        say("Exposure: reading the circle…")
        val started = engine.meterExposureHere { settled ->
            ui.post {
                exposureCircle.state = if (settled) MarkView.State.NEUTRAL else MarkView.State.FAILED
                exposureLockedAt = exposureLockText()
                say(if (settled) "Exposure locked on the circle" else "Exposure locked where it was (the camera did not settle)")
            }
        }
        if (!started) {
            exposureCircle.state = MarkView.State.FAILED
            say("The camera would not meter on the circle")
        }
    }

    /** The circle's mean scene light, linear 0..1 (luma of the decoded channels), or null with no picture. */
    private fun sampleCircleLinear(): Double? {
        if (!preview.isAvailable) return null
        val w = (preview.width / 4).coerceAtLeast(48)
        val h = (preview.height / 4).coerceAtLeast(48)
        val bmp = runCatching { preview.getBitmap(w, h) }.getOrNull() ?: return null
        val bd = exposureCircle.normalisedBounds()
        val curve = LogCurves.Curve.entries[curveIndex]
        var sum = 0.0
        var n = 0
        for (y in (bd[1] * h).toInt().coerceIn(0, h - 1) until (bd[3] * h).toInt().coerceIn(1, h))
            for (x in (bd[0] * w).toInt().coerceIn(0, w - 1) until (bd[2] * w).toInt().coerceIn(1, w)) {
                if (!exposureCircle.contains((x + 0.5f) / w, (y + 0.5f) / h)) continue
                val c = bmp.getPixel(x, y)
                sum += 0.2126 * LogCurves.decode(curve, android.graphics.Color.red(c) / 255.0) +
                    0.7152 * LogCurves.decode(curve, android.graphics.Color.green(c) / 255.0) +
                    0.0722 * LogCurves.decode(curve, android.graphics.Color.blue(c) / 255.0)
                n++
            }
        bmp.recycle()
        return if (n < 4) null else sum / n
    }

    /**
     * One round of the circle (v125): read it, and if it is more than a fifth of a stop off 18% grey, walk
     * ISO (then shutter, for what ISO cannot reach) there over the ramp time, eased in and out; read again.
     * Held where it lands — the lock — until the circle is put away or exposure is changed.
     */
    private fun exposureRound(round: Int, ticket: Int) {
        if (circleTicket != ticket || !pipeline.isRunning) return
        val engine = pipeline.engine
        val linear = sampleCircleLinear()
        if (linear == null) {
            exposureCircle.state = MarkView.State.FAILED
            say("Exposure: the circle could not be read")
            return
        }
        val error = Mechanism.circleExposureError(linear)
        if (round > 1 && kotlin.math.abs(circleLastStops) > 0.15 && circleLastLinear > 1e-4) {
            val answered = kotlin.math.ln(linear / circleLastLinear) / kotlin.math.ln(2.0)
            exposureResponse[curveIndex] = (answered / circleLastStops).coerceIn(0.3, 1.5)
        }
        val response = exposureResponse[curveIndex] ?: 1.0
        val iso0 = engine.lastIso ?: return
        val shutter0 = engine.lastExposureNs ?: return
        val isoRange = engine.isoRange() ?: return
        val (shMin, shMax) = shutterBounds() ?: (shutter0 to shutter0)
        val (iso1, shutter1) = Mechanism.splitExposure((error / response).coerceIn(-4.0, 4.0),
            iso0, shutter0, isoRange.lower, isoRange.upper, shMin, shMax)
        circleLastLinear = linear
        circleLastStops = kotlin.math.ln(iso1.toDouble() / iso0 * shutter1.toDouble() / shutter0) / kotlin.math.ln(2.0)
        Trace.state(String.format(java.util.Locale.ROOT,
            "exposure circle: round %d, light %.3f, %+.2f stops (response %.2f), ISO %d → %d, %s → %s", round, linear, error, response,
            iso0, iso1, Mechanism.formatShutter(shutter0), Mechanism.formatShutter(shutter1)))
        val nothingToMove = iso1 == iso0 && shutter1 == shutter0
        if (kotlin.math.abs(error) < CIRCLE_TOLERANCE_STOPS || round > CIRCLE_ROUNDS || nothingToMove) {
            // held here: from the camera's auto exposure to these same values, which changes nothing on the picture
            if (!circleHolds) { circleHolds = true; engine.setManualExposure(iso0, shutter0) }
            val there = kotlin.math.abs(error) < CIRCLE_TOLERANCE_STOPS
            exposureCircle.state = if (there) MarkView.State.NEUTRAL else MarkView.State.FAILED
            exposureLockedAt = exposureLockText()
            say(if (there) "Exposure locked on the circle"
                else String.format(java.util.Locale.ROOT, "Exposure: as close as it goes (%+.1f stops)", error))
            refreshZones()
            return
        }
        circleHolds = true
        val ms = if (round == 1) settings.focusRackMs else (settings.focusRackMs / 2).coerceAtLeast(300L)
        exposureRamp.run(ms,
            step = { p ->
                circleTicket == ticket && engine.setManualExposure(
                    Math.round(Mechanism.rampLog(iso0.toDouble(), iso1.toDouble(), p)).toInt(),
                    Mechanism.rampLog(shutter0.toDouble(), shutter1.toDouble(), p).toLong(),
                    trace = p >= 1f)
            },
            done = { ui.postDelayed({ exposureRound(round + 1, ticket) }, CIRCLE_SETTLE_MS) },
            failed = {
                if (circleTicket == ticket) {
                    exposureCircle.state = MarkView.State.FAILED
                    say("Exposure: the camera refused the ramp")
                }
            })
    }

    /** The circle is put away or exposure changed: the ramp stops and the hold is let go. */
    private fun releaseCircle(backToAuto: Boolean) {
        circleTicket++
        exposureRamp.cancel()
        val held = circleHolds
        circleHolds = false
        if (backToAuto && pipeline.isRunning) {
            if (held) pipeline.engine.setAutoExposure() else pipeline.engine.releaseExposureLock()
        }
    }

    /**
     * THE SPOT (v101): the white balance triangle's lit pixels, in LINEAR light.
     *
     * Round 1 chooses the pixels — lit (not the black around a key) and not clipped — and every later round
     * reads exactly those, because v100 let the population change from round to round (a red key crossing
     * the clipping line dropped out) and the answer swung r/g 0.16 → 3.19 → 0.18 (trace 2.10.2026 03:41).
     * Returns r, g, b means, or null when too few pixels qualify.
     */
    private var spotMask: BooleanArray? = null

    private fun sampleSpotRgb(choose: Boolean): DoubleArray? {
        if (!preview.isAvailable) return null
        // v102: at half the view's OWN size and shape. v101 read a 192x108 landscape thumbnail of a portrait
        // view, so each sample row averaged ~17 screen rows and a keyboard's thin lit legends blended into the
        // black around them: "nothing lit in the triangle" over a lit keyboard (trace 2.10.2026 03:47).
        val w = (preview.width / 2).coerceAtLeast(64)
        val h = (preview.height / 2).coerceAtLeast(64)
        val bmp = runCatching { preview.getBitmap(w, h) }.getOrNull() ?: return null
        val bd = wbBox.normalisedBounds()
        val x0 = (bd[0] * w).toInt().coerceIn(0, w - 1)
        val x1 = (bd[2] * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (bd[1] * h).toInt().coerceIn(0, h - 1)
        val y1 = (bd[3] * h).toInt().coerceIn(y0 + 1, h)
        val curve = LogCurves.Curve.entries[curveIndex]
        val count = (x1 - x0) * (y1 - y0)
        if (choose || spotMask?.size != count) spotMask = BooleanArray(count)
        val mask = spotMask!!
        val sum = DoubleArray(3)
        var n = 0
        var i = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val bl = android.graphics.Color.blue(c)
            if (choose) mask[i] = maxOf(r, g, bl) >= SPOT_DARK && maxOf(r, g, bl) <= SPOT_CLIPPED &&
                wbBox.contains((x + 0.5f) / w, (y + 0.5f) / h)
            if (mask[i]) {
                sum[0] += LogCurves.decode(curve, r / 255.0)
                sum[1] += LogCurves.decode(curve, g / 255.0)
                sum[2] += LogCurves.decode(curve, bl / 255.0)
                n++
            }
            i++
        }
        bmp.recycle()
        return if (n < SPOT_MIN_PIXELS || sum[1] <= 0) null else doubleArrayOf(sum[0] / n, sum[1] / n, sum[2] / n)
    }

    private var spotTicket = 0
    /** v126: how strongly the picture answered the gains, per curve, learnt by the last tap. */
    private val spotResponse = HashMap<Int, DoubleArray>()
    private val wbRamp by lazy { Ramp(ui) }
    private var spotLearn: WhiteBalance.SpotLearn? = null

    /** A tap on the white balance rectangle. */
    private fun spotWhiteBalance() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        val ticket = ++spotTicket
        probeTicket++                       // a running A gives way
        // v126: from what the last tap on this curve learnt, or cautious (a first step that falls short)
        spotLearn = WhiteBalance.SpotLearn(spotResponse[curveIndex]
            ?: doubleArrayOf(WhiteBalance.CAUTIOUS_RESPONSE, WhiteBalance.CAUTIOUS_RESPONSE))
        wbBox.state = MarkView.State.MEASURING
        wbLockedAt = null
        say("White balance: reading the triangle…")
        spotRound(1, ticket)
    }

    /**
     * One round: read the rectangle; stop when it is neutral or the rounds are spent; otherwise step red and
     * blue gain by what the last rounds taught about how strongly the picture answers (WhiteBalance.SpotLearn),
     * and come back when the picture has taken the new gains.
     */
    private fun spotRound(round: Int, ticket: Int) {
        if (spotTicket != ticket || !pipeline.isRunning) return
        val engine = pipeline.engine
        val rgb = sampleSpotRgb(choose = round == 1)
        val gains = engine.currentGains()
        val learn = spotLearn ?: return
        if (rgb == null || gains == null) {
            wbBox.state = MarkView.State.FAILED
            say(if (round == 1) "Nothing lit in the triangle (all dark or clipped) — point it at something white or grey"
                else "White balance: the triangle went dark or clipped at round $round")
            Trace.refused("white balance spot", "round $round: nothing in the triangle between dark and clipped")
            return
        }
        Trace.state(String.format(java.util.Locale.ROOT,
            "white balance spot: round %d, triangle linear r/g %.3f b/g %.3f", round, rgb[0] / rgb[1], rgb[2] / rgb[1]))
        val neutral = WhiteBalance.spotNeutral(rgb, SPOT_TOLERANCE)
        if (neutral || round > WhiteBalance.SPOT_ROUNDS) {
            if (round > 2) spotResponse[curveIndex] = learn.p.copyOf()
            val k = engine.adoptSpotAsAnchor()
            if (k != null) wbKelvin = k
            wbAuto = false
            wbBox.state = if (neutral) MarkView.State.NEUTRAL else MarkView.State.FAILED
            wbLockedAt = wbLockText()
            say(if (neutral) "White balance locked: neutral after ${round - 1} step${if (round == 2) "" else "s"}"
                else String.format(java.util.Locale.ROOT, "White balance: closest after %d steps (r/g %.2f, b/g %.2f)",
                    round - 1, rgb[0] / rgb[1], rgb[2] / rgb[1]))
            refreshZones(); refreshKeys()
            return
        }
        val next = learn.next(gains, rgb)
        // v125, THE RAMP: the gains walk to the next step, eased in and out, in ratios (a stop of red looks
        // the same at either end); the round reads the picture once it has arrived and settled
        val ms = if (round == 1) settings.focusRackMs else (settings.focusRackMs / 2).coerceAtLeast(300L)
        wbRamp.run(if (settings.focusRackMs > 0) ms else 0L,
            step = { p ->
                spotTicket == ticket && engine.setWhiteBalanceGains(
                    FloatArray(4) { Mechanism.rampLog(gains[it].toDouble(), next[it].toDouble(), p).toFloat() },
                    round, trace = p >= 1f)
            },
            done = { ui.postDelayed({ spotRound(round + 1, ticket) }, SPOT_SETTLE_MS) },
            failed = {
                if (spotTicket == ticket) {
                    wbBox.state = MarkView.State.FAILED
                    say("White balance: the camera refused the gains")
                }
            })
    }

    /** The last compensation move and the error before it, and the step it proved to be. */
    private var lastMove: Pair<Int, Double>? = null
    private var learntStep = 0.0

    /** The grey readout for the status line, or null on the phone's own curve. */
    private var greyReadout: String? = null

    /**
     * LOG-AWARE AUTO EXPOSURE (v98, Phase 5). *"Make automatic exposure expose
     * for the chosen curve, exactly as the manufacturer specifies."* Twice a
     * second: the grey card is read, its distance from the curve's own grey is
     * worked out in stops, and while exposure is automatic the camera's
     * exposure compensation is moved half of it. On the phone's own curve the
     * compensation goes back to zero. With exposure manual it only reads.
     */
    private val logExposureTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) runCatching { stepLogExposure() }
                .onFailure { Trace.fault("log exposure", it) }
            ui.postDelayed(this, 500)
        }
    }

    private fun stepLogExposure() {
        val engine = pipeline.engine
        val curve = LogCurves.Curve.entries[curveIndex]
        // v125: not while the circle holds the exposure, or the compensation would creep while nothing answers
        val auto = isoAuto && shutterAuto && !circleHolds
        val target = Mechanism.greyTarget(curve)
        if (target == null) {
            greyReadout = null
            if (auto && engine.aeCompensation != 0) engine.setExposureCompensation(0)
            return
        }
        val measured = sampleGreyCard() ?: return
        greyReadout = String.format(
            java.util.Locale.ROOT, "grey %.0f%% · %s %.0f%%", measured * 100, curve.displayName, target * 100
        )
        if (!auto) return
        val range = engine.aeCompensationRange() ?: return
        val error = Mechanism.logExposureError(curve, measured) ?: return
        // What one step really moved, learnt from the last move: a camera
        // whose step is coarser than it says would otherwise hunt between two.
        lastMove?.let { (steps, before) ->
            if (steps != 0) learntStep = maxOf(learntStep, kotlin.math.abs(error - before) / kotlin.math.abs(steps))
        }
        lastMove = null
        val step = maxOf(engine.aeCompensationStep(), learntStep)
        val next = Mechanism.nextCompensation(engine.aeCompensation, error, step, range.lower, range.upper)
        val moved = next - engine.aeCompensation
        if (next != engine.aeCompensation && engine.setExposureCompensation(next)) {
            lastMove = moved to error
            Trace.control(
                "log exposure",
                String.format(java.util.Locale.ROOT, "%s grey %.3f, target %.3f, %+.2f stops", curve.displayName, measured, target, error),
                "compensation $next steps"
            )
        }
    }

    private fun sampleBrightness(): Double? {
        if (!preview.isAvailable) return null
        val bmp = runCatching { preview.getBitmap(24, 14) }.getOrNull() ?: return null
        var sum = 0.0
        var n = 0
        for (y in 4 until 10) for (x in 8 until 16) {
            val c = bmp.getPixel(x, y)
            sum += (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) +
                0.114 * android.graphics.Color.blue(c)) / 255.0
            n++
        }
        bmp.recycle()
        return if (n == 0) null else sum / n
    }

    /**
     * The zones on, and the focus box off.
     *
     * Exclusive on purpose: a tap on the picture that could mean "focus here"
     * and could mean "start changing ISO" is a tap that means neither.
     */
    private fun toggleZones() {
        zonesOn = !zonesOn
        zones.visibility = if (zonesOn) View.VISIBLE else View.GONE
        iris.visibility = if (zonesOn) View.VISIBLE else View.GONE
        refreshMarks()
        Trace.control("controls", if (zonesOn) "on" else "off",
            if (zonesOn) "focus box put away" else "focus box back")
        refreshZones()
        refreshKeys()
    }

    /**
     * One zone dragged. Right is more of everything.
     *
     * The drag moves the **knob**, and the value is read off where the knob
     * ends up. Every zone's whole track is the whole of the range the camera
     * will actually honour — so a full sweep is the full range, half way along
     * is half way along, and there is no part of the travel that cannot be
     * reached. Geometric, because ISO and shutter are stops and a stop is what
     * an operator thinks in.
     *
     * Dragging a zone takes that parameter over, rather than doing nothing
     * until a key somewhere else has been pressed first. A zone that has to be
     * armed is a zone that looks broken.
     */
    private fun nudge(index: Int, delta: Float) {
        val engine = pipeline.engine
        // v127: a hand on the fader takes over from a preset's ramp, where it is
        if (index == 0 || index == 1) glider?.cancel()
        when (index) {
            0 -> {
                if (isoAuto) setParamAuto(0, false, quiet = true)
                val range = engine.isoRange() ?: return
                isoPosition = (isoPosition + delta).coerceIn(0f, 1f)
                iso = Mechanism.valueAtPosition(
                    isoPosition, range.lower.toDouble(), range.upper.toDouble()
                ).toInt().coerceIn(range.lower, range.upper)
                applyDragExposure()
            }
            1 -> {
                if (shutterAuto) setParamAuto(1, false, quiet = true)
                val (low, high) = shutterBounds() ?: return
                shutterPosition = (shutterPosition + delta).coerceIn(0f, 1f)
                shutterNs = Mechanism.valueAtPosition(
                    shutterPosition, low.toDouble(), high.toDouble()
                ).toLong().coerceIn(low, high)
                applyDragExposure()
            }
            2 -> {
                // A lens with no focus motor is said outright rather than
                // letting the number move while the picture does not. An ultra
                // wide is fixed on nearly every phone, and the selfie lens on
                // this one, and for six versions the drag was refused in
                // silence — which is exactly what "focus doesn't change focus"
                // looks like from the outside.
                if (!engine.supportsManualFocus()) { sayFixedFocus(); return }
                focusFraction = (focusFraction + delta).coerceIn(0f, 1f)
                stopTrack()
                if (focus.mode == FocusDirector.Mode.AUTO) {
                    focus.setMode(FocusDirector.Mode.MANUAL)
                    refreshKeys()
                }
                engine.setManualFocus(focusFraction)
                // And then look, a moment later, at whether the glass moved.
                ui.removeCallbacks(focusWatch)
                ui.postDelayed(focusWatch, 600)
            }
            3 -> {
                // Left is tungsten, right is daylight — the way the numbers on
                // a colour meter run, and the way every white balance dial ever
                // made is laid out. Taking it over starts from what the camera
                // had settled on, so the colour does not jump.
                if (wbAuto) {
                    // A drag during a measurement ends it where the camera had got to.
                    probeTicket++
                    wbKelvin = engine.measuredKelvin() ?: wbKelvin
                    wbAuto = false
                }
                val at = (WhiteBalance.travel(wbKelvin) + delta).coerceIn(0f, 1f)
                wbKelvin = WhiteBalance.kelvinAt(at)
                engine.setWhiteBalanceKelvin(wbKelvin)
            }
        }
        refreshZones()
    }

    /**
     * **Push-auto white balance.** Ask the camera once, keep the number.
     *
     * *"I don't want it to go to auto mode. I want it to read the right white
     * balance from auto and move the slider to that position, so the white
     * balance looks the same — and I can see exactly how many Kelvin that is."*
     *
     * This is the AWB button on a real camera, and it is not the same thing as
     * switching auto on: auto is the camera deciding again every frame, which
     * is what an operator lighting a scene is trying to stop. A probe is the
     * camera deciding **once**, at a moment he chooses, with the answer left on
     * the fader as a number that will then sit still.
     *
     * The picture does not jump at the end of it, because the temperature that
     * comes back is applied through the anchor, and the anchor is the very
     * measurement just taken.
     */
    /** v127: which lenses have had their curve measured this run, and whether one is being measured now. */
    private val curveTried = HashSet<String>()
    private var calibrating = false

    /** The open lens's measured white balance curve, from settings, onto the engine (or none). */
    private fun loadWbCurve() {
        pipeline.engine.presetCurve = WhiteBalance.usableCurve(WhiteBalance.decodeCurve(settings.wbCurve("m." + lensKey())))
    }

    private fun probeWhiteBalance() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        if (calibrating) return
        // v127: a lens with no curve yet measures it first, once: its presets, a few frames each
        val engine = pipeline.engine
        val lens = lensKey()
        if (engine.presetCurve == null && curveTried.add(lens)) {
            say("Measuring this lens's white balance curve…")
            calibrating = engine.calibratePresets { found ->
                ui.post {
                    calibrating = false
                    val usable = WhiteBalance.usableCurve(found)
                    if (usable != null && lensKey() == lens) {
                        engine.presetCurve = usable
                        settings.setWbCurve("m." + lens, WhiteBalance.encodeCurve(usable))
                        Trace.control("white balance curve", WhiteBalance.encodeCurve(usable), "kept for lens $lens")
                    } else {
                        Trace.refused("white balance curve", "the presets gave no usable curve (" +
                            WhiteBalance.encodeCurve(found) + "): the physics of the light instead")
                    }
                    probeWhiteBalance()
                }
            }
            if (calibrating) return
        }
        say("Measuring white balance…")
        // v90: A on WB is a measurement, not a mode. *"When I press auto, it
        // will just stay auto for a few seconds until it finds the white
        // balance from the camera, and then it will switch back to manual
        // automatically."* A shows while the camera looks; nothing tracks
        // the light afterwards and no earlier manual value comes back.
        wbAuto = true
        wbMeasuredOnce = true
        refreshZones()
        val ticket = ++probeTicket
        // A deadline, because an answer that never comes (the lens changed,
        // the session died) would leave the camera tracking for ever.
        ui.postDelayed({
            if (probeTicket == ticket) {
                probeTicket++
                wbKelvin = pipeline.engine.measuredKelvin() ?: wbKelvin
                wbAuto = false
                if (pipeline.isRunning) pipeline.engine.setWhiteBalanceKelvin(wbKelvin)
                say("White balance held at ${WhiteBalance.format(wbKelvin)} (the camera did not settle)")
                Trace.refused("white balance probe", "no answer within 6 s")
                refreshZones()
                refreshKeys()
            }
        }, 6000)
        val started = pipeline.engine.probeWhiteBalance { kelvin ->
            ui.post {
                // Late, after the deadline or a newer probe: not this one's answer.
                if (probeTicket != ticket) return@post
                probeTicket++
                if (kelvin == null) {
                    // No calibration to turn gains into a temperature. The
                    // camera's own answer is still on the picture, so it is
                    // left there rather than replaced by a number invented
                    // here — a fader that lies is worse than one that stops.
                    wbAuto = true
                    say("This sensor publishes no calibration — left on the camera's own")
                } else {
                    wbKelvin = kelvin
                    wbAuto = false
                    pipeline.engine.setWhiteBalanceKelvin(kelvin)
                    say("White balance measured: ${WhiteBalance.format(kelvin)}, now manual")
                }
                refreshZones()
                refreshKeys()
            }
        }
        if (!started) {
            probeTicket++
            say("The camera would not take a white balance reading")
        }
    }

    /** The ISO presets of the open lens: BASE and, where it says, HIGH. */
    private fun isoPresets(): List<Pair<String, Int>> {
        val engine = pipeline.engine
        if (!pipeline.isRunning) return emptyList()
        val range = engine.isoRange() ?: return emptyList()
        return Mechanism.isoPresets(range.lower, range.upper, engine.maxAnalogIso())
    }

    /** The five shutter presets at this frame rate, as exposure times. */
    private fun shutterPresets(): List<Long> {
        val range = pipeline.engine.exposureRange() ?: return emptyList()
        return Mechanism.SHUTTER_ANGLES.map {
            Mechanism.shutterForAngle(it, pipeline.fps, range.lower, range.upper)
        }
    }

    /**
     * A preset under a fader (v90). *"These presets are nudging the slider of
     * shutter speed to the right shutter speed."* The parameter goes to M and
     * the fader glides there over a quarter of a second, the picture following,
     * then lands on the exact value.
     */
    private fun applyPreset(index: Int, preset: Int) {
        if (!pipeline.isRunning) return
        when (index) {
            0 -> {
                val (name, target) = isoPresets().getOrNull(preset) ?: return
                val range = pipeline.engine.isoRange() ?: return
                if (isoAuto) setParamAuto(0, false, quiet = true)
                // v129: with the shutter on A, it moves against ISO every frame so the exposure holds
                val carry = carryFrom()
                glide(isoPosition, Mechanism.positionOfValue(
                    target.toDouble(), range.lower.toDouble(), range.upper.toDouble()
                ), { p ->
                    isoPosition = p
                    iso = Mechanism.valueAtPosition(p, range.lower.toDouble(), range.upper.toDouble())
                        .toInt().coerceIn(range.lower, range.upper)
                    carry?.let { (iso0, sh0) -> carryShutter(iso0, sh0) }
                }) { iso = target; carry?.let { (iso0, sh0) -> carryShutter(iso0, sh0) }; landCarry() }
                say("ISO $target ($name)")
            }
            1 -> {
                val target = shutterPresets().getOrNull(preset) ?: return
                val (low, high) = shutterBounds() ?: return
                if (shutterAuto) setParamAuto(1, false, quiet = true)
                // v129: with ISO on A, it moves against the shutter every frame so the exposure holds
                val carry = carryFrom()
                glide(shutterPosition, Mechanism.positionOfValue(
                    target.toDouble(), low.toDouble(), high.toDouble()
                ), { p ->
                    shutterPosition = p
                    shutterNs = Mechanism.valueAtPosition(p, low.toDouble(), high.toDouble()).toLong()
                    carry?.let { (iso0, sh0) -> carryIso(iso0, sh0) }
                }) { shutterNs = target; carry?.let { (iso0, sh0) -> carryIso(iso0, sh0) }; landCarry() }
                say("${Mechanism.SHUTTER_ANGLES[preset]}° = ${Mechanism.formatShutter(target)}")
            }
        }
        // The M key reads the switches: a preset just put one on M.
        if (Mechanism.cameraMode(switches()) == "HM") lastHalf = switches()
        refreshKeys()
    }

    private var glider: android.animation.ValueAnimator? = null

    /**
     * v129: a preset key moves one half; when the other half is on A (the app's loop), that half moves against
     * it every frame so ISO × shutter, the exposure, stays where it was — what a camera on auto ISO does when
     * the shutter is turned. Returns the starting ISO and shutter, or null when nothing is to be carried.
     */
    private fun carryFrom(): Pair<Int, Long>? {
        val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
        if (loopNative || (mode != Mechanism.Exposure.ISO_PRIORITY && mode != Mechanism.Exposure.SHUTTER_PRIORITY)) return null
        presetCarries = true
        return iso to shutterNs
    }

    private fun carryIso(iso0: Int, sh0: Long) {
        val r = pipeline.engine.isoRange() ?: return
        iso = Math.round(iso0.toDouble() * sh0 / shutterNs.coerceAtLeast(1L)).toInt().coerceIn(r.lower, r.upper)
    }

    private fun carryShutter(iso0: Int, sh0: Long) {
        val (low, high) = shutterBounds() ?: return
        shutterNs = (sh0.toDouble() * iso0 / iso.coerceAtLeast(1)).toLong().coerceIn(low, high)
    }

    /** The carried half is where the spring starts from, at rest: the loop goes on from there, smoothly. */
    private fun landCarry() {
        val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
        presetCarries = false
        springX = kotlin.math.ln(autoHalf(mode))
        springV = 0.0
        loopGoal = autoHalf(mode)
        loopSentIso = iso
        loopSentShutterNs = shutterNs
    }

    /**
     * Moves a fader from [from] to [to], applying exposure on the way, then [land]s exactly.
     *
     * v127, THE RAMP: *"please do the ramping when I change my shutter speed in degrees from button to
     * button"* (Marko, 2.10.2026). Over the ramp time, eased in and out (smootherstep), the fader's own
     * travel being even in stops; v126 took a quarter of a second, too quick to be a hand. Zero snaps.
     */
    private fun glide(from: Float, to: Float, step: (Float) -> Unit, land: () -> Unit) {
        glider?.cancel()
        glider = android.animation.ValueAnimator.ofFloat(from, to.coerceIn(0f, 1f)).apply {
            duration = settings.focusRackMs.coerceAtLeast(0L)
            interpolator = android.animation.TimeInterpolator { Mechanism.rampEase(it) }
            addUpdateListener {
                step(it.animatedValue as Float)
                applyDragExposure()
                refreshZones()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    land()
                    applyDragExposure()
                    refreshZones()
                }
            })
            start()
        }
    }

    /** Which probe is waiting; anything else arriving is stale. */
    private var probeTicket = 0

    /** The first camera session measures white once, so WB starts as a number. */
    private var wbMeasuredOnce = false

    /**
     * A drag on ISO or shutter: in full manual both go to the camera; in a
     * native priority mode only the held half is sent and the camera keeps
     * the other; in the app's loop the loop goes on from the new value.
     */
    private fun applyDragExposure() {
        val engine = pipeline.engine
        if (loopNative) engine.setPriorityExposure(if (!isoAuto) 1 else 2, iso, shutterNs)
        else engine.setManualExposure(iso, shutterNs)
    }

    /** Said once per lens: a fixed lens is a fact, not an error to repeat. */
    private fun sayFixedFocus() {
        val lens = lenses.getOrNull(activeLens)?.label ?: "This lens"
        val key = lensKey()
        if (focusComplaintFor != key) {
            focusComplaintFor = key
            Trace.refused("focus", "$lens has no focus travel; it is fixed")
        }
        say("$lens is fixed focus — there is nothing to pull")
    }

    /**
     * Did the glass actually move?
     *
     * A lens that refuses in silence and a lens doing its job look identical
     * from in here, and this camera has shipped both. The request and what the
     * capture result says the lens reached are compared a beat later, and a
     * lens that is not following is said on the status line rather than left
     * for the operator to work out from a picture that will not sharpen.
     */
    private val focusWatch = Runnable {
        if (pipeline.isRunning && !pipeline.engine.focusIsResponding()) {
            val lens = lenses.getOrNull(activeLens)?.label ?: "This lens"
            say("$lens is not following the focus zone — see the trace")
            Trace.refused(
                "focus",
                "$lens did not reach what was asked: wanted " +
                    pipeline.engine.focusCommanded + ", reached " +
                    pipeline.engine.lastFocusDistance
            )
        }
    }

    /**
     * What the four zones currently say.
     *
     * The numbers are the camera's own answers wherever the camera has one —
     * the ISO and the shutter it settled on, the distance the lens actually
     * reached — rather than the value that was asked for. A zone that echoes
     * the request agrees with itself whatever the lens is doing, which is how
     * a focus control that never moved anything looked correct for six
     * versions.
     *
     * The knob, though, is where the thumb left it. Those are two different
     * questions and answering both with one number is what made the shutter
     * zone stop two thirds of the way along its own track.
     */
    private fun refreshZones() {
        val engine = pipeline.engine
        val running = pipeline.isRunning
        val apertures = engine.apertures()
        val liveIso = engine.lastIso ?: iso
        val liveShutter = engine.lastExposureNs ?: shutterNs

        val canFocus = running && engine.supportsManualFocus()
        val reached = engine.lastFocusDistance
        val autoFocus = focus.mode == FocusDirector.Mode.AUTO
        val focusText = when {
            !running -> "—"
            // No focus motor on this lens. An ultra wide on most phones, and
            // the selfie lens on this one.
            !canFocus -> "FIXED"
            autoFocus -> "AUTO"
            reached != null && reached > 0.01f -> String.format("%.2fm", 1f / reached)
            else -> "∞"
        }
        val closest = if (running) engine.minimumFocusDistance() else 0f
        val focusPosition = when {
            !canFocus -> 0f
            autoFocus && reached != null && closest > 0f -> (reached / closest).coerceIn(0f, 1f)
            else -> focusFraction
        }

        val isoPresetList = isoPresets()
        val shutterPresetList = if (running) shutterPresets() else emptyList()
        val isoPositionShown = engine.isoRange()?.let {
            if (!isoAuto) isoPosition
            else Mechanism.positionOfValue(
                liveIso.toDouble(), it.lower.toDouble(), it.upper.toDouble()
            )
        } ?: isoPosition
        val shutterPositionShown = shutterBounds()?.let { (low, high) ->
            if (!shutterAuto) shutterPosition
            else Mechanism.positionOfValue(
                liveShutter.toDouble(), low.toDouble(), high.toDouble()
            )
        } ?: shutterPosition

        zones.zones = listOf(
            // While a fader is his, the number is what he set, so it moves
            // with his thumb rather than a frame behind the camera.
            ControlZones.Zone(
                "ISO", (if (isoAuto) liveIso else iso).toString(), true, isoPositionShown, isoAuto,
                presets = isoPresetList.map { (name, value) -> "$name $value" },
                presetLit = if (isoAuto) -1
                    else Mechanism.presetLit(iso.toDouble(), isoPresetList.map { it.second.toDouble() })
            ),
            ControlZones.Zone(
                "SHTR", Mechanism.formatShutter(if (shutterAuto) liveShutter else shutterNs), true,
                shutterPositionShown, shutterAuto,
                presets = if (shutterPresetList.isEmpty()) emptyList()
                    else Mechanism.SHUTTER_ANGLES.map { "$it°" },
                presetLit = if (shutterAuto) -1
                    else Mechanism.presetLit(shutterNs.toDouble(), shutterPresetList.map { it.toDouble() })
            ),
            ControlZones.Zone("FOCUS", focusText, canFocus, focusPosition, autoFocus),
            // Tungsten on the left, daylight on the right. Dragging it takes
            // it off auto; two taps hand it back.
            ControlZones.Zone(
                "WB",
                if (wbAuto) "MEASURING" else WhiteBalance.format(wbKelvin),
                running,
                WhiteBalance.travel(wbKelvin),
                wbAuto
            )
        )

        iris.text = apertures.firstOrNull()?.let { String.format("IRIS  f/%.2f", it) } ?: ""
        // Never in the clean feed. This line runs once a second, and it used
        // to put the F-stop back on a screen that was meant to be empty.
        iris.visibility =
            if (zonesOn && !fullScreen && apertures.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * The shutter speeds this camera will actually honour, at this frame rate.
     *
     * The ceiling is one frame interval — a shutter longer than a frame cannot
     * be held at the frame rate, and the camera settles the argument by
     * dropping the rate, which on a live stream is worse than a dark picture.
     * The floor is 1/8000, because the sensor's own eleven microseconds reads
     * 1/92030 and nobody has ever chosen it.
     */
    private fun shutterBounds(): Pair<Long, Long>? {
        val range = pipeline.engine.exposureRange() ?: return null
        return Mechanism.shutterFaderRange(pipeline.fps, range.lower, range.upper)
    }

    /**
     * Put the knobs where the camera already is.
     *
     * Called whenever this app takes a parameter over or the camera is rebuilt
     * under it. Without it, the first drag after opening a lens jumps the
     * picture to wherever the knob happened to be left.
     */
    private fun seedZonePositions() {
        val engine = pipeline.engine
        engine.isoRange()?.let {
            isoPosition = Mechanism.positionOfValue(
                (engine.lastIso ?: iso).toDouble(), it.lower.toDouble(), it.upper.toDouble()
            )
        }
        shutterBounds()?.let { (low, high) ->
            shutterPosition = Mechanism.positionOfValue(
                (engine.lastExposureNs ?: shutterNs).toDouble(), low.toDouble(), high.toDouble()
            )
        }
    }

    /**
     * **The clean feed.** Everything off the glass but the picture.
     *
     * *"I'm using screen copy to broadcast my camera, not our NDI, so I need
     * the pure full screen mode without anything on the screen, not even the
     * VU meter. Nothing, not even parts of the interface of the phone."*
     *
     * Every pixel this app draws is on the wire when the wire is the phone's
     * own screen, so the question is not which controls to shrink but which to
     * take away — and the answer is all of them, including Android's own
     * status and navigation bars. The picture keeps its shape: it is centred
     * on black at the largest size that does not stretch it, because a
     * stretched picture is the one fault this app has shipped most often and a
     * receiver can crop black but cannot undo a squeeze.
     *
     * The camera is untouched. A take goes on being written and NDI goes on
     * being sent; this is a mode of the screen and of nothing else.
     */
    private fun setFullScreen(on: Boolean) {
        fullScreen = on
        val hidden = if (on) View.GONE else View.VISIBLE
        railLeft.visibility = hidden
        railRight.visibility = hidden
        status.visibility = hidden
        telemetry.visibility = if (!on && settings.showOutputs) View.VISIBLE else View.GONE
        placeStatus()
        vu.visibility = hidden
        timecode.visibility = hidden
        // The zones and the focus box come back to whichever of them was up.
        zones.visibility = if (!on && zonesOn) View.VISIBLE else View.GONE
        refreshMarks()
        iris.visibility = if (!on && zonesOn) View.VISIBLE else View.GONE
        fullScreenCatcher.visibility = if (on) View.VISIBLE else View.GONE
        leaveFullScreen.isEnabled = on

        // No toast: he knows the double tap, and a toast is on the wire.
        hideSystemBars(on)
        Trace.control("clean feed", if (on) "on" else "off", if (on) "screen cleared" else "back")
        refreshKeys()
        // The picture has the whole window to itself now, or has given it back.
        preview.post { holdBufferSize(); applyPreviewTransform() }
    }

    /**
     * Android's own status and navigation bars, off and on.
     *
     * Re-applied rather than set once: the bars come back of their own accord
     * on a rotation and after the app has been away, and a clean feed with a
     * clock and three buttons across it is not a clean feed.
     *
     * Transient-by-swipe rather than immovable, so a phone left in this mode
     * is never trapped in it.
     */
    private fun hideSystemBars(hide: Boolean) {
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        if (hide) {
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.root).setPadding(0, 0, 0, 0)
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The back key leaves the clean feed before it leaves the app. */
    private val leaveFullScreen = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullScreen(false)
    }

    /** What this lens is called in the preferences: its id, and its sub-lens. */
    private fun lensKey(): String {
        val lens = lenses.getOrNull(activeLens) ?: return "0"
        return lens.id + (lens.physicalId?.let { ":$it" } ?: "")
    }


    // --- the LUT switcher, and the other right-rail keys (v85) ----------------

    /**
     * LUT: off, then each LUT loaded in settings, then off again.
     *
     * The eleven slots moved to settings, where LUTs are added and removed;
     * this key only switches between them. A long press opens settings.
     */
    private fun nextLut() {
        val loaded = (1..LutSlots.COUNT).filter { slots.slot(it).loaded }
        // An empty library is not a dead key: it is the way in to filling it.
        if (loaded.isEmpty()) { say("No LUTs yet — add one in settings"); openSettings(); return }
        activeSlot = Mechanism.nextLut(activeSlot, loaded)
        applyLook()
        refreshKeys()
        say(if (activeSlot == 0) "LUT off" else "LUT: " + (slots.slot(activeSlot).label ?: "$activeSlot"))
        Trace.control("LUT", activeSlot, if (activeSlot == 0) "off" else slots.slot(activeSlot).label)
    }

    /** The last take: this session's, or the one remembered from before. */
    private fun lastTakeUri(): Uri? =
        pipeline.lastTakeUri ?: settings.lastTake?.let { Uri.parse(it) }

    /** PLAY: the last take in the phone's own player. */
    private fun playLastTake() {
        if (pipeline.isRecording) { say("Recording — stop the take first"); return }
        val uri = lastTakeUri() ?: run { say("No take yet"); return }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { startActivity(view); true }.getOrDefault(false)) {
            Trace.control("play", uri.toString(), "opened")
        } else {
            say("No player on this phone opens the take")
        }
    }

    /**
     * The orientation key: LANDSCAPE or PORTRAIT.
     *
     * *"A button to change the orientation of the screen and its labels ...
     * when I rotate the phone I need to read all the labels clearly."* The
     * whole interface goes with it, so every word stays upright. v88: *"it
     * just says LANDSCAPE or PORTRAIT and toggles between the two"* — no AUTO.
     */
    private fun nextShoot() {
        settings.shootMode = Mechanism.toggleShoot(settings.shootMode, screenIsLandscape())
        applyShootMode()
        refreshKeys()
        say(if (settings.shootMode == 1) "Landscape" else "Portrait")
    }

    private fun screenIsLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun applyShootMode() {
        // An older version's "follow the phone" becomes the way the screen is now.
        settings.shootMode = Mechanism.shootResolved(settings.shootMode, screenIsLandscape())
        requestedOrientation = if (settings.shootMode == 1)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    /**
     * What the curve key calls the camera's own picture, truthfully.
     *
     * "Rec.709" was a lie in a ten bit session: with no log curve the picture
     * is the phone's own HLG, the broadcast HDR curve with the soft highlight
     * shoulder. HLG when ten bit, STD (the phone's standard SDR curve) when not.
     */
    private fun curveLabel(curve: LogCurves.Curve): String =
        if (curve != LogCurves.Curve.REC709) curve.displayName
        else if (pipeline.isRunning && pipeline.isTenBit) "HLG" else "STD"

    /**
     * The storage key: free space on the drive the takes go to, and how long
     * can still be recorded there at the current bit rate.
     */
    private fun refreshStorage() {
        if (!::storageKey.isInitialized) return
        val free = Recordings.freeBytes(this, settings.recordFolder)
        if (free == null) {
            storageKey.label = "NO DRIVE"
            storageKey.sub = null
            storageKey.state = RailButton.State.ARMED
            return
        }
        val left = Mechanism.secondsLeft(free, pipeline.videoBitRate.toLong(), 192_000L)
        storageKey.label = Mechanism.formatFree(free)
        storageKey.sub = Mechanism.formatTimeLeft(left)
        storageKey.state = if (left < 10 * 60) RailButton.State.ARMED else RailButton.State.OFF
    }

    /** Record-run timecode, ticking ten times a second while a take runs. */
    private val timecodeTick = object : Runnable {
        override fun run() {
            if (rolling) {
                setTimecode("REC", true, android.os.SystemClock.elapsedRealtime() - recordingSince)
                ui.postDelayed(this, 100)
            }
        }
    }

    /**
     * Bottom middle: the status word, then the timecode (v88).
     *
     * *"The timecode is always white. Next to the timecode we have a status,
     * REC, which becomes red when it is active, or PLAY."* The word carries
     * the state; the numbers never change colour.
     */
    private fun setTimecode(word: String, active: Boolean, elapsedMs: Long) {
        // Called from onCreate too, before the pipeline exists (v88 crashed on
        // launch there): standing by, zero is zero at any rate.
        val fps = if (::pipeline.isInitialized) pipeline.fps else 25
        val tc = Mechanism.timecode(elapsedMs, fps)
        val text = android.text.SpannableString("$word  $tc")
        val wordColour = if (active) android.graphics.Color.rgb(255, 59, 48) else android.graphics.Color.WHITE
        text.setSpan(
            android.text.style.ForegroundColorSpan(wordColour), 0, word.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        timecode.text = text
    }

    private fun showTimecode(recording: Boolean) {
        if (recording) { ui.removeCallbacks(timecodeTick); ui.post(timecodeTick) }
        else { ui.removeCallbacks(timecodeTick); setTimecode("REC", false, 0) }
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
            uploaded = cube,
            falseColour = falseColour,
            zebra = zebra,
            zebraLevel = settings.zebraLevel
        )
        if (!ok) {
            say("This phone will not run the preview shader")
            Trace.refused("preview shader", "rejected by the graphics layer")
            peaking = false
            falseColour = false
            zebra = false
            activeSlot = 0
        }
    }

    // --- what the keys say ----------------------------------------------------

    private fun refreshKeys() {
        lenses.getOrNull(activeLens)?.equivalentMm?.takeIf { it > 0 }?.let { lensChooser.sub = "${it}mm" }
        lensChooser.state = if (lensesOpen) RailButton.State.ARMED else RailButton.State.ON
        lensKeys.forEach { it.visibility = if (lensesOpen) View.VISIBLE else View.GONE }
        // v130: while the drawer is open it is the whole rail; every other key steps aside
        for (i in 0 until railLeft.childCount) {
            val k = railLeft.getChildAt(i)
            if (k !== lensChooser && k !in lensKeys) k.visibility = if (lensesOpen) View.GONE else View.VISIBLE
        }
        // v107: each mark key wears its shape's colour of the moment (white, orange searching, green locked,
        // red failed), grey when the shape is off; a bar under it says which one is armed.
        markKeys.forEachIndexed { i, key ->
            val on = settings.marksShown and (1 shl i) != 0
            key.state = if (on) RailButton.State.SHOWN else RailButton.State.OFF
            key.tint = if (!on) null else when (i) { 0 -> focusSquare.colour(); 1 -> exposureCircle.colour(); else -> wbBox.colour() }
            key.marked = on && settings.pinchTarget == i
            key.invalidate()
        }
        lensKeys.forEachIndexed { i, key ->
            val lens = lenses.getOrNull(i)
            key.state = when {
                lens == null -> RailButton.State.DEAD
                i == activeLens && pipeline.isRunning -> RailButton.State.ON
                else -> RailButton.State.OFF
            }
        }

        focusKey.label = when {
            tracking -> "TRK"
            focus.mode == FocusDirector.Mode.AUTO -> "AF"
            else -> "MF"
        }
        focusKey.state =
            if (focus.mode == FocusDirector.Mode.AUTO) RailButton.State.ON
            else RailButton.State.OFF
        peakKey.state = if (peaking) RailButton.State.ON else RailButton.State.OFF
        snapKey.state =
            if (pipeline.isRunning) RailButton.State.OFF else RailButton.State.DEAD
        logKey.sub = curveLabel(LogCurves.Curve.entries[curveIndex]).take(5)
        logKey.state =
            if (LogCurves.Curve.entries[curveIndex] == LogCurves.Curve.REC709)
                RailButton.State.OFF
            else RailButton.State.ON
        val mode = Mechanism.cameraMode(switches())
        manualKey.state = if (mode == "AUTO") RailButton.State.OFF else RailButton.State.ON
        manualKey.sub = mode
        ctrlKey.state = if (zonesOn) RailButton.State.ON else RailButton.State.OFF
        screenKey.state = if (fullScreen || LinkServer.clean) RailButton.State.ON else RailButton.State.OFF

        // The LUT switcher says which LUT is on, or OFF.
        lutKey.sub = if (activeSlot > 0) slots.slot(activeSlot).label?.take(5) ?: "$activeSlot" else "OFF"
        lutKey.state = if (activeSlot > 0) RailButton.State.ON else RailButton.State.OFF
        if (activeSlot == 0 && slots.loadedCount() == 0) lutKey.sub = "ADD"
        falseKey.state = if (falseColour) RailButton.State.ON else RailButton.State.OFF
        zebraKey.state = if (zebra) RailButton.State.ON else RailButton.State.OFF
        zebraKey.sub = if (zebra) "${settings.zebraLevel}%" else null
        shootKey.label = if (settings.shootMode == 1) "LANDSCAPE" else "PORTRAIT"
        shootKey.state = RailButton.State.OFF
        playKey.state = if (lastTakeUri() != null) RailButton.State.OFF else RailButton.State.DEAD
        gearKey.state = RailButton.State.OFF

        recKey.dead = !pipeline.isRunning
        recKey.recording = rolling
        refreshTelemetry()
    }

    // --- the status line ------------------------------------------------------

    private var lastSaid = ""

    private fun say(text: String) {
        lastSaid = text
        status.text = markStatus() + text
        Trace.state(text)
    }

    // v106: the armed shape's name on the status line, and where it locked, in numbers (Marko, 2.10.2026:
    // "at the top status line, you need to write exposure circle, white balance triangle, focusing square, and
    // then when it reads the value and locks ... write exact number where is it locked").
    private var focusLockedAt: String? = null
    private var exposureLockedAt: String? = null
    private var wbLockedAt: String? = null

    /**
     * v111: the status line sits right under the outputs line when that is shown, and at the very top when it is
     * hidden — no gap (Marko, 2.10.2026: "when my outputs are gone, the status line has a gap").
     */
    private fun placeStatus() {
        val lp = status.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val dp = resources.displayMetrics.density
        lp.topMargin = ((if (telemetry.visibility == View.VISIBLE) 22 else 2) * dp).toInt()
        status.layoutParams = lp
    }

    private fun markStatus(): String = when (settings.pinchTarget) {
        1 -> "EXPOSURE CIRCLE" + (exposureLockedAt?.let { " $it" } ?: "") + "  ·  "
        2 -> "WHITE BALANCE TRIANGLE" + (wbLockedAt?.let { " $it" } ?: "") + "  ·  "
        else -> "FOCUSING SQUARE" + (focusLockedAt?.let { " $it" } ?: "") + "  ·  "
    }

    private fun focusLockText(): String? {
        val d = pipeline.engine.lastFocusDistance ?: return null
        return if (d <= 0.001f) "locked at ∞" else String.format(java.util.Locale.ROOT, "locked at %.2f m (%.2f dpt)", 1f / d, d)
    }

    private fun exposureLockText(): String {
        val e = pipeline.engine
        val ev = e.aeCompensation * e.aeCompensationStep()
        return "locked ISO ${e.lastIso ?: "?"} · " + (e.lastExposureNs?.let { Mechanism.formatShutter(it) } ?: "?") +
            String.format(java.util.Locale.ROOT, " · EV %+.1f", ev)
    }

    private fun wbLockText(): String {
        val g = pipeline.engine.currentGains()
        return "locked ${WhiteBalance.format(wbKelvin)}" +
            (g?.let { String.format(java.util.Locale.ROOT, " · gains R %.2f B %.2f", it[0], it[3]) } ?: "")
    }

    private var lastMbps = 0.0
    private var lastWatching = 0

    private fun showRate(fps: Double, mbps: Double, connections: Int) {
        lastMbps = mbps
        lastWatching = connections
        val depth = if (pipeline.isTenBit) "10-bit" else "8-bit"
        val grey = greyReadout?.let { " · $it" } ?: ""
        status.text = if (fps > 0.5) String.format(java.util.Locale.ROOT, "%s%s · %.1f fps%s · %s", markStatus(), depth, fps, grey, lastSaid)
            else "${markStatus()}$depth$grey · $lastSaid"
        refreshTelemetry()
    }

    /**
     * The telemetry line (v91): FILE, USB, NDI, YT, in place. Dim is not
     * armed, white is armed and waiting for the record key, a red dot and
     * its numbers is sending.
     */
    private fun refreshTelemetry() {
        // LESSONS 7: never read the pipeline before onCreate has made it.
        if (!::telemetry.isInitialized || !::pipeline.isInitialized) return
        val parts = Mechanism.telemetry(
            settings.armFile, pipeline.isRecording, pipeline.videoBitRate / 1_000_000.0,
            settings.armNdi, settings.ndiKind, pipeline.mode != CameraPipeline.Mode.OFF,
            lastMbps, lastWatching
        )
        val text = android.text.SpannableStringBuilder()
        parts.forEachIndexed { i, p ->
            if (i > 0) text.append("   ")
            val start = text.length
            if (p.state == Mechanism.Output.LIVE) {
                text.append("● ")
                text.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.rgb(255, 59, 48)),
                    start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val wordStart = text.length
            text.append(p.name)
            if (p.detail.isNotEmpty()) text.append(" ").append(p.detail)
            val colour = when (p.state) {
                Mechanism.Output.OFF -> android.graphics.Color.argb(120, 200, 204, 208)
                Mechanism.Output.READY -> android.graphics.Color.WHITE
                Mechanism.Output.LIVE -> android.graphics.Color.WHITE
            }
            text.setSpan(android.text.style.ForegroundColorSpan(colour), wordStart, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        telemetry.text = text
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

// The spot (v100, v101). Out of 255 on the monitor texture: below DARK is the black around a lit thing, above
// CLIPPED a channel has hit the top and no longer says how bright it is. MIN_PIXELS: enough lit legend to
// average (read at half the view's size since v102). SETTLE: gains sent now reach the texture some frames later (8 at 30 fps ~ 270 ms).
private const val SPOT_DARK = 25
private const val SPOT_CLIPPED = 245
private const val SPOT_MIN_PIXELS = 12
private const val SPOT_SETTLE_MS = 450L
/** v129: the auto half's spring: a frame, and how quick (critically damped; settles in about a second). */
private const val SPRING_FRAME_S = 0.033
private const val SPRING_OMEGA = 5.0
/** v125: the exposure circle is there inside a fifth of a stop; three rounds at most; a read after each ramp. */
private const val CIRCLE_TOLERANCE_STOPS = 0.2
private const val CIRCLE_ROUNDS = 3
private const val CIRCLE_SETTLE_MS = 300L
private const val SPOT_TOLERANCE = 0.03
