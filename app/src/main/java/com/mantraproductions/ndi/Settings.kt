package com.mantraproductions.ndi

import android.content.Context
import android.os.Build

/**
 * The few things worth keeping between runs, and nowhere else.
 *
 * Deliberately small. Everything an operator touches during a take is a key on
 * a rail, where it can be reached without looking; what lives here is what is
 * decided once — the name this camera answers to on the network, how long a
 * focus pull should take, how hard the wire is allowed to be pushed.
 *
 * Nothing here is read on a camera callback. Each value is pulled when the
 * pipeline starts or when a key is pressed, so a preference lookup never sits
 * between a frame and the encoder.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("mantra-ndi", Context.MODE_PRIVATE)

    /**
     * What a receiver sees in its source list.
     *
     * The phone's model by default, because a room with three of these in it
     * is the normal case and "Pixel 7" three times is no use to anybody. It is
     * sanitised on the way out: NDI names travel through mDNS and a stray
     * character costs discovery rather than a warning.
     */
    var sourceName: String
        get() = prefs.getString(SOURCE, null)?.takeIf { it.isNotBlank() }
            ?: (Build.MODEL + " Camera")
        set(value) = prefs.edit().putString(SOURCE, Mechanism.sanitizeSourceName(value)).apply()

    /** How long the focus director holds before it will consider moving. */
    var focusHoldMs: Long
        get() = prefs.getLong(HOLD, 2000L)
        set(value) = prefs.edit().putLong(HOLD, value.coerceIn(0L, 10_000L)).apply()

    /** v127: a lens's measured white balance curve ([WhiteBalance.encodeCurve]), by camera and lens. */
    fun wbCurve(lens: String): String? = prefs.getString("wbCurve.$lens", null)
    fun setWbCurve(lens: String, curve: String) = prefs.edit().putString("wbCurve.$lens", curve).apply()

    /** How long a rack takes. Zero is a snap, for anybody who wants a phone. */
    var focusRackMs: Long
        get() = prefs.getLong(RACK, 2000L)
        set(value) = prefs.edit().putLong(RACK, value.coerceIn(0L, 10_000L)).apply()

    /** 0..100, how faint an edge still counts as in focus. */
    var peakSensitivity: Int
        get() = prefs.getInt(PEAK, 50)
        set(value) = prefs.edit().putInt(PEAK, value.coerceIn(0, 100)).apply()

    var peakColour: PreviewEffects.PeakColour
        get() = runCatching {
            PreviewEffects.PeakColour.valueOf(prefs.getString(PEAK_COLOUR, null) ?: "")
        }.getOrDefault(PreviewEffects.PeakColour.RED)
        set(value) = prefs.edit().putString(PEAK_COLOUR, value.name).apply()

    /**
     * What the HX encoder is allowed, in megabits.
     *
     * Against the wire rather than the picture: this is what a phone pushes
     * across a hall's Wi-Fi without the far end stuttering, and HEVC at 1080p30
     * has little left to gain above it. A dropped frame is worse than a soft one.
     */
    var bitRateMbps: Int
        get() = prefs.getInt(BITRATE, 24)
        set(value) = prefs.edit().putInt(BITRATE, value.coerceIn(2, 100)).apply()

    /**
     * Quarter turns added to the preview by hand, kept between runs.
     *
     * The automatic angle is the sensor's mounting against the display's
     * rotation, and it is right on nearly every device. On one where it is
     * not, `ROT` is how the operator corrects it — and having to press it
     * again after every launch makes a correct camera feel like a broken one.
     * So the correction is remembered. Nothing else about the geometry is a
     * preference; this is the one number that is a fact about the phone.
     */
    var quarterTurns: Int
        get() = prefs.getInt(TURNS, 0)
        set(value) = prefs.edit().putInt(TURNS, ((value % 4) + 4) % 4).apply()

    /**
     * The same correction, per lens.
     *
     * A phone's lenses are not all mounted the same way round — a front camera
     * is a separate sensor in a separate hole, and its own quarter turns are
     * its own. One global correction meant fixing the selfie lens broke the
     * three rear ones and the other way about. The old single number is the
     * default for every lens, so a phone that was already corrected stays
     * corrected.
     */
    fun quarterTurnsFor(lens: String): Int =
        prefs.getInt(TURNS + ":" + lens, quarterTurns)

    fun setQuarterTurnsFor(lens: String, value: Int) {
        prefs.edit().putInt(TURNS + ":" + lens, ((value % 4) + 4) % 4).apply()
    }

    /**
     * Frames a second, for the file and the wire alike.
     *
     * *"I want to decide how many frames a second I am writing my file."* It
     * was 30 in the code with nothing on any screen to say so, which on a
     * camera is not a default but a missing control: 24 is film, 25 belongs
     * beside a 50Hz mains and 30 beside a 60Hz one, and 50 and 60 are what
     * anybody shoots who intends to slow it down. The list offered is filtered
     * against what the lens publishes, like the resolutions.
     */
    var fps: Int
        get() = prefs.getInt(FPS, 30)
        set(value) = prefs.edit().putInt(FPS, value.coerceIn(1, 240)).apply()

    /**
     * The longest side the camera is asked for: 3840, 1920 or 1280.
     *
     * It was pinned to 1920 in the code with a paragraph about the wire, and
     * that reasoning is sound for NDI over a hall's Wi-Fi and wrong for a phone
     * recording to its own card. It is a decision, so it is a setting, and the
     * list offered is filtered against what the lens actually publishes rather
     * than hard-coded — a resolution a lens does not have is a black screen.
     */
    var captureWidth: Int
        get() = prefs.getInt(WIDTH, 1920)
        set(value) = prefs.edit().putInt(WIDTH, value).apply()

    /**
     * Whether the settings screen shows its help text.
     *
     * The paragraphs are worth having once and are in the way for ever after.
     * It is not a setting so much as a mode of the screen, and it is remembered
     * because nobody wants to press MINIMAL every time.
     */
    var verboseSettings: Boolean
        get() = prefs.getBoolean(VERBOSE, true)
        set(value) = prefs.edit().putBoolean(VERBOSE, value).apply()

    /**
     * The focus box's size, as it was last pinched: its side as a fraction of
     * the picture's short side (see Mechanism.focusBoxHalves).
     */
    var focusBoxSize: Float
        get() = prefs.getFloat(BOX, 0.18f)
        set(value) = prefs.edit().putFloat(BOX, value.coerceIn(Mechanism.BOX_MIN, 4f)).apply()

    /** Where the white balance rectangle was left (v101), as fractions of the picture. */
    var wbBoxX: Float
        get() = prefs.getFloat(WB_X, 0.78f)
        set(value) = prefs.edit().putFloat(WB_X, value.coerceIn(0f, 1f)).apply()
    var wbBoxY: Float
        get() = prefs.getFloat(WB_Y, 0.5f)
        set(value) = prefs.edit().putFloat(WB_Y, value.coerceIn(0f, 1f)).apply()

    /**
     * v115: the curves that crashed a lens's camera ("lens/CURVE"), kept across starts so they are never sent again.
     * MEASURED 2.10.2026 on the Nothing Phone 2a: S-Log3 → no frame for 1.2 s → "Camera error 4" (fatal device error).
     */
    var refusedCurves: Set<String>
        get() = prefs.getStringSet(REFUSED_CURVES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(REFUSED_CURVES, value).apply()

    /** v106: the outputs line (FILE USB NDI YT) on the camera screen, or hidden. */
    var showOutputs: Boolean
        get() = prefs.getBoolean(SHOW_OUTPUTS, true)
        set(value) = prefs.edit().putBoolean(SHOW_OUTPUTS, value).apply()

    /** The light (v104): the phone's lamp, kept on while the camera runs. Switched from the top of settings. */
    var torch: Boolean
        get() = prefs.getBoolean(TORCH, false)
        set(value) = prefs.edit().putBoolean(TORCH, value).apply()

    /** Where the exposure circle was left (v103), and the sizes of the circle and the triangle. */
    var circleX: Float
        get() = prefs.getFloat(CIRCLE_X, 0.3f)
        set(value) = prefs.edit().putFloat(CIRCLE_X, value.coerceIn(0f, 1f)).apply()
    var circleY: Float
        get() = prefs.getFloat(CIRCLE_Y, 0.5f)
        set(value) = prefs.edit().putFloat(CIRCLE_Y, value.coerceIn(0f, 1f)).apply()
    var circleSize: Float
        get() = prefs.getFloat(CIRCLE_SIZE, 0.16f)
        set(value) = prefs.edit().putFloat(CIRCLE_SIZE, value.coerceIn(0.06f, 0.5f)).apply()
    var wbSize: Float
        get() = prefs.getFloat(WB_SIZE, 0.16f)
        set(value) = prefs.edit().putFloat(WB_SIZE, value.coerceIn(0.06f, 0.5f)).apply()

    /**
     * Which of the three shapes are on the picture (bit 0 square, 1 circle, 2 triangle) and which one a pinch
     * resizes (0 square, 1 circle, 2 triangle). Marko, 2.10.2026: grey = not on the picture, white = on it,
     * orange = the one the pinch changes, only one at a time.
     */
    var marksShown: Int
        get() = prefs.getInt(MARKS, 0b111)
        set(value) = prefs.edit().putInt(MARKS, value and 0b111).apply()
    var pinchTarget: Int
        get() = prefs.getInt(PINCH, 0)
        set(value) = prefs.edit().putInt(PINCH, value.coerceIn(0, 2)).apply()

    /** SHOOT: 0 follows the phone's own rotation, 1 landscape, 2 portrait. */
    var shootMode: Int
        get() = prefs.getInt(SHOOT, 0)
        set(value) = prefs.edit().putInt(SHOOT, ((value % 3) + 3) % 3).apply()

    /**
     * Where takes and stills go: a folder the operator chose (any drive the
     * phone can see, a USB SSD included), or null for DCIM/Mantra Manual Camera.
     */
    var recordFolder: String?
        get() = prefs.getString(FOLDER, null)
        set(value) = prefs.edit().putString(FOLDER, value).apply()

    /** The last take, so PLAY can open it after a restart. */
    var lastTake: String?
        get() = prefs.getString(LAST_TAKE, null)
        set(value) = prefs.edit().putString(LAST_TAKE, value).apply()

    /** Zebra from this luma up, percent of full scale. */
    var zebraLevel: Int
        get() = prefs.getInt(ZEBRA, 95)
        set(value) = prefs.edit().putInt(ZEBRA, value.coerceIn(50, 100)).apply()

    /** Ten bit is asked for unless somebody has a reason not to. */
    var wantTenBit: Boolean
        get() = prefs.getBoolean(TEN_BIT, true)
        set(value) = prefs.edit().putBoolean(TEN_BIT, value).apply()

    // --- v91, the switchboard: which destinations the record key starts ----------

    /** FILE is armed unless he disarms it, so the record key still records after the upgrade. */
    var armFile: Boolean
        get() = prefs.getBoolean(ARM_FILE, true)
        set(value) = prefs.edit().putBoolean(ARM_FILE, value).apply()

    var armNdi: Boolean
        get() = prefs.getBoolean(ARM_NDI, false)
        set(value) = prefs.edit().putBoolean(ARM_NDI, value).apply()

    /** 1 = NDI HX, 2 = full NDI. */
    var ndiKind: Int
        get() = prefs.getInt(NDI_KIND, 1)
        set(value) = prefs.edit().putInt(NDI_KIND, if (value == 2) 2 else 1).apply()

    /** v135: the wire NDI goes out on (Wi-Fi or the USB tether), see [Routing]. */
    var ndiTransport: Routing.Transport
        get() = runCatching { Routing.Transport.valueOf(prefs.getString(NDI_TRANSPORT, null) ?: "") }
            .getOrDefault(Routing.Transport.WIFI)
        set(value) = prefs.edit().putString(NDI_TRANSPORT, value.name).apply()

    /** v135: the sound travels inside the NDI stream (on unless he turns it off). */
    var ndiAudio: Boolean
        get() = prefs.getBoolean(NDI_AUDIO, true)
        set(value) = prefs.edit().putBoolean(NDI_AUDIO, value).apply()

    /** v135: which microphone: [Routing.AUDIO_AUTO], [Routing.AUDIO_PHONE] or a device's [Routing.Input.key]. */
    var audioSource: String
        get() = prefs.getString(AUDIO_SOURCE, null)?.takeIf { it.isNotBlank() } ?: Routing.AUDIO_AUTO
        set(value) = prefs.edit().putString(AUDIO_SOURCE, value).apply()

    /** v135: two channels when the device has them (a USB-C interface, a two-transmitter wireless kit). */
    var audioStereo: Boolean
        get() = prefs.getBoolean(AUDIO_STEREO, false)
        set(value) = prefs.edit().putBoolean(AUDIO_STEREO, value).apply()

    /** v97: the picture goes through the GPU stage (true) or the direct path. */
    var gpuStage: Boolean
        get() = prefs.getBoolean(GPU_STAGE, true)
        set(value) = prefs.edit().putBoolean(GPU_STAGE, value).apply()

    /** v97: the stream's own bit rate when the GPU stage gives it an encoder. */
    var streamMbps: Int
        get() = prefs.getInt(STREAM_MBPS, 16)
        set(value) = prefs.edit().putInt(STREAM_MBPS, value.coerceIn(2, 50)).apply()

    // --- tracking focus (v99): every parameter, so he can experiment ---------------

    /** The search zone, as a multiple of the pattern (the pinched box), x10: 15..40. */
    var trackSearch: Int
        get() = prefs.getInt("trackSearch", 25)
        set(value) = prefs.edit().putInt("trackSearch", value.coerceIn(15, 40)).apply()

    /** Frames between searches: 1 is every frame, more spares the phone's heat. */
    var trackEvery: Int
        get() = prefs.getInt("trackEvery", 2)
        set(value) = prefs.edit().putInt("trackEvery", value.coerceIn(1, 10)).apply()

    /** Match confidence below which the mark says LOST, percent. */
    var trackConfidence: Int
        get() = prefs.getInt("trackConfidence", 60)
        set(value) = prefs.edit().putInt("trackConfidence", value.coerceIn(20, 95)).apply()

    /** How far the mark moves before focus is asked again, percent of the frame. */
    var trackTolerance: Int
        get() = prefs.getInt("trackTolerance", 4)
        set(value) = prefs.edit().putInt("trackTolerance", value.coerceIn(1, 25)).apply()

    /** How fast the mark follows the match, percent of the way per search. */
    var trackFollow: Int
        get() = prefs.getInt("trackFollow", 60)
        set(value) = prefs.edit().putInt("trackFollow", value.coerceIn(10, 100)).apply()

    private companion object {
        const val SOURCE = "sourceName"
        const val HOLD = "focusHoldMs"
        const val RACK = "focusRackMs"
        const val PEAK = "peakSensitivity"
        const val PEAK_COLOUR = "peakColour"
        const val BITRATE = "bitRateMbps"
        const val TEN_BIT = "wantTenBit"
        const val TURNS = "quarterTurns"
        const val WIDTH = "captureWidth"
        const val VERBOSE = "verboseSettings"
        const val FPS = "framesPerSecond"
        const val BOX = "focusBoxSize"
        const val WB_X = "wbBoxX"
        const val TORCH = "torch"
        const val SHOW_OUTPUTS = "showOutputs"
        const val REFUSED_CURVES = "refusedCurves"
        const val CIRCLE_X = "circleX"
        const val CIRCLE_Y = "circleY"
        const val CIRCLE_SIZE = "circleSize"
        const val WB_SIZE = "wbSize"
        const val MARKS = "marksShown"
        const val PINCH = "pinchTarget"
        const val WB_Y = "wbBoxY"
        const val SHOOT = "shootMode"
        const val FOLDER = "recordFolder"
        const val LAST_TAKE = "lastTake"
        const val ZEBRA = "zebraLevel"
        const val ARM_FILE = "armFile"
        const val ARM_NDI = "armNdi"
        const val NDI_KIND = "ndiKind"
        const val GPU_STAGE = "gpuStage"
        const val STREAM_MBPS = "streamMbps"
        const val NDI_TRANSPORT = "ndiTransport"
        const val NDI_AUDIO = "ndiAudio"
        const val AUDIO_SOURCE = "audioSource"
        const val AUDIO_STEREO = "audioStereo"
    }
}
