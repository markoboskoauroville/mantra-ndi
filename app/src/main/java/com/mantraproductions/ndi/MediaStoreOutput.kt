package com.mantraproductions.ndi

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Where the work lands: DCIM/Mantra NDI.
 *
 * In the gallery, alongside everything else the phone shot, rather than buried
 * in the app's private folder where nothing can see it and an uninstall takes
 * it with them.
 *
 * On Android 10 and newer this goes through MediaStore, which needs no storage
 * permission for the app's own media and puts the file in the index so it
 * appears in the gallery immediately instead of whenever a scan happens to
 * run. Older versions get the plain path.
 */
object MediaStoreOutput {

    const val FOLDER = "Mantra NDI"

    data class Target(val uri: Uri?, val file: File?, val displayName: String) {
        /** What to tell the operator, short enough to read in three seconds. */
        val shortLocation: String get() = "DCIM/$FOLDER/$displayName"
    }

    /**
     * MediaStore will not hand back a writable path, only a stream, and
     * MediaMuxer needs a file descriptor. The pending flag keeps the entry
     * hidden from the gallery until [publish] is called, so a half written
     * recording is never shown as finished.
     */
    fun createVideo(context: Context, name: String): Target? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/$FOLDER"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            uri?.let { Target(it, null, name) }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                FOLDER
            )
            if (!dir.exists()) dir.mkdirs()
            Target(null, File(dir, name), name)
        }
    } catch (e: Exception) {
        null
    }

    /** Clears the pending flag so the gallery shows a finished file. */
    fun publish(context: Context, target: Target) {
        val uri = target.uri ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // The file is written either way; only its visibility is at stake.
        }
    }

    /** Removes a pending entry when a recording failed before it began. */
    fun discard(context: Context, target: Target) {
        target.uri?.let {
            try {
                context.contentResolver.delete(it, null, null)
            } catch (e: Exception) {
                // Nothing further to do; a pending entry expires on its own.
            }
        }
    }

    /**
     * Anything that is not a recording, such as a generated LUT. Documents go
     * beside the footage rather than into a second place nobody looks.
     */
    fun writeText(context: Context, name: String, content: String): Target? = try {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            FOLDER
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.writeText(content)
        Target(null, file, name)
    } catch (e: Exception) {
        null
    }
}
