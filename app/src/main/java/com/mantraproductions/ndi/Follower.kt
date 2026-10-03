package com.mantraproductions.ndi

import android.os.Handler
import android.os.SystemClock

/**
 * THE FOLLOWER (v130): a hand on the dial that keeps looking while it turns.
 *
 * v125–v129 measured the mark once, predicted where to go, ramped there, and measured again. On the Pixel's HLG
 * picture the answer to a stop depends on how bright the spot is (0.3 in the highlights, 0.8 in the mids), so
 * any one prediction was wrong and the next round went past the mark and came back (2.10.2026 10:37:
 * −1.22 → −0.88 → +1.54 → −0.25 stops). A camera operator never does that: he turns and watches.
 *
 * So: several times a second the mark is read and the GOAL moves towards it (most of the error, from where
 * the control was when that picture was taken, scaled by how strongly the picture has been seen to answer while
 * it moves); the control itself travels to the goal on a critically damped spring, so it starts gently, never
 * overshoots, and settles. No model of the curve is needed. Simulated on cameras answering 0.3x to 1.5x, and
 * one whose answer changes on the way: no overshoot, there in 2–3 s at a 2 s ramp.
 *
 * [FollowerCore] is the whole of it without Android, so it can be tested on a pretend camera.
 */
class FollowerCore(
    start: DoubleArray,
    rampMs: Long,
    private val gain: Double,
    private val tolerance: Double,
    private val latencyMs: Long = 130L
) {
    val x = start.copyOf()
    private val v = DoubleArray(start.size)
    private val goal = start.copyOf()
    /** How quick the spring is: a critically damped move is 95% there after 4.74 / ω. */
    private val omega = 4.74 / (rampMs.coerceAtLeast(200L) / 1000.0)
    private val history = ArrayDeque<Pair<Long, DoubleArray>>()
    private var settledReads = 0
    /** How strongly the picture answers the control, per channel, measured as it moves (starts at 1). */
    private val response = DoubleArray(start.size) { 1.0 }
    private var lastThen: DoubleArray? = null
    private var lastError: DoubleArray? = null

    /** One frame: the control moves towards the goal. Returns where it is now. */
    fun frame(nowMs: Long, dtS: Double): DoubleArray {
        for (i in x.indices) {
            val (nx, nv) = Mechanism.springStep(x[i], v[i], goal[i], dtS, omega)
            x[i] = nx; v[i] = nv
        }
        history.addLast(nowMs to x.copyOf())
        while (history.size > 2 && history.first().first < nowMs - 2000) history.removeFirst()
        return x
    }

    /**
     * A reading of the mark at [nowMs]: [error] per channel in the control's own units, the move that would
     * cure it (positive = up). The picture shows the control as it was [latencyMs] ago; the goal is set from
     * there. Returns true once it has been inside the tolerance for three readings and the control is at rest.
     */
    fun reading(nowMs: Long, error: DoubleArray): Boolean {
        val then = history.lastOrNull { it.first <= nowMs - latencyMs }?.second ?: history.firstOrNull()?.second ?: x
        var inside = true
        val pt = lastThen
        val pe = lastError
        for (i in x.indices) {
            // the picture's answer, from how far the error moved for how far the control moved
            if (pt != null && pe != null) {
                val dx = then[i] - pt[i]
                if (kotlin.math.abs(dx) > MIN_MOVE) {
                    val r = (-(error[i] - pe[i]) / dx).coerceIn(MIN_RESPONSE, MAX_RESPONSE)
                    response[i] = 0.5 * response[i] + 0.5 * r
                }
            }
            if (kotlin.math.abs(error[i]) > tolerance) inside = false
            goal[i] = then[i] + gain * error[i] / response[i]
        }
        lastThen = then.copyOf()
        lastError = error.copyOf()
        settledReads = if (inside) settledReads + 1 else 0
        val resting = v.all { kotlin.math.abs(it) < tolerance * 2 }
        return settledReads >= 3 && resting
    }
}

private const val MIN_MOVE = 0.03
private const val MIN_RESPONSE = 0.1
private const val MAX_RESPONSE = 2.0

/**
 * The follower on the main thread: a frame every 33 ms ([apply] the control), a reading every 100 ms ([read]
 * the mark: the error, or null when it cannot be read), [done] when settled (true) or out of time (false).
 */
class Follower(private val handler: Handler) {
    private var ticket = 0

    fun run(
        start: DoubleArray, rampMs: Long, gain: Double, tolerance: Double, maxMs: Long,
        apply: (DoubleArray) -> Boolean, read: () -> DoubleArray?, done: (Boolean) -> Unit
    ) {
        val mine = ++ticket
        val core = FollowerCore(start, rampMs, gain, tolerance)
        val started = SystemClock.uptimeMillis()
        var frames = 0
        handler.post(object : Runnable {
            override fun run() {
                if (ticket != mine) return
                val now = SystemClock.uptimeMillis()
                if (!apply(core.frame(now, FRAME_MS / 1000.0))) { ticket++; done(false); return }
                if (++frames % READ_EVERY == 0) {
                    val error = read()
                    if (error != null && core.reading(now, error)) { ticket++; done(true); return }
                }
                if (now - started > maxMs) { ticket++; done(false); return }
                handler.postDelayed(this, FRAME_MS)
            }
        })
    }

    fun cancel() { ticket++ }

    private companion object {
        const val FRAME_MS = 33L
        const val READ_EVERY = 3
    }
}
