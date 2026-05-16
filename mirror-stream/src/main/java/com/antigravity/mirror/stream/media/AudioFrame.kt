package com.antigravity.mirror.stream.media

/**
 * A chunk of encoded audio data with its presentation timestamp.
 * 
 * Used to maintain sync with video frames.
 */
data class AudioFrame(
    val data: ByteArray,
    val presentationTimeUs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        if (!data.contentEquals(other.data)) return false
        if (presentationTimeUs != other.presentationTimeUs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        return result
    }
}
