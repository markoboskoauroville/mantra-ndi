package com.mantraproductions.ndi

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build

/**
 * What this particular phone can actually do, asked rather than assumed.
 *
 * Ten bit capture arrived in Android 13 and is advertised per camera, so a
 * Pixel 8 and a five year old phone in the same bag answer differently, and
 * the front and rear cameras of one phone can answer differently too. Every
 * question here is asked of a specific camera id.
 *
 * Android requires that any camera advertising ten bit output supports HLG10,
 * so HLG10 is the profile to reach for; HDR10 and Dolby Vision are bonuses
 * some makers add.
 */
class HdrCapabilities(private val characteristics: CameraCharacteristics) {

    /** The capability gate: without this, nothing else here matters. */
    val supportsTenBit: Boolean by lazy {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return@lazy false
        caps.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
        )
    }

    val supportedProfiles: Set<Long> by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !supportsTenBit) {
            emptySet()
        } else {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles ?: emptySet()
        }
    }

    val supportsHlg10: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                supportedProfiles.contains(DynamicRangeProfiles.HLG10)

    val supportsHdr10: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                supportedProfiles.contains(DynamicRangeProfiles.HDR10)

    /**
     * The profile to use for ten bit on this camera, or null for eight bit.
     * HLG10 first because it is the guaranteed one and needs no static
     * metadata; HDR10 only if HLG10 is somehow absent.
     */
    fun bestTenBitProfile(): Long? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> null
        supportsHlg10 -> DynamicRangeProfiles.HLG10
        supportsHdr10 -> DynamicRangeProfiles.HDR10
        else -> null
    }

    /**
     * Whether an eight bit stream may sit in the same capture request as this
     * ten bit profile. Some devices refuse, and the session then fails to
     * configure rather than degrading, so this has to be asked before building
     * a session that mixes a ten bit recording with an eight bit preview.
     */
    fun canMixWithStandard(profile: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val profiles = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) ?: return false
        return profiles.getProfileCaptureRequestConstraints(profile)
            .contains(DynamicRangeProfiles.STANDARD)
    }

    /** Whether the camera will accept an arbitrary tone curve, which is how log is applied. */
    val supportsToneCurve: Boolean by lazy {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return@lazy false
        val manualPost = caps.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        )
        val modes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        manualPost && modes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true
    }

    /**
     * How many points the tone curve may have. Curves are sampled to this,
     * and it is usually 64 or 128, which is plenty for a log curve but has to
     * be asked rather than guessed.
     */
    val maxCurvePoints: Int
        get() = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0

    val hardwareLevel: Int
        get() = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1

    /** One line for the UI, so the operator knows what they are working with. */
    fun summary(): String {
        val level = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            else -> "Unknown"
        }
        val depth = if (supportsHlg10) "10-bit HLG" else "8-bit"
        val log = if (supportsToneCurve) "log ok" else "no log"
        return "$level, $depth, $log"
    }
}
