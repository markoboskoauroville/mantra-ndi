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

    /**
     * DCIM/Mantra Manual Camera: takes and stills together.
     *
     * The app's name since v83. Takes made before it stay in DCIM/Mantra NDI.
     */
    private const val FOLDER = "Mantra Manual Camera"

    /** An open recording: where it is going, and what to do when it is done. */
    class Take(
        val name: String,
        val where: String,
        private val descriptor: ParcelFileDescriptor,
        /** What PLAY opens afterwards: a content URI, or a file path before Android 10. */
        val uri: Uri? = null,
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
    fun open(context: Context, name: String = name(), folder: String? = null): Take? = try {
        val tree = folder?.let { Uri.parse(it) }
        if (tree != null) {
            openInTree(context, tree, name, "video/mp4")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                        descriptor = pfd,
                        uri = uri
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
            Take(name, file.absolutePath, pfd, Uri.fromFile(file)) { keep -> if (!keep) file.delete() }
        }
    } catch (t: Throwable) {
        Trace.fault("recording open", t)
        null
    }

    /** Where a person should look, for showing on screen. */
    fun folder(): String = Environment.DIRECTORY_DCIM + "/" + FOLDER

    /**
     * A still of the picture as a PNG, in the same folder as the takes.
     *
     * *"The file should be a PNG of the current screen inside the same folder
     * where the video is."* The SNAP key wrote a DNG through the RAW target to
     * a place nobody could find. It is the picture now, lossless, beside the
     * takes, with the take's own naming so the two sort together.
     *
     * @return where it went, or null with the reason traced
     */
    fun savePng(
        context: Context, bitmap: android.graphics.Bitmap, at: Date = Date(), folder: String? = null
    ): String? = try {
        val name = "mantra-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(at) + ".png"
        val tree = folder?.let { Uri.parse(it) }
        if (tree != null) {
            val resolver = context.contentResolver
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
            )
            val doc = android.provider.DocumentsContract.createDocument(resolver, parent, "image/png", name)
            val ok = doc != null && resolver.openOutputStream(doc)?.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } == true
            if (ok) describe(context, tree) + "/" + name
            else { Trace.refused("still", "the chosen folder would not take the PNG"); null }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: run { Trace.refused("still", "MediaStore would not make an entry"); return null }
            val ok = resolver.openOutputStream(uri)?.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } == true
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                Trace.refused("still", "the PNG could not be written")
                null
            } else {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                Environment.DIRECTORY_DCIM + "/" + FOLDER + "/" + name
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER
            ).apply { mkdirs() }
            File(dir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            Environment.DIRECTORY_DCIM + "/" + FOLDER + "/" + name
        }
    } catch (t: Throwable) {
        Trace.fault("still", t)
        null
    }

    // --- a folder he chose: any drive, a USB SSD included (v85) -------------

    /**
     * A new file in a folder chosen through the system's folder picker.
     *
     * *"User can choose also an external drive. The recording folder is
     * configurable."* The picker hands back a tree the app keeps a lasting
     * permission for; a file is made in it and opened read-write, because a
     * muxer seeks back to the start to write the index.
     */
    private fun openInTree(context: Context, tree: Uri, name: String, mime: String): Take? {
        val resolver = context.contentResolver
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
        val doc = android.provider.DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: run { Trace.refused("recording", "the chosen folder would not take a new file"); return null }
        val pfd = resolver.openFileDescriptor(doc, "rw")
            ?: run {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, doc) }
                Trace.refused("recording", "no descriptor in the chosen folder")
                return null
            }
        return Take(name, describe(context, tree) + "/" + name, pfd, doc) { keep ->
            if (!keep) runCatching { android.provider.DocumentsContract.deleteDocument(resolver, doc) }
        }
    }

    /** A chosen folder as a person reads it: "USB drive/Takes", "Phone/DCIM/Shoot". */
    fun describe(context: Context, tree: Uri): String {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return tree.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val where = if (volume == "primary") "Phone" else volumeName(context, volume) ?: "Drive $volume"
        return if (path.isEmpty()) where else "$where/$path"
    }

    private fun volumeName(context: Context, uuid: String): String? {
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java) ?: return null
        return sm.storageVolumes.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?.getDescription(context)
    }

    /** Where the takes go, as a person reads it. */
    fun where(context: Context, folder: String?): String =
        folder?.let { describe(context, Uri.parse(it)) } ?: folder()

    /**
     * Free bytes on the drive the takes go to, or null if it cannot be read
     * (a drive unplugged, a folder whose permission was taken back).
     */
    fun freeBytes(context: Context, folder: String?): Long? = try {
        if (folder == null) {
            android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } else {
            val id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(folder))
            val volume = id.substringBefore(':')
            val dir: File? = if (volume == "primary") {
                Environment.getExternalStorageDirectory()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.getSystemService(android.os.storage.StorageManager::class.java)
                    ?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }
                    ?.directory
            } else {
                File("/storage/$volume")
            }
            dir?.takeIf { it.exists() }?.let { android.os.StatFs(it.path).availableBytes }
        }
    } catch (t: Throwable) {
        null
    }
}
