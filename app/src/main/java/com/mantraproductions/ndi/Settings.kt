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
        get() = prefs.getInt(BITRATE, 12)
        set(value) = prefs.edit().putInt(BITRATE, value.coerceIn(2, 50)).apply()

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

    /** Ten bit is asked for unless somebody has a reason not to. */
    var wantTenBit: Boolean
        get() = prefs.getBoolean(TEN_BIT, true)
        set(value) = prefs.edit().putBoolean(TEN_BIT, value).apply()

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
    }
}
