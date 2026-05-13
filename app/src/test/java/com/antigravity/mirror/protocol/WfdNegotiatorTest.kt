package com.antigravity.mirror.protocol

import com.antigravity.mirror.model.AudioCodec
import com.antigravity.mirror.model.AudioCodecType
import com.antigravity.mirror.model.H264Level
import com.antigravity.mirror.model.H264Profile
import com.antigravity.mirror.model.VideoFormat
import com.antigravity.mirror.model.WfdCapabilities
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun caps(vararg formats: VideoFormat) = WfdCapabilities(
    videoFormats = formats.toList(),
    audioCodecs = listOf(AudioCodec(AudioCodecType.LPCM, 2, 48000)),
    rtpPort = 16384,
    maxThroughputKbps = 10000
)

private fun fmt(
    profile: H264Profile = H264Profile.BASELINE,
    level: H264Level = H264Level.LEVEL_3_1,
    w: Int = 1280,
    h: Int = 720,
    fps: Int = 30,
    mbps: Int = 10
) = VideoFormat(profile, level, w, h, fps, mbps)

// ---------------------------------------------------------------------------
// Unit tests (JUnit 4)
// ---------------------------------------------------------------------------

class WfdNegotiatorUnitTest {

    // --- basic intersection ---

    @Test
    fun `returns the single common format when intersection has one element`() {
        val common = fmt(w = 1920, h = 1080)
        val source = caps(common, fmt(w = 3840, h = 2160))
        val sink = caps(common, fmt(w = 640, h = 480))

        val result = WfdNegotiator.negotiate(source, sink)

        assertEquals(common, result)
    }

    @Test
    fun `throws when source and sink have no common formats`() {
        val source = caps(fmt(w = 1920, h = 1080))
        val sink = caps(fmt(w = 1280, h = 720))

        try {
            WfdNegotiator.negotiate(source, sink)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("No compatible video format found between source and sink", e.message)
        }
    }

    @Test
    fun `throws when source has no formats`() {
        val source = caps()
        val sink = caps(fmt())

        try {
            WfdNegotiator.negotiate(source, sink)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun `throws when sink has no formats`() {
        val source = caps(fmt())
        val sink = caps()

        try {
            WfdNegotiator.negotiate(source, sink)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    // --- resolution priority ---

    @Test
    fun `prefers higher resolution when multiple common formats exist`() {
        val hd = fmt(w = 1920, h = 1080, fps = 30)
        val sd = fmt(w = 1280, h = 720, fps = 60) // higher fps but lower resolution
        val source = caps(hd, sd)
        val sink = caps(hd, sd)

        val result = WfdNegotiator.negotiate(source, sink)

        assertEquals(hd, result)
    }

    @Test
    fun `4K beats 1080p regardless of frame rate`() {
        val uhd = fmt(w = 3840, h = 2160, fps = 24)
        val fhd = fmt(w = 1920, h = 1080, fps = 60)
        val source = caps(uhd, fhd)
        val sink = caps(uhd, fhd)

        assertEquals(uhd, WfdNegotiator.negotiate(source, sink))
    }

    // --- frame rate tie-break ---

    @Test
    fun `prefers higher frame rate when resolution is equal`() {
        val fps60 = fmt(w = 1280, h = 720, fps = 60)
        val fps30 = fmt(w = 1280, h = 720, fps = 30)
        val source = caps(fps60, fps30)
        val sink = caps(fps60, fps30)

        assertEquals(fps60, WfdNegotiator.negotiate(source, sink))
    }

    // --- profile tie-break ---

    @Test
    fun `prefers BASELINE over MAIN when resolution and frame rate are equal`() {
        val baseline = fmt(profile = H264Profile.BASELINE, w = 1280, h = 720, fps = 30)
        val main = fmt(profile = H264Profile.MAIN, w = 1280, h = 720, fps = 30)
        val source = caps(baseline, main)
        val sink = caps(baseline, main)

        assertEquals(baseline, WfdNegotiator.negotiate(source, sink))
    }

    @Test
    fun `prefers MAIN over HIGH when resolution and frame rate are equal`() {
        val main = fmt(profile = H264Profile.MAIN, w = 1280, h = 720, fps = 30)
        val high = fmt(profile = H264Profile.HIGH, w = 1280, h = 720, fps = 30)
        val source = caps(main, high)
        val sink = caps(main, high)

        assertEquals(main, WfdNegotiator.negotiate(source, sink))
    }

    @Test
    fun `prefers BASELINE over HIGH when resolution and frame rate are equal`() {
        val baseline = fmt(profile = H264Profile.BASELINE, w = 1920, h = 1080, fps = 30)
        val high = fmt(profile = H264Profile.HIGH, w = 1920, h = 1080, fps = 30)
        val source = caps(baseline, high)
        val sink = caps(baseline, high)

        assertEquals(baseline, WfdNegotiator.negotiate(source, sink))
    }

    // --- format matching is exact (all fields must match) ---

    @Test
    fun `formats that differ only in bitrate are not considered equal`() {
        val f1 = VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_3_1, 1280, 720, 30, 10)
        val f2 = VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_3_1, 1280, 720, 30, 20)
        val source = caps(f1)
        val sink = caps(f2)

        try {
            WfdNegotiator.negotiate(source, sink)
            fail("Expected IllegalArgumentException — formats differ in bitrate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun `formats that differ only in level are not considered equal`() {
        val f1 = VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_3_1, 1280, 720, 30, 10)
        val f2 = VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_4, 1280, 720, 30, 10)
        val source = caps(f1)
        val sink = caps(f2)

        try {
            WfdNegotiator.negotiate(source, sink)
            fail("Expected IllegalArgumentException — formats differ in level")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    // --- result is a member of both sets ---

    @Test
    fun `negotiated format is present in both source and sink format lists`() {
        val shared1 = fmt(w = 1920, h = 1080, fps = 30)
        val shared2 = fmt(w = 1280, h = 720, fps = 60)
        val sourceOnly = fmt(w = 3840, h = 2160, fps = 24)
        val sinkOnly = fmt(w = 640, h = 480, fps = 30)

        val source = caps(shared1, shared2, sourceOnly)
        val sink = caps(shared1, shared2, sinkOnly)

        val result = WfdNegotiator.negotiate(source, sink)

        assertTrue(result in source.videoFormats)
        assertTrue(result in sink.videoFormats)
    }
}

// ---------------------------------------------------------------------------
// Property-based tests (Kotest)
// Feature: android-screen-mirror, Property 7: Capability negotiation produces valid intersection
// ---------------------------------------------------------------------------

class WfdNegotiatorPropertyTest : StringSpec({

    // Arbitrary generators
    val arbProfile = Arb.element(H264Profile.values().toList())
    val arbLevel = Arb.element(H264Level.values().toList())
    val arbWidth = Arb.element(listOf(640, 1280, 1920, 3840))
    val arbHeight = Arb.element(listOf(480, 720, 1080, 2160))
    val arbFps = Arb.element(listOf(24, 30, 60))
    val arbBitrate = Arb.element(listOf(5, 10, 20, 40))

    val arbVideoFormat = Arb.bind(arbProfile, arbLevel, arbWidth, arbHeight) { p, l, w, h ->
        VideoFormat(p, l, w, h, 30, 10)
    }

    // A generator that produces a non-empty list of VideoFormats
    val arbNonEmptyFormatList = Arb.list(arbVideoFormat, 1..6)

    fun makeCaps(formats: List<VideoFormat>) = WfdCapabilities(
        videoFormats = formats,
        audioCodecs = listOf(AudioCodec(AudioCodecType.LPCM, 2, 48000)),
        rtpPort = 16384,
        maxThroughputKbps = 10000
    )

    /**
     * Property 7: Capability negotiation produces valid intersection
     *
     * For any pair of WfdCapabilities objects representing source and sink capabilities,
     * the negotiated VideoFormat SHALL be a member of both the source's and the sink's
     * supported format sets. The negotiation SHALL never produce a format that either
     * party does not support.
     *
     * Validates: Requirements 1.4
     */
    "Property 7: negotiated format is always in both source and sink format sets" {
        // Generate pairs where the intersection is guaranteed to be non-empty
        // by sharing at least one format between source and sink.
        checkAll(100, arbNonEmptyFormatList, arbNonEmptyFormatList, arbNonEmptyFormatList) {
            sharedFormats, sourceExtra, sinkExtra ->

            val sourceFormats = (sharedFormats + sourceExtra).distinct()
            val sinkFormats = (sharedFormats + sinkExtra).distinct()

            val source = makeCaps(sourceFormats)
            val sink = makeCaps(sinkFormats)

            val result = WfdNegotiator.negotiate(source, sink)

            // The result must be in both sets
            source.videoFormats shouldContain result
            sink.videoFormats shouldContain result
        }
    }

    /**
     * Property 7 (corollary): When source and sink have no common formats,
     * negotiate() always throws IllegalArgumentException.
     *
     * Validates: Requirements 1.4
     */
    "Property 7 corollary: negotiate throws when intersection is empty" {
        // Build disjoint format lists by using distinct (w, h) pairs for source vs sink
        val sourceOnlyFormats = listOf(
            VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_3_1, 640, 480, 30, 5),
            VideoFormat(H264Profile.MAIN, H264Level.LEVEL_4, 1280, 720, 30, 10)
        )
        val sinkOnlyFormats = listOf(
            VideoFormat(H264Profile.HIGH, H264Level.LEVEL_4_1, 1920, 1080, 60, 20),
            VideoFormat(H264Profile.BASELINE, H264Level.LEVEL_4_2, 3840, 2160, 24, 40)
        )

        val source = makeCaps(sourceOnlyFormats)
        val sink = makeCaps(sinkOnlyFormats)

        var threw = false
        try {
            WfdNegotiator.negotiate(source, sink)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        threw shouldBe true
    }

    /**
     * Property 7 (selection): The selected format always has the maximum resolution
     * among all formats in the intersection.
     *
     * Validates: Requirements 1.4
     */
    "Property 7 selection: negotiated format has maximum resolution in intersection" {
        checkAll(100, arbNonEmptyFormatList, arbNonEmptyFormatList, arbNonEmptyFormatList) {
            sharedFormats, sourceExtra, sinkExtra ->

            val sourceFormats = (sharedFormats + sourceExtra).distinct()
            val sinkFormats = (sharedFormats + sinkExtra).distinct()

            val source = makeCaps(sourceFormats)
            val sink = makeCaps(sinkFormats)

            val result = WfdNegotiator.negotiate(source, sink)
            val intersection = sourceFormats.intersect(sinkFormats.toSet())

            val maxResolution = intersection.maxOf { it.maxWidth.toLong() * it.maxHeight.toLong() }
            val resultResolution = result.maxWidth.toLong() * result.maxHeight.toLong()

            resultResolution shouldBe maxResolution
        }
    }
})
