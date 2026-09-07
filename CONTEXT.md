# CONTEXT.md — LensCast Domain Glossary

Domain language for LensCast: an Android app that turns a phone into a streaming
camera, serving video/audio to browsers (HTTP/MJPEG + Web API) and RTSP clients.

## Terms

### Streaming Session
**`streaming/StreamingSession.kt`** — the single owner of the live-stream
choreography: wake lock, thermal monitoring, battery optimization, camera
keep-alive, foreground service, and watchdog. Interface is `begin()` (call once
the stream started; idempotent), `end()` (tears down only when no stream is
live), and `refreshAfterRecovery()` (watchdog hard recovery). Web API Stream
Handler, CameraViewModel, and StreamWatchdog are one-call clients — none of
them hand-roll the choreography anymore.

### Settings Applier
**`settings/SettingsApplier.kt`** — the single owner of "persisted settings →
runtime" application. It watches `SettingsDataStore` flows and applies new
values to the StreamingManager, CameraService, and Stream Watchdog. Every other
module (ViewModels, Web API Settings Handler) only *writes* settings to the
store; nobody else applies persisted settings. Direct runtime calls that are
*not* settings changes (session begin/end via the Streaming Session, mic
arbitration in RecordingService, camera rebinding) are allowed to bypass it.
One documented second application path exists for camera controls: gesture /
quick-setting updates apply to the CameraService *before* persisting (for
responsiveness) and the Applier re-applies after persistence — camera-control
application is idempotent, so the overlap is harmless and intended.

### Settings Store
**`data/SettingsDataStore.kt`** — persistence for camera, streaming, watchdog,
auth, and update settings. The only writer-facing surface for configuration:
change a setting by saving it; the Settings Applier reacts. Each setting is
share-in'd exactly once, inside the store, and exposed as a `StateFlow` —
ViewModels and Web API handlers read those flows directly, no re-wrapping and
no re-typed defaults. Settings screens consume them via SettingsViewModel —
no mirror state.

### Recording Controller
**`capture/RecordingController.kt`** — the single owner of recording state and
start/stop/schedule choreography *and* of the bounded-recording policy: a
config with `durationSeconds` auto-stops and optionally repeats, no matter
which client started it (camera screen, capture screen, Web API) and
independent of any screen's lifetime. Its `RecordingState` flow (Idle /
Scheduled / Recording with startedAtMs, config, finalizing) is the only public
truth about recording: CameraViewModel, CaptureViewModel, and the Web API
Recording Handler all observe it, and none keep optimistic copies. The
RecordingService is the truth source — it reports transitions through the
controller's `onService*` methods, and the controller owns intent construction
and delay-until scheduling.

### Recording Service
**`capture/RecordingService.kt`** — the foreground service holding the live
recording. Stateless to consumers: every transition is reported to the
Recording Controller. Camera binding goes through CameraService's
`bindRecording()` seam — it never touches the provider or the use-case getters.

### Photo Capture
**`capture/PhotoCaptureManager.kt`** — owns the photo choreography (acquire
use case → take photo → record in history → release). Interface: `captureToGallery()`
(callback-based) and `captureSnapshot(saveToDisk)` (suspend, returns JPEG
bytes). CameraViewModel, CaptureViewModel, and the Web API Capture Handler are
one-call clients. The save destinations (MediaStore vs legacy file) live
behind one internal `PhotoDestination` seam and one `takePictureAwait`
primitive serving disk and memory; a single bounded Main-hop acquire seam
serves both entry points.

### Transport Responders
**`streaming/HttpAuthFilter.kt`**, **`streaming/StaticAssetStore.kt`**,
**`streaming/MjpegStreamPump.kt`**, **`streaming/MediaResponder.kt`** — the
deep responders behind the Streaming Server's dispatch table. Each answers
as `HttpResult` data (status, mime, headers, payload — with `jsonError` /
`plainText` factories for the common shapes); the server only
translates values onto NanoHTTPD responses and applies the security headers.
The Auth Filter translates the four `/api/auth/` routes plus the
authenticate-then-CSRF gate onto the Web Auth Gate; the Asset Store owns the
LRU cache, `..`/NUL path rejection, mime table, and index-then-control-page
fallback; the MJPEG Pump owns frame state, client bookkeeping, the enabled
flag, and the multipart stream; the Media Responder owns gallery files with
video ranges, snapshots, and the PCM audio stream.

### Streaming Manager
**`streaming/StreamingManager.kt`** — owns live streaming runtime state (web
MJPEG stream, RTSP server, audio streaming, mDNS). Its thermal monitor and its
Web API stack are constructor-owned; `StreamingServer` instances *receive* the
`WebApiStack` and the `NetworkQualityMonitor` at construction — the transport
layer never grows its own handlers. Its setters are its applied interface; the
Settings Applier is their intended caller. One user-facing frame rate fans out
internally via `setFrameRate()` (M-JPEG interval, adaptive default, RTSP), and
one camera frame fans out internally via `pushFrame()` (web pipeline, RTSP
encoder) — the frame listener is wired once at the composition root
(`MainApplication.wireFramePump()`), never in a screen ViewModel. Stream-audio
changes funnel through one internal policy: live-update when the running
server supports it, restart when it doesn't. It is also the composition root
that builds the Web API stack.

### Web API Handlers
**`streaming/web/`** — the JSON-in/JSON-out surface behind the streaming
server's `/api/*` routes, split one handler per domain: `SettingsWebHandler`
(DTO mapping), `StatusWebHandler` (status aggregation), `StreamWebHandler`
(lifecycle), `CaptureWebHandler`, `LensWebHandler`,
`IntervalCaptureWebHandler`, `RecordingWebHandler` (observes the Recording
Controller), and `GalleryWebHandler` (media resolution/thumbnails). The seam
is `ApiRouter.dispatch(request): ApiResponse` — I/O-bound handlers suspend and
`StreamingServer` awaits the router from its worker threads. Handler errors
are encoded in the 200 payload (the web client's contract); non-200 is
reserved for routing/transport. Binary payloads (media streams, thumbnails,
snapshots) bypass the JSON router deliberately: the server serves them
straight from `GalleryWebHandler`/PhotoCaptureManager. The four
`/api/auth/*` routes are the one deliberate exception to the router seam:
their cookie/non-200 contract differs from the JSON handlers, so
`StreamingServer` translates them onto the Web Auth Gate directly.

### Web Auth Gate
**`streaming/WebAuthGate.kt`** — the auth policy for the web client: session
tokens, rate-limited PBKDF2 login (`login`), request authorization
(`authenticate`), CSRF origin checks (`isCsrfSafe`). Owned by the Streaming
Manager so sessions survive a server recreation (e.g. a port change);
`StreamingServer` only translates HTTP requests onto its interface.

### Frame Pipeline
**`streaming/FramePipeline.kt`** — the web frame path: YUV in, overlaid JPEG
out. Owns the frame-interval throttle (thermal + adaptive), JPEG quality
resolution, YUV→JPEG conversion with reusable buffers, the conflated queue,
and the processed/dropped counters. StreamingManager gates activity and feeds
`server.updateFrame` through the pipeline's listener; the pixel work sits
behind `push()` without dragging in lifecycle.

### RTSP Server
**`streaming/rtsp/RtspServer.kt`** — the RTSP output. Its interface is
`start(config, audioStream)` / `apply(config)` / `pushFrame` / `stop`:
`RtspConfig` is one immutable value (video, audio, auth), so the old
order-sensitive setter bag is gone, and both entry points clamp the config to
its `StreamDefaults` bounds (`normalize`). `apply` owns the live-update semantics —
bitrates hot-swap in the encoders, frame rate changes the RTP timestamp
increment, a new input format reconfigures the encoder; structural changes
restart the server (StreamingManager's call). RTP sequence/SSRC state lives
in per-start `RtpPacketizer`/`AacRtpPacketizer` instances — no global reset
ritual. YUV rotation and conversion live in `core/YuvConverter`, not here.

### Camera Binding Seam
**`camera/CameraService.kt` `bindRecording()` / `unbindRecording()`** — the
recording entry point on the camera module. One shared ladder
(`bindLargestCompatible`) backs both preview-start and recording-start, so
they fall back identically on constraint-limited devices; callers never pull
use-case getters or call the provider directly. Callers bracket a recording
with `acquireKeepAlive()` / `beginExclusiveSession()` and restore with
`unbindRecording()`.

### Camera Control Plan
**`camera/model/CameraControlPlan.kt`** — the CaptureRequest *decisions*
(fps range, exposure, scene mode, white balance, focus) as pure data built
from `CameraSettings` plus the device's live ranges. `CameraService` only
translates the plan into `CaptureRequestOptions` — the ISO/fps/night-vision
math is unit-tested on the JVM instead of hiding in the service.

### Lens Inventory
**`camera/LensInventory.kt`** — the pure lens-inventory knowledge: focal
bands to labels (`Ultrawide`/`Wide`/`2x`…), OEM-duplicate removal, back-first
ordering, the main-logical-back default index, and the enumeration-failure
fallback pair. `CameraService` keeps the provider iteration and the Camera2
reads and delegates every decision here. The combination ladder
(`orderedCombinations`) and the 4:3-vs-16:9 analysis sizing stay in the
service file as internal pure functions with their own tests — device-bound
binding keeps no other home.

### Camera Settings Editor
**`camera/CameraSettingsEditor.kt`** — the single camera-settings writer.
`edit(transform)` applies immediately then persists (camera screen, for
responsiveness — the Settings Applier re-applies idempotently) or persists
only (settings screens — the Applier applies). Both ViewModels delegate;
the `Auto`-ISO and `OFF`-scene-mode parsing lives here once. Wiring
arrives as lambdas so the module never touches Android.

### Recording Clock
**`capture/RecordingClock.kt`** — the single recording clock. Derives
millisecond and second flows from the Recording Controller's state (one
ticker, configurable rate) for both screens; the Web API Recording Handler
reads the same pure `elapsedMsSince` for its one-shot status. No screen
keeps its own ticker anymore.

### Stream Status Snapshot
**`camera/model/StreamStatusSnapshot.kt`** — the pure dashboard snapshot:
typed video/audio input groups in, one `StreamStatus` out. The camera
screen's combines feed it; the `List<Any>`-plus-casts mapping is gone.

### Adaptive Bitrate Controller
**`streaming/AdaptiveBitrateController.kt`** — the single adaptive-bitrate
brain. The frame path (`getAdaptiveQuality`, `getAdaptiveFrameInterval`)
drives it and publishes the *driven* `AdaptiveState` that the app dashboard
and `/api/status` display. Bandwidth numbers are measured client throughput —
0 when no client is connected; no invented constants.

### Stream Watchdog
**`core/StreamWatchdog.kt`** — monitors a live stream and performs recovery
(hard restart choreography) when it stalls. Recovery re-attaches the environment
through the Streaming Session's `refreshAfterRecovery()`.

### Interval Capture Policy
**`capture/IntervalCapturePolicy.kt`** — the pure interval-capture policy:
bounds (1–3600s), tick validation, first-vs-next delays, completion,
flash mapping, progress data, and WorkManager status snapshots. The
Scheduler only enqueues and the Worker only executes ticks; the Web API
handler passes requests through untouched because the Scheduler clamps
through the policy. The 3600s ceiling now covers the app screen too (it
previously clamped only the Web API path).

### Stream Quality Policy
**`streaming/StreamQualityPolicy.kt`** — the single quality-resolution
order for the frame path: the battery suggestion arrives as the base
quality, thermal clamps it, the network ladder scales it, one
`resolve()` per push. The sensor seams (`ThermalAdjustmentSource`,
`NetworkAdjustmentSource`) are implemented by the Thermal Monitor and the
Adaptive Bitrate Controller in production and by fakes in tests; the
Frame Pipeline takes the policy instead of the two sensors. Display
bandwidth stays 0-while-idle while the adaptation ladder keeps its
default-aware view — the split is documented and locked by test.

### Connectivity Monitor
**`core/ConnectivityMonitor.kt`** — app-scoped observer of Wi-Fi connectivity.
One network callback, exposed as `isWifiConnected: StateFlow`. Callers observe
the flow; nobody takes one-shot connectivity snapshots.

### Stream Defaults
**`core/StreamDefaults.kt`** — the single home for stream configuration
defaults *and* validation bounds (ports, JPEG quality, fps, audio config,
watchdog limits, video bitrate, RTSP fps ceiling). The store, manager,
encoders, Web API DTOs, and quality monitors all reference it; default
literals and (min,max) bounds are never re-copied. `OverlaySettings.DEFAULT` /
`MaskingZone.DEFAULT` play the same role for overlay and masking fallbacks,
and the `CameraSettings` companion holds the camera-side bounds (exposure,
zoom, color temperature, frame rate); the device's live ranges from
CameraService always win at apply time.

### Stream Auth Crypto
**`core/StreamAuthCrypto.kt`** — the single home for the stream-auth
primitives: `md5Hex`, `constantTimeEquals`, and `RTSP_DIGEST_REALM`. The
stored settings compute the RTSP Digest HA1 with this realm at save time and
the RTSP server verifies against the same symbol — one literal, no drift.

### Mic Access
**`core/MicAccess.kt`** — the one adapter for microphone availability:
`isGranted(context)` plus the shared warn-and-degrade message. Every module
that wants audio but can't get it (CameraViewModel, CaptureViewModel,
RecordingService, AudioStreamingManager) asks here instead of hand-rolling
`checkSelfPermission` and re-copying the toast text.
