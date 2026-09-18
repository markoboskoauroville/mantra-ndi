package com.mantraproductions.ndi

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies camera settings while a finger is still moving.
 *
 * Two things have to be true at once and they pull against each other. The
 * image must react as the fader moves, or the control feels broken; and the
 * camera must not be handed a new capture request per touch event, because a
 * finger generates well over a hundred a second and each request rebuilds the
 * repeating request on the camera thread.
 *
 * So this coalesces: the fader writes the value it wants, and at most once
 * every [intervalMs] the latest value is sent. Nothing queues, nothing backs
 * up. A drag of two hundred events becomes six or seven capture requests,
 * which is roughly the rate the sensor can act on anyway, since exposure
 * changes take two or three frames to appear.
 *
 * Everything happens off the main thread, so a slow camera call never stalls
 * the fader being drawn.
 */
class ControlPump(private val intervalMs: Long = 33) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** The values the operator is asking for right now. Latest always wins. */
    @Volatile private var wantedIso: Int? = null
    @Volatile private var wantedShutterNs: Long? = null
    @Volatile private var wantedKelvin: Int? = null
    @Volatile private var wantedZoom: Float? = null

    private val scheduled = AtomicBoolean(false)

    /** Set by the activity; called on the pump thread. */
    var applyExposure: ((iso: Int, shutterNs: Long) -> Unit)? = null
    var applyWhiteBalance: ((kelvin: Int) -> Unit)? = null
    var applyZoom: ((zoom: Float) -> Unit)? = null

    fun start() {
        if (thread != null) return
        val t = HandlerThread("camera-control").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        scheduled.set(false)
    }

    fun setExposure(iso: Int, shutterNs: Long) {
        wantedIso = iso
        wantedShutterNs = shutterNs
        schedule()
    }

    fun setKelvin(kelvin: Int) {
        wantedKelvin = kelvin
        schedule()
    }

    fun setZoom(zoom: Float) {
        wantedZoom = zoom
        schedule()
    }

    /** On finger lift, send once more immediately so the final value is exact. */
    fun flush() {
        handler?.post { drain() }
    }

    private fun schedule() {
        // compareAndSet means a hundred calls during one interval cost one post.
        if (scheduled.compareAndSet(false, true)) {
            handler?.postDelayed({
                scheduled.set(false)
                drain()
            }, intervalMs)
        }
    }

    private fun drain() {
        val iso = wantedIso
        val shutter = wantedShutterNs
        if (iso != null && shutter != null) {
            applyExposure?.invoke(iso, shutter)
        }
        wantedKelvin?.let { applyWhiteBalance?.invoke(it) }
        wantedZoom?.let { applyZoom?.invoke(it) }
    }
}
