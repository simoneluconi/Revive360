package com.simoneluconi.revive360.media

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.simoneluconi.revive360.protocol.CameraFile
import java.io.File
import java.security.MessageDigest

/**
 * Thumbnails of camera files, in memory and on disk (cache dir, so the system may clear it). Keyed
 * by camera + path + size + mtime (protocol §5.5): a file replaced under the same name gets a new
 * thumbnail. [MISSING] remembers files without one so the grid doesn't ask again.
 */
class ThumbCache(context: Context) {
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = LruCache<String, ImageBitmap>(MEMORY_ENTRIES)
    private val missing = HashSet<String>()

    fun key(camera: String, file: CameraFile): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$camera|${file.path}|${file.size}|${file.mtime}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** From memory only: cheap enough for composition. */
    fun peek(key: String): ImageBitmap? = memory.get(key)

    fun isMissing(key: String): Boolean = synchronized(missing) { key in missing }

    /** From memory or disk; null if not cached. Disk reads: call off the main thread. */
    fun get(key: String): ImageBitmap? {
        memory.get(key)?.let { return it }
        val file = File(dir, "$key.jpg")
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.path)?.asImageBitmap() ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /** Stores the camera's JPEG bytes; null when they don't decode. */
    fun put(key: String, jpeg: ByteArray): ImageBitmap? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() ?: return null.also { markMissing(key) }
        memory.put(key, bitmap)
        runCatching { File(dir, "$key.jpg").writeBytes(jpeg) }
        return bitmap
    }

    fun markMissing(key: String) {
        synchronized(missing) { missing += key }
    }

    fun forget(key: String) {
        memory.remove(key)
        File(dir, "$key.jpg").delete()
    }

    private companion object {
        const val MEMORY_ENTRIES = 240
    }
}
