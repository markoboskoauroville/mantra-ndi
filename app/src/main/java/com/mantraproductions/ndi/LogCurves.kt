package com.mantraproductions.ndi

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow

/**
 * Log transfer functions, as published by the manufacturers.
 *
 * Nothing here is stored as data. Each curve is a handful of constants and one
 * piecewise equation, which is why the whole file is smaller than a single
 * LUT: a 33 cube LUT is 35,937 triplets, about 1.5 MB of text, and every one
 * of those numbers is derivable from about ten constants. Generating beats
 * shipping, and it also means a LUT can be produced at any size on demand.
 *
 * Every curve is a pair. Encoding takes relative scene linear and returns the
 * normalised log signal a recorder stores. Decoding is its exact inverse, and
 * the test suite holds each one to a round trip, because a curve whose inverse
 * is approximate produces a LUT that does not undo it.
 *
 * What this gives is the transfer function only, not the colour gamut. S-Log3
 * proper also carries S-Gamut3 primaries, and no phone sensor has those. The
 * curve is the part that buys dynamic range and the part a LUT can undo, so
 * the curve is what is offered, and it is named honestly in the UI.
 */
object LogCurves {

    enum class Curve(val displayName: String, val vendor: String) {
        REC709("Rec.709", "Standard"),
        SLOG3("S-Log3", "Sony"),
        VLOG("V-Log", "Panasonic"),
        LOGC3("LogC3", "ARRI"),
        LOGC4("LogC4", "ARRI"),
        BMFILM("Film Gen5", "Blackmagic")
    }

    /** Relative scene linear to normalised log signal, 0..1 both ends. */
    fun encode(curve: Curve, linear: Double): Double = when (curve) {
        Curve.REC709 -> rec709Encode(linear)
        Curve.SLOG3 -> slog3Encode(linear)
        Curve.VLOG -> vlogEncode(linear)
        Curve.LOGC3 -> logc3Encode(linear)
        Curve.LOGC4 -> logc4Encode(linear)
        Curve.BMFILM -> bmFilmEncode(linear)
    }

    /** The exact inverse of [encode]. */
    fun decode(curve: Curve, signal: Double): Double = when (curve) {
        Curve.REC709 -> rec709Decode(signal)
        Curve.SLOG3 -> slog3Decode(signal)
        Curve.VLOG -> vlogDecode(signal)
        Curve.LOGC3 -> logc3Decode(signal)
        Curve.LOGC4 -> logc4Decode(signal)
        Curve.BMFILM -> bmFilmDecode(signal)
    }

    // --- Rec.709, the display standard everything is graded back to ----------

    private fun rec709Encode(x: Double): Double = when {
        x < 0.018 -> 4.5 * x
        else -> 1.099 * x.pow(0.45) - 0.099
    }

    private fun rec709Decode(y: Double): Double = when {
        y < 0.081 -> y / 4.5
        else -> ((y + 0.099) / 1.099).pow(1.0 / 0.45)
    }

    // --- Sony S-Log3 ---------------------------------------------------------
    // Sony's published technical summary for S-Gamut3/S-Log3.

    private const val SLOG3_CUT = 0.01125000

    private fun slog3Encode(x: Double): Double = if (x >= SLOG3_CUT) {
        (420.0 + log10((x + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0
    } else {
        (x * (171.2102946929 - 95.0) / SLOG3_CUT + 95.0) / 1023.0
    }

    private fun slog3Decode(y: Double): Double = if (y >= 171.2102946929 / 1023.0) {
        (10.0.pow((y * 1023.0 - 420.0) / 261.5)) * (0.18 + 0.01) - 0.01
    } else {
        (y * 1023.0 - 95.0) * SLOG3_CUT / (171.2102946929 - 95.0)
    }

    // --- Panasonic V-Log -----------------------------------------------------

    private const val VLOG_CUT = 0.01
    private const val VLOG_B = 0.00873
    private const val VLOG_C = 0.241514
    private const val VLOG_D = 0.598206

    private fun vlogEncode(x: Double): Double =
        if (x < VLOG_CUT) 5.6 * x + 0.125
        else VLOG_C * log10(x + VLOG_B) + VLOG_D

    private fun vlogDecode(y: Double): Double =
        if (y < 0.181) (y - 0.125) / 5.6
        else 10.0.pow((y - VLOG_D) / VLOG_C) - VLOG_B

    // --- ARRI LogC3, EI 800 --------------------------------------------------
    // (x > cut) ? c * log10(a * x + b) + d : e * x + f

    private const val LOGC3_CUT = 0.010591
    private const val LOGC3_A = 5.555556
    private const val LOGC3_B = 0.052272
    private const val LOGC3_C = 0.247190
    private const val LOGC3_D = 0.385537
    private const val LOGC3_E = 5.367655
    private const val LOGC3_F = 0.092809

    private fun logc3Encode(x: Double): Double =
        if (x > LOGC3_CUT) LOGC3_C * log10(LOGC3_A * x + LOGC3_B) + LOGC3_D
        else LOGC3_E * x + LOGC3_F

    private fun logc3Decode(y: Double): Double =
        if (y > LOGC3_E * LOGC3_CUT + LOGC3_F) {
            (10.0.pow((y - LOGC3_D) / LOGC3_C) - LOGC3_B) / LOGC3_A
        } else {
            (y - LOGC3_F) / LOGC3_E
        }

    // --- ARRI LogC4 ----------------------------------------------------------
    // Straight from the ARRI LogC4 specification, section 4.1.1.

    private val LOGC4_A = (2.0.pow(18.0) - 16.0) / 117.45
    private val LOGC4_B = (1023.0 - 95.0) / 1023.0
    private val LOGC4_C = 95.0 / 1023.0
    private val LOGC4_S = (7.0 * ln(2.0) * 2.0.pow(7.0 - 14.0 * LOGC4_C / LOGC4_B)) /
            (LOGC4_A * LOGC4_B)
    private val LOGC4_T = (2.0.pow(14.0 * (-LOGC4_C / LOGC4_B) + 6.0) - 64.0) / LOGC4_A

    private fun logc4Encode(x: Double): Double =
        if (x < LOGC4_T) (x - LOGC4_T) / LOGC4_S
        else (log2(LOGC4_A * x + 64.0) - 6.0) / 14.0 * LOGC4_B + LOGC4_C

    private fun logc4Decode(y: Double): Double =
        if (y < 0.0) y * LOGC4_S + LOGC4_T
        else (2.0.pow(((y - LOGC4_C) / LOGC4_B) * 14.0 + 6.0) - 64.0) / LOGC4_A

    // --- Blackmagic Film -----------------------------------------------------
    // Constants recovered from Blackmagic's own LUTs by curve fitting, which is
    // the same trick this file is built on: the LUT was the only published
    // artefact, and the equation behind it is ten numbers.

    private const val BM_A = 3.4845696382315063
    private const val BM_B = 0.035388150275256276
    private const val BM_C = 0.0797443784368146
    private const val BM_D = 0.2952978430809614
    private const val BM_E = 0.781640290185019
    private const val BM_LIN_CUT = 0.005000044472991669
    private const val BM_LOG_CUT = 0.0528111534356503

    private fun bmFilmEncode(x: Double): Double =
        if (x <= BM_LIN_CUT) x * BM_A + BM_B
        else ln(x + BM_C) * BM_D + BM_E

    private fun bmFilmDecode(y: Double): Double =
        if (y <= BM_LOG_CUT) (y - BM_B) / BM_A
        else exp((y - BM_E) / BM_D) - BM_C

    // --- what the curves are for --------------------------------------------

    /**
     * The value a log curve assigns to an 18% grey card. Useful on set: it is
     * the number to expose to, and it differs per curve, which is the usual
     * reason footage comes back looking wrong.
     */
    fun middleGrey(curve: Curve): Double = encode(curve, 0.18)

    /**
     * Maps a signal in [from] to the same scene light expressed in [to].
     * Decoding to linear and re-encoding is the whole of what a conversion LUT
     * does, so this one function generates every LUT the app offers.
     */
    fun convert(from: Curve, to: Curve, signal: Double): Double =
        encode(to, decode(from, signal).coerceAtLeast(0.0)).coerceIn(0.0, 1.0)
}
