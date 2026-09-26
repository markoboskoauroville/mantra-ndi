package com.mantraproductions.ndi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Puts a text file where a person can actually find it: Downloads, in a folder
 * with the app's name on it.
 *
 * This is the single reason the last build's crash reporter never produced a
 * file. It built a java.io.File path into the public Downloads directory and
 * wrote to it. From Android 10 that is refused, and it is refused SILENTLY:
 * the write throws nothing useful, the app carries on, and the folder stays
 * empty. Ten versions were shipped believing there was a crash log.
 *
 * So the public folder is written through MediaStore, which is the only route
 * an app has to it, and the answer is the display path or null. Never a
 * boolean nobody checks.
 */
object Downloads {

    private const val FOLDER = "Mantra Manual Camera"

    /**
     * Writes text into Downloads/Mantra NDI and answers where it went, or null
     * if it could not be written.
     *
     * IS_PENDING is deliberately not used. It is the tidy way to publish a
     * file, and it needs a second call to clear it — which is one more thing
     * to complete while the process is being torn down by a crash. A report
     * that is visible while still being written beats a complete one that is
     * invisible because the process died between the write and the update.
     */
    fun writeText(context: Context, name: String, text: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                    it.flush()
                } ?: return null
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + name
            } else {
                // Below Android 10 a real path still works, and it still needs
                // WRITE_EXTERNAL_STORAGE to have been granted. This branch
                // never runs on the phone this app is for; it is here so the
                // failure is a refusal rather than a crash on an older device.
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                )
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                    it.flush()
                }
                f.absolutePath
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The same route, for a file that is not text.
     *
     * A DNG is written straight into the stream MediaStore hands back rather
     * than built in memory first: a full frame of Bayer data off this sensor
     * is tens of megabytes, and a snap that costs an allocation that size is a
     * snap that occasionally does not happen.
     *
     * @return where it went, or null if it could not be written
     */
    fun writeStream(
        context: Context,
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { body(it); it.flush() }
                    ?: return null
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + name
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                )
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { body(it); it.flush() }
                f.absolutePath
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Where a person should look, for showing on screen. */
    fun folder(): String = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
}
