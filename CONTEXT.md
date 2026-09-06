# CONTEXT.md — LensCast Domain Glossary

Domain language for LensCast: an Android app that turns a phone into a streaming
camera, serving video/audio to browsers (HTTP/MJPEG + Web API) and RTSP clients.

## Terms

### Settings Applier
**`settings/SettingsApplier.kt`** — the single owner of "persisted settings →
runtime" application. It watches `SettingsDataStore` flows and applies new
values to the StreamingManager, CameraService, and Stream Watchdog. Every other
module (ViewModels, WebApiController) only *writes* settings to the store;
nobody else applies persisted settings. Direct runtime calls that are *not*
settings changes (mic arbitration in RecordingService, audio-permission
retrigger in CameraViewModel, camera rebinding) are allowed to bypass it.

### Settings Store
**`data/SettingsDataStore.kt`** — persistence for camera, streaming, watchdog,
auth, and update settings. The only writer-facing surface for configuration:
change a setting by saving it; the Settings Applier reacts.

### Streaming Manager
**`streaming/StreamingManager.kt`** — owns live streaming runtime state (web
MJPEG stream, RTSP server, audio streaming, mDNS). Its setters are its applied
interface; the Settings Applier is their intended caller.

### Stream Watchdog
**`core/StreamWatchdog.kt`** — monitors a live stream and performs recovery
(hard restart choreography) when it stalls.

### Web API Controller
**`streaming/WebApiController.kt`** — JSON-in/JSON-out handlers behind the
streaming server's `/api/*` routes. Writes settings through the Settings Store;
orchestrates stream start/stop.

## Open architecture notes

- A "Streaming Session" module (begin/end choreography: wake lock, thermal,
  camera keep-alive, foreground service, watchdog) is a recognized future
  deepening — the choreography currently exists in WebApiController,
  CameraViewModel, and StreamWatchdog. See the 2026-09-07 architecture review.
- Adaptive bitrate has a known split-brain (undriven `evaluate()` state shown on
  dashboards; live ladders in NetworkQualityMonitor; `estimatedBandwidthKbps`
  displays a constant because nothing updates it). Also from the review.
