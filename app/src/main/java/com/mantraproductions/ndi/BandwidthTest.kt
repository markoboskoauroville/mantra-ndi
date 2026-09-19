package com.mantraproductions.ndi

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * How much this network will actually carry between two phones.
 *
 * Every quality number in this app is a guess until this is run. A stream
 * bitrate, a record bitrate and a half size toggle are three decisions made
 * against nothing, and the usual way of settling them is to shoot something,
 * watch it stutter, and lower a number until it stops.
 *
 * This measures the real path: a TCP connection between the two devices,
 * pushed as hard as it will go, over the wifi they are actually on. TCP rather
 * than UDP on purpose, because NDI runs over TCP and a UDP number would
 * flatter the link by ignoring the retransmissions that make it slow.
 *
 * The phones find each other by a UDP beacon rather than by typed addresses.
 * An operator on a shoot should not be reading IP addresses off a settings
 * screen, and NDI's own discovery gives source names rather than addresses.
 */
object BandwidthTest {

    /** Named ports, high and unremarkable, chosen not to clash with NDI's. */
    private const val BEACON_PORT = 5961
    private const val DATA_PORT = 5962

    /** A phone offering to be the far end of a test. */
    data class Peer(val address: String, val model: String) {
        val label: String get() = "$model   $address"
    }

    data class Result(
        val megabitsPerSecond: Double,
        val seconds: Double,
        val bytes: Long
    ) {
        /**
         * What is safe to stream at.
         *
         * Sixty per cent of what the link managed, because a measurement is
         * the best case: nothing else was talking, nobody had walked between
         * the phones, and the test was a single flat stream rather than
         * bursty video with keyframes. A link run at its measured ceiling
         * drops frames the first time any of that changes.
         */
        val safeStreamMbps: Int get() = (megabitsPerSecond * 0.6).toInt().coerceAtLeast(1)

        val verdict: String
            get() = when {
                megabitsPerSecond < 8 -> "Too slow for video. Check the router or move closer."
                megabitsPerSecond < 25 -> "1080p at a low bitrate, half size if it stutters."
                megabitsPerSecond < 60 -> "1080p comfortably, 4K only at a modest bitrate."
                megabitsPerSecond < 150 -> "4K at a sensible bitrate."
                else -> "Anything this app can send."
            }
    }

    // --- being the far end ----------------------------------------------------

    private val serving = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var beaconSocket: DatagramSocket? = null

    val isServing: Boolean get() = serving.get()

    /**
     * Announces this phone and swallows whatever is sent to it.
     *
     * The receiving side does nothing but read, because the number wanted is
     * how fast bytes cross the room, not how fast this phone can think about
     * them.
     */
    fun startServer(onEvent: (String) -> Unit = {}) {
        if (serving.getAndSet(true)) return

        thread(name = "bw-beacon") {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(BEACON_PORT))
                }
                beaconSocket = socket
                val buffer = ByteArray(256)
                while (serving.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (String(packet.data, 0, packet.length) != PING) continue
                    // Answers with what a person would recognise on a set.
                    val reply = (PONG + android.os.Build.MODEL).toByteArray()
                    socket.send(
                        DatagramPacket(reply, reply.size, packet.address, packet.port)
                    )
                }
            } catch (e: Exception) {
                if (serving.get()) Log.w(TAG, "beacon", e)
            }
        }

        thread(name = "bw-sink") {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(DATA_PORT))
                serverSocket = server
                onEvent("Listening. Run the test on the other phone.")
                while (serving.get()) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    val input = DataInputStream(client.getInputStream())
                    val chunk = ByteArray(CHUNK)
                    var total = 0L
                    try {
                        while (true) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            total += read
                        }
                    } catch (e: Exception) {
                        // A sender that closes abruptly is a finished test.
                    }
                    runCatching { client.close() }
                    onEvent("Received " + (total / 1_000_000) + " MB")
                }
            } catch (e: Exception) {
                if (serving.get()) onEvent("Could not listen: " + e.message)
            }
        }
    }

    fun stopServer() {
        serving.set(false)
        runCatching { serverSocket?.close() }
        runCatching { beaconSocket?.close() }
        serverSocket = null
        beaconSocket = null
    }

    // --- finding the far end --------------------------------------------------

    /**
     * Broadcasts a question and collects whoever answers.
     *
     * Broadcast rather than multicast: this is a direct question to the subnet
     * and does not need the multicast lock NDI discovery does, so a failure
     * here means the network is blocking phone to phone traffic, which is
     * itself the answer to why NDI was not working.
     */
    fun findPeers(timeoutMs: Int = 2500): List<Peer> {
        val found = LinkedHashMap<String, Peer>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val ping = PING.toByteArray()
                for (address in broadcastAddresses()) {
                    runCatching {
                        socket.send(DatagramPacket(ping, ping.size, address, BEACON_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (!text.startsWith(PONG)) continue
                        val host = packet.address.hostAddress ?: continue
                        found[host] = Peer(host, text.removePrefix(PONG))
                    } catch (e: Exception) {
                        // Timeout on this read; keep asking until the deadline.
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "find peers", e)
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.interfaceAddresses) {
                    address.broadcast?.let { out.add(it) }
                }
            }
        }
        // The all-subnets address as a fallback, for interfaces that report none.
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out
    }

    // --- the measurement ------------------------------------------------------

    /**
     * Pushes as hard as the link will take for a few seconds.
     *
     * The first second is discarded. TCP starts slow on purpose and climbs,
     * so a measurement that includes the ramp reports a link slower than the
     * one a stream will actually see.
     */
    fun measure(host: String, seconds: Int = 6, onProgress: (Double) -> Unit = {}): Result? {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, DATA_PORT), 4000)
                val output = DataOutputStream(socket.getOutputStream())

                // Incompressible, so no link along the way can flatter the
                // number by compressing it.
                val chunk = ByteArray(CHUNK)
                java.util.Random(7).nextBytes(chunk)

                val started = System.nanoTime()
                val warmupEnds = started + 1_000_000_000L
                val finishAt = started + seconds * 1_000_000_000L

                var countedBytes = 0L
                var countingFrom = 0L

                while (System.nanoTime() < finishAt) {
                    output.write(chunk)
                    val now = System.nanoTime()
                    if (now < warmupEnds) continue
                    if (countingFrom == 0L) countingFrom = now
                    countedBytes += CHUNK
                    val elapsed = (now - countingFrom) / 1_000_000_000.0
                    if (elapsed > 0.2) {
                        onProgress(countedBytes * 8.0 / elapsed / 1_000_000.0)
                    }
                }
                output.flush()

                val elapsed = (System.nanoTime() - countingFrom) / 1_000_000_000.0
                if (elapsed <= 0.0 || countedBytes == 0L) return null
                Result(
                    megabitsPerSecond = countedBytes * 8.0 / elapsed / 1_000_000.0,
                    seconds = elapsed,
                    bytes = countedBytes
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "measure", e)
            null
        }
    }

    /**
     * What a given stream setting actually asks of the link.
     *
     * Video, plus audio, plus the overhead NDI and TCP add. Worth stating
     * beside the measurement, because a bitrate is a number nobody can judge
     * against a network speed without doing this arithmetic.
     */
    fun requirement(videoMbps: Int): Double {
        val audio = 1.6           // two channels of AAC with room to spare
        val overhead = 1.12       // headers, acknowledgements, retransmits
        return (videoMbps + audio) * overhead
    }

    private const val CHUNK = 64 * 1024
    private const val PING = "MANTRA-BW?"
    private const val PONG = "MANTRA-BW!"
    private const val TAG = "BandwidthTest"
}
