package com.antigravity.mirror.stream.transport.miracast

/**
 * Represents the negotiated WFD parameters exchanged during the RTSP M3/M4 handshake.
 */
data class WfdCapabilities(
    val videoFormats: List<VideoFormat>,
    val audioCodecs: List<AudioCodec>,
    val rtpPort: Int,
    val maxThroughputKbps: Int
)

/**
 * Describes a supported H.264 video format for WFD streaming.
 */
data class VideoFormat(
    val profile: H264Profile,
    val level: H264Level,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val maxBitrateMbps: Int
)

/** H.264 profile levels supported by the Miracast specification. */
enum class H264Profile {
    BASELINE,
    MAIN,
    HIGH
}

/** H.264 codec levels supported by the Miracast specification. */
enum class H264Level {
    LEVEL_3_1,
    LEVEL_4,
    LEVEL_4_1,
    LEVEL_4_2
}

/**
 * Describes a supported audio codec for WFD streaming.
 */
data class AudioCodec(
    val type: AudioCodecType,
    val channels: Int,
    val sampleRateHz: Int
)

/** Audio codec types supported by the Miracast specification. */
enum class AudioCodecType {
    LPCM,
    AAC,
    AC3
}
