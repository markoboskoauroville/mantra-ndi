package com.mantraproductions.ndi

/**
 * NDI source discovery over mDNS. Blocking calls, so drive it off a
 * background thread; MonitorViewModel does that.
 */
object NdiFinder {

    val available: Boolean get() = NdiSender.available

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    /**
     * Discovery needs the multicast lock, and this is where it was missing.
     *
     * NDI finds sources by mDNS, which is UDP multicast, and Android's wifi
     * driver drops multicast frames unless something holds a MulticastLock.
     * The lock was only taken when a stream started, so a phone that was
     * merely looking was listening to a filtered socket and heard nothing.
     * Two phones on one network could both be sending and neither could see
     * the other, which is exactly the symptom.
     *
     * The lock belongs to whoever is listening, so the finder takes its own.
     */
    fun start(context: android.content.Context): Boolean {
        if (!available) return false
        if (multicastLock == null) {
            val wifi = context.applicationContext
                .getSystemService(android.content.Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
            multicastLock = wifi.createMulticastLock("ndi-finder").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
        return nativeStart()
    }

    fun stop() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        if (available) nativeStop()
    }

    val holdsMulticastLock: Boolean get() = multicastLock?.isHeld == true

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
