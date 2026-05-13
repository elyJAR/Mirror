package com.antigravity.mirror.stream.transport

/**
 * Unique identifier for a transport implementation.
 *
 * Used for allow-list lookups, logging, and metrics.
 */
enum class TransportId {
    /** Custom TCP protocol over local network. */
    LAN,

    /** Standard Wi-Fi Display / Miracast protocol over Wi-Fi Direct. */
    MIRACAST
}
