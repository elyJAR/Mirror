package com.antigravity.mirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
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
    // Views — Kinetic Glass panels
    // -------------------------------------------------------------------------

    // Panels
    private lateinit var panelHome: View
    private lateinit var panelScanning: View
    private lateinit var panelStreaming: View
    private lateinit var panelConnecting: View
    private lateinit var panelError: View

    // Home panel
    private lateinit var discoverButton: Button

    // Scanning panel
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var deviceList: RecyclerView
    private lateinit var radarRing1: View
    private lateinit var radarRing2: View

    // Streaming panel
    private lateinit var disconnectButton: Button
    private lateinit var streamTimer: TextView
    private lateinit var streamTargetName: TextView
    private lateinit var streamStatsHud: TextView
    private lateinit var reverseControlCard: View
    private lateinit var reverseControlStatus: TextView
    private lateinit var reverseControlSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var extendDisplayCard: View
    private lateinit var extendDisplayStatus: TextView

    // Connecting panel
    private lateinit var connectingLabel: TextView
    private lateinit var connectingSubLabel: TextView

    // Error panel
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button

    // Bottom nav
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var deviceAdapter: DeviceAdapter

    // Stream timer state
    private var streamStartMs = 0L
    private var streamTimerTask: Timer? = null
    
    private var hudVisible = false
    private var lastStats: SessionStats? = null

    private lateinit var latencyToggleGroup: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var latencyToggleGroupStreaming: com.google.android.material.button.MaterialButtonToggleGroup

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
            
            // Only auto-discover if we're not already streaming/connecting
            if (mirrorService?.getState()?.value is MirrorState.Idle) {
                if (PermissionManager.missingPermissions(this@MainActivity).isEmpty()) {
                    mirrorService?.startDiscovery()
                }
            }
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
            } else {
                mirrorService?.startDiscovery()
            }
        }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Panels
        panelHome      = findViewById(R.id.panelHome)
        panelScanning  = findViewById(R.id.panelScanning)
        panelStreaming  = findViewById(R.id.panelStreaming)
        panelConnecting = findViewById(R.id.panelConnecting)
        panelError     = findViewById(R.id.panelError)

        // Home
        discoverButton = findViewById(R.id.discoverButton)

        // Scanning
        progressBar  = findViewById(R.id.progressBar)
        statusText   = findViewById(R.id.statusText)
        deviceList   = findViewById(R.id.deviceList)
        radarRing1   = findViewById(R.id.radarRing1)
        radarRing2   = findViewById(R.id.radarRing2)

        // Streaming
        disconnectButton = findViewById(R.id.disconnectButton)
        streamTimer      = findViewById(R.id.streamTimer)
        streamTargetName = findViewById(R.id.streamTargetName)
        streamStatsHud   = findViewById(R.id.streamStatsHud)

        // Connecting
        connectingLabel    = findViewById(R.id.connectingLabel)
        connectingSubLabel = findViewById(R.id.connectingSubLabel)

        // Error
        errorMessage = findViewById(R.id.errorMessage)
        retryButton  = findViewById(R.id.retryButton)

        // Reverse Control
        reverseControlCard = findViewById(R.id.reverseControlCard)
        reverseControlStatus = findViewById(R.id.reverseControlStatus)
        reverseControlSwitch = findViewById(R.id.reverseControlSwitch)
        
        // Extend Display
        extendDisplayCard = findViewById(R.id.extendDisplayCard)
        extendDisplayStatus = findViewById(R.id.extendDisplayStatus)

        extendDisplayCard.setOnClickListener {
            if (mirrorService != null) {
                try {
                    mirrorService?.toggleProjection()
                    Toast.makeText(this, "Requesting PC projection...", Toast.LENGTH_SHORT).show()
                    extendDisplayStatus.text = "Request sent to PC"
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending projection request: ${e.message}", e)
                    Toast.makeText(this, "Failed to send request: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
            }
        }
        
        reverseControlCard.setOnClickListener {
            if (!com.antigravity.mirror.service.InputAccessibilityService.isEnabled()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        
        reverseControlSwitch.setOnCheckedChangeListener { _, _ ->
            // Re-sync switch state and open settings if toggled
            updateReverseControlUI()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Bottom nav
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.nav_mirror
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_mirror   -> { /* already on mirror tab */ }
                else -> { /* history / support — future screens */ }
            }
            true
        }

        deviceAdapter = DeviceAdapter(emptyList()) { receiver -> onDeviceSelected(receiver) }
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        discoverButton.setOnClickListener  { onDiscoverClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }
        retryButton.setOnClickListener     { onDiscoverClicked() }

        streamStatsHud.setOnLongClickListener {
            hudVisible = !hudVisible
            streamStatsHud.visibility = if (hudVisible) View.VISIBLE else View.GONE
            true
        }

        latencyToggleGroup = findViewById(R.id.latencyToggleGroup)
        latencyToggleGroupStreaming = findViewById(R.id.latencyToggleGroupStreaming)
        setupLatencyToggles()

        checkAndRequestPermissions()
    }

    private fun setupLatencyToggles() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val modeStr = prefs.getString("latency_mode", "BALANCED") ?: "BALANCED"
        val mode = com.antigravity.mirror.stream.api.LatencyMode.valueOf(modeStr)
        updateLatencyUI(mode)

        latencyToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = when (checkedId) {
                    R.id.btnLowLatency -> com.antigravity.mirror.stream.api.LatencyMode.LOW
                    R.id.btnHighQuality -> com.antigravity.mirror.stream.api.LatencyMode.QUALITY
                    else -> com.antigravity.mirror.stream.api.LatencyMode.BALANCED
                }
                saveLatencyMode(newMode)
                updateLatencyUI(newMode)
            }
        }

        latencyToggleGroupStreaming.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = when (checkedId) {
                    R.id.btnLowLatencyStreaming -> com.antigravity.mirror.stream.api.LatencyMode.LOW
                    R.id.btnHighQualityStreaming -> com.antigravity.mirror.stream.api.LatencyMode.QUALITY
                    else -> com.antigravity.mirror.stream.api.LatencyMode.BALANCED
                }
                saveLatencyMode(newMode)
                updateLatencyUI(newMode)
            }
        }
        
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "latency_mode") {
            val modeStr = sharedPreferences.getString("latency_mode", "BALANCED") ?: "BALANCED"
            val mode = com.antigravity.mirror.stream.api.LatencyMode.valueOf(modeStr)
            updateLatencyUI(mode)
            updateServiceConfig()
        }
    }

    private fun saveLatencyMode(mode: com.antigravity.mirror.stream.api.LatencyMode) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("latency_mode", mode.name)
            .apply()
        updateServiceConfig()
    }

    private fun updateLatencyUI(mode: com.antigravity.mirror.stream.api.LatencyMode) {
        val homeId = when (mode) {
            com.antigravity.mirror.stream.api.LatencyMode.LOW -> R.id.btnLowLatency
            com.antigravity.mirror.stream.api.LatencyMode.QUALITY -> R.id.btnHighQuality
            else -> R.id.btnBalanced
        }
        val streamId = when (mode) {
            com.antigravity.mirror.stream.api.LatencyMode.LOW -> R.id.btnLowLatencyStreaming
            com.antigravity.mirror.stream.api.LatencyMode.QUALITY -> R.id.btnHighQualityStreaming
            else -> R.id.btnBalancedStreaming
        }
        
        latencyToggleGroup.check(homeId)
        latencyToggleGroupStreaming.check(streamId)
    }

    private fun updateServiceConfig() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val modeStr = prefs.getString("latency_mode", "BALANCED") ?: "BALANCED"
        val mode = com.antigravity.mirror.stream.api.LatencyMode.valueOf(modeStr)
        
        val config = com.antigravity.mirror.stream.api.MirrorConfig(
            latencyMode = mode
        )
        mirrorService?.setConfig(config)
    }

    override fun onStart() {
        super.onStart()
        val serviceIntent = Intent(this, MirrorService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updateReverseControlUI()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun updateReverseControlUI() {
        val enabled = com.antigravity.mirror.service.InputAccessibilityService.isEnabled()
        reverseControlSwitch.isChecked = enabled
        reverseControlStatus.text = if (enabled) "Enabled" else "Off — Tap to enable"
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
        lifecycleScope.launch {
            mirrorService?.getControlMessages()?.collect { msg ->
                if (msg is com.antigravity.mirror.stream.transport.lan.protocol.ProjectionStateMessage) {
                    updateProjectionUI(msg.active)
                }
            }
        }
    }

    private fun updateProjectionUI(active: Boolean) {
        extendDisplayStatus.text = if (active) "Currently projecting" else "Toggle secondary monitor"
        extendDisplayCard.alpha = if (active) 1.0f else 0.8f
    }

    private fun renderHUD(stats: com.antigravity.mirror.stream.api.SessionStats) {
        val text = "${stats.fps} FPS · ${stats.bitrateKbps} kbps · Q:${stats.queueDepth} · D:${stats.droppedFrames}"
        streamStatsHud.text = text
        streamStatsHud.visibility = View.VISIBLE
    }

    // ── panel helpers ──────────────────────────────────────────────────────────

    private fun showPanel(panel: View) {
        listOf(panelHome, panelScanning, panelStreaming, panelConnecting, panelError)
            .forEach { it.visibility = if (it === panel) View.VISIBLE else View.GONE }
    }

    private fun startRadarPulse() {
        for (ring in listOf(radarRing1, radarRing2)) {
            ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 1f).apply {
                duration = 2000; repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator(); start()
            }
            ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 1f).apply {
                duration = 2000; repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator(); start()
            }
            ObjectAnimator.ofFloat(ring, "alpha", 0.8f, 0f).apply {
                duration = 2000; repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator(); start()
            }
        }
    }

    private fun startStreamTimer(targetName: String) {
        streamStartMs = mirrorService?.getStreamStartMs() ?: System.currentTimeMillis()
        streamTargetName.text = targetName
        streamTimerTask?.cancel()
        streamTimerTask = Timer().also { timer ->
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val elapsed = (System.currentTimeMillis() - streamStartMs) / 1000
                    val formatted = String.format(Locale.ROOT, "%02d:%02d", elapsed / 60, elapsed % 60)
                    runOnUiThread { streamTimer.text = formatted }
                }
            }, 0L, 1000L)
        }
    }

    private fun stopStreamTimer() {
        streamTimerTask?.cancel()
        streamTimerTask = null
    }

    private var pinDialog: AlertDialog? = null

    // ── state renderer ─────────────────────────────────────────────────────────

    private fun renderState(state: MirrorState) {
        Log.i(TAG, "Rendering state: ${state::class.simpleName}")
        
        if (state !is MirrorState.AwaitingPairing) {
            pinDialog?.dismiss()
            pinDialog = null
        }

        when (state) {

            is MirrorState.Idle -> {
                projectionConsentLaunched = false
                stopStreamTimer()
                deviceAdapter.updateDevices(emptyList())
                showPanel(panelHome)
            }

            is MirrorState.Discovering -> {
                statusText.text = getString(R.string.label_scanning_network)
                progressBar.visibility = View.VISIBLE
                deviceAdapter.updateDevices(emptyList())
                showPanel(panelScanning)
                startRadarPulse()
            }

            is MirrorState.ReceiversFound -> {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.label_scanning_network)
                deviceAdapter.updateDevices(state.receivers)
                showPanel(panelScanning)
            }

            is MirrorState.Connecting -> {
                connectingLabel.text    = getString(R.string.label_connecting)
                connectingSubLabel.text = ""
                showPanel(panelConnecting)
            }

            is MirrorState.AwaitingProjection -> {
                connectingLabel.text    = getString(R.string.label_awaiting_consent)
                connectingSubLabel.text = getString(R.string.label_awaiting_consent)
                showPanel(panelConnecting)
                if (!projectionConsentLaunched) {
                    projectionConsentLaunched = true
                    launchProjectionConsent()
                }
            }

            is MirrorState.AwaitingPairing -> {
                connectingLabel.text    = getString(R.string.label_awaiting_pin)
                connectingSubLabel.text = getString(R.string.dialog_pin_message)
                showPanel(panelConnecting)
                showPinInputDialog()
            }

            is MirrorState.Reconnecting -> {
                connectingLabel.text    = getString(R.string.label_reconnecting)
                connectingSubLabel.text = ""
                showPanel(panelConnecting)
            }

            is MirrorState.Streaming -> {
                val target = "Connected Display"
                startStreamTimer(target)
                showPanel(panelStreaming)
            }

            is MirrorState.Error -> {
                projectionConsentLaunched = false
                stopStreamTimer()
                val message = state.cause.message ?: "Unknown error"
                errorMessage.text = message
                showPanel(panelError)
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
            .setMessage("Please grant permissions in the app settings to use Mirror.")
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
        if (pinDialog?.isShowing == true) return
        Log.i(TAG, "Showing PIN input dialog")

        val view = layoutInflater.inflate(R.layout.dialog_pin_entry, null)
        val hiddenInput = view.findViewById<EditText>(R.id.hiddenPinInput)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConnect = view.findViewById<MaterialButton>(R.id.btnConnect)

        val digits = arrayOf(
            view.findViewById<TextView>(R.id.pinDigit1),
            view.findViewById<TextView>(R.id.pinDigit2),
            view.findViewById<TextView>(R.id.pinDigit3),
            view.findViewById<TextView>(R.id.pinDigit4)
        )

        val pinRow = view.findViewById<View>(R.id.pinRow)

        // Helper to update the active and filled visual states of the digit boxes
        fun updatePinUi(text: CharSequence) {
            for (i in 0 until 4) {
                if (i < text.length) {
                    digits[i].text = text[i].toString()
                    digits[i].setBackgroundResource(R.drawable.bg_pin_digit_active)
                } else {
                    digits[i].text = ""
                    if (i == text.length) {
                        // Highlight the box currently awaiting input
                        digits[i].setBackgroundResource(R.drawable.bg_pin_digit_active)
                    } else {
                        digits[i].setBackgroundResource(R.drawable.bg_pin_digit)
                    }
                }
            }
        }

        // Initialize empty state UI
        updatePinUi("")

        hiddenInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentText = s ?: ""
                updatePinUi(currentText)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Open system soft keyboard and focus the hidden EditText when digit cards are clicked
        val focusAction = Runnable {
            hiddenInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(hiddenInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        pinRow.setOnClickListener { focusAction.run() }
        for (digit in digits) {
            digit.setOnClickListener { focusAction.run() }
        }

        pinDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            mirrorService?.disconnect()
            pinDialog?.dismiss()
            pinDialog = null
        }

        btnConnect.setOnClickListener {
            val pin = hiddenInput.text.toString().trim()
            if (pin.length == 4) {
                mirrorService?.submitPin(pin)
                pinDialog?.dismiss()
                pinDialog = null
            } else {
                Toast.makeText(this, "Please enter a 4-digit PIN", Toast.LENGTH_SHORT).show()
            }
        }

        pinDialog?.show()

        // Clear the default Android white dialog window background so our rounded container is fully visible
        pinDialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Focus and pop-up keyboard automatically after the view finishes inflating
        view.postDelayed(focusAction, 200)
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
