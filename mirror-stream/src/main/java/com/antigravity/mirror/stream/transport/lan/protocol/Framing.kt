package com.antigravity.mirror.stream.transport.lan.protocol

import io.ktor.utils.io.*

/**
 * Common framing logic for the LAN protocol.
 *
 * Wire format:
 * [Tag: 1 byte] [Length: 4 bytes, Big Endian] [Payload: N bytes]
 *
 * Requirements: design.md §3.1
 */
data class Frame(val tag: Byte, val payload: ByteArray)

/** Control messages (JSON). */
const val TAG_CONTROL: Byte = 0x01

/** Video NAL units (raw H.264). */
const val TAG_VIDEO: Byte = 0x02

/** Audio samples (AAC). */
const val TAG_AUDIO: Byte = 0x03

/** Hard limit to prevent OOM from malformed/hostile frames. */
const val MAX_PAYLOAD_SIZE = 8 * 1024 * 1024 // 8 MiB

/**
 * Writes a length-prefixed frame to the channel.
 */
suspend fun ByteWriteChannel.writeFrame(tag: Byte, payload: ByteArray) {
    writeByte(tag)
    writeInt(payload.size) // Big-endian by default in Ktor
    writeFully(payload)
    flush()
}

/**
 * Reads a length-prefixed frame from the channel.
 *
 * @throws IllegalStateException if the length exceeds [MAX_PAYLOAD_SIZE].
 */
suspend fun ByteReadChannel.readFrame(): Frame {
    val tag = readByte()
    val length = readInt()
    
    if (length < 0 || length > MAX_PAYLOAD_SIZE) {
        throw IllegalStateException("Invalid frame length: $length (max $MAX_PAYLOAD_SIZE)")
    }
    
    val payload = ByteArray(length)
    readFully(payload)
    return Frame(tag, payload)
}
