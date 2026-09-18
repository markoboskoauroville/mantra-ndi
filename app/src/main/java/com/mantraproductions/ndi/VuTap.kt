package com.mantraproductions.ndi

import com.pedro.encoder.input.audio.CustomAudioEffect

/**
 * Taps microphone PCM on its way to the encoder and reports RMS, without
 * altering a single sample. RootEncoder hands every buffer to a
 * CustomAudioEffect before encoding, which makes it the cheapest honest place
 * to meter: this is the audio actually being sent, not a second capture that
 * might disagree with it.
 */
class VuTap(private val onLevel: (Float) -> Unit) : CustomAudioEffect() {

    /** Unity until the operator moves the gain fader. */
    @Volatile var gain: Float = 1f

    override fun process(pcmBuffer: ByteArray): ByteArray {
        // Gain first, then meter, so the number on screen is the level being
        // encoded rather than the level before the adjustment.
        Mechanism.applyGainPcm16(pcmBuffer, gain)
        onLevel(Mechanism.rmsOfPcm16(pcmBuffer))
        return pcmBuffer
    }
}
