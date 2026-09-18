package com.mantraproductions.ndi

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.sqrt

/**
 * Taps microphone PCM on its way to the encoder and reports RMS, without
 * altering a single sample. RootEncoder hands every buffer to a
 * CustomAudioEffect before encoding, which makes it the cheapest honest place
 * to meter: this is the audio actually being sent, not a second capture that
 * might disagree with it.
 */
class VuTap(private val onLevel: (Float) -> Unit) : CustomAudioEffect() {

    override fun process(pcmBuffer: ByteArray): ByteArray {
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        // 16 bit little endian. Step by 4 rather than 2: metering every other
        // sample is plenty for a meter and halves the work on the audio thread.
        while (i + 1 < pcmBuffer.size) {
            val value = ((pcmBuffer[i + 1].toInt() shl 8) or (pcmBuffer[i].toInt() and 0xFF)).toShort()
            val normalised = value / 32768.0
            sumSquares += normalised * normalised
            samples++
            i += 4
        }
        if (samples > 0) {
            onLevel(sqrt(sumSquares / samples).toFloat())
        }
        return pcmBuffer
    }
}
