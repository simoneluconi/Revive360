package com.simoneluconi.revive360.protocol

import java.io.IOException

/**
 * The phone <-> camera protocol v1 (docs/protocol.md), as neutral types. Nothing here knows a
 * camera model: what a camera can do arrives in [Capabilities], and the UI shows only that.
 */
object Protocol {
    const val VERSION = 1
    const val PORT = 8360
    /** Used when the network's LinkProperties don't name the camera (protocol §1). */
    const val DEFAULT_HOST = "192.168.107.1"
    /** Every camera softAP's SSID starts with this. */
    const val SSID_PREFIX = "G360R-"
    const val PASSPHRASE_MIN = 8
    const val PASSPHRASE_MAX = 63

    fun isValidPassphrase(text: String): Boolean =
        text.length in PASSPHRASE_MIN..PASSPHRASE_MAX && text.all { it.code in 0x20..0x7E }
}

enum class CaptureMode(val wire: String) {
    PHOTO("photo"),
    RAW("raw"),
    RAW_PHOTO("raw+photo");

    companion object {
        fun of(wire: String): CaptureMode? = entries.firstOrNull { it.wire == wire }
    }
}

enum class FileKind(val wire: String) {
    PHOTO("photo"),
    RAW("raw"),
    VIDEO("video"),
    OTHER("other");

    companion object {
        fun of(wire: String): FileKind = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

enum class LedState(val wire: String) {
    OFF("off"),
    ON("on"),
    BLINK("blink"),
    AUTO("auto");

    companion object {
        fun of(wire: String): LedState? = entries.firstOrNull { it.wire == wire }
    }
}

data class LedChannel(
    val id: String,
    val label: String,
    /** `status_red`, `front`, ... or `unknown` while the camera's mapping is unconfirmed. */
    val role: String,
    val states: List<LedState>,
)

data class Capabilities(
    val captureModes: List<CaptureMode> = emptyList(),
    val photoQualities: List<String> = emptyList(),
    val photoResolutions: List<String> = emptyList(),
    val resolutionSettable: Boolean = false,
    val videoModes: List<String> = emptyList(),
    val record: Boolean = false,
    val liveview: Boolean = false,
    val listFiles: Boolean = false,
    val download: Boolean = false,
    val range: Boolean = false,
    val delete: Boolean = false,
    val thumbnails: Boolean = false,
    val battery: Boolean = false,
    val storage: Boolean = false,
    val batteryTemperature: Boolean = false,
    val leds: List<LedChannel> = emptyList(),
    val debugLog: Boolean = false,
    /** The camera clock can be set from the phone (protocol §5.10). */
    val time: Boolean = false,
    /** §5.15: the camera can be rebooted or switched off from the app */
    val power: Boolean = false,
    /** Camera settings of protocol §5.12, in display order. */
    val settings: List<SettingDef> = emptyList(),
) {
    val canCapture: Boolean get() = captureModes.isNotEmpty()

    fun setting(id: String): SettingDef? = settings.firstOrNull { it.id == id }
}

/** One camera setting: protocol id (`mode`, `lens`, `video_size`, ...) and its accepted tokens. */
data class SettingDef(val id: String, val values: List<String>)

/** Setting ids the app knows (protocol §5.12); others are shown with their raw tokens. */
object SettingIds {
    const val MODE = "mode"
    const val LENS = "lens"
    const val PHOTO_SIZE = "photo_size"
    const val VIDEO_SIZE = "video_size"
    const val TIMER = "timer"
    const val TIMELAPSE_INTERVAL = "timelapse_interval"
    const val LOOP_TIME = "loop_time"
    const val EV = "ev"
    const val ISO_LIMIT = "iso_limit"
    const val WHITE_BALANCE = "white_balance"
    const val METERING = "metering"
    const val HDR = "hdr"
    const val SHARPNESS = "sharpness"
    const val WIND_CUT = "wind_cut"
    const val AUDIO = "audio"
    const val BEEP = "beep"
    const val LED = "led"
    const val AUTO_POWER_OFF = "auto_power_off"

    /** Picture and camera settings, kept out of the way of the shooting ones */
    val ADVANCED = setOf(EV, ISO_LIMIT, WHITE_BALANCE, METERING, HDR, SHARPNESS, WIND_CUT, AUDIO, BEEP, LED, AUTO_POWER_OFF)
}

/** Shooting modes (values of the `mode` setting). */
object ShootingModes {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val TIMELAPSE = "timelapse"
    const val LOOPING = "looping"

    /** Modes whose shutter starts and stops a recording (protocol §5.13). */
    fun records(mode: String?): Boolean = mode == VIDEO || mode == LOOPING || mode == TIMELAPSE
}

/** Current setting values: id to token, `null` when the camera can't tell. */
typealias SettingValues = Map<String, String?>

/**
 * The answer of protocol §5.12: the [values] in force, and per setting the tokens the camera
 * [available]s now (the sizes of the current lens, say); a setting missing there accepts all of its
 * capability values.
 */
data class CameraSettings(
    val values: SettingValues = emptyMap(),
    val available: Map<String, List<String>> = emptyMap(),
) {
    /** The tokens of [def] the camera accepts now, in the capability's order. */
    fun offered(def: SettingDef): List<String> = available[def.id]?.let { now -> def.values.filter { it in now } } ?: def.values
}

data class Recording(val active: Boolean, val elapsedS: Long?)

data class RecordResult(val recording: Recording, val files: List<CameraFile>)

data class CameraInfo(
    val protocol: Int,
    val serverName: String,
    val serverVersion: String,
    val model: String,
    val modelRaw: String,
    val firmware: String,
    val adapter: String,
    /** `supported`, `experimental` or `stub`. */
    val adapterStatus: String,
    val mac: String,
    val name: String,
    val capabilities: Capabilities,
)

data class Battery(
    val percent: Int,
    val charging: Boolean,
    /** `charging`, `discharging`, `full`, `not_charging` or `unknown`. */
    val status: String,
    val externalPower: Boolean,
    val temperatureC: Double?,
)

data class Storage(val mounted: Boolean, val totalBytes: Long, val freeBytes: Long, val usedBytes: Long)

data class CameraStatus(
    val battery: Battery?,
    val storage: Storage?,
    /** A capture is in progress. */
    val busy: Boolean,
    /** Seconds since the camera's controller started: going down means the camera rebooted. */
    val uptimeS: Long,
    /** Present when the camera can record (protocol §5.13). */
    val recording: Recording? = null,
)

data class CameraFile(
    /** Relative to the card, always under `DCIM/`: the file's identity. */
    val path: String,
    val name: String,
    val size: Long,
    /** Unix seconds, camera clock. */
    val mtime: Long,
    val kind: FileKind,
) {
    /** The name without its extension: a JPG and its SRW share it. */
    val baseName: String get() = name.substringBeforeLast('.')
}

/**
 * Newest shot first, by the DCF numbers (folder 100..999, then the file's four digits): the order
 * the camera shot them in, whatever its clock said. Names without numbers go by date, after.
 */
object ShotOrder {
    private val FOLDER = Regex("""^DCIM/(\d{3})""")
    private val FILE = Regex("""(\d{4})$""")

    val newestFirst: Comparator<CameraFile> = compareByDescending<CameraFile> { folder(it) }
        .thenByDescending { number(it) }
        .thenByDescending { it.mtime }
        .thenBy { it.name }

    private fun folder(file: CameraFile): Int = FOLDER.find(file.path)?.groupValues?.get(1)?.toInt() ?: -1

    private fun number(file: CameraFile): Int = FILE.find(file.baseName)?.groupValues?.get(1)?.toInt() ?: -1
}

data class CaptureResult(val files: List<CameraFile>, val durationMs: Long)

/** The camera clock after a time sync, and what it applied (`system`, `rtc`, `timezone`). */
data class TimeResult(val utcMs: Long, val applied: List<String>)

/** A camera answer outside 2xx, with the protocol's error code (`busy`, `no_storage`, ...). */
class CameraException(val httpStatus: Int, val code: String, message: String) :
    IOException("$httpStatus $code: $message") {
    companion object {
        const val BAD_REQUEST = "bad_request"
        const val BAD_PATH = "bad_path"
        const val NOT_FOUND = "not_found"
        const val NO_THUMBNAIL = "no_thumbnail"
        const val BUSY = "busy"
        const val UNSUPPORTED = "unsupported"
        const val NO_STORAGE = "no_storage"
        const val CAPTURE_TIMEOUT = "capture_timeout"
        /** The request needs another shooting mode (protocol §5.13). */
        const val WRONG_MODE = "wrong_mode"
        const val INTERNAL = "internal"
        /** Not from the camera: an answer this app can't read. */
        const val MALFORMED = "malformed"
    }
}
