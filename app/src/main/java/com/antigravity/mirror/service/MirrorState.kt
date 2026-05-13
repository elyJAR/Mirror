package com.antigravity.mirror.service

import android.net.wifi.p2p.WifiP2pDevice

/**
 * Represents the current state of the Miracast streaming pipeline.
 *
 * Emitted by [MirrorService] via a [kotlinx.coroutines.flow.StateFlow] and consumed
 * by [com.antigravity.mirror.ui.MainActivity] to drive the UI.
 */
sealed class MirrorState {

    /** No active session; the app is idle and ready to start discovery. */
    object Idle : MirrorState()

    /** Wi-Fi Direct peer discovery is in progress. */
    object Discovering : MirrorState()

    /**
     * Discovery completed and one or more Miracast sink devices were found.
     * @param devices The list of discovered Wi-Fi Direct peer devices.
     */
    data class DevicesFound(val devices: List<WifiP2pDevice>) : MirrorState()

    /** A connection to the selected sink is being established. */
    object Connecting : MirrorState()

    /**
     * The RTSP handshake has reached the point where the Android system screen-capture
     * consent dialog must be shown to the user before streaming can begin.
     */
    object AwaitingProjectionConsent : MirrorState()

    /** Screen capture consent has been granted and the stream is actively transmitting. */
    object Streaming : MirrorState()

    /**
     * An error occurred. The UI should display [message] and offer a retry or back action
     * depending on [recoverable].
     *
     * @param message Human-readable description of the error.
     * @param recoverable `true` if the user can retry the last action; `false` if the app
     *                    must return to the device list.
     */
    data class Error(val message: String, val recoverable: Boolean) : MirrorState()
}
