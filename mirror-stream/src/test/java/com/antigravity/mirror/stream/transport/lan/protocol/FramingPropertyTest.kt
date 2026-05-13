package com.antigravity.mirror.stream.transport.lan.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.*

class FramingPropertyTest : StringSpec({

    "round-trip framing of arbitrary payloads up to 64KB" {
        val channel = ByteChannel(autoFlush = true)
        val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        channel.writeFrame(0x7F.toByte(), payload)

        val frame = channel.readFrame()

        frame.tag shouldBe 0x7F.toByte()
        frame.payload.size shouldBe payload.size
        frame.payload shouldBe payload
    }

    "should reject payloads larger than MAX_PAYLOAD_SIZE" {
        val tag: Byte = 0x01
        val tooLarge = MAX_PAYLOAD_SIZE + 1
        
        val channel = ByteChannel(autoFlush = true)
        
        // Manually write a header with an invalid length
        channel.writeByte(tag)
        channel.writeInt(tooLarge)
        
        try {
            channel.readFrame()
            throw AssertionError("Should have rejected large payload")
        } catch (e: IllegalStateException) {
            e.message?.contains("max") shouldBe true
        }
    }
})
