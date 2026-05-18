package com.antigravity.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antigravity.mirror.R
import com.antigravity.mirror.stream.api.MirrorClient
import com.antigravity.mirror.stream.api.MirrorState
import com.antigravity.mirror.stream.api.Receiver
import com.antigravity.mirror.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "MirrorApp/MirrorService"
private const val CHANNEL_ID = "mirror_service_channel"
private const val NOTIFICATION_ID = 1001

/**
 * Foreground service that hosts the [MirrorClient] and manages its lifecycle.
 *
 * This service ensures that the mirroring process remains active even if the Activity is
 * destroyed, and satisfies Android's foreground service requirements for MediaProjection.
 */
class MirrorService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MirrorService = this@MirrorService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var client: MirrorClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MirrorService creating")
        client = MirrorClient(this)
        
        // Initial foreground promotion
        promoteToForeground(isStreaming = false)
        
        // Monitor state transitions
        serviceScope.launch {
            client.state.collect { state ->
                Log.i(TAG, "Client state: ${state::class.simpleName}")
                when (state) {
                    is MirrorState.Streaming -> {
                        promoteToForeground(isStreaming = true)
                        acquireWakeLocks()
                    }
                    is MirrorState.Idle, is MirrorState.Error -> {
                        promoteToForeground(isStreaming = false)
                        releaseWakeLocks()
                    }
                    else -> {}
                }
            }
        }

        // Setup input injection (Reverse Control)
        client.setInputInjector { event ->
            InputAccessibilityService.getInstance()?.let { service ->
                when (event) {
                    is com.antigravity.mirror.stream.transport.TransportEvent.InjectTouch -> {
                        service.injectTouch(event.action, event.x, event.y)
                    }
                    is com.antigravity.mirror.stream.transport.TransportEvent.InjectKey -> {
                        service.injectKey(event.code)
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (app swiped away), stopping service")
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "MirrorService destroying")
        releaseWakeLocks()
        client.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                
                // Read preference to determine wake lock level
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                val keepAwake = prefs.getBoolean("keep_awake", false)
                
                val level = if (keepAwake) {
                    @Suppress("DEPRECATION")
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
                } else {
                    PowerManager.PARTIAL_WAKE_LOCK
                }
                
                Log.i(TAG, "Creating WakeLock with level: ${if (keepAwake) "SCREEN_DIM" else "PARTIAL"}")
                wakeLock = powerManager.newWakeLock(
                    level,
                    "MirrorApp::StreamingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                Log.i(TAG, "Acquiring PowerManager WakeLock")
                wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours safety timeout
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "MirrorApp::StreamingWifiLock"
                    )
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL,
                        "MirrorApp::StreamingWifiLock"
                    )
                }.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                Log.i(TAG, "Acquiring WifiManager WifiLock")
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                Log.i(TAG, "Releasing PowerManager WakeLock")
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        } finally {
            wakeLock = null
        }

        try {
            if (wifiLock?.isHeld == true) {
                Log.i(TAG, "Releasing WifiManager WifiLock")
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WifiLock", e)
        } finally {
            wifiLock = null
        }
    }

    // --- Public API for MainActivity ---

    private var cachedConfig: com.antigravity.mirror.stream.api.MirrorConfig? = null

    fun setConfig(config: com.antigravity.mirror.stream.api.MirrorConfig) {
        cachedConfig = config
    }

    fun startDiscovery() = client.startDiscovery()
    fun stopDiscovery() = client.stopDiscovery()
    fun connect(receiver: Receiver, config: com.antigravity.mirror.stream.api.MirrorConfig? = null) {
        val finalConfig = config ?: cachedConfig ?: loadConfigFromPrefs(getSharedPreferences("mirror_settings", MODE_PRIVATE))
        client.connect(receiver, finalConfig)
    }
    fun connectManual(host: String, port: Int, config: com.antigravity.mirror.stream.api.MirrorConfig? = null) {
        val finalConfig = config ?: cachedConfig ?: loadConfigFromPrefs(getSharedPreferences("mirror_settings", MODE_PRIVATE))
        client.connectManual(host, port, finalConfig)
    }
    fun loadConfigFromPrefs(prefs: android.content.SharedPreferences) = client.loadConfigFromPrefs(prefs)
    fun onProjectionGranted(resultCode: Int, data: Intent) {
        Log.i(TAG, "=== onProjectionGranted called ===")
        // IMPORTANT: Update foreground service type to MEDIA_PROJECTION before creating ScreenCaptureEngine
        // This must happen BEFORE MirrorClient attempts to create the projection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "Switching foreground service to MEDIA_PROJECTION type")
            val notification = buildNotification(isStreaming = false)
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                Log.i(TAG, "Successfully switched to MEDIA_PROJECTION service type")
            }.onFailure { e ->
                Log.e(TAG, "Failed to switch to MEDIA_PROJECTION service type", e)
            }
        }
        
        Log.i(TAG, "Calling client.onProjectionGranted()")
        client.onProjectionGranted(resultCode, data)
        Log.i(TAG, "client.onProjectionGranted() returned")
    }
    fun disconnect() = client.disconnect()
    fun submitPin(pin: String) = client.submitPin(pin)
    fun sendControl(message: com.antigravity.mirror.stream.transport.lan.protocol.ControlMessage) = client.sendControl(message)
    fun toggleProjection() = client.toggleRemoteProjection()
    fun getState(): StateFlow<MirrorState> = client.state
    fun getStats(): StateFlow<com.antigravity.mirror.stream.api.SessionStats> = client.stats
    fun getControlMessages() = client.controlMessages
    fun getStreamStartMs(): Long = client.getStreamStartMs()

    // --- Foreground Support ---

    private fun promoteToForeground(isStreaming: Boolean) {
        val notification = buildNotification(isStreaming)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isStreaming) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                // DATA_SYNC is a neutral placeholder that doesn't require special tokens
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            }.onFailure { e ->
                Log.e(TAG, "Failed to start foreground service with type $type", e)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(isStreaming: Boolean): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isStreaming) {
            getString(R.string.notification_text_streaming)
        } else {
            getString(R.string.notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Mirror", NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }
}
