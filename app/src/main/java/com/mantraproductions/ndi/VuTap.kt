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

    override fun process(pcmBuffer: ByteArray): ByteArray {
        onLevel(Mechanism.rmsOfPcm16(pcmBuffer))
        return pcmBuffer
    }
}
