# Mirror

[![Android CI](https://github.com/elyJAR/Mirror/actions/workflows/android.yml/badge.svg)](https://github.com/elyJAR/Mirror/actions/workflows/android.yml)

An Android app that mirrors your phone's screen to a Windows PC over Wi-Fi, Wi-Fi Direct, or a mobile hotspot — using the built-in **Connect** (Wireless Display) app on Windows. No extra PC-side software, no USB cable, no vendor lock-in.

Mirror implements the **Miracast / Wi-Fi Display (WFD)** protocol stack directly on the phone, so it works against any Miracast-compatible receiver (Windows `Connect` app, smart TVs, Miracast dongles) regardless of phone manufacturer.

---

## Features

- **Three connection modes**
  - Same Wi-Fi network (phone and PC on the same LAN)
  - Wi-Fi Direct (peer-to-peer, no router required)
  - Phone hotspot (PC connects to the phone)
- **Miracast-compatible** — targets the Windows 10/11 `Connect` app out of the box
- **Hardware-accelerated H.264 encoding** via `MediaCodec`
- **RTP/RTSP streaming** with a standards-compliant WFD negotiator
- **Foreground service** keeps the mirror session alive when the app is backgrounded
- **Device discovery UI** to pick a receiver from a scanned list
- **Graceful error handling** with descriptive messages and retry options

---

## Architecture

```
ui/            MainActivity + device picker (RecyclerView)
service/       Foreground MirrorService, state machine, permissions,
               connection-type selection
discovery/     Wi-Fi Direct / hotspot / LAN peer discovery
media/         ScreenCaptureEngine (MediaProjection) + VideoEncoder (MediaCodec H.264)
protocol/      RTSP server, RTSP parser, WFD capability negotiator,
               WFD session manager (M1–M7), RTP packetizer/sender
model/         MirrorSession, WfdCapabilities, RtspMessage
error/         AppError sealed hierarchy
```

High-level flow:

1. User picks a connection mode and a target device.
2. `MirrorService` (foreground) requests `MediaProjection` permission.
3. `ScreenCaptureEngine` feeds frames into `VideoEncoder` (H.264 Baseline Profile).
4. `RtspServer` accepts the receiver's RTSP session and `WfdNegotiator` agrees on resolution / codec parameters (M1–M7 handshake).
5. Encoded NAL units are packetized by `RtpSender` and streamed over UDP to the receiver.

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

```
Mirror/
├── .github/
│   └── workflows/
│       └── android.yml       # CI: build + unit tests on every push
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/antigravity/mirror/
│       │       ├── ui/           MainActivity, DeviceAdapter
│       │       ├── service/      MirrorService, MirrorState, PermissionManager,
│       │       │                 ConnectionTypeSelector
│       │       ├── discovery/    DiscoveryManager
│       │       ├── media/        ScreenCaptureEngine, VideoEncoder
│       │       ├── protocol/     RtspServer, RtspParser, WfdSessionManager,
│       │       │                 WfdNegotiator, RtpSender
│       │       ├── model/        WfdCapabilities, RtspMessage, MirrorSession
│       │       └── error/        AppError
│       ├── test/                 JVM unit tests (Kotest + MockK)
│       └── androidTest/          Instrumented tests (Espresso)
├── build.gradle
├── settings.gradle
└── gradle/
    └── wrapper/
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

1. On the PC, open the **Connect** app and make sure *Projecting to this PC* is enabled.
2. Connect the phone and PC to the same network (LAN, Wi-Fi Direct, or phone hotspot).
3. Launch **Mirror** on the phone and grant the requested permissions.
4. Tap **Discover Devices** and wait for the PC to appear in the list.
5. Tap the PC name, then grant the screen-capture permission when prompted.
6. Your screen now appears in the Connect window on the PC.
7. To stop, tap **Disconnect** in the app or dismiss the persistent notification.

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

## Status

Active development — CI builds and unit tests run automatically on every push. See the badge at the top for current build status.

---

## License

TBD.
