package com.antigravity.mirror.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
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
     * Connects to [device] over Wi-Fi Direct.
     *
     * Uses `groupOwnerIntent = 0` so the **peer** (e.g. the Windows Connect app, which
     * advertises itself as a Miracast sink and expects to be the Group Owner) wins GO
     * negotiation. The phone joins as a P2P client and receives a DHCP-assigned IP
     * from the GO; the GO's IP is read from [WifiP2pInfo.groupOwnerAddress] on the
     * [WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION] broadcast.
     *
     * Before calling `connect()`, this method:
     *  1. Stops in-progress peer discovery (otherwise `connect()` returns
     *     [WifiP2pManager.BUSY]).
     *  2. Calls `removeGroup()` to tear down any stale group left over from a previous
     *     crashed session.
     *
     * Emits [ConnectionEvent.Connected] with the actual GO IP on success, or
     * [ConnectionEvent.Failed] with the real [WifiP2pManager] reason code on error.
     */
    fun connectToDevice(device: WifiP2pDevice): Flow<ConnectionEvent> = callbackFlow {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Let the peer (Miracast sink / Windows Connect) become the Group Owner.
            // Forcing 15 here causes negotiation to fail or leaves the phone as the
            // unexpected GO, which Windows then refuses to stream to.
            groupOwnerIntent = 0
        }

        // Capture the channel so callbacks outside the lambda can emit events
        val channel = this

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return

                @Suppress("DEPRECATION")
                val info: WifiP2pInfo? =
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)

                if (info != null && info.groupFormed) {
                    val goAddress: InetAddress? = info.groupOwnerAddress
                    if (goAddress != null) {
                        Log.i(
                            TAG,
                            "Group formed — isGroupOwner=${info.isGroupOwner}, " +
                                "goAddress=${goAddress.hostAddress}"
                        )
                        if (!channel.isClosedForSend) {
                            channel.trySend(ConnectionEvent.Connected(goAddress))
                            channel.close()
                        }
                    } else {
                        Log.w(TAG, "Group formed but groupOwnerAddress is null — waiting")
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)

        // Stop any in-progress discovery FIRST. Calling connect() while discoverPeers()
        // is still running returns WifiP2pManager.BUSY (reason=1). Then remove any
        // stale group left from a previous session so the framework doesn't reject
        // the new connect() with ERROR.
        wifiP2pManager.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery succeeded — clearing stale group")
                clearStaleGroupThenConnect(config, channel)
            }
            override fun onFailure(reason: Int) {
                // Failure here just means discovery wasn't running — safe to proceed
                Log.d(TAG, "stopPeerDiscovery failed (reason=$reason) — clearing stale group anyway")
                clearStaleGroupThenConnect(config, channel)
            }
        })

        // 30-second timeout
        val timeoutJob = launch {
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                kotlinx.coroutines.delay(CONNECTION_TIMEOUT_MS)
            }
            if (!channel.isClosedForSend) {
                Log.w(TAG, "Connection timed out after ${CONNECTION_TIMEOUT_MS}ms")
                channel.trySend(ConnectionEvent.Failed(WifiP2pManager.ERROR))
                channel.close()
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

    /**
     * Removes any existing P2P group (best-effort) before initiating a new connect.
     * A stale group from a previous crashed session will cause `connect()` to fail
     * with [WifiP2pManager.ERROR] until the group is torn down.
     */
    private fun clearStaleGroupThenConnect(
        config: WifiP2pConfig,
        channel: kotlinx.coroutines.channels.SendChannel<ConnectionEvent>
    ) {
        wifiP2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup succeeded — proceeding to connect()")
                initiateConnect(config, channel)
            }
            override fun onFailure(reason: Int) {
                // Reason 2 (NO_SERVICE_REQUESTS / no group) is expected when there's
                // nothing to remove; any other failure we also proceed since connect()
                // will surface the real reason.
                Log.d(TAG, "removeGroup failed (reason=$reason) — proceeding to connect() anyway")
                initiateConnect(config, channel)
            }
        })
    }

    private fun initiateConnect(
        config: WifiP2pConfig,
        channel: kotlinx.coroutines.channels.SendChannel<ConnectionEvent>
    ) {
        wifiP2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect() initiated — waiting for WIFI_P2P_CONNECTION_CHANGED_ACTION")
                // Also check immediately in case the group already exists
                wifiP2pManager.requestConnectionInfo(p2pChannel) { info ->
                    if (info != null && info.groupFormed && info.groupOwnerAddress != null &&
                        !channel.isClosedForSend
                    ) {
                        Log.i(
                            TAG,
                            "Group already formed after connect() — " +
                                "isGroupOwner=${info.isGroupOwner}, " +
                                "goAddress=${info.groupOwnerAddress.hostAddress}"
                        )
                        channel.trySend(ConnectionEvent.Connected(info.groupOwnerAddress))
                        channel.close()
                    }
                }
            }
            override fun onFailure(reason: Int) {
                // BUSY (1) and P2P_UNSUPPORTED (2) are deterministic failures — surface
                // them immediately so the user sees a useful error instead of waiting
                // for the 30-second timeout to fire a generic ERROR.
                //
                // ERROR (0) is known to be reported by some OEMs even when the connection
                // actually succeeds via the broadcast, so for ERROR we keep waiting and
                // let the timeout decide.
                when (reason) {
                    WifiP2pManager.BUSY,
                    WifiP2pManager.P2P_UNSUPPORTED -> {
                        Log.e(TAG, "connect() onFailure(reason=$reason) — failing fast")
                        if (!channel.isClosedForSend) {
                            channel.trySend(ConnectionEvent.Failed(reason))
                            channel.close()
                        }
                    }
                    else -> {
                        Log.w(
                            TAG,
                            "connect() onFailure(reason=$reason) — continuing to wait for broadcast " +
                                "(some OEMs report ERROR spuriously)"
                        )
                    }
                }
            }
        })
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

/** Events emitted by [DiscoveryManager.connectToDevice]. */
sealed class ConnectionEvent {
    /**
     * Connection succeeded; [groupOwnerAddress] is the IP of the Group Owner, read from
     * [WifiP2pManager.EXTRA_WIFI_P2P_INFO] on the connection-changed broadcast.
     */
    data class Connected(val groupOwnerAddress: InetAddress) : ConnectionEvent()

    /**
     * Connection failed.
     * @param reason One of [WifiP2pManager.ERROR], [WifiP2pManager.BUSY], or
     *               [WifiP2pManager.P2P_UNSUPPORTED].
     */
    data class Failed(val reason: Int) : ConnectionEvent()
}
