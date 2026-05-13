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
                when (state) {
                    is MirrorState.Streaming -> promoteToForeground(isStreaming = true)
                    is MirrorState.Idle, is MirrorState.Error -> promoteToForeground(isStreaming = false)
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
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
    fun onProjectionGranted(resultCode: Int, data: Intent) = client.onProjectionGranted(resultCode, data)
    fun disconnect() = client.disconnect()
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
