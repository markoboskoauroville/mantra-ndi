package com.mantraproductions.ndi

/**
 * NDI source discovery over mDNS. Blocking calls, so drive it off a
 * background thread; MonitorViewModel does that.
 */
object NdiFinder {

    val available: Boolean get() = NdiSender.available

    fun start(): Boolean = if (available) nativeStart() else false

    fun stop() {
        if (available) nativeStop()
    }

    /**
     * Waits up to [timeoutMs] for the source list to change, then returns the
     * current sources. An unchanged list comes back as the same names again,
     * so this is safe to call in a loop.
     */
    fun sources(timeoutMs: Int = 2000): List<String> =
        if (available) nativeGetSources(timeoutMs).toList() else emptyList()

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeGetSources(timeoutMs: Int): Array<String>
}
