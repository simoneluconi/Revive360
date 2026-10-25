package com.simoneluconi.revive360.protocol

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** An open live view: its container type and bytes; [close] ends it (the camera then stops its stream). */
class LiveStream(val contentType: String, val input: InputStream, private val disconnect: () -> Unit) : java.io.Closeable {
    override fun close() {
        runCatching { input.close() }
        disconnect()
    }
}

/** Opens a connection: through the camera's Wi-Fi `Network` in the app, plainly in tests and on the simulator. */
fun interface Connector {
    fun open(url: URL): HttpURLConnection
}

/**
 * The camera's HTTP API (docs/protocol.md §5). Blocking calls: run them on an IO dispatcher. Every
 * non-2xx answer becomes a [CameraException] with the protocol's error code.
 */
class CameraClient(host: String, port: Int = Protocol.PORT, private val connector: Connector) {
    private val base = "http://${if (':' in host) "[$host]" else host}:$port"

    fun info(): CameraInfo = ProtocolJson.info(text("GET", "/api/v1/info"))

    fun status(): CameraStatus = ProtocolJson.status(text("GET", "/api/v1/status"))

    fun files(): List<CameraFile> = ProtocolJson.files(text("GET", "/api/v1/files"))

    /** Waits for the camera to write the file(s): up to its own 30 s timeout, hence the long read timeout. */
    fun capture(mode: CaptureMode, quality: String? = null, resolution: String? = null): CaptureResult =
        ProtocolJson.capture(
            text("POST", "/api/v1/capture", ProtocolJson.captureRequest(mode, quality, resolution), CAPTURE_READ_TIMEOUT_MS)
        )

    fun delete(path: String) {
        text("DELETE", "/files/${CameraPaths.encode(path)}")
    }

    fun setLed(id: String, state: LedState, onMs: Int? = null, offMs: Int? = null) {
        text("POST", "/api/v1/led", ProtocolJson.ledRequest(id, state, onMs, offMs))
    }

    /** Current camera settings (protocol §5.12). */
    fun settings(): CameraSettings = ProtocolJson.settings(text("GET", "/api/v1/settings"))

    /** Changes settings; answers the values after the change. */
    fun setSettings(changes: Map<String, String>): CameraSettings =
        ProtocolJson.settings(text("POST", "/api/v1/settings", ProtocolJson.settingsRequest(changes)))

    /** Starts or stops a video recording (protocol §5.13). Stopping waits for the finished file. */
    fun record(start: Boolean): RecordResult =
        ProtocolJson.record(
            text("POST", "/api/v1/record", ProtocolJson.recordRequest(start), if (start) READ_TIMEOUT_MS else CAPTURE_READ_TIMEOUT_MS)
        )

    /** Sets the camera clock and time zone (protocol §5.10). */
    fun setTime(utcMs: Long, tz: String?, tzOffsetMin: Int): TimeResult =
        ProtocolJson.time(text("POST", "/api/v1/time", ProtocolJson.timeRequest(utcMs, tz, tzOffsetMin)))

    /** Reboots ("reboot") or switches off ("off") the camera (protocol §5.15); the link drops after. */
    fun power(action: String) {
        text("POST", "/api/v1/power", org.json.JSONObject().put("action", action).toString())
    }

    fun log(lines: Int = 200): String = text("GET", "/api/v1/log?lines=$lines")

    /** The embedded JPEG thumbnail, or null when the file has none (`no_thumbnail`). */
    fun thumbnail(path: String): ByteArray? = try {
        exchange("GET", "/api/v1/thumb/${CameraPaths.encode(path)}") { it.readBytes() }
    } catch (e: CameraException) {
        if (e.code == CameraException.NO_THUMBNAIL || e.code == CameraException.NOT_FOUND) null else throw e
    }

    /**
     * Downloads [path] into [target]. A partial [target] is resumed with `Range` when the camera allows
     * it ([resume]); a camera that answers 200 instead restarts it from zero. [progress] gets
     * (bytes so far, total) and may throw to cancel.
     */
    fun download(path: String, target: File, resume: Boolean, progress: (Long, Long) -> Unit) {
        val offset = if (resume && target.exists()) target.length() else 0L
        val connection = open("GET", "/files/${CameraPaths.encode(path)}", DOWNLOAD_READ_TIMEOUT_MS)
        try {
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val code = connection.responseCode
            if (code == 416 && offset > 0) {
                // Already complete (or the file shrank): the size check below decides
                val total = contentRangeTotal(connection.getHeaderField("Content-Range")) ?: offset
                if (total == offset) return progress(offset, total)
            }
            if (code !in 200..299) throw failure(connection, code)
            val append = code == 206 && offset > 0
            val start = if (append) offset else 0L
            val total = if (append) {
                contentRangeTotal(connection.getHeaderField("Content-Range")) ?: (start + connection.contentLengthLong)
            } else {
                connection.contentLengthLong
            }
            FileOutputStream(target, append).use { out ->
                connection.inputStream.use { input -> copy(input, out, start, total, progress) }
            }
            if (total >= 0 && target.length() != total) throw IOException("download ended at ${target.length()} of $total bytes")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The live view stream (protocol §5.14), open until [LiveStream.close]. The camera's stream starts
     * its camcorder a few seconds after the request: the read timeout leaves room for it.
     */
    fun openLiveView(quality: String? = null): LiveStream {
        val connection = open("GET", "/api/v1/liveview" + (quality?.let { "?quality=$it" } ?: ""), LIVE_READ_TIMEOUT_MS)
        val code = connection.responseCode
        if (code != 200) {
            val failure = failure(connection, code)
            connection.disconnect()
            throw failure
        }
        return LiveStream(connection.contentType ?: "", connection.inputStream) { connection.disconnect() }
    }

    /** Bytes [offset] to [offset] + [length] of [path] (protocol §5.5 `Range`); fewer at the end, none past it. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val connection = open("GET", "/files/${CameraPaths.encode(path)}", READ_TIMEOUT_MS)
        try {
            connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            val code = connection.responseCode
            if (code == 416) return ByteArray(0)
            // A 200 would be the whole file: not what a reader of small pieces wants
            if (code != 206) throw if (code in 200..299) IOException("the camera ignored Range") else failure(connection, code)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream, start: Long, total: Long, progress: (Long, Long) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var done = start
        var reported = 0L
        progress(done, total)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            // A callback every 256 KB: fine-grained enough for a progress bar, cheap on the UI
            if (done - reported >= PROGRESS_STEP_BYTES) {
                reported = done
                progress(done, total)
            }
        }
        progress(done, total)
    }

    private fun text(method: String, path: String, body: String? = null, readTimeoutMs: Int = READ_TIMEOUT_MS): String =
        exchange(method, path, body, readTimeoutMs) { it.readBytes().toString(Charsets.UTF_8) }

    private fun <T> exchange(
        method: String,
        path: String,
        body: String? = null,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        read: (InputStream) -> T,
    ): T {
        val connection = open(method, path, readTimeoutMs)
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw failure(connection, code)
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, path: String, readTimeoutMs: Int): HttpURLConnection =
        connector.open(URL(base + path)).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            useCaches = false
            // The camera may close after every answer (protocol §2): never reuse a socket it dropped
            setRequestProperty("Connection", "close")
        }

    private fun failure(connection: HttpURLConnection, code: Int): CameraException {
        val text = runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        return ProtocolJson.error(code, text) ?: CameraException(code, CameraException.MALFORMED, "HTTP $code")
    }

    private fun contentRangeTotal(header: String?): Long? = header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
        const val LIVE_READ_TIMEOUT_MS = 40_000
        /** The camera's capture timeout is 30 s (protocol §5.3). */
        const val CAPTURE_READ_TIMEOUT_MS = 45_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 20_000
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
