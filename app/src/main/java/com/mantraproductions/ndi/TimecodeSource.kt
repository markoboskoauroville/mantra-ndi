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
 * The clock in the room, read over the air.
 *
 * Tentacle devices broadcast and never connect, so this only listens. Several
 * cameras can therefore share one generator, which is the entire point of the
 * thing, and nothing about this app can take the generator away from another.
 *
 * Between advertisements the timecode is run forward from the phone's
 * monotonic clock rather than left to sit. An advertisement arrives roughly
 * once a second and a display that only moved then would visibly stutter,
 * while a display that free-runs and re-syncs on every packet stays frame
 * accurate and never drifts, because it is corrected before it can.
 */
class TimecodeSource(private val context: Context) {

    interface Listener {
        fun onTimecode(timecode: Timecode, deviceName: String)
        fun onLost()
        /** Every advertisement seen, so an unreadable one can be looked at. */
        fun onRawSeen(deviceName: String, hex: String, parsed: String?)
    }

    var listener: Listener? = null

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false

    /** The last reading, and when it arrived on the phone's own clock. */
    @Volatile private var anchor: Timecode? = null
    @Volatile private var anchorAt = 0L
    @Volatile var deviceName: String = ""
        private set

    val isRunning: Boolean get() = scanning

    /** True while a generator has been heard recently enough to be trusted. */
    val isLocked: Boolean
        get() = anchor != null && SystemClock.elapsedRealtime() - anchorAt < LOST_AFTER_MS

    /**
     * The time right now, run forward from the last packet. Null when nothing
     * has been heard, which is different from zero and has to stay different:
     * 00:00:00:00 is a real timecode.
     */
    fun now(): Timecode? {
        val base = anchor ?: return null
        val elapsed = SystemClock.elapsedRealtime() - anchorAt
        if (elapsed > LOST_AFTER_MS) return null
        return base.advancedBy(elapsed)
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (scanning) return true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false

        val leScanner = adapter.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            // Low latency: a timecode that arrives late is a timecode that is
            // wrong, and this only runs while the camera screen is open.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            // No filter: the manufacturer id differs between Tentacle models
            // and firmware, and filtering on a guess is how a working device
            // goes unseen. Names are checked instead, in the callback.
            leScanner.startScan(null, settings, callback)
            scanner = leScanner
            scanning = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "scan refused", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stop", e)
        }
        scanner = null
        scanning = false
    }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val name = record.deviceName ?: result.device?.name
            if (!TentacleParser.looksLikeTentacle(name)) return

            val manufacturer = record.manufacturerSpecificData ?: return
            for (i in 0 until manufacturer.size()) {
                val data = manufacturer.valueAt(i) ?: continue
                val reading = TentacleParser.parse(data)

                listener?.onRawSeen(
                    name ?: "unnamed",
                    TentacleParser.hex(data),
                    reading?.let { "${it.timecode}  ${it.layout}" }
                )

                if (reading != null) {
                    anchor = reading.timecode
                    anchorAt = SystemClock.elapsedRealtime()
                    deviceName = name ?: "Tentacle"
                    listener?.onTimecode(reading.timecode, deviceName)
                    return
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            scanning = false
            listener?.onLost()
        }
    }

    private companion object {
        const val TAG = "TimecodeSource"

        /**
         * Generous, because a packet can be missed without the clock being
         * wrong, but finite, because a timecode that keeps running after the
         * generator has gone is a lie told confidently.
         */
        const val LOST_AFTER_MS = 5000L
    }
}
