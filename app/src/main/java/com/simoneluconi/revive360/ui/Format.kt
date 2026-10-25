package com.simoneluconi.revive360.ui

import android.content.res.Resources
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.Failure
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.protocol.CameraException
import com.simoneluconi.revive360.protocol.CaptureMode
import com.simoneluconi.revive360.protocol.SettingIds
import com.simoneluconi.revive360.protocol.ShootingModes
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

    /** Title of a camera setting row; null for the shooting mode (it has its own row) or unknown ids. */
    fun settingTitle(resources: Resources, id: String): String? = when (id) {
        SettingIds.LENS -> resources.getString(R.string.setting_lens)
        SettingIds.PHOTO_SIZE -> resources.getString(R.string.setting_photo_size)
        SettingIds.VIDEO_SIZE -> resources.getString(R.string.setting_video_size)
        SettingIds.TIMER -> resources.getString(R.string.setting_timer)
        SettingIds.TIMELAPSE_INTERVAL -> resources.getString(R.string.setting_timelapse_interval)
        SettingIds.LOOP_TIME -> resources.getString(R.string.setting_loop_time)
        SettingIds.EV -> resources.getString(R.string.setting_ev)
        SettingIds.ISO_LIMIT -> resources.getString(R.string.setting_iso_limit)
        SettingIds.WHITE_BALANCE -> resources.getString(R.string.setting_white_balance)
        SettingIds.METERING -> resources.getString(R.string.setting_metering)
        SettingIds.HDR -> resources.getString(R.string.setting_hdr)
        SettingIds.SHARPNESS -> resources.getString(R.string.setting_sharpness)
        SettingIds.WIND_CUT -> resources.getString(R.string.setting_wind_cut)
        SettingIds.AUDIO -> resources.getString(R.string.setting_audio)
        SettingIds.BEEP -> resources.getString(R.string.setting_beep)
        SettingIds.LED -> resources.getString(R.string.setting_led)
        SettingIds.AUTO_POWER_OFF -> resources.getString(R.string.setting_auto_power_off)
        else -> null
    }

    /** A setting token for display: known ones get a label, the rest show as they are. */
    fun settingValue(resources: Resources, id: String, token: String): String = when (id) {
        SettingIds.MODE -> when (token) {
            ShootingModes.PHOTO -> resources.getString(R.string.shoot_mode_photo)
            ShootingModes.VIDEO -> resources.getString(R.string.shoot_mode_video)
            ShootingModes.TIMELAPSE -> resources.getString(R.string.shoot_mode_timelapse)
            ShootingModes.LOOPING -> resources.getString(R.string.shoot_mode_looping)
            "burst" -> resources.getString(R.string.shoot_mode_burst)
            "slowmo" -> resources.getString(R.string.shoot_mode_slowmo)
            else -> token
        }
        SettingIds.LENS -> when (token) {
            "dual" -> resources.getString(R.string.lens_dual)
            "rear" -> resources.getString(R.string.lens_rear)
            "front" -> resources.getString(R.string.lens_front)
            else -> token
        }
        SettingIds.PHOTO_SIZE -> when (token) {
            "dual_large" -> resources.getString(R.string.photo_size_dual_large)
            "dual_small" -> resources.getString(R.string.photo_size_dual_small)
            "single_wide" -> resources.getString(R.string.photo_size_single_wide)
            else -> token.replace('x', '×')
        }
        SettingIds.VIDEO_SIZE -> {
            val fps = token.substringAfter('@', "")
            val size = token.substringBefore('@').replace('x', '×')
            if (fps.isEmpty()) size else resources.getString(R.string.video_size_value, size, fps)
        }
        SettingIds.TIMER -> when (token) {
            "off" -> resources.getString(R.string.timer_off)
            else -> if (token.toDoubleOrNull() != null) resources.getString(R.string.value_seconds, token) else token
        }
        SettingIds.TIMELAPSE_INTERVAL ->
            if (token.toDoubleOrNull() != null) resources.getString(R.string.value_seconds, token) else token
        SettingIds.LOOP_TIME -> when (token) {
            "max" -> resources.getString(R.string.value_max)
            else -> if (token.toDoubleOrNull() != null) resources.getString(R.string.value_minutes, token) else token
        }
        SettingIds.AUTO_POWER_OFF -> when (token) {
            "never" -> resources.getString(R.string.value_never)
            else -> if (token.toDoubleOrNull() != null) resources.getString(R.string.value_minutes, token) else token
        }
        SettingIds.EV -> if (token.toDoubleOrNull() == 0.0) "0" else token
        SettingIds.ISO_LIMIT -> "ISO $token"
        SettingIds.SHARPNESS -> "+$token"
        SettingIds.WHITE_BALANCE -> when (token) {
            "auto" -> resources.getString(R.string.wb_auto)
            "daylight" -> resources.getString(R.string.wb_daylight)
            "cloudy" -> resources.getString(R.string.wb_cloudy)
            "incandescent" -> resources.getString(R.string.wb_incandescent)
            "fluorescent" -> resources.getString(R.string.wb_fluorescent)
            "underwater" -> resources.getString(R.string.wb_underwater)
            else -> token
        }
        SettingIds.METERING -> when (token) {
            "multi" -> resources.getString(R.string.metering_multi)
            "center" -> resources.getString(R.string.metering_center)
            "spot" -> resources.getString(R.string.metering_spot)
            else -> token
        }
        SettingIds.BEEP -> when (token) {
            "off" -> resources.getString(R.string.beep_off)
            "low" -> resources.getString(R.string.beep_low)
            "mid" -> resources.getString(R.string.beep_mid)
            "high" -> resources.getString(R.string.beep_high)
            else -> token
        }
        else -> when (token) {
            "off" -> resources.getString(R.string.value_off)
            "on" -> resources.getString(R.string.value_on)
            else -> token
        }
    }

    /** Elapsed recording time, m:ss or h:mm:ss. */
    fun elapsed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    fun state(resources: Resources, state: LinkState): String = when (state) {
        LinkState.NotPaired -> resources.getString(R.string.state_not_paired)
        LinkState.Idle -> resources.getString(R.string.state_idle)
        LinkState.Waking -> resources.getString(R.string.state_waking)
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
        CameraException.WRONG_MODE -> resources.getString(R.string.error_wrong_mode)
        null -> resources.getString(R.string.error_link)
        else -> resources.getString(R.string.error_camera, code)
    }
}
