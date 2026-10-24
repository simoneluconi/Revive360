package com.simoneluconi.revive360.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A camera this phone is paired with: how to join its Wi-Fi and what it said about itself.
 * [ssid] and [bssid] come from the system's companion scan; when they are unknown the app joins by
 * the SSID prefix instead (the system then asks every time).
 */
data class PairedCamera(
    /** Stable key in this app; not the camera's identity. */
    val id: String,
    val passphrase: String,
    val ssid: String? = null,
    val bssid: String? = null,
    /** `info.camera.mac`, once the camera has answered. Identity, not the BSSID. */
    val mac: String? = null,
    /** Display name, model and firmware from the last `info`, for the screens shown before connecting. */
    val name: String? = null,
    val model: String? = null,
    val firmware: String? = null,
) {
    /** What the user sees in lists: the camera's own name, else its network. */
    val label: String get() = name?.takeIf { it.isNotEmpty() } ?: ssid ?: model ?: "Gear 360"
}

/** The paired cameras and which one the app talks to. */
class CameraStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("camera", Context.MODE_PRIVATE)

    private val _cameras = MutableStateFlow(load())
    val cameras: StateFlow<List<PairedCamera>> = _cameras.asStateFlow()

    private val _camera = MutableStateFlow(selectedOf(_cameras.value, prefs.getString(SELECTED, null)))
    /** The camera in use, or null when none is paired. */
    val camera: StateFlow<PairedCamera?> = _camera.asStateFlow()

    /**
     * Adds a camera and makes it the one in use. A camera already paired with the same BSSID
     * (set up again) or the given [replaceId] is replaced, keeping its id.
     */
    fun add(camera: PairedCamera, replaceId: String? = null): PairedCamera {
        val list = _cameras.value
        val old = list.firstOrNull { it.id == replaceId }
            ?: camera.bssid?.let { b -> list.firstOrNull { it.bssid.equals(b, ignoreCase = true) } }
        val saved = if (old != null) camera.copy(id = old.id, mac = camera.mac ?: old.mac, name = camera.name ?: old.name,
            model = camera.model ?: old.model, firmware = camera.firmware ?: old.firmware) else camera
        val next = if (old != null) list.map { if (it.id == old.id) saved else it } else list + saved
        persist(next, saved.id)
        return saved
    }

    fun select(id: String) {
        if (_cameras.value.any { it.id == id }) persist(_cameras.value, id)
    }

    /** Changes the camera in use (what it reported about itself). */
    fun update(change: (PairedCamera) -> PairedCamera) {
        val current = _camera.value ?: return
        persist(_cameras.value.map { if (it.id == current.id) change(it) else it }, current.id)
    }

    /** Removes [id]; another camera, if any, becomes the one in use. */
    fun forget(id: String) {
        val next = _cameras.value.filterNot { it.id == id }
        val selected = _camera.value?.id?.takeIf { it != id } ?: next.firstOrNull()?.id
        persist(next, selected)
    }

    private fun persist(list: List<PairedCamera>, selectedId: String?) {
        _cameras.value = list
        _camera.value = selectedOf(list, selectedId)
        prefs.edit {
            clear()
            putString(CAMERAS, JSONArray(list.map(::toJson)).toString())
            putString(SELECTED, _camera.value?.id)
        }
    }

    private fun load(): List<PairedCamera> {
        prefs.getString(CAMERAS, null)?.let { text ->
            return runCatching {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        // Before more cameras were possible, the one camera was stored as flat keys
        val passphrase = prefs.getString(LEGACY_PASSPHRASE, null) ?: return emptyList()
        val legacy = PairedCamera(
            id = UUID.randomUUID().toString(),
            passphrase = passphrase,
            ssid = prefs.getString("ssid", null),
            bssid = prefs.getString("bssid", null),
            mac = prefs.getString("mac", null),
            name = prefs.getString("name", null),
            model = prefs.getString("model", null),
            firmware = prefs.getString("firmware", null),
        )
        prefs.edit {
            clear()
            putString(CAMERAS, JSONArray(listOf(toJson(legacy))).toString())
            putString(SELECTED, legacy.id)
        }
        return listOf(legacy)
    }

    private fun selectedOf(list: List<PairedCamera>, id: String?): PairedCamera? =
        list.firstOrNull { it.id == id } ?: list.firstOrNull()

    private fun toJson(c: PairedCamera) = JSONObject().apply {
        put("id", c.id)
        put("passphrase", c.passphrase)
        c.ssid?.let { put("ssid", it) }
        c.bssid?.let { put("bssid", it) }
        c.mac?.let { put("mac", it) }
        c.name?.let { put("name", it) }
        c.model?.let { put("model", it) }
        c.firmware?.let { put("firmware", it) }
    }

    private fun fromJson(o: JSONObject): PairedCamera? {
        fun opt(key: String) = if (o.has(key) && !o.isNull(key)) o.getString(key) else null
        return PairedCamera(
            id = opt("id") ?: return null,
            passphrase = opt("passphrase") ?: return null,
            ssid = opt("ssid"),
            bssid = opt("bssid"),
            mac = opt("mac"),
            name = opt("name"),
            model = opt("model"),
            firmware = opt("firmware"),
        )
    }

    private companion object {
        const val CAMERAS = "cameras"
        const val SELECTED = "selected"
        const val LEGACY_PASSPHRASE = "passphrase"
    }
}
