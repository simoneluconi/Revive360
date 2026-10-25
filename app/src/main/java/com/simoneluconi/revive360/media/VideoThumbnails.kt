package com.simoneluconi.revive360.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.simoneluconi.revive360.protocol.CameraClient
import com.simoneluconi.revive360.protocol.CameraFile
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore

/**
 * A video's first frame as a JPEG thumbnail, decoded on the phone: the camera stores none in its
 * MP4s. The file is read from the camera in blocks with `Range`, so only what the decoder asks for
 * travels (the index, at the end of the camera's MP4s, and the first frame).
 */
object VideoThumbnails {
    private const val BLOCK_BYTES = 256 * 1024
    private const val CACHED_BLOCKS = 24
    private const val WIDTH = 640
    private const val JPEG_QUALITY = 85

    /** A 4K HEVC decode each: a gallery full of videos must not start them all at once */
    private val decoders = Semaphore(2)

    /** The JPEG, or null when the video does not decode. Network and decoder work: call off the main thread. */
    fun firstFrame(client: CameraClient, file: CameraFile): ByteArray? {
        decoders.acquire()
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(RangeSource(client, file.path, file.size))
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val frame = if (w > 0 && h > 0) {
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH * h / w)
                } else {
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: return null
                return ByteArrayOutputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    frame.recycle()
                    out.toByteArray()
                }
            } finally {
                retriever.release()
            }
        } finally {
            decoders.release()
        }
    }

    /** The camera file as a seekable source, in cached blocks. */
    private class RangeSource(
        private val client: CameraClient,
        private val path: String,
        private val fileSize: Long,
    ) : MediaDataSource() {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(CACHED_BLOCKS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > CACHED_BLOCKS
        }

        override fun getSize(): Long = fileSize

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= fileSize) return -1
            var done = 0
            while (done < size && position + done < fileSize) {
                val at = position + done
                val index = at / BLOCK_BYTES
                val block = blocks.getOrPut(index) { client.readRange(path, index * BLOCK_BYTES, BLOCK_BYTES) }
                val within = (at - index * BLOCK_BYTES).toInt()
                if (within >= block.size) break
                val n = minOf(size - done, block.size - within)
                System.arraycopy(block, within, buffer, offset + done, n)
                done += n
            }
            return if (done == 0) -1 else done
        }

        override fun close() {
            blocks.clear()
        }
    }
}
