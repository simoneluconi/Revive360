package com.simoneluconi.revive360.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The camera this phone is paired with: how to join its Wi-Fi and what it said about itself.
 * [ssid] and [bssid] come from the system's companion scan; when they are unknown the app joins by
 * the SSID prefix instead (the system then asks every time).
 */
data class PairedCamera(
    val passphrase: String,
    val ssid: String? = null,
    val bssid: String? = null,
    /** `info.camera.mac`, once the camera has answered. Identity, not the BSSID. */
    val mac: String? = null,
    /** Display name, model and firmware from the last `info`, for the screens shown before connecting. */
    val name: String? = null,
    val model: String? = null,
    val firmware: String? = null,
)

class CameraStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("camera", Context.MODE_PRIVATE)

    private val _camera = MutableStateFlow(load())
    val camera: StateFlow<PairedCamera?> = _camera.asStateFlow()

    fun save(camera: PairedCamera) {
        _camera.value = camera
        prefs.edit {
            putString(PASSPHRASE, camera.passphrase)
            putString(SSID, camera.ssid)
            putString(BSSID, camera.bssid)
            putString(MAC, camera.mac)
            putString(NAME, camera.name)
            putString(MODEL, camera.model)
            putString(FIRMWARE, camera.firmware)
        }
    }

    fun update(change: (PairedCamera) -> PairedCamera) {
        _camera.value?.let { save(change(it)) }
    }

    fun forget() {
        _camera.value = null
        prefs.edit { clear() }
    }

    private fun load(): PairedCamera? {
        val passphrase = prefs.getString(PASSPHRASE, null) ?: return null
        return PairedCamera(
            passphrase = passphrase,
            ssid = prefs.getString(SSID, null),
            bssid = prefs.getString(BSSID, null),
            mac = prefs.getString(MAC, null),
            name = prefs.getString(NAME, null),
            model = prefs.getString(MODEL, null),
            firmware = prefs.getString(FIRMWARE, null),
        )
    }

    private companion object {
        const val PASSPHRASE = "passphrase"
        const val SSID = "ssid"
        const val BSSID = "bssid"
        const val MAC = "mac"
        const val NAME = "name"
        const val MODEL = "model"
        const val FIRMWARE = "firmware"
    }
}
