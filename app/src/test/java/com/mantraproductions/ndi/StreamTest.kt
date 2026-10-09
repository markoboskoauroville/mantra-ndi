package com.mantraproductions.ndi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** v136: the stream follows the phone, and the meter reads without copying. */
class StreamTest {

    @Test fun degreesBecomeQuarterTurns() {
        assertEquals(0, Mechanism.streamTurns(0))
        assertEquals(1, Mechanism.streamTurns(90))
        assertEquals(2, Mechanism.streamTurns(180))
        assertEquals(3, Mechanism.streamTurns(270))
        assertEquals(0, Mechanism.streamTurns(360))
    }

    /** The texture corner (u, v) shown at an output corner: BL is vertex 0, BR 1, TL 2, TR 3. */
    private fun uvAt(quad: FloatArray, vertex: Int) = floatArrayOf(quad[vertex * 4 + 2], quad[vertex * 4 + 3])

    @Test fun noTurnIsTheOldQuad() {
        // The quad GpuStage drew before v136: BL (0,1), BR (1,1), TL (0,0), TR (1,0)
        assertArrayEquals(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f),
            Mechanism.turnedQuad(0), 0f)
    }

    @Test fun aQuarterTurnClockwisePutsTheImageBottomLeftAtTheTopLeft() {
        val q = Mechanism.turnedQuad(1)
        assertArrayEquals(floatArrayOf(0f, 1f), uvAt(q, 2), 0f)   // output TL shows image BL
        assertArrayEquals(floatArrayOf(0f, 0f), uvAt(q, 3), 0f)   // output TR shows image TL
        assertArrayEquals(floatArrayOf(1f, 0f), uvAt(q, 1), 0f)   // output BR shows image TR
        assertArrayEquals(floatArrayOf(1f, 1f), uvAt(q, 0), 0f)   // output BL shows image BR
    }

    @Test fun halfAndThreeQuarterTurns() {
        assertArrayEquals(floatArrayOf(1f, 1f), uvAt(Mechanism.turnedQuad(2), 2), 0f)  // TL shows BR
        assertArrayEquals(floatArrayOf(1f, 0f), uvAt(Mechanism.turnedQuad(3), 2), 0f)  // TL shows TR
        // Positions never move, only the picture on them
        for (t in 0..3) assertEquals(-1f, Mechanism.turnedQuad(t)[0], 0f)
    }

    @Test fun theMeterReadsOnlyWhatWasRead() {
        val pcm = ByteArray(8)
        // first sample full scale, the rest of the buffer stale full scale too
        for (i in 0 until 8 step 2) { pcm[i] = 0xFF.toByte(); pcm[i + 1] = 0x7F }
        val all = Mechanism.rmsOfPcm16(pcm, stride = 2)
        assertEquals(all, Mechanism.rmsOfPcm16Range(pcm, 8, 2), 0.0001f)
        pcm[2] = 0; pcm[3] = 0; pcm[4] = 0; pcm[5] = 0; pcm[6] = 0; pcm[7] = 0
        // only 2 bytes were read: the zeros after them do not count
        assertEquals(1f, Mechanism.rmsOfPcm16Range(pcm, 2, 2), 0.001f)
        assertEquals(0f, Mechanism.rmsOfPcm16Range(pcm, 0, 2), 0f)
    }
}
