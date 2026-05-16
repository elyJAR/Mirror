package com.antigravity.mirror.stream.transport.lan

import android.content.Context
import com.antigravity.mirror.stream.api.MirrorConfig
import com.antigravity.mirror.stream.media.NalUnit
import com.antigravity.mirror.stream.transport.*
import com.antigravity.mirror.stream.transport.lan.discovery.LanDiscoveryManager
import com.antigravity.mirror.stream.transport.lan.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Custom TCP-based transport for local network mirroring.
 *
 * Requirements: design.md §3.1, §3.2
 */
class LanTransport(private val context: Context) : Transport {

    override val id: TransportId = TransportId.LAN

    private val discoveryManager = LanDiscoveryManager(context)

    override fun startDiscovery(): Flow<List<TransportTarget>> {
        return discoveryManager.discoverReceivers()
    }

    override suspend fun connect(target: TransportTarget, config: MirrorConfig): TransportSession {
        val client = ProtocolClient(target.host, target.port)
        
        // This suspends until the hello handshake is complete.
        client.connect()
        
        return LanTransportSession(client)
    }
}

/**
 * Active LAN session wrapping a [ProtocolClient].
 */
private class LanTransportSession(
    private val client: ProtocolClient
) : TransportSession {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val videoSink = Channel<NalUnit>(capacity = 30)
    override val audioSink = Channel<ByteArray>(capacity = 60)
    override val stats: Flow<com.antigravity.mirror.stream.api.SessionStats> = client.stats
    override val negotiatedCodec: String = client.negotiatedCodec
    override val pairingRequired: Boolean
        get() = client.isPairingRequired()

    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 16)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    init {
        if (client.isPairingRequired()) {
            _events.tryEmit(TransportEvent.PairingRequest)
        }

        // Bridge: videoSink (from MirrorClient) -> ProtocolClient.sendVideo
        sessionScope.launch {
            try {
                for (nal in videoSink) {
                    client.sendVideo(nal)
                }
            } catch (e: Exception) {}
        }

        // Bridge: audioSink -> ProtocolClient.sendAudio
        sessionScope.launch {
            try {
                for (audio in audioSink) {
                    client.sendAudio(audio)
                }
            } catch (e: Exception) {}
        }

        // Bridge: ProtocolClient events -> TransportEvent
        client.events
            .onEach { msg ->
                when (msg) {
                    is RequestKeyframeMessage -> {
                        _events.emit(TransportEvent.RequestKeyframe)
                    }
                    is TouchEventMessage -> {
                        _events.emit(TransportEvent.InjectTouch(msg.action, msg.x, msg.y))
                    }
                    is KeyEventMessage -> {
                        _events.emit(TransportEvent.InjectKey(msg.code))
                    }
                    is ByeMessage -> {
                        _events.emit(TransportEvent.PeerDisconnected(msg.reason))
                    }
                    is TransportEvent -> {
                        _events.emit(msg)
                    }
                    else -> {
                        // Other messages (stats, etc) are logged by client; ignored here for now
                    }
                }
            }
            .launchIn(sessionScope)
    }

    override fun sendControl(message: ControlMessage) {
        client.sendControl(message)
    }
    
    override fun toggleProjection() {
        client.sendExtendDisplay()
    }

    override fun submitPin(pin: String) {
        client.submitPin(pin)
    }

    override suspend fun close(reason: String) {
        withContext(Dispatchers.IO) {
            // Send bye if still connected (optional, but good practice)
            // Currently ProtocolClient doesn't expose sendBye, but close() cancels everything.
            client.close()
            sessionScope.cancel()
            videoSink.close()
        }
    }
}
