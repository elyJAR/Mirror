package com.antigravity.mirror.stream.api

/**
 * A discovered destination the phone can mirror to.
 *
 * Returned to consumers via [MirrorState.ReceiversFound]. The same type covers both
 * transports — [transport] tells the consumer (and its UI) which one this descriptor
 * came from. Two receivers are equal when their (host, port, transport) tuple matches.
 *
 * @property name        human-friendly name (e.g. PC hostname or peer device name).
 * @property host        IP address or Wi-Fi Direct device address, depending on [transport].
 * @property port        TCP port for [Transport.LAN]; not meaningful for Miracast.
 * @property transport   transport that found this receiver.
 */
data class Receiver(
    val name: String,
    val host: String,
    val port: Int,
    val transport: Transport,
)
