package com.antigravity.mirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
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
import com.antigravity.mirror.service.MirrorState
import com.antigravity.mirror.service.PermissionManager
import kotlinx.coroutines.launch

/**
 * Single-screen Activity that hosts the device list, discovery button, and active session controls.
 *
 * Communicates with [MirrorService] via binding and a [ServiceConnection].
 * Observes [MirrorState] from the service's [kotlinx.coroutines.flow.StateFlow] to drive the UI.
 *
 * Requirements: 6.1, 6.2, 7.1, 7.2, 7.3, 7.4, 4.3, 4.4, 9.1, 9.2, 8.1, 8.2, 8.3
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
    // MediaProjection consent — Task 11.1
    // -------------------------------------------------------------------------

    /**
     * Flag that prevents the consent dialog from being launched more than once per
     * [MirrorState.AwaitingProjectionConsent] state entry.
     *
     * Reset to `false` when state transitions back to [MirrorState.Idle] or [MirrorState.Error].
     *
     * Requirements: 8.1, 8.3
     */
    private var projectionConsentLaunched = false

    /**
     * Modern [ActivityResultLauncher] for the screen-capture consent dialog.
     *
     * - [Activity.RESULT_OK] + non-null data → create [android.media.projection.MediaProjection]
     *   and hand it to [MirrorService.onProjectionGranted].
     * - Any other result → call [MirrorService.disconnect] to cancel session establishment.
     *
     * Requirements: 8.1, 8.2, 8.3
     */
    private val projectionConsentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.i(TAG, "Screen capture consent granted")
                val mediaProjectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                mirrorService?.onProjectionGranted(projection)
            } else {
                Log.w(TAG, "Screen capture consent denied or cancelled (resultCode=$resultCode)")
                mirrorService?.disconnect()
            }
        }

    // -------------------------------------------------------------------------
    // Permission launcher — Task 12.2
    // -------------------------------------------------------------------------

    /**
     * Launcher for requesting multiple runtime permissions at once.
     *
     * In the result callback:
     * - If all required permissions are granted, nothing more is needed.
     * - If any required permission is still missing AND shouldShowRationale returns false
     *   (permanently denied), show a dialog directing the user to app settings.
     *
     * Requirements: 7.3, 7.4
     */
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val stillMissing = results.entries
                .filter { !it.value }
                .map { it.key }

            if (stillMissing.isNotEmpty()) {
                // Check if any are permanently denied (rationale returns false after denial)
                val permanentlyDenied = stillMissing.any { permission ->
                    !PermissionManager.shouldShowRationale(this, permission)
                }
                if (permanentlyDenied) {
                    showSettingsDialog()
                }
                // Otherwise the user just denied — they can try again via Discover button
            }
        }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        deviceList = findViewById(R.id.deviceList)
        discoverButton = findViewById(R.id.discoverButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        // Set up RecyclerView
        deviceAdapter = DeviceAdapter(emptyList()) { device -> onDeviceSelected(device) }
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        // Wire buttons
        discoverButton.setOnClickListener { onDiscoverClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }

        // Task 12.2: check permissions on launch
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Start the service explicitly so onStartCommand runs and startForeground is called,
        // then bind to get the Binder reference. BIND_AUTO_CREATE alone never calls onStartCommand.
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
    // State observation
    // -------------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            mirrorService?.getState()?.collect { state ->
                renderState(state)
            }
        }
    }

    /**
     * Renders the current [MirrorState] in the UI and drives side-effects such as
     * launching the MediaProjection consent dialog.
     *
     * Requirements: 6.1, 4.3, 4.4, 9.1, 9.2, 8.1, 8.2, 8.3
     */
    private fun renderState(state: MirrorState) {
        when (state) {
            is MirrorState.Idle -> {
                projectionConsentLaunched = false
                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                discoverButton.visibility = View.VISIBLE
                disconnectButton.visibility = View.GONE
                deviceAdapter.updateDevices(emptyList())
            }

            is MirrorState.Discovering -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_discovering)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                disconnectButton.visibility = View.GONE
            }

            is MirrorState.DevicesFound -> {
                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                discoverButton.visibility = View.VISIBLE
                disconnectButton.visibility = View.GONE
                deviceAdapter.updateDevices(state.devices)
            }

            is MirrorState.Connecting -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_connecting)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                disconnectButton.visibility = View.GONE
            }

            is MirrorState.AwaitingProjectionConsent -> {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.label_awaiting_consent)
                statusText.visibility = View.VISIBLE
                discoverButton.visibility = View.GONE
                disconnectButton.visibility = View.GONE

                // Launch the system consent dialog exactly once per state entry.
                if (!projectionConsentLaunched) {
                    projectionConsentLaunched = true
                    launchProjectionConsent()
                }
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

                val message = state.message

                when {
                    // Task 12.3: PIN required
                    message.contains("PIN", ignoreCase = true) -> {
                        statusText.text = message
                        statusText.visibility = View.VISIBLE
                        discoverButton.visibility = if (state.recoverable) View.VISIBLE else View.GONE
                        disconnectButton.visibility = View.GONE
                        showPinDialog()
                    }

                    // Task 12.4: WiFi disabled
                    message.contains("WiFi is disabled", ignoreCase = true) ||
                    message.contains("No network", ignoreCase = true) -> {
                        statusText.text = message
                        statusText.visibility = View.VISIBLE
                        discoverButton.visibility = if (state.recoverable) View.VISIBLE else View.GONE
                        disconnectButton.visibility = View.GONE
                        showWifiRequiredDialog(message)
                    }

                    // Task 12.4: WiFi Direct not supported
                    message.contains("not supported", ignoreCase = true) -> {
                        statusText.text = message
                        statusText.visibility = View.VISIBLE
                        discoverButton.visibility = View.GONE
                        disconnectButton.visibility = View.GONE
                        showNotSupportedDialog(message)
                    }

                    // Generic error
                    else -> {
                        statusText.text = message
                        statusText.visibility = View.VISIBLE
                        discoverButton.visibility = if (state.recoverable) View.VISIBLE else View.GONE
                        disconnectButton.visibility = View.GONE
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // MediaProjection consent launch
    // -------------------------------------------------------------------------

    /**
     * Launches the Android system screen-capture consent dialog via
     * [MediaProjectionManager.createScreenCaptureIntent].
     *
     * Requirements: 8.1
     */
    private fun launchProjectionConsent() {
        Log.i(TAG, "Launching screen capture consent dialog")
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionConsentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // -------------------------------------------------------------------------
    // Permission handling — Task 12.2
    // -------------------------------------------------------------------------

    /**
     * Checks for missing permissions and requests them, showing rationale dialogs where needed.
     *
     * Requirements: 7.1, 7.2, 7.3, 7.4
     */
    private fun checkAndRequestPermissions() {
        val missing = PermissionManager.missingPermissions(this)
        if (missing.isEmpty()) return

        // Separate permissions that need a rationale from those that don't
        val needRationale = missing.filter { PermissionManager.shouldShowRationale(this, it) }

        if (needRationale.isNotEmpty()) {
            // Show a single rationale dialog covering all permissions that need one,
            // then request all missing permissions on OK.
            val rationaleMessage = buildRationaleMessage(needRationale)
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage(rationaleMessage)
                .setPositiveButton("OK") { _, _ ->
                    permissionLauncher.launch(missing.toTypedArray())
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Builds a human-readable rationale message for the given list of permissions.
     */
    private fun buildRationaleMessage(permissions: List<String>): String {
        val lines = permissions.map { permission ->
            when {
                permission.contains("FINE_LOCATION") ->
                    getString(R.string.permission_rationale_location)
                permission.contains("NEARBY_WIFI") ->
                    getString(R.string.permission_rationale_nearby_wifi)
                else -> "Permission required: $permission"
            }
        }
        return lines.joinToString("\n\n")
    }

    /**
     * Shows a dialog directing the user to grant permissions in app settings.
     *
     * Requirements: 7.4
     */
    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "Some required permissions have been permanently denied. " +
                "Please grant them in the app settings to use Screen Mirror."
            )
            .setPositiveButton(getString(R.string.settings_open)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // PIN dialog — Task 12.3
    // -------------------------------------------------------------------------

    /**
     * Shows a PIN entry dialog when the Miracast sink requires PIN authentication.
     *
     * Requirements: 4.3, 4.4
     */
    private fun showPinDialog() {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.pin_dialog_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_dialog_title))
            .setMessage("Enter the PIN shown on your PC's Connect app")
            .setView(pinInput)
            .setPositiveButton(getString(R.string.pin_dialog_ok)) { _, _ ->
                val pin = pinInput.text.toString().trim()
                if (pin.isNotEmpty()) {
                    mirrorService?.submitPin(pin)
                }
            }
            .setNegativeButton(getString(R.string.pin_dialog_cancel)) { _, _ ->
                mirrorService?.disconnect()
            }
            .setCancelable(false)
            .show()
    }

    // -------------------------------------------------------------------------
    // WiFi / unsupported dialogs — Task 12.4
    // -------------------------------------------------------------------------

    /**
     * Shows a dialog prompting the user to enable WiFi, with a shortcut to WiFi settings.
     *
     * Requirements: 9.1
     */
    private fun showWifiRequiredDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("WiFi Required")
            .setMessage(message)
            .setPositiveButton("Open WiFi Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a dialog informing the user that WiFi Direct is not supported on this device.
     *
     * Requirements: 9.2
     */
    private fun showNotSupportedDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Not Supported")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // User interactions
    // -------------------------------------------------------------------------

    /**
     * Called when the user taps the "Discover" button.
     * Checks permissions before starting discovery.
     *
     * Requirements: 7.1, 7.2, 2.1
     */
    fun onDiscoverClicked() {
        val missing = PermissionManager.missingPermissions(this)
        if (missing.isNotEmpty()) {
            checkAndRequestPermissions()
            return
        }
        val service = mirrorService
        if (service == null) {
            Log.w(TAG, "onDiscoverClicked: service not yet bound, retrying bind")
            // Service not connected yet — re-trigger bind and show a brief status
            statusText.text = "Starting service…"
            statusText.visibility = View.VISIBLE
            startService(Intent(this, MirrorService::class.java))
            bindService(Intent(this, MirrorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            return
        }
        service.startDiscovery()
    }

    /** Called when the user selects a device from the list. */
    fun onDeviceSelected(device: WifiP2pDevice) {
        mirrorService?.connectToDevice(device)
    }

    /** Called when the user taps the "Disconnect" button during an active session. */
    fun onDisconnectClicked() {
        mirrorService?.disconnect()
    }

    companion object {
        private const val TAG = "MirrorApp/MainActivity"
    }
}
