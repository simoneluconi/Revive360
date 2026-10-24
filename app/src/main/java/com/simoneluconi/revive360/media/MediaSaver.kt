package com.simoneluconi.revive360.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.FileKind
import java.io.File

/**
 * Publishes a downloaded original to the phone's shared storage, untouched (the camera's dual-fisheye
 * files are kept as they are; stitching is a later feature). Photos go to Pictures, videos to Movies,
 * RAW and anything else to Download, each in a "Revive360" folder. No storage permission needed.
 */
object MediaSaver {
    const val FOLDER = "Revive360"

    fun publish(context: Context, file: CameraFile, source: File): Uri {
        val resolver = context.contentResolver
        val (collection, directory) = when (file.kind) {
            FileKind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            FileKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            FileKind.RAW, FileKind.OTHER -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
            // The camera's time, not the download's: the phone's gallery sorts by when it was shot
            if (file.mtime > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, file.mtime)
            if (file.mtime > 0 && file.kind != FileKind.OTHER && file.kind != FileKind.RAW) {
                put(MediaStore.MediaColumns.DATE_TAKEN, file.mtime * 1000)
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("the system refused a new ${file.kind} entry")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("can't write ${file.name}")
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
