package com.antigravity.mirror.stream.transport.miracast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MirrorApp/RtspServer"

/**
 * TCP server that listens on port 7236 for the Miracast sink's RTSP connection.
 *
 * In the Miracast protocol the *source* (this device) runs the RTSP server; the sink connects
 * to it to negotiate capabilities and start the stream. The Android device acts as the Wi-Fi
 * Direct Group Owner with IP 192.168.49.1.
 *
 * Usage:
 * ```kotlin
 * val server = RtspServer()
 * server.start().collect { message ->
 *     val response = handleMessage(message)
 *     server.sendResponse(response)
 * }
 * // When done:
 * server.stop()
 * ```
 *
 * Thread-safety: [sendResponse] is safe to call from any coroutine/thread while [start] is
 * running. The underlying [PrintWriter] is guarded by a lock.
 *
 * Requirements: 4.1, 4.2
 */
class RtspServer(private val port: Int = 7236) {

    /** The server socket; set once in [start], closed in [stop]. */
    private val serverSocketRef = AtomicReference<ServerSocket?>(null)

    /** The accepted client socket; set once the sink connects. */
    private val clientSocketRef = AtomicReference<Socket?>(null)

    /**
     * Writer for the accepted client socket. Guarded by [writerLock] so that
     * [sendResponse] can be called safely from any coroutine.
     */
    private val writerRef = AtomicReference<PrintWriter?>(null)
    private val writerLock = Any()

    /**
     * Starts the TCP server and returns a [Flow] of parsed [RtspMessage] objects.
     *
     * The flow:
     * - Binds a [ServerSocket] on [port].
     * - Accepts exactly one TCP connection from the Miracast sink.
     * - Reads RTSP messages in a loop, parsing each complete message with [RtspParser].
     * - Emits each parsed [RtspMessage] to the collector.
     * - Closes and cleans up when [stop] is called or a socket error occurs.
     *
     * The flow is cold — the server socket is only opened when collection begins.
     *
     * Requirements: 4.1, 4.2
     */
    fun start(): Flow<RtspMessage> = callbackFlow {
        val serverSocket = withContext(Dispatchers.IO) {
            ServerSocket(port).also { ss ->
                ss.reuseAddress = true
                serverSocketRef.set(ss)
                Log.i(TAG, "RTSP server listening on port $port")
            }
        }

        // Launch the accept/read loop inside the callbackFlow's scope so that
        // cancellation of the flow also cancels this job.
        val job = launch(Dispatchers.IO) {
            try {
                // Accept exactly one connection from the sink.
                val clientSocket = serverSocket.accept().also { cs ->
                    clientSocketRef.set(cs)
                    Log.i(TAG, "Sink connected from ${cs.inetAddress.hostAddress}")
                }

                // Set up the writer for sendResponse (guarded by writerLock).
                synchronized(writerLock) {
                    writerRef.set(
                        PrintWriter(
                            OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8),
                            /* autoFlush = */ false
                        )
                    )
                }

                val reader = BufferedReader(
                    InputStreamReader(clientSocket.getInputStream(), Charsets.UTF_8)
                )

                // Read RTSP messages until the socket is closed or stop() is called.
                while (!clientSocket.isClosed) {
                    val raw = readRtspMessage(reader) ?: break
                    try {
                        val message = RtspParser.parse(raw)
                        trySend(message)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Failed to parse RTSP message: ${e.message}")
                        // Skip malformed messages; keep the connection alive.
                    }
                }
            } catch (e: Exception) {
                // ServerSocket.accept() or read throws when stop() closes the socket.
                if (!serverSocket.isClosed) {
                    Log.e(TAG, "RTSP server error: ${e.message}", e)
                }
            } finally {
                closeResources()
                close() // Close the callbackFlow channel.
            }
        }

        awaitClose {
            job.cancel()
            closeResources()
        }
    }

    /**
     * Reads a single complete RTSP message from [reader].
     *
     * An RTSP message consists of:
     * 1. A request line.
     * 2. Zero or more header lines.
     * 3. A blank line (end of headers).
     * 4. An optional body whose length is given by the `Content-Length` header.
     *
     * @return The raw RTSP message string, or `null` if the stream ended (EOF).
     */
    private fun readRtspMessage(reader: BufferedReader): String? {
        val sb = StringBuilder()

        // Read the request line and headers until the blank line.
        var contentLength = 0
        var lineCount = 0
        val MAX_HEADERS = 100
        val MAX_LINE_LENGTH = 4096

        while (lineCount < MAX_HEADERS) {
            val line = readLimitedLine(reader, MAX_LINE_LENGTH) ?: return null // EOF
            lineCount++
            sb.append(line).append("\r\n")
            if (line.isEmpty()) {
                // Blank line — end of headers.
                break
            }
            // Check for Content-Length header (case-insensitive).
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        if (lineCount >= MAX_HEADERS) {
            Log.w(TAG, "Too many headers in RTSP message")
            return null
        }

        // Read the body if Content-Length > 0.
        // Limit body size to 64KB to prevent OOM
        val MAX_BODY_SIZE = 64 * 1024
        if (contentLength > MAX_BODY_SIZE) {
            Log.w(TAG, "RTSP body too large: $contentLength")
            return null
        }

        if (contentLength > 0) {
            val bodyChars = CharArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                if (read == -1) break // EOF mid-body
                totalRead += read
            }
            sb.append(bodyChars, 0, totalRead)
        }

        return sb.toString()
    }

    /**
     * Reads a line from [reader] with a maximum length to prevent DoS.
     */
    private fun readLimitedLine(reader: BufferedReader, maxLength: Int): String? {
        val sb = StringBuilder()
        var charCount = 0
        while (charCount < maxLength) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.toInt()) break
            if (c == '\r'.toInt()) {
                reader.mark(1)
                if (reader.read() != '\n'.toInt()) {
                    reader.reset()
                }
                break
            }
            sb.append(c.toChar())
            charCount++
        }
        if (charCount >= maxLength) {
            Log.w(TAG, "RTSP line too long")
        }
        return sb.toString()
    }

    /**
     * Writes [response] to the open TCP connection with the sink.
     *
     * Safe to call from any coroutine or thread while [start] is active.
     * If the connection is not yet established or has been closed, the call is a no-op.
     *
     * Requirements: 4.2
     */
    fun sendResponse(response: RtspResponse) {
        synchronized(writerLock) {
            val writer = writerRef.get() ?: run {
                Log.w(TAG, "sendResponse called but no client is connected")
                return
            }
            try {
                val wire = response.toWireFormat()
                writer.print(wire)
                writer.flush()
                Log.d(TAG, "Sent RTSP response: ${response.statusCode} CSeq=${response.cseq}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send RTSP response: ${e.message}", e)
            }
        }
    }

    /**
     * Writes a raw RTSP message string to the open TCP connection with the sink.
     *
     * Used for source-initiated RTSP requests (e.g., M2 OPTIONS) that cannot be expressed
     * as an [RtspResponse]. The caller is responsible for correct RTSP wire formatting.
     *
     * Safe to call from any coroutine or thread while [start] is active.
     * If the connection is not yet established or has been closed, the call is a no-op.
     *
     * Requirements: 4.1, 4.2
     */
    fun sendRawMessage(raw: String) {
        synchronized(writerLock) {
            val writer = writerRef.get() ?: run {
                Log.w(TAG, "sendRawMessage called but no client is connected")
                return
            }
            try {
                writer.print(raw)
                writer.flush()
                Log.d(TAG, "Sent raw RTSP message (${raw.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send raw RTSP message: ${e.message}", e)
            }
        }
    }

    /**
     * Closes the server socket and cancels the listening coroutine.
     *
     * After [stop] returns, the [Flow] returned by [start] will complete.
     */
    fun stop() {
        Log.i(TAG, "Stopping RTSP server")
        // Closing the sockets unblocks any pending accept()/read() calls,
        // which causes the read loop to exit and the flow to complete.
        closeResources()
    }

    /** Closes all open sockets and clears the writer reference. */
    private fun closeResources() {
        synchronized(writerLock) {
            writerRef.getAndSet(null)?.close()
        }
        clientSocketRef.getAndSet(null)?.runCatching { close() }
        serverSocketRef.getAndSet(null)?.runCatching { close() }
    }
}
