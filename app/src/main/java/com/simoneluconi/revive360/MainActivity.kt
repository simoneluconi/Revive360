package com.simoneluconi.revive360

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.simoneluconi.revive360.link.CompanionWifi
import com.simoneluconi.revive360.ui.AppRoot

/** Hosts the Compose UI and the system prompts it needs (Wi-Fi permission, the companion chooser). */
class MainActivity : ComponentActivity() {
    private val app get() = application as Revive360App

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        EventLog.log("Permissions: ${granted.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        // The connection started without them (at the app's start): again, with them
        if (granted.any { it.value && it.key in requiredPermissions() }) app.controller.connect()
    }

    /** Waiting for the companion chooser's answer (lost if the activity is recreated meanwhile). */
    private var onFound: ((CompanionWifi.Found?) -> Unit)? = null

    private val association = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val found = if (result.resultCode == RESULT_OK) CompanionWifi.found(result.data) else null
        EventLog.log("Pairing: chooser ${if (result.resultCode == RESULT_OK) "accepted (${found?.ssid} ${found?.bssid})" else "declined"}")
        onFound?.invoke(if (result.resultCode == RESULT_OK) found ?: CompanionWifi.Found(null, null) else null)
        onFound = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Notifications only show the transfers' one (TransferService): asked here, not needed for the link
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = (requiredPermissions() + notifications).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        setContent {
            AppRoot(app = app, findCamera = ::findCamera)
        }
    }

    /**
     * The system's chooser for the camera's Wi-Fi. [onResult] gets what was picked, an empty [CompanionWifi.Found]
     * when the system accepted without telling which network, or null when declined or unavailable.
     */
    private fun findCamera(onResult: (CompanionWifi.Found?) -> Unit) {
        onFound = onResult
        CompanionWifi.associate(
            context = this,
            onConfirm = { sender -> association.launch(IntentSenderRequest.Builder(sender).build()) },
            onFailure = { error ->
                EventLog.log("Pairing: chooser failed ($error)")
                onFound?.invoke(null)
                onFound = null
            },
        )
    }

    companion object {
        /** What the app asks for at start; the connection card asks again for what is still missing. */
        fun requiredPermissions(): List<String> = when {
            // Local network protection: the camera's LAN address is unreachable without it
            Build.VERSION.SDK_INT >= 37 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } + bluetoothPermissions()

        /** Android 12+: finding the camera and asking it for its Wi-Fi over Bluetooth (protocol §9) */
        private fun bluetoothPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyList()
            }
    }
}
