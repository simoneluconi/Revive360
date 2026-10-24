package com.simoneluconi.revive360.link

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.wifi.ScanResult
import android.os.Build
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.protocol.Protocol
import java.util.regex.Pattern

/**
 * Android's companion device association for the camera's Wi-Fi: the system scans for `G360R-`
 * networks and shows its own chooser once. The access point picked there (its SSID and BSSID, read
 * from the scan, never assumed from the camera's MAC) is then approved for [CameraNetwork] without
 * further dialogs.
 */
object CompanionWifi {
    /** What the user picked in the system's chooser. */
    data class Found(val ssid: String?, val bssid: String?)

    fun associate(context: Context, onConfirm: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val cdm = cdm(context)
        val filter = WifiDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^" + Pattern.quote(Protocol.SSID_PREFIX) + ".*"))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        EventLog.log("Pairing: asking the system to find the camera's Wi-Fi")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onConfirm(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) =
                    EventLog.log("Pairing: association ${associationInfo.id} created")
                override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Replaced by onAssociationPending on Android 13")
                override fun onDeviceFound(intentSender: IntentSender) = onConfirm(intentSender)
                override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
            }, null)
        }
    }

    /** Reads the chooser's result: the scan result where the system gives it, else the association. */
    fun found(data: Intent?): Found? {
        data ?: return null
        val scan: ScanResult? = scanResult(data)
        if (scan != null) {
            @Suppress("DEPRECATION")
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                scan.wifiSsid?.toString()?.removeSurrounding("\"")
            } else {
                scan.SSID
            }
            return Found(ssid?.takeIf { it.isNotEmpty() }, scan.BSSID?.lowercase())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            if (info != null) {
                val name = info.displayName?.toString()?.takeIf { it.startsWith(Protocol.SSID_PREFIX) }
                return Found(name, info.deviceMacAddress?.toString()?.lowercase())
            }
        }
        return null
    }

    /** Removes the association with the access point [bssid] (other cameras keep theirs). */
    fun forget(context: Context, bssid: String) {
        val cdm = cdm(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations
                .filter { it.deviceMacAddress?.toString().equals(bssid, ignoreCase = true) }
                .forEach { runCatching { cdm.disassociate(it.id) } }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations
                .filter { it.equals(bssid, ignoreCase = true) }
                .forEach { runCatching { cdm.disassociate(it) } }
        }
    }

    private fun scanResult(data: Intent): ScanResult? {
        if (Build.VERSION.SDK_INT >= 34) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.associatedDevice?.wifiDevice?.let { return it }
        }
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
        } else {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        }
    }

    private fun cdm(context: Context): CompanionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
}
