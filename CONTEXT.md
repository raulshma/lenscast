# CONTEXT.md — LensCast Domain Glossary

Domain language for LensCast: an Android app that turns a phone into a streaming
camera, serving video/audio to browsers (HTTP/MJPEG + Web API) and RTSP clients.

## Terms

### Streaming Session
**`streaming/StreamingSession.kt`** — the single owner of the live-stream
choreography: wake lock, thermal monitoring, battery optimization, camera
keep-alive, foreground service, and watchdog. Interface is `begin()` (call once
the stream started; idempotent), `end()` (tears down only when no stream is
live), and `recover(tier)` (watchdog recovery for every tier — SOFT rebinds,
MEDIUM re-rolls the attach choreography, HARD does the full refresh — all
through the session's bounded Main seams; the watchdog keeps only the tier
decision, backoff, verification, and state publishing). `begin()` also carries
the foreground-service microphone verdict (web audio live *or* the RTSP output
live with its track wanted — an RTSP-only capture without the MICROPHONE type
is silenced by the OS) and re-asserts it when a second output starts late.
Web API Stream
Handler, CameraViewModel, and StreamWatchdog are one-call clients — none of
them hand-roll the choreography anymore.

### Settings Applier
**`settings/SettingsApplier.kt`** — the single owner of "persisted settings →
runtime" application. It watches `SettingsDataStore` flows and applies new
values to the StreamingManager, CameraService, Stream Watchdog, and the MQTT
alert publisher's connection lifecycle. Every other
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
change a setting by saving it; the Settings Applier reacts. Every setting is
one internal `SettingPref` declaration (key, encode, decode, clamp) — the
encode/decode conventions (including the default-true vs default-false
boolean reads) and every numeric clamp through `StreamDefaults` live in the
descriptor mechanism, JVM-tested for round-trip and bounds; saver clamping is
the one home (watchdog setters no longer re-clamp). One descriptor per key:
the composite `cameraSettingsPref` owns frame rate and night-vision mode, and
no second descriptor or dead pref trio survives over the same keys.
Overlay/masking saves go
through the pure
`OverlaySettings.normalized` / `MaskingZone.normalized` — "persist a valid
value" is the store's invariant, so ViewModels and the Web API Settings
Handler write raw values and no caller pre-guards. Masking-zone lists
serialize through App Json (a Moshi codec with the legacy org.json field
names kept decode-compatible), not a second JSON stack. Enum strings parse
through the one `core/EnumParsing.parseEnum(name, fallback)` with each
site's fallback an explicit argument. Each setting is share-in'd exactly
once, inside the store, and exposed as a `StateFlow` — ViewModels and Web
API handlers read those flows directly, no re-wrapping and no re-typed
defaults. Settings screens consume them via SettingsViewModel — no mirror
state. Auth settings are a plain value type; hashing/verification lives in
Stream Auth Crypto.

### Recording Controller
**`capture/RecordingController.kt`** — the single owner of recording state and
start/stop/schedule choreography *and* of the bounded-recording policy: a
config with `durationSeconds` auto-stops and optionally repeats, no matter
which client started it (camera screen, capture screen, Web API) and
independent of any screen's lifetime. The policy's verdicts (arm, auto-stop,
repeat re-arm and fire, stop-survival) are the pure Recording Duration
Policy, event-sequence-tested; the controller keeps the lock, jobs, epochs,
and intent construction. Its `RecordingState` flow (Idle /
Scheduled / Recording with startedAtMs, config, finalizing) is the only public
truth about recording: CameraViewModel, CaptureViewModel, and the Web API
Recording Handler all observe it, and none keep optimistic copies. The
RecordingService is the truth source — it reports transitions through the
controller's `onService*` methods, and the controller owns intent construction
and delay-until scheduling. `RecordingConfig`'s companion owns the
capture-screen bounds (`MAX_DURATION_SECONDS` / `MAX_REPEAT_SECONDS`) and the
pure `scheduledStartFor` wall-clock rollover (today, else tomorrow) the
capture screen schedules through.

### Recording Service
**`capture/RecordingService.kt`** — the foreground service holding the live
recording. Stateless to consumers: every transition is reported to the
Recording Controller. Camera binding goes through CameraService's
`bindRecording()` seam — it never touches the provider or the use-case getters.

### Capture Media Resolver
**`capture/CaptureMediaResolver.kt`** — the one scheme ladder behind a
history `filePath`: content:// vs file:// vs a plain existing path, for
`openStream` / `displayModel` / `exists` / `delete`, with the
contentResolver injected so the classification is JVM-tested. GalleryMedia,
the Web API Gallery Handler, PhotoCaptureManager, and CaptureHistoryStore's
delete/exists all delegate instead of re-rolling the ladder.
`CaptureMediaFormat`'s rooted relative-path accessors are the single
derivation of `Pictures/LensCast/` / `Movies/LensCast/`, so the MediaStore
writes and the store's queries can no longer drift.

### Scheduled Recording UI
**`capture/ScheduledRecordingUi.kt`** — the pure state→UI mapping for the
capture screen's schedule surface, in two shapes: `scheduledUiModel(state)`
(the controller's `RecordingState` → startAtMs / canCancel / isScheduled) and
`scheduleRowUi(state, draftStartMs)` — the whole schedule row in one verdict:
the "Start: HH:mm" label (the screen keeps no `SimpleDateFormat`), the
armed-vs-draft merge (armed wins), trash-button canCancel, and the
Schedule-vs-Start-Now button verdict. The screen renders the model's answers
and its trash button cancels through the Recording Controller — the old draft
copy of the schedule (which let an armed job fire after "clear") is gone.

### Recording Duration Policy
**`capture/model/RecordingDurationPolicy.kt`** — the pure decision core of
the bounded-recording cycle: whether a config arms the policy at all
(`shouldArm`), the auto-stop timing and its post-delay fire gate, the
repeat re-arm verdict after an auto-stop, the repeat fire triple-check
(superseded job / bumped epoch / not-Idle never restarts over a live
recording), and `doesRepeatSurviveStop(cause)` — a stop report survives the
armed repeat only when it is the policy's own AUTO stop, never a
USER-initiated or externally-reported one. Recording Controller keeps the
lock, the coroutine jobs, the epoch bookkeeping, and intent construction;
every non-obvious conditional in its arm/stop paths consults this policy, so
the user-stop vs auto-stop vs repeat-fire races are pinned by event-sequence
JVM tests instead of living only in comments.

### Photo Capture
**`capture/PhotoCaptureManager.kt`** — owns the photo choreography (acquire
use case → take photo → record in history → release). Interface: `captureToGallery()`
(callback-based) and `captureSnapshot(saveToDisk)` (suspend, returns JPEG
bytes). CameraViewModel, CaptureViewModel, and the Web API Capture Handler are
one-call clients. The save destinations (MediaStore vs legacy file) live
behind one internal `PhotoDestination` seam and one `takePictureAwait`
primitive serving disk and memory; a single bounded Main-hop acquire seam
serves both entry points.

### Capture Media Format
**`capture/model/CaptureMediaFormat.kt`** — the capture-media identity
literals in one home: mime per capture type (`image/jpeg` / `video/mp4`),
the `LensCast` folder and its `Pictures/LensCast/` / `Movies/LensCast/`
relative paths, and the `content://` sniff. Producers (PhotoCaptureManager,
RecordingService), the MediaStore queries (CaptureHistoryStore), and the
gallery/web consumers (GalleryMedia, GalleryWebHandler) all reference the
same symbols — a folder rename or new format is one edit.

### Media File Naming
**`capture/MediaFileNaming.kt`** — one shared `yyyyMMdd_HHmmss` stamp behind
`photoName(now)` / `videoName(now)`; the capture manager and the recording
service both delegate, so the `IMG_`/`VID_` patterns stay in lockstep with
CaptureHistoryStore's path-based merge.

### Capture History Store
**`data/CaptureHistoryStore.kt`** — the persisted capture history plus its
MediaStore reconciliation. One merge policy, `mergeFields(existing,
incoming)`, backs both the single-entry `mergeEntry` and the pure
`mergeWithDeviceMedia(current, deviceMedia)` that the MediaStore cursor
adapter feeds: dedupe by normalized path, `maxOf` timestamps, richer-field
fallbacks — including keeping the existing fileName when an incoming one is
blank (no producer creates blank names, so the unified rule is strictly
safer than the old unconditional overwrite). Deletion is one `deleteAll(ids)`
with `deleteMedia(id)` as its single-id form. Folder queries reference
Capture Media Format's constants; persistence goes through App Json.

### H264 Stream Assembler
**`streaming/rtsp/H264StreamAssembler.kt`** — the wire-format core of the
H.264 encoder path: holds the latest SPS/PPS from both MediaCodec CSD
sources (codec-config buffers and the output format's csd-0/csd-1) and owns
the keyframe prepend decision in `assemble(nalUnits, isKeyFrame)`. Pure over
ByteBuffers, so both CSD paths and the assembly are JVM-tested.

### Media Codec Encoder Harness
**`streaming/rtsp/MediaCodecEncoderHarness.kt`** — the one MediaCodec
lifecycle behind both RTSP encoders: the running guard, the
create→configure→start ladder, the output-drain thread (10 ms dequeue,
format-changed and output callbacks), and the teardown (join, stop/release)
with the exact exception ladder — all behind a thin `CodecLike` seam (the
production adapter wraps MediaCodec), so the invariants — start idempotence,
stop-after-failed-start safety, drain-exit classification, the original
timings — are JVM-tested with a fake codec. `H264Encoder` and `AacEncoder`
keep only their format construction, CSD interpretation, and input feeding;
`RtspServer`'s call sites are unchanged.

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
server supports it, restart when it doesn't. Auth settings are read live
wherever they're consumed — the Web Auth Gate reference and the RTSP
auth-spec provider both read the applied value directly, so server
recreation re-applies nothing. It is also the composition root
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
straight from `GalleryWebHandler`/PhotoCaptureManager. The login/logout/
session routes (`/api/auth/login`, `/api/auth/logout`, `/api/auth/status`,
`/api/auth/session`) are the one deliberate exception to the router seam:
their cookie/non-200 contract differs from the JSON handlers, so
`StreamingServer` translates them onto the Web Auth Gate directly. The
config/session-management routes (`/api/auth/config`, `/api/auth/sessions`)
stay behind the router like every other JSON handler, as do the two deliberate
binary-style bypasses `/api/events` (the SSE status stream's never-ending
chunked response) and `/api/audio/uplink` (raw PCM16 body), which the server
serves without JSON semantics. The WS sidecar (`streaming/ws/WsMediaServer`)
handshakes through the same Web Auth Gate — auth on requires the session
cookie, and a rejected handshake aborts the upgrade. The
`web/src/types.ts` mirror of the DTO surface is hand-maintained in lockstep:
fields the Kotlin side deletes are deleted from the client too (the web UI
ships no control with no runtime effect), and the plaintext-password /
hashed-at-rest contract is documented at the type. Lockstep is enforced, not
just disciplined: representative Kotlin DTOs serialize through App Json into
checked-in `web/contract/*.json` fixtures — `DtoContractFixtureTest` fails
when a Kotlin DTO drifts from its fixture, and the web contract test fails
when `types.ts` or the client's `API_DEFAULTS` drifts from the same fixtures
— and `buildWebUi` runs `tsc --noEmit` + vitest before vite, so type errors
and contract drift stop shipping inside the APK.

### Web Client Core
**`web/src/hooks/useAppState.ts`** — the one state hook over the Web API,
composition only: signals, settings/save handlers, and the action pipeline
(`runStreamAction` — one guarded call → error → nonce/preview/fetchStatus
choreography behind all ten stream/interval/recording handlers, the RTSP
pair's differences explicit options rather than omissions). The deep modules
behind it: `web/src/hooks/pollLadder.ts` (the multi-rate, visibility-gated
fetch scheduler as tested tick math) and `web/src/audio/LiveAudioPlayer.ts`
(the PCM live-audio engine: int16 framing, jitter scheduling, 3-attempt
reconnect — browser primitives injected, the pure parts
vitest-tested). `web/src/api/defaults.ts` (`API_DEFAULTS`) is the single TS
home for the Kotlin-default fallbacks every component used to re-type (a
shipped `?? 80` vs the real JPEG default 70 was the drift class this
deletes); the dead `StreamingCard.tsx` is deleted.

### Gallery Page
**`streaming/web/GalleryPage.kt`** — the pure `/api/gallery` pagination:
`of(items, type, page, pageSize)` returns items, filtered total, and the
hasMore verdict with the handler's exact math. `GalleryWebHandler` parses
query params, delegates, and serializes; it serves the store's current
snapshot honestly (no token fire-and-forget refresh inside the request).

### Web Auth Gate
**`streaming/WebAuthGate.kt`** — the auth policy for the web client: session
tokens, rate-limited PBKDF2 login (`login`), request authorization
(`authenticate`), CSRF origin checks (`isCsrfSafe`). Owned by the Streaming
Manager so sessions survive a server recreation (e.g. a port change), and
mirrored through the `SessionPersistence` hook (`AuthSessionStore`, an
atomic-write file in app-private `filesDir`) so they also survive an app
restart — the gate reloads and re-prunes the map at construction;
`StreamingServer` only translates HTTP requests onto its interface. Time
flows through the injected `clock: () -> Long` (the Rtsp Session Authorizer's
pattern), so the 60 s lockout and 24 h session expiry are JVM-tested;
login failures carry a typed `LoginFailure` (NotConfigured / RateLimited /
InvalidCredentials) and the Auth Filter maps reason → status — it never
string-matches the human-readable message. Credentials are read live through
the gate (the manager-owned reference *is* the provider); no auth snapshot is
re-applied when the server is recreated.

### Frame Pipeline
**`streaming/FramePipeline.kt`** — the web frame path: YUV in, overlaid JPEG
out. Owns the frame-interval throttle (thermal + adaptive), JPEG quality
resolution, YUV→JPEG conversion with reusable buffers, the conflated queue,
and the processed/dropped counters. StreamingManager gates activity and feeds
`server.updateFrame` through the pipeline's listener; the pixel work sits
behind `push()` without dragging in lifecycle. The accept/reject tick itself
is the shared `FrameThrottle`, and the fps→interval / 90 kHz RTP increment
derivations are `streaming/FrameTiming` (`effectiveFps` falls back to
`StreamDefaults.STREAM_FPS`) — one home each for the web and RTSP push paths.

### RTSP Server
**`streaming/rtsp/RtspServer.kt`** — the RTSP output. Its interface is
`start(config, audioStream)` / `apply(config)` / `pushFrame` / `stop`:
`RtspConfig` is one immutable value (video, audio, auth), so the old
order-sensitive setter bag is gone, and both entry points clamp the config to
its `StreamDefaults` bounds (`normalize`). What `apply` does live vs what
restarts is a pure verdict — `RtspConfig.diff(old, new)` classifies each
changed field HotSwap or NeedsRestart (video bitrate genuinely hot-swaps in
the encoder; audio bitrate does not, so it restarts) — the code, not a
comment, owns the promise. Structural changes restart the server (StreamingManager's
call). RTP sequence/SSRC state lives
in per-start `RtpPacketizer`/`AacRtpPacketizer` instances — no global reset
ritual — over the shared `RtpStreamState` header writer. YUV rotation and
conversion live in `core/YuvConverter`, encoder color-format selection in
`EncoderFormatPolicy`; the session/track state machine stays here, but the
protocol knowledge behind it is pure: `RtspRequest` (wire parsing),
`RtspSessionAuthorizer` (Digest/Basic ladder + the nonce store it owns),
`RtspUriPolicy` (track-id grammar, path normalization, method allow-list),
`RtspSessionProtocol` (the SETUP transport verdict, the CSeq monotonicity
ladder, the Session-header parse, PLAY's RTP-Info, the RTCP sender-report
bytes, and the `$`-interleaved framing — all byte-pinned by test),
and `SdpBuilder`. ClientSession only reads requests, asks the authorizer,
consults the policies, and writes frames — the security surface is
JVM-tested without a socket.

### RTSP Output
**`streaming/RtspOutput.kt`** — the manager-side RTSP owner: retained
`RtspConfig` while stopped, server lifecycle, the audio `InputStream` handle,
URL building, and the audio-wanted/mic-arbitration decision. The
restart-vs-apply choice has one routing point: an internal
`update(trigger, transform)` lands the config change, diffs old vs new
through `RtspConfigDiff`, and routes — any NeedsRestart field restarts,
HotSwap-only changes go to `server.apply(config)` — with explicit triggers
for the audio-track ladder (audio config restarts whenever the track is
wanted) and for audio-wanted flips. The per-setting setters are one-line
delegates over `update`, plus the coalesced `setAudioConfig(wanted, bitrate)`
single-restart entry the manager's audio snapshot routes through;
`StreamingManager` keeps its public surface (the
Settings Applier's audio write is one coalesced call) and delegates — the manager retains fan-out and
web/mDNS concerns the way `FramePipeline` absorbed the web frame path.

### AAC Format
**`streaming/rtsp/AacFormat.kt`** — one home for the AAC stream's format
literals and pure capture math: samples-per-AU (1024 — the RTP timestamp
advance per audio AU, contract-pinned against `RtspServer`), PCM bytes per
sample, the mic probe ladder with default from `StreamDefaults`,
`resolveBuffers` (frame-aligned read chunk and record buffer sizing, the
former `AudioStreamingManager` inline math), the AudioSpecificConfig bytes,
and the SDP fallback hex derived per call from the actual rate/channel count
(`fallbackAscHex` — never a single historical literal, which advertised stereo
for the mono default). Encoder, RTP clock,
SDP, and mic capture reference the same symbols.

### Audio Subscriber Pipe
**`streaming/AudioSubscriberPipe.kt`** — the RTSP audio track's backpressure
contract as a pure module: `enqueue` (drop-oldest at the bounded capacity,
default 6), a blocking `InputStream` read that hands chunks across, EOF after
`shutdown`, and idempotent `close` (which runs the owner's deregister hook).
AudioStreamingManager keeps AudioRecord construction, effect attach, and the
reader thread; the drop-oldest / block-until-chunk / EOF semantics are
JVM-tested instead of being device-only behavior.

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
(fps range, exposure, scene mode, white balance, focus, color temperature) as
pure data built from `CameraSettings` plus the device's live ranges.
`CameraService` only translates the plan into `CaptureRequestOptions` — the
ISO/fps/night-vision math is unit-tested on the JVM instead of hiding in the
service. The plan also owns the exposure-index decision
(`exposureIndex`: clamp to the live range, skip when unchanged) and the
metering ladder (`meteringOnApply` / `meteringOnTap` returning a
`MeteringDecision` — auto-cancel, plain, or none — with the 5-second
auto-cancel constant named here); the service deduplicates all
`FocusMeteringAction` construction behind that one translation.
`colorTemperatureKelvin` carries the clamped MANUAL-WB decision
(`COLOR_TEMPERATURE_MIN..MAX`, null unless white balance is MANUAL); manual
mode is conveyed by AWB OFF and the Kelvin value is logged at apply time —
per-sensor gains/matrix mapping stays device-default until a calibrated
Kelvin→gains table exists.

### Focus Apply Policy
**`camera/model/FocusApplyPolicy.kt`** — the pure verdict on whether a
settings apply must re-fire the center AF/AE metering: only a first apply, a
focus-mode change, or a focus-distance change counts. `CameraService` keeps
the last focus-applied settings, consults the policy (forcing on a fresh
bind), and skips the metering otherwise — so the settings-apply path that
runs on every slider tick (twice, editor + Applier) can no longer cancel a
deliberate tap-to-focus.

### Quick Setting Catalog
**`camera/model/QuickSettingCatalog.kt`** — one pure descriptor per
camera-screen quick setting: pill label, sheet title, icon selector, editor
shape (Toggle | Chips | Slider as functions of `CameraSettings` plus the
device's `QuickSettingRanges`), and the write transform — a typed
`QuickSettingEditorValue` in, updated `CameraSettings` out — so the catalog
owns both the reads and the writes of every setting it describes — for both
screens: SettingsViewModel's camera-settings half has no per-field writers,
it routes one `updateQuickSetting(type, value)` through the same
`editorValueFor` + `descriptor.write` table, and SettingsScreen derives its
dropdown options and labels from the descriptors (`chipLabel` is the one
underscore→space rule). The horizontal bar and the sheet render from the
catalog — CameraScreen keeps no per-control branches — SettingsScreen reuses
the catalog's night-vision copy, slider ranges, and scene-mode options (the
persistence bounds stay the one home; a parity test pins UI range == clamp),
and `ZoomIndicator` calls the catalog's `zoomLabel` (Locale.US-pinned).
Writes funnel through the one `CameraSettingsEditor` path; no second write
table and no `Any`-cast dispatch survives.

### Camera Dashboard Policy
**`camera/model/CameraDashboardPolicy.kt`** — the pure dashboard
verdicts/formats the camera screen renders: the WiFi-banner message, server
status tint/text ladders, the stream-shutter button verdict
(`StreamShutterVisual.of` — container tier, tint, icon-gating
content-description, and click gate for the web/RTSP buttons, rendered by
one `StreamShutterButton`), thermal banner, network-quality badge, client
pluralization, byte formatting, the slider-endpoint trim, and the connection
panel (`qualityIndicatorVisible`, `qualitySummary`, `connectionStatRows`,
per-client header/rows — the panel renders policy-produced strings, so the
KB-vs-formatBytes split is gone). CameraScreen only maps the policy's
answers onto composables — every ladder is JVM-tested, and no
`String.format` without an explicit locale survives.

### Stream Toggle
**`camera/model/StreamToggle.kt`** — the single gate → start → session-begin
→ rollback ladder for turning the web stream, RTSP, or the whole server
on/off. Returns a sealed `StreamStartOutcome` (Started | Disabled |
StartFailed | BeginFailedRolledBack) so both clients — CameraViewModel
(toasts) and the Web API Stream Handler (DTO mapping) — consume one set of
failure semantics; a session-begin failure after a successful start rolls
the stream back everywhere, not just on the web path. Its one `Transports`
adapter is `streaming/StreamingTransports.kt`, built once over
(StreamingManager, StreamingSession) with gate getters reading the manager's
live flows — no client re-types the adapter and no gate reads a derived
snapshot; the Stream Handler's start-all runs web+RTSP through the toggle's
`startBoth` so the rollback discipline is single-homed, and `startServer`
extends the same ladder to the whole-server toggle — CameraViewModel's server
entry is one outcome-mapped call (a null-kind outcome is the server), and the
old inline begin→rollback tail with its bespoke error string is gone. The
record button's sibling verdict is Recording Toggle.

### Recording Toggle
**`camera/model/RecordingToggle.kt`** — the record button's verdict, the
Stream Toggle's twin: `decide(currentState, startConfig, onBeforeStart)`
returns Start(the caller's config, includeAudio resolved by the pre-start
hook) or Stop over the Recording Controller's state — the start payload is the
caller's full `RecordingConfig`, so the camera screen passes its default and
the capture screen its draft and both route through this one verdict (the
capture screen's inline copy of the stop-vs-start rule is gone); stop never
consults the hook. CameraViewModel and CaptureViewModel execute the decision
and keep the wiring; the same file
holds the two other camera-screen decisions as pure code — `stickyCameraState`
(the never-regress-to-Idle filter over the service's camera-state flow) and
`CameraInitRetry` (the bounded init retry budget).

### Resolution Apply Policy
**`camera/model/ResolutionApplyPolicy.kt`** — the pure verdict on whether a
resolution change rebinds now or defers to the next active session
(`decide(demandActive, exclusiveActive, resolutionChanged)`), and the
freedom predicate beneath it: `isCameraFree(demandActive, exclusiveActive)`
is the one "camera is not busy" ladder, consulted by lens select, camera
switch, and auto-recovery alike. CameraService keeps only the pending field,
the rebind call, and the resume hook — no site re-derives the predicate.

### Camera Session Arbiter
**`camera/model/CameraSessionArbiter.kt`** — the pure precedence verdict over
the camera module's demand state: one immutable `CameraDemandState` snapshot
(preview requested, keep-alive/exclusive refcounts, activity foreground,
surface attached, pending resolution, trigger) in, one `CameraSessionAction`
(Rebind / Unbind / DetachSurface / ApplyPendingResolution / Noop) out. Every
CameraService lifecycle entry point mutates its fields as before, builds the
snapshot, consults the arbiter once, and executes against CameraX — the gates
that used to be re-derived per caller (and the exclusivity pre-check that ran
outside the Resolution Apply Policy) are gone; `isCameraFree` still comes
from ResolutionApplyPolicy. The resume path's known asymmetry (resume gates
on `previewRequested` alone, so a keep-alive-only session with a pending
resolution waits for the next flush) is preserved and pinned by test, not
silently "fixed".

### Frame Error Policy
**`camera/model/FrameErrorPolicy.kt`** — the pure verdict on whether a
consecutive frame-error streak (threshold + reset window) triggers the
auto-recovery rebind; the service keeps only counting and the call.

### Preview Gestures
**`camera/model/PreviewGestures.kt`** — the pure preview-gesture math: pinch
scale → null-below-deadband (`SCALE_DEADBAND = 0.01f`) or clamped zoom, tap
recognition (identity zoom, minimal pan), and the zoom-indicator hide delay
(800 ms). `CameraPreview`'s transform handler only normalizes coordinates
and delegates; a recognized tap becomes a normalized (0..1)
`CameraService.tapToFocus` call through the ViewModel — the UI twin of the
LensWebHandler entry point.

### Lens Inventory
**`camera/LensInventory.kt`** — the pure lens-inventory knowledge: focal
bands to labels (`Ultrawide`/`Wide`/`2x`…), OEM-duplicate removal, back-first
ordering, the main-logical-back default index, the enumeration-failure
fallback pair, and the switch math (`nextIndex` cycle, `fallbackSelector`
front/back fallback). `CameraService` keeps the provider iteration and the
Camera2 reads and delegates every decision here. The combination ladder
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
through the Streaming Session's `refreshAfterRecovery()`. The decisions are
the pure `WatchdogPolicy`: the SOFT/MEDIUM/HARD escalation ladder
(`tierFor`), the stall verdict over a `HealthSnapshot` (`evaluate`), the
per-tier recovery-verification verdict (`verificationSuccess` — SOFT's
frames-advanced-or-zero-clients, MEDIUM's isLive, HARD's isLive+cameraReady),
and the named verification windows. The loop itself executes a tested plan:
`verificationSpecFor(tier)` (delay + whether frames are measured) collapses
`verifyRecovery` into one parameterized pass, and `nextTick` is the one
verdict (action, status, backoff) per health tick — backoff before the
attempt, verification after, by construction — so the coroutine keeps only
timing, the reads, and the recovery calls.

### Battery Quality Policy
**`core/BatteryQualityPolicy.kt`** — the pure battery→quality ladder:
`resolve(level, isPowerSave, isCharging, inDoze)` returns the
`BatteryOptimizationResult` (suggested JPEG quality + user-facing message)
with charging beating doze beating low battery. `PowerManager` keeps the
receivers, wake lock, and battery reads and delegates; the thresholds and
tier qualities live here as named constants — this ladder's own knowledge,
not cross-module config — locked by test.

### Foreground Notifications
**`core/ForegroundNotifications.kt`** — the one foreground-notification
registry: named, distinct IDs (recording 1001, streaming 1002, update 1003,
interval capture 1004 — the interval worker's old private 1002 collided with
streaming's), the pure message builders (`streamingMessage`,
`intervalCaptureMessage`), and both promotion variants — the Service
`startForeground` path and the camera-typed WorkManager `ForegroundInfo`
builder. StreamingService, RecordingService, IntervalCaptureWorker, and
UpdateNotifier reference the registry; no private ID constant survives, so a
new foreground owner cannot silently collide.

### Interval Capture Policy
**`capture/IntervalCapturePolicy.kt`** — the pure interval-capture policy:
bounds (1–3600s), tick validation, first-vs-next delays, completion,
flash mapping, progress data, the bounded retry verdict
(`retryVerdict(attempt, cause)` — at most `MAX_CAPTURE_ATTEMPTS` tries per
tick, then the tick is skipped and the series continues), and WorkManager
status snapshots. The Scheduler only enqueues (with the linear backoff
criteria) and the Worker only executes ticks; the Web API handler passes
requests through untouched because the Scheduler clamps through the policy.
The 3600s ceiling now covers the app screen too (it previously clamped only
the Web API path).

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
ISO span, zoom, color temperature, frame rate) plus
`effectiveZoomRange(deviceMaxZoom)` — the one clamp where the device's live
zoom ceiling meets the persistence ceiling, so pinch and settings re-apply
agree; the device's live ranges from CameraService always win at apply time.

### Stream Auth Crypto
**`core/StreamAuthCrypto.kt`** — the single home for the stream-auth
primitives: `md5Hex`, `constantTimeEquals`, `RTSP_DIGEST_REALM`, and the
password crypto — PBKDF2-SHA256 `hashPassword`/`verifyPassword` (with the
legacy bare-SHA-256 migration branch), `computeRtspDigestHa1`, all over
`java.util.Base64` so the security path is JVM-tested. `StreamAuthSettings`
is a plain value type; the Settings ViewModel hashes at save, the Web Auth
Gate verifies at login, and the RTSP server's `RtspSessionAuthorizer`
verifies Digest/Basic through the same symbols — one literal, no drift.

### Mic Access
**`core/MicAccess.kt`** — the one adapter for microphone availability:
`isGranted(context)` plus the shared warn-and-degrade policy. Every module
that wants audio but can't get it (CameraViewModel, CaptureViewModel,
RecordingService, AudioStreamingManager) asks here instead of hand-rolling
`checkSelfPermission` and re-copying the toast text. Stream/record starts
refresh their permission state and then consult the pure
`startDecision(featureEnabled, granted, label)` — Proceed, or
`Degrade(warning)` with the shared message — and `shouldAutoRequest` gates
the camera screen's ask-once permission prompt. The consult rides one
`core/MicGate` adapter (refresh-then-consult plus the one toast sink) shared
by CameraViewModel, CaptureViewModel, and the Stream Toggle's pre-start hook —
one implementation of "refresh, decide, warn-and-degrade", one wording home.

### Gallery Pager Math
**`gallery/GalleryPagerMath.kt`** — the media viewer pager's math as pure
functions: `indexAfterDelete(currentIndex, sizeAfterDelete)` → the next
page, the previous page at the end of the list, or null (pop back);
`initialIndexFor(items, mediaId)` for opening on a tapped item; and
`viewerResyncTarget(items, mediaId, currentPage)` — the single resync
verdict (known id pins its index, unknown id falls back to the
indexAfterDelete neighbor rather than 0, empty list pops) that folds
initial placement, list-shrink clamping, and the post-delete landing into
one tested answer. The choreography *around* that effect — the placed
one-shot, jump-vs-animate, pop-when-empty — is `gallery/ViewerSync.kt`, a
pure state machine (`reduce` → JumpTo / AnimateTo / Pop) that NavigationGraph
executes effect-by-effect; the delete-while-paging transitions are
JVM-tested, not composable-embedded.

### Viewer Zoom Policy
**`gallery/ViewerZoomPolicy.kt`** — the media viewer's gesture policy, pure:
double-tap zoom-in/reset verdict (1.2f threshold, 2.5f target), pinch scale
clamped to 1..5, pan clamped to half the scaled overflow per axis, center
pin at fit scale — the PreviewGestures twin. MediaViewerScreen normalizes
the gesture and delegates.

### App Json
**`core/AppJson.kt`** — the one Moshi instance (KotlinJsonAdapterFactory)
behind every JSON serializer: the web DTO layer, CaptureHistoryStore's
persistence, the RecordingController intent shuttle, and UpdateChecker. No
private Moshi stacks left to drift.

### Network Adaptation Policy
**`core/NetworkAdaptationPolicy.kt`** — the pure network ladders the monitor
renders: threshold→level, level→quality factor, level→fps factor, and the
display rule (measured throughput, 0 while idle, default-aware for
adaptation) stated once. `NetworkQualityMonitor` keeps the sampling and
bookkeeping and delegates — the display-vs-adaptation split is one locked
implementation, not copy-paste.

### Update Policy
**`update/UpdatePolicy.kt`** — the pure update-check decisions: version
`normalize`, numeric `isNewer` comparison, 24h `shouldAutoCheck` gate,
`shouldNotify` dismissal check, `shouldNotifyAfterCheck(result,
dismissedVersion)` — the one verdict on notify/saveLastCheck that both the
startup auto-check and the manual ViewModel check consume — and
`selectApkAsset` (prefer the universal APK, else any APK), the release-asset
ladder the network checker delegates to. The checker's 403
path produces the real `UpdateCheckResult.RateLimited` (silent, like Error);
no caller re-types the outcome choreography.

### Update Check Pipeline
**`update/UpdateCheckPipeline.kt`** — the check choreography the policy
decides for: check → `shouldNotifyAfterCheck` → conditionally persist the
last-check time → notify, with constructor-injected checker/store/notifier so
the whole ladder is JVM-tested. Both callers — the startup auto-check
(MainApplication keeps only the delay + `shouldAutoCheck` gate) and the
settings-screen ViewModel — run the one pipeline instead of hand-rolling
divergent copies.

### Update Http
**`update/UpdateHttp.kt`** — the update stack's transport seam:
`mapCheckOutcome(statusCode, errorBody, parsed)` is the pure HTTP-outcome →
`UpdateCheckResult` mapping (403 → RateLimited, other non-200 → Error,
200-without-parse → Error), so the GitHub rate-limit path — the most likely
real-world failure — is JVM-tested without a socket; `openConnection` /
`applyDefaults` are the one connection factory (LensCast User-Agent,
redirects, caller timeouts) that both the checker and the downloader build
on, so the connection config cannot drift between them.

### Overlay Layout Policy
**`streaming/OverlayLayoutPolicy.kt`** — the pure overlay/masking math:
`zoneToPixels` clipping, pixelate/blur downscale sizes, hex color parsing,
the overlay line selection (`buildOverlayLines` — timestamp, branding,
status, and the overlay-side viewer pluralization), and the text-overlay
position rect (`computeOverlayPosition` over the named
`OVERLAY_MARGIN_PX`).
`StreamOverlayRenderer` keeps only the Bitmap/Canvas adapter work and
delegates every number — and every line decision — to the policy, so the
privacy-critical rect math and the overlay text are JVM-tested without a
device.

### Status Snapshot Builder
**`streaming/web/StatusSnapshotBuilder.kt`** — the pure `/api/status`
aggregation: typed `StreamingInputs`/`ThermalInputs`/`BatteryInputs`/
`WatchdogInputs`/`AdaptiveInputs`/`NetworkInputs` in, one
`StatusResponseDto` out (adaptive null-when-disabled, connectionQuality
null-when-idle, first-client fps). `StatusWebHandler` only collects flows and
delegates — the dashboard/API mapping is tested without a manager.
