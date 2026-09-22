package com.mantraproductions.ndi

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a take goes, and the descriptor the muxer writes down.
 *
 * **DCIM/Mantra NDI**, through MediaStore, so the file appears in the gallery
 * and in a file manager the moment it is finished — the same lesson as
 * [Downloads], for the same reason: a path into a public folder is refused
 * silently from Android 10 and the app carries on as if it had worked.
 *
 * DCIM rather than Movies, and that was a real bug rather than a preference:
 * the first build of this put takes in Movies, he looked in DCIM — where every
 * camera on a phone puts its footage, and where he had been told to look — and
 * reported that recording was broken. It was not; 577 frames were in the file.
 * A take nobody can find is a take that did not happen.
 *
 * IS_PENDING is used here, unlike the trace, and the difference is what the
 * file is. A half-written trace is still worth reading; a half-written MP4 has
 * no moov atom and no player will open it, so it is better hidden until the
 * muxer has closed it properly.
 */
object Recordings {

    private const val FOLDER = "Mantra NDI"

    /** An open recording: where it is going, and what to do when it is done. */
    class Take(
        val name: String,
        val where: String,
        private val descriptor: ParcelFileDescriptor,
        private val finish: (Boolean) -> Unit
    ) {
        val fileDescriptor: java.io.FileDescriptor get() = descriptor.fileDescriptor

        /** Publishes the take, or removes it if nothing was ever written. */
        fun close(keep: Boolean) {
            runCatching { descriptor.close() }
            runCatching { finish(keep) }
        }
    }

    fun name(at: Date = Date()): String =
        "mantra-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(at) + ".mp4"

    /**
     * @return a take ready to be written, or null with the reason traced.
     */
    fun open(context: Context, name: String = name()): Take? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/" + FOLDER
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri? = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) {
                Trace.refused("recording", "MediaStore would not make an entry")
                null
            } else {
                // "rw" rather than "w": a muxer seeks back to the start of the
                // file to write the moov atom, and a write-only descriptor
                // cannot, which produces an MP4 that is all data and no index.
                val pfd = resolver.openFileDescriptor(uri, "rw")
                if (pfd == null) {
                    runCatching { resolver.delete(uri, null, null) }
                    Trace.refused("recording", "no descriptor for the new file")
                    null
                } else {
                    Take(
                        name = name,
                        where = Environment.DIRECTORY_DCIM + "/" + FOLDER + "/" + name,
                        descriptor = pfd
                    ) { keep ->
                        if (keep) {
                            resolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                },
                                null, null
                            )
                        } else {
                            resolver.delete(uri, null, null)
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                FOLDER
            )
            dir.mkdirs()
            val file = File(dir, name)
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
            )
            Take(name, file.absolutePath, pfd) { keep -> if (!keep) file.delete() }
        }
    } catch (t: Throwable) {
        Trace.fault("recording open", t)
        null
    }

    /** Where a person should look, for showing on screen. */
    fun folder(): String = Environment.DIRECTORY_DCIM + "/" + FOLDER
}
