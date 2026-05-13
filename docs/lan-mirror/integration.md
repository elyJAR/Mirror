# Mirror Stream Library Integration Guide

This guide describes how to integrate the `mirror-stream` library into another Android project and how to interact with its PC receiver.

## 1. Dependency Setup

The library is published as an Android Archive (AAR). To include it in your project:

### Option A: JitPack (Recommended)
Add the JitPack repository to your root `build.gradle`:
```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```
Then add the dependency in your app `build.gradle`:
```gradle
dependencies {
    implementation 'com.github.elyJAR:Mirror:v1.0.0'
}
```

## 2. Quick Start (10-line Integration)

The `MirrorClient` is designed for minimal boilerplate.

```kotlin
// 1. Initialize the client
val client = MirrorClient(context)

// 2. Discover receivers (LAN + Miracast)
lifecycleScope.launch {
    client.state.collect { state ->
        if (state is MirrorState.ReceiversFound) {
            // Update your UI list
        }
    }
}
client.startDiscovery()

// 3. Connect to a receiver
client.connect(receiver, MirrorConfig(bitrateBps = 4_000_000))

// 4. Forward screen capture consent (from Activity/Fragment)
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
        client.onProjectionGranted(resultCode, data!!)
    }
}
```

## 3. Advanced Features

### Manual Connection
If you already know the IP of the PC (e.g., via a QR code or push notification), you can bypass discovery:
```kotlin
client.connectManual("192.168.1.5", 8765)
```

### Performance Monitoring
Observe the `stats` flow to implement your own HUD or bitrate reporting:
```kotlin
client.stats.collect { stats ->
    Log.d("Mirror", "${stats.fps} FPS, ${stats.bitrateKbps} kbps")
}
```

### Congestion Handling
The library automatically scales bitrate down if the network is congested (queue depth > 15 for 2s). You can disable this by passing a custom `MirrorConfig` if you intend to handle it manually.

## 4. Integration into Background Services
If your app already has a long-running foreground service, you can host `MirrorClient` within it. Ensure you declare `mediaProjection` and `dataSync` foreground service types in your `AndroidManifest.xml`.
