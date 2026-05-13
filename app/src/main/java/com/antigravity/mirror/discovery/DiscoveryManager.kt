package com.antigravity.mirror.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

private const val TAG = "MirrorApp/DiscoveryManager"
private const val DISCOVERY_TIMEOUT_MS = 30_000L
private const val CONNECTION_TIMEOUT_MS = 30_000L

/** Synthetic reason code used when WiFi is disabled (not a WifiP2pManager code). */
const val REASON_WIFI_DISABLED = -100

/**
 * Wraps [WifiP2pManager] and its [WifiP2pManager.Channel].
 *
 * Handles peer discovery, group creation (Android as Group Owner), and connection events.
 * Emits typed events via [Flow] so callers can react without coupling to the P2P broadcast receiver.
 */
class DiscoveryManager(private val context: Context) {

    private val wifiP2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val p2pChannel: WifiP2pManager.Channel =
        wifiP2pManager.initialize(context, Looper.getMainLooper(), null)

    /**
     * Returns `true` if the device hardware supports Wi-Fi Direct.
     */
    fun isWifiDirectSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }

    /**
     * Starts Wi-Fi Direct peer discovery with a 30-second timeout.
     *
     * Emits [DiscoveryEvent.PeersFound] when devices are found,
     * [DiscoveryEvent.NoPeersFound] on timeout or empty peer list, or
     * [DiscoveryEvent.DiscoveryFailed] on error.
     *
     * The BroadcastReceiver is registered when the flow is collected and unregistered
     * (along with stopping discovery) when the flow is cancelled or completes.
     */
    fun startDiscovery(): Flow<DiscoveryEvent> = callbackFlow {
        // Check WiFi is enabled — emit a typed error instead of throwing
        @Suppress("DEPRECATION")
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "startDiscovery: WiFi is disabled")
            trySend(DiscoveryEvent.DiscoveryFailed(REASON_WIFI_DISABLED))
            close()
            return@callbackFlow
        }

        // Check WiFi Direct is supported
        if (!isWifiDirectSupported()) {
            Log.w(TAG, "startDiscovery: WiFi Direct not supported")
            trySend(DiscoveryEvent.DiscoveryFailed(WifiP2pManager.P2P_UNSUPPORTED))
            close()
            return@callbackFlow
        }

        // BroadcastReceiver to handle peer change events
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                    @Suppress("DEPRECATION")
                    wifiP2pManager.requestPeers(p2pChannel) { peerList ->
                        val peers = peerList.deviceList.toList()
                        Log.d(TAG, "Peers changed: ${peers.size} peer(s)")
                        if (peers.isNotEmpty()) {
                            trySend(DiscoveryEvent.PeersFound(peers))
                        }
                        // Don't emit NoPeersFound here — wait for the timeout
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)

        // Start peer discovery
        wifiP2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers initiated successfully")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers failed with reason: $reason")
                trySend(DiscoveryEvent.DiscoveryFailed(reason))
                close()
            }
        })

        // Run a 30-second timeout in a child coroutine. When it fires, emit NoPeersFound
        // and close the flow. This is separate from awaitClose so the receiver cleanup
        // always runs regardless of whether the timeout or a peer event closes the flow.
        val timeoutJob = launch {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                // Just suspend — the timeout cancels this coroutine after 30s
                kotlinx.coroutines.delay(DISCOVERY_TIMEOUT_MS)
            }
            // If we reach here the timeout elapsed without the flow being closed
            if (!isClosedForSend) {
                Log.i(TAG, "Discovery timed out — no peers found")
                trySend(DiscoveryEvent.NoPeersFound)
                close()
            }
        }

        awaitClose {
            timeoutJob.cancel()
            stopDiscovery()
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver already unregistered", e)
            }
        }
    }

    /** Stops an in-progress discovery scan. */
    fun stopDiscovery() {
        wifiP2pManager.stopPeerDiscovery(p2pChannel, null)
    }

    /**
     * Connects to [device] as the Wi-Fi Direct Group Owner (groupOwnerIntent = 15).
     *
     * Emits [ConnectionEvent.Connected] with the GO IP address (`192.168.49.1`) on success, or
     * [ConnectionEvent.Failed] on error.
     *
     * Listens for [WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION] to confirm the connection.
     */
    fun connectAsGroupOwner(device: WifiP2pDevice): Flow<ConnectionEvent> = callbackFlow {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 15
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        wifiP2pManager.requestGroupInfo(p2pChannel) { group ->
                            if (group != null && group.isGroupOwner) {
                                val goAddress = InetAddress.getByName("192.168.49.1")
                                trySend(ConnectionEvent.Connected(goAddress))
                                close()
                            }
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)

        wifiP2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect() initiated successfully")
                wifiP2pManager.requestGroupInfo(p2pChannel) { group ->
                    if (group != null && group.isGroupOwner) {
                        val goAddress = InetAddress.getByName("192.168.49.1")
                        trySend(ConnectionEvent.Connected(goAddress))
                        close()
                    }
                }
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "connect() failed with reason: $reason")
                trySend(ConnectionEvent.Failed(reason))
                close()
            }
        })

        // 30-second timeout — emit Failed if connection never completes
        val timeoutJob = launch {
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                kotlinx.coroutines.delay(CONNECTION_TIMEOUT_MS)
            }
            if (!isClosedForSend) {
                Log.w(TAG, "Connection timed out after ${CONNECTION_TIMEOUT_MS}ms")
                trySend(ConnectionEvent.Failed(WifiP2pManager.ERROR))
                close()
            }
        }

        awaitClose {
            timeoutJob.cancel()
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Connection receiver already unregistered", e)
            }
        }
    }

    /** Disconnects from the current Wi-Fi Direct group. */
    fun disconnect() {
        wifiP2pManager.removeGroup(p2pChannel, null)
    }
}

// ---------------------------------------------------------------------------
// Event types
// ---------------------------------------------------------------------------

/** Events emitted by [DiscoveryManager.startDiscovery]. */
sealed class DiscoveryEvent {
    /** One or more Miracast sink peers were found. */
    data class PeersFound(val peers: List<WifiP2pDevice>) : DiscoveryEvent()

    /** Discovery timed out without finding any peers, or the peer list was empty. */
    object NoPeersFound : DiscoveryEvent()

    /**
     * Discovery failed with a WifiP2pManager error code.
     * @param reason One of [WifiP2pManager.ERROR], [WifiP2pManager.BUSY], or
     *               [WifiP2pManager.P2P_UNSUPPORTED].
     */
    data class DiscoveryFailed(val reason: Int) : DiscoveryEvent()
}

/** Events emitted by [DiscoveryManager.connectAsGroupOwner]. */
sealed class ConnectionEvent {
    /** Connection succeeded; [groupOwnerAddress] is the IP of the Group Owner (`192.168.49.1`). */
    data class Connected(val groupOwnerAddress: InetAddress) : ConnectionEvent()

    /**
     * Connection failed.
     * @param reason One of [WifiP2pManager.ERROR], [WifiP2pManager.BUSY], or
     *               [WifiP2pManager.P2P_UNSUPPORTED].
     */
    data class Failed(val reason: Int) : ConnectionEvent()
}
