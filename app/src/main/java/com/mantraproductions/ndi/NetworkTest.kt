package com.mantraproductions.ndi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Why two phones cannot see each other, answered rather than guessed.
 *
 * NDI discovery fails silently and for several unrelated reasons, and from the
 * outside they all look the same: an empty list. This asks each question
 * separately so the answer names the actual cause.
 *
 * The ones that matter, in the order they bite:
 *
 *  - **No multicast lock.** Discovery is mDNS, which is UDP multicast, and
 *    Android's wifi driver drops multicast frames unless something holds a
 *    MulticastLock. This was the bug: the lock was only taken while streaming,
 *    so a phone that was merely looking heard nothing.
 *  - **Client isolation.** Many routers, and almost every guest network and
 *    public hotspot, forbid one client talking to another at all. Nothing on
 *    the phone can work around it and no app can detect it directly, so the
 *    report says plainly that it is the next thing to rule out.
 *  - **Different subnets.** Two phones on the same router but one on 2.4GHz
 *    guest and one on 5GHz main are not on the same network, whatever the
 *    name on the screen says. The addresses show it immediately.
 *  - **Nothing is sending.** A camera that is not live is not discoverable,
 *    which is correct behaviour and the first thing to check.
 */
object NetworkTest {

    fun run(context: Context): String = buildString {
        append("NDI NETWORK TEST\n")
        append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()))
        append("  ").append(android.os.Build.MODEL).append('\n')
        append("Mantra NDI v").append(BuildConfig.VERSION_NAME).append("\n\n")

        append(wifiSection(context))
        append('\n')
        append(addressSection())
        append('\n')
        append(ndiSection(context))
        append('\n')
        append(
            "IF NOTHING IS FOUND\n" +
                "  1  is the other phone actually live, not just open\n" +
                "  2  are both addresses on the same subnet, above\n" +
                "  3  client isolation on the router or hotspot. Guest networks\n" +
                "     and public wifi block phone to phone traffic entirely, and\n" +
                "     no app can work around it. Try a personal hotspot from one\n" +
                "     phone with the other joined to it: if they see each other\n" +
                "     there, the router is the problem\n"
        )
    }

    private fun wifiSection(context: Context) = buildString {
        append("WIFI\n")
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            append("  connected by: ")
            append(
                when {
                    onWifi -> "wifi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                        "mobile data, which carries no NDI"
                    else -> "nothing"
                }
            )
            append('\n')

            // Whether the interface carries multicast is reported per
            // interface below, which is the answer that actually matters and
            // the one the platform will still give.
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            append("  wifi service: ").append(if (wifi.isWifiEnabled) "on" else "off")
                .append('\n')
        } catch (e: Exception) {
            append("  unreadable: ").append(e.message).append('\n')
        }
        append("  finder holds the multicast lock: ")
            .append(NdiFinder.holdsMulticastLock).append('\n')
    }

    private fun addressSection() = buildString {
        append("ADDRESSES\n")
        try {
            var found = false
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.inetAddresses) {
                    if (address !is Inet4Address) continue
                    found = true
                    append("  ").append(nif.name).append("  ")
                        .append(address.hostAddress)
                    append(if (nif.supportsMulticast()) "  multicast ok" else "  NO MULTICAST")
                    append('\n')
                }
            }
            if (!found) append("  no IPv4 address, so nothing can be reached\n")
        } catch (e: Exception) {
            append("  unreadable: ").append(e.message).append('\n')
        }
    }

    private fun ndiSection(context: Context) = buildString {
        append("NDI\n")
        if (!NdiFinder.available) {
            append("  built without the SDK, so discovery cannot run\n")
            return@buildString
        }
        val started = NdiFinder.start(context)
        append("  finder started: ").append(started).append('\n')
        if (!started) return@buildString

        // Longer than the app normally waits: a phone that has just joined the
        // network can take several seconds to announce itself.
        val sources = NdiFinder.sources(timeoutMs = 6000)
        append("  sources found: ").append(sources.size).append('\n')
        sources.forEach { append("    ").append(it).append('\n') }
        if (sources.isEmpty()) {
            append("    nothing. This phone is looking and hearing no answer.\n")
        }
        NdiFinder.stop()
    }
}
