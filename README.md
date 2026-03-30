# LensCast

An Android camera application with live video/audio streaming to web browsers over WiFi. LensCast turns your Android device into a networked camera station with professional controls, remote web access, interval photography, and scheduled recording.

## Features

### Camera
- Live preview with professional quick settings bar
- Manual controls: exposure compensation, ISO, white balance, focus mode, zoom
- Multi-lens support (ultra-wide, wide, telephoto)
- HDR, image stabilization, resolution (up to 4K), and frame rate controls
- Photo capture and video recording

### Live Streaming
- Real-time M-JPEG video streaming to any web browser on the same WiFi network
- Live audio streaming with configurable bitrate, channels, and echo cancellation
- Optional HTTP Basic Authentication for secure access
- Embedded web UI built with SolidJS for full remote control

### Capture
- One-tap quick photo and video capture
- Interval/time-lapse photography with configurable interval (1–3600s), total captures, JPEG quality, and flash mode
- Scheduled video recording with quality presets, duration limits, and repeat intervals
- Capture history tracking

### Gallery
- Grid layout with chronological sections
- Filter by media type (All, Photos, Videos)
- Selection mode for batch operations
- Full-screen media viewer with swipe navigation
- Share and delete capabilities

### Monitoring
- Device thermal state monitoring with automatic quality adaptation
- Battery and wake lock management for long-duration sessions

## Tech Stack

**Android**
- Kotlin, Jetpack Compose, Material 3
- CameraX, WorkManager, DataStore, NanoHTTPD
- MVVM architecture with StateFlow

**Web UI**
- SolidJS, TailwindCSS, DaisyUI, Vite, TypeScript

## Requirements

- Android 8.0 (API 26) or later
- WiFi connection for streaming

## Building

```bash
# Build the Android app
./gradlew assembleDebug

# Build the web UI (outputs to app/src/main/assets/web/)
cd web && npm install && npm run build
```

## Project Structure

```
app/src/main/java/com/raulshma/lenscast/
├── camera/          Camera preview, controls, and CameraX integration
├── capture/         Photo/video capture, interval scheduling, recording
├── gallery/         Media gallery and viewer
├── streaming/       HTTP server, video/audio streaming, web API
├── settings/        App settings UI and persistence
├── navigation/      Compose navigation graph
├── core/            Power management, thermal monitoring, network utils
├── data/            DataStore settings, MediaStore integration
└── ui/              Theme and shared components

web/                 SolidJS web UI for remote control
```

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.
