package com.mantraproductions.ndi

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * What killed the app, written down before it finishes dying.
 *
 * Two things here are the fix for two separate faults in the last build.
 *
 * The handler takes a Throwable, and everything inside it catches Throwable.
 * A rejected GL shader arrives as an Error, not an Exception, and an Error
 * walks straight past a catch on Exception as though nothing had been written
 * at all. Java's own uncaught handler hands over a Throwable for exactly this
 * reason; the mistake is narrowing it afterwards.
 *
 * The report is written in two places, in that order. First into the app's own
 * external files directory, which needs no permission and cannot be refused.
 * Then into Downloads through MediaStore, which is reachable without a file
 * manager that can see Android/data. The order matters: the guaranteed copy is
 * written before the convenient one, so a failure in the second never costs
 * the first.
 *
 * And the previous handler is always called afterwards. Swallowing the
 * throwable leaves a process with a dead UI thread and no dialogue, which
 * looks to a person like the app froze rather than crashed.
 */
object CrashLog {

    private var previous: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                report(app, thread, throwable)
            } catch (t: Throwable) {
                // Nothing left to do. The system's handler still runs below.
            }
            previous?.uncaughtException(thread, throwable)
        }
        Trace.step("crash handler installed, catching Throwable")
    }

    private fun report(context: Context, thread: Thread, throwable: Throwable) {
        Trace.fault("uncaught on thread " + thread.name, throwable)

        val now = System.currentTimeMillis()
        val name = TraceFormat.crashName(now, Trace.offsetAt(now))
        val header = ArrayList<Pair<String, String>>(Trace.header())
        header.add("thread" to thread.name)
        header.add("trace file" to (Trace.file()?.name ?: "none"))

        val text = TraceFormat.crashReport(header, Trace.stackOf(throwable), Trace.lines())

        writeBeside(context, name, text)
        Downloads.writeText(context, name, text)
    }

    /** The copy that cannot be refused: next to the trace, in the app's own directory. */
    private fun writeBeside(context: Context, name: String, text: String): File? = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val f = File(dir, name)
        FileOutputStream(f).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        }
        f
    } catch (t: Throwable) {
        null
    }

    /** Every crash report on this phone, newest first, for the panel to show. */
    fun reports(context: Context): List<File> = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        (dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?: emptyArray())
            .sortedByDescending { it.name }
    } catch (t: Throwable) {
        emptyList()
    }
}
