package com.antigravity.mirror.stream.transport.miracast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Packetises H.264 NAL units into RTP packets (RFC 6184) and sends them over UDP
 * to the Miracast sink's RTP port negotiated during the RTSP M5/M6 exchange.
 *
 * Supports:
 * - Single NAL unit packets for NAL units ≤ 1400 bytes (RFC 6184 §5.6).
 * - FU-A fragmentation for NAL units > 1400 bytes (RFC 6184 §5.8).
 *
 * RTP header format (RFC 3550):
 *   Byte 0: V=2, P=0, X=0, CC=0  → 0x80
 *   Byte 1: M (marker) | PT=96
 *   Bytes 2-3: Sequence number (big-endian, monotonically incrementing)
 *   Bytes 4-7: Timestamp (big-endian, 90 kHz clock)
 *   Bytes 8-11: SSRC (big-endian, random fixed value per session)
 */
class RtpSender(
    private val sinkAddress: InetAddress,
    private val rtpPort: Int
) {
    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_VERSION_FLAGS: Byte = 0x80.toByte()  // V=2, P=0, X=0, CC=0
        private const val RTP_PAYLOAD_TYPE = 96
        private const val MAX_NAL_SIZE = 1400
        private const val FU_HEADER_SIZE = 2  // FU indicator + FU header
        private const val MAX_FU_PAYLOAD = MAX_NAL_SIZE - FU_HEADER_SIZE  // 1398 bytes of NAL data per fragment

        // FU-A NAL type = 28
        private const val FU_A_TYPE = 28

        // FU header bit masks
        private const val FU_START_BIT = 0x80
        private const val FU_END_BIT = 0x40
    }

    /** Random SSRC generated once per session. */
    private val ssrc: Int = Random.nextInt()

    /** Monotonically incrementing sequence number (wraps at 65535). */
    private var sequenceNumber: Int = 0

    /** UDP socket opened lazily on first sendNalUnit() call. Volatile for cross-thread visibility. */
    @Volatile
    private var socket: DatagramSocket? = null

    /**
     * Packetises [nalUnit] and sends it to the sink.
     *
     * Opens the UDP socket on the first call. Subsequent calls reuse the same socket.
     *
     * @param nalUnit Raw H.264 NAL unit bytes (without Annex B start code).
     * @param timestampUs Presentation timestamp in microseconds.
     */
    fun sendNalUnit(nalUnit: ByteArray, timestampUs: Long) {
        if (socket == null) {
            socket = DatagramSocket()
        }

        // Convert microseconds to 90 kHz RTP clock
        val rtpTimestamp = (timestampUs * 90_000L / 1_000_000L).toInt()

        if (nalUnit.size <= MAX_NAL_SIZE) {
            sendSingleNalUnit(nalUnit, rtpTimestamp)
        } else {
            sendFuA(nalUnit, rtpTimestamp)
        }
    }

    /**
     * Sends a single NAL unit packet (RFC 6184 §5.6).
     * Used when the NAL unit fits within [MAX_NAL_SIZE] bytes.
     *
     * Packet layout: RTP header (12 bytes) + NAL unit bytes
     */
    private fun sendSingleNalUnit(nalUnit: ByteArray, rtpTimestamp: Int) {
        val packet = ByteArray(RTP_HEADER_SIZE + nalUnit.size)
        writeRtpHeader(
            buf = packet,
            marker = true,
            timestamp = rtpTimestamp,
            seqNum = nextSequenceNumber()
        )
        System.arraycopy(nalUnit, 0, packet, RTP_HEADER_SIZE, nalUnit.size)
        sendUdp(packet)
    }

    /**
     * Fragments a large NAL unit using FU-A packetisation (RFC 6184 §5.8).
     * Used when the NAL unit exceeds [MAX_NAL_SIZE] bytes.
     *
     * FU indicator byte: (nal_unit[0] & 0x60) | 28
     *   - NRI field (bits 5-6) preserved from original NAL header
     *   - Type field (bits 0-4) = 28 (FU-A)
     *
     * FU header byte:
     *   - Bit 7 (S): set on first fragment
     *   - Bit 6 (E): set on last fragment
     *   - Bit 5 (R): reserved, always 0
     *   - Bits 4-0: original NAL unit type
     *
     * Packet layout: RTP header (12 bytes) + FU indicator (1 byte) + FU header (1 byte) + fragment data
     */
    private fun sendFuA(nalUnit: ByteArray, rtpTimestamp: Int) {
        val nalHeader = nalUnit[0].toInt() and 0xFF
        val fuIndicator = ((nalHeader and 0x60) or FU_A_TYPE).toByte()
        val nalType = (nalHeader and 0x1F).toByte()

        // NAL payload starts at byte 1 (skip the NAL header byte)
        var offset = 1
        var isFirst = true

        while (offset < nalUnit.size) {
            val remaining = nalUnit.size - offset
            val fragmentSize = minOf(remaining, MAX_FU_PAYLOAD)
            val isLast = (offset + fragmentSize) >= nalUnit.size

            // Build FU header byte
            var fuHeader = nalType.toInt() and 0x1F
            if (isFirst) fuHeader = fuHeader or FU_START_BIT
            if (isLast) fuHeader = fuHeader or FU_END_BIT

            val packetSize = RTP_HEADER_SIZE + FU_HEADER_SIZE + fragmentSize
            val packet = ByteArray(packetSize)

            writeRtpHeader(
                buf = packet,
                marker = isLast,
                timestamp = rtpTimestamp,
                seqNum = nextSequenceNumber()
            )

            // FU indicator and FU header
            packet[RTP_HEADER_SIZE] = fuIndicator
            packet[RTP_HEADER_SIZE + 1] = fuHeader.toByte()

            // Fragment data
            System.arraycopy(nalUnit, offset, packet, RTP_HEADER_SIZE + FU_HEADER_SIZE, fragmentSize)

            sendUdp(packet)

            offset += fragmentSize
            isFirst = false
        }
    }

    /**
     * Writes the 12-byte RTP header into [buf] starting at offset 0.
     *
     * @param buf    Buffer to write into (must be at least 12 bytes).
     * @param marker Whether to set the marker bit (last packet of a NAL unit).
     * @param timestamp RTP timestamp in 90 kHz clock units.
     * @param seqNum 16-bit sequence number.
     */
    private fun writeRtpHeader(buf: ByteArray, marker: Boolean, timestamp: Int, seqNum: Int) {
        // Byte 0: V=2, P=0, X=0, CC=0
        buf[0] = RTP_VERSION_FLAGS

        // Byte 1: Marker bit | Payload type 96
        val markerBit = if (marker) 0x80 else 0x00
        buf[1] = (markerBit or RTP_PAYLOAD_TYPE).toByte()

        // Bytes 2-3: Sequence number (big-endian)
        buf[2] = ((seqNum shr 8) and 0xFF).toByte()
        buf[3] = (seqNum and 0xFF).toByte()

        // Bytes 4-7: Timestamp (big-endian)
        buf[4] = ((timestamp shr 24) and 0xFF).toByte()
        buf[5] = ((timestamp shr 16) and 0xFF).toByte()
        buf[6] = ((timestamp shr 8) and 0xFF).toByte()
        buf[7] = (timestamp and 0xFF).toByte()

        // Bytes 8-11: SSRC (big-endian)
        buf[8] = ((ssrc shr 24) and 0xFF).toByte()
        buf[9] = ((ssrc shr 16) and 0xFF).toByte()
        buf[10] = ((ssrc shr 8) and 0xFF).toByte()
        buf[11] = (ssrc and 0xFF).toByte()
    }

    /**
     * Returns the next sequence number and increments the counter.
     * Wraps around at 65535 (16-bit unsigned).
     */
    private fun nextSequenceNumber(): Int {
        val current = sequenceNumber
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        return current
    }

    /**
     * Sends [data] as a UDP datagram to [sinkAddress]:[rtpPort].
     */
    private fun sendUdp(data: ByteArray) {
        val datagram = DatagramPacket(data, data.size, sinkAddress, rtpPort)
        socket?.send(datagram)
    }

    /**
     * Closes the UDP socket and releases all resources.
     */
    fun close() {
        socket?.close()
        socket = null
    }
}
