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
    val device: String,
    val codecs: List<String> = listOf("video/avc")
) : ControlMessage()

/**
 * Sent by the PC if it accepts the connection.
 */
@Serializable
@SerialName("hello-ack")
data class HelloAckMessage(
    override val type: String = "hello-ack",
    val receiver: String,
    val params: SessionParams,
    val pinRequired: Boolean = false
) : ControlMessage()

@Serializable
data class SessionParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String = "video/avc"
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
 * Sent by the PC to inject a touch event on the phone.
 */
@Serializable
@SerialName("touch")
data class TouchEventMessage(
    override val type: String = "touch",
    val action: Int, // 0=down, 1=up, 2=move
    val x: Float,    // 0.0 to 1.0 (normalized)
    val y: Float
) : ControlMessage()

/**
 * Sent by the PC to inject a key event on the phone.
 */
@Serializable
@SerialName("key")
data class KeyEventMessage(
    override val type: String = "key",
    val code: Int // Android KeyEvent code
) : ControlMessage()

/**
 * Sent by the phone to verify the pairing PIN.
 */
@Serializable
@SerialName("verify-pin")
data class VerifyPinMessage(
    override val type: String = "verify-pin",
    val pin: String
) : ControlMessage()

/**
 * Sent by the PC after a verify-pin message.
 */
@Serializable
@SerialName("auth-result")
data class AuthResultMessage(
    override val type: String = "auth-result",
    val success: Boolean,
    val message: String? = null
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

/**
 * Sent by the phone to toggle extended display on the PC.
 */
@Serializable
@SerialName("extend_display")
data class ExtendDisplayMessage(
    override val type: String = "extend_display"
) : ControlMessage()
/**
 * Sent by the PC to report its projection status.
 */
@Serializable
@SerialName("projection_state")
data class ProjectionStateMessage(
    override val type: String = "projection_state",
    val active: Boolean
) : ControlMessage()
