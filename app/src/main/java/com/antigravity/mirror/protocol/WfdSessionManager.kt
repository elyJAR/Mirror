package com.antigravity.mirror.protocol

import android.media.projection.MediaProjection
import android.util.Log
import com.antigravity.mirror.media.ScreenCaptureEngine
import com.antigravity.mirror.model.RtspResponse
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion

private const val TAG = "MirrorApp/WfdSessionMgr"

/**
 * Source RTP port used for the WFD stream (fixed per session).
 * The sink's RTP port is negotiated during M6 SETUP.
 */
private const val SOURCE_RTP_PORT = 16384

/**
 * WFD RTSP state machine states, corresponding to the M1–M7 message exchange.
 *
 * Transitions:
 *   IDLE → AWAITING_M1_OPTIONS (on session start)
 *   AWAITING_M1_OPTIONS → AWAITING_M3_GET_PARAMETER (after M1 OPTIONS + M2 OPTIONS)
 *   AWAITING_M3_GET_PARAMETER → AWAITING_M4_SET_PARAMETER (after M3 GET_PARAMETER)
 *   AWAITING_M4_SET_PARAMETER → AWAITING_M5_TRIGGER (after M4 SET_PARAMETER)
 *   AWAITING_M5_TRIGGER → AWAITING_M6_SETUP (after M5 SET_PARAMETER wfd_trigger_method)
 *   AWAITING_M6_SETUP → AWAITING_M7_PLAY (after M6 SETUP)
 *   AWAITING_M7_PLAY → STREAMING (after M7 PLAY)
 *   Any state → TERMINATED (on TEARDOWN or error)
 */
private enum class WfdState {
    IDLE,
    AWAITING_M1_OPTIONS,
    AWAITING_M3_GET_PARAMETER,
    AWAITING_M4_SET_PARAMETER,
    AWAITING_M5_TRIGGER,
    AWAITING_M6_SETUP,
    AWAITING_M7_PLAY,
    STREAMING,
    TERMINATED
}

/**
 * Implements the WFD/Miracast RTSP state machine (M1–M7 message exchange).
 *
 * Coordinates [RtspServer], [ScreenCaptureEngine], and [RtpSender] lifecycles within the
 * state machine. Emits [SessionEvent] values as state transitions occur.
 *
 * The WFD RTSP handshake sequence:
 * ```
 * M1: Sink → Source:  OPTIONS * RTSP/1.0
 *     Source → Sink:  200 OK (Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER)
 *
 * M2: Source → Sink:  OPTIONS * RTSP/1.0  (Require: org.wfa.wfd1.0)
 *     Sink → Source:  200 OK
 *
 * M3: Sink → Source:  GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
 *     Source → Sink:  200 OK (body: WFD parameter list)
 *
 * M4: Sink → Source:  SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0 (body: selected params)
 *     Source → Sink:  200 OK
 *
 * M5: Sink → Source:  SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
 *                     body: wfd_trigger_method: SETUP
 *     Source → Sink:  200 OK
 *
 * M6: Sink → Source:  SETUP rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0
 *                     Transport: RTP/AVP/UDP;unicast;client_port={rtpPort}-{rtcpPort}
 *     Source → Sink:  200 OK
 *                     Session: {sessionId};timeout=60
 *                     Transport: RTP/AVP/UDP;unicast;client_port={rtpPort};server_port=19000
 *
 * M7: Sink → Source:  PLAY rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0
 *     Source → Sink:  200 OK
 *     → Emit SessionEvent.NegotiationComplete, then SessionEvent.StreamingStarted
 * ```
 *
 * Requirements: 1.1, 4.1, 4.2, 4.5
 */
class WfdSessionManager(
    private val rtspServer: RtspServer,
    private val captureEngine: ScreenCaptureEngine,
    private val rtpSender: RtpSender
) {

    /** Current state machine state. Accessed only from the flow's coroutine. */
    @Volatile
    private var state: WfdState = WfdState.IDLE

    /** RTSP session identifier assigned during M6 SETUP. */
    private var sessionId: String = "00000001"

    /** Sink's RTP port, parsed from the Transport header in M6 SETUP. */
    private var sinkRtpPort: Int = 0

    /** The active flow producer scope, used by [stopSession] to emit events. */
    @Volatile
    private var activeScope: ProducerScope<SessionEvent>? = null

    /**
     * Starts the WFD session using the granted [projection] token.
     *
     * Returns a [Flow] of [SessionEvent] values representing state transitions.
     * The flow completes when the session ends (TEARDOWN, error, or [stopSession]).
     *
     * Requirements: 1.1, 4.1, 4.2, 4.5
     */
    fun startSession(projection: MediaProjection): Flow<SessionEvent> = callbackFlow<SessionEvent> {
        activeScope = this
        state = WfdState.AWAITING_M1_OPTIONS
        Log.i(TAG, "WFD session starting, state=$state")

        try {
            rtspServer.start().collect { message ->
                Log.d(TAG, "Received RTSP ${message.method} CSeq=${message.cseq} state=$state")

                // TEARDOWN is handled at any state
                if (message.method.equals("TEARDOWN", ignoreCase = true)) {
                    handleTeardown(message.cseq)
                    return@collect
                }

                when (state) {
                    WfdState.AWAITING_M1_OPTIONS -> {
                        if (message.method.equals("OPTIONS", ignoreCase = true)) {
                            handleM1Options(message.cseq)
                        } else {
                            handleUnexpectedMessage(message.method, state)
                        }
                    }

                    WfdState.AWAITING_M3_GET_PARAMETER -> {
                        when {
                            message.method.equals("OPTIONS", ignoreCase = true) -> {
                                // M2: sink responding to our OPTIONS — just send 200 OK
                                handleM2OptionsResponse(message.cseq)
                            }
                            message.method.equals("GET_PARAMETER", ignoreCase = true) -> {
                                handleM3GetParameter(message.cseq)
                            }
                            else -> handleUnexpectedMessage(message.method, state)
                        }
                    }

                    WfdState.AWAITING_M4_SET_PARAMETER -> {
                        if (message.method.equals("SET_PARAMETER", ignoreCase = true)) {
                            handleM4SetParameter(message.cseq)
                        } else {
                            handleUnexpectedMessage(message.method, state)
                        }
                    }

                    WfdState.AWAITING_M5_TRIGGER -> {
                        if (message.method.equals("SET_PARAMETER", ignoreCase = true)) {
                            handleM5TriggerSetup(message.cseq, message.body)
                        } else {
                            handleUnexpectedMessage(message.method, state)
                        }
                    }

                    WfdState.AWAITING_M6_SETUP -> {
                        if (message.method.equals("SETUP", ignoreCase = true)) {
                            val transportHeader = message.headers["Transport"] ?: ""
                            handleM6Setup(message.cseq, transportHeader)
                        } else {
                            handleUnexpectedMessage(message.method, state)
                        }
                    }

                    WfdState.AWAITING_M7_PLAY -> {
                        if (message.method.equals("PLAY", ignoreCase = true)) {
                            handleM7Play(message.cseq, projection)
                        } else {
                            handleUnexpectedMessage(message.method, state)
                        }
                    }

                    WfdState.STREAMING -> {
                        // During streaming, only TEARDOWN is expected (handled above)
                        Log.d(TAG, "Ignoring ${message.method} in STREAMING state")
                    }

                    WfdState.TERMINATED, WfdState.IDLE -> {
                        Log.w(TAG, "Received ${message.method} in terminal state $state — ignoring")
                    }
                }
            }
        } catch (e: Exception) {
            if (state != WfdState.TERMINATED) {
                Log.e(TAG, "WFD session error: ${e.message}", e)
                state = WfdState.TERMINATED
                trySend(SessionEvent.StreamingError(e))
                stopSession()
            }
        } finally {
            activeScope = null
            Log.i(TAG, "WFD session flow completed")
        }
    }.catch { e ->
        Log.e(TAG, "Unhandled error in WFD session flow: ${e.message}", e)
        emit(SessionEvent.StreamingError(e))
    }.onCompletion {
        // Ensure resources are always released when the flow ends
        if (state != WfdState.TERMINATED) {
            stopSession()
        }
    }

    // -------------------------------------------------------------------------
    // M1: Sink → Source: OPTIONS
    // Source → Sink: 200 OK with WFD public methods
    // Then Source → Sink: OPTIONS (M2 initiation)
    // -------------------------------------------------------------------------

    private fun ProducerScope<SessionEvent>.handleM1Options(cseq: Int) {
        Log.d(TAG, "M1: Handling OPTIONS from sink (CSeq=$cseq)")

        // Respond to sink's OPTIONS with our supported methods
        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = mapOf(
                "Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"
            ),
            body = null
        )
        rtspServer.sendResponse(response)

        // Transition: now we expect M3 GET_PARAMETER (after we send M2 OPTIONS)
        state = WfdState.AWAITING_M3_GET_PARAMETER

        // Send M2: Source → Sink: OPTIONS * RTSP/1.0 with WFD requirement
        // This is a source-initiated request; the sink will respond with 200 OK.
        // We use CSeq=100 as the source-initiated sequence number (distinct from sink's CSeq).
        val m2Wire = buildM2OptionsWire(cseq = 100)
        rtspServer.sendRawMessage(m2Wire)

        Log.d(TAG, "M1 handled, M2 sent, state=$state")
    }

    /**
     * Builds the M2 OPTIONS request wire format that the source sends to the sink.
     *
     * Wire format:
     * ```
     * OPTIONS * RTSP/1.0\r\n
     * CSeq: {cseq}\r\n
     * Require: org.wfa.wfd1.0\r\n
     * \r\n
     * ```
     */
    private fun buildM2OptionsWire(cseq: Int): String = buildString {
        append("OPTIONS * RTSP/1.0\r\n")
        append("CSeq: $cseq\r\n")
        append("Require: org.wfa.wfd1.0\r\n")
        append("\r\n")
    }

    // -------------------------------------------------------------------------
    // M2: Sink → Source: 200 OK (response to our OPTIONS)
    // In our flow, the sink's M2 response arrives as an OPTIONS message
    // -------------------------------------------------------------------------

    private fun handleM2OptionsResponse(cseq: Int) {
        Log.d(TAG, "M2: Sink responded to our OPTIONS (CSeq=$cseq) — waiting for M3 GET_PARAMETER")
        // No response needed; just stay in AWAITING_M3_GET_PARAMETER
        // The sink will now send M3 GET_PARAMETER
    }

    // -------------------------------------------------------------------------
    // M3: Sink → Source: GET_PARAMETER
    // Source → Sink: 200 OK with WFD capabilities body
    // -------------------------------------------------------------------------

    private fun handleM3GetParameter(cseq: Int) {
        Log.d(TAG, "M3: Handling GET_PARAMETER from sink (CSeq=$cseq)")

        val wfdBody = buildWfdCapabilitiesBody()

        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = mapOf(
                "Content-Type" to "text/parameters"
            ),
            body = wfdBody
        )
        rtspServer.sendResponse(response)

        state = WfdState.AWAITING_M4_SET_PARAMETER
        Log.d(TAG, "M3 handled, state=$state")
    }

    /**
     * Builds the WFD source capabilities body for the M3 GET_PARAMETER response.
     *
     * Format per WFD specification:
     * - wfd_video_formats: native, preferred-display-mode, H264-codec-list
     * - wfd_audio_codecs: codec-list
     * - wfd_client_rtp_ports: profile;mode port0 port1 mode
     */
    private fun buildWfdCapabilitiesBody(): String {
        return buildString {
            // Video formats: native=00, preferred-display-mode=00,
            // H264-codec: profile=01 (CBP), level=01 (3.1),
            // CEA-resolution-bitmap=00000040 (1280x720p30),
            // VESA-resolution-bitmap=00000000, HH-resolution-bitmap=00000000,
            // latency=00, min-slice-size=0000, slice-enc-params=0000,
            // frame-rate-control-support=00, max-hres=none, max-vres=none
            append("wfd_video_formats: 00 00 01 01 00000040 00000000 00000000 00 0000 0000 00 none none\r\n")

            // Audio codecs: LPCM, modes=00000003 (44100Hz stereo + 48000Hz stereo), latency=00
            append("wfd_audio_codecs: LPCM 00000003 00\r\n")

            // RTP ports: profile, source port, RTCP port (0 = not used), mode
            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $SOURCE_RTP_PORT 0 mode=play\r\n")
        }
    }

    // -------------------------------------------------------------------------
    // M4: Sink → Source: SET_PARAMETER (selected parameters)
    // Source → Sink: 200 OK
    // -------------------------------------------------------------------------

    private fun handleM4SetParameter(cseq: Int) {
        Log.d(TAG, "M4: Handling SET_PARAMETER (selected params) from sink (CSeq=$cseq)")

        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = emptyMap(),
            body = null
        )
        rtspServer.sendResponse(response)

        state = WfdState.AWAITING_M5_TRIGGER
        Log.d(TAG, "M4 handled, state=$state")
    }

    // -------------------------------------------------------------------------
    // M5: Sink → Source: SET_PARAMETER with wfd_trigger_method: SETUP
    // Source → Sink: 200 OK
    // -------------------------------------------------------------------------

    private fun handleM5TriggerSetup(cseq: Int, body: String?) {
        Log.d(TAG, "M5: Handling SET_PARAMETER trigger from sink (CSeq=$cseq), body=$body")

        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = emptyMap(),
            body = null
        )
        rtspServer.sendResponse(response)

        state = WfdState.AWAITING_M6_SETUP
        Log.d(TAG, "M5 handled, state=$state")
    }

    // -------------------------------------------------------------------------
    // M6: Sink → Source: SETUP with Transport header
    // Source → Sink: 200 OK with Session and Transport headers
    // -------------------------------------------------------------------------

    private fun handleM6Setup(cseq: Int, transportHeader: String) {
        Log.d(TAG, "M6: Handling SETUP from sink (CSeq=$cseq), Transport=$transportHeader")

        // Parse the sink's RTP port from the Transport header
        // Format: RTP/AVP/UDP;unicast;client_port={rtpPort}-{rtcpPort}
        sinkRtpPort = parseClientRtpPort(transportHeader)
        Log.d(TAG, "M6: Parsed sink RTP port=$sinkRtpPort")

        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = mapOf(
                "Session" to "$sessionId;timeout=60",
                "Transport" to "RTP/AVP/UDP;unicast;client_port=$sinkRtpPort;server_port=$SOURCE_RTP_PORT"
            ),
            body = null
        )
        rtspServer.sendResponse(response)

        state = WfdState.AWAITING_M7_PLAY
        Log.d(TAG, "M6 handled, state=$state")
    }

    /**
     * Parses the sink's RTP client port from a WFD Transport header.
     *
     * Expected format: `RTP/AVP/UDP;unicast;client_port={rtpPort}-{rtcpPort}`
     * or: `RTP/AVP/UDP;unicast;client_port={rtpPort}`
     *
     * @return The parsed RTP port, or [SOURCE_RTP_PORT] as a fallback if parsing fails.
     */
    internal fun parseClientRtpPort(transportHeader: String): Int {
        val parts = transportHeader.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("client_port=", ignoreCase = true)) {
                val portValue = trimmed.substringAfter("=").trim()
                val rtpPortStr = portValue.substringBefore("-").trim()
                val parsed = rtpPortStr.toIntOrNull()
                if (parsed != null && parsed in 1..65535) {
                    return parsed
                }
            }
        }
        // Cannot parse the sink's RTP port — throw so the session fails clearly
        // rather than silently sending RTP to the wrong port.
        throw IllegalArgumentException(
            "Could not parse client_port from Transport header: '$transportHeader'"
        )
    }

    // -------------------------------------------------------------------------
    // M7: Sink → Source: PLAY
    // Source → Sink: 200 OK
    // → Emit NegotiationComplete, start capture + RTP, emit StreamingStarted
    // -------------------------------------------------------------------------

    private fun ProducerScope<SessionEvent>.handleM7Play(cseq: Int, projection: MediaProjection) {
        Log.d(TAG, "M7: Handling PLAY from sink (CSeq=$cseq)")

        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = mapOf(
                "Session" to sessionId
            ),
            body = null
        )
        rtspServer.sendResponse(response)

        state = WfdState.STREAMING

        // Emit NegotiationComplete — M1–M7 handshake is done
        trySend(SessionEvent.NegotiationComplete)
        Log.i(TAG, "M7 handled — negotiation complete, starting capture engine")

        // Start screen capture and RTP streaming
        // Use 1280x720 @ 160 dpi as the default negotiated resolution
        captureEngine.start(width = 1280, height = 720, dpi = 160)

        // Emit StreamingStarted — RTP stream is now active
        trySend(SessionEvent.StreamingStarted)
        Log.i(TAG, "Streaming started")
    }

    // -------------------------------------------------------------------------
    // TEARDOWN: Sink → Source (any state)
    // Source → Sink: 200 OK, then terminate
    // -------------------------------------------------------------------------

    private fun ProducerScope<SessionEvent>.handleTeardown(cseq: Int) {
        Log.i(TAG, "TEARDOWN received from sink (CSeq=$cseq), state=$state")

        val response = RtspResponse(
            statusCode = 200,
            cseq = cseq,
            headers = mapOf(
                "Session" to sessionId
            ),
            body = null
        )
        rtspServer.sendResponse(response)

        state = WfdState.TERMINATED
        trySend(SessionEvent.SessionEnded)

        // Release resources
        runCatching { captureEngine.stop() }
        runCatching { rtpSender.close() }
        runCatching { rtspServer.stop() }

        close() // Close the callbackFlow channel
    }

    // -------------------------------------------------------------------------
    // Unexpected message handler
    // -------------------------------------------------------------------------

    private fun ProducerScope<SessionEvent>.handleUnexpectedMessage(method: String, currentState: WfdState) {
        val msg = "Unexpected RTSP method '$method' in state $currentState"
        Log.e(TAG, msg)
        state = WfdState.TERMINATED
        trySend(SessionEvent.StreamingError(IllegalStateException(msg)))
        runCatching { stopSession() }
        close()
    }

    // -------------------------------------------------------------------------
    // stopSession
    // -------------------------------------------------------------------------

    /**
     * Sends an RTSP TEARDOWN to the sink and releases all protocol resources.
     *
     * Safe to call from any thread. If the session is already terminated, this is a no-op.
     *
     * Requirements: 4.5
     */
    fun stopSession() {
        if (state == WfdState.TERMINATED) {
            Log.d(TAG, "stopSession() called but session already terminated")
            return
        }

        Log.i(TAG, "stopSession() called, state=$state")
        state = WfdState.TERMINATED

        // Send TEARDOWN to the sink (best-effort)
        runCatching {
            val teardownResponse = RtspResponse(
                statusCode = 200,
                cseq = 0,
                headers = mapOf(
                    "Session" to sessionId
                ),
                body = null
            )
            rtspServer.sendResponse(teardownResponse)
        }

        // Release all resources
        runCatching { captureEngine.stop() }
        runCatching { rtpSender.close() }
        runCatching { rtspServer.stop() }

        // Emit SessionEnded if the flow is still active
        activeScope?.let { scope ->
            scope.trySend(SessionEvent.SessionEnded)
            scope.close()
        }

        Log.i(TAG, "stopSession() complete")
    }
}

// ---------------------------------------------------------------------------
// Event types
// ---------------------------------------------------------------------------

/** Events emitted by [WfdSessionManager.startSession]. */
sealed class SessionEvent {
    /** The M1–M7 RTSP handshake completed successfully. */
    object NegotiationComplete : SessionEvent()

    /** The RTP stream is actively transmitting encoded video. */
    object StreamingStarted : SessionEvent()

    /** An error occurred during streaming. */
    data class StreamingError(val cause: Throwable) : SessionEvent()

    /** The session ended (TEARDOWN received or sent). */
    object SessionEnded : SessionEvent()
}
