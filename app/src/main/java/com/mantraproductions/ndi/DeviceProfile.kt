package com.mantraproductions.ndi

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log

/**
 * What this phone can do, decided once at startup and then never asked again.
 *
 * Ten bit arrived as a second pipeline and took the app down with it on a
 * phone that has no ten bit camera. The fault was asking the question late and
 * in several places: a capability read inside a code path is a capability read
 * on a device already committed to that path.
 *
 * So it is read here, before anything opens a camera, and everything
 * downstream reads these flags instead of the platform. On an eight bit phone
 * the ten bit classes are then never touched at all, which matters because
 * some of them name platform types that do not exist before Android 13 and
 * loading such a class is itself the crash.
 */
object DeviceProfile {

    @Volatile private var resolved = false

    /** The camera can deliver ten bit, and there is an encoder for it. */
    @Volatile var tenBitCapable = false
        private set

    /** The camera accepts an arbitrary tone curve, which is how log is applied. */
    @Volatile var logCapable = false
        private set

    @Volatile var manualSensor = false
        private set

    @Volatile var summary = "not detected"
        private set

    /**
     * Cheap, and safe to call more than once. Every read is wrapped: a
     * capability query that throws on an unusual device must leave the app in
     * the conservative state rather than taking it down.
     */
    @Synchronized
    fun detect(context: Context) {
        if (resolved) return
        resolved = true

        var tenBitCamera = false
        var tone = false
        var manual = false
        var level = "unknown"

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull()
            if (id != null) {
                val c = manager.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: IntArray(0)

                manual = caps.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                )

                val manualPost = caps.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
                )
                val curvePoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
                tone = manualPost && curvePoints >= 2

                // Ten bit is an Android 13 idea. Below that the question has no
                // answer and the class that would answer it does not exist, so
                // it is never named.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val tenBitCap = caps.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
                    )
                    tenBitCamera = tenBitCap && TenBit.hasHlg10(c)
                }

                level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                    else -> "unknown"
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Capability detection failed, assuming 8-bit", e)
        }

        // A camera that can produce ten bit is useless without an encoder that
        // can take it, so both have to be true before anything offers it.
        val encoder = try {
            tenBitCamera && HdrVideoEncoder.supportsTenBit()
        } catch (e: Throwable) {
            false
        }

        tenBitCapable = encoder
        logCapable = tone
        manualSensor = manual
        summary = buildString {
            append(level)
            append(if (tenBitCapable) ", 10-bit HLG" else ", 8-bit")
            append(if (logCapable) ", log" else ", no log")
            append(if (manualSensor) ", manual sensor" else ", auto only")
        }
        Log.i(TAG, "Device: $summary")
    }

    /**
     * Named separately so that the platform class it touches is only ever
     * loaded on a version that has it. A class reference resolved on the wrong
     * Android is not an exception you can catch where you wrote it.
     */
    private object TenBit {
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun hasHlg10(c: CameraCharacteristics): Boolean = try {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles
                ?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) == true
        } catch (e: Throwable) {
            false
        }
    }

    private const val TAG = "DeviceProfile"
}
