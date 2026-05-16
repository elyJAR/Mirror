package com.antigravity.mirror.stream.transport

import com.antigravity.mirror.stream.media.NalUnit
import com.antigravity.mirror.stream.transport.lan.protocol.ControlMessage
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow

/**
 * An active connection established by a [Transport].
 *
 * This interface abstracts away the protocol details (RTSP/RTP vs custom TCP). The session
 * provides a [videoSink] for the capture engine to push frames into, and an [events]
 * stream for control signals coming back from the peer.
 */
interface TransportSession {
    /**
     * Sink for encoded H.264 NAL units.
     */
    val videoSink: SendChannel<NalUnit>

    /**
     * Sink for encoded AAC audio data.
     */
    val audioSink: SendChannel<ByteArray>

    /**
     * The video codec negotiated with the peer (e.g. "video/avc", "video/hevc").
     */
    val negotiatedCodec: String

    /**
     * Side-channel events from the peer or transport layer.
     */
    val events: Flow<TransportEvent>

    /**
     * True when the session is waiting for a user-entered PIN.
     */
    val pairingRequired: Boolean

    /**
     * Observable performance metrics for the session.
     */
    val stats: Flow<com.antigravity.mirror.stream.api.SessionStats>

    /**
     * Submits a PIN for authentication if requested by the peer.
     */
    fun submitPin(pin: String)

    /**
     * Sends a custom control message to the peer.
     */
    fun sendControl(message: ControlMessage)

    /**
     * Requests the peer to toggle its secondary projection window.
     */
    fun toggleProjection()

    /**
     * Cleanly terminates the session.
     *
     * @param reason Informational reason to send to the peer (if the protocol supports it).
     */
    suspend fun close(reason: String)
}
