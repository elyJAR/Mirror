package com.antigravity.mirror.stream.transport

import com.antigravity.mirror.stream.media.NalUnit
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
     *
     * The capture/encoder layer pushes [NalUnit]s here. The transport implementation is
     * responsible for framing/packetising and sending them over the wire.
     */
    val videoSink: SendChannel<NalUnit>

    /**
     * Side-channel events from the peer or transport layer.
     */
    val events: Flow<TransportEvent>

    /**
     * Observable performance metrics for the session.
     */
    val stats: Flow<com.antigravity.mirror.stream.api.SessionStats>

    /**
     * Cleanly terminates the session.
     *
     * @param reason Informational reason to send to the peer (if the protocol supports it).
     */
    suspend fun close(reason: String)
}
