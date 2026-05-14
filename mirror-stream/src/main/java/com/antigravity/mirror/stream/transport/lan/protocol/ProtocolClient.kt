package com.antigravity.mirror.stream.transport.lan.protocol

import android.os.Build
import android.util.Log
import com.antigravity.mirror.stream.api.MirrorError
import com.antigravity.mirror.stream.api.SessionStats
import com.antigravity.mirror.stream.media.NalUnit
import com.antigravity.mirror.stream.transport.TransportEvent
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.ClosedChannelException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

import java.util.LinkedList
import java.util.Collections

private const val TAG = "MirrorApp/ProtocolClient"
private const val MAX_QUEUE_SIZE = 30

/**
 * Implementation of the LAN wire protocol (Android side).
 *
 * Coordinates the TCP socket, JSON handshake, framing, pings, and video transmission.
 *
 * Requirements: design.md §3.1, §3.2, §6.1
 */
class ProtocolClient(
    private val host: String,
    private val port: Int,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selector = SelectorManager(Dispatchers.IO)
    
    private val videoQueue = LinkedList<NalUnit>()
    private val audioQueue = LinkedList<ByteArray>()
    private val queueSignal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
    private val _events = MutableSharedFlow<Any>(replay = 1)
    val events: SharedFlow<Any> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private var framesSent = 0
    private var bytesSent = 0
    private var droppedTotal = 0

    init {
        // Stats ticker
        scope.launch {
            while (isActive) {
                delay(1000)
                val depth = synchronized(videoQueue) { videoQueue.size }
                _stats.value = SessionStats(
                    fps = framesSent,
                    bitrateKbps = (bytesSent * 8) / 1000,
                    queueDepth = depth,
                    droppedFrames = droppedTotal
                )
                framesSent = 0
                bytesSent = 0
            }
        }
    }
    
    private var socket: Socket? = null
    private var controlWriteChannel: ByteWriteChannel? = null
    var negotiatedCodec: String = "video/avc"
        private set

    @Volatile
    private var awaitingPin: Boolean = false

    /** Submit the PIN entered by the user. */
    fun submitPin(pin: String) {
        if (!awaitingPin) {
            Log.w(TAG, "submitPin called but no PIN is currently required")
            return
        }
        val channel = controlWriteChannel
        if (channel == null) {
            Log.w(TAG, "submitPin called before control channel is ready")
            return
        }

        scope.launch {
            runCatching {
                channel.writeFrame(TAG_CONTROL, json.encodeToString(VerifyPinMessage(pin = pin)).toByteArray())
            }.onFailure {
                Log.e(TAG, "Failed to send verify-pin: ${it.message}")
            }
        }
    }

    fun isPairingRequired(): Boolean = awaitingPin
    
    private var lastPongTime = System.currentTimeMillis()

    /**
     * Connects to the receiver and performs the hello handshake.
     *
     * Suspends until the session is fully established ([HelloAckMessage] received).
     *
     * @throws MirrorError if the connection fails or is rejected.
     */
    suspend fun connect() = withContext(scope.coroutineContext) {
        Log.i(TAG, "Connecting to $host:$port...")
        val socket = try {
            aSocket(selector).tcp().connect(host, port)
        } catch (e: Exception) {
            throw MirrorError.NetworkUnreachable(host, e)
        }
        this@ProtocolClient.socket = socket
        
        val readChannel = socket.openReadChannel()
        val writeChannel = socket.openWriteChannel(autoFlush = true)
        controlWriteChannel = writeChannel
        
        // 1. Handshake: Hello -> HelloAck
        try {
            val codecs = mutableListOf("video/avc")
            if (com.antigravity.mirror.stream.media.VideoEncoder.isCodecSupported("video/hevc")) {
                codecs.add("video/hevc")
            }
            
            val hello = HelloMessage(
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                codecs = codecs
            )
            writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(hello).toByteArray())
            
            val ackFrame = readChannel.readFrame()
            if (ackFrame.tag != TAG_CONTROL) {
                throw MirrorError.HandshakeFailed("Expected control frame, got ${ackFrame.tag}")
            }

            val ackRaw = String(ackFrame.payload)
            Log.i(TAG, "Received handshake control payload: $ackRaw")
            
            val msg = try {
                json.decodeFromString<ControlMessage>(ackRaw)
            } catch (decodeError: Exception) {
                Log.e(TAG, "Failed to decode handshake control payload", decodeError)
                throw MirrorError.HandshakeFailed("Invalid handshake payload: $ackRaw")
            }
            when (msg) {
                is HelloAckMessage -> {
                    negotiatedCodec = msg.params.codec
                    Log.i(TAG, "Handshake accepted by ${msg.receiver}. Negotiated: $negotiatedCodec")
                    
                    if (msg.pinRequired) {
                        awaitingPin = true
                        Log.i(TAG, "PIN required by peer")
                        _events.emit(TransportEvent.PairingRequest)
                    }
                }
                is HelloRejectMessage -> {
                    throw MirrorError.HandshakeFailed("Rejected by receiver: ${msg.reason}")
                }
                else -> {
                    throw MirrorError.HandshakeFailed("Unexpected initial message: ${msg.type}")
                }
            }
        } catch (e: Exception) {
            socket.close()
            if (e is MirrorError) throw e
            throw MirrorError.HandshakeFailed(e.message ?: "Unknown handshake error")
        }
        
        lastPongTime = System.currentTimeMillis()

        // 2. Start protocol loops
        scope.launch { readLoop(readChannel) }
        scope.launch { writeLoop(writeChannel) }
        scope.launch { pingLoop(writeChannel) }
        scope.launch { watchdogLoop() }
        
        Log.i(TAG, "Protocol loops launched, connect() returning")
    }

    private suspend fun readLoop(readChannel: ByteReadChannel) {
        try {
            while (true) {
                val frame = readChannel.readFrame()
                if (frame.tag == TAG_CONTROL) {
                    val msg = json.decodeFromString<ControlMessage>(String(frame.payload))
                    handleControlMessage(msg)
                } else if (frame.tag == TAG_VIDEO) {
                    // Receiver shouldn't send video, but we ignore it if it does
                    Log.w(TAG, "Received unexpected video frame from receiver")
                }
            }
        } catch (e: io.ktor.utils.io.ClosedChannelException) {
            if (scope.isActive) {
                Log.i(TAG, "Read loop: channel closed (expected during shutdown)")
            }
        } catch (e: Exception) {
            if (scope.isActive) {
                Log.e(TAG, "Read loop failed: ${e.message}", e)
                runCatching { _events.emit(ByeMessage(reason = "connection-loss")) }
            }
        }
    }

    private suspend fun writeLoop(writeChannel: ByteWriteChannel) {
        try {
            while (true) {
                // Wait for a signal that data is available
                queueSignal.receive()
                
                var nal: NalUnit?
                while (true) {
                    synchronized(videoQueue) {
                        nal = videoQueue.pollFirst()
                    }
                    if (nal == null) break
                    
                    writeChannel.writeFrame(TAG_VIDEO, nal!!.payload)
                    framesSent++
                    bytesSent += nal!!.payload.size
                }

                // Drain audio queue
                while (true) {
                    val audio: ByteArray?
                    synchronized(audioQueue) {
                        audio = audioQueue.pollFirst()
                    }
                    if (audio == null) break
                    
                    writeChannel.writeFrame(TAG_AUDIO, audio)
                    bytesSent += audio.size
                }
            }
        } catch (e: io.ktor.utils.io.ClosedChannelException) {
            if (scope.isActive) {
                Log.i(TAG, "Write loop: channel closed (expected during shutdown)")
            }
        } catch (e: Exception) {
            if (scope.isActive) {
                Log.e(TAG, "Write loop failed: ${e.message}", e)
            }
        }
    }

    private suspend fun pingLoop(writeChannel: ByteWriteChannel) {
        try {
            while (true) {
                delay(5000)
                val ping = PingMessage(timestamp = System.currentTimeMillis())
                writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(ping).toByteArray())
            }
        } catch (e: Exception) {
            // Loop will exit on scope cancellation
        }
    }

    private suspend fun watchdogLoop() {
        try {
            while (true) {
                delay(1000)
                if (System.currentTimeMillis() - lastPongTime > 15000) {
                    Log.w(TAG, "Watchdog timeout - no pong for 15s")
                    _events.emit(ByeMessage(reason = "timeout"))
                    break
                }
            }
        } catch (e: Exception) {
            // Loop will exit
        }
    }

    private fun handleControlMessage(msg: ControlMessage) {
        when (msg) {
            is AuthResultMessage -> {
                if (msg.success) {
                    awaitingPin = false
                    scope.launch { _events.emit(TransportEvent.PairingVerified) }
                } else {
                    val error = msg.message ?: "Invalid PIN"
                    scope.launch { _events.emit(TransportEvent.Error(MirrorError.HandshakeFailed("Authentication failed: $error"))) }
                }
            }
            is PongMessage -> {
                lastPongTime = System.currentTimeMillis()
            }
            is ByeMessage -> {
                Log.i(TAG, "Receiver sent bye: ${msg.reason}")
                scope.launch { _events.emit(msg) }
            }
            else -> {
                scope.launch { _events.emit(msg) }
            }
        }
    }

    /**
     * Enqueues a NAL unit for transmission. Drops oldest non-keyframe if full.
     */
    fun sendVideo(nal: NalUnit) {
        synchronized(videoQueue) {
            if (videoQueue.size >= MAX_QUEUE_SIZE) {
                // Backpressure handling: drop oldest non-keyframe to maintain latency
                val it = videoQueue.listIterator()
                var dropped = false
                while (it.hasNext()) {
                    val current = it.next()
                    if (!current.isKeyframe()) {
                        it.remove()
                        dropped = true
                        droppedTotal++
                        break
                    }
                }
                
                if (!dropped) {
                    // If everything is a keyframe (unlikely), just drop the oldest
                    videoQueue.pollFirst()
                    droppedTotal++
                }
                
                // Signal encoder to generate a new sync frame ASAP to recover from the drop
                scope.launch { _events.emit(RequestKeyframeMessage()) }
                Log.w(TAG, "Backpressure: Queue full ($MAX_QUEUE_SIZE), dropped frame, requested keyframe.")
            }
            videoQueue.addLast(nal)
            queueSignal.trySend(Unit)
        }
    }

    /**
     * Enqueues audio data for transmission.
     */
    fun sendAudio(data: ByteArray) {
        synchronized(audioQueue) {
            if (audioQueue.size >= 100) { // Limit audio queue to ~2s of data
                audioQueue.pollFirst()
            }
            audioQueue.addLast(data)
            queueSignal.trySend(Unit)
        }
    }

    /**
     * Closes the session and releases all networking resources.
     * Safe to call multiple times.
     */
    fun close() {
        Log.i(TAG, "Closing ProtocolClient...")
        
        // Step 1: Close the socket first so readLoop/writeLoop get ClosedChannelException and exit cleanly
        runCatching {
            socket?.close()
            Log.i(TAG, "Socket closed")
        }.onFailure { Log.w(TAG, "Socket close failed: ${it.message}") }
        
        // Step 2: Close the control channel to signal loops to stop
        runCatching {
            controlWriteChannel?.close()
            Log.i(TAG, "Control channel closed")
        }.onFailure { Log.w(TAG, "Control channel close failed: ${it.message}") }
        
        // Step 3: Cancel the scope and wait for coroutines to exit
        runCatching {
            scope.cancel()
            Log.i(TAG, "Scope cancelled")
        }.onFailure { Log.w(TAG, "Scope cancel failed: ${it.message}") }
        
        // Step 4: Close the selector (this is the most likely to throw)
        runCatching {
            selector.close()
            Log.i(TAG, "Selector closed")
        }.onFailure { Log.w(TAG, "Selector close failed: ${it.message}") }
        
        awaitingPin = false
        controlWriteChannel = null
        Log.i(TAG, "ProtocolClient fully closed")
    }
}
