package com.mantraproductions.ndi

import android.os.Handler
import android.os.Looper

/**
 * How focus behaves, as opposed to how it is set.
 *
 * Continuous autofocus hunts: it re-evaluates constantly and snaps whenever it
 * changes its mind. That is right for a photograph and ruinous in a shot,
 * because the snap is visible and the hunting is visible and neither is
 * something a camera operator would ever do.
 *
 * A focus puller holds, notices, then moves over a beat. So:
 *
 *     hold for the hold time, measuring sharpness but touching nothing
 *     if it has genuinely drifted, rack to the new mark over the rack time
 *     hold again
 *
 * The lens is driven here rather than handed to the camera's own routine,
 * because the camera's routine cannot be asked to take two seconds about it.
 * Sharpness comes from the preview, the same frames the scope and the waveform
 * read, so it costs nothing extra.
 *
 * Both times are settings. Two seconds is a considered move, one is brisk, and
 * zero is a snap for anybody who wants the phone to behave like a phone.
 */
class FocusDirector(
    private val controls: () -> CaptureEngine?,
    private val onState: (FocusSquareView.State) -> Unit
) {
    enum class Mode { MANUAL, AUTO }

    private val handler = Handler(Looper.getMainLooper())

    var mode: Mode = Mode.MANUAL
        private set

    /** Where the box is, as fractions of the frame. */
    var target: Pair<Float, Float> = 0.5f to 0.5f

    /** The box, for measuring sharpness only inside it. */
    var bounds: FloatArray = floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f)

    /** Set by the screen each time it samples the preview. */
    @Volatile var currentSharpness: Double = 0.0

    var holdMs: Long = 2000
    var rampMs: Long = 2000

    private var reference = 0.0
    private var lensPosition = 0f
    private var racking = false
    private var searching = false

    fun setMode(next: Mode) {
        if (mode == next) return
        mode = next
        handler.removeCallbacksAndMessages(null)
        racking = false
        when (next) {
            Mode.MANUAL -> {
                controls()?.lockFocusHere()
                onState(FocusSquareView.State.LOCKED)
            }
            Mode.AUTO -> {
                focusNowThenHold()
            }
        }
    }

    /** The box was tapped. In either mode that means focus here, then hold. */
    fun focusHereAndHold() {
        val c = controls() ?: return
        if (searching) return
        searching = true
        onState(FocusSquareView.State.SEEKING)
        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            searching = false
            if (focused) {
                c.lockFocusHere()
                lensPosition = c.lastFocusDistance ?: lensPosition
                reference = currentSharpness
                onState(FocusSquareView.State.LOCKED)
            } else {
                onState(FocusSquareView.State.FAILED)
            }
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        racking = false
    }

    private fun focusNowThenHold() {
        focusHereAndHold()
        handler.postDelayed({ if (mode == Mode.AUTO) watch() }, holdMs.coerceAtLeast(200))
    }

    /**
     * The held part. Nothing is touched unless sharpness has actually fallen,
     * so a static shot never moves, which is the whole complaint about
     * continuous autofocus.
     */
    private fun watch() {
        if (mode != Mode.AUTO || racking) return

        if (Mechanism.focusHasDrifted(reference, currentSharpness)) {
            onState(FocusSquareView.State.SEEKING)
            rackToNewMark()
        } else {
            // Sharpness creeps up as light changes; keep the reference honest
            // so the next comparison is against the best it has actually been.
            if (currentSharpness > reference) reference = currentSharpness
            handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
        }
    }

    /**
     * Finds the new mark, then travels to it over the rack time.
     *
     * The camera is asked where focus should be, which is one quick search,
     * and then the lens is put back and walked there instead of left where the
     * search dropped it. At a rack time of zero that walk is a single step,
     * which is the snap somebody asked for.
     */
    private fun rackToNewMark() {
        val c = controls() ?: return
        if (searching) return
        searching = true
        val from = c.lastFocusDistance ?: lensPosition

        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            searching = false
            val to = c.lastFocusDistance ?: from
            if (!focused) {
                onState(FocusSquareView.State.IDLE)
                handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
                return@focusAtNormalisedPoint
            }

            if (rampMs <= 0L || from == to) {
                c.setFocusDistance(to)
                settle(to)
                return@focusAtNormalisedPoint
            }

            // Back to where it was, then walk.
            c.setFocusDistance(from)
            racking = true
            val started = System.currentTimeMillis()
            val step = object : Runnable {
                override fun run() {
                    if (mode != Mode.AUTO) { racking = false; return }
                    val progress = (System.currentTimeMillis() - started).toFloat() / rampMs
                    c.setFocusDistance(Mechanism.rackPosition(from, to, progress))
                    if (progress >= 1f) {
                        racking = false
                        settle(to)
                    } else {
                        handler.postDelayed(this, 33)
                    }
                }
            }
            handler.post(step)
        }
    }

    private fun settle(distance: Float) {
        lensPosition = distance
        reference = currentSharpness
        onState(FocusSquareView.State.LOCKED)
        handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
    }
}
