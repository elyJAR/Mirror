# LAN Screen Mirror — Requirements

**Status:** Draft v1 — supersedes the Miracast/WFD path (`.kiro/specs/android-screen-mirror/`) which was blocked by Samsung's third-party Miracast lockdown.

**Purpose:** Mirror an Android phone's screen to a PC over a local IP network using a custom protocol — not Miracast. The Android side captures and encodes the screen; the PC side runs a small receiver app that decodes and renders it.

**Why pivot:** Modern Samsung (and most OEM) firmware blocks third-party apps from initiating Miracast via `WifiP2pManager.connect()` because it requires the `setMiracastMode()` system API. We sidestep this by not using Miracast at all — phone and PC just need IP reachability.

---

## 1. Scope

### 1.1 In scope (v1)

- Real-time mirror of the phone's screen to the PC (video only).
- Three transport modes, all using IP:
  - Same Wi-Fi network (phone and PC on the same router).
  - Phone hotspot (PC connects to phone's tethering AP).
  - PC hotspot (phone connects to PC's hosted network / mobile hotspot).
- Discovery of the PC receiver from the phone (mDNS / manual IP entry).
- One active phone-to-PC session at a time.
- Hardware-accelerated H.264 encode (phone) and decode (PC).
- Foreground service so streaming continues when the app is backgrounded.
- Graceful disconnect, reconnect, and error reporting.

### 1.2 Out of scope (v1, candidates for v2+)

- Audio mirroring.
- Touch-back / remote control (PC → phone input).
- Multi-receiver fan-out.
- End-to-end encryption (assume trusted LAN; v2 should add WireGuard or DTLS-SRTP).
- iOS / Mac receiver.
- Cloud relay for off-LAN mirroring.

### 1.3 Two consumption shapes

This spec is written so both shapes are first-class:

1. **Standalone Mirror app** — the existing Android app + a new Windows receiver app, distributed together.
2. **Embedded Mirror module** — the Android `mirror-stream` module integrated into another Android app (the user's other GitHub project), and the receiver protocol implemented inside that other project's existing PC client.

The Android library API and the wire protocol are designed to be stable and embeddable. UI in the standalone app is a thin shell over the same library.

---

## 2. User stories

### As a phone user

- **U1** I open the Mirror app, tap a single button, and within ~5 seconds I see my phone screen on my PC.
- **U2** If my PC is on the same Wi-Fi, I do not have to type an IP address — the receiver is auto-discovered.
- **U3** If discovery fails, I can manually enter the PC's IP and port.
- **U4** I can mirror over my phone's hotspot when there is no router available.
- **U5** I get a clear, actionable error message when something goes wrong (firewall, receiver not running, wrong network, etc.).
- **U6** I can disconnect at any time from a persistent notification or in-app button.

### As a PC user

- **P1** I install a small receiver (.exe or `.zip`-portable, no admin install) and run it.
- **P2** When the receiver is running it auto-advertises itself on the LAN.
- **P3** When a phone connects, a window opens showing the live screen with low latency (<300 ms target).
- **P4** I can resize and full-screen the window.
- **P5** I see connection status (waiting / connected / disconnected) clearly.
- **P6** Closing the window or quitting the app cleanly stops the session.

### As an integrator (the other project's developer)

- **I1** I can add the Android `mirror-stream` module as a Gradle dependency and start a session in 5–10 lines of Kotlin.
- **I2** I can run the receiver protocol from any language (Python / Node / .NET / C++) by following a documented binary protocol on a single TCP port.
- **I3** I can disable the standalone UI and call the library headlessly from a service.

---

## 3. Functional requirements

### 3.1 Discovery

- **F1.1** PC receiver MUST advertise itself via mDNS using service type `_mirror-stream._tcp.local.` and TXT records for protocol version and capabilities.
- **F1.2** Phone MUST scan for `_mirror-stream._tcp.local.` services on all reachable network interfaces (Wi-Fi, mobile hotspot, USB tether).
- **F1.3** Phone MUST present a list of discovered receivers with hostname and IP.
- **F1.4** Phone MUST allow manual IP+port entry as a fallback when discovery fails (e.g. mDNS blocked by router).
- **F1.5** Discovery MUST NOT require any system or root permissions beyond `INTERNET`.

### 3.2 Session establishment

- **F2.1** Phone MUST initiate a TCP connection to `<receiver-ip>:8765` (default control+stream port).
- **F2.2** Phone and PC MUST exchange a JSON handshake declaring protocol version, encoding parameters (codec, resolution, bitrate, fps), and capabilities (HW decode supported, audio supported, etc.).
- **F2.3** PC MUST accept or reject the handshake and reply with the *negotiated* parameters (it MAY downscale resolution or bitrate).
- **F2.4** A session MUST fail fast with a typed error if: the protocol version is incompatible, the handshake times out (>10 s), or the requested codec is unsupported.

### 3.3 Streaming

- **F3.1** Phone MUST capture the screen using `MediaProjection`, encode with `MediaCodec` H.264 Baseline Profile (default 1280×720 @ 30 fps, 4 Mbps), and emit length-prefixed Annex-B NAL units over the TCP stream.
- **F3.2** Each frame MUST carry a monotonic 64-bit microsecond presentation timestamp.
- **F3.3** Phone MUST emit SPS/PPS at session start and on every IDR (keyframe).
- **F3.4** Phone MUST request a keyframe (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) at session start and on PC request.
- **F3.5** PC MUST decode and render at the negotiated resolution with target glass-to-glass latency ≤ 300 ms on a wired LAN.
- **F3.6** Either side MAY send keepalive ping messages every 5 s; absence of any message for 15 s MUST terminate the session.

### 3.4 Disconnect and recovery

- **F4.1** Either side MUST be able to send a clean `bye` control message; the other side MUST then release all resources within 1 s.
- **F4.2** A TCP socket close MUST terminate the session within 2 s and surface a typed error.
- **F4.3** If the network drops mid-session, the phone MUST attempt up to 3 reconnects with exponential backoff (1, 2, 4 s) before surfacing an error.

### 3.5 Permissions and lifecycle

- **F5.1** Android app MUST request `MediaProjection` consent on every session start (Android requirement, not optional).
- **F5.2** Android app MUST run streaming inside a `FOREGROUND_SERVICE` of type `mediaProjection` with a persistent notification including a Stop action.
- **F5.3** Android app MUST handle `onStop()` of the projection (user revokes consent, screen-off policy, etc.) by terminating the session cleanly.
- **F5.4** PC receiver MUST NOT require admin/root privileges to install or run.

### 3.6 Configuration

- **F6.1** Phone MUST allow the user to set a target bitrate (1 / 2 / 4 / 8 Mbps presets).
- **F6.2** Phone MUST allow the user to set a target resolution capped at the device's native (presets: 720p, 1080p, native).
- **F6.3** PC MUST persist last-used window size and position.
- **F6.4** Settings MUST persist across app restarts (Android `SharedPreferences`, PC application config file).

---

## 4. Non-functional requirements

### 4.1 Performance

- **N1** Glass-to-glass latency target: ≤ 300 ms (wired LAN) / ≤ 600 ms (Wi-Fi 5 GHz) at 720p30.
- **N2** CPU usage on phone: ≤ 25 % of one big core during steady-state 720p30 (assuming HW encode).
- **N3** Receiver memory footprint: ≤ 200 MB during 720p stream.
- **N4** Throughput: must sustain 4 Mbps without dropped frames on a 50 Mbps Wi-Fi link.

### 4.2 Reliability

- **N5** No crash of either app on disconnect, network change, or phone screen-off.
- **N6** No leaked threads, sockets, or `MediaProjection` instances after session end.
- **N7** App MUST recover (manual retry) from any error without a restart.

### 4.3 Compatibility

- **N8** Android: API 24 (Android 7.0) minimum, API 34+ targeted. Tested on Samsung One UI 6+, Pixel, Xiaomi MIUI.
- **N9** PC: Windows 10 (1903+) / Windows 11. Linux receiver is stretch goal.
- **N10** Codec: H.264 Baseline only in v1 (broadest decoder support). H.265 / AV1 in v2+ behind a feature flag.

### 4.4 Security

- **N11** v1 explicitly assumes a trusted LAN. No authentication; no encryption.
- **N12** PC receiver MUST bind only to non-loopback interfaces actually selected by the user (no inadvertent public exposure if the PC is also on a public Wi-Fi).
- **N13** v2 MUST add a one-time pairing PIN exchanged out-of-band (shown on PC, typed on phone) and TLS or Noise-protocol session encryption.

### 4.5 Observability

- **N14** Both sides MUST log at INFO at start/stop of every session and at WARN/ERROR for every recoverable/unrecoverable failure, with enough detail to diagnose without reproducing.
- **N15** Both sides SHOULD expose a debug overlay/HUD (toggleable) showing FPS, bitrate, dropped frames, and round-trip ping.

---

## 5. Acceptance criteria (v1 done = all of these pass)

- **A1** From a cold start: tap *Discover* on the phone, tap the auto-discovered PC, grant projection consent, see the screen on PC within 5 s.
- **A2** Same as A1 but with manual IP entry on the phone (mDNS disabled). Connects within 5 s after IP entered.
- **A3** Phone hotspot mode: PC connects to phone's hotspot, sees receiver still working, mirroring works the same.
- **A4** Stream runs continuously for 30 minutes at 720p30 with no crashes, no memory growth (>50 MB drift), no dropped frames over a 50 Mbps link.
- **A5** Killing the receiver app while streaming surfaces a clean error on the phone within 3 s. Phone returns to a usable Idle state.
- **A6** Killing the phone app while streaming closes the receiver window within 3 s.
- **A7** Toggling phone Wi-Fi off mid-session: phone retries 3× then surfaces a clear error, no crashes either side.
- **A8** Receiver runs on a vanilla Windows 11 install with no admin rights, no extra runtime install (apart from the receiver's bundled deps).
- **A9** The Android `mirror-stream` library can be added to a separate Android project and a session started in ≤ 10 lines of Kotlin per the public API documented in `design.md`.

---

## 6. Open questions / decisions for design.md

- Single TCP socket multiplexing control + video, or separate control + RTP/UDP video sockets? (Default: single TCP for v1 simplicity.)
- Receiver tech: Electron + WebCodecs vs .NET 8 + LibVLCSharp vs Rust + ffmpeg? (See `design.md` §4.)
- Length-prefixed NAL units vs MPEG-TS vs RTP-in-TCP? (Default: length-prefixed NAL units for simplicity.)
- mDNS library on Android: NSD (built-in, flaky on some OEMs) vs jmDNS (bundled, reliable). (Default: NSD primary, jmDNS fallback.)
