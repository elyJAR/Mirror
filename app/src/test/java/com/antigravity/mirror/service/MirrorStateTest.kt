package com.antigravity.mirror.service

import com.antigravity.mirror.stream.api.MirrorError
import com.antigravity.mirror.stream.api.MirrorState
import com.antigravity.mirror.stream.api.Receiver
import com.antigravity.mirror.stream.transport.TransportId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [MirrorState] sealed interface hierarchy.
 *
 * Validates: Requirements 6.1, 6.2
 */
class MirrorStateTest {

    @Test
    fun `Idle is a singleton object`() {
        val s1: MirrorState = MirrorState.Idle
        val s2: MirrorState = MirrorState.Idle
        assertEquals(s1, s2)
    }

    @Test
    fun `Discovering is a singleton object`() {
        val s1: MirrorState = MirrorState.Discovering
        val s2: MirrorState = MirrorState.Discovering
        assertEquals(s1, s2)
    }

    @Test
    fun `Connecting is a singleton object`() {
        val s1: MirrorState = MirrorState.Connecting
        val s2: MirrorState = MirrorState.Connecting
        assertEquals(s1, s2)
    }

    @Test
    fun `AwaitingPairing is a singleton object`() {
        val s1: MirrorState = MirrorState.AwaitingPairing
        val s2: MirrorState = MirrorState.AwaitingPairing
        assertEquals(s1, s2)
    }

    @Test
    fun `AwaitingProjection is a singleton object`() {
        val s1: MirrorState = MirrorState.AwaitingProjection
        val s2: MirrorState = MirrorState.AwaitingProjection
        assertEquals(s1, s2)
    }

    @Test
    fun `Streaming is a singleton object`() {
        val s1: MirrorState = MirrorState.Streaming
        val s2: MirrorState = MirrorState.Streaming
        assertEquals(s1, s2)
    }

    @Test
    fun `Reconnecting is a singleton object`() {
        val s1: MirrorState = MirrorState.Reconnecting
        val s2: MirrorState = MirrorState.Reconnecting
        assertEquals(s1, s2)
    }

    @Test
    fun `ReceiversFound carries receiver list`() {
        val receiver = Receiver("PC", "192.168.1.5", 8765, TransportId.LAN)
        val state = MirrorState.ReceiversFound(receivers = listOf(receiver))
        assertEquals(1, state.receivers.size)
        assertEquals(receiver, state.receivers[0])
    }

    @Test
    fun `ReceiversFound with empty list is valid`() {
        val state = MirrorState.ReceiversFound(receivers = emptyList())
        assertTrue(state.receivers.isEmpty())
    }

    @Test
    fun `Error carries cause and recoverable flag`() {
        val cause = MirrorError.HandshakeFailed("oops")
        val recoverable = MirrorState.Error(cause = cause, recoverable = true)
        val unrecoverable = MirrorState.Error(cause = cause, recoverable = false)

        assertEquals(cause, recoverable.cause)
        assertTrue(recoverable.recoverable)

        assertEquals(cause, unrecoverable.cause)
        assertFalse(unrecoverable.recoverable)
    }

    @Test
    fun `when expression covers all MirrorState subtypes`() {
        // Exhaustiveness check — fails to compile if a new subtype is added without updating this.
        fun label(state: MirrorState): String = when (state) {
            MirrorState.Idle -> "idle"
            MirrorState.Discovering -> "discovering"
            is MirrorState.ReceiversFound -> "receivers_found"
            MirrorState.Connecting -> "connecting"
            MirrorState.AwaitingPairing -> "awaiting_pairing"
            MirrorState.AwaitingProjection -> "awaiting_projection"
            MirrorState.Streaming -> "streaming"
            MirrorState.Reconnecting -> "reconnecting"
            is MirrorState.Error -> "error"
        }

        assertEquals("idle", label(MirrorState.Idle))
        assertEquals("discovering", label(MirrorState.Discovering))
        assertEquals("receivers_found", label(MirrorState.ReceiversFound(emptyList())))
        assertEquals("connecting", label(MirrorState.Connecting))
        assertEquals("awaiting_pairing", label(MirrorState.AwaitingPairing))
        assertEquals("awaiting_projection", label(MirrorState.AwaitingProjection))
        assertEquals("streaming", label(MirrorState.Streaming))
        assertEquals("reconnecting", label(MirrorState.Reconnecting))
        assertEquals("error", label(MirrorState.Error(MirrorError.HandshakeFailed(""), false)))
    }
}
