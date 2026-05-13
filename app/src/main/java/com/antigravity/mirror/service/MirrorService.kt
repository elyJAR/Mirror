package com.antigravity.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import com.antigravity.mirror.R
import com.antigravity.mirror.stream.media.ScreenCaptureEngine
import com.antigravity.mirror.stream.media.VideoEncoder
import com.antigravity.mirror.stream.transport.miracast.ConnectionEvent
import com.antigravity.mirror.stream.transport.miracast.DiscoveryEvent
import com.antigravity.mirror.stream.transport.miracast.DiscoveryManager
import com.antigravity.mirror.stream.transport.miracast.REASON_WIFI_DISABLED
import com.antigravity.mirror.stream.transport.miracast.RtpSender
import com.antigravity.mirror.stream.transport.miracast.RtspServer
import com.antigravity.mirror.stream.transport.miracast.SessionEvent
import com.antigravity.mirror.stream.transport.miracast.WfdSessionManager
import com.antigravity.mirror.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Foreground service that owns the entire Miracast streaming pipeline lifecycle.
 *
 * Responsibilities:
 * - Manages [DiscoveryManager] for Wi-Fi Direct peer discovery.
 * - Orchestrates [WfdSessionManager] for the RTSP M1–M7 handshake.
 * - Coordinates [ScreenCaptureEngine] and [VideoEncoder] for screen capture and H.264 encoding.
 * - Exposes a [Binder] so [MainActivity] can call service methods and observe [MirrorState].
 * - Registers a [ConnectivityManager.NetworkCallback] to detect network loss during active sessions.
 * - Releases all resources on session end via [releaseAllResources].
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5
 */
class MirrorService : Service() {

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): MirrorService = this@MirrorService
    }

    private val binder = LocalBinder()

    // -------------------------------------------------------------------------
    // Coroutine scope
    // -------------------------------------------------------------------------

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)

    /** Observable state flow consumed by [MainActivity] to drive the UI. */
    fun getState(): StateFlow<MirrorState> = _state.asStateFlow()

    // -------------------------------------------------------------------------
    // Component references (nullable — null when not in an active session)
    // -------------------------------------------------------------------------

    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var connectionTypeSelector: ConnectionTypeSelector

    private var captureEngine: ScreenCaptureEngine? = null
    private var rtpSender: RtpSender? = null
    private var rtspServer: RtspServer? = null
    private var wfdSessionManager: WfdSessionManager? = null

    /** The Group Owner IP address stored after a successful Wi-Fi Direct connection. */
    private var goAddress: InetAddress? = null

    /** Active coroutine jobs for discovery and session flows. */
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null
    private var sessionJob: Job? = null

    // -------------------------------------------------------------------------
    // ConnectivityManager.NetworkCallback — Task 10.2
    // -------------------------------------------------------------------------

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /** Whether the network callback is currently registered. */
    private var networkCallbackRegistered = false

    /**
     * Monitors the active network during a streaming session.
     * On loss, terminates the session and transitions to [MirrorState.Error].
     *
     * Requirements: 5.3, 5.4
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            // onLost is called on a binder thread — post to main to safely update state
            mainHandler.post {
                Log.w(TAG, "Network lost during active session")
                releaseAllResources()
                _state.value = MirrorState.Error(
                    message = "Network connection lost",
                    recoverable = true
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        discoveryManager = DiscoveryManager(this)
        connectionTypeSelector = ConnectionTypeSelector(this)
        Log.d(TAG, "MirrorService created")
    }

    /**
     * Starts the service as a foreground service.
     *
     * On API 29+: uses [ServiceCompat.startForeground] with
     * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION].
     * On older APIs: uses [startForeground] directly.
     *
     * Requirements: 5.1
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        // Use FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34) or no type (older APIs) at startup.
        // We must NOT use FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION here because that type
        // requires an active MediaProjection token — we don't have one yet at service start.
        // The service is promoted to mediaProjection type in onProjectionGranted().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: startForeground requires a valid type. Use DATA_SYNC as a neutral
            // placeholder — it doesn't require any special token.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        releaseAllResources()
        serviceScope.cancel()
        Log.d(TAG, "MirrorService destroyed")
    }

    // -------------------------------------------------------------------------
    // Public API (called via Binder from MainActivity)
    // -------------------------------------------------------------------------

    /**
     * Starts Wi-Fi Direct peer discovery.
     *
     * Checks [ConnectionTypeSelector.selectBestConnectionType] first; if no network is
     * available, emits [MirrorState.Error] and returns early.
     * Otherwise transitions to [MirrorState.Discovering] and collects from
     * [DiscoveryManager.startDiscovery].
     *
     * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.4, 3.5
     */
    fun startDiscovery() {
        val connectionType = connectionTypeSelector.selectBestConnectionType()
        if (connectionType == null) {
            Log.w(TAG, "startDiscovery: no network connection available")
            _state.value = MirrorState.Error(
                message = "No network connection available",
                recoverable = true
            )
            return
        }

        Log.i(TAG, "startDiscovery: using connection type $connectionType")
        _state.value = MirrorState.Discovering

        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            discoveryManager.startDiscovery().collect { event ->
                when (event) {
                    is DiscoveryEvent.PeersFound -> {
                        Log.i(TAG, "Discovery: found ${event.peers.size} peer(s)")
                        _state.value = MirrorState.DevicesFound(event.peers)
                    }
                    is DiscoveryEvent.NoPeersFound -> {
                        Log.i(TAG, "Discovery: no peers found")
                        _state.value = MirrorState.Error(
                            message = "No devices found. Make sure your PC's Connect app is open.",
                            recoverable = true
                        )
                    }
                    is DiscoveryEvent.DiscoveryFailed -> {
                        Log.e(TAG, "Discovery failed with reason: ${event.reason}")
                        val message = mapWifiP2pReasonToMessage(event.reason)
                        _state.value = MirrorState.Error(
                            message = message,
                            recoverable = true
                        )
                    }
                }
            }
        }
    }

    /**
     * Initiates a Miracast session with the selected [device].
     *
     * Detects the connection type, transitions to [MirrorState.Connecting], and collects
     * from [DiscoveryManager.connectToDevice]. On success, stores the GO address and
     * transitions to [MirrorState.AwaitingProjectionConsent] so the Activity can launch
     * the MediaProjection consent dialog.
     *
     * Requirements: 4.1, 5.1
     */
    fun connectToDevice(device: WifiP2pDevice) {
        val connectionType = detectAndNotifyConnectionType() ?: return

        Log.i(TAG, "connectToDevice: ${device.deviceName}, connectionType=$connectionType")

        // Cancel discovery job first — the DiscoveryManager will also call stopPeerDiscovery
        // internally before connect(), but cancelling the job ensures the flow is torn down
        // cleanly and no stale PeersFound events arrive after we start connecting.
        discoveryJob?.cancel()
        discoveryJob = null

        _state.value = MirrorState.Connecting

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            discoveryManager.connectToDevice(device).collect { event ->
                when (event) {
                    is ConnectionEvent.Connected -> {
                        Log.i(TAG, "Wi-Fi Direct group formed, goAddress=${event.groupOwnerAddress}")
                        goAddress = event.groupOwnerAddress
                        _state.value = MirrorState.AwaitingProjectionConsent
                        // The Activity will now launch the MediaProjection consent dialog
                        // and call onProjectionGranted(projection) on this service.
                    }
                    is ConnectionEvent.Failed -> {
                        Log.e(TAG, "Connection failed with reason: ${event.reason}")
                        val message = mapWifiP2pReasonToMessage(event.reason)
                        _state.value = MirrorState.Error(
                            message = message,
                            recoverable = false
                        )
                    }
                }
            }
        }
    }

    /**
     * Called by [MainActivity] after the user grants screen capture consent.
     *
     * Creates all streaming components ([VideoEncoder], [ScreenCaptureEngine], [RtpSender],
     * [RtspServer], [WfdSessionManager]), wires the NAL unit callback, and starts the WFD
     * session. Collects [SessionEvent] values and transitions [MirrorState] accordingly.
     *
     * Requirements: 5.1, 5.2, 5.5, 8.1, 8.3
     */
    fun onProjectionGranted(projection: MediaProjection) {
        val sinkAddress = goAddress
        if (sinkAddress == null) {
            Log.e(TAG, "onProjectionGranted: goAddress is null — cannot start session")
            _state.value = MirrorState.Error(
                message = "Connection lost before streaming could start",
                recoverable = true
            )
            return
        }

        Log.i(TAG, "onProjectionGranted: creating streaming components, sinkAddress=$sinkAddress")

        // Promote the foreground service to mediaProjection type now that we have a token.
        // This is required on API 29+ — the type must match the actual usage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }.onFailure { Log.w(TAG, "Failed to promote to mediaProjection type: ${it.message}") }
        }

        // Create all streaming components
        val encoder = VideoEncoder(
            width = 1280,
            height = 720,
            bitrateBps = 4_000_000,
            frameRate = 30
        )
        val captureEngineInstance = ScreenCaptureEngine(projection, encoder)
        val rtpSenderInstance = RtpSender(sinkAddress, rtpPort = 16384)
        val rtspServerInstance = RtspServer()
        val wfdSessionManagerInstance = WfdSessionManager(
            rtspServerInstance,
            captureEngineInstance,
            rtpSenderInstance
        )

        // Store references for cleanup
        captureEngine = captureEngineInstance
        rtpSender = rtpSenderInstance
        rtspServer = rtspServerInstance
        wfdSessionManager = wfdSessionManagerInstance

        // Wire NAL unit callback: encoder output → RTP sender
        captureEngineInstance.setNalUnitCallback { bytes, ts ->
            rtpSenderInstance.sendNalUnit(bytes, ts)
        }

        // Start the WFD session and collect events
        sessionJob?.cancel()
        sessionJob = serviceScope.launch {
            wfdSessionManagerInstance.startSession(projection).collect { event ->
                when (event) {
                    is SessionEvent.NegotiationComplete -> {
                        Log.i(TAG, "WFD negotiation complete")
                    }
                    is SessionEvent.StreamingStarted -> {
                        Log.i(TAG, "Streaming started")
                        _state.value = MirrorState.Streaming
                        registerNetworkCallback()
                    }
                    is SessionEvent.StreamingError -> {
                        Log.e(TAG, "Streaming error: ${event.cause.message}", event.cause)
                        releaseAllResources()
                        _state.value = MirrorState.Error(
                            message = event.cause.message ?: "Streaming error",
                            recoverable = true
                        )
                    }
                    is SessionEvent.SessionEnded -> {
                        Log.i(TAG, "Session ended")
                        releaseAllResources()
                        _state.value = MirrorState.Idle
                    }
                }
            }
        }
    }

    /**
     * Stub for PIN submission — full implementation when PIN auth is needed.
     *
     * Requirements: 4.3, 4.4
     */
    fun submitPin(pin: String) {
        // TODO: forward PIN to WfdSessionManager
        Log.d(TAG, "submitPin: $pin")
    }

    /**
     * Terminates the active session and releases all resources.
     * Transitions state back to [MirrorState.Idle].
     *
     * Requirements: 5.2, 5.5
     */
    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        wfdSessionManager?.stopSession()
        releaseAllResources()
        _state.value = MirrorState.Idle
    }

    // -------------------------------------------------------------------------
    // Resource release — Task 10.2
    // -------------------------------------------------------------------------

    /**
     * Releases ALL streaming and network resources in a safe, ordered manner.
     *
     * Each release step is wrapped in [runCatching] so that a failure in one step does not
     * prevent subsequent steps from running. After all resources are released, all component
     * references are set to null.
     *
     * Release order:
     * 1. [WfdSessionManager.stopSession]
     * 2. [ScreenCaptureEngine.stop]
     * 3. [RtpSender.close]
     * 4. [RtspServer.stop]
     * 5. [DiscoveryManager.disconnect]
     * 6. Unregister [ConnectivityManager.NetworkCallback]
     * 7. Null out all component references
     *
     * Requirements: 5.5
     */
    private fun releaseAllResources() {
        Log.i(TAG, "releaseAllResources() called")

        // 1. Stop WFD session manager
        runCatching {
            wfdSessionManager?.stopSession()
        }.onFailure { Log.w(TAG, "wfdSessionManager.stopSession() failed: ${it.message}") }

        // 2. Stop screen capture engine (releases VirtualDisplay and MediaProjection)
        runCatching {
            captureEngine?.stop()
        }.onFailure { Log.w(TAG, "captureEngine.stop() failed: ${it.message}") }

        // 3. Close RTP sender (releases UDP socket)
        runCatching {
            rtpSender?.close()
        }.onFailure { Log.w(TAG, "rtpSender.close() failed: ${it.message}") }

        // 4. Stop RTSP server (closes TCP server socket)
        runCatching {
            rtspServer?.stop()
        }.onFailure { Log.w(TAG, "rtspServer.stop() failed: ${it.message}") }

        // 5. Disconnect Wi-Fi Direct group
        runCatching {
            discoveryManager.disconnect()
        }.onFailure { Log.w(TAG, "discoveryManager.disconnect() failed: ${it.message}") }

        // 6. Unregister network callback
        unregisterNetworkCallback()

        // 7. Null out all component references
        wfdSessionManager = null
        captureEngine = null
        rtpSender = null
        rtspServer = null
        goAddress = null

        // Cancel active coroutine jobs
        discoveryJob?.cancel()
        connectionJob?.cancel()
        sessionJob?.cancel()
        discoveryJob = null
        connectionJob = null
        sessionJob = null

        Log.i(TAG, "releaseAllResources() complete")
    }

    // -------------------------------------------------------------------------
    // Network callback registration — Task 10.2
    // -------------------------------------------------------------------------

    /**
     * Registers the [networkCallback] with [ConnectivityManager] to monitor network loss.
     * Called when streaming starts ([SessionEvent.StreamingStarted]).
     *
     * Requirements: 5.3, 5.4
     */
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder().build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * Unregisters the [networkCallback] from [ConnectivityManager].
     * Called from [releaseAllResources].
     *
     * Requirements: 5.3, 5.4
     */
    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
            Log.d(TAG, "Network callback unregistered")
        }.onFailure { Log.w(TAG, "Failed to unregister network callback: ${it.message}") }
    }

    // -------------------------------------------------------------------------
    // Connection type detection
    // -------------------------------------------------------------------------

    /**
     * Detects the best available connection type and updates the service state accordingly.
     *
     * - If no connection type is available, transitions to [MirrorState.Error] with a
     *   recoverable error so the user can retry after enabling a network.
     * - If a connection type is found, logs the selected type and returns it so the caller
     *   can proceed with session establishment.
     *
     * @return The selected [ConnectionType], or `null` if no network is available.
     */
    private fun detectAndNotifyConnectionType(): ConnectionType? {
        val selected = connectionTypeSelector.selectBestConnectionType()
        return if (selected == null) {
            Log.w(TAG, "detectAndNotifyConnectionType: no network connection available")
            _state.value = MirrorState.Error(
                message = "No network connection available",
                recoverable = true
            )
            null
        } else {
            Log.i(TAG, "detectAndNotifyConnectionType: selected connection type = $selected")
            selected
        }
    }

    // -------------------------------------------------------------------------
    // Error message mapping
    // -------------------------------------------------------------------------

    /**
     * Maps a [WifiP2pManager] reason code to a human-readable error message.
     *
     * @param reason One of [WifiP2pManager.ERROR] (0), [WifiP2pManager.BUSY] (1), or
     *               [WifiP2pManager.P2P_UNSUPPORTED] (2).
     */
    private fun mapWifiP2pReasonToMessage(reason: Int): String = when (reason) {
        REASON_WIFI_DISABLED -> "WiFi is disabled. Please enable WiFi to discover devices."
        WifiP2pManager.ERROR -> "Wi-Fi Direct error. Please try again."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Please try again."
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
        else -> "Wi-Fi Direct error (code $reason). Please try again."
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirror",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active screen mirroring session"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "MirrorApp/MirrorService"
        private const val CHANNEL_ID = "mirror_service_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
