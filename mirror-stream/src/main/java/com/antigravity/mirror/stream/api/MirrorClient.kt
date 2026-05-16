package com.antigravity.mirror.stream.api

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.antigravity.mirror.stream.media.NalUnit
import com.antigravity.mirror.stream.media.ScreenCaptureEngine
import com.antigravity.mirror.stream.media.VideoEncoder
import com.antigravity.mirror.stream.media.AudioEncoder
import com.antigravity.mirror.stream.selector.TransportSelector
import com.antigravity.mirror.stream.transport.Transport
import com.antigravity.mirror.stream.transport.TransportId
import com.antigravity.mirror.stream.transport.TransportTarget
import com.antigravity.mirror.stream.transport.TransportSession
import com.antigravity.mirror.stream.transport.TransportEvent
import com.antigravity.mirror.stream.transport.lan.LanTransport
import com.antigravity.mirror.stream.transport.miracast.MiracastTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.pow

private const val TAG = "MirrorApp/MirrorClient"

/**
 * Public entry point for screen mirroring.
 *
 * Designed for ≤ 10 lines of consumer code on the happy path.
 *
 * This implementation coordinates [Transport] implementations, the [TransportSelector] for
 * compatibility heuristics, and the [ScreenCaptureEngine] / [VideoEncoder] media pipeline.
 */
class MirrorClient(context: Context) {

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    protected val appContext: Context = context.applicationContext
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val transportSelector = TransportSelector(appContext)
    
    private val transports: Map<TransportId, Transport> = mapOf(
        TransportId.MIRACAST to MiracastTransport(appContext),
        TransportId.LAN to LanTransport(appContext)
    )

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)

    /** Observable session state. Hot, never replays multiple values per subscriber. */
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    /** Observable performance metrics for the active session. */
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private var inputInjector: ((TransportEvent) -> Unit)? = null

    /**
     * Provide a callback to inject input events (touch/key) into the system.
     * Usually implemented by an AccessibilityService or Shizuku shell.
     */
    fun setInputInjector(injector: (TransportEvent) -> Unit) {
        this.inputInjector = injector
    }

    /** Submit the pairing PIN to the active session. */
    fun submitPin(pin: String) {
        activeSession?.submitPin(pin)
    }

    private var discoveryJob: Job? = null
    private var sessionJob: Job? = null
    
    private var activeSession: TransportSession? = null
    private var captureEngine: ScreenCaptureEngine? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    
    private val discoveredTargets = mutableMapOf<Receiver, TransportTarget>()
    private var lastConfig: MirrorConfig? = null
    
    private var currentBitrateBps = 0
    private var highQueueStartTime = 0L
    private var streamStartMs = 0L

    fun getStreamStartMs() = streamStartMs

    fun pause() {
        captureEngine?.stop()
        // We stay in Streaming state but stop sending frames
    }

    fun resume() {
        val config = lastConfig ?: MirrorConfig()
        captureEngine?.start(config.width, config.height, 160)
        videoEncoder?.requestKeyframe()
    }

    /** Begin discovering receivers across both transports. */
    fun startDiscovery() {
        stopDiscovery()
        _state.value = MirrorState.Discovering
        
        discoveryJob = scope.launch {
            // Combine discovery results from all available transports
            val discoveryFlows = transports.values.map { transport ->
                transport.startDiscovery()
                    .onStart { emit(emptyList()) } // Ensure combine doesn't wait for the first scan
                    .map { targets ->
                        targets.map { target ->
                            val receiver = Receiver(
                                name = target.name,
                                host = target.host,
                                port = target.port,
                                transportId = target.transportId
                            )
                            receiver to target
                        }
                    }
            }

            combine(discoveryFlows) { lists ->
                lists.flatMap { it.toList() }.toMap()
            }.collect { targetsMap ->
                discoveredTargets.clear()
                discoveredTargets.putAll(targetsMap)
                _state.value = MirrorState.ReceiversFound(targetsMap.keys.toList())
            }
        }
    }

    /** Stop discovery; safe to call when not discovering. */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        if (_state.value is MirrorState.Discovering || _state.value is MirrorState.ReceiversFound) {
            _state.value = MirrorState.Idle
        }
    }

    /** Connect to a [Receiver] returned in [MirrorState.ReceiversFound]. */
    fun connect(receiver: Receiver, config: MirrorConfig = MirrorConfig()) {
        val target = discoveredTargets[receiver] ?: run {
            _state.value = MirrorState.Error(MirrorError.NetworkUnreachable(receiver.host), false)
            return
        }
        startSession(target, config)
    }

    /** Connect manually by host/port (LAN only). Bypasses discovery. */
    fun connectManual(host: String, port: Int = 8765, config: MirrorConfig = MirrorConfig()) {
        val target = object : TransportTarget {
            override val name: String = host
            override val host: String = host
            override val port: Int = port
            override val transportId: TransportId = TransportId.LAN
        }
        startSession(target, config)
    }

    private fun startSession(target: TransportTarget, config: MirrorConfig) {
        stopDiscovery()
        lastConfig = config
        currentBitrateBps = config.bitrateBps
        
        sessionJob?.cancel()
        sessionJob = scope.launch {
            var attempts = 0
            val maxAttempts = 3
            
            while (isActive && attempts < maxAttempts) {
                _state.value = MirrorState.Connecting
                try {
                    val transport = transports[target.transportId] ?: throw IllegalStateException("Transport not found")
                    
                    Log.i(TAG, "Starting session via ${target.transportId} to ${target.name} (Attempt ${attempts + 1})")
                    Log.i(TAG, "About to call transport.connect()...")
                    val session = transport.connect(target, config)
                    Log.i(TAG, "transport.connect() returned")
                    activeSession = session
                    Log.i(TAG, "transport.connect() completed successfully")
                    
                    // Collect stats
                    launch {
                        session.stats.collect { stats ->
                            _stats.value = stats
                            handleCongestion(stats)
                        }
                    }

                    Log.i(TAG, "Session established. pairingRequired=${session.pairingRequired}")
                    _state.value = if (session.pairingRequired) {
                        Log.i(TAG, "Setting state to AwaitingPairing")
                        MirrorState.AwaitingPairing
                    } else {
                        Log.i(TAG, "Setting state to AwaitingProjection")
                        MirrorState.AwaitingProjection
                    }
                    Log.i(TAG, "State set. Current state: ${_state.value::class.simpleName}")
                    
                    // Monitor session events (control signals from peer)
                    session.events.collect { event ->
                        when (event) {
                            TransportEvent.RequestKeyframe -> {
                                Log.d(TAG, "Peer requested keyframe")
                                videoEncoder?.requestKeyframe()
                            }
                            is TransportEvent.InjectTouch -> {
                                inputInjector?.invoke(event)
                            }
                            is TransportEvent.InjectKey -> {
                                inputInjector?.invoke(event)
                            }
                            TransportEvent.PairingRequest -> {
                                _state.value = MirrorState.AwaitingPairing
                            }
                            TransportEvent.PairingVerified -> {
                                _state.value = MirrorState.AwaitingProjection
                            }
                            is TransportEvent.PeerDisconnected -> {
                                Log.i(TAG, "Peer disconnected: ${event.reason}")
                                disconnect()
                            }
                            is TransportEvent.Error -> {
                                Log.e(TAG, "Transport error: ${event.cause.message}")
                                _state.value = MirrorState.Error(event.cause, false)
                                disconnect()
                            }
                        }
                    }
                    return@launch // Success!
                    
                } catch (e: Exception) {
                    attempts++
                    if (attempts >= maxAttempts) {
                        Log.e(TAG, "All $maxAttempts connection attempts failed: ${e.message}")
                        _state.value = MirrorState.Error(
                            if (e is MirrorError) e else MirrorError.NetworkUnreachable(target.host, e),
                            true
                        )
                        break
                    }
                    
                    val backoffMs = (2.0.pow(attempts - 1) * 1000).toLong() // 1s, 2s, 4s
                    Log.w(TAG, "Connection attempt $attempts failed: ${e.message}. Retrying in ${backoffMs}ms...")
                    delay(backoffMs)
                }
            }
        }
    }

    /**
     * Forward the result of the system MediaProjection consent dialog.
     *
     * Call from your launcher callback after starting `MediaProjectionManager.createScreenCaptureIntent()`.
     */
    fun onProjectionGranted(resultCode: Int, data: Intent) {
        val session = activeSession ?: run {
            Log.w(TAG, "onProjectionGranted called but no active session")
            return
        }
        val config = lastConfig ?: MirrorConfig()
        
        val mpm = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            _state.value = MirrorState.Error(MirrorError.ProjectionDenied(), false)
            return
        }

        Log.i(TAG, "Projection granted, starting media pipeline")

            try {
                Log.d(TAG, "Creating VideoEncoder with codec=${session.negotiatedCodec} width=${config.width} height=${config.height}")
                val encoder = VideoEncoder(
                    width = config.width,
                    height = config.height,
                    bitrateBps = config.bitrateBps,
                    frameRate = config.fps,
                    mimeType = session.negotiatedCodec
                )
                Log.d(TAG, "VideoEncoder created successfully")
                encoder.configure(config.latencyMode)
                Log.d(TAG, "Creating ScreenCaptureEngine")
                val capture = ScreenCaptureEngine(projection, encoder)
                Log.d(TAG, "ScreenCaptureEngine created successfully")
            
                // Wire the pipeline: Encoder -> TransportSession
                Log.d(TAG, "Setting NAL unit callback")
                capture.setNalUnitCallback { bytes, ts ->
                    session.videoSink.trySend(NalUnit(bytes, ts))
                }
                Log.d(TAG, "NAL unit callback set")
            
                videoEncoder = encoder
                captureEngine = capture
            
                // Start audio capture (Android 10+)
                Log.d(TAG, "Creating AudioEncoder")
                audioEncoder = AudioEncoder(projection).apply {
                    Log.d(TAG, "Starting AudioEncoder with latencyMode=${config.latencyMode}")
                    start(config.latencyMode) { data, pts ->
                        session.audioSink.trySend(data)
                    }
                    Log.d(TAG, "AudioEncoder started")
                }
            
                // Default to 160 DPI
                Log.d(TAG, "Starting screen capture with DPI=160")
                capture.start(config.width, config.height, 160)
                Log.d(TAG, "Screen capture started successfully")
                Log.i(TAG, "Media pipeline initialized, transitioning to Streaming state")
                streamStartMs = System.currentTimeMillis()
                _state.value = MirrorState.Streaming
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize media pipeline: ${e.message}", e)
                _state.value = MirrorState.Error(MirrorError.EncoderFailure(e), false)
                disconnect()
            }
    }

    /** Stop the active session, if any. Idempotent. */
    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        
        scope.launch {
            activeSession?.close("User disconnected")
            activeSession = null
            
            captureEngine?.stop()
            videoEncoder?.stop()
            audioEncoder?.stop()
            
            captureEngine = null
            videoEncoder = null
            audioEncoder = null
            
            _state.value = MirrorState.Idle
        }
    }

    /** Release any retained resources. The instance is unusable afterward. */
    fun release() {
        disconnect()
        scope.cancel()
    }

    /** Helper to load [MirrorConfig] from app [SharedPreferences]. */
    fun loadConfigFromPrefs(prefs: android.content.SharedPreferences): MirrorConfig {
        val bitrate = prefs.getString("bitrate", "12000000")?.toIntOrNull() ?: 12_000_000
        val resolution = prefs.getString("resolution", "1080p") ?: "1080p"
        val transportMode = prefs.getString("transport_mode", "auto") ?: "auto"
        
        val (w, h) = if (resolution == "720p") 1280 to 720 else 1920 to 1080
        
        val transport = when (transportMode) {
            "miracast" -> TransportPreference.MIRACAST
            "lan" -> TransportPreference.LAN
            else -> TransportPreference.AUTO
        }
        
        return MirrorConfig(
            width = w,
            height = h,
            bitrateBps = bitrate,
            transport = transport
        )
    }

    private fun handleCongestion(stats: SessionStats) {
        if (stats.queueDepth > 15) {
            if (highQueueStartTime == 0L) highQueueStartTime = System.currentTimeMillis()
            if (System.currentTimeMillis() - highQueueStartTime > 2000) {
                val nextBitrate = (currentBitrateBps * 0.7).toInt().coerceAtLeast(500_000)
                if (nextBitrate < currentBitrateBps) {
                    Log.w(TAG, "Congestion detected (queue depth ${stats.queueDepth}). Scaling bitrate down to $nextBitrate bps")
                    videoEncoder?.setBitrate(nextBitrate)
                    currentBitrateBps = nextBitrate
                }
                highQueueStartTime = 0
            }
        } else {
            highQueueStartTime = 0
        }
    }
}
