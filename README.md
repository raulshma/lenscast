# LensCast

An Android camera application with live video/audio streaming to web browsers over WiFi. LensCast turns your Android device into a networked camera station with professional controls, remote web access, interval photography, and scheduled recording.

## Features

### Camera
- Live preview with professional quick settings bar
- Manual controls: exposure compensation, ISO, white balance, focus mode, zoom
- Multi-lens support (ultra-wide, wide, telephoto) with runtime lens switching
- HDR, image stabilization, resolution (up to 4K), and frame rate controls (15–60 fps)
- Photo capture and video recording
- Night vision / IR mode (Off, Auto, On) for low-light environments
- Scene modes (Face Detection, Night, HDR, Sunset, Fireworks, Action, Portrait, and more)
- Focus distance control for manual focus mode
- Color temperature adjustment (2000K–9000K) for manual white balance

### Live Streaming
- Real-time M-JPEG video streaming to any web browser on the same WiFi network
- RTSP streaming with H.264 video and AAC audio encoding for use with VLC, OBS, and other RTSP clients
- Live audio streaming with configurable bitrate (32–320 kbps), channels (mono/stereo), and echo cancellation
- Adaptive bitrate control that dynamically adjusts JPEG quality and frame rate based on network quality and thermal state
- Stream overlays with configurable timestamp, branding text, custom text, and viewer count display
- Privacy masking zones with blackout, pixelate, or blur effects on user-defined regions
- Optional HTTP Basic Authentication for secure access (web and RTSP)
- mDNS / NSD service discovery so clients can find the stream on the local network automatically
- Configurable streaming port and JPEG quality
- Network quality monitoring with per-client throughput tracking and quality level classification (Excellent → Critical)
- Foreground service with persistent notification to keep streaming alive in the background

### Web UI (Remote Control Dashboard)
- Full remote camera control dashboard built with SolidJS, Tailwind CSS v4, and DaisyUI
- Live stream preview with start/stop/resume controls for both web (M-JPEG) and RTSP streams
- Remote camera settings: exposure, focus, white balance, zoom, resolution, frame rate, HDR, stabilization, night vision, and scene mode
- Streaming settings: port, JPEG quality, adaptive bitrate toggle, web/RTSP enable/disable, audio configuration
- Stream overlay configuration: timestamp, branding, custom text, position, font size, colors
- Privacy masking editor: create, position, resize, and configure masking zones (blackout/pixelate/blur)
- Interval capture controls: interval, total captures, quality, capture mode, flash mode
- Video recording controls with scheduled recording via time picker, quality presets, duration limits, and repeat intervals
- Connection quality indicator with real-time bandwidth, throughput, latency, and per-client stats
- Remote media gallery with thumbnail grid, full-screen viewer, and file downloads
- HTTP Basic Authentication login screen
- Cinematic dark-themed glassmorphism design with micro-animations

### Capture
- One-tap quick photo and video capture
- Interval/time-lapse photography with configurable interval (1–3600s), total captures, JPEG quality, capture mode, and flash mode
- Scheduled video recording with quality presets (High/Medium/Low), duration limits, repeat intervals, and optional audio
- Capture history tracking persisted via DataStore
- Video recording as a foreground service for reliability

### Gallery
- Grid layout with chronological sections
- Filter by media type (All, Photos, Videos)
- Selection mode for batch operations
- Full-screen media viewer with horizontal pager navigation and shared element transitions
- Video thumbnail extraction via Coil video decoder
- Share and delete capabilities
- Also accessible remotely from the Web UI with thumbnail grid and download support

### Monitoring & Power Management
- Device thermal state monitoring (Normal → Critical) with automatic quality and frame rate adaptation
- Battery level monitoring with tiered optimization: auto-reduces quality, bitrate, and resolution as battery drops
- Power save mode detection and Doze mode awareness
- Wake lock management for long-duration streaming and recording sessions
- Battery optimization exemption request for uninterrupted background operation

### Networking
- Network change monitoring with real-time connectivity state tracking (WiFi, Cellular, Ethernet, Bluetooth, VPN)
- Automatic stream URL generation based on device local IP
- Per-client connection statistics with throughput, latency, and frames-per-second tracking
- HTTP Range request support for video file streaming in the web gallery

## Tech Stack

**Android**
- Kotlin, Jetpack Compose, Material 3
- CameraX (camera2 backend), WorkManager, DataStore Preferences, NanoHTTPD
- MVVM architecture with Kotlin Coroutines and StateFlow
- Moshi for JSON serialization, Coil for image/video loading
- Custom RTSP server with H.264/AAC RTP packetization

**Web UI**
- SolidJS, Tailwind CSS v4, DaisyUI v5, Vite, TypeScript
- Built output embedded directly into Android assets at build time

## Requirements

- Android 8.0 (API 26) or later
- WiFi connection for streaming
- Camera and microphone permissions
- Node.js 20+ and npm (for building the web UI)
- JDK 17 (for building the Android app)

## Permissions

LensCast requires the following Android permissions:

| Permission | Purpose |
|---|---|
| `CAMERA` | Camera preview and capture |
| `RECORD_AUDIO` | Live audio streaming and video recording with audio |
| `INTERNET` | Serving the HTTP/RTSP streams |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Detecting network connectivity and IP address |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS service discovery |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` / `FOREGROUND_SERVICE_MICROPHONE` | Background streaming and recording |
| `WAKE_LOCK` | Keeping the device awake during long sessions |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Requesting Doze mode exemption |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Accessing captured media in gallery |
| `POST_NOTIFICATIONS` | Foreground service notifications |

## Building

### Prerequisites

- Android Studio or Gradle CLI
- JDK 17
- Node.js 20+ with npm

### Build Commands

```bash
# Build the web UI and Android app together (Gradle builds the web UI automatically)
./gradlew assembleDebug

# Or build them separately:

# 1. Build the web UI (outputs to app/src/main/assets/webui/)
cd web && npm install && npm run build

# 2. Build the Android APK
./gradlew assembleDebug

# Build a signed release APK (requires keystore environment variables)
# KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD must be set
./gradlew assembleRelease
```

### Web UI Development

```bash
cd web
npm install
npm run dev    # Starts dev server on port 3000 with proxy to Android device
```

The Vite dev server proxies `/api`, `/stream`, `/audio`, and `/snapshot` requests to `localhost:8080` for local development against a running LensCast instance.

## CI/CD

A GitHub Actions workflow (`.github/workflows/release.yml`) automates release builds:
- Triggers on pushes to `v*` or `release/**` branches, or via manual dispatch
- Builds a signed release APK with automatic semantic version tagging
- Publishes per-ABI APKs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, universal) to GitHub Releases

## Project Structure

```
app/src/main/java/com/raulshma/lenscast/
├── camera/          Camera preview, controls, CameraX integration, and lens management
│   └── model/       Camera settings, state, overlay settings, and lens info models
├── capture/         Photo/video capture, interval scheduling, recording service
│   └── model/       Capture history and recording config models
├── gallery/         Media gallery grid, viewer with pager, and media management
├── streaming/       HTTP server, MJPEG/audio/RTSP streaming, web API controller
│   ├── model/       Web API DTOs
│   └── rtsp/        RTSP server, H.264 encoder, AAC encoder, RTP packetizers
├── settings/        Camera settings and app settings screens with ViewModels
├── navigation/      Compose navigation graph with shared element transitions
├── core/            Power management, thermal monitoring, network monitoring
├── data/            DataStore settings persistence, capture history store
└── ui/              Theme, shared components, and animation utilities

web/                 SolidJS web UI for remote control
├── src/
│   ├── components/  Dashboard components (stream, settings, overlay, gallery, etc.)
│   ├── api/         HTTP API client
│   ├── hooks/       Application state management and utilities
│   └── types.ts     TypeScript type definitions and constants
└── vite.config.ts   Build config (outputs to Android assets)
```

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.

