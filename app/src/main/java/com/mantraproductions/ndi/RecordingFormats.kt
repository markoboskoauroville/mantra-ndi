package com.mantraproductions.ndi

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * What this phone can actually record, asked of the phone.
 *
 * A menu of formats that were decided when the app was written is a menu that
 * lies on most phones: it offers 4K60 to a sensor that tops out at 4K30 and
 * hides 10-bit from a phone that has it. Every option here is read from the
 * camera's own configuration map, so the list is different on every device and
 * correct on all of them.
 *
 * One correction to the usual advice, because it costs a day to find: for
 * ordinary recording the frame rate limit comes from
 * getOutputMinFrameDuration, not from getHighSpeedVideoFpsRangesFor. The high
 * speed ranges only apply to a constrained high speed session, which is the
 * slow motion path, and asking about them for a normal size returns nothing at
 * all. That reads as "this phone cannot do 4K" when it plainly can.
 */
object RecordingFormats {

    /** How the file is written. */
    enum class Container(val label: String, val extension: String, val detail: String) {
        MP4("MP4", "mp4", "Plays everywhere, smallest files"),
        MOV("MOV", "mov", "QuickTime, what an edit expects")
    }

    /** What the picture is encoded as, in the words a camera menu uses. */
    enum class Codec(val label: String, val mime: String, val detail: String) {
        H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC, "Compatible with everything"),
        H265("H.265", MediaFormat.MIMETYPE_VIDEO_HEVC, "Half the size, same picture")
    }

    /**
     * Bit depth and what the numbers mean, which cameras present as one choice
     * rather than two, because choosing 10-bit without choosing a curve to put
     * in it is not a thing anybody wants.
     */
    enum class ColourMode(val label: String, val detail: String, val tenBit: Boolean) {
        REC709_8("8-bit 709", "Ready to use, nothing to grade", false),
        LOG_10("10-bit Log", "Flat, for grading. Needs a LUT to view", true),
        HDR_10("10-bit HDR", "HLG, wide latitude, plays as HDR", true)
    }

    data class Mode(
        val size: Size,
        val maxFps: Int,
        /** Every standard rate this size can actually hold. */
        val rates: List<Int>
    ) {
        val label: String
            get() = when {
                size.width >= 3840 -> "4K UHD"
                size.width >= 2560 -> "2.5K"
                size.width >= 1920 -> "1080p"
                size.width >= 1280 -> "720p"
                else -> "${size.width}x${size.height}"
            }

        val detail: String get() = "${size.width}x${size.height}, up to ${maxFps}p"
    }

    /**
     * Sensible bitrates for a size and rate, as a camera offers them: a small
     * set of named qualities rather than a number nobody can judge.
     *
     * Derived from the pixel rate, because 25 Mbps is generous at 1080p25 and
     * poor at 4K50, and a fixed list gets that backwards at one end or the
     * other.
     */
    fun bitrateChoices(size: Size, fps: Int): List<Pair<String, Int>> {
        val pixelsPerSecond = size.width.toLong() * size.height * fps
        // Bits per pixel, the figures broadcast encoders settle around.
        return listOf(
            "Light" to (pixelsPerSecond * 0.04).toInt(),
            "Standard" to (pixelsPerSecond * 0.08).toInt(),
            "High" to (pixelsPerSecond * 0.14).toInt(),
            "Master" to (pixelsPerSecond * 0.22).toInt()
        ).map { (name, bits) ->
            val mbps = (bits / 1_000_000).coerceIn(4, 300)
            "$name, $mbps Mbps" to mbps
        }
    }

    /** Every recordable size this camera offers, largest first, 720p and up. */
    fun modes(context: Context, cameraId: String?): List<Mode> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val id = cameraId ?: manager.cameraIdList.firstOrNull() ?: return emptyList()

        return try {
            val c = manager.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return emptyList()

            val aeRanges: Array<Range<Int>> =
                c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?: emptyArray()
            val aeMax = aeRanges.maxOfOrNull { it.upper } ?: 30

            map.getOutputSizes(MediaRecorder::class.java)
                .filter { it.width >= 1280 && it.height >= 720 }
                .map { size ->
                    // The real limit for a normal session: how long one frame
                    // takes at this size. High speed ranges are the slow motion
                    // path and answer nothing here.
                    val minDurationNs = map.getOutputMinFrameDuration(
                        MediaRecorder::class.java, size
                    )
                    val sensorMax = if (minDurationNs > 0) {
                        (1_000_000_000.0 / minDurationNs).toInt()
                    } else 30

                    val ceiling = minOf(sensorMax, aeMax)
                    val rates = STANDARD_RATES.filter { it <= ceiling }
                    Mode(size, ceiling, rates.ifEmpty { listOf(24) })
                }
                .filter { it.rates.isNotEmpty() }
                .sortedByDescending { it.size.width.toLong() * it.size.height }
        } catch (e: Exception) {
            Log.w(TAG, "probe", e)
            emptyList()
        }
    }

    /** Whether this phone's encoder will really write 10-bit in this codec. */
    fun supportsTenBit(codec: Codec): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(codec.mime, true) } &&
                info.getCapabilitiesForType(codec.mime).profileLevels.any { level ->
                    // Main10 for HEVC, High10 for AVC. Anything else is 8-bit
                    // however the marketing describes the phone.
                    level.profile == HEVC_MAIN10 || level.profile == AVC_HIGH10
                }
        }
    } catch (e: Exception) {
        false
    }

    fun colourModes(codec: Codec): List<ColourMode> {
        val ten = supportsTenBit(codec)
        return ColourMode.values().filter { !it.tenBit || ten }
    }

    private val STANDARD_RATES = listOf(24, 25, 30, 50, 60, 100, 120)
    private const val HEVC_MAIN10 = 0x1000
    private const val AVC_HIGH10 = 0x1000
    private const val TAG = "RecordingFormats"
}
