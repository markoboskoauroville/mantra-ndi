package com.mantraproductions.ndi

import android.app.Application

/**
 * The first of the app's own code to run.
 *
 * Order matters and is the whole job of this class. The elapsed clock is
 * marked, then the file is opened, then the crash handler is installed, then
 * the header is written. Anything that fails after this point has somewhere to
 * be written down; anything that fails before it is the operating system's,
 * not ours.
 */
class MantraApp : Application() {

    override fun onCreate() {
        Trace.markStart()
        Trace.open(this)
        CrashLog.install(this)
        super.onCreate()

        for ((k, v) in Trace.header()) Trace.state("$k = $v")
        if (Trace.onPrivateStorage()) {
            Trace.state("external files dir unavailable, trace is in private storage")
        }
        Trace.step("application created")
    }
}
