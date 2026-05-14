package com.antigravity.mirror.stream.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.antigravity.mirror.stream.transport.TransportId

/**
 * Phase 1 smoke test for the public `mirror-stream` API surface.
 *
 * Verifies the types compile, defaults are sensible, and the sealed hierarchies
 * behave like value types. Does NOT instantiate [MirrorClient] — that needs a
 * `Context` and is exercised once Phase 1.5 wires real implementations behind it.
 */
class MirrorApiSmokeTest : StringSpec({

    "MirrorConfig defaults match design.md §5" {
        val cfg = MirrorConfig()
        cfg.width shouldBe 1280
        cfg.height shouldBe 720
        cfg.fps shouldBe 30
        cfg.bitrateBps shouldBe 8_000_000
        cfg.codec shouldBe Codec.H264_BASELINE
        cfg.transport shouldBe TransportPreference.AUTO
    }

    "MirrorState.Idle is a singleton" {
        // data object equality is referential
        (MirrorState.Idle === MirrorState.Idle) shouldBe true
        MirrorState.Idle.shouldBeInstanceOf<MirrorState>()
    }

    "MirrorState.ReceiversFound carries the receiver list" {
        val r = Receiver("My PC", "192.168.1.50", 8765, TransportId.LAN)
        val state: MirrorState = MirrorState.ReceiversFound(listOf(r))
        state.shouldBeInstanceOf<MirrorState.ReceiversFound>()
        (state as MirrorState.ReceiversFound).receivers shouldBe listOf(r)
    }

    "MirrorError subtypes carry their context" {
        val handshake = MirrorError.HandshakeFailed("incompatible-version")
        handshake.reason shouldBe "incompatible-version"
        handshake.message shouldBe "Handshake rejected: incompatible-version"

        val bye = MirrorError.PeerDisconnected(reason = "user-requested")
        bye.reason shouldBe "user-requested"

        val byeNull = MirrorError.PeerDisconnected(reason = null)
        byeNull.message shouldBe "Peer closed: (no reason)"
    }

    "Receiver equality is structural" {
        val a = Receiver("PC", "10.0.0.1", 8765, TransportId.LAN)
        val b = Receiver("PC", "10.0.0.1", 8765, TransportId.LAN)
        val c = Receiver("PC", "10.0.0.1", 8765, TransportId.MIRACAST)
        (a == b) shouldBe true
        (a == c) shouldBe false
    }

    "TransportPreference enum covers all three modes" {
        TransportPreference.values().toSet() shouldBe setOf(TransportPreference.AUTO, TransportPreference.LAN, TransportPreference.MIRACAST)
    }
})
