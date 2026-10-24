package com.simoneluconi.revive360.ui

import android.content.res.Resources
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.Failure
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.protocol.CameraException
import com.simoneluconi.revive360.protocol.CaptureMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Text shared by several screens: sizes, dates, states and the protocol's error codes. */
object Format {
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = value / 1024.0
        var unit = 0
        while (v >= 1024 && unit < units.lastIndex) {
            v /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[unit])
    }

    fun date(unixSeconds: Long): String =
        if (unixSeconds <= 0) "—" else dateTime.format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()))

    fun mode(resources: Resources, mode: CaptureMode): String = resources.getString(
        when (mode) {
            CaptureMode.PHOTO -> R.string.mode_photo
            CaptureMode.RAW -> R.string.mode_raw
            CaptureMode.RAW_PHOTO -> R.string.mode_raw_photo
        }
    )

    /** The camera's quality names are opaque; the known ones get a friendlier label. */
    fun quality(resources: Resources, quality: String): String = when (quality) {
        "sfine" -> resources.getString(R.string.quality_sfine)
        "fine" -> resources.getString(R.string.quality_fine)
        "norm" -> resources.getString(R.string.quality_norm)
        else -> quality
    }

    fun state(resources: Resources, state: LinkState): String = when (state) {
        LinkState.NotPaired -> resources.getString(R.string.state_not_paired)
        LinkState.Idle -> resources.getString(R.string.state_idle)
        LinkState.Joining -> resources.getString(R.string.state_joining)
        LinkState.Handshake -> resources.getString(R.string.state_handshake)
        is LinkState.Connected -> resources.getString(R.string.state_connected)
        is LinkState.Failed -> when (state.reason) {
            Failure.NOT_FOUND -> resources.getString(R.string.state_not_found)
            Failure.NO_SERVER -> resources.getString(R.string.state_no_server)
            Failure.UNSUPPORTED_VERSION -> resources.getString(R.string.state_unsupported_version, state.detail ?: "?")
        }
    }

    fun error(resources: Resources, error: Throwable): String = when (val code = (error as? CameraException)?.code) {
        CameraException.BUSY -> resources.getString(R.string.error_busy)
        CameraException.NO_STORAGE -> resources.getString(R.string.error_no_storage)
        CameraException.CAPTURE_TIMEOUT -> resources.getString(R.string.error_capture_timeout)
        CameraException.UNSUPPORTED -> resources.getString(R.string.error_unsupported)
        CameraException.NOT_FOUND -> resources.getString(R.string.error_not_found)
        null -> resources.getString(R.string.error_link)
        else -> resources.getString(R.string.error_camera, code)
    }
}
