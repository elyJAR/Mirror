# Mirror

[![Android CI](https://github.com/elyJAR/Mirror/actions/workflows/android.yml/badge.svg)](https://github.com/elyJAR/Mirror/actions/workflows/android.yml)

An Android app that mirrors your phone's screen to a Windows PC over your local network. **Two transports** are supported under one UI:

- **Miracast / Wi-Fi Display** — streams to the built-in Windows **Connect** app or any Miracast sink. No PC-side install. Works on devices whose firmware allows third-party Miracast initiation.
- **LAN (custom protocol)** — streams H.264 over plain TCP to a small custom PC receiver. Works on **every** Android 7+ device because it only needs `INTERNET`. *(Status: spec complete in `docs/lan-mirror/`, implementation in progress.)*

The app picks the right transport automatically and falls back gracefully on devices where Miracast is blocked by OEM policy (e.g. recent Samsung One UI). The user can also force a transport in Settings.

> **Why two transports?** Modern Samsung firmware (and some other OEMs) silently rejects third-party `WifiP2pManager.connect()` calls to Miracast sinks because it requires the system-only `setMiracastMode()` API. The LAN transport sidesteps that by not using Miracast at all. See `docs/lan-mirror/requirements.md` for the full story.

---

## Features

- **Dual transport** with automatic selection and fallback
  - **Miracast** — to Windows *Connect*, smart TVs, Miracast dongles
  - **LAN** — to a custom PC receiver (Electron + WebCodecs, planned)
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
2. `LanTransport` discovers receivers via mDNS (`_mirror-stream._tcp.local.`).
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

## Project Structure

Current layout (pre-Phase-1; everything still in `app/`). The `mirror-stream` library will be split out per `docs/lan-mirror/tasks.md` Phase 1.

```text
Mirror/
├── .github/workflows/android.yml   CI: build + unit tests on every push
├── .kiro/specs/                    Miracast spec (gitignored, local Kiro tool)
├── docs/lan-mirror/                LAN-transport spec (committed)
│   ├── requirements.md
│   ├── design.md
│   └── tasks.md
├── app/
│   └── src/main/java/com/antigravity/mirror/
│       ├── ui/           MainActivity, DeviceAdapter
│       ├── service/      MirrorService, MirrorState, PermissionManager,
│       │                 ConnectionTypeSelector
│       ├── discovery/    DiscoveryManager (Wi-Fi Direct)
│       ├── media/        ScreenCaptureEngine, VideoEncoder
│       ├── protocol/     RtspServer, RtspParser, WfdSessionManager,
│       │                 WfdNegotiator, RtpSender
│       ├── model/        WfdCapabilities, RtspMessage, MirrorSession
│       └── error/        AppError
├── build.gradle
├── settings.gradle
└── gradle/wrapper/
```

---

## Build & Run

### Prerequisites

- Android Studio Giraffe or newer
- JDK 17
- An Android device running Android 5.0+ with screen-capture support
- A Windows 10/11 PC with the **Connect** app open (`Win` → type `Connect`)
  - Enable *Projecting to this PC* in `Settings → System → Projecting to this PC`

### Build debug APK

```powershell
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

### Install on a connected device

```powershell
.\gradlew.bat installDebug
```

### Run tests

```powershell
# JVM unit tests
.\gradlew.bat test

# Instrumented tests (device or emulator required)
.\gradlew.bat connectedAndroidTest
```

---

## Usage

### Miracast (current `main` behaviour)

1. On the PC, open the **Connect** app and make sure *Projecting to this PC* is enabled.
2. Connect the phone and PC to the same network (Wi-Fi, Wi-Fi Direct, or phone hotspot).
3. Launch **Mirror** on the phone and grant the requested permissions.
4. Tap **Discover Devices** and wait for the PC to appear in the list.
5. Tap the PC name, then grant the screen-capture permission when prompted.
6. Your screen now appears in the Connect window on the PC.
7. To stop, tap **Disconnect** in the app or dismiss the persistent notification.

> **Note:** On recent Samsung devices (One UI 6+ on Android 14) the Miracast `connect()` call is rejected by the framework. The LAN transport (below) is the workaround.

### LAN (planned, see `docs/lan-mirror/`)

1. Run the PC receiver app on a Windows / Linux PC on the same network.
2. Phone auto-discovers the receiver via mDNS, or enter `host:port` manually.
3. Tap connect; grant screen-capture consent; mirroring starts.

The receiver app and the LAN transport implementation are coming next — see `docs/lan-mirror/tasks.md`.

---

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | RTP/RTSP sockets |
| `ACCESS_NETWORK_STATE` | Detect active network type |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Wi-Fi Direct management |
| `CHANGE_NETWORK_STATE` | Network binding |
| `ACCESS_FINE_LOCATION` | Required for Wi-Fi Direct discovery on Android 8–11 |
| `NEARBY_WIFI_DEVICES` | Replaces location permission for Wi-Fi Direct on Android 12+ |
| `FOREGROUND_SERVICE` | Keep streaming service alive in background |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required on Android 14+ for screen capture services |
| `WAKE_LOCK` | Prevent CPU sleep during active streaming |
| `MediaProjection` (runtime) | Screen capture consent — requested on session start |

---

## Releases

APKs are published automatically to [GitHub Releases](https://github.com/elyJAR/Mirror/releases) whenever a version tag is pushed.

To cut a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The release job will:
1. Run all unit tests
2. Build a debug APK and an unsigned release APK
3. Create a GitHub Release named `Mirror v1.0.0` with both APKs attached and auto-generated release notes

Tags containing a hyphen (e.g. `v1.0.0-beta`) are published as pre-releases.

---

## Status

Active development. CI builds and unit tests run on every push to `main`.

**Roadmap:**

- [x] Miracast / WFD pipeline (M1–M7 RTSP, RTP/UDP video, foreground service).
- [x] Miracast fixes for OEM-quirky `WifiP2pManager.connect()` (commit `25390a5`).
- [x] LAN-transport spec (`docs/lan-mirror/`).
- [x] Tag `v0.1-miracast-only` archived as the pre-pivot snapshot.
- [ ] Phase 1: extract `mirror-stream` library module.
- [ ] Phase 1.5: introduce `Transport` interface; wrap Miracast behind it; add allow-list selector.
- [ ] Phase 2: implement LAN transport (TCP framing, JSON control, NAL streaming).
- [ ] Phase 3–4: mDNS discovery + Electron PC receiver.
- [ ] Phase 5–7: UI polish, hardening, integration story for the sibling project.
- [ ] v1.0 release.

See `docs/lan-mirror/tasks.md` for the full plan.

---

## License

TBD.
