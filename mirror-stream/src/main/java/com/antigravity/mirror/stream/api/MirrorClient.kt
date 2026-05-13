package com.antigravity.mirror.stream.api

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public entry point for screen mirroring.
 *
 * Designed for ≤ 10 lines of consumer code on the happy path:
 * ```
 * val client = MirrorClient(context)
 * client.startDiscovery()
 * // observe client.state — when ReceiversFound, call:
 * client.connect(receivers.first())
 * // when AwaitingProjection, launch the system consent intent and pass the result back:
 * client.onProjectionGranted(resultCode, data)
 * // it transitions to Streaming. To stop:
 * client.disconnect()
 * ```
 *
 * **Status: Phase 1 stub.** All methods are no-ops or throw [NotImplementedError]; the
 * `state` flow is wired up so consumers can already integrate against the surface.
 * Real behaviour is implemented in Phases 1.5 (transport selector wrapping the existing
 * Miracast pipeline) and 2 (LAN transport).
 *
 * Construction is `Context`-typed so the future implementation can:
 *  - access `MediaProjectionManager`,
 *  - read `Build.MANUFACTURER` / `Build.VERSION.SDK_INT` for transport selection,
 *  - persist the per-device Miracast allow-list via DataStore,
 *  - host a foreground service when the consumer opts in.
 *
 * The constructor stores [Context.getApplicationContext] only; it does not retain the
 * caller's Activity.
 */
class MirrorClient(context: Context) {

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    protected val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)

    /** Observable session state. Hot, never replays multiple values per subscriber. */
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    /** Begin discovering receivers across both transports (per allow-list). */
    fun startDiscovery() {
        // Phase 1.5 will wire this to MiracastTransport + LanTransport via TransportSelector.
        notImplemented("startDiscovery")
    }

    /** Stop discovery; safe to call when not discovering. */
    fun stopDiscovery() {
        notImplemented("stopDiscovery")
    }

    /** Connect to a [Receiver] returned in [MirrorState.ReceiversFound]. */
    @Suppress("UNUSED_PARAMETER")
    fun connect(receiver: Receiver, config: MirrorConfig = MirrorConfig()) {
        notImplemented("connect")
    }

    /** Connect manually by host/port (LAN only). Bypasses discovery. */
    @Suppress("UNUSED_PARAMETER")
    fun connectManual(host: String, port: Int = 8765, config: MirrorConfig = MirrorConfig()) {
        notImplemented("connectManual")
    }

    /**
     * Forward the result of the system MediaProjection consent dialog.
     *
     * Call from your launcher callback after starting `MediaProjectionManager.createScreenCaptureIntent()`.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onProjectionGranted(resultCode: Int, data: Intent) {
        notImplemented("onProjectionGranted")
    }

    /** Stop the active session, if any. Idempotent. */
    fun disconnect() {
        notImplemented("disconnect")
    }

    /** Release any retained resources. The instance is unusable afterward. */
    fun release() {
        notImplemented("release")
    }

    private fun notImplemented(method: String): Nothing =
        throw NotImplementedError(
            "MirrorClient.$method is a Phase 1 stub. " +
                "Implementation lands in Phase 1.5 / Phase 2; see docs/lan-mirror/tasks.md."
        )
}
