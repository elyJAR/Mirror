package com.antigravity.mirror.stream.transport.lan.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.ktor.utils.io.*

class FramingPropertyTest : StringSpec({

    "round-trip framing of arbitrary payloads up to 64KB" {
        // We use a smaller range and fewer iterations for property tests to keep them fast (avoid CI hangs)
        checkAll(50, Arb.byte(), Arb.byteArray(length = Arb.int(0, 1024 * 64), content = Arb.byte())) { tag, payload ->
            val channel = ByteChannel(autoFlush = true)
            
            // Write
            channel.writeFrame(tag, payload)
            
            // Read
            val frame = channel.readFrame()
            
            frame.tag shouldBe tag
            frame.payload.size shouldBe payload.size
            frame.payload shouldBe payload
        }
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
