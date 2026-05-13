package com.antigravity.mirror.stream.api

/**
 * Sealed hierarchy of typed errors that can surface from a mirror session.
 *
 * Every [MirrorState.Error] carries one of these. UI maps them to user-facing
 * strings; integrators can switch on the concrete subtype to take recovery action.
 */
sealed class MirrorError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Could not reach the host (DNS / TCP connect failure on LAN, no peer found on Miracast). */
    class NetworkUnreachable(host: String, cause: Throwable? = null) :
        MirrorError("Cannot reach $host", cause)

    /** Receiver rejected the handshake (incompatible protocol version, busy, etc.). */
    class HandshakeFailed(val reason: String) :
        MirrorError("Handshake rejected: $reason")

    /** Wire-format violation by the peer. Indicates a bug or hostile peer. */
    class ProtocolViolation(detail: String) :
        MirrorError("Protocol violation: $detail")

    /** [android.media.MediaCodec] failed to configure or encode. */
    class EncoderFailure(cause: Throwable) :
        MirrorError("Encoder error: ${cause.message}", cause)

    /** User declined the system MediaProjection consent dialog. */
    class ProjectionDenied :
        MirrorError("User denied screen capture consent")

    /** Screen capture was stopped by the system (screen off policy, user revoked, etc.). */
    class ProjectionLost :
        MirrorError("Screen capture stopped by the system")

    /** Peer closed the session, optionally with a reason string from a `bye` message. */
    class PeerDisconnected(val reason: String?) :
        MirrorError("Peer closed: ${reason ?: "(no reason)"}")

    /** A timed operation did not complete within its budget. */
    class Timeout(stage: String) :
        MirrorError("Timeout during $stage")

    /**
     * Miracast-specific: the device's firmware blocks third-party `WifiP2pManager.connect()`.
     * The selector demotes the device's allow-list entry to DENIED on this error and falls
     * back to the LAN transport when [MirrorConfig.transport] is [Transport.AUTO].
     */
    class MiracastBlocked(detail: String) :
        MirrorError("Miracast initiation blocked by device firmware: $detail")

    /** Both transports were attempted (or selectively forced) and none succeeded. */
    class NoTransportAvailable(detail: String) :
        MirrorError("No usable transport: $detail")
}
