package com.antigravity.mirror.stream.api

/**
 * Observable lifecycle of a [MirrorClient].
 *
 * Consumers observe [MirrorClient.state] and react. The state machine is single-threaded;
 * transitions go through [MirrorClient]'s internal scope.
 *
 * Typical happy-path sequence (LAN transport):
 *   Idle -> Discovering -> ReceiversFound -> Connecting -> AwaitingPairing
 *        -> AwaitingProjection -> Streaming -> Idle (after disconnect)
 *
 * Any state can transition to [Error]. Recoverable errors return to [Idle] after a retry,
 * unrecoverable errors stay until [MirrorClient.release] is called or a new session starts.
 */
sealed interface MirrorState {

    /** No active session. Default state on construction and after a clean disconnect. */
    data object Idle : MirrorState

    /** Looking for receivers. UI typically shows a spinner. */
    data object Discovering : MirrorState

    /** Discovery returned at least one [Receiver]. UI shows a list to pick from. */
    data class ReceiversFound(val receivers: List<Receiver>) : MirrorState

    /** TCP / Wi-Fi Direct connection in progress; handshake not yet complete. */
    data object Connecting : MirrorState

    /** The peer requires a PIN. UI should show a keypad. */
    data class AwaitingPairing(val errorMsg: String? = null) : MirrorState

    /** Handshake done; waiting for the user to grant MediaProjection consent. */
    data object AwaitingProjection : MirrorState

    /** Frames are flowing to the receiver. Steady state. */
    data object Streaming : MirrorState

    /** Lost the connection mid-session and is attempting to recover (1/2/4 s backoff). */
    data object Reconnecting : MirrorState

    /**
     * Terminal-or-recoverable error.
     *
     * @param cause       the typed error.
     * @param recoverable whether the user retrying is likely to succeed without restarting.
     */
    data class Error(val cause: MirrorError, val recoverable: Boolean) : MirrorState
}
