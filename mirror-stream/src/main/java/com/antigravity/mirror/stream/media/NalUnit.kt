package com.antigravity.mirror.stream.media

/**
 * A single H.264 Network Abstraction Layer (NAL) unit.
 *
 * [payload] is the raw NAL unit bytes (Annex B start codes MUST be stripped).
 * [presentationTimeUs] is the presentation timestamp in microseconds.
 */
data class NalUnit(
    val payload: ByteArray,
    val presentationTimeUs: Long
) {
    /** Returns true if this NAL unit is an IDR (Instantaneous Decoding Refresh) frame. */
    fun isKeyframe(): Boolean = payload.isNotEmpty() && (payload[0].toInt() and 0x1F) == 5

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NalUnit

        if (!payload.contentEquals(other.payload)) return false
        if (presentationTimeUs != other.presentationTimeUs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        return result
    }
}
