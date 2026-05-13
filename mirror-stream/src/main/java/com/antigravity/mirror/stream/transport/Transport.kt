package com.antigravity.mirror.stream.transport

import com.antigravity.mirror.stream.api.MirrorConfig
import kotlinx.coroutines.flow.Flow

/**
 * Common interface for screen mirroring transports (LAN and Miracast).
 *
 * Each transport owns its own discovery mechanism (e.g. mDNS for LAN, Wi-Fi P2P for Miracast)
 * and its own wire protocol (e.g. custom TCP for LAN, RTSP/RTP for Miracast).
 */
interface Transport {
    /**
     * Begin discovering peers on the network.
     *
     * @return A flow that emits snapshots of all currently reachable [TransportTarget]s.
     */
    fun startDiscovery(): Flow<List<TransportTarget>>

    /**
     * Establish a session to the given [target].
     *
     * This method suspends until the handshake is complete and the session is ready
     * to receive video frames.
     *
     * @param target The peer to connect to.
     * @param config Session parameters (resolution, bitrate, etc.).
     * @return An active [TransportSession].
     * @throws com.antigravity.mirror.stream.api.MirrorError if connection fails or is rejected.
     */
    suspend fun connect(target: TransportTarget, config: MirrorConfig): TransportSession

    /**
     * Unique identifier for this transport.
     */
    val id: TransportId
}
