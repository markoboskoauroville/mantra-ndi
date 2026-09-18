package com.mantraproductions.ndi

import com.pedro.encoder.input.audio.CustomAudioEffect

/**
 * Taps microphone PCM on its way to the encoder and reports RMS, without
 * altering a single sample. RootEncoder hands every buffer to a
 * CustomAudioEffect before encoding, which makes it the cheapest honest place
 * to meter: this is the audio actually being sent, not a second capture that
 * might disagree with it.
 */
class VuTap(private val onLevel: (level: Float, isInput: Boolean) -> Unit) : CustomAudioEffect() {

    /** Unity until the operator moves the gain fader. */
    @Volatile var gain: Float = 1f

    /**
     * Metered twice, on purpose.
     *
     * The hairline across the top shows what arrives from the microphone, and
     * the meter inside the gain fader shows what leaves for the encoder. That
     * is the whole point of having both: one says whether the mic is hearing
     * anything, the other says whether what is being recorded is clipping, and
     * between them sits the fader that is being moved. Showing the same number
     * in both places made the second one furniture.
     */
    override fun process(pcmBuffer: ByteArray): ByteArray {
        onLevel(Mechanism.rmsOfPcm16(pcmBuffer), true)
        Mechanism.applyGainPcm16(pcmBuffer, gain)
        onLevel(Mechanism.rmsOfPcm16(pcmBuffer), false)
        return pcmBuffer
    }
}
