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
 * @property bitrateBps target encoder bitrate in bits per second.
 * @property codec    video codec; only [Codec.H264_BASELINE] is supported in v1.
 * @property transport which network transport to use; see [TransportPreference].
 */
data class MirrorConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateBps: Int = 8_000_000,
    val codec: Codec = Codec.H264_BASELINE,
    val transport: TransportPreference = TransportPreference.AUTO,
)

/** Supported video codecs. v1 is H.264 Baseline only; H.265/AV1 are deferred. */
enum class Codec {
    H264_BASELINE,
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
