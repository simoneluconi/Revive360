package com.simoneluconi.revive360.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.settings.PairedCamera
import java.net.Inet4Address

/**
 * Joins the camera's softAP as a local-only network (protocol §1). Only this app's sockets use it
 * (through [Network.openConnection]); the phone keeps its normal internet for everything else.
 *
 * With the SSID and BSSID from the companion association the request is pinned to that one access
 * point, which the system approves without a dialog. Without them it matches any `G360R-` network
 * and the system asks every time.
 */
class CameraNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    interface Listener {
        /** The camera's Wi-Fi is up; [host] is the camera's address on it. */
        fun onAvailable(network: Network, host: String)
        fun onLost()
        /** The system found no matching network, or the user declined its dialog. */
        fun onUnavailable()
    }

    fun request(camera: PairedCamera, listener: Listener) {
        release()
        val specifier = WifiNetworkSpecifier.Builder().apply {
            if (camera.ssid != null) {
                setSsid(camera.ssid)
            } else {
                setSsidPattern(PatternMatcher(Protocol.SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            }
            camera.bssid?.let { runCatching { setBssid(MacAddress.fromString(it)) } }
            setWpa2Passphrase(camera.passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The camera has no internet: without this the request would wait for a validated network
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var announced: Network? = null

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                // Announced once, when the address is known (it comes with the link properties)
                if (announced == network) return
                announced = network
                val host = cameraHost(properties)
                EventLog.log("Wi-Fi: camera network up (${properties.interfaceName}), camera at $host")
                listener.onAvailable(network, host)
            }

            override fun onLost(network: Network) {
                EventLog.log("Wi-Fi: camera network lost")
                announced = null
                listener.onLost()
            }

            override fun onUnavailable() {
                EventLog.log("Wi-Fi: no camera network (not found or declined)")
                listener.onUnavailable()
            }
        }
        callback = cb
        val target = camera.ssid ?: "${Protocol.SSID_PREFIX}*"
        EventLog.log("Wi-Fi: asking for $target${if (camera.bssid != null) " (${camera.bssid})" else ""}")
        connectivity.requestNetwork(request, cb)
    }

    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    /** The DHCP server, else the default gateway, else the protocol's fixed address. */
    private fun cameraHost(properties: LinkProperties): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            properties.dhcpServerAddress?.hostAddress?.takeIf { it != "0.0.0.0" }?.let { return it }
        }
        properties.routes.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }
            ?.gateway?.hostAddress?.takeIf { it != "0.0.0.0" }?.let { return it }
        return Protocol.DEFAULT_HOST
    }
}
