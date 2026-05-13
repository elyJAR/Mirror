package com.antigravity.mirror.stream.transport.miracast

/**
 * Negotiates the best compatible [VideoFormat] between a WFD source and sink.
 *
 * Negotiation rules:
 * 1. Compute the intersection of source and sink video formats (exact match on all fields).
 * 2. If the intersection is empty, throw [IllegalArgumentException].
 * 3. From the intersection, select the best format by:
 *    a. Highest resolution (maxWidth * maxHeight) — prefer larger
 *    b. Tie-break: highest maxFrameRate
 *    c. Tie-break: prefer BASELINE > MAIN > HIGH (maximum compatibility)
 */
object WfdNegotiator {

    /**
     * Returns the best [VideoFormat] that both [source] and [sink] support.
     *
     * @throws IllegalArgumentException if no common format exists.
     */
    fun negotiate(source: WfdCapabilities, sink: WfdCapabilities): VideoFormat {
        val intersection = source.videoFormats.intersect(sink.videoFormats.toSet())

        if (intersection.isEmpty()) {
            throw IllegalArgumentException(
                "No compatible video format found between source and sink"
            )
        }

        return intersection.maxWith(
            compareBy<VideoFormat> { it.maxWidth.toLong() * it.maxHeight.toLong() }
                .thenBy { it.maxFrameRate }
                .thenByDescending { profileCompatibilityScore(it.profile) }
        )
    }

    /**
     * Returns a score where a higher value means lower preference (we use [thenByDescending]
     * so that BASELINE — score 2 — wins over MAIN — score 1 — wins over HIGH — score 0).
     */
    private fun profileCompatibilityScore(profile: H264Profile): Int = when (profile) {
        H264Profile.BASELINE -> 2
        H264Profile.MAIN -> 1
        H264Profile.HIGH -> 0
    }
}
