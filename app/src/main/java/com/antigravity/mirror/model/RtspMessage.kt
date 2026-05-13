package com.antigravity.mirror.model

/**
 * Represents a parsed WFD RTSP request received from the Miracast sink.
 *
 * WFD methods: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN
 */
data class RtspMessage(
    /** RTSP method (e.g. "OPTIONS", "GET_PARAMETER", "SET_PARAMETER", "SETUP", "PLAY", "TEARDOWN"). */
    val method: String,
    /** Request URI (e.g. "rtsp://192.168.49.1/wfd1.0"). */
    val uri: String,
    /** CSeq header value — monotonically increasing sequence number. */
    val cseq: Int,
    /** All RTSP headers as a case-insensitive map (header name → value). */
    val headers: Map<String, String>,
    /** Optional message body (e.g. WFD parameter list for GET_PARAMETER / SET_PARAMETER). */
    val body: String?
)

/**
 * Represents an RTSP response to be sent back to the Miracast sink.
 */
data class RtspResponse(
    /** HTTP-style status code (200 OK, 400 Bad Request, 404 Not Found, etc.). */
    val statusCode: Int,
    /** CSeq value echoed from the corresponding request. */
    val cseq: Int,
    /** Response headers. */
    val headers: Map<String, String>,
    /** Optional response body. */
    val body: String?
) {
    /**
     * Serialises this response to the RTSP wire format.
     *
     * Format:
     * ```
     * RTSP/1.0 {statusCode} {reasonPhrase}\r\n
     * CSeq: {cseq}\r\n
     * [Header-Name: value\r\n ...]
     * [Content-Length: {n}\r\n]
     * \r\n
     * [body]
     * ```
     *
     * Rules:
     * - The `CSeq` header is always emitted immediately after the status line.
     * - Additional headers from [headers] follow (CSeq is skipped if already present in the map
     *   to avoid duplication).
     * - If [body] is non-null and non-empty, a `Content-Length` header is auto-inserted (unless
     *   the caller already provided one in [headers]).
     * - The header block is terminated by a blank line (`\r\n`).
     * - If [body] is null or empty the message ends after the blank line.
     *
     * Validates: Requirements 4.1, 4.2
     */
    fun toWireFormat(): String {
        val reason = reasonPhrase(statusCode)
        val sb = StringBuilder()

        // Status line
        sb.append("RTSP/1.0 $statusCode $reason\r\n")

        // CSeq is always first
        sb.append("CSeq: $cseq\r\n")

        // Determine whether we need to auto-add Content-Length
        val hasBody = !body.isNullOrEmpty()
        val bodyBytes = if (hasBody) body!!.toByteArray(Charsets.UTF_8) else ByteArray(0)
        val callerProvidesContentLength = headers.keys.any {
            it.equals("Content-Length", ignoreCase = true)
        }

        // Emit caller-supplied headers (skip CSeq to avoid duplication)
        for ((name, value) in headers) {
            if (name.equals("CSeq", ignoreCase = true)) continue
            sb.append("$name: $value\r\n")
        }

        // Auto-add Content-Length when body is present and caller didn't supply it
        if (hasBody && !callerProvidesContentLength) {
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }

        // Blank line separating headers from body
        sb.append("\r\n")

        // Body (if any)
        if (hasBody) {
            sb.append(body)
        }

        return sb.toString()
    }

    companion object {
        /** Maps standard RTSP/HTTP status codes to their reason phrases. */
        fun reasonPhrase(statusCode: Int): String = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }
}
