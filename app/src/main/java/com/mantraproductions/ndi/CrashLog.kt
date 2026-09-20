package com.mantraproductions.ndi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evidence that survives the app dying.
 *
 * Two faults made this useless exactly when it mattered. It wrote with a raw
 * File path into public DCIM, which Android has forbidden since version ten, so
 * no crash file was ever produced and the silence looked like "no crash was
 * recorded" rather than "the recorder cannot write". And it only caught Java
 * exceptions, so a fault in the NDI native code took the process down without
 * leaving anything at all.
 *
 * So: breadcrumbs written to disk **as they happen**, in the app's own external
 * directory which needs no permission and cannot be refused, plus a full report
 * into Downloads through MediaStore, which is the only route a modern Android
 * allows into a folder a person can actually open.
 *
 * Breadcrumbs matter more than the report here. A native crash never reaches a
 * Java handler, and the last line written before the silence says where it
 * happened just as well as a stack trace would.
 */
object CrashLog {

    private const val TRACE_FILE = "mantra_trace.txt"
    private var appContext: Context? = null
    private val recent = ArrayDeque<String>()

    fun install(context: Context) {
        appContext = context.applicationContext

        // A fresh trace each launch, or the file grows forever and the
        // interesting part is buried under yesterday.
        runCatching {
            traceFile()?.writeText(
                "Mantra NDI v" + BuildConfig.VERSION_NAME + "\n" +
                    Build.MANUFACTURER + " " + Build.MODEL +
                    ", Android " + Build.VERSION.RELEASE +
                    " (API " + Build.VERSION.SDK_INT + ")\n" +
                    "started " + stamp() + "\n\n"
            )
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * One step of the startup, written immediately.
     *
     * Immediately, not buffered, because the whole point is to survive a
     * process that is about to stop existing. Each line is short and there are
     * a few dozen of them, so the cost is nothing next to what it answers.
     */
    fun trace(step: String) {
        val line = stamp() + "  " + step
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > 60) recent.removeFirst()
        }
        runCatching { traceFile()?.appendText(line + "\n") }
    }

    /** Where the breadcrumbs are, so it can be said out loud rather than guessed. */
    fun traceLocation(): String =
        traceFile()?.absolutePath ?: "unavailable"

    private fun traceFile(): File? {
        val context = appContext ?: return null
        // The app's own external directory: always writable, never refused,
        // and reachable with any file manager.
        val dir = context.getExternalFilesDir(null) ?: return null
        if (!dir.exists()) dir.mkdirs()
        return File(dir, TRACE_FILE)
    }

    private fun record(thread: Thread, error: Throwable) {
        val context = appContext ?: return

        val report = buildString {
            append("Mantra NDI v").append(BuildConfig.VERSION_NAME).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("thread: ").append(thread.name).append('\n')
            append("when: ").append(stamp()).append("\n\n")

            append("LAST STEPS BEFORE THE FAULT\n")
            synchronized(recent) { recent.forEach { append("  ").append(it).append('\n') } }

            append("\nSTACK\n")
            append(java.io.StringWriter().also {
                error.printStackTrace(java.io.PrintWriter(it))
            }.toString())
        }

        runCatching { traceFile()?.appendText("\nCRASH\n" + report) }
        runCatching { toDownloads(context, "MantraNDI_crash_" + fileStamp() + ".txt", report) }
    }

    /**
     * Into Downloads, which on Android ten and later means MediaStore.
     *
     * A raw File path to a public folder is refused, and it is refused
     * silently, which is how a crash reporter ends up reporting nothing for
     * months without anybody noticing.
     */
    fun toDownloads(context: Context, name: String, content: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null
            else {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Downloads/$name"
            }
        } else {
            val dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(content)
            "Downloads/$name"
        }
    } catch (e: Throwable) {
        null
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
