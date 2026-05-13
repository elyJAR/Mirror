# Mirror

[![Android CI](https://github.com/elyJAR/Mirror/actions/workflows/android.yml/badge.svg)](https://github.com/elyJAR/Mirror/actions/workflows/android.yml)

An Android app that mirrors your phone's screen to a Windows PC over your local network. **Two transports** are supported under one UI:

- **Miracast / Wi-Fi Display** — streams to the built-in Windows **Connect** app or any Miracast sink. No PC-side install. Works on devices whose firmware allows third-party Miracast initiation.
- **LAN (custom protocol)** — streams H.264 over plain TCP to a small custom PC receiver. Works on **every** Android 7+ device because it only needs `INTERNET`. *(Status: v1.0.0-pre released!)*

The app picks the right transport automatically and falls back gracefully on devices where Miracast is blocked by OEM policy (e.g. recent Samsung One UI). The user can also force a transport in Settings.

> **Why two transports?** Modern Samsung firmware (and some other OEMs) silently rejects third-party `WifiP2pManager.connect()` calls to Miracast sinks because it requires the system-only `setMiracastMode()` API. The LAN transport sidesteps that by not using Miracast at all. See `docs/lan-mirror/requirements.md` for the full story.

---

## Features

- **Dual transport** with automatic selection and fallback
  - **Miracast** — to Windows *Connect*, smart TVs, Miracast dongles
  - **LAN** — to a custom PC receiver (Electron + WebCodecs)
- **Three IP-network modes** (used by both transports where applicable)
  - Same Wi-Fi network (router)
  - Phone hotspot (PC connects to phone's hotspot)
  - PC hotspot (phone connects to PC's hosted network)
- **Hardware-accelerated H.264 encoding** via `MediaCodec`
- **Foreground service** keeps the mirror session alive when the app is backgrounded
- **Device discovery UI** — mDNS for LAN, Wi-Fi Direct peer discovery for Miracast
- **Graceful error handling** with descriptive messages and retry options
- **Per-device Miracast allow-list** — the app remembers which devices' firmware allows Miracast and skips it on devices where it's known to fail

---

## Architecture

Two transports, one library, one UI:

```text
  app/  (UI shell)            → depends on →   mirror-stream  (library)
                                                  ├─ api/         MirrorClient, MirrorConfig, MirrorState
                                                  ├─ media/       ScreenCaptureEngine + VideoEncoder (H.264)
                                                  ├─ transport/
                                                  │    ├─ miracast/   Wi-Fi Direct + RTSP M1–M7 + RTP/UDP
                                                  │    └─ lan/        TCP + length-prefixed NAL units (port 8765)
                                                  ├─ selector/    Allow-list + auto-fallback
                                                  └─ session/     Transport-agnostic state machine
```

**High-level flow (LAN):**

1. User taps *Connect*; `TransportSelector` checks the allow-list.
2. `LanTransport` discovers receivers via mDNS (`_mirror._tcp.local.`).
3. `MirrorService` (foreground) requests `MediaProjection` consent.
4. `ScreenCaptureEngine` feeds frames into `VideoEncoder` (H.264 Baseline).
5. NAL units (with PTS) are framed and sent over a single TCP connection to the PC receiver.

**High-level flow (Miracast):**

1. User taps *Connect*; `TransportSelector` decides Miracast is allowed for this device.
2. `MiracastTransport` discovers Wi-Fi Direct peers and runs the WFD M1–M7 RTSP handshake.
3. After `MediaProjection` consent, encoded NAL units are RTP-packetised and streamed over UDP to the sink.

For the full LAN spec see `docs/lan-mirror/{requirements,design,tasks}.md`.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.22 |
| Build | Gradle 8.4, Android Gradle Plugin 8.2.2 |
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 (Android 14) |
| Async | Kotlin Coroutines + Flow |
| UI | AndroidX AppCompat, Material Components, RecyclerView, ViewBinding |
| Testing | JUnit 4, Kotest 5.8 (property-based), MockK, kotlinx-coroutines-test, Espresso |

---

## Usage

### Miracast

1. On the PC, open the **Connect** app and make sure *Projecting to this PC* is enabled.
2. Connect the phone and PC to the same network (Wi-Fi, Wi-Fi Direct, or phone hotspot).
3. Launch **Mirror** on the phone and grant the requested permissions.
4. Tap **Discover Devices** and wait for the PC to appear in the list.
5. Tap the PC name, then grant the screen-capture permission when prompted.

### LAN (v1.0.0-pre)

1. Run the PC receiver app (`receiver-pc`) on a Windows / Linux PC on the same network.
2. Phone auto-discovers the receiver via mDNS, or enter `host:port` manually via the "Manual Connection" menu.
3. Tap connect; grant screen-capture consent; mirroring starts.
4. Use the settings menu to adjust bitrate (1-8 Mbps) and resolution (720p/1080p).

See `RELEASE_NOTES.md` for performance tips and limitations.

---

## Status

Active development. v1.0.0-pre is now available with full LAN transport support.

**Roadmap:**

- [x] Miracast / WFD pipeline (M1–M7 RTSP, RTP/UDP video, foreground service).
- [x] LAN-transport spec (`docs/lan-mirror/`).
- [x] Phase 1: extract `mirror-stream` library module.
- [x] Phase 2: implement LAN transport (TCP framing, JSON control, NAL streaming).
- [x] Phase 3–4: mDNS discovery + Electron PC receiver.
- [x] Phase 5–7: UI polish, hardening, integration story for the sibling project.
- [x] v1.0.0-pre release.
- [ ] v2.0: Audio support, pairing PIN, H.265/AV1.

---

## License

MIT License. See `LICENSE` file for details.
