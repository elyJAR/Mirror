package com.antigravity.mirror.stream.transport.lan.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy for JSON control messages exchanged over TCP.
 *
 * See docs/lan-mirror/design.md §3.2 for the wire format specification.
 */
@Serializable
sealed class ControlMessage {
    abstract val type: String
}

/**
 * Sent by the phone immediately after TCP connect.
 */
@Serializable
@SerialName("hello")
data class HelloMessage(
    override val type: String = "hello",
    val version: Int = 1,
    val device: String
) : ControlMessage()

/**
 * Sent by the PC if it accepts the connection.
 */
@Serializable
@SerialName("hello-ack")
data class HelloAckMessage(
    override val type: String = "hello-ack",
    val receiver: String,
    val params: SessionParams
) : ControlMessage()

@Serializable
data class SessionParams(
    val width: Int,
    val height: Int,
    val fps: Int
)

/**
 * Sent by the PC if it is busy or incompatible.
 */
@Serializable
@SerialName("hello-reject")
data class HelloRejectMessage(
    override val type: String = "hello-reject",
    val reason: String
) : ControlMessage()

/**
 * Sent periodically (every 5s) by either side to maintain the session.
 */
@Serializable
@SerialName("ping")
data class PingMessage(
    override val type: String = "ping",
    val timestamp: Long
) : ControlMessage()

@Serializable
@SerialName("pong")
data class PongMessage(
    override val type: String = "pong",
    val timestamp: Long
) : ControlMessage()

/**
 * Sent by the PC when it loses sync or a new decoder starts.
 */
@Serializable
@SerialName("request-keyframe")
data class RequestKeyframeMessage(
    override val type: String = "request-keyframe"
) : ControlMessage()

/**
 * Sent periodically by the PC to report decoding performance.
 */
@Serializable
@SerialName("stats")
data class StatsMessage(
    override val type: String = "stats",
    val fps: Double,
    val kbps: Double,
    val latency: Int
) : ControlMessage()

/**
 * Sent by either side to end the session gracefully.
 */
@Serializable
@SerialName("bye")
data class ByeMessage(
    override val type: String = "bye",
    val reason: String
) : ControlMessage()
