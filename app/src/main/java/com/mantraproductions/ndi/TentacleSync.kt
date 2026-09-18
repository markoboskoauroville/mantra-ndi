package com.mantraproductions.ndi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Timecode from a Tentacle, over Bluetooth.
 *
 * A Tentacle broadcasts: every Sync E acts as a master and puts its timecode
 * out over BLE independently, so nothing pairs and nothing connects, and any
 * number of apps and phones can read one device at the same time. That is why
 * this listens to advertisements rather than opening a connection.
 *
 * **What is known and what is not.** Everything above this class is exact: the
 * SMPTE arithmetic, the drop frame rule, the free running clock. The one piece
 * that is not public is the byte layout inside the advertisement. Tentacle
 * ship an SDK for it under licence, which is what the Blackmagic app uses, and
 * the layout is not documented anywhere I can check.
 *
 * Guessing it would be the worst possible outcome: a reading that looks right,
 * is wrong by some frames, and is only discovered in the edit. So this does
 * not guess. It finds the device, captures the raw bytes, and writes them out
 * where they can be looked at. Decode the format once from a real device and
 * [parse] becomes ten lines; until then the app says plainly that it can see
 * the Tentacle and cannot yet read it.
 */
class TentacleSync(private val context: Context) {

    interface Listener {
        fun onTimecode(timecode: Timecode, atNanos: Long)
        fun onDeviceSeen(name: String, rssi: Int)
        fun onRaw(name: String, manufacturerId: Int, bytes: ByteArray)
        fun onError(message: String)
    }

    var listener: Listener? = null

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false

    /** Every advertisement seen, for working the format out from a real device. */
    private val captured = LinkedHashMap<String, MutableList<String>>()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (scanning) return true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener?.onError("Bluetooth is off")
            return false
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener?.onError("No BLE scanner on this phone")
            return false
        }

        return try {
            // Low latency: timecode is only useful if it arrives promptly, and
            // this runs while the operator is looking at the screen.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // No filter: a filter needs the company id, which is the thing not
            // yet known. Names are checked instead.
            scanner?.startScan(null, settings, callback)
            scanning = true
            true
        } catch (e: SecurityException) {
            listener?.onError("Bluetooth scan permission not granted")
            false
        } catch (e: Exception) {
            listener?.onError("Could not start scanning: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
        scanning = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            listener?.onError("Bluetooth scan failed, code $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val name = try {
            result.scanRecord?.deviceName ?: result.device?.name
        } catch (e: SecurityException) {
            null
        } ?: return

        if (!looksLikeTentacle(name)) return
        listener?.onDeviceSeen(name, result.rssi)

        val data = result.scanRecord?.manufacturerSpecificData ?: return
        for (i in 0 until data.size()) {
            val companyId = data.keyAt(i)
            val bytes = data.valueAt(i) ?: continue

            listener?.onRaw(name, companyId, bytes)
            remember(name, companyId, bytes)

            parse(bytes)?.let { timecode ->
                listener?.onTimecode(timecode, SystemClock.elapsedRealtimeNanos())
            }
        }
    }

    /**
     * The devices that broadcast timecode all carry the maker's name, which is
     * enough to find them without knowing the company id.
     */
    private fun looksLikeTentacle(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("tentacle") || lower.startsWith("sync e") ||
            lower.startsWith("track e") || lower.startsWith("timebar")
    }

    /**
     * Not implemented, on purpose.
     *
     * Returns null until the layout is known from a real device, so the app
     * reports what it honestly has: a Tentacle in range whose timecode it
     * cannot yet read. Everything downstream is already built and tested, so
     * this becomes a few lines the moment the bytes are understood.
     */
    fun parse(@Suppress("UNUSED_PARAMETER") manufacturerData: ByteArray): Timecode? = null

    private fun remember(name: String, companyId: Int, bytes: ByteArray) {
        val list = captured.getOrPut(name) { mutableListOf() }
        if (list.size >= 40) return
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val line = "company 0x%04X  len %d  %s".format(companyId, bytes.size, hex)
        if (list.none { it.endsWith(hex) }) {
            list.add(
                "%s  %s".format(
                    java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                        .format(java.util.Date()),
                    line
                )
            )
        }
    }

    /**
     * What was actually heard, as text.
     *
     * Advertisements a second or two apart differ only in the fields that
     * count, so the bytes that change between lines are the timecode and the
     * ones that do not are the identity. That is how the format gets worked
     * out, and it is why the capture keeps the times alongside.
     */
    fun captureReport(): String = buildString {
        append("TENTACLE CAPTURE\n")
        append(android.os.Build.MODEL).append("  Mantra NDI v")
            .append(BuildConfig.VERSION_NAME).append("\n\n")
        if (captured.isEmpty()) {
            append("Nothing seen yet.\n\n")
            append("  Bluetooth on, Tentacle powered, and within a few metres.\n")
            append("  The scan needs the nearby devices permission on Android 12\n")
            append("  and newer, which the app asks for when this is opened.\n")
            return@buildString
        }
        for ((name, lines) in captured) {
            append(name).append('\n')
            lines.forEach { append("  ").append(it).append('\n') }
            append('\n')
        }
        append(
            "The bytes that change between two lines a second apart are the\n" +
                "timecode. The ones that do not are identity and flags.\n"
        )
    }

    fun clearCapture() = captured.clear()

    private companion object {
        const val TAG = "TentacleSync"
    }
}
