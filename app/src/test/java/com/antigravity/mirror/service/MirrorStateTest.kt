package com.antigravity.mirror.service

import android.net.wifi.p2p.WifiP2pDevice
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [MirrorState] sealed class hierarchy.
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
    fun `AwaitingProjectionConsent is a singleton object`() {
        val s1: MirrorState = MirrorState.AwaitingProjectionConsent
        val s2: MirrorState = MirrorState.AwaitingProjectionConsent
        assertEquals(s1, s2)
    }

    @Test
    fun `Streaming is a singleton object`() {
        val s1: MirrorState = MirrorState.Streaming
        val s2: MirrorState = MirrorState.Streaming
        assertEquals(s1, s2)
    }

    @Test
    fun `DevicesFound carries device list`() {
        // Use mockk to avoid instantiating the Android framework class in JVM tests
        val device = mockk<WifiP2pDevice>(relaxed = true)
        val state = MirrorState.DevicesFound(devices = listOf(device))
        assertEquals(1, state.devices.size)
        assertEquals(device, state.devices[0])
    }

    @Test
    fun `DevicesFound with empty list is valid`() {
        val state = MirrorState.DevicesFound(devices = emptyList())
        assertTrue(state.devices.isEmpty())
    }

    @Test
    fun `Error carries message and recoverable flag`() {
        val recoverable = MirrorState.Error(message = "Retry available", recoverable = true)
        val unrecoverable = MirrorState.Error(message = "Fatal error", recoverable = false)

        assertEquals("Retry available", recoverable.message)
        assertTrue(recoverable.recoverable)

        assertEquals("Fatal error", unrecoverable.message)
        assertFalse(unrecoverable.recoverable)
    }

    @Test
    fun `when expression covers all MirrorState subtypes`() {
        // Exhaustiveness check — fails to compile if a new subtype is added without updating this.
        fun label(state: MirrorState): String = when (state) {
            is MirrorState.Idle -> "idle"
            is MirrorState.Discovering -> "discovering"
            is MirrorState.DevicesFound -> "devices_found"
            is MirrorState.Connecting -> "connecting"
            is MirrorState.AwaitingProjectionConsent -> "awaiting_consent"
            is MirrorState.Streaming -> "streaming"
            is MirrorState.Error -> "error"
        }

        assertEquals("idle", label(MirrorState.Idle))
        assertEquals("discovering", label(MirrorState.Discovering))
        assertEquals("devices_found", label(MirrorState.DevicesFound(emptyList())))
        assertEquals("connecting", label(MirrorState.Connecting))
        assertEquals("awaiting_consent", label(MirrorState.AwaitingProjectionConsent))
        assertEquals("streaming", label(MirrorState.Streaming))
        assertEquals("error", label(MirrorState.Error("oops", false)))
    }
}
