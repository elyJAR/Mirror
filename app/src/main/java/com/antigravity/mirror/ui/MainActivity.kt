package com.antigravity.mirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antigravity.mirror.R
import com.antigravity.mirror.service.MirrorService
import com.antigravity.mirror.service.PermissionManager
import com.antigravity.mirror.stream.api.MirrorConfig
import com.antigravity.mirror.stream.api.MirrorState
import com.antigravity.mirror.stream.api.Receiver
import com.antigravity.mirror.stream.api.SessionStats
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager

/**
 * Single-screen Activity that hosts the device list, discovery button, and active session controls.
 *
 * This version uses the [com.antigravity.mirror.stream.api.MirrorClient] API via [MirrorService].
 *
 * Requirements: 6.1, 6.2, 7.1, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3
 */
class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var deviceList: RecyclerView
    private lateinit var discoverButton: Button
    private lateinit var disconnectButton: Button

    private lateinit var deviceAdapter: DeviceAdapter
    
    private var hudVisible = false
    private var lastStats: SessionStats? = null

    // -------------------------------------------------------------------------
    // Service binding
    // -------------------------------------------------------------------------

    private var mirrorService: MirrorService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as MirrorService.LocalBinder
            mirrorService = localBinder.getService()
            isBound = true
            observeState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mirrorService = null
            isBound = false
        }
    }

    // -------------------------------------------------------------------------
    // MediaProjection consent
    // -------------------------------------------------------------------------

    private var projectionConsentLaunched = false

    private val projectionConsentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.i(TAG, "Screen capture consent granted")
                mirrorService?.onProjectionGranted(resultCode, data)
            } else {
                Log.w(TAG, "Screen capture consent denied or cancelled")
                mirrorService?.disconnect()
            }
        }

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val stillMissing = results.entries.filter { !it.value }.map { it.key }
            if (stillMissing.isNotEmpty()) {
                val permanentlyDenied = stillMissing.any { !PermissionManager.shouldShowRationale(this, it) }
                if (permanentlyDenied) {
                    showSettingsDialog()
                }
            }
        }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        deviceList = findViewById(R.id.deviceList)
        discoverButton = findViewById(R.id.discoverButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        deviceAdapter = DeviceAdapter(emptyList()) { receiver -> onDeviceSelected(receiver) }
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        discoverButton.setOnClickListener { onDiscoverClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }
        
        statusText.setOnLongClickListener {
            hudVisible = !hudVisible
            if (!hudVisible) {
                // Restore normal state text
                mirrorService?.getState()?.value?.let { renderState(it) }
            } else {
                lastStats?.let { renderHUD(it) }
            }
            true
        }

        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val serviceIntent = Intent(this, MirrorService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manual_connect -> {
                showManualConnectDialog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------------------------
    // State observation
    // -------------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            mirrorService?.getState()?.collect { state ->
                renderState(state)
            }
        }
        lifecycleScope.launch {
            mirrorService?.getStats()?.collect { stats ->
                lastStats = stats
                if (hudVisible) {
                    renderHUD(stats)
                }
            }
        }
    }

    private fun renderHUD(stats: com.antigravity.mirror.stream.api.SessionStats) {
        val text = "${stats.fps} FPS | ${stats.bitrateKbps} kbps | Q:${stats.queueDepth} | D:${stats.droppedFrames}"
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun renderState(state: MirrorState) {
        Log.i(TAG, "Rendering state: ${state::class.simpleName}")
        when (state) {
            is MirrorState.Idle -> {
                projectionConsentLaunched = false
                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                discoverButton.visibility = View.VISIBLE
                disconnectButton.visibility = View.GONE
                deviceAdapter.updateDevices(emptyList())
                deviceList.visibility = View.VISIBLE
            }

            is MirrorState.Discovering -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_discovering)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                disconnectButton.visibility = View.GONE
            }

            is MirrorState.ReceiversFound -> {
                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                discoverButton.visibility = View.VISIBLE
                disconnectButton.visibility = View.GONE
                deviceAdapter.updateDevices(state.receivers)
                deviceList.visibility = View.VISIBLE
            }

            is MirrorState.Connecting -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_connecting)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                disconnectButton.visibility = View.GONE
            }

            is MirrorState.AwaitingProjection -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_awaiting_consent)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                disconnectButton.visibility = View.GONE

                if (!projectionConsentLaunched) {
                    projectionConsentLaunched = true
                    launchProjectionConsent()
                }
            }

            is MirrorState.AwaitingPairing -> {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.label_awaiting_pin)
                statusText.visibility = View.VISIBLE
                showPinInputDialog()
            }

            is MirrorState.Reconnecting -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_reconnecting)
                statusText.visibility = View.VISIBLE
            }

            is MirrorState.Streaming -> {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.label_streaming)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                deviceList.visibility = View.GONE
                disconnectButton.visibility = View.VISIBLE
            }

            is MirrorState.Error -> {
                projectionConsentLaunched = false
                progressBar.visibility = View.GONE
                deviceList.visibility = View.VISIBLE

                val message = state.cause.message ?: "Unknown error"
                statusText.text = message
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = if (state.recoverable) View.VISIBLE else View.GONE
                disconnectButton.visibility = View.GONE

                // Specific error handling (Miracast/WiFi)
                if (message.contains("WiFi", ignoreCase = true) || message.contains("network", ignoreCase = true)) {
                    showWifiRequiredDialog(message)
                }
            }
        }
    }

    private fun launchProjectionConsent() {
        Log.i(TAG, "Launching screen capture consent dialog")
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionConsentLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // -------------------------------------------------------------------------
    // Permission & Dialogs
    // -------------------------------------------------------------------------

    private fun checkAndRequestPermissions() {
        val missing = PermissionManager.missingPermissions(this)
        if (missing.isEmpty()) return
        val needRationale = missing.filter { PermissionManager.shouldShowRationale(this, it) }
        if (needRationale.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Nearby devices and location permissions are required for discovery.")
                .setPositiveButton("OK") { _, _ -> permissionLauncher.launch(missing.toTypedArray()) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Please grant permissions in the app settings to use Screen Mirror.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWifiRequiredDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("WiFi Required")
            .setMessage(message)
            .setPositiveButton("Settings") { _, _ -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualConnectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_manual_connect, null)
        val hostInput = view.findViewById<EditText>(R.id.hostInput)
        val portInput = view.findViewById<EditText>(R.id.portInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_manual_connect_title)
            .setView(view)
            .setPositiveButton(R.string.btn_connect) { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 8765
                if (host.isNotEmpty()) {
                    mirrorService?.connectManual(host, port, getMirrorConfig())
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showPinInputDialog() {
        Log.i(TAG, "Showing PIN input dialog")
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "0000"
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pin_title)
            .setMessage(R.string.dialog_pin_message)
            .setView(input)
            .setPositiveButton(R.string.btn_connect) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 4) {
                    mirrorService?.submitPin(pin)
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                mirrorService?.disconnect()
            }
            .setCancelable(false)
            .show()
    }

    private fun getMirrorConfig(): MirrorConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return mirrorService?.loadConfigFromPrefs(prefs) ?: MirrorConfig()
    }

    // -------------------------------------------------------------------------
    // Interactions
    // -------------------------------------------------------------------------

    fun onDiscoverClicked() {
        if (PermissionManager.missingPermissions(this).isNotEmpty()) {
            checkAndRequestPermissions()
            return
        }
        mirrorService?.startDiscovery()
    }

    fun onDeviceSelected(receiver: Receiver) {
        mirrorService?.connect(receiver, getMirrorConfig())
    }

    fun onDisconnectClicked() {
        mirrorService?.disconnect()
    }

    companion object {
        private const val TAG = "MirrorApp/MainActivity"
    }
}
