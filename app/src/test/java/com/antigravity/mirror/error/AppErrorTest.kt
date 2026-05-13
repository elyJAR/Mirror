package com.antigravity.mirror.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [AppError] sealed class hierarchy.
 *
 * Validates: Requirements 7.1, 7.2, 9.1, 9.2, 9.3, 9.4
 */
class AppErrorTest {

    @Test
    fun `WifiDisabled carries message`() {
        val error = AppError.WifiDisabled("WiFi is off")
        assertEquals("WiFi is off", error.message)
        assertTrue(error is AppError)
    }

    @Test
    fun `WifiDirectUnsupported carries message`() {
        val error = AppError.WifiDirectUnsupported("P2P not supported")
        assertEquals("P2P not supported", error.message)
    }

    @Test
    fun `DiscoveryFailed carries reason code`() {
        val error = AppError.DiscoveryFailed(reason = 0) // WifiP2pManager.ERROR = 0
        assertEquals(0, error.reason)
    }

    @Test
    fun `ConnectionFailed carries reason code`() {
        val error = AppError.ConnectionFailed(reason = 2) // WifiP2pManager.BUSY = 2
        assertEquals(2, error.reason)
    }

    @Test
    fun `ProjectionDenied carries message`() {
        val error = AppError.ProjectionDenied("User denied screen capture")
        assertEquals("User denied screen capture", error.message)
    }

    @Test
    fun `StreamingError carries cause throwable`() {
        val cause = RuntimeException("codec error")
        val error = AppError.StreamingError(cause)
        assertEquals(cause, error.cause)
        assertEquals("codec error", error.cause.message)
    }

    @Test
    fun `NetworkLost carries message`() {
        val error = AppError.NetworkLost("WiFi disconnected")
        assertEquals("WiFi disconnected", error.message)
    }

    @Test
    fun `PinRequired carries message`() {
        val error = AppError.PinRequired("Enter PIN to connect")
        assertEquals("Enter PIN to connect", error.message)
    }

    @Test
    fun `IncorrectPin carries message`() {
        val error = AppError.IncorrectPin("Wrong PIN, try again")
        assertEquals("Wrong PIN, try again", error.message)
    }

    @Test
    fun `AppError subtypes are distinct sealed class members`() {
        val errors: List<AppError> = listOf(
            AppError.WifiDisabled(""),
            AppError.WifiDirectUnsupported(""),
            AppError.DiscoveryFailed(0),
            AppError.ConnectionFailed(0),
            AppError.ProjectionDenied(""),
            AppError.StreamingError(RuntimeException()),
            AppError.NetworkLost(""),
            AppError.PinRequired(""),
            AppError.IncorrectPin("")
        )
        // All 9 subtypes are present
        assertEquals(9, errors.size)
        assertTrue(errors.all { it is AppError })
    }

    @Test
    fun `when expression covers all AppError subtypes`() {
        // This test verifies the sealed class is exhaustive — if a new subtype is added
        // without updating this when expression, the test will fail to compile.
        fun describe(error: AppError): String = when (error) {
            is AppError.WifiDisabled -> "wifi_disabled"
            is AppError.WifiDirectUnsupported -> "wifi_direct_unsupported"
            is AppError.DiscoveryFailed -> "discovery_failed"
            is AppError.ConnectionFailed -> "connection_failed"
            is AppError.ProjectionDenied -> "projection_denied"
            is AppError.StreamingError -> "streaming_error"
            is AppError.NetworkLost -> "network_lost"
            is AppError.PinRequired -> "pin_required"
            is AppError.IncorrectPin -> "incorrect_pin"
        }

        assertEquals("wifi_disabled", describe(AppError.WifiDisabled("")))
        assertEquals("streaming_error", describe(AppError.StreamingError(RuntimeException())))
        assertEquals("incorrect_pin", describe(AppError.IncorrectPin("")))
    }
}
