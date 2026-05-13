package com.antigravity.mirror.stream.transport.lan.protocol

import android.os.Build
import android.util.Log
import com.antigravity.mirror.stream.api.MirrorError
import com.antigravity.mirror.stream.api.SessionStats
import com.antigravity.mirror.stream.media.NalUnit
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
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
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selector = SelectorManager(Dispatchers.IO)
    
    private val videoQueue = LinkedList<NalUnit>()
    private val queueSignal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
    private val _events = MutableSharedFlow<ControlMessage>()
    val events: SharedFlow<ControlMessage> = _events.asSharedFlow()

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
        
        // 1. Handshake: Hello -> HelloAck
        try {
            val hello = HelloMessage(device = "${Build.MANUFACTURER} ${Build.MODEL}")
            writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(hello).toByteArray())
            
            val ackFrame = readChannel.readFrame()
            if (ackFrame.tag != TAG_CONTROL) {
                throw MirrorError.HandshakeFailed("Expected control frame, got ${ackFrame.tag}")
            }
            
            val msg = json.decodeFromString<ControlMessage>(String(ackFrame.payload))
            when (msg) {
                is HelloAckMessage -> {
                    Log.i(TAG, "Handshake accepted by ${msg.receiver}. Params: ${msg.params}")
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
        launch { readLoop(readChannel) }
        launch { writeLoop(writeChannel) }
        launch { pingLoop(writeChannel) }
        launch { watchdogLoop() }
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
        } catch (e: Exception) {
            if (scope.isActive) {
                Log.e(TAG, "Read loop failed: ${e.message}")
                _events.emit(ByeMessage(reason = "connection-loss"))
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
            }
        } catch (e: Exception) {
            if (scope.isActive) {
                Log.e(TAG, "Write loop failed: ${e.message}")
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
     * Closes the session and releases all networking resources.
     */
    fun close() {
        Log.i(TAG, "Closing ProtocolClient...")
        scope.cancel()
        runCatching { socket?.close() }
        runCatching { selector.close() }
    }
}
