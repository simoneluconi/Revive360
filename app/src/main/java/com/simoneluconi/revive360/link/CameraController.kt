package com.simoneluconi.revive360.link

import android.content.Context
import android.net.Network
import androidx.compose.ui.graphics.ImageBitmap
import com.simoneluconi.revive360.BuildConfig
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.media.SavedFiles
import com.simoneluconi.revive360.media.ThumbCache
import com.simoneluconi.revive360.protocol.CameraClient
import com.simoneluconi.revive360.protocol.CameraException
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.CameraInfo
import com.simoneluconi.revive360.protocol.CameraStatus
import com.simoneluconi.revive360.protocol.CaptureMode
import com.simoneluconi.revive360.protocol.CaptureResult
import com.simoneluconi.revive360.protocol.Connector
import com.simoneluconi.revive360.protocol.LedState
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.settings.AppSettings
import com.simoneluconi.revive360.settings.CameraStore
import com.simoneluconi.revive360.settings.PairedCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Where the link to the camera stands. */
sealed interface LinkState {
    data object NotPaired : LinkState
    /** Paired, not connected: the app is in the background, or the user hasn't connected yet. */
    data object Idle : LinkState
    /** Waiting for the camera's Wi-Fi (the system is searching, or showing its dialog). */
    data object Joining : LinkState
    /** On the camera's Wi-Fi, waiting for its server to answer. */
    data object Handshake : LinkState
    data class Connected(val info: CameraInfo) : LinkState
    data class Failed(val reason: Failure, val detail: String? = null) : LinkState
}

enum class Failure {
    /** The camera's Wi-Fi wasn't found, or the user declined the system dialog. */
    NOT_FOUND,
    /** On its Wi-Fi, but the camera's server doesn't answer. */
    NO_SERVER,
    /** The camera speaks a protocol version this app doesn't know. */
    UNSUPPORTED_VERSION,
}

data class DownloadProgress(val done: Long, val total: Long, val failed: String? = null) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * The process-wide link to the camera: joins its Wi-Fi while the app is in use, says hello, polls
 * its status and runs captures, file transfers and LED commands. Screens only read the flows and
 * call these methods; nothing here depends on a camera model.
 */
class CameraController(
    private val context: Context,
    private val store: CameraStore,
    private val settings: AppSettings,
    val thumbs: ThumbCache,
    val saved: SavedFiles,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val network = CameraNetwork(context)

    private val _state = MutableStateFlow<LinkState>(if (store.camera.value == null) LinkState.NotPaired else LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _status = MutableStateFlow<CameraStatus?>(null)
    val status: StateFlow<CameraStatus?> = _status.asStateFlow()

    private val _files = MutableStateFlow<List<CameraFile>?>(null)
    /** The camera's files, newest first; null until listed. */
    val files: StateFlow<List<CameraFile>?> = _files.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _lastCapture = MutableStateFlow<CaptureResult?>(null)
    val lastCapture: StateFlow<CaptureResult?> = _lastCapture.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    /** Transfers in progress (or failed) by file path. */
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    @Volatile
    private var client: CameraClient? = null
    private var session: Job? = null
    private var releaseJob: Job? = null
    private val downloadJobs = HashMap<String, Job>()

    /** The connected camera's identity, for cache keys. */
    private val cameraKey: String get() = (state.value as? LinkState.Connected)?.info?.mac ?: store.camera.value?.mac ?: "camera"

    // ---- lifecycle ----

    fun onForeground() {
        releaseJob?.cancel()
        releaseJob = null
        if (session == null) connect()
    }

    /** Lets the camera's Wi-Fi go shortly after the app leaves the screen, unless a download runs. */
    fun onBackground() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(BACKGROUND_RELEASE_MS)
            while (downloadJobs.isNotEmpty()) delay(BACKGROUND_RELEASE_MS)
            EventLog.log("In the background: releasing the camera link")
            disconnect(LinkState.Idle)
        }
    }

    /** (Re)joins the camera, or the developer's simulator host in debug builds. */
    fun connect() {
        val camera = store.camera.value
        val devHost = settings.devHost.value?.takeIf { BuildConfig.DEBUG }
        if (camera == null && devHost == null) {
            _state.value = LinkState.NotPaired
            return
        }
        disconnect(LinkState.Joining)
        if (devHost != null) {
            val (host, port) = parseHost(devHost)
            EventLog.log("Developer: using the camera server at $host:$port over the normal network")
            startSession(CameraClient(host, port, Connector { it.openConnection() as HttpURLConnection }))
            return
        }
        network.request(camera!!, object : CameraNetwork.Listener {
            override fun onAvailable(network: Network, host: String) {
                scope.launch {
                    startSession(CameraClient(host, Protocol.PORT, Connector { network.openConnection(it) as HttpURLConnection }))
                }
            }

            override fun onLost() {
                scope.launch {
                    stopSession()
                    if (_state.value !is LinkState.Idle) _state.value = LinkState.Joining
                }
            }

            override fun onUnavailable() {
                scope.launch {
                    stopSession()
                    _state.value = LinkState.Failed(Failure.NOT_FOUND)
                }
            }
        })
    }

    private fun disconnect(next: LinkState) {
        network.release()
        stopSession()
        _state.value = if (store.camera.value == null && settings.devHost.value == null) LinkState.NotPaired else next
    }

    private fun stopSession() {
        session?.cancel()
        session = null
        client = null
        _status.value = null
    }

    /** Hello, then status polling, until cancelled. The server may still be starting: hello retries. */
    private fun startSession(client: CameraClient) {
        stopSession()
        this.client = client
        _state.value = LinkState.Handshake
        session = scope.launch {
            val info = hello(client) ?: return@launch
            if (info.protocol != Protocol.VERSION) {
                EventLog.log("Camera speaks protocol ${info.protocol}, this app ${Protocol.VERSION}")
                _state.value = LinkState.Failed(Failure.UNSUPPORTED_VERSION, info.protocol.toString())
                return@launch
            }
            EventLog.log(
                "Connected: ${info.model} ${info.firmware} (adapter ${info.adapter}, ${info.adapterStatus}), " +
                    "server ${info.serverName} ${info.serverVersion}"
            )
            store.update { it.copy(mac = info.mac, name = info.name, model = info.model, firmware = info.firmware) }
            _state.value = LinkState.Connected(info)
            if (info.capabilities.listFiles) launch { refreshFiles() }
            poll(client)
        }
    }

    private suspend fun hello(client: CameraClient): CameraInfo? {
        repeat(HELLO_ATTEMPTS) { attempt ->
            try {
                return withContext(Dispatchers.IO) { client.info() }
            } catch (e: CameraException) {
                EventLog.log("Hello: unreadable answer (${e.message})")
                _state.value = LinkState.Failed(Failure.NO_SERVER, e.message)
                return null
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                if (attempt == 0 || attempt == HELLO_ATTEMPTS - 1) EventLog.log("Hello: no answer yet (${e.message})")
                delay(HELLO_RETRY_MS)
            }
        }
        _state.value = LinkState.Failed(Failure.NO_SERVER)
        return null
    }

    private suspend fun poll(client: CameraClient) {
        var failures = 0
        var lastUptime = -1L
        while (coroutineContext.isActive) {
            try {
                val status = withContext(Dispatchers.IO) { client.status() }
                if (lastUptime >= 0 && status.uptimeS < lastUptime) EventLog.log("The camera's controller restarted")
                lastUptime = status.uptimeS
                _status.value = status
                failures = 0
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                failures++
                EventLog.log("Status: failed ($failures) ${e.message}")
                if (failures >= POLL_FAILURES) {
                    // The Wi-Fi may still be up while the server is gone: say hello again
                    _status.value = null
                    _state.value = LinkState.Handshake
                    val info = hello(client) ?: return
                    _state.value = LinkState.Connected(info)
                    failures = 0
                }
            }
            delay(POLL_MS)
        }
    }

    // ---- pairing ----

    fun pair(passphrase: String, found: CompanionWifi.Found?) {
        EventLog.log("Pairing: ${found?.ssid ?: "any ${Protocol.SSID_PREFIX} network"}${found?.bssid?.let { " ($it)" } ?: ""}")
        store.save(PairedCamera(passphrase = passphrase, ssid = found?.ssid, bssid = found?.bssid))
        connect()
    }

    fun forget() {
        EventLog.log("Forgetting the camera")
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        _downloads.value = emptyMap()
        CompanionWifi.forgetAll(context)
        store.forget()
        _files.value = null
        _lastCapture.value = null
        disconnect(LinkState.NotPaired)
    }

    // ---- commands ----

    private fun requireClient(): CameraClient = client ?: throw IllegalStateException("not connected")

    suspend fun capture(mode: CaptureMode, quality: String?): CaptureResult {
        val client = requireClient()
        _capturing.value = true
        try {
            EventLog.log("Capture: ${mode.wire}${quality?.let { " / $it" } ?: ""}")
            val result = withContext(Dispatchers.IO) { client.capture(mode, quality) }
            EventLog.log("Capture: ${result.files.joinToString { it.name }} in ${result.durationMs} ms")
            _lastCapture.value = result
            // New files first, then a fresh listing replaces them
            _files.update { list -> list?.let { result.files + it.filterNot { f -> result.files.any { n -> n.path == f.path } } } }
            scope.launch { runCatching { refreshFiles() } }
            return result
        } catch (e: Exception) {
            EventLog.log("Capture failed: ${e.message}")
            throw e
        } finally {
            _capturing.value = false
        }
    }

    suspend fun refreshFiles() {
        val client = client ?: return
        try {
            val list = withContext(Dispatchers.IO) { client.files() }
            _files.value = list
        } catch (e: Exception) {
            EventLog.log("Files: listing failed (${e.message})")
            throw e
        }
    }

    suspend fun delete(file: CameraFile) {
        val client = requireClient()
        withContext(Dispatchers.IO) { client.delete(file.path) }
        EventLog.log("Deleted ${file.path}")
        thumbs.forget(thumbs.key(cameraKey, file))
        _files.update { list -> list?.filterNot { it.path == file.path } }
    }

    suspend fun setLed(id: String, state: LedState, onMs: Int? = null, offMs: Int? = null) {
        val client = requireClient()
        withContext(Dispatchers.IO) { client.setLed(id, state, onMs, offMs) }
        EventLog.log("LED $id: ${state.wire}")
    }

    suspend fun cameraLog(lines: Int = 400): String {
        val client = requireClient()
        return withContext(Dispatchers.IO) { client.log(lines) }
    }

    /** The thumbnail from the cache or the camera; null when the file has none. */
    suspend fun thumbnail(file: CameraFile): ImageBitmap? {
        val key = thumbs.key(cameraKey, file)
        thumbs.peek(key)?.let { return it }
        if (thumbs.isMissing(key)) return null
        return withContext(Dispatchers.IO) {
            thumbs.get(key) ?: run {
                val client = client ?: return@withContext null
                val bytes = try {
                    client.thumbnail(file.path)
                } catch (e: Exception) {
                    // Not remembered as missing: a dropped link is not a file without thumbnail
                    return@withContext null
                }
                if (bytes == null) {
                    thumbs.markMissing(key)
                    null
                } else {
                    thumbs.put(key, bytes)
                }
            }
        }
    }

    fun savedKey(file: CameraFile): String = saved.key(cameraKey, file)

    /** Downloads the original to the phone's shared storage; resumes a broken transfer where it stopped. */
    fun download(file: CameraFile) {
        if (downloadJobs.containsKey(file.path)) return
        val client = client ?: return
        val info = (state.value as? LinkState.Connected)?.info
        val resume = info?.capabilities?.range == true
        val key = savedKey(file)
        _downloads.update { it + (file.path to DownloadProgress(0, file.size)) }
        val job = scope.launch {
            val part = partFile(key)
            try {
                EventLog.log("Download: ${file.path} (${file.size} bytes)${if (part.exists()) ", resuming at ${part.length()}" else ""}")
                withContext(Dispatchers.IO) {
                    client.download(file.path, part, resume) { done, total ->
                        ensureActive()
                        _downloads.update { it + (file.path to DownloadProgress(done, if (total > 0) total else file.size)) }
                    }
                    MediaSaver.publish(context, file, part)
                    part.delete()
                }
                saved.add(key)
                EventLog.log("Download: ${file.name} saved")
                _downloads.update { it - file.path }
            } catch (e: Exception) {
                val cancelled = !coroutineContext.isActive
                EventLog.log("Download: ${file.name} ${if (cancelled) "cancelled" else "failed (${e.message})"}")
                _downloads.update {
                    if (cancelled) it - file.path
                    else it + (file.path to (it[file.path] ?: DownloadProgress(0, file.size)).copy(failed = e.message ?: "error"))
                }
            } finally {
                downloadJobs.remove(file.path)
            }
        }
        downloadJobs[file.path] = job
    }

    fun cancelDownload(file: CameraFile) {
        downloadJobs.remove(file.path)?.cancel()
        _downloads.update { it - file.path }
    }

    fun dismissDownload(file: CameraFile) {
        if (!downloadJobs.containsKey(file.path)) _downloads.update { it - file.path }
    }

    private fun partFile(key: String): File {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.part")
    }

    private fun parseHost(text: String): Pair<String, Int> {
        val colon = text.lastIndexOf(':')
        val port = if (colon > 0) text.substring(colon + 1).toIntOrNull() else null
        return if (port != null) text.substring(0, colon) to port else text to Protocol.PORT
    }

    private companion object {
        const val BACKGROUND_RELEASE_MS = 20_000L
        const val HELLO_ATTEMPTS = 15
        const val HELLO_RETRY_MS = 2_000L
        const val POLL_MS = 5_000L
        const val POLL_FAILURES = 3
    }
}
