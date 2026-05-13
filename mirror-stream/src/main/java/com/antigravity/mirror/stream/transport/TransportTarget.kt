package com.antigravity.mirror.stream.transport

/**
 * A descriptor for a peer discovered by a specific [Transport].
 *
 * This is an internal type used between [Transport] implementations and the session state
 * machine. It contains enough information for a [Transport] to initiate a connection.
 */
interface TransportTarget {
    /** Human-friendly name of the target (e.g. PC hostname or device name). */
    val name: String

    /** Transport-specific address (e.g. IP address or Wi-Fi Direct MAC). */
    val host: String

    /** Transport-specific port (default 0 if not applicable). */
    val port: Int

    /** The ID of the transport that discovered this target. */
    val transportId: TransportId
}
