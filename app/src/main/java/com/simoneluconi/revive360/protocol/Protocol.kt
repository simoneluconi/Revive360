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
) {
    val canCapture: Boolean get() = captureModes.isNotEmpty()
}

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
        const val INTERNAL = "internal"
        /** Not from the camera: an answer this app can't read. */
        const val MALFORMED = "malformed"
    }
}
