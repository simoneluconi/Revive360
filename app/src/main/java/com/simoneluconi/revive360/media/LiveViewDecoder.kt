package com.simoneluconi.revive360.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Plays the camera's live view (protocol §5.14) onto a [Surface]. The C200 sends Samsung's TTTS
 * stream: a header (`TTTS`, `VID0` with the size, `AUD0`, then `LIST`/`movi`), then chunks of
 * `id(4) size(4, big-endian) timestamp(8) data(size)`; video chunks (`00VD`) hold one HEVC access
 * unit in Annex B form, the first with its VPS/SPS/PPS. Each frame is shown as soon as it decodes:
 * a preview, not a recording.
 *
 * [run] blocks reading the stream; closing the stream ends it.
 */
class LiveViewDecoder(
    private val input: InputStream,
    private val surface: Surface,
    /** The picture size, once known (from the stream header) */
    private val onSize: (Int, Int) -> Unit,
    /** Frames shown so far */
    private val onFrame: (Long) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var frames = 0L

    /** Plays until the stream ends or fails (the exception says why) */
    fun run() {
        val data = DataInputStream(input.buffered(256 * 1024))
        try {
            val (width, height) = readHeader(data)
            onSize(width, height)
            while (true) {
                val id = ByteArray(4).also { data.readFully(it) }
                val size = data.readInt()
                val timestamp = data.readLong()
                if (size < 0 || size > MAX_CHUNK) throw IllegalStateException("chunk of $size bytes")
                val payload = ByteArray(size).also { data.readFully(it) }
                if (String(id, Charsets.US_ASCII) == VIDEO_CHUNK) video(payload, timestamp, width, height)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }
    }

    /** The size from VID0; the reader left at the first chunk (after `movi`) */
    private fun readHeader(data: DataInputStream): Pair<Int, Int> {
        val header = ByteArray(HEADER_MAX)
        var have = 0
        while (have < header.size) {
            val n = data.read(header, have, 1)
            if (n < 0) throw EOFException("the stream ended in its header")
            have += n
            if (have >= 4 && String(header, have - 4, 4, Charsets.US_ASCII) == "movi") break
        }
        if (String(header, 0, 4, Charsets.US_ASCII) != "TTTS") throw IllegalStateException("not a TTTS stream")
        val vid = indexOf(header, have, "VID0")
        if (vid < 0) throw IllegalStateException("no VID0 in the stream header")
        val box = ByteBuffer.wrap(header, vid, have - vid)
        return box.getInt(vid + 16) to box.getInt(vid + 20)
    }

    private fun video(au: ByteArray, timestamp: Long, width: Int, height: Int) {
        val codec = codec ?: start(au, width, height) ?: return
        val inIndex = codec.dequeueInputBuffer(INPUT_WAIT_US)
        if (inIndex >= 0) {
            val buffer = codec.getInputBuffer(inIndex) ?: return
            buffer.clear()
            buffer.put(au, 0, minOf(au.size, buffer.remaining()))
            codec.queueInputBuffer(inIndex, 0, buffer.position(), timestamp * 1_000_000 / TIMESCALE, 0)
        }
        // Whatever is decoded goes to the screen now
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) break
            codec.releaseOutputBuffer(outIndex, true)
            onFrame(++frames)
        }
    }

    /** The decoder, from the first access unit with parameter sets; null until one comes */
    private fun start(au: ByteArray, width: Int, height: Int): MediaCodec? {
        val config = parameterSets(au) ?: return null
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)   // real time
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).also {
            it.configure(format, surface, null, 0)
            it.start()
            codec = it
        }
    }

    companion object {
        private const val VIDEO_CHUNK = "00VD"
        /** The stream's clock: 120 kHz (its VID0/AUD0 timescale) */
        private const val TIMESCALE = 120_000L
        private const val HEADER_MAX = 4096
        private const val MAX_CHUNK = 8 * 1024 * 1024
        private const val INPUT_WAIT_US = 100_000L

        private const val NAL_VPS = 32
        private const val NAL_SPS = 33
        private const val NAL_PPS = 34

        /** The VPS, SPS and PPS NAL units of an Annex B access unit, with their start codes */
        fun parameterSets(au: ByteArray): ByteArray? {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 < au.size) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                    starts += i + 3
                    i += 3
                } else {
                    i++
                }
            }
            val out = java.io.ByteArrayOutputStream()
            for ((k, start) in starts.withIndex()) {
                val type = (au[start].toInt() shr 1) and 0x3f
                if (type != NAL_VPS && type != NAL_SPS && type != NAL_PPS) continue
                // Up to the next start code (its zero bytes excluded)
                var end = if (k + 1 < starts.size) starts[k + 1] - 3 else au.size
                while (end > start && au[end - 1].toInt() == 0) end--
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(au, start, end - start)
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }

        private fun indexOf(bytes: ByteArray, length: Int, tag: String): Int {
            val t = tag.toByteArray(Charsets.US_ASCII)
            for (i in 0..length - t.size) if ((t.indices).all { bytes[i + it] == t[it] }) return i
            return -1
        }
    }
}
