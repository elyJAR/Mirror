package com.antigravity.mirror.stream.api

/**
 * Configuration for a mirror session.
 *
 * Designed to have sensible defaults so a happy-path call site reads:
 * ```
 * client.connect(receiver, MirrorConfig())
 * ```
 *
 * @property width    target capture/encode width in pixels (clamped to device max).
 * @property height   target capture/encode height in pixels.
 * @property fps      target frame rate.
 * @property transport which network transport to use; see [TransportPreference].
 * @property latencyMode trade-off between speed and visual/audio stability.
 */
data class MirrorConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrateBps: Int = 12_000_000,
    val codec: Codec = Codec.H264_BASELINE,
    val transport: TransportPreference = TransportPreference.AUTO,
    val latencyMode: LatencyMode = LatencyMode.BALANCED,
)

/** Supported video codecs. v1 is H.264 Baseline only; H.265/AV1 are deferred. */
enum class Codec {
    H264_BASELINE,
}

/** Trade-off between speed and stability. */
enum class LatencyMode {
    /** 720p, 6Mbps, smallest buffers. Prioritizes speed. */
    LOW,
    /** 1080p, 10Mbps, medium buffers. The default. */
    BALANCED,
    /** 1080p, 15Mbps, largest buffers. Prioritizes visual/audio quality. */
    QUALITY,
}

/**
 * Selects which network transport to use.
 *
 * - [AUTO]: try Miracast if the device is in the per-device allow-list, otherwise LAN.
 *           This is the default and matches the standalone app's UX.
 * - [LAN]: force the LAN (custom-protocol) transport. Always available on Android 7+.
 * - [MIRACAST]: force the Miracast / Wi-Fi Display transport. Fails fast (no fallback)
 *               if the device firmware blocks third-party Miracast initiation.
 *
 * See `docs/lan-mirror/design.md` §6 for the auto-selection decision table.
 */
enum class TransportPreference {
    AUTO,
    LAN,
    MIRACAST,
}
