# CONTEXT.md — LensCast Domain Glossary

Domain language for LensCast: an Android app that turns a phone into a streaming
camera, serving video/audio to browsers (HTTP/MJPEG + Web API) and RTSP clients.

## Terms

### Streaming Session
**`streaming/StreamingSession.kt`** — the single owner of the live-stream
choreography: wake lock, thermal monitoring, battery optimization, camera
keep-alive, foreground service, and watchdog. Interface is `begin()` (call once
the stream started; idempotent), `end()` (tears down only when no stream is
live), and `refreshAfterRecovery()` (watchdog hard recovery). WebApiController,
CameraViewModel, and StreamWatchdog are one-call clients — none of them hand-roll
the choreography anymore. The session also owns the periodic battery-optimization
poll that used to live in CameraViewModel.

### Settings Applier
**`settings/SettingsApplier.kt`** — the single owner of "persisted settings →
runtime" application. It watches `SettingsDataStore` flows and applies new
values to the StreamingManager, CameraService, and Stream Watchdog. Every other
module (ViewModels, WebApiController) only *writes* settings to the store;
nobody else applies persisted settings. Direct runtime calls that are *not*
settings changes (session begin/end via the Streaming Session, mic arbitration
in RecordingService, audio-permission retrigger in CameraViewModel, camera
rebinding) are allowed to bypass it.

### Settings Store
**`data/SettingsDataStore.kt`** — persistence for camera, streaming, watchdog,
auth, and update settings. The only writer-facing surface for configuration:
change a setting by saving it; the Settings Applier reacts. Settings screens read
these flows directly (via `stateIn` in SettingsViewModel) — no mirror state.

### Streaming Manager
**`streaming/StreamingManager.kt`** — owns live streaming runtime state (web
MJPEG stream, RTSP server, audio streaming, mDNS). Its thermal monitor and its
single WebApiController are constructor-owned; `StreamingServer` instances
*receive* the controller at construction — the transport layer never grows its
own. Its setters are its applied interface; the Settings Applier is their
intended caller. Stream-audio changes funnel through one internal policy:
live-update when the running server supports it, restart when it doesn't.

### Adaptive Bitrate Controller
**`streaming/AdaptiveBitrateController.kt`** — the single adaptive-bitrate
brain. The frame path (`pushFrame`) drives it; it applies NetworkQualityMonitor's
quality ladders and publishes the *driven* `AdaptiveState` that the app
dashboard and `/api/status` display. Bandwidth numbers are measured client
throughput — 0 when no client is connected; no invented constants.

### Stream Watchdog
**`core/StreamWatchdog.kt`** — monitors a live stream and performs recovery
(hard restart choreography) when it stalls. Recovery re-attaches the environment
through the Streaming Session's `refreshAfterRecovery()`.

### Web API Controller
**`streaming/WebApiController.kt`** — JSON-in/JSON-out handlers behind the
streaming server's `/api/*` routes. Writes settings through the Settings Store;
starts/stops streams through the Streaming Session. App-scoped: one instance,
owned by the Streaming Manager, with an explicit `close()`.

### Connectivity Monitor
**`core/ConnectivityMonitor.kt`** — app-scoped observer of Wi-Fi connectivity.
One network callback, exposed as `isWifiConnected: StateFlow`. Callers observe
the flow; nobody takes one-shot connectivity snapshots.

### Stream Defaults
**`core/StreamDefaults.kt`** — the single home for stream configuration
defaults (ports, JPEG quality, fps, audio config, watchdog limits). The store,
manager, encoders, and Web API DTOs all reference it; default literals are
never re-copied.

## Open architecture notes

- The WebApiController is still a God module (five domains behind one file:
  settings DTO mapping, status aggregation, stream lifecycle, capture/snapshot,
  gallery/media). Splitting it along those domains is a recognized future
  deepening.
- RecordingService's state is bare flags plus companion statics polled by the
  Web API; an explicit `RecordingState` flow on the service is a recognized
  future deepening. Same review.
- RecordingService binds camera use cases by pulling six CameraService getters
  and re-implementing the combination ladder; a `bindRecording()` seam on
  CameraService would fix it, device-verified. Same review.
