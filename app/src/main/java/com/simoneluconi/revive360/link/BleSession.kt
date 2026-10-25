package com.simoneluconi.revive360.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.simoneluconi.revive360.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/**
 * A Bluetooth LE connection to a camera's G360R service (protocol §9): it only negotiates the
 * Wi-Fi. [info] tells the camera's name, its Wi-Fi state and whether pairing is open; [wifi] gives
 * the network's name and passphrase while pairing is open; [setWifi] switches the Wi-Fi on or off.
 * One operation at a time, each with a timeout.
 */
@SuppressLint("MissingPermission")
class BleSession private constructor(private val gatt: BluetoothGatt, private val callback: Callback) : Closeable {

    /** What the camera's INFO says: its name, its Wi-Fi's name and state, whether pairing is open. */
    data class Info(val name: String, val ssid: String, val wifi: String, val pairing: Boolean)

    /** The camera's Wi-Fi, from WIFI while pairing is open. */
    data class WifiDetails(val ssid: String, val passphrase: String)

    private val mutex = Mutex()

    suspend fun info(): Info {
        val json = JSONObject(String(read(INFO), Charsets.UTF_8))
        return Info(json.optString("name"), json.optString("ssid"), json.optString("wifi", "off"), json.optBoolean("pair"))
    }

    /** Null when pairing is closed (the camera was switched on more than a few minutes ago). */
    suspend fun wifi(): WifiDetails? {
        val json = JSONObject(String(read(WIFI), Charsets.UTF_8))
        if (!json.optBoolean("pair")) return null
        val ssid = json.optString("ssid")
        val psk = json.optString("psk")
        return if (ssid.isNotEmpty() && psk.isNotEmpty()) WifiDetails(ssid, psk) else null
    }

    suspend fun setWifi(on: Boolean) = write(CMD, byteArrayOf(if (on) 1 else 0))

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        gatt.getService(SERVICE)?.getCharacteristic(uuid) ?: throw IOException("the camera has no characteristic $uuid")

    private suspend fun read(uuid: UUID): ByteArray = mutex.withLock {
        val c = characteristic(uuid)
        val done = CompletableDeferred<ByteArray>()
        callback.pendingRead = done
        if (!gatt.readCharacteristic(c)) throw IOException("read refused")
        withTimeout(OP_TIMEOUT_MS) { done.await() }
    }

    private suspend fun write(uuid: UUID, value: ByteArray): Unit = mutex.withLock {
        val c = characteristic(uuid)
        val done = CompletableDeferred<Unit>()
        callback.pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c)
        }
        if (!started) throw IOException("write refused")
        withTimeout(OP_TIMEOUT_MS) { done.await() }
    }

    private class Callback : BluetoothGattCallback() {
        /** BluetoothGatt.refresh(): hidden, but the only way to drop a stale service cache */
        private fun refreshCache(gatt: BluetoothGatt) {
            runCatching { gatt.javaClass.getMethod("refresh").invoke(gatt) }
        }

        val ready = CompletableDeferred<Unit>()
        @Volatile var pendingRead: CompletableDeferred<ByteArray>? = null
        @Volatile var pendingWrite: CompletableDeferred<Unit>? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // A camera bonded once (the old Samsung pairing) has its services cached by the
                // phone, and ours are registered anew at each start: read them afresh
                refreshCache(gatt)
                if (!gatt.discoverServices()) ready.completeExceptionally(IOException("service discovery refused"))
            } else {
                val error = IOException("Bluetooth connection lost ($status)")
                ready.completeExceptionally(error)
                pendingRead?.completeExceptionally(error)
                pendingWrite?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt.getService(SERVICE) != null) ready.complete(Unit)
            else ready.completeExceptionally(IOException("the camera's service is missing ($status)"))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val done = pendingRead ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) done.complete(value) else done.completeExceptionally(IOException("read failed ($status)"))
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onCharacteristicRead(gatt, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val done = pendingWrite ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) done.complete(Unit) else done.completeExceptionally(IOException("write failed ($status)"))
        }
    }

    companion object {
        val SERVICE: UUID = CameraBle.SERVICE
        private val CMD: UUID = UUID.fromString("d9c70002-5a3b-4e21-9f6d-7c8e0a1b2c3d")
        private val INFO: UUID = UUID.fromString("d9c70004-5a3b-4e21-9f6d-7c8e0a1b2c3d")
        private val WIFI: UUID = UUID.fromString("d9c70005-5a3b-4e21-9f6d-7c8e0a1b2c3d")
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val OP_TIMEOUT_MS = 8_000L

        /** Connects to the camera at [address] and finds its service. */
        suspend fun open(context: Context, address: String): BleSession {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.takeIf { it.isEnabled } ?: throw IOException("Bluetooth is off")
            val device = adapter.getRemoteDevice(address)
            val callback = Callback()
            EventLog.log("Bluetooth: connecting to $address")
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("cannot connect to $address")
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { callback.ready.await() }
            } catch (e: Exception) {
                runCatching { gatt.close() }
                throw if (e is IOException) e else IOException("the camera did not answer over Bluetooth", e)
            }
            EventLog.log("Bluetooth: connected to $address")
            return BleSession(gatt, callback)
        }
    }
}
