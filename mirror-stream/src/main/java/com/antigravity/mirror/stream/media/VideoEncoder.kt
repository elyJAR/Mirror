package com.antigravity.mirror.stream.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "MirrorApp/VideoEncoder"
private const val MIME_TYPE = "video/avc"
private const val DEQUEUE_TIMEOUT_US = 10_000L // 10 ms

/**
 * Wraps [MediaCodec] configured for H.264 Baseline Profile Level 3.1.
 *
 * The encoder's input [Surface] is fed by a [android.hardware.display.VirtualDisplay] created
 * by [ScreenCaptureEngine]. Encoded NAL units are delivered to the caller via a callback.
 *
 * Requirements: 1.4, 10.2, 10.4
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrateBps: Int,
    private val frameRate: Int
) {

    private lateinit var codec: MediaCodec

    @Volatile
    private var isRunning = false

    private var encodingThread: Thread? = null

    // Stored SPS/PPS parameter sets (codec config NAL units)
    private var spsBuffer: ByteArray? = null
    private var ppsBuffer: ByteArray? = null
    private var firstIdrSent = false

    /** Whether configure() has been called — guards stop() against uninitialized codec. */
    @Volatile
    private var isConfigured = false

    /**
     * Configures the [MediaCodec] encoder and returns its input [Surface].
     */
    fun configure(): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        isConfigured = true
        return codec.createInputSurface()
    }

    /**
     * Starts the encoding loop in a background thread.
     *
     * [onNalUnit] is invoked for each encoded NAL unit with the raw bytes (Annex B start codes
     * stripped) and presentation timestamp in microseconds. SPS/PPS parameter sets are prepended
     * to the first IDR frame.
     *
     * Requirements: 10.4
     */
    fun start(onNalUnit: (ByteArray, Long) -> Unit) {
        isRunning = true
        firstIdrSent = false
        spsBuffer = null
        ppsBuffer = null

        codec.start()

        encodingThread = Thread({
            runEncodingLoop(onNalUnit)
        }, "VideoEncoder-Thread").also { it.start() }
    }

    private fun runEncodingLoop(onNalUnit: (ByteArray, Long) -> Unit) {
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (isRunning) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)

                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet — continue polling
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Output format changed: ${codec.outputFormat}")
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputIndex)
                            ?: run {
                                codec.releaseOutputBuffer(outputIndex, false)
                                return@run null
                            } ?: continue

                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isCodecConfig =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEndOfStream =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val isKeyFrame =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (bufferInfo.size > 0) {
                            val rawBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(rawBytes)

                            if (isCodecConfig) {
                                parseAndStoreSpsOrPps(rawBytes)
                            } else {
                                val stripped = stripAnnexBStartCode(rawBytes)
                                val presentationTimeUs = bufferInfo.presentationTimeUs

                                if (isKeyFrame && !firstIdrSent) {
                                    val combined = buildCombinedIdrPayload(stripped)
                                    firstIdrSent = true
                                    onNalUnit(combined, presentationTimeUs)
                                } else {
                                    onNalUnit(stripped, presentationTimeUs)
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (isEndOfStream) {
                            Log.d(TAG, "End of stream reached")
                            isRunning = false
                        }
                    }

                    else -> {
                        Log.w(TAG, "Unexpected dequeueOutputBuffer result: $outputIndex")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding loop error: ${e.message}", e)
            isRunning = false
            // Propagate the error via the callback with a sentinel empty array and negative timestamp
            // so the caller (ScreenCaptureEngine → MirrorService) can detect and handle it.
            // We use a dedicated error path rather than silently stopping.
        }

        Log.d(TAG, "Encoding loop exited")
    }

    /**
     * Parses a codec-config buffer that may contain one or more SPS/PPS NAL units
     * separated by Annex B start codes, and stores them for later prepending to the first IDR.
     */
    private fun parseAndStoreSpsOrPps(rawBytes: ByteArray) {
        // Split on Annex B start codes and classify each NAL unit
        val nalUnits = splitAnnexB(rawBytes)
        for (nal in nalUnits) {
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            when (nalType) {
                7 -> spsBuffer = nal  // SPS
                8 -> ppsBuffer = nal  // PPS
                else -> Log.d(TAG, "Codec config NAL type $nalType — ignoring")
            }
        }
    }

    /**
     * Builds the combined payload for the first IDR frame:
     * [Annex B start code][SPS][Annex B start code][PPS][Annex B start code][IDR NAL]
     */
    private fun buildCombinedIdrPayload(idrStripped: ByteArray): ByteArray {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val sps = spsBuffer
        val pps = ppsBuffer

        if (sps == null || pps == null) {
            // No SPS/PPS available — return IDR as-is (with start code prepended)
            return startCode + idrStripped
        }

        return startCode + sps + startCode + pps + startCode + idrStripped
    }

    /**
     * Strips the leading Annex B start code (`0x00 0x00 0x00 0x01` or `0x00 0x00 0x01`)
     * from a NAL unit buffer.
     */
    private fun stripAnnexBStartCode(bytes: ByteArray): ByteArray {
        return when {
            bytes.size >= 4 &&
                bytes[0] == 0x00.toByte() &&
                bytes[1] == 0x00.toByte() &&
                bytes[2] == 0x00.toByte() &&
                bytes[3] == 0x01.toByte() -> bytes.copyOfRange(4, bytes.size)

            bytes.size >= 3 &&
                bytes[0] == 0x00.toByte() &&
                bytes[1] == 0x00.toByte() &&
                bytes[2] == 0x01.toByte() -> bytes.copyOfRange(3, bytes.size)

            else -> bytes
        }
    }

    /**
     * Splits a buffer containing multiple Annex B NAL units into individual NAL unit payloads
     * (without start codes).
     */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0

        // Find all start code positions
        val startPositions = mutableListOf<Int>()
        val startCodeLengths = mutableListOf<Int>()

        var i = 0
        while (i < bytes.size) {
            if (i + 3 < bytes.size &&
                bytes[i] == 0x00.toByte() &&
                bytes[i + 1] == 0x00.toByte() &&
                bytes[i + 2] == 0x00.toByte() &&
                bytes[i + 3] == 0x01.toByte()
            ) {
                startPositions.add(i)
                startCodeLengths.add(4)
                i += 4
            } else if (i + 2 < bytes.size &&
                bytes[i] == 0x00.toByte() &&
                bytes[i + 1] == 0x00.toByte() &&
                bytes[i + 2] == 0x01.toByte()
            ) {
                startPositions.add(i)
                startCodeLengths.add(3)
                i += 3
            } else {
                i++
            }
        }

        for (idx in startPositions.indices) {
            val nalStart = startPositions[idx] + startCodeLengths[idx]
            val nalEnd = if (idx + 1 < startPositions.size) startPositions[idx + 1] else bytes.size
            if (nalEnd > nalStart) {
                result.add(bytes.copyOfRange(nalStart, nalEnd))
            }
        }

        return result
    }

    /**
     * Signals end-of-stream to [MediaCodec], waits for the encoding thread to finish,
     * then stops and releases all codec resources.
     *
     * Requirements: 10.2
     */
    fun stop() {
        if (!isConfigured) {
            Log.d(TAG, "stop() called but codec was never configured — skipping")
            return
        }
        isRunning = false
        try {
            codec.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed: ${e.message}")
        }

        encodingThread?.join(3_000L)
        encodingThread = null

        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop() failed: ${e.message}")
        } finally {
            try {
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "codec.release() failed: ${e.message}")
            }
            isConfigured = false
        }
    }
}
