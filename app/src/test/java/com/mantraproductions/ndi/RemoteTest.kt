package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Test

/** v117: the remote-control vocabulary survives the wire both ways, quotes and all. */
class RemoteTest {
    @Test fun aKeyAndATapRoundTrip() {
        val c = CameraCommand.parse(CameraCommand(key = "LOG", tapX = 0.25f, tapY = 0.75f).toXml())!!
        assertEquals("LOG", c.key); assertEquals(0.25f, c.tapX); assertEquals(0.75f, c.tapY)
    }

    @Test fun theStateRoundTripsWithAwkwardText() {
        val st = CameraState(status = "WHITE BALANCE TRIANGLE locked 6500K · gains \"R\" 1.15 & <B> 1.25",
            keys = "LOG~LOG~HLG~OFF~|M~M~AUTO~ON~", marks = "0,0.1,0.2,0.3,0.4,-1,1", armed = 2, turns = 1, cameraName = "Pixel 7")
        val back = CameraState.parse(st.toXml())!!
        assertEquals(st.status, back.status); assertEquals(st.keys, back.keys); assertEquals(st.marks, back.marks)
        assertEquals(2, back.armed); assertEquals(1, back.turns); assertEquals("Pixel 7", back.cameraName)
    }

    @Test fun aStateIsNotTakenForACommand() {
        // "mantra_cam" is inside "mantra_cam_state": a camera must not obey its own state coming back
        val st = CameraState(status = "x").toXml()
        assertEquals(null, CameraCommand.parse(st)?.key)
    }
}
