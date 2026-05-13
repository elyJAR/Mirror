package com.antigravity.mirror.stream.transport.miracast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [RtpSender] packetisation logic.
 *
 * Tests verify:
 * - RTP header fields (version, payload type, marker bit, sequence number, timestamp, SSRC)
 * - Single NAL unit packets for NAL units ≤ 1400 bytes (RFC 6184 §5.6)
 * - FU-A fragmentation for NAL units > 1400 bytes (RFC 6184 §5.8)
 * - Monotonically incrementing sequence numbers
 * - close() releases the UDP socket
 *
 * Requirements: 1.1, 10.4
 */
class RtpSenderTest {

    @get:Rule
    val timeout = Timeout(10, TimeUnit.SECONDS)

    private val localhost: InetAddress = InetAddress.getLoopbackAddress()
    private lateinit var receiverSocket: DatagramSocket
    private var receiverPort: Int = 0

    @Before
    fun setUp() {
        // Bind a receiver socket on a random port to capture sent packets
        receiverSocket = DatagramSocket(0, localhost)
        receiverPort = receiverSocket.localPort
        receiverSocket.soTimeout = 1000 // 1-second timeout for receives (reduced from 2s for faster tests)
    }

    @After
    fun tearDown() {
        if (!receiverSocket.isClosed) {
            receiverSocket.close()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createSender(): RtpSender = RtpSender(localhost, receiverPort)

    /** Receive a single UDP datagram from the receiver socket. */
    private fun receivePacket(maxSize: Int = 2048): ByteArray {
        val buf = ByteArray(maxSize)
        val packet = DatagramPacket(buf, buf.size)
        receiverSocket.receive(packet)
        return buf.copyOf(packet.length)
    }

    /** Read a 16-bit big-endian unsigned int from [buf] at [offset]. */
    private fun readUInt16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    /** Read a 32-bit big-endian int from [buf] at [offset]. */
    private fun readInt32(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)

    /** Returns true if the marker bit (bit 7 of byte 1) is set. */
    private fun isMarkerSet(packet: ByteArray): Boolean =
        (packet[1].toInt() and 0x80) != 0

    /** Returns the RTP payload type (bits 6-0 of byte 1). */
    private fun payloadType(packet: ByteArray): Int =
        packet[1].toInt() and 0x7F

    /** Returns the RTP version (bits 7-6 of byte 0). */
    private fun rtpVersion(packet: ByteArray): Int =
        (packet[0].toInt() ushr 6) and 0x03

    /** Returns the padding bit (bit 5 of byte 0). */
    private fun paddingBit(packet: ByteArray): Int =
        (packet[0].toInt() ushr 5) and 0x01

    /** Returns the extension bit (bit 4 of byte 0). */
    private fun extensionBit(packet: ByteArray): Int =
        (packet[0].toInt() ushr 4) and 0x01

    /** Returns the CC field (bits 3-0 of byte 0). */
    private fun ccField(packet: ByteArray): Int =
        packet[0].toInt() and 0x0F

    // -------------------------------------------------------------------------
    // RTP header field tests
    // -------------------------------------------------------------------------

    @Test
    fun `RTP version is 2`() {
        val sender = createSender()
        val nal = ByteArray(100) { it.toByte() }
        sender.sendNalUnit(nal, 0L)
        val packet = receivePacket()
        assertEquals("RTP version must be 2", 2, rtpVersion(packet))
        sender.close()
    }

    @Test
    fun `padding bit is 0`() {
        val sender = createSender()
        sender.sendNalUnit(ByteArray(100), 0L)
        val packet = receivePacket()
        assertEquals("Padding bit must be 0", 0, paddingBit(packet))
        sender.close()
    }

    @Test
    fun `extension bit is 0`() {
        val sender = createSender()
        sender.sendNalUnit(ByteArray(100), 0L)
        val packet = receivePacket()
        assertEquals("Extension bit must be 0", 0, extensionBit(packet))
        sender.close()
    }

    @Test
    fun `CC field is 0`() {
        val sender = createSender()
        sender.sendNalUnit(ByteArray(100), 0L)
        val packet = receivePacket()
        assertEquals("CC field must be 0", 0, ccField(packet))
        sender.close()
    }

    @Test
    fun `payload type is 96`() {
        val sender = createSender()
        sender.sendNalUnit(ByteArray(100), 0L)
        val packet = receivePacket()
        assertEquals("Payload type must be 96", 96, payloadType(packet))
        sender.close()
    }

    @Test
    fun `SSRC is consistent across packets from the same sender`() {
        val sender = createSender()
        sender.sendNalUnit(ByteArray(100), 0L)
        val packet1 = receivePacket()
        sender.sendNalUnit(ByteArray(100), 1000L)
        val packet2 = receivePacket()

        val ssrc1 = readInt32(packet1, 8)
        val ssrc2 = readInt32(packet2, 8)
        assertEquals("SSRC must be the same across packets from the same sender", ssrc1, ssrc2)
        sender.close()
    }

    // -------------------------------------------------------------------------
    // Single NAL unit packet tests (NAL ≤ 1400 bytes)
    // -------------------------------------------------------------------------

    @Test
    fun `small NAL unit produces exactly one packet`() {
        val sender = createSender()
        val nal = ByteArray(100) { (it + 1).toByte() }
        sender.sendNalUnit(nal, 0L)

        val packet = receivePacket()
        // Verify only one packet was sent (socket timeout would throw if another arrives)
        receiverSocket.soTimeout = 200
        var secondPacketReceived = false
        try {
            receivePacket()
            secondPacketReceived = true
        } catch (_: Exception) { /* expected timeout */ }

        assertFalse("Small NAL should produce exactly one packet", secondPacketReceived)
        sender.close()
    }

    @Test
    fun `single NAL unit packet has marker bit set`() {
        val sender = createSender()
        val nal = ByteArray(100) { it.toByte() }
        sender.sendNalUnit(nal, 0L)
        val packet = receivePacket()
        assertTrue("Marker bit must be set on single NAL unit packet", isMarkerSet(packet))
        sender.close()
    }

    @Test
    fun `single NAL unit packet payload matches original NAL bytes`() {
        val sender = createSender()
        val nal = ByteArray(50) { (it * 3).toByte() }
        sender.sendNalUnit(nal, 0L)
        val packet = receivePacket()

        // Payload starts at byte 12 (after RTP header)
        val payload = packet.copyOfRange(12, packet.size)
        assertTrue("Payload must match original NAL unit", nal.contentEquals(payload))
        sender.close()
    }

    @Test
    fun `single NAL unit packet total size is RTP header plus NAL size`() {
        val sender = createSender()
        val nalSize = 200
        val nal = ByteArray(nalSize)
        sender.sendNalUnit(nal, 0L)
        val packet = receivePacket()
        assertEquals("Packet size must be 12 (header) + NAL size", 12 + nalSize, packet.size)
        sender.close()
    }

    @Test
    fun `timestamp is correctly converted from microseconds to 90kHz clock`() {
        val sender = createSender()
        val timestampUs = 1_000_000L  // 1 second = 90000 ticks at 90 kHz
        sender.sendNalUnit(ByteArray(50), timestampUs)
        val packet = receivePacket()
        val rtpTimestamp = readInt32(packet, 4)
        assertEquals("Timestamp must be 90000 for 1 second", 90000, rtpTimestamp)
        sender.close()
    }

    @Test
    fun `timestamp conversion for 33ms frame interval`() {
        val sender = createSender()
        val timestampUs = 33_333L  // ~33ms ≈ 1 frame at 30fps
        val expectedTicks = (timestampUs * 90_000L / 1_000_000L).toInt()
        sender.sendNalUnit(ByteArray(50), timestampUs)
        val packet = receivePacket()
        val rtpTimestamp = readInt32(packet, 4)
        assertEquals("Timestamp conversion must match formula", expectedTicks, rtpTimestamp)
        sender.close()
    }

    @Test
    fun `NAL unit of exactly 1400 bytes uses single NAL unit packet`() {
        val sender = createSender()
        val nal = ByteArray(1400) { it.toByte() }
        sender.sendNalUnit(nal, 0L)
        val packet = receivePacket()

        // Single NAL unit packet: marker bit set, size = 12 + 1400
        assertTrue("Marker bit must be set for 1400-byte NAL", isMarkerSet(packet))
        assertEquals("Packet size must be 12 + 1400", 1412, packet.size)
        sender.close()
    }

    // -------------------------------------------------------------------------
    // FU-A fragmentation tests (NAL > 1400 bytes)
    // -------------------------------------------------------------------------

    @Test
    fun `large NAL unit produces multiple FU-A packets`() {
        val sender = createSender()
        val nal = ByteArray(2800) { it.toByte() }  // 2 full fragments + remainder
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { /* timeout = no more packets */ }

        assertTrue("Large NAL must produce more than one packet", packets.size > 1)
        sender.close()
    }

    @Test
    fun `FU-A first fragment has start bit set and end bit clear`() {
        val sender = createSender()
        val nal = ByteArray(3000) { it.toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        val firstPacket = packets.first()
        val fuHeader = firstPacket[13].toInt() and 0xFF
        assertTrue("Start bit (bit 7) must be set on first fragment", (fuHeader and 0x80) != 0)
        assertFalse("End bit (bit 6) must be clear on first fragment", (fuHeader and 0x40) != 0)
        sender.close()
    }

    @Test
    fun `FU-A last fragment has end bit set and marker bit set`() {
        val sender = createSender()
        val nal = ByteArray(3000) { it.toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        val lastPacket = packets.last()
        val fuHeader = lastPacket[13].toInt() and 0xFF
        assertTrue("End bit (bit 6) must be set on last fragment", (fuHeader and 0x40) != 0)
        assertTrue("Marker bit must be set on last fragment", isMarkerSet(lastPacket))
        sender.close()
    }

    @Test
    fun `FU-A middle fragments have neither start nor end bit set`() {
        val sender = createSender()
        // 3 * 1398 + 100 = 4294 bytes → 4 fragments (start, middle, middle, end)
        val nal = ByteArray(4294) { it.toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        assertTrue("Expected at least 4 fragments", packets.size >= 4)

        // Middle fragments: index 1 to size-2
        for (i in 1 until packets.size - 1) {
            val fuHeader = packets[i][13].toInt() and 0xFF
            assertFalse("Middle fragment $i must not have start bit", (fuHeader and 0x80) != 0)
            assertFalse("Middle fragment $i must not have end bit", (fuHeader and 0x40) != 0)
        }
        sender.close()
    }

    @Test
    fun `FU-A marker bit is clear on all but the last fragment`() {
        val sender = createSender()
        val nal = ByteArray(3000) { it.toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        for (i in 0 until packets.size - 1) {
            assertFalse("Marker bit must be clear on non-last fragment $i", isMarkerSet(packets[i]))
        }
        sender.close()
    }

    @Test
    fun `FU-A indicator preserves NRI from original NAL header`() {
        val sender = createSender()
        // NAL header with NRI = 0x60 (bits 5-6 set), type = 5 (IDR)
        val nalHeader = 0x65.toByte()  // NRI=3 (0x60), type=5
        val nal = ByteArray(3000)
        nal[0] = nalHeader
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        val fuIndicator = packets[0][12].toInt() and 0xFF
        // FU indicator = (nalHeader & 0x60) | 28
        val expectedFuIndicator = (nalHeader.toInt() and 0x60) or 28
        assertEquals("FU indicator must preserve NRI and set type=28", expectedFuIndicator, fuIndicator)
        sender.close()
    }

    @Test
    fun `FU-A header preserves original NAL type in bits 4-0`() {
        val sender = createSender()
        // NAL type = 5 (IDR slice)
        val nalHeader = 0x65.toByte()  // NRI=3, type=5
        val nal = ByteArray(3000)
        nal[0] = nalHeader
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        val fuHeader = packets[0][13].toInt() and 0xFF
        val nalType = fuHeader and 0x1F
        assertEquals("FU header must preserve original NAL type in bits 4-0", 5, nalType)
        sender.close()
    }

    @Test
    fun `FU-A fragments reassemble to original NAL payload`() {
        val sender = createSender()
        val nal = ByteArray(3000) { (it and 0xFF).toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        // Reassemble: skip RTP header (12) + FU indicator (1) + FU header (1) = 14 bytes
        val reassembled = mutableListOf<Byte>()
        for (packet in packets) {
            val fragmentData = packet.copyOfRange(14, packet.size)
            reassembled.addAll(fragmentData.toList())
        }

        // Original NAL payload = nal[1..] (skip NAL header byte which is encoded in FU indicator/header)
        val expectedPayload = nal.copyOfRange(1, nal.size)
        assertTrue(
            "Reassembled FU-A fragments must equal original NAL payload (minus header byte)",
            expectedPayload.contentEquals(reassembled.toByteArray())
        )
        sender.close()
    }

    @Test
    fun `NAL unit of 1401 bytes uses FU-A fragmentation`() {
        val sender = createSender()
        val nal = ByteArray(1401) { it.toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        assertTrue("1401-byte NAL must produce more than one FU-A packet", packets.size > 1)
        sender.close()
    }

    // -------------------------------------------------------------------------
    // Sequence number tests
    // -------------------------------------------------------------------------

    @Test
    fun `sequence number increments monotonically across packets`() {
        val sender = createSender()
        val seqNums = mutableListOf<Int>()

        repeat(5) { i ->
            sender.sendNalUnit(ByteArray(100), i * 33_333L)
            val packet = receivePacket()
            seqNums.add(readUInt16(packet, 2))
        }

        for (i in 1 until seqNums.size) {
            assertEquals(
                "Sequence number must increment by 1 each packet",
                (seqNums[i - 1] + 1) and 0xFFFF,
                seqNums[i]
            )
        }
        sender.close()
    }

    @Test
    fun `sequence number increments across FU-A fragments`() {
        val sender = createSender()
        // Send a large NAL that produces multiple fragments
        val nal = ByteArray(3000) { it.toByte() }
        sender.sendNalUnit(nal, 0L)

        val packets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) packets.add(receivePacket())
        } catch (_: Exception) { }

        assertTrue("Expected multiple FU-A packets", packets.size > 1)

        val seqNums = packets.map { readUInt16(it, 2) }
        for (i in 1 until seqNums.size) {
            assertEquals(
                "Sequence number must increment by 1 across FU-A fragments",
                (seqNums[i - 1] + 1) and 0xFFFF,
                seqNums[i]
            )
        }
        sender.close()
    }

    @Test
    fun `sequence number continues incrementing after FU-A fragmented NAL`() {
        val sender = createSender()

        // Send a large NAL (multiple fragments)
        val largeNal = ByteArray(3000) { it.toByte() }
        sender.sendNalUnit(largeNal, 0L)

        val fuPackets = mutableListOf<ByteArray>()
        receiverSocket.soTimeout = 500
        try {
            while (true) fuPackets.add(receivePacket())
        } catch (_: Exception) { }

        val lastFuSeq = readUInt16(fuPackets.last(), 2)

        // Send a small NAL after the large one
        sender.sendNalUnit(ByteArray(100), 33_333L)
        val nextPacket = receivePacket()
        val nextSeq = readUInt16(nextPacket, 2)

        assertEquals(
            "Sequence number must continue from last FU-A fragment",
            (lastFuSeq + 1) and 0xFFFF,
            nextSeq
        )
        sender.close()
    }

    // -------------------------------------------------------------------------
    // close() tests
    // -------------------------------------------------------------------------

    @Test
    fun `close() can be called without sending any packets`() {
        val sender = createSender()
        // Should not throw even if socket was never opened
        sender.close()
    }

    @Test
    fun `close() can be called multiple times without throwing`() {
        val sender = createSender()
        sender.sendNalUnit(ByteArray(100), 0L)
        receivePacket()
        sender.close()
        sender.close() // second close must not throw
    }
}
