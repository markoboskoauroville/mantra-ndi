package com.mantraproductions.ndi

import android.os.Handler
import android.os.SystemClock

/**
 * THE RAMP (v125): one move of a control, eased in and out over a time, a step every frame.
 *
 * Marko, 2.10.2026: *"When the user clicks to recalibrate the camera, the change itself is not happening
 * suddenly. It emulates the analog equipment ... I can record that change."* Exposure and white balance
 * walk through this; the focus rack walks the same curve ([Mechanism.rampEase]).
 *
 * A new [run] takes over from one still going: the control is picked up where it is, as a hand would.
 * [step] gets the time's progress 0..1 and eases it itself ([Mechanism.rampLog], [Mechanism.rackPosition]);
 * it answers false if the camera refused it, which ends the move.
 */
class Ramp(private val handler: Handler) {
    private var ticket = 0

    val running: Boolean get() = active
    private var active = false

    fun run(ms: Long, step: (Float) -> Boolean, done: () -> Unit, failed: () -> Unit = {}) {
        val mine = ++ticket
        if (ms <= 0L) {
            active = false
            if (step(1f)) done() else failed()
            return
        }
        active = true
        val started = SystemClock.uptimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                if (ticket != mine) return
                val progress = (SystemClock.uptimeMillis() - started).toFloat() / ms
                if (!step(progress.coerceIn(0f, 1f))) { active = false; failed(); return }
                if (progress >= 1f) { active = false; done() } else handler.postDelayed(this, FRAME_MS)
            }
        })
    }

    fun cancel() { ticket++; active = false }

    private companion object { const val FRAME_MS = 33L }
}
