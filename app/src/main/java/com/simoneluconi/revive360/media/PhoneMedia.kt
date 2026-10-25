package com.simoneluconi.revive360.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.simoneluconi.revive360.protocol.FileKind
import java.io.File

/** A picture or video the app saved on the phone (the "Revive360" folders of [MediaSaver]). */
data class PhoneItem(
    val uri: Uri,
    val name: String,
    val kind: FileKind,
    val size: Long,
    /** Unix seconds: when it was shot (the camera's time), else when it was saved. */
    val time: Long,
) {
    /** A stitched 360 copy (equirectangular), as opposed to a camera original. */
    val isStitched: Boolean get() = name.substringBeforeLast('.').endsWith(STITCHED_SUFFIX)

    val baseName: String get() = name.substringBeforeLast('.')

    /** An ordinary picture made from the sphere (a framing): opens in the phone's own viewer. */
    val isFlat: Boolean get() = kind == FileKind.PHOTO && !isStitched && !ORIGINAL.matches(baseName)

    companion object {
        const val STITCHED_SUFFIX = "_360"
        /** A camera's own name: DCF, four digits last (360_0042, SAM_0042) */
        private val ORIGINAL = Regex(""".+_\d{4}""")
    }
}

/**
 * Reads back what [MediaSaver] published. Without a media permission the system lists the entries
 * this installation created, which is what the app saved.
 */
object PhoneMedia {
    fun list(context: Context): List<PhoneItem> {
        val items = ArrayList<PhoneItem>()
        for ((collection, kind) in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to FileKind.PHOTO,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to FileKind.VIDEO,
        )) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%/${MediaSaver.FOLDER}/%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(3)
                    items += PhoneItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1) ?: continue,
                        kind = kind,
                        size = c.getLong(2),
                        time = if (taken > 0) taken / 1000 else c.getLong(4),
                    )
                }
            }
        }
        return items.sortedWith(compareByDescending<PhoneItem> { it.time }.thenByDescending { it.name })
    }

    fun thumbnail(context: Context, item: PhoneItem): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(item.uri, Size(THUMB_WIDTH, THUMB_WIDTH / 2), null) }.getOrNull()

    /** A copy in the app's cache, for the readers that want a file. */
    fun local(context: Context, item: PhoneItem): File {
        val dir = File(context.cacheDir, "phone").apply { mkdirs() }
        val file = File(dir, "${item.uri.lastPathSegment}_${item.size}_${item.name}")
        if (file.exists() && file.length() == item.size) return file
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "${file.name}.part")
        context.contentResolver.openInputStream(item.uri)?.use { input -> part.outputStream().use { input.copyTo(it) } }
            ?: error("can't read ${item.name}")
        check(part.renameTo(file)) { "can't keep ${item.name}" }
        return file
    }

    private const val THUMB_WIDTH = 480
}
