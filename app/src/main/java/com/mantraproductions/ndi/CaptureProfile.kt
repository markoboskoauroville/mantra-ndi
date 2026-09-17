package com.mantraproductions.ndi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named capture setup, the fcam-pro idea: pick "Cinema 24" once instead of
 * setting six things every time you start.
 *
 * isoValue / shutterNs are null when that control is left on auto. Shutter is
 * nanoseconds because that's what SENSOR_EXPOSURE_TIME wants; the UI thinks
 * in fractions (1/50s) and converts.
 */
data class CaptureProfile(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitRate: Int,
    val useHevc: Boolean = false,
    val isoValue: Int? = null,
    val shutterNs: Long? = null,
    val lockWhiteBalance: Boolean = false,
    val videoStabilization: Boolean = false
) {
    /** 180-degree shutter for this profile's frame rate, the film-standard default. */
    fun shutter180Ns(): Long = 1_000_000_000L / (fps * 2L)

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("bitRate", bitRate)
        put("useHevc", useHevc)
        put("isoValue", isoValue ?: JSONObject.NULL)
        put("shutterNs", shutterNs ?: JSONObject.NULL)
        put("lockWhiteBalance", lockWhiteBalance)
        put("videoStabilization", videoStabilization)
    }

    companion object {
        fun fromJson(o: JSONObject) = CaptureProfile(
            name = o.getString("name"),
            width = o.getInt("width"),
            height = o.getInt("height"),
            fps = o.getInt("fps"),
            bitRate = o.getInt("bitRate"),
            useHevc = o.optBoolean("useHevc", false),
            isoValue = if (o.isNull("isoValue")) null else o.getInt("isoValue"),
            shutterNs = if (o.isNull("shutterNs")) null else o.getLong("shutterNs"),
            lockWhiteBalance = o.optBoolean("lockWhiteBalance", false),
            videoStabilization = o.optBoolean("videoStabilization", false)
        )

        /**
         * Starting set. Bitrates are sized for NDI HX over decent 5GHz Wi-Fi —
         * high enough to look good, low enough not to saturate the link.
         */
        val defaults: List<CaptureProfile> = listOf(
            CaptureProfile("Cinema 24", 1920, 1080, 24, 12_000_000, lockWhiteBalance = true),
            CaptureProfile("PAL 25", 1920, 1080, 25, 12_000_000, lockWhiteBalance = true),
            CaptureProfile("Broadcast 50", 1920, 1080, 50, 18_000_000),
            CaptureProfile("Standard 30", 1920, 1080, 30, 10_000_000),
            CaptureProfile("4K 25", 3840, 2160, 25, 35_000_000, useHevc = true),
            CaptureProfile("Low bandwidth 25", 1280, 720, 25, 5_000_000)
        )
    }
}

/** SharedPreferences-backed profile store. Small enough not to need Room. */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("capture_profiles", Context.MODE_PRIVATE)

    fun load(): List<CaptureProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return CaptureProfile.defaults
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { CaptureProfile.fromJson(array.getJSONObject(it)) }
                .ifEmpty { CaptureProfile.defaults }
        } catch (e: Exception) {
            CaptureProfile.defaults
        }
    }

    fun save(profiles: List<CaptureProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun upsert(profile: CaptureProfile) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.name == profile.name }
        if (index >= 0) current[index] = profile else current.add(profile)
        save(current)
    }

    var selectedName: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    fun selected(): CaptureProfile {
        val profiles = load()
        return profiles.firstOrNull { it.name == selectedName } ?: profiles.first()
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_SELECTED = "selected"
    }
}
