package com.antigravity.mirror.stream.transport.lan.protocol

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Test fixture that mocks the PC receiver side of the LAN protocol.
 *
 * Allows unit tests to verify the phone's protocol implementation without a real PC.
 */
class FakeReceiver(private val port: Int = 8765) {
    private val selector = SelectorManager(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Starts the receiver, accepts ONE connection, and runs the [block].
     */
    suspend fun start(block: suspend FakeReceiverSession.() -> Unit) = withContext(Dispatchers.IO) {
        val server = aSocket(selector).tcp().bind("127.0.0.1", port)
        serverSocket = server
        
        try {
            val socket = server.accept()
            val session = FakeReceiverSession(socket, json)
            socket.use { 
                session.block() 
            }
        } finally {
            server.close()
            selector.close()
        }
    }
}

class FakeReceiverSession(
    private val socket: Socket,
    private val json: Json
) {
    private val readChannel = socket.openReadChannel()
    private val writeChannel = socket.openWriteChannel(autoFlush = true)

    suspend fun expectHello(): HelloMessage {
        val frame = readChannel.readFrame()
        return json.decodeFromString<HelloMessage>(String(frame.payload))
    }

    suspend fun sendHelloAck(receiverName: String = "Test Receiver") {
        val ack = HelloAckMessage(
            receiver = receiverName,
            params = SessionParams(width = 1280, height = 720, fps = 30)
        )
        writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(ack).toByteArray())
    }

    suspend fun sendHelloReject(reason: String) {
        val reject = HelloRejectMessage(reason = reason)
        writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(reject).toByteArray())
    }

    suspend fun expectVideoFrame(): ByteArray {
        val frame = readChannel.readFrame()
        if (frame.tag != TAG_VIDEO) throw IllegalStateException("Expected video frame, got ${frame.tag}")
        return frame.payload
    }

    suspend fun sendRequestKeyframe() {
        val msg = RequestKeyframeMessage()
        writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(msg).toByteArray())
    }

    suspend fun sendBye(reason: String = "test-end") {
        val msg = ByeMessage(reason = reason)
        writeChannel.writeFrame(TAG_CONTROL, json.encodeToString(msg).toByteArray())
    }
}
