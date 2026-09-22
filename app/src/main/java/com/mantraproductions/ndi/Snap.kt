package com.mantraproductions.ndi

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface

/**
 * SNAP: the sensor's own frame, written as a DNG, while the stream carries on.
 *
 * Uncorrected on purpose. Everything the operator has chosen — the log curve
 * in the phone's tone mapper, the LUT on the monitor — is a decision about how
 * the picture should look going out, and none of it belongs in a negative. A
 * DNG is the measurement: Bayer values as the sensor read them, with the
 * numbers needed to develop them later written into the header. Baking a look
 * into one throws away the only thing it was for.
 *
 * Android's own DngCreator does the writing. The Adobe SDK would do it too and
 * costs four megabytes of native library, a build dependency and an OpenCV;
 * the platform has had this since Lollipop and it is the same file.
 *
 * The reader is configured into the capture session but never appears on the
 * repeating request, so an armed SNAP costs one buffer of memory and no
 * bandwidth at all until the key is pressed.
 */
class Snap(private val context: Context) {

    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** The frame and the result arrive separately and a DNG needs both. */
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var characteristics: CameraCharacteristics? = null
    private var orientation: Int = 0
    private var onDone: ((String?) -> Unit)? = null
    private val lock = Any()

    var lastPath: String? = null
        private set

    /** The biggest RAW this camera makes, or null if it makes none. */
    fun rawSize(characteristics: CameraCharacteristics): Size? {
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null
        return map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height }
    }

    /**
     * @return the surface to configure into the session, or null when this
     *   lens cannot produce RAW at all — the ultra wide on some phones, and
     *   every logical fused camera on others. The key goes dark rather than
     *   pretending.
     */
    fun open(characteristics: CameraCharacteristics): Surface? {
        close()
        val size = rawSize(characteristics) ?: run {
            Trace.state("no RAW on this lens, SNAP is dark")
            return null
        }
        this.characteristics = characteristics

        val t = HandlerThread("snap").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        // Two, not one. A single buffer means the next snap cannot be captured
        // until the last one has finished being written, and writing a DNG
        // takes longer than a finger does.
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
        r.setOnImageAvailableListener({ source ->
            val image = try { source.acquireNextImage() } catch (e: Throwable) { null }
            if (image != null) {
                synchronized(lock) { pendingImage = image }
                writeIfReady()
            }
        }, handler)
        reader = r
        Trace.state("SNAP armed at ${size.width}x${size.height} RAW")
        return r.surface
    }

    fun close() {
        synchronized(lock) {
            runCatching { pendingImage?.close() }
            pendingImage = null
            pendingResult = null
        }
        runCatching { reader?.close() }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    val armed: Boolean get() = reader != null

    val surface: Surface? get() = reader?.surface

    /**
     * Takes one.
     *
     * @param deviceRotation the phone's own rotation in degrees, so the DNG
     *   carries an orientation tag and opens the right way up in a developer
     *   rather than needing to be turned by hand every time.
     */
    fun take(engine: CaptureEngine, deviceRotation: Int, onDone: (String?) -> Unit): Boolean {
        val target = reader?.surface ?: return false
        synchronized(lock) {
            this.onDone = onDone
            this.orientation = deviceRotation
            runCatching { pendingImage?.close() }
            pendingImage = null
            pendingResult = null
        }
        Trace.step("snap requested")
        val started = engine.captureStill(target) { result ->
            synchronized(lock) { pendingResult = result }
            if (result == null) finish(null) else writeIfReady()
        }
        if (!started) finish(null)
        return started
    }

    private fun writeIfReady() {
        val image: Image
        val result: TotalCaptureResult
        val chars: CameraCharacteristics
        synchronized(lock) {
            image = pendingImage ?: return
            result = pendingResult ?: return
            chars = characteristics ?: return
            pendingImage = null
            pendingResult = null
        }

        val now = System.currentTimeMillis()
        val name = "snap-" + TraceFormat.fileStamp(now, Trace.offsetAt(now)) + ".dng"
        val path = try {
            DngCreator(chars, result).use { dng ->
                dng.setOrientation(exifFor(orientation))
                Downloads.writeStream(context, name, "image/x-adobe-dng") { out ->
                    dng.writeImage(out, image)
                }
            }
        } catch (t: Throwable) {
            Trace.fault("snap write", t)
            null
        } finally {
            runCatching { image.close() }
        }

        if (path != null) Trace.step("snap written to $path")
        else Trace.refused("snap", "could not be written")
        finish(path)
    }

    private fun finish(path: String?) {
        lastPath = path
        val callback = synchronized(lock) { onDone.also { onDone = null } }
        callback?.invoke(path)
    }

    /**
     * Degrees to the TIFF orientation tag DngCreator wants.
     *
     * It takes an ExifInterface constant, not an angle, and handing it an angle
     * produces a file that opens sideways with nothing to say why.
     */
    private fun exifFor(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> android.media.ExifInterface.ORIENTATION_NORMAL
    }
}
