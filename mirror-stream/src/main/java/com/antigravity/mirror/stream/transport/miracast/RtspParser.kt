package com.antigravity.mirror.stream.transport.miracast

import java.util.TreeMap

/**
 * Parses raw RTSP request text into [RtspMessage] objects.
 *
 * Supports WFD methods: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN.
 *
 * Validates: Requirements 4.1, 4.2
 */
object RtspParser {

    /** WFD RTSP methods recognised by this parser. Unknown methods are still accepted. */
    private val KNOWN_METHODS = setOf(
        "OPTIONS", "GET_PARAMETER", "SET_PARAMETER", "SETUP", "PLAY", "TEARDOWN"
    )

    /**
     * Parses a raw RTSP request string into an [RtspMessage].
     *
     * The expected format is:
     * ```
     * METHOD URI RTSP/1.0\r\n
     * Header-Name: value\r\n
     * ...
     * \r\n
     * [optional body]
     * ```
     *
     * Lines may be separated by `\r\n` or bare `\n`. Leading/trailing whitespace on
     * header values is trimmed. The `CSeq` header is required; a missing or non-integer
     * `CSeq` throws [IllegalArgumentException]. All headers (including unknown ones) are
     * stored in a case-insensitive map.
     *
     * @param raw The raw RTSP request text received from the sink.
     * @return A fully populated [RtspMessage].
     * @throws IllegalArgumentException if the request line is malformed, the RTSP version
     *   is not `RTSP/1.0`, or the `CSeq` header is missing or not a valid integer.
     */
    fun parse(raw: String): RtspMessage {
        // Normalise line endings to \n for uniform splitting
        val normalised = raw.replace("\r\n", "\n").replace("\r", "\n")

        // Split into lines; keep empty lines so we can find the header/body separator
        val lines = normalised.split("\n")

        if (lines.isEmpty() || lines[0].isBlank()) {
            throw IllegalArgumentException("RTSP request is empty or missing request line")
        }

        // --- Parse request line ---
        val requestLine = lines[0].trim()
        val requestParts = requestLine.split(Regex("\\s+"))
        if (requestParts.size < 3) {
            throw IllegalArgumentException(
                "Malformed RTSP request line (expected 'METHOD URI RTSP/1.0'): '$requestLine'"
            )
        }
        val method = requestParts[0]
        val uri = requestParts[1]
        val version = requestParts[2]
        if (!version.equals("RTSP/1.0", ignoreCase = true)) {
            throw IllegalArgumentException(
                "Unsupported RTSP version '$version'; only RTSP/1.0 is supported"
            )
        }

        // --- Parse headers ---
        // Use a case-insensitive TreeMap so lookups like headers["cseq"] work.
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        var bodyStartIndex = lines.size // default: no body

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) {
                // Blank line separates headers from body
                bodyStartIndex = i + 1
                break
            }
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val name = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[name] = value
            }
            // Lines without a colon are silently ignored (malformed header lines)
        }

        // --- Parse CSeq ---
        val cseqRaw = headers["CSeq"]
            ?: throw IllegalArgumentException("Required 'CSeq' header is missing")
        val cseq = cseqRaw.trim().toIntOrNull()
            ?: throw IllegalArgumentException(
                "Malformed 'CSeq' header value: '$cseqRaw' is not a valid integer"
            )

        // --- Parse body ---
        val body: String? = if (bodyStartIndex < lines.size) {
            val bodyLines = lines.subList(bodyStartIndex, lines.size)
            val joined = bodyLines.joinToString("\n")
            // Return null if the body is effectively empty
            if (joined.isBlank()) null else joined
        } else {
            null
        }

        return RtspMessage(
            method = method,
            uri = uri,
            cseq = cseq,
            headers = headers,
            body = body
        )
    }
}
