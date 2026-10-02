package com.mantraproductions.ndi

/**
 * The monitor sends no picture; this only loads the camera's native bridge (libndi_bridge, which also holds the
 * receive side, ndi_recv_bridge.cpp) and says whether it loaded. NdiFinder and NdiReceiver ask it.
 */
object NdiSender {
    val available: Boolean = try {
        System.loadLibrary("ndi_bridge")
        true
    } catch (e: Throwable) {
        false
    }
}
