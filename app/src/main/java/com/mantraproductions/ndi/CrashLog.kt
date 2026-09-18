package com.mantraproductions.ndi

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the stack trace where the operator can find it.
 *
 * A crash on a phone that is not on my desk is otherwise a sentence in a chat
 * message, and a sentence is not a stack trace. This puts the real one in
 * DCIM/Mantra NDI beside the footage, so it can be opened, read or sent
 * without a cable, a computer or adb.
 *
 * It rethrows afterwards. Swallowing a crash to keep the app alive leaves it
 * running in a state nobody reasoned about, which is worse than stopping.
 */
object CrashLog {

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(context, thread, error)
            } catch (ignored: Throwable) {
                // Never let the reporter be the thing that crashes.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

        val report = buildString {
            append("Mantra NDI v").append(BuildConfig.VERSION_NAME).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("thread: ").append(thread.name).append("\n\n")
            append(trace)
        }

        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            ),
            MediaStoreOutput.FOLDER
        )
        if (!dir.exists()) dir.mkdirs()
        File(dir, "crash_$stamp.txt").writeText(report)
    }
}
