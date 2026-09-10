# LensCast 0.1.1

Changes since 0.1.0.

## Streaming & web
- H.265 (HEVC) video for RTSP alongside H.264
- ONVIF Profile S device service (off by default) for NVR discovery (Home Assistant etc.)
- HTTPS mode with a self-signed on-device certificate, fingerprint shown for one-tap verification
- Low-latency WebCodecs playback in the dashboard, automatic fallback ladder to MJPEG and HLS (muxed A/V, iOS-friendly)
- In-browser microphone talkback
- WebDAV backup
- Remote camera controls and streaming client management from the web dashboard

## Detection & automation
- ML object detection gate: MediaPipe Tasks Vision + EfficientDet-Lite0 (Apache-2.0), model downloaded on demand with a pinned SHA-256
- MQTT alerting, tamper detection, local alerts
- Detection statistics: 24 h / 7 d / all-time counts, trends, top labels, CSV/JSON export
- Quiet hours for notifications; day-of-week arming schedules; adaptive noise floor and record-on-sound audio detection
- Loop recording with retention policy
- Boot resume of running sessions
- In-app detection event feed with notification deep links
- Telegram backup

## Web API
- Audit log ring buffer for API mutations and auth events, exposed via the web API
- System diagnostics endpoint (`/api/system`): device, battery, OS, storage

## Changed
- ML stack migrated to MediaPipe Tasks Vision 1.0.0 (16 KB page-size aligned); 32-bit x86 dropped
- JSON stack migrated from moshi-kotlin reflection to Moshi codegen (KSP)
- Fixed RTSP audio restart storms (audio config writes coalesced)
- Fixed SSE EventSource freezing at load (responses no longer auto-gzipped)
- Fixed web-audio talkback hanging when autoplay is suspended until a user gesture
- versionCode now increases strictly every release — update prompts work again
- Gradle wrapper 9.7.1; dependency security updates
- New F-Droid build flavor without the in-app self-updater — updates arrive via F-Droid
