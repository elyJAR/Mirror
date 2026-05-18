package com.antigravity.mirror.stream.transport

import com.antigravity.mirror.stream.api.MirrorError

/**
 * Side-channel events emitted by a [TransportSession].
 *
 * These events allow the [Transport] implementation to communicate back to the session
 * state machine (e.g. peer closed the connection) or the video encoder (e.g. peer
 * requested a keyframe).
 */
sealed interface TransportEvent {
    /**
     * The peer requested a new keyframe (IDR).
     *
     * The encoder should be signaled to emit a keyframe as soon as possible.
     */
    data object RequestKeyframe : TransportEvent

    /**
     * The peer initiated a clean disconnection.
     *
     * @property reason An optional informational string provided by the peer.
     */
    data class PeerDisconnected(val reason: String?) : TransportEvent

    /**
     * A transport-level error occurred (e.g. socket timeout, network loss).
     */
    data class Error(val cause: MirrorError) : TransportEvent

    /**
     * Request to inject a touch event.
     */
    data class InjectTouch(val action: Int, val x: Float, val y: Float) : TransportEvent

    /**
     * Request to inject a key event.
     */
    data class InjectKey(val code: Int) : TransportEvent

    /**
     * The peer requires a PIN to continue.
     */
    data object PairingRequest : TransportEvent

    /**
     * PIN verification completed successfully and the session can proceed.
     */
    data object PairingVerified : TransportEvent

    /**
     * PIN verification failed.
     */
    data class PairingFailed(val reason: String) : TransportEvent

    /**
     * Protocol-specific control message received from the peer.
     */
    data class ProtocolControl(val message: com.antigravity.mirror.stream.transport.lan.protocol.ControlMessage) : TransportEvent
}
