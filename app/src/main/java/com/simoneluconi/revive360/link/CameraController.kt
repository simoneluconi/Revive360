package com.simoneluconi.revive360.link

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Network
import androidx.compose.ui.graphics.ImageBitmap
import com.simoneluconi.revive360.BuildConfig
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.media.DngWriter
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.media.SavedFiles
import com.simoneluconi.revive360.media.ThumbCache
import com.simoneluconi.revive360.media.VideoThumbnails
import com.simoneluconi.revive360.protocol.CameraClient
import com.simoneluconi.revive360.protocol.CameraException
import com.simoneluconi.revive360.protocol.CameraFile
import com.simoneluconi.revive360.protocol.ShotOrder
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
import com.simoneluconi.revive360.protocol.RawLayout
import com.simoneluconi.revive360.protocol.RawPair
import com.simoneluconi.revive360.protocol.RawPairs
import com.simoneluconi.revive360.settings.AppSettings
import com.simoneluconi.revive360.settings.CameraStore
import com.simoneluconi.revive360.settings.PairedCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    /** Asking the camera over Bluetooth to switch its Wi-Fi on (protocol §9). */
    data object Waking : LinkState
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

/** Many DNGs in a row: [done] of [total] handled, [failed] of them not saved, [current] the photo in hand. */
data class DngBatch(val done: Int, val total: Int, val failed: Int, val current: String?, val finished: Boolean = false)

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
    // A failure nothing caught is logged, not a crash of the whole app
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
        EventLog.log("Unexpected error: ${e.javaClass.simpleName} ${e.message}")
    })
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

    private val _dngBatch = MutableStateFlow<DngBatch?>(null)
    /** The DNGs being saved together ([saveDngs]); null when none. */
    val dngBatch: StateFlow<DngBatch?> = _dngBatch.asStateFlow()

    @Volatile
    private var client: CameraClient? = null
    private var session: Job? = null
    private var releaseJob: Job? = null
    private var rejoinJob: Job? = null
    private var wakeJob: Job? = null
    private var rejoinAttempts = 0
    private var foreground = false
    private val downloadJobs = HashMap<String, Job>()
    /** [TransferService] runs (see [transfersChanged]) */
    private var transferService = false
    private val originals = Mutex()

    /** The connected camera's identity, for cache keys. */
    private val cameraKey: String get() = (state.value as? LinkState.Connected)?.info?.mac ?: store.camera.value?.mac ?: "camera"

    // ---- lifecycle ----

    fun onForeground() {
        foreground = true
        releaseJob?.cancel()
        releaseJob = null
        // A request still searching is left alone: a new one would cancel a join in progress
        val state = _state.value
        if (session == null && state != LinkState.Waking && state != LinkState.Joining && state != LinkState.Handshake) connect()
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

    /** Stops a connection in progress (or a failed one): the camera stays idle until asked again. */
    fun cancel() {
        EventLog.log("Connection cancelled")
        disconnect(LinkState.Idle)
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
        val target = camera!!
        if (!CameraBle.allowed(context)) {
            requestNetwork(target)
            return
        }
        // The camera keeps its Wi-Fi off until asked: over Bluetooth first, then the network
        _state.value = LinkState.Waking
        wakeJob = scope.launch {
            wakeWifi(target)
            if (store.camera.value?.id != target.id || _state.value != LinkState.Waking) return@launch
            _state.value = LinkState.Joining
            requestNetwork(target)
        }
    }

    /**
     * Asks the camera over Bluetooth to switch its Wi-Fi on and waits until it says it is. A
     * camera paired before it had Bluetooth is first found among those nearby by its network's
     * name. Failing here is no failure to connect: the Wi-Fi may be on anyway (wifi=on).
     */
    private suspend fun wakeWifi(camera: PairedCamera) {
        val address = camera.bluetooth ?: findBluetooth(camera) ?: return
        repeat(WAKE_ATTEMPTS) { attempt ->
            try {
                BleSession.open(context, address).use { ble ->
                    var wifi = ble.info().wifi
                    if (wifi != "on") {
                        EventLog.log("Bluetooth: asking the camera to switch its Wi-Fi on")
                        ble.setWifi(true)
                        val until = System.currentTimeMillis() + WAKE_TIMEOUT_MS
                        while (wifi != "on" && wifi != "failed" && System.currentTimeMillis() < until) {
                            delay(1_000)
                            wifi = ble.info().wifi
                        }
                    }
                    EventLog.log("Bluetooth: the camera's Wi-Fi is $wifi")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("Bluetooth: ${e.message}")
                if (attempt < WAKE_ATTEMPTS - 1) delay(1_000)
            }
        }
    }

    /** The Bluetooth address of a camera paired over its Wi-Fi alone, matched by network name. */
    private suspend fun findBluetooth(camera: PairedCamera): String? {
        val ssid = camera.ssid ?: return null
        for (seen in CameraBle.scan(context, SCAN_MS)) {
            val info = try {
                BleSession.open(context, seen.address).use { it.info() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("Bluetooth: ${seen.address} did not answer (${e.message})")
                continue
            }
            if (info.ssid == ssid) {
                EventLog.log("Bluetooth: ${camera.label} is ${seen.address}")
                store.update { if (it.id == camera.id) it.copy(bluetooth = seen.address) else it }
                return seen.address
            }
        }
        return null
    }

    private fun requestNetwork(camera: PairedCamera) {
        network.request(camera, object : CameraNetwork.Listener {
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
                    // In the background nothing rejoins: idle, so coming back starts over
                    // (Bluetooth included) instead of waiting on a search that never runs
                    if (!rejoin(camera)) disconnect(LinkState.Idle)
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
        wakeJob?.cancel()
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
            launch {
                // The clock first: file times are only right once the camera knows the phone's zone
                if (info.capabilities.time) syncTime(client)
                // A failed listing (logged there) must not end the session: the gallery lists again
                if (info.capabilities.listFiles) {
                    try {
                        refreshFiles()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                    }
                }
            }
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
                if (lastUptime >= 0 && status.uptimeS < lastUptime) {
                    EventLog.log("The camera's controller restarted")
                    // A new controller knows nothing of the phone's zone: file times need it again
                    val info = (_state.value as? LinkState.Connected)?.info
                    if (info?.capabilities?.time == true) {
                        syncTime(client)
                        if (info.capabilities.listFiles) runCatching { refreshFiles() }
                    }
                }
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
    fun pair(passphrase: String, found: CompanionWifi.Found?, replaceId: String? = null, ssid: String? = null, bluetooth: String? = null) {
        EventLog.log("Pairing: ${found?.ssid ?: ssid ?: "any ${Protocol.SSID_PREFIX} network"}${found?.bssid?.let { " ($it)" } ?: ""}" +
            (bluetooth?.let { ", Bluetooth $it" } ?: ""))
        val camera = PairedCamera(
            id = UUID.randomUUID().toString(), passphrase = passphrase, ssid = found?.ssid ?: ssid, bssid = found?.bssid,
            bluetooth = bluetooth,
        )
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
        transfersChanged()
        _downloads.value = emptyMap()
        _files.value = null
        _lastCapture.value = null
        _settings.value = null
    }

    // ---- commands ----

    private fun requireClient(): CameraClient = client ?: throw IllegalStateException("not connected")

    /**
     * Runs in the controller's scope: a screen left meanwhile stops waiting, but the shot (which the
     * camera takes anyway) still reaches [lastCapture] and [files].
     */
    suspend fun capture(mode: CaptureMode, quality: String?): CaptureResult {
        val client = requireClient()
        return scope.async { capture(client, mode, quality) }.await()
    }

    private suspend fun capture(client: CameraClient, mode: CaptureMode, quality: String?): CaptureResult {
        _capturing.value = true
        try {
            EventLog.log("Capture: ${mode.wire}${quality?.let { " / $it" } ?: ""}")
            val result = withContext(Dispatchers.IO) { client.capture(mode, quality) }
            EventLog.log("Capture: ${result.files.joinToString { it.name }} in ${result.durationMs} ms")
            _lastCapture.value = result
            // New files first, then a fresh listing replaces them
            _files.update { list -> list?.let { (result.files + it.filterNot { f -> result.files.any { n -> n.path == f.path } }).sortedWith(ShotOrder.newestFirst) } }
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
    fun openLiveView(quality: String? = null, opening: (HttpURLConnection) -> Unit = {}): LiveStream {
        EventLog.log("Live view: opening${quality?.let { " ($it)" } ?: ""}")
        return requireClient().openLiveView(quality, opening)
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

    /**
     * Starts or stops a video recording; stopping adds the new video to the files. In the
     * controller's scope, as [capture]: a screen left meanwhile does not lose the video.
     */
    suspend fun record(start: Boolean) {
        val client = requireClient()
        scope.async { record(client, start) }.await()
    }

    private suspend fun record(client: CameraClient, start: Boolean) {
        _recordBusy.value = true
        try {
            EventLog.log("Recording: ${if (start) "start" else "stop"}")
            val result = withContext(Dispatchers.IO) { client.record(start) }
            _status.update { it?.copy(recording = result.recording) }
            if (result.files.isNotEmpty()) {
                EventLog.log("Recording: ${result.files.joinToString { it.name }}")
                _files.update { list -> list?.let { (result.files + it.filterNot { f -> result.files.any { n -> n.path == f.path } }).sortedWith(ShotOrder.newestFirst) } }
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
            _files.value = list.sortedWith(ShotOrder.newestFirst)
        } catch (e: Exception) {
            EventLog.log("Files: listing failed (${e.message})")
            throw e
        }
    }

    /** Deletes a file from the card; a photo takes its RAW pair with it (it would be left alone in the list). */
    suspend fun delete(file: CameraFile) {
        val client = requireClient()
        // In the controller's scope, as [capture]: a delete that went through always leaves the list
        scope.async { delete(client, file) }.await()
    }

    private suspend fun delete(client: CameraClient, file: CameraFile) {
        val raw = _files.value?.let { RawPairs.of(it).of(file) }
        withContext(Dispatchers.IO) {
            client.delete(file.path)
            raw?.let { client.delete(it.front.path); client.delete(it.rear.path) }
        }
        EventLog.log("Deleted ${file.path}${if (raw != null) " and its RAW pair" else ""}")
        thumbs.forget(thumbs.key(cameraKey, file))
        val gone = setOfNotNull(file.path, raw?.front?.path, raw?.rear?.path)
        _files.update { list -> list?.filterNot { it.path in gone } }
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
                } catch (e: CancellationException) {
                    throw e
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
        if (downloadJobs[file.path]?.isActive == true) return
        val client = client ?: return
        val info = (state.value as? LinkState.Connected)?.info
        val resume = info?.capabilities?.range == true
        val key = savedKey(file)
        _downloads.update { it + (file.path to DownloadProgress(0, file.size)) }
        startTransfer(file.path) {
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
                // A newer transfer of the file may hold the entry by now: its progress is not this one's
                if (downloadJobs[file.path] === coroutineContext.job) _downloads.update {
                    if (cancelled) it - file.path
                    else it + (file.path to (it[file.path] ?: DownloadProgress(0, file.size)).copy(failed = e.message ?: "error"))
                }
            }
        }
    }

    /**
     * Runs a transfer as the [downloadJobs] entry [key]: registered before it starts and removed
     * only by itself, so one finishing late never drops a newer one. A cancelled one still finishing
     * (its blocking read stops at the next block) is waited for first: both would write the same
     * .part. Nothing starts while one under [key] is active.
     */
    private fun startTransfer(key: String, block: suspend CoroutineScope.() -> Unit) {
        val previous = downloadJobs[key]
        if (previous?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                block()
            } finally {
                if (downloadJobs[key] === coroutineContext.job) removeTransfer(key)
            }
        }
        putTransfer(key, job)
        job.start()
    }

    private fun putTransfer(key: String, job: Job) {
        downloadJobs[key] = job
        transfersChanged()
    }

    private fun removeTransfer(key: String) {
        downloadJobs.remove(key)
        transfersChanged()
    }

    /** A foreground service while anything transfers: the system lets it finish with the app in the background. */
    private fun transfersChanged() {
        val busy = downloadJobs.isNotEmpty()
        if (busy == transferService) return
        transferService = busy
        TransferService.set(context, busy)
    }

    /**
     * The original of [file] in the app's cache (the 360 viewer and the stitcher read it there),
     * downloaded if it isn't yet. The few most recent ones are kept.
     */
    suspend fun original(file: CameraFile, progress: (Long, Long) -> Unit): File = originals.withLock { fetchOriginal(file, progress) }

    // One at a time: a fetch left behind (still finishing its read) and a new one would share the .part
    private suspend fun fetchOriginal(file: CameraFile, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "originals").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(savedKey(file).toByteArray()).joinToString("") { "%02x".format(it) }
        val done = File(dir, "$name.${file.name.substringAfterLast('.', "bin").lowercase()}")
        if (done.exists() && done.length() == file.size) return@withContext done.also { it.setLastModified(System.currentTimeMillis()) }
        val client = client ?: throw IOException("not connected to the camera")
        val resume = (state.value as? LinkState.Connected)?.info?.capabilities?.range == true
        val part = File(dir, "$name.part")
        client.download(file.path, part, resume) { got, total ->
            ensureActive()
            progress(got, if (total > 0) total else file.size)
        }
        check(part.renameTo(done)) { "can't keep ${file.name}" }
        dir.listFiles { f -> !f.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }?.drop(ORIGINALS_KEPT)?.forEach { it.delete() }
        done
    }

    /** The [downloads] entry of a photo's DNG, and its mark in [saved]. */
    fun dngKey(photo: CameraFile): String = "${photo.path}#dng"

    fun dngSavedKey(photo: CameraFile): String = savedKey(photo) + "|dng"

    /**
     * Saves a photo's RAW pair as one DNG (both lenses side by side) in the phone's Download/Revive360:
     * the two dumps come to the cache (a broken transfer resumes), the DNG is written from them
     * straight into the shared storage, the dumps go. Progress in [downloads] under [dngKey].
     */
    fun saveDng(photo: CameraFile, raw: RawPair) {
        if (client == null) return
        startTransfer(dngKey(photo)) { writeDng(photo, raw) }
    }

    /** One DNG, its progress and outcome in [downloads]; true when saved. */
    private suspend fun writeDng(photo: CameraFile, raw: RawPair): Boolean {
        val key = dngKey(photo)
        val client = client ?: return false
        val resume = (state.value as? LinkState.Connected)?.info?.capabilities?.range == true
        _downloads.update { it + (key to DownloadProgress(0, raw.size)) }
        val parts = listOf(raw.front, raw.rear).map { it to partFile(savedKey(it)) }
        try {
            EventLog.log("DNG: ${photo.name} from ${raw.front.name} + ${raw.rear.name}")
            withContext(Dispatchers.IO) {
                var before = 0L
                for ((file, part) in parts) {
                    client.download(file.path, part, resume) { done, _ ->
                        ensureActive()
                        _downloads.update { it + (key to DownloadProgress(before + done, raw.size)) }
                    }
                    before += file.size
                }
                val layout = RawLayout.of(raw.front.name) ?: error("no layout in ${raw.front.name}")
                val reference = dngReference(client, photo)
                var grey = 0
                MediaSaver.publish(context, "${photo.baseName}.dng", FileKind.RAW, "image/x-adobe-dng", photo.mtime) { out ->
                    grey = DngWriter.write(parts[0].second, parts[1].second, layout, out, reference)
                }
                EventLog.log("DNG: white balance ${if (grey > 0) "from the camera ($grey grey cells)" else "grey world"}")
                parts.forEach { it.second.delete() }
            }
            saved.add(dngSavedKey(photo))
            EventLog.log("DNG: ${photo.baseName}.dng saved")
            _downloads.update { it - key }
            return true
        } catch (e: Exception) {
            val cancelled = !coroutineContext.isActive
            EventLog.log("DNG: ${photo.name} ${if (cancelled) "cancelled" else "failed (${e.message})"}")
            // A newer DNG of the photo may hold the entry by now (see startTransfer)
            if (downloadJobs[key] === coroutineContext.job) _downloads.update {
                if (cancelled) it - key
                else it + (key to (it[key] ?: DownloadProgress(0, raw.size)).copy(failed = e.message ?: "error"))
            }
            if (cancelled) throw e
            return false
        }
    }

    /** The photo's thumbnail, for the camera's white balance in the DNG; null without one */
    private fun dngReference(client: CameraClient, photo: CameraFile): DngWriter.Reference? = runCatching {
        val bytes = client.thumbnail(photo.path) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        DngWriter.Reference(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }.getOrNull()

    /** The entry stays until the transfer has stopped writing (see [startTransfer]). */
    fun cancelDng(photo: CameraFile) {
        downloadJobs[dngKey(photo)]?.cancel()
        _downloads.update { it - dngKey(photo) }
    }

    /**
     * Saves many photos' DNGs one after the other ([saveDng] each); a failed one does not stop the
     * rest, one cancelled from its page neither. Progress in [dngBatch], which stays at the end
     * (finished) until [dismissDngBatch]. The camera link stays while it runs, the app in the
     * background too.
     */
    fun saveDngs(photos: List<Pair<CameraFile, RawPair>>) {
        if (downloadJobs[DNG_BATCH]?.isActive == true || photos.isEmpty() || client == null) return
        _dngBatch.value = DngBatch(0, photos.size, 0, photos.first().first.path)
        EventLog.log("DNG: ${photos.size} to save")
        // Registered before it runs: a batch whose photos all run already ends without suspending
        startTransfer(DNG_BATCH) {
            var failed = 0
            var done = 0
            try {
                for ((photo, raw) in photos) {
                    _dngBatch.value = DngBatch(done, photos.size, failed, photo.path)
                    val key = dngKey(photo)
                    val previous = downloadJobs[key]
                    if (previous?.isActive != true) {
                        // As startTransfer: in the list before it runs, after a cancelled one of the photo
                        val one = async(start = CoroutineStart.LAZY) {
                            previous?.join()
                            writeDng(photo, raw)
                        }
                        putTransfer(key, one)
                        val ok = try {
                            one.await()
                        } catch (e: CancellationException) {
                            ensureActive()   // the whole batch stops; one photo cancelled: on to the next
                            false
                        } finally {
                            if (downloadJobs[key] === one) removeTransfer(key)
                        }
                        if (!ok) failed++
                    }
                    done++
                }
            } finally {
                EventLog.log("DNG: $done of ${photos.size} done, $failed failed")
                _dngBatch.value = DngBatch(done, photos.size, failed, null, finished = true)
            }
        }
    }

    fun cancelDngs() {
        downloadJobs[DNG_BATCH]?.cancel()
    }

    fun dismissDngBatch() {
        if (_dngBatch.value?.finished == true) _dngBatch.value = null
    }

    /** The entry stays until the transfer has stopped writing (see [startTransfer]). */
    fun cancelDownload(file: CameraFile) {
        downloadJobs[file.path]?.cancel()
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
        const val ORIGINALS_KEPT = 6
        /** The [downloadJobs] entry of [saveDngs]: the link stays while it runs */
        const val DNG_BATCH = "#dng-batch"
        const val REJOIN_MS = 3_000L
        const val WAKE_ATTEMPTS = 2
        /** The access point takes a few seconds; more on a 2.4 GHz fallback */
        const val WAKE_TIMEOUT_MS = 30_000L
        const val SCAN_MS = 5_000L
        /** Refused or timed-out requests in a row before the app shows the camera as not found */
        const val REJOIN_ATTEMPTS = 20
        const val HELLO_ATTEMPTS = 15
        const val HELLO_RETRY_MS = 2_000L
        const val POLL_MS = 5_000L
        const val POLL_FAILURES = 3
    }
}
