package com.antigravity.mirror.stream.transport.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the core data model classes.
 *
 * Validates: Requirements 1.3, 6.1, 7.1, 7.2
 */
class DataModelsTest {

    // -------------------------------------------------------------------------
    // WfdCapabilities
    // -------------------------------------------------------------------------

    @Test
    fun `WfdCapabilities holds video formats and audio codecs`() {
        val videoFormat = VideoFormat(
            profile = H264Profile.BASELINE,
            level = H264Level.LEVEL_3_1,
            maxWidth = 1920,
            maxHeight = 1080,
            maxFrameRate = 30,
            maxBitrateMbps = 10
        )
        val audioCodec = AudioCodec(
            type = AudioCodecType.LPCM,
            channels = 2,
            sampleRateHz = 48000
        )
        val caps = WfdCapabilities(
            videoFormats = listOf(videoFormat),
            audioCodecs = listOf(audioCodec),
            rtpPort = 16384,
            maxThroughputKbps = 10000
        )

        assertEquals(1, caps.videoFormats.size)
        assertEquals(H264Profile.BASELINE, caps.videoFormats[0].profile)
        assertEquals(H264Level.LEVEL_3_1, caps.videoFormats[0].level)
        assertEquals(1920, caps.videoFormats[0].maxWidth)
        assertEquals(1080, caps.videoFormats[0].maxHeight)
        assertEquals(30, caps.videoFormats[0].maxFrameRate)
        assertEquals(10, caps.videoFormats[0].maxBitrateMbps)

        assertEquals(1, caps.audioCodecs.size)
        assertEquals(AudioCodecType.LPCM, caps.audioCodecs[0].type)
        assertEquals(2, caps.audioCodecs[0].channels)
        assertEquals(48000, caps.audioCodecs[0].sampleRateHz)

        assertEquals(16384, caps.rtpPort)
        assertEquals(10000, caps.maxThroughputKbps)
    }

    @Test
    fun `WfdCapabilities supports empty format lists`() {
        val caps = WfdCapabilities(
            videoFormats = emptyList(),
            audioCodecs = emptyList(),
            rtpPort = 16384,
            maxThroughputKbps = 5000
        )
        assertEquals(0, caps.videoFormats.size)
        assertEquals(0, caps.audioCodecs.size)
    }

    // -------------------------------------------------------------------------
    // H264Profile and H264Level enums
    // -------------------------------------------------------------------------

    @Test
    fun `H264Profile has exactly three values`() {
        val values = H264Profile.values()
        assertEquals(3, values.size)
        assert(H264Profile.BASELINE in values)
        assert(H264Profile.MAIN in values)
        assert(H264Profile.HIGH in values)
    }

    @Test
    fun `H264Level has exactly four values`() {
        val values = H264Level.values()
        assertEquals(4, values.size)
        assert(H264Level.LEVEL_3_1 in values)
        assert(H264Level.LEVEL_4 in values)
        assert(H264Level.LEVEL_4_1 in values)
        assert(H264Level.LEVEL_4_2 in values)
    }

    // -------------------------------------------------------------------------
    // AudioCodecType enum
    // -------------------------------------------------------------------------

    @Test
    fun `AudioCodecType has exactly three values`() {
        val values = AudioCodecType.values()
        assertEquals(3, values.size)
        assert(AudioCodecType.LPCM in values)
        assert(AudioCodecType.AAC in values)
        assert(AudioCodecType.AC3 in values)
    }

    // -------------------------------------------------------------------------
    // RtspMessage
    // -------------------------------------------------------------------------

    @Test
    fun `RtspMessage stores all fields correctly`() {
        val headers = mapOf("CSeq" to "1", "User-Agent" to "TestSink/1.0")
        val msg = RtspMessage(
            method = "OPTIONS",
            uri = "rtsp://192.168.49.1/wfd1.0",
            cseq = 1,
            headers = headers,
            body = null
        )

        assertEquals("OPTIONS", msg.method)
        assertEquals("rtsp://192.168.49.1/wfd1.0", msg.uri)
        assertEquals(1, msg.cseq)
        assertEquals("1", msg.headers["CSeq"])
        assertNull(msg.body)
    }

    @Test
    fun `RtspMessage supports non-null body`() {
        val body = "wfd_video_formats\r\nwfd_audio_codecs\r\n"
        val msg = RtspMessage(
            method = "GET_PARAMETER",
            uri = "rtsp://192.168.49.1/wfd1.0",
            cseq = 2,
            headers = emptyMap(),
            body = body
        )
        assertEquals(body, msg.body)
    }

    // -------------------------------------------------------------------------
    // RtspResponse
    // -------------------------------------------------------------------------

    @Test
    fun `RtspResponse stores status code and cseq`() {
        val response = RtspResponse(
            statusCode = 200,
            cseq = 1,
            headers = mapOf("Content-Type" to "text/parameters"),
            body = "wfd_video_formats: ..."
        )

        assertEquals(200, response.statusCode)
        assertEquals(1, response.cseq)
        assertEquals("text/parameters", response.headers["Content-Type"])
        assertEquals("wfd_video_formats: ...", response.body)
    }

    @Test
    fun `RtspResponse supports null body`() {
        val response = RtspResponse(
            statusCode = 200,
            cseq = 3,
            headers = emptyMap(),
            body = null
        )
        assertNull(response.body)
    }

    // -------------------------------------------------------------------------
    // VideoFormat equality (data class)
    // -------------------------------------------------------------------------

    @Test
    fun `VideoFormat data class equality works correctly`() {
        val fmt1 = VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_3_1, 1280, 720, 30, 5)
        val fmt2 = VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_3_1, 1280, 720, 30, 5)
        val fmt3 = VideoFormat(H264Profile.MAIN, H264Level.LEVEL_4, 1920, 1080, 60, 20)

        assertEquals(fmt1, fmt2)
        assert(fmt1 != fmt3)
    }

    // -------------------------------------------------------------------------
    // AudioCodec equality (data class)
    // -------------------------------------------------------------------------

    @Test
    fun `AudioCodec data class equality works correctly`() {
        val codec1 = AudioCodec(AudioCodecType.AAC, 2, 44100)
        val codec2 = AudioCodec(AudioCodecType.AAC, 2, 44100)
        val codec3 = AudioCodec(AudioCodecType.LPCM, 2, 48000)

        assertEquals(codec1, codec2)
        assert(codec1 != codec3)
    }
}
