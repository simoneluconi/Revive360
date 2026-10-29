package com.simoneluconi.revive360.settings

/** What a camera model needs before the app can use it. */
enum class Requirement { CUSTOM_FIRMWARE, STOCK_FIRMWARE, UNKNOWN }

/** How far the app is with a camera model. */
enum class Support { SUPPORTED, COMING }

data class CameraModel(val name: String, val code: String, val requirement: Requirement, val support: Support)

/**
 * The cameras the app knows, shown before pairing so nobody looks for a camera the app can't see
 * (a camera on the stock firmware does not advertise the app's Bluetooth service). One line per
 * model: when a model's state changes, the setup screens follow from here.
 */
object CameraModels {
    val all = listOf(
        CameraModel("Gear 360 (2016)", "SM-C200", Requirement.CUSTOM_FIRMWARE, Support.SUPPORTED),
        CameraModel("Gear 360 (2017)", "SM-R210", Requirement.UNKNOWN, Support.COMING),
    )

    /** The install guide: the Revive360 firmware and the SD card, step by step. */
    const val INSTALL_GUIDE = "https://github.com/simoneluconi/Revive360/blob/main/docs/INSTALL.md"
}
