package com.simoneluconi.revive360.media

import android.content.Context
import androidx.core.content.edit
import com.simoneluconi.revive360.protocol.CameraFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which camera files are already on the phone, so the gallery can mark them. */
class SavedFiles(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("saved_files", Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow(prefs.getStringSet(KEYS, emptySet())!!.toSet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun key(camera: String, file: CameraFile): String = "$camera|${file.path}|${file.size}|${file.mtime}"

    fun add(key: String) {
        val next = _keys.value + key
        _keys.value = next
        prefs.edit { putStringSet(KEYS, next) }
    }

    private companion object {
        const val KEYS = "keys"
    }
}
