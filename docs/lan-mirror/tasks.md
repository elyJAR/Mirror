# LAN Screen Mirror — Task list

**Companion to:** `requirements.md`, `design.md`. Read those first.

Tasks are grouped by phase and ordered so each phase produces something **demoable**. Boxes are intentionally checkable in Markdown (`[ ]` / `[x]`).

Legend:

- **(A)** Android side — work happens in `Mirror/`
- **(P)** PC side — work happens in `receiver-pc/` (or your other project's PC client)
- **(I)** Integrator side — only matters when embedding into your other project
- **(D)** Docs / repo / CI

Where requirements (`Rx.x`) and design (`Dx`) IDs are referenced, they map to sections in those files.

---

## Phase 0 — Repo prep (no code deletion)

Preserve everything that exists today. The Miracast pipeline stays — it'll work on devices where the OEM allows it, and become one of two transports behind the same `MirrorClient` API.

- [ ] **0.1 (A)** Keep all existing code in `app/src/main/java/com/antigravity/mirror/{media,protocol,discovery,service,model,error}` — nothing is deleted in this phase.
- [ ] **0.2 (D)** Tag current `main` as `v0.1-miracast-only` so the pre-pivot snapshot is easy to recover.
- [ ] **0.3 (D)** Update `README.md`:
  - Lead with the dual-transport story.
  - Document that Miracast works on a subset of devices (mostly older or non-Samsung) and LAN works everywhere.
  - Link to `docs/lan-mirror/` for the LAN spec, and keep `.kiro/specs/android-screen-mirror/` (gitignored) as the Miracast spec.
- [ ] **0.4 (D)** `.kiro/` continues to be gitignored; new docs at `docs/lan-mirror/` are committed.

**Phase 0 done when:** repo is tagged, README reflects the dual-transport plan, no code has been moved yet.

---

## Phase 1 — Android library skeleton (`mirror-stream`)

Goal: a new Gradle module with the public API stubbed, no networking yet, no streaming. The `app/` module depends on it and compiles.

- [ ] **1.1 (A)** Create new module `mirror-stream` (Android library, `com.android.library` plugin). `applicationId` is removed; `namespace = com.antigravity.mirror.stream`.
- [ ] **1.2 (A)** Wire `app/build.gradle` to `implementation project(':mirror-stream')`. Update `settings.gradle` to include it.
- [ ] **1.3 (A)** Create the public API package `com.antigravity.mirror.stream.api`:
  - `MirrorConfig.kt` (data class, see `design.md` §5)
  - `MirrorState.kt` (sealed interface)
  - `Receiver.kt` (data class)
  - `MirrorError.kt` (sealed Exception hierarchy, see `design.md` §9)
  - `MirrorClient.kt` — class with all public methods stubbed `TODO()`, exposing `state: StateFlow<MirrorState>`.
- [ ] **1.4 (A)** Move `media/ScreenCaptureEngine.kt` and `media/VideoEncoder.kt` into `mirror-stream/.../media/`. Update imports in `app/`. Tests follow.
- [ ] **1.5 (A)** Move the entire existing Miracast pipeline into `mirror-stream/.../transport/miracast/` **without changing behaviour**:
  - `discovery/DiscoveryManager.kt`
  - `protocol/RtspServer.kt`, `RtspParser.kt`, `WfdSessionManager.kt`, `WfdNegotiator.kt`, `RtpSender.kt`
  - `model/RtspMessage.kt`, `WfdCapabilities.kt`
  - matching tests in `app/src/test/` move to `mirror-stream/src/test/`
  - existing `MirrorService` orchestration logic is **temporarily** kept in `app/` until Phase 2 — it will be split between transport-agnostic session code (moves to `stream/session/`) and the Miracast-specific `WfdSessionManager` (already in `transport/miracast/`).
- [ ] **1.6 (A)** Add a smoke unit test that constructs `MirrorClient` with a mocked `Context` and asserts initial state is `Idle`.
- [ ] **1.7 (D)** Update `.github/workflows/android.yml` so the CI build runs `:mirror-stream:assembleDebug` and `:mirror-stream:test` in addition to `:app`.

**Phase 1 done when:** CI compiles both modules; existing Miracast tests still pass after the move; smoke test on the new `MirrorClient` passes. No public-API behaviour change yet.

---

## Phase 1.5 — Transport abstraction

Goal: introduce the `Transport` interface and adapt the existing Miracast code to implement it, without breaking the standalone app. After this phase, Miracast still works exactly as it did, but is reachable through `MirrorClient` instead of `MirrorService` directly.

- [ ] **1.5.1 (A)** Define `transport/Transport.kt`, `TransportSession.kt`, `TransportTarget.kt`, `TransportEvent.kt`, `TransportId.kt` per `design.md` §5.1.
- [ ] **1.5.2 (A)** Wrap the existing Miracast pipeline in `transport/miracast/MiracastTransport.kt` (implements `Transport`):
  - `startDiscovery()` → wraps existing `DiscoveryManager.discoverPeers()`.
  - `connect(target, config)` → wraps existing `WfdSessionManager` orchestration.
  - Maps existing `WifiP2pManager` reason codes into `MirrorError`.
- [ ] **1.5.3 (A)** Stub `transport/lan/LanTransport.kt` (returns empty discovery and throws `NotImplementedError` on `connect()`). Implementation lands in Phase 2.
- [ ] **1.5.4 (A)** Build `selector/TransportSelector.kt` per `design.md` §6:
  - Reads/writes per-device fingerprint state via DataStore.
  - Returns the chosen `Transport` for an `AUTO` request.
  - Records outcomes after each session attempt.
- [ ] **1.5.5 (A)** Wire `MirrorClient` to use `TransportSelector` + the chosen `Transport`. Public API surface unchanged.
- [ ] **1.5.6 (A)** Refactor the standalone `app/`'s `MainActivity` to call into `MirrorClient` instead of `MirrorService` directly. The existing `MirrorService` becomes a thin foreground-service host owning a `MirrorClient`.
- [ ] **1.5.7 (A)** Manual smoke test: a Pixel device (Miracast-allowed) still connects to Windows Connect via the new path. A Samsung device's allow-list entry is auto-set to `DENIED` after one failed Miracast attempt; subsequent runs skip Miracast immediately.
- [ ] **1.5.8 (A)** Unit tests for `TransportSelector` decision table (§6.1) using fake `Build` values.

**Phase 1.5 done when:** standalone app behaves identically to before for Miracast users; Samsung-class devices auto-fall-back-fast to LAN (which is still a stub); allow-list state persists across restarts.

---

## Phase 2 — LAN wire protocol (Android side, with a fake PC)

Goal: implement the LAN transport's protocol on Android against a mock receiver running locally as a JVM unit-test fixture. No real PC code yet.

- [ ] **2.1 (A)** Add Kotlin `kotlinx.serialization` dependency. Create `transport/lan/protocol/ControlMessage.kt` modelling every JSON message from `design.md` §3.2 as a `@Serializable` sealed class.
- [ ] **2.2 (A)** Create `transport/lan/protocol/Framing.kt`:
  - `suspend fun ByteWriteChannel.writeFrame(tag: Byte, payload: ByteArray)`
  - `suspend fun ByteReadChannel.readFrame(): Frame` — returns `Frame(tag, payload)`
  - Hard-fails on payload > 8 MiB.
  - Uses `java.nio` for big-endian length.
- [ ] **2.3 (A)** Create `transport/lan/protocol/ProtocolClient.kt`:
  - Constructor takes `host: String`, `port: Int`.
  - `suspend fun connect(): Unit` — opens socket, performs handshake, returns when in STREAMING state.
  - `fun videoFrames(): SendChannel<NalUnit>` — encoder pushes here.
  - Internal coroutines: a sender (drains video channel + control queue), a receiver (reads frames, dispatches control), a ping loop, a watchdog.
  - Emits state into a passed-in `MutableStateFlow<MirrorState>`.
- [ ] **2.4 (A)** Implement `request-keyframe` handling — calls into a callback that the encoder layer subscribes to.
- [ ] **2.5 (A)** Implement clean teardown: `bye` sent, socket drained, scope cancelled. No leaked threads under JUnit.
- [ ] **2.6 (A)** Replace the Phase 1.5 stub: `LanTransport` now wires `ProtocolClient` to `TransportSession`, exposes `videoSink`, and forwards events. `MirrorClient` end-to-end works for the LAN path.
- [ ] **2.7 (A)** Build a test fixture `FakeReceiver.kt` (test-only) that opens a `ServerSocket` on a free port, accepts one connection, and exposes hooks (`expectHello()`, `sendHelloAck(...)`, `expectVideoFrames(n)`, `requestKeyframe()`, `closeNormally()`, `crashHard()`).
- [ ] **2.8 (A)** Property-based tests with Kotest covering:
  - Round-trip of every control message type.
  - Framing of payloads of varying sizes (1 B, 1 KiB, 1 MiB, 8 MiB).
  - Reject payload > 8 MiB.
  - Watchdog fires on no-pong for 15 s (use a virtual time test dispatcher).
  - Reconnect logic: 3 attempts, 1/2/4 s backoff, then `Error` with `ProjectionLost`.
- [ ] **2.9 (A)** Connect the encoder's NAL output to `LanTransport`'s `videoSink`. End-to-end JVM test: spin up `FakeReceiver`, drive the LAN protocol with synthetic NAL units, assert all are received.

**Phase 2 done when:** Android-side LAN protocol passes its unit/property tests against a local fake receiver. Both `MIRACAST` and `LAN` transports are now functional via `MirrorClient`. No real PC needed yet.

---

## Phase 3 — Discovery (Android + PC)

Goal: phone finds a fake mDNS advertiser and reports it to the UI.

- [ ] **3.1 (A)** Create `discovery/DiscoveryClient.kt`:
  - Wraps `NsdManager`. Subscribes to `_mirror-stream._tcp.local.`.
  - Exposes `Flow<List<Receiver>>`. Debounces noisy "added/removed" events to a clean snapshot every 500 ms.
  - On Android 13+, use the `executor` overloads (avoid the deprecated handler-less `discoverServices`).
- [ ] **3.2 (A)** Add a `jmDNS` fallback path activated when NSD returns nothing within 3 s. Wrap both behind the same `Flow`.
- [ ] **3.3 (A)** Add a manual-entry path: `MirrorClient.connectManual(host, port)` that skips discovery entirely.
- [ ] **3.4 (A)** Espresso/instrumented test: launch on a device with the standalone receiver running on the same Wi-Fi, assert it appears in the flow within 5 s.
- [ ] **3.5 (P)** *(Skeleton only — full receiver in Phase 4.)* Build a 30-line Node script `receiver-pc/scripts/advertise-only.ts` that publishes the mDNS service on port 8765 and never accepts connections. Run it during 3.4 if no real receiver is built yet.

**Phase 3 done when:** real Android device sees the `advertise-only.ts` service show up in the in-app device list within 5 s.

---

## Phase 4 — PC receiver (Electron + WebCodecs)

Goal: a working receiver that pairs with the Android side end-to-end.

- [ ] **4.1 (P)** Create `receiver-pc/` with Vite + Electron Forge + TypeScript. Add a top-level `package.json` with scripts `dev`, `build`, `package`.
- [ ] **4.2 (P)** Main process (`src/main/`):
  - Window setup with sane defaults (1280×720, resizable, persisted size).
  - mDNS advertiser via `bonjour-service`.
  - TCP server on port 8765 (configurable via env var / settings).
  - Frame parser using a length-prefixed reader on the socket.
  - Forwards control messages and video NAL units to the renderer over IPC.
- [ ] **4.3 (P)** Renderer process (`src/renderer/`):
  - `<canvas>` filling the window, aspect-ratio preserved with letterboxing.
  - `VideoDecoder` (WebCodecs) configured for `avc1.42E01F` (Baseline 3.1).
  - Reconstructs Annex-B before feeding decoder.
  - Reports decoded frame stats back to main process for HUD.
- [ ] **4.4 (P)** Implement the full control protocol from `design.md` §3.2: send `hello-ack`, periodic `ping`, optional `request-keyframe` button in the UI, send `bye` on window close.
- [ ] **4.5 (P)** Status bar:
  - Connected receiver IP / port the phone connected from.
  - Live FPS, kbps, dropped frames.
  - "Disconnected — waiting for phone" idle state.
- [ ] **4.6 (P)** Settings panel: port, advertised name, "bind to specific interface" picker.
- [ ] **4.7 (P)** Cross-platform packaging: `npm run package` produces a portable `.zip` for Windows that runs without installation. Linux `.AppImage` is a stretch goal.
- [ ] **4.8 (P)** Smoke E2E test (manual checklist in `receiver-pc/TESTING.md`): launch receiver, run Android app from Phase 2 fake encoder, observe video on canvas.

**Phase 4 done when:** standalone Android app connects to the receiver, screen appears in the receiver window with usable latency. **A1 (requirements.md §5)** passes manually.

---

## Phase 5 — Standalone Android UI shell

Goal: the user-facing app on the phone polished for v1 release.

- [ ] **5.1 (A)** Refactor `MainActivity` to use `MirrorClient` from `mirror-stream`. Remove all references to `WfdSessionManager`, `RtspServer`, etc.
- [ ] **5.2 (A)** Discover screen: list of `Receiver`s with name/IP, "Refresh" and "Manual entry" actions.
- [ ] **5.3 (A)** Manual entry dialog: `host` + `port` (default 8765) with input validation and recent-hosts dropdown.
- [ ] **5.4 (A)** Settings screen: bitrate (1/2/4/8 Mbps), resolution (720p / 1080p / native), persisted via DataStore.
- [ ] **5.5 (A)** Streaming screen: live preview thumbnail (off-screen `SurfaceView` mirroring the encoder's source), "Disconnect" button, FPS/kbps HUD toggleable.
- [ ] **5.6 (A)** Foreground service notification: title, receiver name, Stop action; survives Activity death.
- [ ] **5.7 (A)** Robust error UI: dialogs for `NetworkUnreachable`, `HandshakeFailed`, `ProjectionDenied`, etc. Recoverable errors get a "Retry" button.
- [ ] **5.8 (A)** `AndroidManifest.xml` permissions stay broadly the same (Miracast permissions remain because Miracast is still a supported transport). Audit:
  - Keep: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `NEARBY_WIFI_DEVICES` (Miracast), `ACCESS_FINE_LOCATION` (legacy Miracast on API 23–30).
  - The runtime-permission flow already requests `NEARBY_WIFI_DEVICES` / location only when the user picks a Miracast-eligible session. LAN-only sessions never trigger those prompts.
- [ ] **5.9 (A)** Add a transport selector to the Settings screen: `Auto` (default) | `Miracast only` | `LAN only`, plus a "Reset Miracast detection" button (§6.3 of design.md).
- [ ] **5.10 (A)** Update app icon and `app_name` if rebranding ("Mirror Lan" vs "Mirror" — your call).

**Phase 5 done when:** A1, A2, A3, A5, A6, A7 from `requirements.md` §5 pass manually on the test device.

---

## Phase 6 — Hardening, perf, observability

- [ ] **6.1 (A)** Backpressure handling: drop oldest non-keyframe + request keyframe when channel queue exceeds 30 frames. Add unit test with a paused fake receiver.
- [ ] **6.2 (A)** Reconnect: implement the 3-attempt 1/2/4 s backoff. Test by toggling the fake receiver's socket.
- [ ] **6.3 (A)** Bitrate adaptation (stretch): if the phone receives `stats` showing high queue depth from itself for >2 s, halve the bitrate via `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE`.
- [ ] **6.4 (A & P)** Debug HUD on both sides — toggle key (Ctrl+H on PC, long-press status bar on phone) shows FPS, bitrate, queue depth, RTT, dropped frames.
- [ ] **6.5 (A & P)** Structured logs: every state transition logs at INFO with session-id; errors at WARN/ERROR with full context.
- [ ] **6.6 (A & P)** Run `requirements.md` A4 (30-min stability test). Profile and fix any leaks discovered.
- [ ] **6.7 (P)** Receiver: handle multiple sequential connections cleanly (current phone disconnects → next phone can connect). Don't allow two concurrent senders in v1 (reject second with `hello-reject reason: busy`).

**Phase 6 done when:** A4, A8 pass; HUD is functional on both sides; logs make a failed session diagnosable from logs alone.

---

## Phase 7 — Integration story for the other project

This phase is only relevant if you go ahead with embedding into your other GitHub project.

- [ ] **7.1 (I)** Publish `mirror-stream` as an artifact:
  - Option A: GitHub Packages Maven via `maven-publish` plugin.
  - Option B: JitPack (`jitpack.io`) — zero CI changes, just a tag.
  - Pick one and document in `docs/lan-mirror/integration.md` (new file).
- [ ] **7.2 (I)** Write `docs/lan-mirror/integration.md`:
  - 10-line happy-path sample (copied from `design.md` §5).
  - How to host `MirrorClient` in your existing service.
  - How to disable the bundled `MirrorForegroundService` and use your own.
  - How to call `connectManual` from a deeplink/push-message in the other app.
- [ ] **7.3 (I)** Mirror the wire protocol into the other project's PC client:
  - If the other PC client is .NET, write `MirrorClient.cs` using `System.Net.Sockets.TcpListener`, `Makaretu.Dns.Multicast`, and `LibVLCSharp`.
  - If it's Node/Electron-based, copy the receiver-pc framing/decoding code as a npm subpackage.
  - If it's Rust, port using `tokio`, `mdns-sd`, and `ffmpeg-next`.
- [ ] **7.4 (I)** Write `docs/lan-mirror/protocol-conformance.md`: a check-list any new receiver implementation must pass (a numbered list of ~30 wire-level expectations covering framing, control messages, video, teardown).
- [ ] **7.5 (I)** Add a conformance test harness: `mirror-stream/src/test/.../ConformanceHarness.kt` exposes a `runAllChecks(receiverHostPort)` that any third-party receiver can be pointed at to validate.

**Phase 7 done when:** the other project can `implementation 'com.elyJAR:mirror-stream:0.1.0'`, integrate in <1 hour, and the conformance harness passes against its receiver.

---

## Phase 8 — v1 release

- [ ] **8.1 (D)** Tag `v1.0.0` on the Android repo. CI builds `app-debug.apk` (you already have this) and additionally `mirror-stream-1.0.0.aar`.
- [ ] **8.2 (D)** Tag `receiver-pc/v1.0.0` (separate tag namespace if it lives in this repo, separate repo if extracted). CI publishes a `Mirror-Receiver-1.0.0-windows.zip`.
- [ ] **8.3 (D)** Write public release notes:
  - What it does (one paragraph).
  - **Explicitly state v1 is LAN-only and unencrypted.**
  - Known limitations (no audio, no input back-channel, no Mac receiver).
  - Quick-start (3 numbered steps for both phone and PC).
- [ ] **8.4 (D)** Add a 30-second screen-capture demo GIF to the README.
- [ ] **8.5 (D)** Decide license — most likely Apache-2.0 or MIT. Add `LICENSE` file at repo root (currently `TBD`).

---

## Stretch / v2 backlog (not part of v1)

Tracked here so they don't get lost; do not start until Phase 8 ships.

- [ ] Audio capture via `AudioPlaybackCapture` + AAC encode → new `AUDIO` tag (`0x03`) in protocol.
- [ ] Pairing PIN + Noise-protocol session encryption.
- [ ] H.265 / AV1 codec negotiation.
- [ ] Touch-back: PC mouse/keyboard → phone via accessibility service or shizuku-style ADB shell.
- [ ] Multi-receiver fan-out (transcode or copy).
- [ ] Off-LAN relay server (separate spec).
- [ ] iOS / macOS receiver port.

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| WebCodecs HW decode missing on some Windows GPUs | Medium | Stutter, high CPU | Fall back to ffmpeg.wasm software decode; expose codec choice in receiver settings. |
| mDNS blocked on corporate / hotspot networks | High | Discovery silently fails | Manual IP entry is mandatory v1 (F1.4). UI surfaces "no devices found — try manual" within 8 s. |
| Phone OEMs throttle background CPU mid-session | Medium | Frame drops | Acquire `WAKE_LOCK`; show user a one-time tip about disabling battery optimisation for the app. |
| Encoder/decoder version mismatch (Baseline vs Main) | Low | Decoder fails to start | Lock encoder profile to Baseline 3.1, verified at hello-ack time. |
| User on public Wi-Fi exposes receiver | Medium | Stranger could connect | Receiver shows IP + port + warning toast at startup; v2 adds PIN. |
| Embedding integrator's existing service conflicts with bundled foreground service | Medium | Crash / dual notifications | Library never auto-starts the service; integrator opts in (D8). Documented. |
| You discover Samsung blocks something else mid-build | Low (LAN path needs no special privs) | Minor | The whole pivot was to get out of OEM-policy territory; this should be quiet. |
