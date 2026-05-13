package com.antigravity.mirror.model

import android.net.wifi.p2p.WifiP2pDevice
import java.net.InetAddress

/**
 * Holds the runtime state of an active Miracast streaming session.
 *
 * Created once the RTSP M1–M7 handshake completes and streaming begins.
 */
data class MirrorSession(
    /** The Wi-Fi Direct peer device representing the Miracast sink (Windows Connect app). */
    val sinkDevice: WifiP2pDevice,
    /** IP address of the Miracast sink, used for RTP/RTSP communication. */
    val sinkAddress: InetAddress,
    /** UDP port on the sink where RTP video packets are sent. */
    val rtpPort: Int,
    /** The WFD capabilities agreed upon during the RTSP M3/M4 negotiation. */
    val negotiatedCapabilities: WfdCapabilities,
    /** Wall-clock time when the session started, in milliseconds since epoch. */
    val startedAt: Long
)
