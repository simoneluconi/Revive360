package com.simoneluconi.revive360.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads and writes the protocol's JSON bodies. Unknown fields are ignored and missing ones take the
 * "not supported" default, so a newer camera server keeps working (protocol §3).
 */
object ProtocolJson {
    fun info(text: String): CameraInfo = parse(text) { json ->
        val server = json.optJSONObject("server") ?: JSONObject()
        val camera = json.optJSONObject("camera") ?: JSONObject()
        val modelRaw = camera.optString("model_raw")
        CameraInfo(
            protocol = json.getInt("protocol"),
            serverName = server.optString("name"),
            serverVersion = server.optString("version"),
            model = camera.optString("model").ifEmpty { modelRaw },
            modelRaw = modelRaw,
            firmware = camera.optString("firmware"),
            adapter = camera.optString("adapter"),
            adapterStatus = camera.optString("adapter_status"),
            mac = camera.optString("mac").lowercase(),
            name = camera.optString("name"),
            capabilities = capabilities(json.optJSONObject("capabilities") ?: JSONObject()),
        )
    }

    fun capabilities(json: JSONObject): Capabilities {
        val capture = json.optJSONObject("capture") ?: JSONObject()
        val files = json.optJSONObject("files") ?: JSONObject()
        val status = json.optJSONObject("status") ?: JSONObject()
        return Capabilities(
            // Modes this app doesn't know are left out rather than failing the whole camera
            captureModes = strings(capture.optJSONArray("modes")).mapNotNull(CaptureMode::of),
            photoQualities = strings(capture.optJSONArray("photo_qualities")),
            photoResolutions = strings(capture.optJSONArray("photo_resolutions")),
            resolutionSettable = capture.optBoolean("resolution_settable"),
            videoModes = strings(json.optJSONArray("video_modes")),
            record = json.optBoolean("record"),
            liveview = json.optBoolean("liveview"),
            listFiles = files.optBoolean("list"),
            download = files.optBoolean("download"),
            range = files.optBoolean("range"),
            delete = files.optBoolean("delete"),
            thumbnails = files.optBoolean("thumbnails"),
            battery = status.optBoolean("battery"),
            storage = status.optBoolean("storage"),
            batteryTemperature = status.optBoolean("battery_temperature"),
            leds = objects(json.optJSONArray("leds")).mapNotNull(::led),
            debugLog = json.optBoolean("debug_log"),
            time = json.optBoolean("time"),
            settings = objects(json.optJSONArray("settings")).mapNotNull(::settingDef),
        )
    }

    /** `{"settings": {"mode": "photo", "lens": null}}` (protocol §5.12) */
    fun settings(text: String): SettingValues = parse(text) { json ->
        val settings = json.optJSONObject("settings") ?: JSONObject()
        settings.keys().asSequence().associateWith { key ->
            if (settings.isNull(key)) null else settings.optString(key).takeIf(String::isNotEmpty)
        }
    }

    fun settingsRequest(changes: Map<String, String>): String = JSONObject(changes).toString()

    fun record(text: String): RecordResult = parse(text) { json ->
        RecordResult(
            recording = recording(json.optJSONObject("recording")) ?: Recording(active = false, elapsedS = null),
            files = objects(json.optJSONArray("files")).mapNotNull(::file),
        )
    }

    fun recordRequest(start: Boolean): String =
        JSONObject().put("action", if (start) "start" else "stop").toString()

    private fun recording(json: JSONObject?): Recording? = json?.let {
        Recording(active = it.optBoolean("active"), elapsedS = if (it.has("elapsed_s")) it.optLong("elapsed_s") else null)
    }

    private fun settingDef(json: JSONObject): SettingDef? {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val values = strings(json.optJSONArray("values"))
        return if (values.isEmpty()) null else SettingDef(id, values)
    }

    fun status(text: String): CameraStatus = parse(text) { json ->
        CameraStatus(
            battery = json.optJSONObject("battery")?.let { battery ->
                Battery(
                    percent = battery.optInt("percent", -1),
                    charging = battery.optBoolean("charging"),
                    status = battery.optString("status", "unknown"),
                    externalPower = battery.optBoolean("external_power"),
                    temperatureC = battery.optDoubleOrNull("temperature_c"),
                )
            },
            storage = json.optJSONObject("storage")?.let { storage ->
                Storage(
                    mounted = storage.optBoolean("mounted"),
                    totalBytes = storage.optLong("total_bytes"),
                    freeBytes = storage.optLong("free_bytes"),
                    usedBytes = storage.optLong("used_bytes"),
                )
            },
            busy = json.optBoolean("busy"),
            uptimeS = json.optLong("uptime_s"),
            recording = recording(json.optJSONObject("recording")),
        )
    }

    fun files(text: String): List<CameraFile> = parse(text) { json ->
        objects(json.optJSONArray("files")).mapNotNull(::file)
    }

    fun capture(text: String): CaptureResult = parse(text) { json ->
        CaptureResult(objects(json.optJSONArray("files")).mapNotNull(::file), json.optLong("duration_ms"))
    }

    fun time(text: String): TimeResult = parse(text) { json ->
        TimeResult(json.optLong("utc_ms"), strings(json.optJSONArray("applied")))
    }

    fun timeRequest(utcMs: Long, tz: String?, tzOffsetMin: Int): String =
        JSONObject().apply {
            put("utc_ms", utcMs)
            if (tz != null) put("tz", tz)
            put("tz_offset_min", tzOffsetMin)
        }.toString()

    /** The `error` object of a non-2xx answer, or null when the body isn't one. */
    fun error(httpStatus: Int, text: String?): CameraException? {
        val error = text?.let { runCatching { JSONObject(it).optJSONObject("error") }.getOrNull() } ?: return null
        return CameraException(httpStatus, error.optString("code", CameraException.INTERNAL), error.optString("message"))
    }

    fun captureRequest(mode: CaptureMode, quality: String?, resolution: String?): String =
        JSONObject().apply {
            put("mode", mode.wire)
            if (quality != null) put("quality", quality)
            if (resolution != null) put("resolution", resolution)
        }.toString()

    fun ledRequest(id: String, state: LedState, onMs: Int?, offMs: Int?): String =
        JSONObject().apply {
            put("id", id)
            put("state", state.wire)
            if (state == LedState.BLINK) {
                if (onMs != null) put("on_ms", onMs)
                if (offMs != null) put("off_ms", offMs)
            }
        }.toString()

    private fun file(json: JSONObject): CameraFile? {
        val path = json.optString("path").takeIf { it.isNotEmpty() } ?: return null
        return CameraFile(
            path = path,
            name = json.optString("name").ifEmpty { path.substringAfterLast('/') },
            size = json.optLong("size"),
            mtime = json.optLong("mtime"),
            kind = FileKind.of(json.optString("kind")),
        )
    }

    private fun led(json: JSONObject): LedChannel? {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return LedChannel(
            id = id,
            label = json.optString("label").ifEmpty { id },
            role = json.optString("role", "unknown"),
            states = strings(json.optJSONArray("states")).mapNotNull(LedState::of),
        )
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    private inline fun <T> parse(text: String, read: (JSONObject) -> T): T = try {
        read(JSONObject(text))
    } catch (e: JSONException) {
        throw CameraException(200, CameraException.MALFORMED, e.message ?: "bad JSON")
    }
}
