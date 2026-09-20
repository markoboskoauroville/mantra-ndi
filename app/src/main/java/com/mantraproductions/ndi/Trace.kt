package com.mantraproductions.ndi

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.TimeZone

/**
 * What the app was doing, written down while it does it.
 *
 * This is phase zero of the rebuild and it is built before the camera on
 * purpose. The previous attempt ran ten versions without a working log, and
 * every one of those versions was a guess that cost an hour of testing on the
 * phone. Rotation and white balance took six attempts each for the same
 * reason: the app was built before there was any way of seeing what it was
 * doing.
 *
 * Four decisions here, each of which is the fix for something that went wrong.
 *
 * The file goes in getExternalFilesDir. That needs no permission, cannot be
 * refused by the person or the system, and lands somewhere reachable in any
 * file manager under Android/data/com.mantraproductions.ndi/files.
 *
 * Nothing is buffered. A BufferedWriter holds the last few kilobytes in memory
 * and those are exactly the lines that say what the app was doing when it
 * died. Every line is a write straight to the descriptor; the kernel keeps it
 * even if the process is killed a microsecond later. It costs one syscall per
 * line, which is affordable for startup steps and for a fader being dragged,
 * and is NOT affordable per video frame. Nothing on the camera callback thread
 * may call this.
 *
 * Nothing here may throw. A logger that can crash the app turns every fault
 * into two faults and hides the first. Every entry point catches Throwable and
 * gives up quietly.
 *
 * Lines written before the file is open are kept and flushed into it. The
 * earliest steps are the ones worth having, and they happen before there is a
 * Context to ask for a directory.
 */
object Trace {

    /** How many lines the on-screen panel and a crash report can look back over. */
    private const val RING = 400

    /** How many runs of the app keep a file. Older ones are deleted at startup. */
    private const val KEEP_RUNS = 12

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val pending = ArrayList<String>()

    private var stream: FileOutputStream? = null
    private var file: File? = null
    private var written: Long = 0
    private var baseElapsed: Long = SystemClock.elapsedRealtime()
    private var zone: TimeZone = TimeZone.getDefault()
    private var fellBackToPrivateStorage = false

    /**
     * Opens the file for this run and flushes everything said so far into it.
     * Safe to call twice; the second call does nothing.
     */
    fun open(context: Context) {
        synchronized(lock) {
            if (stream != null) return
            try {
                zone = TimeZone.getDefault()
                val now = System.currentTimeMillis()

                // getExternalFilesDir can answer null when external storage is
                // not mounted. Falling back keeps the trace rather than losing
                // it, but the panel has to say so, because somebody looking
                // under Android/data would find an empty folder and conclude
                // the logging was broken again.
                val dir = context.getExternalFilesDir(null)
                    ?: context.filesDir.also { fellBackToPrivateStorage = true }
                dir.mkdirs()

                val f = File(dir, TraceFormat.traceName(now, offsetAt(now)))
                val s = FileOutputStream(f, true)
                file = f
                stream = s
                written = f.length()

                for (line in pending) writeLine(s, line)
                pending.clear()

                sweep(dir)
            } catch (t: Throwable) {
                // No file. The ring still fills, so the on-screen panel works
                // even when the storage does not.
                stream = null
            }
        }
    }

    /** The base for every elapsed figure. Called once, as early as anything runs. */
    fun markStart() {
        baseElapsed = SystemClock.elapsedRealtime()
    }

    // --- the things worth writing down -------------------------------------

    /** A step of starting up, shutting down, or changing mode. */
    fun step(text: String) = put(TraceFormat.STEP, text)

    /** Something the app now believes to be true, with the value it believes. */
    fun state(text: String) = put(TraceFormat.STATE, text)

    /**
     * An operator moved something. Both numbers, always: what was asked for
     * and what actually took effect. The gap between those two is where the
     * last build's white balance and rotation bugs lived, invisibly.
     */
    fun control(name: String, requested: Any?, applied: Any?, note: String = "") =
        put(TraceFormat.CONTROL, TraceFormat.control(name, show(requested), show(applied), note))

    /** A request the hardware or the system turned down, and the reason it gave. */
    fun refused(request: String, reason: String) =
        put(TraceFormat.REFUSED, TraceFormat.refused(request, reason))

    /** A throwable the app survived. The ones it does not survive go through CrashLog. */
    fun fault(what: String, t: Throwable?) {
        put(TraceFormat.FAULT, what + if (t != null) "  " + describe(t) else "")
        if (t != null) put(TraceFormat.FAULT, stackOf(t).trimEnd())
    }

    // --- what the panel and the crash report read --------------------------

    fun lines(): List<String> = synchronized(lock) { ring.toList() }

    fun file(): File? = synchronized(lock) { file }

    fun bytes(): Long = synchronized(lock) { written }

    fun lineCount(): Int = synchronized(lock) { ring.size }

    fun onPrivateStorage(): Boolean = synchronized(lock) { fellBackToPrivateStorage }

    fun offsetAt(millis: Long): Int = zone.getOffset(millis) / 60_000

    /** A throwable turned into text, for the report. Kept here so the format stays pure. */
    fun stackOf(t: Throwable): String {
        val w = StringWriter()
        t.printStackTrace(PrintWriter(w))
        return w.toString()
    }

    fun describe(t: Throwable): String =
        t.javaClass.name + (t.message?.let { ": " + TraceFormat.truncate(it, 160) } ?: "")

    /** The header block at the top of every trace file and every crash report. */
    fun header(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        return listOf(
            "version" to BuildConfig.VERSION_NAME,
            "when" to TraceFormat.stamp(now, offsetAt(now)),
            "phone" to (Build.MANUFACTURER + " " + Build.MODEL),
            "android" to (Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"),
            "build" to Build.DISPLAY,
            "abi" to Build.SUPPORTED_ABIS.joinToString(","),
            "ndi sdk" to if (BuildConfig.NDI_SDK_PRESENT) "present" else "absent"
        )
    }

    // --- the writing itself ------------------------------------------------

    private fun put(tag: String, text: String) {
        try {
            val now = System.currentTimeMillis()
            val line = TraceFormat.line(
                now, offsetAt(now), SystemClock.elapsedRealtime() - baseElapsed, tag, text
            )
            synchronized(lock) {
                ring.addLast(line)
                while (ring.size > RING) ring.removeFirst()
                val s = stream
                if (s == null) {
                    if (pending.size < RING) pending.add(line)
                } else {
                    writeLine(s, line)
                }
            }
        } catch (t: Throwable) {
            // A logger may not be the reason an app fails.
        }
    }

    private fun writeLine(s: FileOutputStream, line: String) {
        try {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            s.write(bytes)
            written += bytes.size
        } catch (t: Throwable) {
            stream = null
        }
    }

    private fun show(v: Any?): String = TraceFormat.truncate(v?.toString() ?: "null", 40)

    /**
     * Keeps the newest few runs and deletes the rest, so the folder stays
     * readable on a phone. A run that has just crashed is the newest, so
     * nothing that matters is ever the one swept away.
     */
    private fun sweep(dir: File) {
        try {
            val traces = dir.listFiles { f -> f.name.startsWith("trace-") && f.name.endsWith(".txt") }
                ?: return
            if (traces.size <= KEEP_RUNS) return
            traces.sortBy { it.name }
            for (i in 0 until traces.size - KEEP_RUNS) traces[i].delete()
        } catch (t: Throwable) {
            // Housekeeping is not worth a fault.
        }
    }
}
