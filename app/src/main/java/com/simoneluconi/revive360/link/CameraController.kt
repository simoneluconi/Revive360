package com.simoneluconi.revive360.link

import android.content.Context
import android.net.Network
import androidx.compose.ui.graphics.ImageBitmap
import com.simoneluconi.revive360.BuildConfig
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.media.SavedFiles
import com.simoneluconi.revive360.media.ThumbCache
import com.simoneluconi.revive360.media.VideoThumbnails
import com.simoneluconi.revive360.protocol.CameraClient
import com.simoneluconi.revive360.protocol.CameraException
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.CameraInfo
import com.simoneluconi.revive360.protocol.CameraStatus
import com.simoneluconi.revive360.protocol.CaptureMode
import com.simoneluconi.revive360.protocol.CaptureResult
import com.simoneluconi.revive360.protocol.Connector
import com.simoneluconi.revive360.protocol.LedState
import com.simoneluconi.revive360.protocol.LiveStream
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.protocol.CameraSettings
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
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** The phone's Wi-Fi link to the camera. */
data class WifiLink(val frequencyMhz: Int, val linkMbps: Int) {
    /** 2.4, 5 or 6 (GHz), from the frequency */
    val band: String get() = when {
        frequencyMhz < 3000 -> "2.4"
        frequencyMhz < 5925 -> "5"
        else -> "6"
    }

    /** The channel number of the frequency, 0 when unknown */
    val channel: Int get() = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5000..5895 -> (frequencyMhz - 5000) / 5
        frequencyMhz >= 5955 -> (frequencyMhz - 5950) / 5
        else -> 0
    }
}

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

    private val _settings = MutableStateFlow<CameraSettings?>(null)
    /**
     * The camera's current settings and what it offers now (protocol §5.12); null until read or when
     * it has none. Re-read with every status poll: the camera's own buttons change them too.
     */
    val cameraSettings: StateFlow<CameraSettings?> = _settings.asStateFlow()
    private var settingsWrites = 0
    private var settingsWriting = false

    private val _link = MutableStateFlow<WifiLink?>(null)
    /** The phone's link to the camera's Wi-Fi, for display; null when not on it. */
    val link: StateFlow<WifiLink?> = _link.asStateFlow()

    private val _recordBusy = MutableStateFlow(false)
    /** A record start or stop is running (stopping waits for the finished video). */
    val recordBusy: StateFlow<Boolean> = _recordBusy.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    /** Transfers in progress (or failed) by file path. */
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    @Volatile
    private var client: CameraClient? = null
    private var session: Job? = null
    private var releaseJob: Job? = null
    private var rejoinJob: Job? = null
    private var rejoinAttempts = 0
    private var foreground = false
    private val downloadJobs = HashMap<String, Job>()

    /** The connected camera's identity, for cache keys. */
    private val cameraKey: String get() = (state.value as? LinkState.Connected)?.info?.mac ?: store.camera.value?.mac ?: "camera"

    // ---- lifecycle ----

    fun onForeground() {
        foreground = true
        releaseJob?.cancel()
        releaseJob = null
        // A request still searching is left alone: a new one would cancel a join in progress
        val state = _state.value
        if (session == null && state != LinkState.Joining && state != LinkState.Handshake) connect()
    }

    /** Lets the camera's Wi-Fi go shortly after the app leaves the screen, unless a download runs. */
    fun onBackground() {
        foreground = false
        rejoinJob?.cancel()
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
        rejoinAttempts = 0
        join()
    }

    private fun join() {
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
            override fun onLink(frequencyMhz: Int, linkMbps: Int) {
                _link.value = WifiLink(frequencyMhz, linkMbps)
            }

            override fun onAvailable(network: Network, host: String) {
                scope.launch {
                    rejoinAttempts = 0
                    startSession(CameraClient(host, Protocol.PORT, Connector { network.openConnection(it) as HttpURLConnection }))
                }
            }

            override fun onLost() {
                // The camera went off or restarted: the system does not join it again by itself,
                // so a new request waits for it to come back
                scope.launch {
                    stopSession()
                    if (_state.value is LinkState.Idle) return@launch
                    _state.value = LinkState.Joining
                    rejoin(camera)
                }
            }

            override fun onUnavailable() {
                scope.launch {
                    stopSession()
                    // Pinned to the paired access point there is no dialog to decline: the request
                    // was refused (made a moment before the app counted as foreground) or timed out
                    if (camera.bssid != null && rejoinAttempts < REJOIN_ATTEMPTS && rejoin(camera)) return@launch
                    _state.value = LinkState.Failed(Failure.NOT_FOUND)
                }
            }
        })
    }

    /** A new network request shortly, while the app is in use; false when it isn't. */
    private fun rejoin(camera: PairedCamera): Boolean {
        if (!foreground) return false
        rejoinJob?.cancel()
        rejoinAttempts++
        rejoinJob = scope.launch {
            delay(REJOIN_MS)
            if (store.camera.value?.id == camera.id && _state.value == LinkState.Joining) join()
        }
        return true
    }

    private fun disconnect(next: LinkState) {
        rejoinJob?.cancel()
        _link.value = null
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
            if (info.capabilities.time) launch { syncTime(client) }
            if (info.capabilities.listFiles) launch { refreshFiles() }
            if (info.capabilities.settings.isNotEmpty()) launch { runCatching { refreshSettings() } }
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

    /** The camera has no network time: it gets the phone's on every connection. */
    private suspend fun syncTime(client: CameraClient) {
        val zone = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        try {
            val result = withContext(Dispatchers.IO) { client.setTime(now, zone.id, zone.getOffset(now) / 60_000) }
            EventLog.log("Time: camera clock set (${result.applied.joinToString().ifEmpty { "nothing applied" }})")
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            EventLog.log("Time: sync failed (${e.message})")
        }
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
                // Changes made on the camera itself (its buttons, later its Bluetooth remote)
                if (_settings.value != null && !_capturing.value && !_recordBusy.value) {
                    try {
                        refreshSettings()
                    } catch (e: Exception) {
                        coroutineContext.ensureActive()
                    }
                }
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

    /** Pairs a new camera, or sets up [replaceId] again, and switches to it. */
    fun pair(passphrase: String, found: CompanionWifi.Found?, replaceId: String? = null) {
        EventLog.log("Pairing: ${found?.ssid ?: "any ${Protocol.SSID_PREFIX} network"}${found?.bssid?.let { " ($it)" } ?: ""}")
        val camera = PairedCamera(id = UUID.randomUUID().toString(), passphrase = passphrase, ssid = found?.ssid, bssid = found?.bssid)
        store.add(camera, replaceId)
        resetCameraState()
        connect()
    }

    /** Switches to another paired camera. */
    fun select(id: String) {
        if (store.camera.value?.id == id) return
        EventLog.log("Switching camera")
        store.select(id)
        resetCameraState()
        connect()
    }

    /** Forgets the camera in use; the next paired camera, if any, takes its place. */
    fun forget() {
        val camera = store.camera.value ?: return
        EventLog.log("Forgetting ${camera.label}")
        camera.bssid?.let { CompanionWifi.forget(context, it) }
        store.forget(camera.id)
        resetCameraState()
        if (store.camera.value != null) connect() else disconnect(LinkState.NotPaired)
    }

    /** What belonged to the previous camera: transfers, listing, last shot. */
    private fun resetCameraState() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        _downloads.value = emptyMap()
        _files.value = null
        _lastCapture.value = null
        _settings.value = null
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
            // The camera switches to photo mode for a still (protocol §5.3)
            if (_settings.value != null) scope.launch { runCatching { refreshSettings() } }
            return result
        } catch (e: Exception) {
            EventLog.log("Capture failed: ${e.message}")
            throw e
        } finally {
            _capturing.value = false
        }
    }

    suspend fun refreshSettings() {
        val client = client ?: return
        // A read that crossed a change would show the values from before it
        val writes = settingsWrites
        try {
            val read = withContext(Dispatchers.IO) { client.settings() }
            if (settingsWrites == writes && !settingsWriting) _settings.value = read
        } catch (e: Exception) {
            EventLog.log("Settings: read failed (${e.message})")
            throw e
        }
    }

    /** Reboots or switches off the camera (protocol §5.15). A reboot comes back by itself: the app rejoins. */
    suspend fun power(off: Boolean) {
        val client = requireClient()
        EventLog.log("Camera: ${if (off) "switching off" else "rebooting"}")
        withContext(Dispatchers.IO) { client.power(if (off) "off" else "reboot") }
    }

    /** Opens the live view (protocol §5.14); the caller closes it. Blocking: call off the main thread. */
    fun openLiveView(quality: String? = null): LiveStream {
        EventLog.log("Live view: opening${quality?.let { " ($it)" } ?: ""}")
        return requireClient().openLiveView(quality)
    }

    /** Changes one camera setting; the flow takes the values the camera answers. */
    suspend fun setSetting(id: String, value: String) {
        val client = requireClient()
        settingsWrites++
        settingsWriting = true
        try {
            _settings.value = withContext(Dispatchers.IO) { client.setSettings(mapOf(id to value)) }
            EventLog.log("Setting $id = $value")
        } catch (e: Exception) {
            EventLog.log("Setting $id = $value failed: ${e.message}")
            // The camera may have taken part of it
            scope.launch { runCatching { refreshSettings() } }
            throw e
        } finally {
            settingsWriting = false
        }
    }

    /** Starts or stops a video recording; stopping adds the new video to the files. */
    suspend fun record(start: Boolean) {
        val client = requireClient()
        _recordBusy.value = true
        try {
            EventLog.log("Recording: ${if (start) "start" else "stop"}")
            val result = withContext(Dispatchers.IO) { client.record(start) }
            _status.update { it?.copy(recording = result.recording) }
            if (result.files.isNotEmpty()) {
                EventLog.log("Recording: ${result.files.joinToString { it.name }}")
                _files.update { list -> list?.let { result.files + it.filterNot { f -> result.files.any { n -> n.path == f.path } } } }
                scope.launch { runCatching { refreshFiles() } }
            }
        } catch (e: Exception) {
            EventLog.log("Recording ${if (start) "start" else "stop"} failed: ${e.message}")
            throw e
        } finally {
            _recordBusy.value = false
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
                    // Videos have no thumbnail on the camera: their first frame, decoded here
                    if (file.kind == FileKind.VIDEO) VideoThumbnails.firstFrame(client, file) else client.thumbnail(file.path)
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
        const val REJOIN_MS = 3_000L
        /** Refused or timed-out requests in a row before the app shows the camera as not found */
        const val REJOIN_ATTEMPTS = 20
        const val HELLO_ATTEMPTS = 15
        const val HELLO_RETRY_MS = 2_000L
        const val POLL_MS = 5_000L
        const val POLL_FAILURES = 3
    }
}
