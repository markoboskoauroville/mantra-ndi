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
    private lateinit var zones: ControlZones
    private lateinit var fullScreenCatcher: View
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

    /** Manual exposure, and whether the zones on the picture are listening. */
    /**
     * A / M per parameter (v87). ISO and shutter each have their own switch;
     * focus is the focus director's mode and white balance is [wbAuto]. The
     * M key's AUTO / HM / FM is read off these four, never stored beside them.
     */
    private var isoAuto = true
    private var shutterAuto = true

    /** The last half-manual set, for the M key to put back. */
    private var lastHalf: BooleanArray? = null

    /** The brightness the half-manual loop holds, and whether the camera does it itself. */
    private var loopTarget = 0.0
    private var loopNative = false
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
    private lateinit var lightKey: RailButton
    private lateinit var focusKey: RailButton
    private lateinit var peakKey: RailButton
    private lateinit var snapKey: RailButton
    private lateinit var logKey: RailButton
    private lateinit var manualKey: RailButton
    private lateinit var ctrlKey: RailButton
    private lateinit var ndiKey: RailButton
    private lateinit var screenKey: RailButton

    private val ui = Handler(Looper.getMainLooper())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trace.open(this)
        Trace.markStart()
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        focusSquare = findViewById(R.id.focusSquare)
        zones = findViewById(R.id.zones)
        fullScreenCatcher = findViewById(R.id.fullScreenCatcher)
        status = findViewById(R.id.status)
        geometry = findViewById(R.id.geometry)
        vu = findViewById(R.id.vu)
        iris = findViewById(R.id.iris)
        stage = findViewById(R.id.stage)
        railLeft = findViewById(R.id.railLeft)
        railRight = findViewById(R.id.railRight)
        timecode = findViewById(R.id.timecode)

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
        }
        focusSquare.onResized = { size -> settings.focusBoxSize = size }
        focusSquare.onTapped = { focus.focusHereAndHold() }

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
                    if (x in 0f..1f && y in 0f..1f) focusAt(x, y)
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
        zones.onSingleTap = { x, y -> focusAt(x, y) }

        // The trace is the only instrument that reaches a phone with no cable,
        // so it is always one long press away rather than behind a menu.
        status.setOnLongClickListener { exportTrace(); true }

        pipeline.engine.onCurveRefused = { refused, back ->
            ui.post {
                curveIndex = back.ordinal
                refusedCurves.add(lensKey() + "/" + refused.name)
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
                    if (restoreMode != CameraPipeline.Mode.OFF) {
                        val want = restoreMode
                        restoreMode = CameraPipeline.Mode.OFF
                        pipeline.setMode(want)
                    }
                    // A new session starts from the template, which is all
                    // automatic: his A / M switches are put back on it.
                    if (!isoAuto || !shutterAuto) applyExposure()
                    refreshZones()
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
        // As many lens keys as this phone has lenses, and no more. Four were
        // always drawn with the missing ones dark; a key for a lens that does
        // not exist is not a control.
        for (i in 1..lenses.size) {
            val key = newKey("L$i") { chooseLens(i - 1) }
            lensKeys.add(key)
            railLeft.addView(key)
        }
        focusKey = newKey("AF") { toggleAutoFocus() }.also { railLeft.addView(it) }
        logKey = newKey("LOG") { nextCurve() }.also { railLeft.addView(it) }
        manualKey = newKey("M") { nextCameraMode() }.also { railLeft.addView(it) }
        ctrlKey = newKey("CTRL") { toggleZones() }.also { railLeft.addView(it) }
        // One key for NDI: off, HX, full, off. An NDI source is one stream.
        ndiKey = newKey("NDI") { nextNdiMode() }.also { railLeft.addView(it) }
        // The clean feed, for a phone that is being broadcast by its screen.
        screenKey = newKey("FULL") { setFullScreen(!fullScreen) }
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
        snapKey = newKey("SNAP") { takeSnap() }.also { railRight.addView(it) }
        lightKey = newKey("LGHT") { toggleLight() }.also { railRight.addView(it) }
        shootKey = newKey("SHOOT") { nextShoot() }.also { railRight.addView(it) }
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
        manualQuarterTurns = settings.quarterTurnsFor(lensKey())
        focusComplaintFor = ""
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
        displays?.registerDisplayListener(displayListener, ui)
        if (fullScreen) hideSystemBars(true)
        // The camera session is lost while backgrounded even with a foreground
        // service, so it is rebuilt rather than tested for.
        if (preview.isAvailable && !pipeline.isRunning) openCamera()
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

    private var storageTicks = 0

    private val rateTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) pipeline.sampleRate()
            if (++storageTicks % 5 == 0) refreshStorage()
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
        val where = pipeline.startRecording(this, meter, settings.recordFolder)
            ?: run { refreshKeys(); return }
        pipeline.lastTakeUri?.let { settings.lastTake = it.toString() }
        recordingSince = android.os.SystemClock.elapsedRealtime()
        recKey.elapsedSeconds = 0
        recKey.recording = true
        showTimecode(true)
        say("Recording → $where")
        refreshKeys()
    }

    private fun stopRecording() {
        val frames = pipeline.recordedFrames
        val drops = pipeline.recordedDrops
        val where = pipeline.stopRecording()
        recKey.recording = false
        recKey.elapsedSeconds = 0
        showTimecode(false)
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
        val producerMirrored = Mechanism.producerMirrored(producer)
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
        geometry.text = line
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
        val displayDegrees = when (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        ) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val front = engine.isFrontFacing
        val degrees = Mechanism.sensorToViewDegrees(
            engine.sensorOrientation, displayDegrees, front, manualQuarterTurns
        )
        val b = focusSquare.normalisedBounds()
        val onStream = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], degrees, front)
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

    /** Curves a lens froze on, "lens/CURVE": stepped over from then on. */
    private val refusedCurves = mutableSetOf<String>()

    private fun nextCurve() {
        val count = LogCurves.Curve.entries.size
        for (step in 1..count) {
            curveIndex = (curveIndex + 1) % count
            val name = LogCurves.Curve.entries[curveIndex].name
            if (lensKey() + "/" + name !in refusedCurves) break
        }
        val curve = LogCurves.Curve.entries[curveIndex]
        val ok = pipeline.setLogCurve(curve)
        say(if (ok) (if (curve == LogCurves.Curve.REC709) curveLabel(curve) + ", the camera's own curve"
                     else "${curve.vendor} ${curve.displayName}") else "${curveLabel(curve)} refused")
        refreshKeys()
    }

    /** The four switches: ISO, shutter, focus, white balance; true = A. */
    private fun switches(): BooleanArray = booleanArrayOf(
        isoAuto, shutterAuto, focus.mode == FocusDirector.Mode.AUTO, wbAuto
    )

    /**
     * M: AUTO → HM → FM → AUTO.
     *
     * *"Now we have modes: full manual, half manual."* HM puts back the last
     * half-manual set he made with the switches (or manual exposure with
     * automatic focus and white balance, the first time).
     */
    private fun nextCameraMode() {
        val now = switches()
        if (Mechanism.cameraMode(now) == "HM") lastHalf = now.copyOf()
        val next = Mechanism.nextCameraMode(now, lastHalf)
        for (i in 0..3) if (next[i] != now[i]) setParamAuto(i, next[i], quiet = true)
        say("Mode " + Mechanism.cameraMode(switches()))
        refreshZones()
        refreshKeys()
    }

    /** The A / M switch at the head of a fader. */
    private fun toggleParam(index: Int) {
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
                (focus.mode == FocusDirector.Mode.AUTO) != auto -> {
                    toggleAutoFocus()
                    if (!quiet) say("Focus " + if (auto) "automatic" else "manual")
                }
            }
            3 -> if (auto) {
                wbAuto = true
                engine.setAutoWhiteBalance()
                if (!quiet) say("White balance automatic")
            } else if (wbAuto) {
                probeWhiteBalance()
            }
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
                if (mode == Mechanism.Exposure.ISO_PRIORITY) {
                    shutterBounds()?.let { (low, high) ->
                        shutterNs = Mechanism.priorityStep(
                            shutterNs.toDouble(), measured, loopTarget, low.toDouble(), high.toDouble()
                        ).toLong()
                    }
                } else {
                    engine.isoRange()?.let {
                        iso = Mechanism.priorityStep(
                            iso.toDouble(), measured, loopTarget, it.lower.toDouble(), it.upper.toDouble()
                        ).toInt()
                    }
                }
                engine.setManualExposure(iso, shutterNs)
                refreshZones()
            }
            ui.postDelayed(this, 250)
        }
    }

    /**
     * The picture's brightness in the middle, 0..1, from a 24x14 read of the
     * preview: the scaling is done by the GPU, a few dozen pixels are averaged
     * here.
     */
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
        focusSquare.visibility = if (zonesOn) View.GONE else View.VISIBLE
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
    private fun probeWhiteBalance() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        say("Reading the camera's white balance…")
        val ticket = ++probeTicket
        // A deadline, because an answer that never comes (the lens changed,
        // the session died) would leave "Reading…" on the screen for ever.
        ui.postDelayed({
            if (probeTicket == ticket) {
                probeTicket++
                say("The camera gave no white balance reading — try again")
                Trace.refused("white balance probe", "no answer within 3 s")
            }
        }, 3000)
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

    /** Which probe is waiting; anything else arriving is stale. */
    private var probeTicket = 0

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
            ControlZones.Zone("ISO", (if (isoAuto) liveIso else iso).toString(), true, isoPositionShown, isoAuto),
            ControlZones.Zone(
                "SHTR", Mechanism.formatShutter(if (shutterAuto) liveShutter else shutterNs), true,
                shutterPositionShown, shutterAuto
            ),
            ControlZones.Zone("FOCUS", focusText, canFocus, focusPosition, autoFocus),
            // Tungsten on the left, daylight on the right. Dragging it takes
            // it off auto; two taps hand it back.
            ControlZones.Zone(
                "WB",
                if (wbAuto) "AUTO" else WhiteBalance.format(wbKelvin),
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
        geometry.visibility = hidden
        vu.visibility = hidden
        timecode.visibility = hidden
        // The zones and the focus box come back to whichever of them was up.
        zones.visibility = if (!on && zonesOn) View.VISIBLE else View.GONE
        focusSquare.visibility = if (!on && !zonesOn) View.VISIBLE else View.GONE
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

    /**
     * Off, HX, full, off.
     *
     * A mode this phone cannot offer is stepped over rather than being a tap
     * that does nothing: on a session that gave up the full-NDI reader to get
     * ten bit, the key goes straight from HX back to off.
     */
    private fun nextNdiMode() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        var next = pipeline.mode
        for (step in 1..3) {
            next = when (next) {
                CameraPipeline.Mode.OFF -> CameraPipeline.Mode.HX
                CameraPipeline.Mode.HX -> CameraPipeline.Mode.FULL
                CameraPipeline.Mode.FULL -> CameraPipeline.Mode.OFF
            }
            val possible = when (next) {
                CameraPipeline.Mode.OFF -> true
                CameraPipeline.Mode.HX -> pipeline.hxAvailable
                CameraPipeline.Mode.FULL -> pipeline.fullAvailable
            }
            if (possible) break
        }
        pipeline.setMode(next)
        Trace.control("stream", next.name, next.name)
        refreshKeys()
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
     * SHOOT: follow the phone, landscape, or portrait.
     *
     * *"A button to change the orientation of the screen and its labels ...
     * when I rotate the phone I need to read all the labels clearly."* With
     * the phone's auto-rotate off, this is how the camera is turned: the whole
     * interface goes with it, so every word stays upright.
     */
    private fun nextShoot() {
        settings.shootMode = Mechanism.nextShoot(settings.shootMode)
        applyShootMode()
        refreshKeys()
        say(when (settings.shootMode) {
            1 -> "Shooting landscape"
            2 -> "Shooting portrait"
            else -> "Following the phone's rotation"
        })
    }

    private fun applyShootMode() {
        requestedOrientation = when (settings.shootMode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
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
            if (pipeline.isRecording) {
                timecode.text = Mechanism.timecode(
                    android.os.SystemClock.elapsedRealtime() - recordingSince, pipeline.fps
                )
                ui.postDelayed(this, 100)
            }
        }
    }

    private fun showTimecode(recording: Boolean) {
        timecode.setTextColor(if (recording) android.graphics.Color.rgb(255, 59, 48)
            else android.graphics.Color.argb(200, 220, 224, 228))
        if (recording) { ui.removeCallbacks(timecodeTick); ui.post(timecodeTick) }
        else ui.removeCallbacks(timecodeTick)
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
        ndiKey.sub = when (pipeline.mode) {
            CameraPipeline.Mode.HX -> "HX"
            CameraPipeline.Mode.FULL -> "FULL"
            CameraPipeline.Mode.OFF -> "OFF"
        }
        ndiKey.state = when {
            pipeline.mode != CameraPipeline.Mode.OFF -> RailButton.State.ON
            pipeline.isRunning && !pipeline.hxAvailable && !pipeline.fullAvailable ->
                RailButton.State.DEAD
            else -> RailButton.State.OFF
        }
        screenKey.state = if (fullScreen) RailButton.State.ON else RailButton.State.OFF

        // The LUT switcher says which LUT is on, or OFF.
        lutKey.sub = if (activeSlot > 0) slots.slot(activeSlot).label?.take(5) ?: "$activeSlot" else "OFF"
        lutKey.state = if (activeSlot > 0) RailButton.State.ON else RailButton.State.OFF
        if (activeSlot == 0 && slots.loadedCount() == 0) lutKey.sub = "ADD"
        falseKey.state = if (falseColour) RailButton.State.ON else RailButton.State.OFF
        zebraKey.state = if (zebra) RailButton.State.ON else RailButton.State.OFF
        zebraKey.sub = if (zebra) "${settings.zebraLevel}%" else null
        shootKey.sub = when (settings.shootMode) { 1 -> "LAND"; 2 -> "PORT"; else -> "AUTO" }
        shootKey.state = if (settings.shootMode == 0) RailButton.State.OFF else RailButton.State.ON
        playKey.state = if (lastTakeUri() != null) RailButton.State.OFF else RailButton.State.DEAD
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
