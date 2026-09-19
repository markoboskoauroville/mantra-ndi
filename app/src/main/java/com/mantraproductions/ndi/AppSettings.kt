package com.mantraproductions.ndi

import android.content.Context

/** Everything the settings screen owns, and the fader the operator last used. */
/**
 * What the app is doing, which decides what the settings below it mean.
 *
 * Three jobs that share one screen: shooting here, driving a camera somewhere
 * else, and watching without shooting at all. Half the settings are
 * meaningless in two of the three, so the mode comes first and the rest
 * follows from it.
 */
enum class AppMode(val label: String, val detail: String) {
    LOCAL("Local", "This phone is the camera"),
    REMOTE("Remote", "Drive a camera over NDI"),
    MONITOR("Monitor", "Watch a source, no camera"),

    /**
     * Not a way of working, a place for the things that belong to the phone
     * rather than to a job: what the hardware can do, whether the network
     * carries NDI, which keys the system gives us, what version this is.
     *
     * They were scattered through the other three and read as common to all
     * of them, which they are not. Testing a camera is not a camera setting.
     */
    SYSTEM("System", "The phone, the network, the build")
}

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /**
     * The fader shown when the control bar opens. Remembered, because on a
     * shoot the same one is wanted twenty times in a row.
     */
    var lastParam: Mechanism.Param
        get() = Mechanism.Param.fromName(prefs.getString(KEY_PARAM, null))
        set(value) = prefs.edit().putString(KEY_PARAM, value.name).apply()

    var logCurve: LogCurves.Curve
        get() = LogCurves.Curve.values()
            .firstOrNull { it.name == prefs.getString(KEY_CURVE, null) }
            ?: LogCurves.Curve.REC709
        set(value) = prefs.edit().putString(KEY_CURVE, value.name).apply()

    var tenBitWanted: Boolean
        get() = prefs.getBoolean(KEY_TEN_BIT, false)
        set(value) = prefs.edit().putBoolean(KEY_TEN_BIT, value).apply()

    /** A vectorscope, not a histogram: brightness the eye can judge, cast it cannot. */
    var vectorscopeVisible: Boolean
        get() = prefs.getBoolean(KEY_VECTORSCOPE, false)
        set(value) = prefs.edit().putBoolean(KEY_VECTORSCOPE, value).apply()

    var histogramVisible: Boolean
        get() = prefs.getBoolean(KEY_HISTOGRAM, false)
        set(value) = prefs.edit().putBoolean(KEY_HISTOGRAM, value).apply()

    var stabilisation: Boolean
        get() = prefs.getBoolean(KEY_STABILISATION, false)
        set(value) = prefs.edit().putBoolean(KEY_STABILISATION, value).apply()

    /** Whether the controls drive this phone's camera or one on the network. */
    var remoteMode: Boolean
        get() = prefs.getBoolean(KEY_REMOTE, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE, value).apply()

    /** Which NDI source the remote controls are pointed at. */
    var remoteSource: String?
        get() = prefs.getString(KEY_REMOTE_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_REMOTE_SOURCE, value).apply()

    /** Small, medium, large or the whole frame. Set here, not by tapping. */
    var focusBoxSize: FocusSquareView.Size
        get() = FocusSquareView.Size.values()
            .firstOrNull { it.name == prefs.getString(KEY_BOX, null) }
            ?: FocusSquareView.Size.MEDIUM
        set(value) = prefs.edit().putString(KEY_BOX, value.name).apply()

    /**
     * Kept on the device and never in the build. The APK is published, and
     * anything compiled into it can be read out of it by anybody who downloads
     * it, so a key belongs in the phone's own storage and nowhere else.
     */
    var groqApiKey: String?
        get() = prefs.getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_GROQ, value?.trim()).apply()

    /** Which waveform traces are drawn; any combination, or none for off. */
    var waveformChannels: Set<Mechanism.WaveformChannel>
        get() = prefs.getStringSet(KEY_WAVEFORM, emptySet())
            ?.mapNotNull { name ->
                Mechanism.WaveformChannel.values().firstOrNull { it.name == name }
            }?.toSet() ?: emptySet()
        set(value) = prefs.edit()
            .putStringSet(KEY_WAVEFORM, value.map { it.name }.toSet()).apply()

    /** Local camera, a camera over the network, or a monitor with no camera. */
    var appMode: AppMode
        get() = AppMode.values().firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
            ?: AppMode.LOCAL
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** How long focus holds before it even looks, in milliseconds. */
    var focusHoldMs: Long
        get() = prefs.getLong(KEY_HOLD, 2000)
        set(value) = prefs.edit().putLong(KEY_HOLD, value).apply()

    /** How long a rack takes. Zero is a snap. */
    var focusRampMs: Long
        get() = prefs.getLong(KEY_RAMP, 2000)
        set(value) = prefs.edit().putLong(KEY_RAMP, value).apply()

    /**
     * Whether this phone makes the timecode, follows it, or ignores it.
     *
     * Stored as a name rather than an ordinal so adding a role later cannot
     * silently reinterpret what somebody already chose.
     */
    var ltcRole: LtcEngine.Role
        get() = LtcEngine.Role.values()
            .firstOrNull { it.name == prefs.getString(KEY_LTC_ROLE, null) }
            ?: LtcEngine.Role.OFF
        set(value) = prefs.edit().putString(KEY_LTC_ROLE, value.name).apply()

    /** The rate a master generates at. */
    var ltcRate: Timecode.Rate
        get() = Timecode.Rate.values()
            .firstOrNull { it.name == prefs.getString(KEY_LTC_RATE, null) }
            ?: Timecode.Rate.FPS_25
        set(value) = prefs.edit().putString(KEY_LTC_RATE, value.name).apply()

    /**
     * Hold the screen awake on the camera screen.
     *
     * On by default, because a camera that sleeps mid take is not a camera.
     * It is still a setting, since a phone left monitoring on a shelf for an
     * hour would rather not cook itself.
     */
    var selectedCameraId: String?
        get() = prefs.getString(KEY_CAMERA_ID, null)
        set(value) = prefs.edit().putString(KEY_CAMERA_ID, value).apply()

    /** Recording bitrate in megabits per second. */
    var recordMbps: Int
        get() = prefs.getInt(KEY_RECORD_MBPS, 40)
        set(value) = prefs.edit().putInt(KEY_RECORD_MBPS, value.coerceIn(4, 200)).apply()

    /** Top edge or bottom edge of the picture. */
    var timecodeAtTop: Boolean
        get() = prefs.getBoolean(KEY_TC_TOP, true)
        set(value) = prefs.edit().putBoolean(KEY_TC_TOP, value).apply()

    /** The half transparent plate a broadcast burn-in has. */
    var timecodePlate: Boolean
        get() = prefs.getBoolean(KEY_TC_PLATE, true)
        set(value) = prefs.edit().putBoolean(KEY_TC_PLATE, value).apply()

    /**
     * Which device's clock to follow.
     *
     * Needed once more than one device can generate: two cameras following two
     * different masters look identical if all you show is a running number.
     * Null means take whatever arrives.
     */
    var timecodeSource: String?
        get() = prefs.getString(KEY_TC_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_TC_SOURCE, value).apply()

    /** Which lines of the burn-in are drawn. */
    var timecodeShowSync: Boolean
        get() = prefs.getBoolean(KEY_TC_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_TC_SYNC, value).apply()

    var timecodeShowStatus: Boolean
        get() = prefs.getBoolean(KEY_TC_STATUS, true)
        set(value) = prefs.edit().putBoolean(KEY_TC_STATUS, value).apply()

    var timecodeShowFormat: Boolean
        get() = prefs.getBoolean(KEY_TC_FORMAT, true)
        set(value) = prefs.edit().putBoolean(KEY_TC_FORMAT, value).apply()

    var showTimecode: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TC, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TC, value).apply()

    /**
     * How big the clock is drawn, in sp.
     *
     * A setting because the right size depends on where the phone is. On a
     * gimbal at arm's length it wants to be large; on a monitor propped beside
     * a mixer it can be small, and a large one would only cover the shot.
     */
    var timecodeSize: Int
        get() = prefs.getInt(KEY_TC_SIZE, 16)
        set(value) = prefs.edit().putInt(KEY_TC_SIZE, value.coerceIn(10, 48)).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /** Whether the battery prompt has been shown, so it is asked once, not nagged. */
    var batteryAsked: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_ASKED, value).apply()

    /** Listen for a Tentacle and show its timecode. */
    var timecodeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIMECODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TIMECODE, value).apply()

    var monitorLutName: String?
        get() = prefs.getString(KEY_LUT, null)
        set(value) = prefs.edit().putString(KEY_LUT, value).apply()

    private companion object {
        const val KEY_PARAM = "last_param"
        const val KEY_CURVE = "log_curve"
        const val KEY_TEN_BIT = "ten_bit"
        const val KEY_HISTOGRAM = "histogram"
        const val KEY_VECTORSCOPE = "vectorscope"
        const val KEY_BOX = "focus_box"
        const val KEY_GROQ = "groq_key"
        const val KEY_WAVEFORM = "waveform"
        const val KEY_MODE = "app_mode"
        const val KEY_HOLD = "focus_hold"
        const val KEY_RAMP = "focus_ramp"
        const val KEY_TIMECODE = "timecode"
        const val KEY_LTC_ROLE = "ltc_role"
        const val KEY_LTC_RATE = "ltc_rate"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_TC_SIZE = "timecode_size"
        const val KEY_SHOW_TC = "show_timecode"
        const val KEY_TC_TOP = "timecode_top"
        const val KEY_TC_PLATE = "timecode_plate"
        const val KEY_TC_SYNC = "tc_line_sync"
        const val KEY_TC_STATUS = "tc_line_status"
        const val KEY_TC_FORMAT = "tc_line_format"
        const val KEY_TC_SOURCE = "timecode_source"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_RECORD_MBPS = "record_mbps"
        const val KEY_BATTERY_ASKED = "battery_asked"
        const val KEY_STABILISATION = "stabilisation"
        const val KEY_LUT = "monitor_lut"
        const val KEY_REMOTE = "remote_mode"
        const val KEY_REMOTE_SOURCE = "remote_source"
    }
}
