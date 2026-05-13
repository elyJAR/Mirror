package com.antigravity.mirror.stream.transport.lan.protocol

import com.antigravity.mirror.stream.media.NalUnit
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.ServerSocket

class ProtocolIntegrationTest {

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @Test
    fun `full session lifecycle - connect, send video, receive keyframe request, disconnect`() = runTest {
        val port = findFreePort()
        val receiver = FakeReceiver(port)
        val client = ProtocolClient("127.0.0.1", port)

        val receiverJob = async(Dispatchers.IO) {
            receiver.start {
                val hello = expectHello()
                println("Receiver: Got hello from ${hello.device}")
                
                sendHelloAck("Mock Receiver PC")
                
                // Expect a video frame
                val video = expectVideoFrame()
                video shouldBe byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
                
                // Signal a keyframe request back to the phone
                sendRequestKeyframe()
                
                // End session
                sendBye("test-complete")
            }
        }

        val clientJob = async(Dispatchers.IO) {
            client.connect()
            
            // Subscribe to all expected events BEFORE triggering them to avoid missing them
            val keyframeEventDeferred = async { client.events.first { it is RequestKeyframeMessage } }
            val byeEventDeferred = async { client.events.first { it is ByeMessage } }
            
            // Send synthetic NAL unit
            client.sendVideo(NalUnit(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), 12345L))
            
            val event = keyframeEventDeferred.await()
            event shouldBe RequestKeyframeMessage()
            
            // Wait for disconnect
            byeEventDeferred.await()
        }

        try {
            withTimeout(5000) {
                clientJob.await()
                receiverJob.await()
            }
        } finally {
            clientJob.cancel()
            receiverJob.cancel()
            client.close()
        }
    }
}
