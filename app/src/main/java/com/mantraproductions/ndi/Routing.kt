package com.mantraproductions.ndi

/**
 * v135: WHERE THE STREAM GOES OUT, AND WHERE THE SOUND COMES IN. Pure: no Android imports, so RoutingTest checks
 * every choice on a desk.
 *
 * Marko, 9.10.2026: *"a robust switch inside the NDI settings, to stream NDI over Wi-Fi, or USB cable. The phone is
 * connected to USB, it's switched to tethering, and then the interface is used from phone to send NDI over cable to
 * computer."* And: *"What is the audio source? Is it USB microphone? Is it connected audio interface through the
 * USB-C? Is it internal microphone? Embed sound from any selected source to travel together with the NDI feed."*
 *
 * **The wire.** Until v134 the sender was created with no network setting at all, so the SDK announced itself and
 * listened on every interface the phone had: Wi-Fi, the tether, mobile data. With the cable in, a receiver could pick
 * the Wi-Fi path and the cable carried nothing. Now the sender is created with an NDI JSON configuration that allows
 * exactly one adapter (`"adapters": {"allowed": [ip]}`), the one the switch names, and it is re-created whenever
 * that adapter's address changes (Android hands a tether a fresh subnet on every connect) or goes away.
 *
 * **The sound.** One reader on the microphone, as before ([AudioMeter]); what changes is which microphone. AUTO takes
 * whatever is plugged in (USB-C first: a USB microphone, a USB-C audio interface, a wireless receiver such as the
 * Hollyland), then a wired one, then the phone's own. A named choice is kept by family and product name, so it is
 * found again after a replug, when Android gives the device a new id.
 */
object Routing {

    // --- the wire -------------------------------------------------------------------------------------------------

    enum class Transport(val label: String) { WIFI("WI-FI"), USB("USB CABLE") }

    enum class Kind { USB, WIFI, OTHER }

    /** One network interface with an IPv4 address. */
    data class Nic(val name: String, val ip: String) {
        val kind: Kind get() = kindOf(name)
    }

    /**
     * Android names a USB tether `rndis0` or `ncm0` depending on which gadget the phone offers (a Pixel on Android 14
     * and later uses NCM, older ones RNDIS), and `usb0` turns up on some builds. Matching the name is how the cable is
     * told from the air, because nothing in the public API says which interface is which. The phone's own hotspot
     * (`ap0`, `swlan0`, `wlan1`) counts as Wi-Fi: a receiver joined to it reaches the phone through the air.
     */
    fun kindOf(name: String): Kind = when {
        name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") -> Kind.USB
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> Kind.WIFI
        else -> Kind.OTHER
    }

    /**
     * The interface the switch names, or null when it is not up. Wi-Fi prefers `wlan0` (the network the phone joined)
     * over its own hotspot, because that is where a studio's computers are.
     */
    fun adapterFor(transport: Transport, nics: List<Nic>): Nic? = when (transport) {
        Transport.USB -> nics.firstOrNull { it.kind == Kind.USB }
        Transport.WIFI -> nics.firstOrNull { it.name == "wlan0" } ?: nics.firstOrNull { it.kind == Kind.WIFI }
    }

    /**
     * The NDI configuration that pins a sender to one adapter. Passed to `NDIlib_send_create_v2`, the Advanced SDK's
     * per-instance form of `ndi-config.v1.json`. Multicast sending stays off (the SDK's default), so a receiver
     * connects by TCP/UDP unicast to exactly this address.
     */
    fun ndiConfig(ip: String): String =
        "{\"ndi\":{\"adapters\":{\"allowed\":[\"" + ip.filter { it.isDigit() || it == '.' } + "\"]}}}"

    /** What the outputs line and the settings say about the wire. */
    fun wireLabel(transport: Transport, adapter: Nic?): String = when {
        adapter != null -> (if (transport == Transport.USB) "USB " else "WI-FI ") + adapter.ip
        transport == Transport.USB -> "USB: no cable — turn USB tethering on"
        else -> "WI-FI: not connected"
    }

    // --- the sound ------------------------------------------------------------------------------------------------

    const val AUDIO_AUTO = "auto"
    const val AUDIO_PHONE = "phone"

    enum class Family(val label: String) { PHONE("PHONE MIC"), USB("USB-C"), WIRED("WIRED"), NONE("") }

    // AudioDeviceInfo.TYPE_*, copied so this file stays pure. They are part of the public API and never renumbered.
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_LINE_ANALOG = 5
    const val TYPE_LINE_DIGITAL = 6
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_ACCESSORY = 12
    const val TYPE_DOCK = 13
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_USB_HEADSET = 22

    /** One input the phone offers, as AudioManager describes it. */
    data class Input(
        val id: Int,
        val type: Int,
        val product: String,
        val channelCounts: List<Int> = emptyList()
    ) {
        val family: Family get() = familyOf(type)
        /** Kept in settings: stable across a replug, unlike [id]. */
        val key: String get() = if (family == Family.PHONE) AUDIO_PHONE else family.name + ":" + product.trim()
        val label: String get() = when (family) {
            Family.PHONE -> "PHONE MIC"
            Family.USB -> "USB-C · " + product.trim().ifEmpty { if (type == TYPE_USB_HEADSET) "headset" else "audio device" }
            Family.WIRED -> "WIRED · " + product.trim().ifEmpty { "headset" }
            Family.NONE -> product
        }
    }

    /**
     * Bluetooth is left out on purpose: its microphone is a phone-call channel (8 or 16 kHz) that only opens in
     * call mode, so a key for it would be a key that does nothing. Telephony, echo reference, FM and remote submix
     * are not microphones.
     */
    fun familyOf(type: Int): Family = when (type) {
        TYPE_BUILTIN_MIC -> Family.PHONE
        TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_USB_HEADSET, TYPE_DOCK -> Family.USB
        TYPE_WIRED_HEADSET, TYPE_LINE_ANALOG, TYPE_LINE_DIGITAL -> Family.WIRED
        else -> Family.NONE
    }

    /**
     * The choices settings offers: the phone's microphone once (a Pixel lists two or three, the camera source picks
     * the right one for a camera), then every plugged-in device once by name.
     */
    fun choices(inputs: List<Input>): List<Input> {
        val usable = inputs.filter { it.family != Family.NONE }
        val phone = usable.firstOrNull { it.family == Family.PHONE }
        val external = usable.filter { it.family != Family.PHONE }.distinctBy { it.key }
        return listOfNotNull(phone) + external
    }

    /**
     * The input a choice resolves to right now, or null when the chosen device is not plugged in (the caller then
     * uses the phone's microphone and says so). AUTO: USB-C first, then wired, then the phone.
     */
    fun pick(choice: String, inputs: List<Input>): Input? {
        val usable = choices(inputs)
        return when (choice) {
            AUDIO_AUTO -> usable.firstOrNull { it.family == Family.USB }
                ?: usable.firstOrNull { it.family == Family.WIRED }
                ?: usable.firstOrNull { it.family == Family.PHONE }
            AUDIO_PHONE -> usable.firstOrNull { it.family == Family.PHONE }
            else -> usable.firstOrNull { it.key == choice }
        }
    }

    /** Stereo when asked for and the device can give it; a device that lists no counts takes any. */
    fun channelsFor(stereo: Boolean, input: Input?): Int =
        if (stereo && (input == null || input.channelCounts.isEmpty() || 2 in input.channelCounts)) 2 else 1

    /** What the settings and the status say a choice is. */
    fun choiceLabel(choice: String): String = when (choice) {
        AUDIO_AUTO -> "AUTO"
        AUDIO_PHONE -> "PHONE MIC"
        else -> choice.substringBefore(':').let { f -> runCatching { Family.valueOf(f).label }.getOrDefault(f) } +
            " · " + choice.substringAfter(':')
    }
}
