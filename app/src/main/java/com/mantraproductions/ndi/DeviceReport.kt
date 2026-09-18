package com.mantraproductions.ndi

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Size

/**
 * Everything this phone will admit to, asked rather than assumed.
 *
 * Written because the answer differs on every device and the differences are
 * the ones that decide a shoot: whether the sensor takes a manual exposure,
 * whether ten bit exists on the wide lens but not the tele, whether there is
 * an HEVC encoder, and whether there is room for the take.
 */
object DeviceReport {

    fun build(context: Context): String = buildString {
        append(phoneSection())
        append('\n')
        append(storageSection())
        append('\n')
        append(cameraSection(context))
        append('\n')
        append(encoderSection())
    }

    private fun phoneSection() = buildString {
        append("PHONE\n")
        append("  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("  Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("  ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
    }

    private fun storageSection() = buildString {
        append("STORAGE\n")
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val free = stat.availableBytes
            val total = stat.totalBytes
            append("  ").append(gb(free)).append(" free of ").append(gb(total)).append('\n')
            // What that space is actually worth, which is the only number that
            // answers "can I shoot this".
            for ((label, mbps) in listOf("1080p25 at 12 Mbps" to 12, "4K25 at 35 Mbps" to 35)) {
                val seconds = free * 8.0 / (mbps * 1_000_000.0)
                append("  ").append(label).append(": about ")
                    .append((seconds / 60).toInt()).append(" minutes\n")
            }
        } catch (e: Exception) {
            append("  Unreadable\n")
        }
    }

    private fun cameraSection(context: Context) = buildString {
        append("CAMERAS\n")
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            append("  ").append(ids.size).append(" reported by the system\n\n")

            for (id in ids) {
                val c = manager.getCameraCharacteristics(id)
                val caps = HdrCapabilities(c)

                append("  Camera ").append(id).append(' ')
                append(
                    when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraMetadata.LENS_FACING_FRONT -> "(front)"
                        CameraMetadata.LENS_FACING_BACK -> "(back)"
                        else -> "(external)"
                    }
                ).append('\n')
                append("    ").append(caps.summary()).append('\n')

                c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    append("    ISO ").append(it.lower).append(" to ").append(it.upper).append('\n')
                }
                c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    append("    Shutter ").append(Mechanism.formatShutter(it.lower))
                        .append(" to ").append(Mechanism.formatShutter(it.upper)).append('\n')
                }
                val closest = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                append("    Focus: ")
                    .append(if (closest > 0f) "manual, closest ${"%.2f".format(1 / closest)} m"
                            else "auto only")
                    .append('\n')
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let {
                    append("    Aperture f/").append(it.joinToString(", f/")).append('\n')
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                        append("    Zoom ").append("%.1f".format(it.lower))
                            .append("x to ").append("%.1f".format(it.upper)).append("x\n")
                    }
                }
                append("    Tone curve points: ").append(caps.maxCurvePoints).append('\n')
                if (caps.supportedProfiles.isNotEmpty()) {
                    append("    10-bit profiles: ").append(caps.supportedProfiles.size).append('\n')
                }

                append(recordableSizes(c))
                append('\n')
            }
        } catch (e: Exception) {
            append("  Could not read the camera system: ").append(e.message).append('\n')
        }
    }

    /** What this camera will actually hand to an encoder, and how fast. */
    private fun recordableSizes(c: CameraCharacteristics): String = buildString {
        val map: StreamConfigurationMap =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@buildString
        val sizes = map.getOutputSizes(android.media.MediaRecorder::class.java) ?: return@buildString

        append("    Records:\n")
        // Largest first, and only the shapes anybody shoots, so the list is
        // readable rather than forty entries of legacy sizes.
        sizes.sortedByDescending { it.width.toLong() * it.height }
            .filter { it.width >= 1280 }
            .take(6)
            .forEach { size ->
                val duration = map.getOutputMinFrameDuration(
                    android.media.MediaRecorder::class.java, size
                )
                val fps = if (duration > 0) (1_000_000_000.0 / duration).toInt() else 0
                append("      ").append(size.width).append('x').append(size.height)
                if (fps > 0) append(" up to ").append(fps).append(" fps")
                append('\n')
            }

        map.highSpeedVideoSizes?.takeIf { it.isNotEmpty() }?.let { highSpeed ->
            append("    High speed: ")
            append(highSpeed.joinToString(", ") { s: Size -> "${s.width}x${s.height}" })
            append('\n')
        }
    }

    private fun encoderSection() = buildString {
        append("ENCODERS\n")
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val wanted = listOf("video/avc", "video/hevc", "video/av01", "audio/mp4a-latm")
            for (mime in wanted) {
                val names = list.codecInfos
                    .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                    .map { it.name }
                if (names.isEmpty()) continue
                append("  ").append(mime).append(": ").append(names.size).append(" encoder")
                    .append(if (names.size == 1) "" else "s")
                // Hardware is the only one worth having for video.
                val hardware = names.filterNot {
                    it.startsWith("OMX.google") || it.startsWith("c2.android")
                }
                if (hardware.isNotEmpty()) append(", hardware present")
                append('\n')
            }
        } catch (e: Exception) {
            append("  Could not read the codec list\n")
        }
    }

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)
}
