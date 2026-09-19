package com.mantraproductions.ndi

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Every camera this phone actually has, named the way a person would name it.
 *
 * Phones no longer have a front and a back camera. They have a main, an ultra
 * wide, a telephoto, sometimes a depth sensor that cannot take a picture at
 * all, and often a logical camera that is really several of those fused. The
 * app was opening whichever one came first in the list, which on most phones
 * is the main sensor and on some is whatever the vendor felt like.
 *
 * So they are enumerated, described by what they are rather than by their id,
 * and the ones that cannot produce a usable picture are left out.
 */
object CameraCatalogue {

    data class Lens(
        val id: String,
        val facing: String,
        val focalLengthMm: Float,
        /** Roughly what it would be called on a full frame body. */
        val equivalentMm: Int,
        val isLogical: Boolean,
        val maxResolution: String
    ) {
        /**
         * The name the operator sees. A focal length says more than an id, and
         * the word says more than the number to anybody who is not a stills
         * photographer.
         */
        val label: String
            get() {
                val kind = when {
                    equivalentMm <= 0 -> ""
                    equivalentMm < 20 -> "Ultra wide"
                    equivalentMm < 32 -> "Wide"
                    equivalentMm < 70 -> "Main"
                    else -> "Tele"
                }
                val mm = if (equivalentMm > 0) " ${equivalentMm}mm" else ""
                val fused = if (isLogical) " (fused)" else ""
                return "$facing  $kind$mm$fused".replace("  ", " ").trim()
            }
    }

    /**
     * @param usableOnly drops cameras that cannot deliver a normal picture:
     *        depth and infrared sensors appear in the list and produce nothing
     *        an operator would recognise as a shot.
     */
    fun lenses(context: Context, usableOnly: Boolean = true): List<Lens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()

        return try {
            manager.cameraIdList.mapNotNull { id ->
                try {
                    val c = manager.getCameraCharacteristics(id)

                    val capabilities = c.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                    ) ?: IntArray(0)

                    val backwardCompatible = capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                    )
                    if (usableOnly && !backwardCompatible) return@mapNotNull null

                    val isLogical = capabilities.contains(
                        CameraCharacteristics
                            .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    )

                    val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                        CameraCharacteristics.LENS_FACING_BACK -> "Back"
                        else -> "External"
                    }

                    val focal = c.get(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )?.firstOrNull() ?: 0f

                    val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val equivalent = if (sensor != null && focal > 0f && sensor.width > 0) {
                        // 35mm equivalent from the sensor's own width, which is
                        // the only honest way to compare two phone lenses.
                        Math.round(focal * (36f / sensor.width))
                    } else 0

                    val largest = c.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                        ?.maxByOrNull { it.width.toLong() * it.height }

                    Lens(
                        id = id,
                        facing = facing,
                        focalLengthMm = focal,
                        equivalentMm = equivalent,
                        isLogical = isLogical,
                        maxResolution = largest?.let { "${it.width}x${it.height}" } ?: "unknown"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "camera $id", e)
                    null
                }
            }.sortedWith(compareBy({ it.facing != "Back" }, { it.equivalentMm }))
        } catch (e: Exception) {
            Log.w(TAG, "enumerate", e)
            emptyList()
        }
    }

    private const val TAG = "CameraCatalogue"
}
