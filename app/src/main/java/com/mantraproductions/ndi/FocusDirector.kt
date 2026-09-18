package com.mantraproductions.ndi

import android.os.Handler
import android.os.Looper

/**
 * How focus behaves, as opposed to how it is set.
 *
 * Continuous autofocus on a phone hunts: it re-evaluates constantly and snaps
 * whenever it changes its mind, which is fine for a photograph and ruinous in
 * a shot. A focus puller does not do that. They check, and if it is off they
 * move to the new mark over a beat.
 *
 * So automatic here means: look every two seconds, and when the answer has
 * genuinely changed, ramp to it rather than jump. Manual means the box is a
 * trigger, focusing once where it sits and then holding, which is what locking
 * onto a subject actually is.
 */
class FocusDirector(
    private val controls: () -> ProControls?,
    private val onState: (FocusSquareView.State) -> Unit
) {
    enum class Mode { MANUAL, AUTO }

    private val handler = Handler(Looper.getMainLooper())
    private var checking = false

    var mode: Mode = Mode.MANUAL
        private set

    /** Where the box is, as fractions of the frame. */
    var target: Pair<Float, Float> = 0.5f to 0.5f

    fun setMode(next: Mode) {
        if (mode == next) return
        mode = next
        handler.removeCallbacks(patrol)
        when (next) {
            Mode.MANUAL -> {
                // Whatever it is on now, it stays on now.
                controls()?.lockFocusHere()
                onState(FocusSquareView.State.LOCKED)
            }
            Mode.AUTO -> {
                checkNow()
                handler.postDelayed(patrol, INTERVAL_MS)
            }
        }
    }

    /** The box was tapped. In manual that means focus here, then hold. */
    fun focusHereAndHold() {
        val c = controls() ?: return
        if (checking) return
        checking = true
        onState(FocusSquareView.State.SEEKING)
        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            checking = false
            if (focused) {
                // Frozen, so nothing hunts through the take.
                c.lockFocusHere()
                onState(FocusSquareView.State.LOCKED)
            } else {
                onState(FocusSquareView.State.FAILED)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(patrol)
    }

    /**
     * Every two seconds, not every frame. A check that finds nothing changed
     * costs one capture request and the image never moves; a check that finds
     * something changed hands over to the camera's own ramp rather than a cut.
     */
    private val patrol = object : Runnable {
        override fun run() {
            if (mode == Mode.AUTO) {
                checkNow()
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
    }

    private fun checkNow() {
        val c = controls() ?: return
        if (checking) return
        checking = true
        onState(FocusSquareView.State.SEEKING)
        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            checking = false
            onState(
                if (focused) FocusSquareView.State.LOCKED else FocusSquareView.State.IDLE
            )
        }
    }

    private companion object {
        const val INTERVAL_MS = 2000L
    }
}
