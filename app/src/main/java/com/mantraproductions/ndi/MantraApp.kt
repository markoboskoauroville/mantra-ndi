package com.mantraproductions.ndi

import android.app.Application

/**
 * Runs before any screen does, which is the only place capability detection
 * belongs. Deciding what the phone can do while a camera is already opening
 * is how an eight bit phone ended up down a ten bit path.
 */
class MantraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        DeviceProfile.detect(this)
    }
}
