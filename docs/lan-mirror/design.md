# LAN Screen Mirror — Design

**Companion to:** `requirements.md`. Read that first.

This document fixes the architecture, wire protocol, and module boundaries so both the standalone Mirror app and the integration into your other project use the **same** Android library and the **same** wire protocol.

The library is **dual-transport**: an existing Miracast/WFD implementation (preserved from the original spec) and a new LAN implementation, both behind a single `Transport` interface. The default `MirrorClient` picks one automatically based on the device allow-list and falls back gracefully.

---

## 1. High-level architecture

```text
   PHONE (Android)                                    PC (Windows / Linux)
   ┌────────────────────────────────────────┐         ┌───────────────────────────────────────┐
   │  app (UI shell)                        │         │  receiver-app (UI shell)            │
   │   └── depends on ──┐                   │         │   └── depends on ──┐                │
   │                    ▼                   │         │                    ▼                │
   │  mirror-stream                         │         │  mirror-receiver                    │
   │   ├── api/  (MirrorClient, public API) │         │   ├── DiscoveryAdvertiser (mDNS)    │
   │   ├── media/ ScreenCapture + Encoder   │         │   ├── ProtocolServer (port 8765)    │
   │   ├── transport/                       │         │   ├── H264Decoder (WebCodecs)       │
   │   │    ├─ LanTransport (TCP, this spec) │ ◄─TCP─► │   └── Renderer (canvas)             │
   │   │    └─ MiracastTransport (WFD)      │ ◄─WFD─► ┌───────────────────────────────────────┐
   │   ├── selector/ (allow-list, fallback) │         │  Windows Connect app (built-in)     │
   │   └── session/ SessionStateMachine     │         │  / any Miracast sink                │
   └────────────────────────────────────────┘         └───────────────────────────────────────┘

   LAN transport: router / phone hotspot / PC hotspot
   Miracast transport: Wi-Fi Direct (when supported by device firmware)
```

Three clean layers per side:

- A **library module** that owns capture/encode, both transports, and the session state machine. No UI dependencies.
- A **shell app** that provides UX, settings persistence, and OS integration (foreground service notification on Android, window management on PC).
- The **transport layer** is pluggable. Two implementations ship in v1: `MiracastTransport` (preserved from the original WFD work) and `LanTransport` (new). Both implement the same `Transport` interface and feed encoded NAL units the same way.

The library module on Android (`mirror-stream`) is the artifact your other project will consume.

---

## 2. Module layout

### 2.1 Android repo (`Mirror/`)

```text
Mirror/
├── app/                      Standalone UI (existing module, refactored to use api/)
│   └── src/main/java/com/antigravity/mirror/ui/...
└── mirror-stream/            Library, zero UI deps
    └── src/main/java/com/antigravity/mirror/stream/
        ├── api/              public API surface
        │                       MirrorClient, MirrorConfig, MirrorState, Transport (enum)
        ├── media/            ScreenCaptureEngine, VideoEncoder (moved from app/)
        ├── transport/        Transport interface + implementations
        │   ├── lan/          LanTransport: ProtocolClient, mDNS DiscoveryClient,
        │   │                 framing, control + video frame codec
        │   └── miracast/     MiracastTransport: existing Wi-Fi Direct + WFD/RTSP/RTP
        │                       (DiscoveryManager, RtspServer, WfdSessionManager,
        │                        WfdNegotiator, RtpSender) moved here intact
        ├── selector/         TransportSelector: allow-list + auto-fallback
        └── session/          SessionStateMachine (transport-agnostic)
```

Public API contract (`api` package) is **the** thing the integrator depends on. It MUST stay stable across patch versions; breaking changes go in a major bump.

Existing classes in `app/src/main/java/com/antigravity/mirror/{media,protocol,discovery,service,model,error}` are **migrated** (not deleted) into `mirror-stream`:

- `media/` → `stream/media/` (transport-agnostic, used by both transports).
- `discovery/DiscoveryManager.kt`, `protocol/Rtsp*`, `protocol/Wfd*`, `protocol/RtpSender.kt`, `model/Rtsp*`, `model/Wfd*` → `stream/transport/miracast/` (the entire existing Miracast pipeline, kept as-is and put behind the `Transport` interface).
- `error/AppError.kt` → `stream/api/MirrorError.kt` (extended, see §9).
- `service/ConnectionTypeSelector.kt` is repurposed and renamed `selector/TransportSelector.kt` (§6).

### 2.2 PC repo (this same `Mirror/` repo, sibling folder)

```text
Mirror/
├── app/                      Android standalone UI
├── mirror-stream/            Android library
└── receiver-pc/              NEW: PC receiver
    ├── src/                  language-specific (see §4)
    ├── package.json | *.csproj | Cargo.toml
    └── README.md
```

Or, if you'd rather keep PC in your other project's repo, this folder becomes a thin reference implementation and the canonical receiver lives there. Both work — the protocol is the contract.

---

## 3. Wire protocol (LAN transport only)

The Miracast transport uses the standard Wi-Fi Display / RTSP M1–M7 + RTP wire format — documented in the existing `.kiro/specs/android-screen-mirror/design.md`. This section describes only the new LAN transport's wire format.

Single TCP connection per session. Default port **8765**. Phone is the client; PC is the server.

### 3.1 Frame envelope

Every message on the wire is one of two kinds, distinguished by a one-byte tag:

```text
  +------+------------------+--------------------+
  | tag  | length (4 bytes) | payload (length B) |
  | 1 B  | u32 big-endian   |                    |
  +------+------------------+--------------------+

  tag = 0x01  CONTROL  payload = UTF-8 JSON
  tag = 0x02  VIDEO    payload = NAL unit + 8-byte PTS prefix
```

Endianness: **big-endian** for all multi-byte integers (network byte order).

Maximum payload size: **8 MiB** (anything larger MUST be rejected as malformed).

### 3.2 Control messages (tag = 0x01, JSON)

All control messages have a `type` discriminator. Forward-compatible: receivers MUST ignore unknown fields and SHOULD ignore unknown `type` values.

#### 3.2.1 `hello` — phone → PC, opens the session

```json
{
  "type": "hello",
  "protocolVersion": 1,
  "client": { "name": "Mirror Android", "version": "1.0.0", "platform": "android-14" },
  "video": {
    "codec": "h264",
    "profile": "baseline",
    "width": 1280,
    "height": 720,
    "fps": 30,
    "bitrateBps": 4000000
  },
  "capabilities": ["video"]
}
```

#### 3.2.2 `hello-ack` — PC → phone, accepts (possibly with adjusted params)

```json
{
  "type": "hello-ack",
  "protocolVersion": 1,
  "server": { "name": "Mirror Receiver", "version": "1.0.0", "platform": "win-11" },
  "video": {
    "codec": "h264",
    "width": 1280,
    "height": 720,
    "fps": 30,
    "bitrateBps": 4000000
  }
}
```

If `video.*` differs from the phone's request, the phone MUST reconfigure the encoder before sending any video frame. If reconfiguration is impossible, the phone MUST send `bye` with reason `unsupported-config` and close.

#### 3.2.3 `hello-reject` — PC → phone, refuses

```json
{ "type": "hello-reject", "reason": "incompatible-version", "message": "..." }
```

`reason` ∈ `incompatible-version | busy | unsupported-codec | malformed-hello`.

#### 3.2.4 `request-keyframe` — PC → phone

```json
{ "type": "request-keyframe" }
```

Phone MUST request an IDR from the encoder within 1 frame.

#### 3.2.5 `ping` / `pong` — either direction

```json
{ "type": "ping", "id": 42 }
{ "type": "pong", "id": 42 }
```

Sent every 5 s. No `pong` for 15 s ⇒ teardown.

#### 3.2.6 `stats` — phone → PC, optional, every 1 s

```json
{ "type": "stats", "fps": 29.8, "bitrateKbps": 3850, "queueDepth": 0, "droppedFrames": 0 }
```

#### 3.2.7 `bye` — either direction

```json
{ "type": "bye", "reason": "user-requested" }
```

`reason` is informational. Receiver MUST close socket within 1 s of receiving or sending `bye`.

### 3.3 Video frames (tag = 0x02)

Payload layout:

```text
  +-----------+--------------------------+
  | pts (8 B) | nal_unit (length-8 B)    |
  | u64 µs    | Annex-B body (no 0x...01)|
  +-----------+--------------------------+
```

- **One NAL unit per VIDEO message.** Don't pack multiple NALs.
- **Annex-B start codes are stripped** before sending — the length prefix is the framing. Receivers reconstruct Annex-B (`0x00 0x00 0x00 0x01` + NAL) before feeding to ffmpeg / WebCodecs / MF.
- **PTS** is monotonic microseconds since session start (not since boot, not Unix epoch).
- The phone MUST emit SPS (NAL type 7) and PPS (NAL type 8) **before the first IDR**, and again before every IDR. Many decoders need them inline, not just at start.

### 3.4 State machine

Both sides track the same states. Diagram (phone side; PC mirrors it):

```text
  IDLE
   │  user taps connect
   ▼
  CONNECTING ── tcp connect fail ───────────► ERROR ──► IDLE
   │  socket open
   ▼
  HANDSHAKING ── timeout / hello-reject ────► ERROR ──► IDLE
   │  hello-ack
   ▼
  AWAITING_PROJECTION  (Android only)
   │  user grants
   ▼
  STREAMING ── network drop / bye ──────────► ERROR or RECONNECTING
   │
   ▼
  RECONNECTING (3 attempts, 1/2/4 s backoff)
   │
   └─► STREAMING (success)  or  ERROR (3 fails)
```

PC side has no `AWAITING_PROJECTION`; everything else mirrors.

---

## 4. PC receiver — technology choice

Three viable stacks. Pick one for v1; the protocol is identical so any can be swapped later.

| Stack | Pros | Cons |
|---|---|---|
| **Electron + WebCodecs** | Cross-platform free; modern HW-accelerated H.264 decode via WebCodecs API; familiar JS/TS; easy UI; small enough installer | ~80 MB install; needs Chromium WebCodecs (Electron 20+) |
| **.NET 8 WPF + LibVLCSharp** | Native Windows feel; mature ffmpeg-based decode; single .exe via self-contained publish | Windows-first; larger app footprint with bundled libvlc; .NET runtime ergonomics |
| **Rust + ffmpeg + winit/SDL2** | Smallest binary; fastest startup; cross-platform | Most code to write; no UI framework included; trickier for non-Rust contributors |

**Recommendation for v1: Electron + WebCodecs.** Reasons:

- WebCodecs gives true HW-accelerated H.264 decode in Chromium with a 30-line API.
- A `<canvas>` is the simplest possible renderer.
- Trivial to add controls, settings, and a debug HUD.
- Cross-platform out of the box — Linux/Mac receivers come "free."
- Hot-reloadable during development (Vite + Electron Forge).

Skeleton:

```ts
// receiver-pc/src/main.ts (Electron main process)
import { app, BrowserWindow } from 'electron'
import net from 'node:net'
import { Bonjour } from 'bonjour-service'

const PORT = 8765

new Bonjour().publish({
  name: 'Mirror Receiver',
  type: 'mirror-stream',
  protocol: 'tcp',
  port: PORT,
  txt: { v: '1' },
})

const server = net.createServer((socket) => {
  // ... parse framed messages, forward video frames over IPC to renderer
})
server.listen(PORT)

app.whenReady().then(() => {
  const win = new BrowserWindow({ width: 1280, height: 720 })
  win.loadFile('renderer/index.html')
})
```

```ts
// receiver-pc/src/renderer/decoder.ts (renderer process)
const decoder = new VideoDecoder({
  output: (frame) => {
    canvasCtx.drawImage(frame, 0, 0)
    frame.close()
  },
  error: (e) => console.error(e),
})
decoder.configure({ codec: 'avc1.42E01F', optimizeForLatency: true })

// when a NAL unit arrives via IPC:
decoder.decode(new EncodedVideoChunk({
  type: nalType === 5 ? 'key' : 'delta',
  timestamp: ptsMicros,
  data: annexBBytes,
}))
```

If your other project is .NET-based, the same protocol drops into LibVLCSharp instead — see `tasks.md` Phase 4 alternate path.

---

## 5. Android library API

### 5.1 Transport abstraction (internal)

```kotlin
// com.antigravity.mirror.stream.transport
interface Transport {
    /** Begin discovering peers. Emits a flow of human-friendly target descriptors. */
    fun startDiscovery(): Flow<List<TransportTarget>>

    /** Establish a session to [target]. Suspends until the session reaches STREAMING. */
    suspend fun connect(target: TransportTarget, config: MirrorConfig): TransportSession

    /** Identifier used by selector for allow-list and logs. */
    val id: TransportId  // LAN | MIRACAST
}

interface TransportSession {
    /** Push encoded H.264 NAL units. Capture-side calls this. */
    val videoSink: SendChannel<NalUnit>

    /** Side-channel events from the peer (request keyframe, bye, errors). */
    val events: Flow<TransportEvent>

    suspend fun close(reason: String)
}
```

Both `LanTransport` and `MiracastTransport` implement this. `MirrorClient` holds an active `TransportSession`, knows nothing about which transport is in use, and just routes encoded frames into `videoSink`. The encoder, capture, foreground service, and state machine are entirely transport-agnostic.

### 5.2 Public API surface

The integrator-facing API. Designed to be ≤ 10 lines for a happy path.

```kotlin
// com.antigravity.mirror.stream.api
data class MirrorConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateBps: Int = 4_000_000,
    val codec: Codec = Codec.H264_BASELINE,
    /**
     * Which transport to use.
     *  - AUTO    : try Miracast if device is allow-listed (§6), otherwise LAN.
     *  - LAN     : force LAN; never attempt Miracast.
     *  - MIRACAST: force Miracast; fail if it doesn't connect (no fallback).
     */
    val transport: Transport = Transport.AUTO,
)

enum class Transport { AUTO, LAN, MIRACAST }

sealed interface MirrorState {
    data object Idle : MirrorState
    data object Discovering : MirrorState
    data class ReceiversFound(val receivers: List<Receiver>) : MirrorState
    data object Connecting : MirrorState
    data object AwaitingProjection : MirrorState
    data object Streaming : MirrorState
    data object Reconnecting : MirrorState
    data class Error(val cause: MirrorError, val recoverable: Boolean) : MirrorState
}

data class Receiver(val name: String, val host: String, val port: Int)

class MirrorClient(context: Context) {
    val state: StateFlow<MirrorState>

    fun startDiscovery()
    fun stopDiscovery()
    fun connect(receiver: Receiver, config: MirrorConfig = MirrorConfig())
    fun connectManual(host: String, port: Int = 8765, config: MirrorConfig = MirrorConfig())

    /** Call from your Activity after MediaProjection consent is granted. */
    fun onProjectionGranted(resultCode: Int, data: Intent)

    fun disconnect()
    fun release()
}
```

Happy path for an integrator:

```kotlin
val client = MirrorClient(applicationContext)
client.startDiscovery()
// observe client.state — when ReceiversFound, call:
client.connect(receivers.first())
// observe state — when AwaitingProjection, launch the system consent intent:
val mpm = getSystemService(MediaProjectionManager::class.java)
projectionLauncher.launch(mpm.createScreenCaptureIntent())
// in the launcher callback:
client.onProjectionGranted(result.resultCode, result.data!!)
// it transitions to Streaming. To stop:
client.disconnect()
```

The standalone `app/` module is itself a consumer of this API — it doesn't have any privileged access to the library internals.

---

## 6. Transport selection (allow-list and fallback)

`TransportSelector` decides which `Transport` to try for a given session.

### 6.0 Inputs

- `Build.MANUFACTURER`, `Build.MODEL`, `Build.VERSION.SDK_INT`.
- Persistent allow-list state per device fingerprint (DataStore): `UNKNOWN | ALLOWED | DENIED`.
- User override from settings (`Auto | Miracast only | LAN only`).
- `MirrorConfig.transport` from the integrator.

### 6.1 Decision table (when `MirrorConfig.transport = AUTO`)

| Device fingerprint state | Action |
|---|---|
| `ALLOWED` | Try Miracast. On any failure within 5 s, demote to `DENIED` and fall back to LAN. |
| `DENIED` | Skip Miracast entirely. Use LAN. |
| `UNKNOWN` | Use built-in heuristic: if `manufacturer == "google"` AND `sdkInt <= 33` → try Miracast, else `DENIED`. Update state based on outcome. |

### 6.2 Failure detection (Miracast)

A Miracast attempt is considered a synchronous failure (and the device demoted) if any of:

- `WifiP2pManager.connect()` returns `onFailure(BUSY | P2P_UNSUPPORTED)`.
- `WIFI_P2P_CONNECTION_CHANGED_ACTION` does not arrive within 5 s.
- Logcat tag `WifiP2pService` emits `Dropping connect request` (best-effort, since reading own logs is restricted; we use the 5 s no-broadcast heuristic instead).

### 6.3 User-visible behaviour

- During a fallback the UI shows *"Wireless Display unavailable on this device — switching to PC receiver…"* once, then proceeds to LAN discovery silently.
- A *Reset Miracast detection* button in advanced settings clears the persistent state, useful after a system update.

---

## 7. LAN discovery (mDNS)

### 7.1 mDNS service definition

- Service type: `_mirror-stream._tcp.local.`
- TXT records:
  - `v=1` — protocol version
  - `name=...` — friendly name shown to user (defaults to PC hostname)
  - `caps=video` — capability list, comma-separated; future: `video,audio,input`

### 7.2 Android discovery

Primary: `android.net.nsd.NsdManager` (built-in, no extra deps). Known to be flaky on some OEMs (especially Samsung when the network is a phone hotspot).

Fallback: bundle [jmDNS](https://github.com/jmdns/jmdns) (~150 KB) and use it when NSD returns no results within 3 s. jmDNS has fewer compatibility issues but uses more CPU.

### 7.3 PC advertising

Electron: [`bonjour-service`](https://www.npmjs.com/package/bonjour-service) (pure JS, no native deps).
.NET: `Makaretu.Dns.Multicast` or `Tmds.MDns`.
Rust: `mdns-sd`.

### 7.4 Manual entry

Phone UI MUST always offer manual `host:port` entry as a fallback. Some networks (corporate Wi-Fi, certain routers, Windows guest networks) block multicast.

---

## 8. Threading model (Android library)

Three coroutine scopes, all `SupervisorJob`-rooted, all on `Dispatchers.IO` except where noted.

- `discoveryScope` — owns NSD and jmDNS lifecycles. Cancelled on `stopDiscovery()` / `release()`.
- `protocolScope` — owns the TCP socket, framing, control message handler, ping/pong loop. One scope per session.
- `encoderScope` — owns `MediaCodec` callback dispatching. Encoder runs on its own dedicated `HandlerThread` (MediaCodec requirement); coroutines just bridge into the channel.

State updates flow into a single `MutableStateFlow<MirrorState>` exposed as read-only `StateFlow`. No other observable surfaces.

The encoder → network bridge is a **bounded** `Channel<NalUnit>(capacity = 30)`. Backpressure: if the channel fills (network slower than encoder), drop the **oldest non-keyframe** and request a keyframe. Don't block the encoder.

---

## 9. Foreground service

`mirror-stream` provides a `MirrorForegroundService` class consumers can register in their manifest, OR the integrator can host the `MirrorClient` in their own service. The library itself does not auto-start a service — that's the consumer's choice (matters for embedding into your other project, which may already have its own service).

The standalone `app/` module registers the bundled service.

Notification:
- Title: *"Mirror — connected to {receiverName}"*
- Stop action that calls `MirrorClient.disconnect()`
- Tap → opens the standalone activity (or a no-op when embedded headless)

---

## 10. Errors

Single typed hierarchy in `mirror-stream`:

```kotlin
sealed class MirrorError(message: String) : Exception(message) {
    class NetworkUnreachable(host: String) : MirrorError("Cannot reach $host")
    class HandshakeFailed(val reason: String) : MirrorError("Handshake rejected: $reason")
    class ProtocolViolation(detail: String) : MirrorError("Protocol violation: $detail")
    class EncoderFailure(cause: Throwable) : MirrorError("Encoder error: ${cause.message}")
    class ProjectionDenied : MirrorError("User denied screen capture consent")
    class ProjectionLost : MirrorError("Screen capture stopped by the system")
    class PeerDisconnected(val reason: String?) : MirrorError("Peer closed: $reason")
    class Timeout(stage: String) : MirrorError("Timeout during $stage")
}
```

Every public state-flow `Error` carries one of these. UI maps them to user-facing strings; integrators can surface them however they like.

---

## 11. Versioning and compatibility

- **`protocolVersion`** in `hello` / `hello-ack` is an integer that increments on any breaking wire change. v1 = 1.
- Receivers SHOULD support N-1 (so a v2 receiver still talks to v1 phones for one release cycle).
- Library `api` package follows semver. Internal packages are not stable.
- Wire protocol changes go through a short ADR in `docs/lan-mirror/adr/` before merging.

---

## 12. Security posture (v1) and threat model

Explicit non-goals for v1, called out so reviewers don't assume otherwise:

- **No authentication.** Anyone on the LAN who finds the receiver port can connect.
- **No encryption.** All frames are in cleartext on the wire.
- **No identity binding.** A receiver can't tell two phones apart.

This is acceptable for a v1 LAN-only tool with the default port and zero exposure to the internet, but **must** be highlighted in the README and in the receiver's UI ("connected without encryption").

v2 plan (separate spec):
- Out-of-band PIN displayed on PC, typed on phone, mixes into a Noise-XX or TLS-PSK key.
- AEAD-encrypted frames over the same TCP socket.
- Receiver remembers paired phones by their public key.

---

## 13. Out-of-scope items deferred to later versions

| Feature | Why deferred |
|---|---|
| Audio mirroring | Needs `AudioPlaybackCapture` (API 29+) and a separate AAC encoder; doubles the protocol surface area. |
| Touch-back | Needs uinput on PC (admin) or a custom kernel driver; major scope. |
| H.265 / AV1 | Decoder support not universal in WebCodecs as of 2026; revisit when stable. |
| Multi-receiver | Encoder is single-output; would need to copy frames or transcode. |
| Off-LAN (relay) | Requires hosted infra and proper auth; whole separate product. |
