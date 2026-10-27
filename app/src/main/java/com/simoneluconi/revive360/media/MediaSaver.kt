package com.simoneluconi.revive360.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.FileKind
import java.io.File
import java.io.OutputStream

/**
 * Publishes a downloaded original to the phone's shared storage, untouched (the camera's dual-fisheye
 * files are kept as they are; a stitched copy is a file of its own). Photos go to Pictures, videos to Movies,
 * RAW and anything else to Download, each in a "Revive360" folder. No storage permission needed.
 */
object MediaSaver {
    const val FOLDER = "Revive360"

    fun publish(context: Context, file: CameraFile, source: File): Uri =
        publish(context, file.name, file.kind, mimeType(file), file.mtime, source)

    /** A file made on the phone from a camera file (a stitched 360 photo), next to the originals. */
    fun publish(context: Context, name: String, kind: FileKind, mime: String, mtime: Long, source: File): Uri =
        publish(context, name, kind, mime, mtime) { out -> source.inputStream().use { it.copyTo(out) } }

    /** As above, the content written straight into the new entry by [write] (a DNG made on the way). */
    fun publish(context: Context, name: String, kind: FileKind, mime: String, mtime: Long, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val (collection, directory) = when (kind) {
            FileKind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            FileKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            FileKind.RAW, FileKind.OTHER -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
            // The camera's time, not the download's: the phone's gallery sorts by when it was shot
            if (mtime > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, mtime)
            if (mtime > 0 && kind != FileKind.OTHER && kind != FileKind.RAW) {
                put(MediaStore.MediaColumns.DATE_TAKEN, mtime * 1000)
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("the system refused a new $kind entry")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("can't write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    fun mimeType(file: CameraFile): String = when (file.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "srw" -> "image/x-samsung-srw"
        "dng" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
}
