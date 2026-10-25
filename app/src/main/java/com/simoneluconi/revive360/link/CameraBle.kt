package com.simoneluconi.revive360.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.simoneluconi.revive360.EventLog
import java.util.UUID

/**
 * The camera's Bluetooth LE side (docs/firmware/11_bluetooth_control_design.md): it advertises the
 * G360R control service while it is on, so the phone can find it without its Wi-Fi.
 */
object CameraBle {
    val SERVICE: UUID = UUID.fromString("d9c70001-5a3b-4e21-9f6d-7c8e0a1b2c3d")

    /** One camera seen advertising. */
    data class Seen(val address: String, val name: String?, val rssi: Int)

    /** The runtime permissions a scan needs on this Android version. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** The cameras seen in [durationMs]; empty when Bluetooth is off or the scan failed. */
    suspend fun scan(context: Context, durationMs: Long): List<Seen> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val seen = ArrayList<Seen>()
        scan(context, durationMs, onSeen = { synchronized(seen) { seen += it } }) {
            if (cont.isActive) cont.resumeWith(Result.success(synchronized(seen) { seen.toList() }))
        }
    }

    /** Whether the app may connect to Bluetooth devices (asked at start on Android 12+). */
    fun allowed(context: Context): Boolean = permissions().all {
        context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Scans [durationMs] for cameras, reporting each once; [onDone] when it stops (null on success). */
    @SuppressLint("MissingPermission")
    fun scan(context: Context, durationMs: Long, onSeen: (Seen) -> Unit, onDone: (String?) -> Unit) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return onDone("Bluetooth is off")
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (seen.add(address)) {
                    val name = result.scanRecord?.deviceName
                    EventLog.log("Bluetooth: camera $name ($address) at ${result.rssi} dBm")
                    onSeen(Seen(address, name, result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) = onDone("scan failed ($errorCode)")
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        EventLog.log("Bluetooth: looking for cameras")
        scanner.startScan(listOf(filter), settings, callback)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { scanner.stopScan(callback) }
            EventLog.log("Bluetooth: scan over, ${seen.size} camera(s)")
            onDone(null)
        }, durationMs)
    }
}
