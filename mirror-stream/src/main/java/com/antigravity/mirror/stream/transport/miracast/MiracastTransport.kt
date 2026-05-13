package com.antigravity.mirror.stream.transport.miracast

import android.content.Context
import android.util.Log
import com.antigravity.mirror.stream.api.MirrorConfig
import com.antigravity.mirror.stream.api.MirrorError
import com.antigravity.mirror.stream.media.NalUnit
import com.antigravity.mirror.stream.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.net.InetAddress

private const val TAG = "MirrorApp/MiracastTrans"

/**
 * Miracast implementation of [Transport].
 *
 * Wraps the existing [DiscoveryManager], [WfdSessionManager], and [RtpSender] to provide
 * a standard transport interface.
 */
class MiracastTransport(private val context: Context) : Transport {

    private val discoveryManager = DiscoveryManager(context)

    override val id: TransportId = TransportId.MIRACAST

    override fun startDiscovery(): Flow<List<TransportTarget>> =
        discoveryManager.startDiscovery().map { event ->
            when (event) {
                is DiscoveryEvent.PeersFound -> event.peers.map { MiracastTarget(it) }
                else -> emptyList()
            }
        }

    override suspend fun connect(target: TransportTarget, config: MirrorConfig): TransportSession {
        if (target !is MiracastTarget) {
            throw IllegalArgumentException("Target must be MiracastTarget")
        }

        Log.i(TAG, "Connecting to Miracast target: ${target.name} (${target.host})")

        // 1. Establish Wi-Fi Direct connection. This suspends until group is formed or fails.
        val connectionEvent = discoveryManager.connectToDevice(target.device)
            .first { it is ConnectionEvent.Connected || it is ConnectionEvent.Failed }

        if (connectionEvent is ConnectionEvent.Failed) {
            Log.e(TAG, "Wi-Fi Direct connection failed: ${connectionEvent.reason}")
            throw MirrorError.NetworkUnreachable(
                target.name,
                Exception("Wi-Fi Direct connection failed: ${connectionEvent.reason}")
            )
        }

        val goAddress = (connectionEvent as ConnectionEvent.Connected).groupOwnerAddress
        Log.i(TAG, "Wi-Fi Direct connected, GO address: ${goAddress.hostAddress}")

        // 2. Initialise RTSP and RTP components
        val rtspServer = RtspServer()
        val rtpSender = RtpSender(goAddress, rtpPort = 16384)
        val wfdSessionManager = WfdSessionManager(rtspServer)

        return MiracastTransportSession(rtspServer, rtpSender, wfdSessionManager)
    }
}

/**
 * Active Miracast session.
 *
 * Orchestrates the RTSP handshake (M1–M7) and routes [NalUnit]s from the capture engine
 * into the [RtpSender] for transmission over UDP.
 */
private class MiracastTransportSession(
    private val rtspServer: RtspServer,
    private val rtpSender: RtpSender,
    private val wfdSessionManager: WfdSessionManager
) : TransportSession {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val videoSink = Channel<NalUnit>(capacity = 30)
    override val audioSink = Channel<ByteArray>(capacity = 60)
    override val negotiatedCodec: String = "video/avc"
    override val stats: Flow<com.antigravity.mirror.stream.api.SessionStats> = emptyFlow()

    private val _events = MutableSharedFlow<TransportEvent>()
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    init {
        // Start RTSP handshake state machine
        sessionScope.launch {
            wfdSessionManager.startSession()
                .catch { e ->
                    Log.e(TAG, "RTSP session error: ${e.message}", e)
                    _events.emit(TransportEvent.Error(MirrorError.HandshakeFailed(e.message ?: "RTSP error")))
                }
                .collect { event ->
                    when (event) {
                        is SessionEvent.NegotiationComplete -> {
                            Log.i(TAG, "RTSP negotiation (M1-M7) complete")
                        }
                        is SessionEvent.PlayRequested -> {
                            Log.i(TAG, "Sink sent PLAY — ready to receive RTP stream")
                            // We don't need to do anything special here as the RTP sender
                            // loop is already waiting for frames from the videoSink.
                        }
                        is SessionEvent.StreamingError -> {
                            Log.e(TAG, "RTSP streaming error: ${event.cause.message}")
                            _events.emit(TransportEvent.Error(MirrorError.ProtocolViolation(event.cause.message ?: "Streaming error")))
                        }
                        is SessionEvent.SessionEnded -> {
                            Log.i(TAG, "RTSP session ended by peer")
                            _events.emit(TransportEvent.PeerDisconnected("RTSP TEARDOWN"))
                        }
                    }
                }
        }

        // Start RTP sender loop: drains videoSink and sends packets over UDP
        sessionScope.launch {
            try {
                for (nal in videoSink) {
                    rtpSender.sendNalUnit(nal.payload, nal.presentationTimeUs)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "RTP sender loop failed: ${e.message}", e)
                    _events.emit(TransportEvent.Error(MirrorError.ProtocolViolation("RTP send failed: ${e.message}")))
                }
            }
        }
    }

    override fun submitPin(pin: String) {
        // Miracast uses WPS/PBC, not application-layer PINs
    }

    override suspend fun close(reason: String) {
        Log.i(TAG, "Closing Miracast session: $reason")
        withContext(Dispatchers.IO) {
            // Cancel the scope first to stop the RTP loop and event processing
            sessionScope.cancel()
            
            // Cleanly stop the protocol components
            runCatching { wfdSessionManager.stopSession() }
            runCatching { rtpSender.close() }
            runCatching { rtspServer.stop() }
            
            // Close the sinks to notify any pending senders
            videoSink.close()
            audioSink.close()
        }
    }
}
