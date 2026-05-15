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
                    is MirrorState.Streaming -> promoteToForeground(isStreaming = true)
                    is MirrorState.Idle, is MirrorState.Error -> promoteToForeground(isStreaming = false)
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
        client.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Public API for MainActivity ---

    fun startDiscovery() = client.startDiscovery()
    fun stopDiscovery() = client.stopDiscovery()
    fun connect(receiver: Receiver, config: com.antigravity.mirror.stream.api.MirrorConfig) = client.connect(receiver, config)
    fun connectManual(host: String, port: Int, config: com.antigravity.mirror.stream.api.MirrorConfig) = client.connectManual(host, port, config)
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
    fun getState(): StateFlow<MirrorState> = client.state
    fun getStats(): StateFlow<com.antigravity.mirror.stream.api.SessionStats> = client.stats

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
                    CHANNEL_ID, "Screen Mirror", NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }
}
