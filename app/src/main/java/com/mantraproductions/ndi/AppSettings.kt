package com.mantraproductions.ndi

import android.content.Context

/** Everything the settings screen owns, and the fader the operator last used. */
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

    var monitorLutName: String?
        get() = prefs.getString(KEY_LUT, null)
        set(value) = prefs.edit().putString(KEY_LUT, value).apply()

    private companion object {
        const val KEY_PARAM = "last_param"
        const val KEY_CURVE = "log_curve"
        const val KEY_TEN_BIT = "ten_bit"
        const val KEY_HISTOGRAM = "histogram"
        const val KEY_STABILISATION = "stabilisation"
        const val KEY_LUT = "monitor_lut"
        const val KEY_REMOTE = "remote_mode"
        const val KEY_REMOTE_SOURCE = "remote_source"
    }
}
