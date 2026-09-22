package com.mantraproductions.ndi

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
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
        /** The camera to open. For a physical sub-lens this is its parent. */
        val id: String,
        /**
         * The physical sub-camera to point the session at, or null for the
         * camera itself.
         *
         * A Pixel does not put its ultra wide in `cameraIdList`. The back
         * camera is one *logical* camera that fuses several physical ones and
         * decides between them by zoom, so asking the list for the lenses of a
         * Pixel 7 answers "back and front" — which is why L3 and L4 were dark
         * on a phone with three lenses. The real ones are behind
         * `getPhysicalCameraIds()` and are reached by naming one on each
         * OutputConfiguration.
         */
        val physicalId: String? = null,
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
                if (physicalId != null) {
                    return "$facing $kind ${equivalentMm}mm".replace("  ", " ").trim()
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
            val found = mutableListOf<Lens>()

            for (id in manager.cameraIdList) {
                val c = try {
                    manager.getCameraCharacteristics(id)
                } catch (e: Exception) {
                    Log.w(TAG, "camera $id", e); continue
                }

                val capabilities = c.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                ) ?: IntArray(0)

                val backwardCompatible = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                )
                if (usableOnly && !backwardCompatible) continue

                val isLogical = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )

                found.add(describe(id, null, c, isLogical))

                // The physical lenses hiding inside a logical camera. On a
                // Pixel this is where the ultra wide lives, and on a 7 Pro the
                // telephoto; without this the app offers "back and front" and
                // calls it four lenses.
                if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (physical in c.physicalCameraIds) {
                        if (physical in manager.cameraIdList) continue   // already listed
                        val pc = try {
                            manager.getCameraCharacteristics(physical)
                        } catch (e: Exception) {
                            Log.w(TAG, "physical camera $physical", e); continue
                        }
                        found.add(describe(id, physical, pc, isLogical = false))
                    }
                }
            }

            // Two entries at the same focal length and facing are the logical
            // camera and the physical one behind it saying the same thing, and
            // a rail with the same lens on it twice is worse than one lens.
            found
                .distinctBy { it.facing to it.equivalentMm }
                .sortedWith(compareBy({ it.facing != "Back" }, { it.equivalentMm }))
        } catch (e: Exception) {
            Log.w(TAG, "enumerate", e)
            emptyList()
        }
    }

    private fun describe(
        id: String,
        physicalId: String?,
        c: CameraCharacteristics,
        isLogical: Boolean
    ): Lens {
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
            // 35mm equivalent from the sensor's own width, which is the only
            // honest way to compare two phone lenses.
            Math.round(focal * (36f / sensor.width))
        } else 0

        val largest = c.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height }

        return Lens(
            id = id,
            physicalId = physicalId,
            facing = facing,
            focalLengthMm = focal,
            equivalentMm = equivalent,
            isLogical = isLogical,
            maxResolution = largest?.let { "${it.width}x${it.height}" } ?: "unknown"
        )
    }

    private const val TAG = "CameraCatalogue"
}
