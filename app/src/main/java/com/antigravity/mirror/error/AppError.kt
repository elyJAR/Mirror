package com.antigravity.mirror.error

/**
 * Typed error model used throughout the app.
 *
 * Each subtype represents a distinct failure mode with enough context for the UI
 * to display a meaningful message and offer an appropriate recovery action.
 */
sealed class AppError {

    /** The device's WiFi radio is disabled. User should be directed to WiFi settings. */
    data class WifiDisabled(val message: String) : AppError()

    /** The device hardware does not support Wi-Fi Direct (P2P). */
    data class WifiDirectUnsupported(val message: String) : AppError()

    /**
     * Peer discovery failed with a WifiP2pManager error code.
     * @param reason One of WifiP2pManager.ERROR, BUSY, or P2P_UNSUPPORTED.
     */
    data class DiscoveryFailed(val reason: Int) : AppError()

    /**
     * Wi-Fi Direct connection to the sink failed.
     * @param reason One of WifiP2pManager.ERROR, BUSY, or P2P_UNSUPPORTED.
     */
    data class ConnectionFailed(val reason: Int) : AppError()

    /** The user denied the MediaProjection screen capture consent dialog. */
    data class ProjectionDenied(val message: String) : AppError()

    /** An error occurred in the streaming pipeline (MediaCodec, RTP socket, etc.). */
    data class StreamingError(val cause: Throwable) : AppError()

    /** The network connection was lost while a session was active. */
    data class NetworkLost(val message: String) : AppError()

    /** The Miracast sink requires PIN authentication before the session can proceed. */
    data class PinRequired(val message: String) : AppError()

    /** The PIN entered by the user was rejected by the Miracast sink. */
    data class IncorrectPin(val message: String) : AppError()
}
