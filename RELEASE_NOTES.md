# Release Notes — v1.0.0 (Pre-release)

This is the initial pre-release of the Mirror LAN Protocol library and receiver.

## Overview
Mirror is a low-latency, high-performance screen mirroring solution for local networks. It consists of an Android library (`mirror-stream`) and a cross-platform Electron-based PC receiver (`receiver-pc`).

## Key Features
- **Zero-Config Discovery**: Automatic PC detection via mDNS.
- **Low Latency**: Optimized H.264 pipeline with WebCodecs for sub-100ms glass-to-glass delay.
- **Congestion Control**: Dynamic bitrate adaptation and backpressure handling to maintain stability on busy Wi-Fi networks.
- **Developer Friendly**: 10-line integration for Android apps.

## ⚠️ Important Limitations
- **LAN-Only**: This version is designed for use on local networks. Discovery and streaming will not work across the public internet or complex firewalls without a VPN.
- **Unencrypted**: The wire protocol is currently unencrypted. Do not use this over untrusted public Wi-Fi networks.
- **No Audio**: v1 supports video mirroring only. Audio support is planned for v2.
- **No Input Back-channel**: You cannot control the phone from the PC in this version.

## Quick Start
### On your PC:
1. Extract `Mirror-Receiver.zip`.
2. Run `Mirror Receiver.exe`.
3. Press `Ctrl+H` to toggle the performance HUD.

### In your Android App:
1. Grant the screen capture permission when prompted.
2. Select your PC from the discovery list.
3. Long-press the "Streaming" status bar to view performance metrics.

## License
MIT License
