package com.antigravity.mirror.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * The underlying network transport used to establish a Miracast session.
 *
 * Listed in priority order: [WIFI_DIRECT] is preferred, then [WIFI], then [HOTSPOT].
 */
enum class ConnectionType {
    /** Wi-Fi Direct P2P — highest priority. */
    WIFI_DIRECT,

    /** Standard WiFi infrastructure network. */
    WIFI,

    /** Mobile hotspot / tethering network. */
    HOTSPOT
}

/**
 * Detects available network connection types and selects the highest-priority one.
 *
 * Priority order: [ConnectionType.WIFI_DIRECT] → [ConnectionType.WIFI] → [ConnectionType.HOTSPOT]
 *
 * Satisfies Requirements 3.4 and 3.5: the app automatically negotiates the appropriate
 * connection type and falls back to the next available type when the preferred one is
 * unavailable.
 */
class ConnectionTypeSelector(private val context: Context) {

    private val tag = "MirrorApp/ConnectionTypeSelector"

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * Returns `true` if Wi-Fi Direct is supported by the device hardware AND Wi-Fi is enabled.
     *
     * Detection: checks [PackageManager.FEATURE_WIFI_DIRECT] for hardware support and
     * [WifiManager.isWifiEnabled] for the radio being on (Wi-Fi Direct requires the Wi-Fi
     * radio to be active).
     */
    fun isWifiDirectAvailable(): Boolean {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val wifiEnabled = wifiManager.isWifiEnabled
        Log.d(tag, "isWifiDirectAvailable: hasFeature=$hasFeature, wifiEnabled=$wifiEnabled")
        return hasFeature && wifiEnabled
    }

    /**
     * Returns `true` if the device is currently connected to a standard WiFi infrastructure
     * network (i.e. connected to an access point).
     *
     * Uses [NetworkCapabilities.TRANSPORT_WIFI] on API 23+ and falls back to the deprecated
     * [android.net.NetworkInfo] API on older devices.
     */
    fun isWifiAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            // Exclude hotspot: when acting as a hotspot the device's own WiFi radio is in AP
            // mode and networkId is -1 (not associated with any AP).
            val networkId = wifiManager.connectionInfo?.networkId ?: -1
            val isConnectedToAp = networkId != -1
            Log.d(tag, "isWifiAvailable (API 23+): hasWifi=$hasWifi, networkId=$networkId")
            hasWifi && isConnectedToAp
        } else {
            @Suppress("DEPRECATION")
            val info = connectivityManager.getActiveNetworkInfo()
            @Suppress("DEPRECATION")
            val available = info != null &&
                info.isConnected &&
                info.type == ConnectivityManager.TYPE_WIFI
            Log.d(tag, "isWifiAvailable (legacy): available=$available")
            available
        }
    }

    /**
     * Returns `true` if a mobile hotspot / tethering network is active.
     *
     * Detection strategy: look for any active network with [NetworkCapabilities.TRANSPORT_WIFI]
     * that has [NetworkCapabilities.NET_CAPABILITY_NOT_VPN] while the device is NOT connected
     * to a WiFi access point (i.e. `WifiManager.connectionInfo.networkId == -1`). This
     * indicates the Wi-Fi radio is active in AP/hotspot mode rather than client mode.
     *
     * On API < 23 we fall back to checking whether WiFi is enabled but the device is not
     * associated with any AP.
     */
    fun isHotspotAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networkId = wifiManager.connectionInfo?.networkId ?: -1
            val wifiEnabled = wifiManager.isWifiEnabled
            // WiFi radio is on but not connected to any AP → likely in hotspot/AP mode
            if (!wifiEnabled || networkId != -1) {
                Log.d(tag, "isHotspotAvailable (API 23+): false (wifiEnabled=$wifiEnabled, networkId=$networkId)")
                return false
            }
            // Confirm there is at least one active network with TRANSPORT_WIFI + NOT_VPN
            val networks = connectivityManager.allNetworks
            val hasHotspotNetwork = networks.any { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            Log.d(tag, "isHotspotAvailable (API 23+): hasHotspotNetwork=$hasHotspotNetwork")
            hasHotspotNetwork
        } else {
            @Suppress("DEPRECATION")
            val info = connectivityManager.getActiveNetworkInfo()
            @Suppress("DEPRECATION")
            val wifiConnected = info != null &&
                info.isConnected &&
                info.type == ConnectivityManager.TYPE_WIFI
            val networkId = wifiManager.connectionInfo?.networkId ?: -1
            // WiFi active but not associated with an AP → hotspot mode
            val available = wifiManager.isWifiEnabled && !wifiConnected && networkId == -1
            Log.d(tag, "isHotspotAvailable (legacy): available=$available")
            available
        }
    }

    /**
     * Selects the highest-priority available connection type.
     *
     * Priority: [ConnectionType.WIFI_DIRECT] → [ConnectionType.WIFI] → [ConnectionType.HOTSPOT]
     *
     * @return The best available [ConnectionType], or `null` if no connection type is available.
     */
    fun selectBestConnectionType(): ConnectionType? {
        return when {
            isWifiDirectAvailable() -> ConnectionType.WIFI_DIRECT
            isWifiAvailable() -> ConnectionType.WIFI
            isHotspotAvailable() -> ConnectionType.HOTSPOT
            else -> null
        }.also { selected ->
            Log.d(tag, "selectBestConnectionType: $selected")
        }
    }

    /**
     * Returns all currently available connection types in priority order.
     *
     * The list is ordered from highest to lowest priority:
     * [ConnectionType.WIFI_DIRECT], [ConnectionType.WIFI], [ConnectionType.HOTSPOT].
     * Types that are not currently available are omitted.
     */
    fun availableConnectionTypes(): List<ConnectionType> {
        return buildList {
            if (isWifiDirectAvailable()) add(ConnectionType.WIFI_DIRECT)
            if (isWifiAvailable()) add(ConnectionType.WIFI)
            if (isHotspotAvailable()) add(ConnectionType.HOTSPOT)
        }.also { types ->
            Log.d(tag, "availableConnectionTypes: $types")
        }
    }
}
