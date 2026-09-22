package com.mantraproductions.ndi

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Streaming down the cable instead of through the air.
 *
 * **This app cannot be the phone's "Webcam" USB option, and no third-party app
 * can be.** That option is `com.android.DeviceAsWebcam`, which lives in
 * `/system/priv-app` with the SYSTEM flag; presenting the phone to a computer
 * as a UVC camera means writing the USB gadget's configuration in configfs,
 * which needs `MANAGE_USB` — a signature|privileged permission with no public
 * API behind it. An ordinary APK cannot get there, and an APK that claimed to
 * would be a key that does nothing.
 *
 * It is also not what you want. The system webcam mode hands a computer
 * 1080p30 of 8-bit YUV, because that is what UVC is; this app already makes
 * 10-bit HEVC and whole I420 frames. The cable is worth having for its
 * bandwidth, not for its protocol.
 *
 * So: **USB tethering, and NDI over it.** Turn tethering on and the phone
 * becomes a network interface on the computer — a private link at USB speed,
 * with none of a hall's Wi-Fi in the way. Full NDI at 1080p is around 125
 * Mbit/s, which a cable carries without noticing and a shared access point
 * does not.
 *
 * The one thing a person then needs is the address, because NDI's discovery
 * travels by mDNS and a freshly-made USB link is exactly where a receiver is
 * most likely not to hear it. Every receiver has somewhere to type an address
 * by hand; none of them can guess it.
 */
object UsbLink {

    /** One interface this phone is reachable on, and what kind it is. */
    data class Address(val interfaceName: String, val ip: String, val kind: Kind) {
        enum class Kind { USB, WIFI, OTHER }

        val label: String
            get() = when (kind) {
                Kind.USB -> "USB  $ip"
                Kind.WIFI -> "Wi-Fi  $ip"
                Kind.OTHER -> "$interfaceName  $ip"
            }
    }

    /**
     * Android names a USB tether `rndis0` or `ncm0` depending on which gadget
     * the phone offers — a Pixel on Android 14 and later uses NCM, older ones
     * RNDIS — and `usb0` turns up on some builds. Matching the name is how the
     * cable is told from the air, because nothing in the public API says which
     * interface is which.
     */
    private fun kindOf(name: String): Address.Kind = when {
        name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") ->
            Address.Kind.USB
        name.startsWith("wlan") || name.startsWith("ap") -> Address.Kind.WIFI
        else -> Address.Kind.OTHER
    }

    /**
     * Every address a receiver could be pointed at, the cable first.
     *
     * Loopback and link-local are left out: neither is reachable from the
     * computer at the other end, and offering an address that cannot work is
     * worse than offering none.
     */
    fun addresses(): List<Address> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map { Address(nif.name, it.hostAddress ?: "", kindOf(nif.name)) }
            }
            .filter { it.ip.isNotBlank() }
            .sortedBy { it.kind.ordinal }
    } catch (t: Throwable) {
        Trace.fault("reading network interfaces", t)
        emptyList()
    }

    /** True when the cable is up, which is the only thing the key needs to know. */
    fun usbUp(): Boolean = addresses().any { it.kind == Address.Kind.USB }

    /** What to put in front of a person, cable first and never empty. */
    fun summary(): String {
        val found = addresses()
        if (found.isEmpty()) return "No network. Nothing can reach this phone."
        return found.joinToString("\n") { it.label }
    }
}
