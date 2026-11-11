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
The same foreground service has a server-only CONNECTED_DEVICE mode:
`ensureServerForeground()` / `stopServerForegroundIfIdle()` keep the running
web server (and its outbound alert delivery) out of the background-network
firewall between streams; `end()` hands the one notification slot back to
that mode when the server survives, or stops the service when it does not.
Web API Stream
Handler, CameraViewModel, and StreamWatchdog are one-call clients — none of
them hand-roll the choreography anymore.

### Server Foreground Controller
**`streaming/ServerForegroundController.kt`** - maps
`StreamingManager.isServerRunning` to the session's server-only foreground
mode and provides the visible-resume `sync()` retry when Android denies a
background foreground-service start. It owns only that runtime-state bridge;
the session still owns every foreground intent and stream-mode transition.
It is separate from Settings Applier because server state is runtime state,
not a persisted setting.

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
Continuous Recording Controller is one more client of the same public
surface — it watches the state flow and starts/stops the loop through
`start`/`stop` like any screen would. The
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

### Continuous Recording Policy
**`capture/model/ContinuousRecordingPolicy.kt`** — the pure decisions behind
NVR-style loop recording. Chaining is deliberately not re-implemented: the
segment config (`segmentConfig`) sets `repeatIntervalSeconds > 0`, so the
Recording Duration Policy's bounded+repeat machinery re-starts each segment,
and this policy only decides the initial arm (feature on, controller Idle),
the disable-time teardown (only a loop the controller armed goes down with
the toggle — a user's own recording is never stopped by a setting flip), the
5 s idle grace that separates a broken chain from the healthy one-second
repeat gap, and the user-stop suppression model — a stop sets a
suppressed-until stamp and arming stays blocked for the 60 s cooldown.

### Continuous Recording Controller
**`capture/ContinuousRecordingController.kt`** — keeps the loop alive over
the Recording Controller's public API (no new service, no RecordingService
contact), hosted at the Streaming Manager but owned by the capture module.
It combines the store's continuous-recording preferences with the
controller's `RecordingState` flow, treats any live recording as the loop
(never double-starts over a user's own start), stops the loop when the
toggle goes off, and repairs the chain when an Idle outlives the grace
window — respecting the suppression cooldown before re-arming, with the
re-arm verdicts re-read at fire time so a toggle flip or user start landing
during the wait wins.

### Detection Coordinator
**`capture/DetectionCoordinator.kt`** — the single owner of the
detection-event choreography: arm-schedule verdict, then dispatch — the
bounded motion recording (started only from Idle) or the legacy auto-photo,
the webhook/MQTT/Web-Push/UnifiedPush/local-notification fan-out, the auto-siren/auto-torch
deterrence behind its cooldown, and the persisted event-log entry, all
reading one event-moment stamp and id so every sink reports the same
identity. Sound events ride the same choreography behind their own persisted
cooldown, with an opt-in bounded recording (`soundRecordingEnabled`, riding
the motion post-roll duration). Motion events additionally pass through the ML object-detection
gate when `mlDetectionEnabled` is on: the triggering frame is classified
off-path (one worker thread, at most one classification per second) and only
a positive "no allowed class at/above the confidence floor" verdict
suppresses the event — a busy, throttled, or failed classification is
fail-open. Surviving events carry the class labels into every sink and watch
the Recording Controller for the bounded recording they started, linking the
finalized clip (`clipMediaId`/`clipFileName`) back into the persisted event;
while the continuous loop holds the recorder the clip start is skipped and
the link fields stay null. The fan-out itself is a `DetectionSink` list
(see Detection Sinks) dispatched in order after the recording/photo half —
a new sink is a list entry in the constructor default, not an edit to the
dispatch — and the claimed actions ride the log as the
`DetectionEventAction` enum's wire names, byte-identical to the old
`ACTION_*` strings the feed has always persisted. The gate's classifier
side is seamed the same way: the coordinator holds `ObjectClassifier` /
`SoundClassifier` (capture/ml) with the MediaPipe engines as constructor
defaults, and the submit ladder (throttle / single-flight / fail-open) is
pinned by `DetectionCoordinatorTest` against fakes.

### Detection Sinks
**`capture/DetectionSinks.kt`** — the alert fan-out seam:
`DetectionSink` is one dispatch destination for a detection event's alert
(`suspend fun dispatch(alert, eventId, clipAvailable): Boolean` plus the
`DetectionEventAction` it claims on a `true` verdict), and the file holds
the four adapters over the coordinator's existing dependencies:
`WebhookDetectionSink` (the notifier's JSON POST with the settings'
custom headers), `MqttDetectionSink` (the publisher's event publish),
`WebPushDetectionSink` (the only sink consuming `eventId` and the
`clipAvailable` hint), and `NotificationDetectionSink` (the heads-up
post, gated by the notifications toggle and — sink-private — the
QuietHoursPolicy window: quiet hours silence the phone, not the
integrations). The contract: every sink gets the same `DetectionAlert`
(one snapshot encode feeds the whole fan-out), answers its own go/no-go at
dispatch time (a `false` claims nothing on the log; the settings can flip
while the snapshot encodes), and must not throw — a throw aborts the
coordinator's remaining fan-out AND the event-log write (pinned by test;
the real sinks are fail-open by contract). The coordinator builds the four
adapters as its constructor default in the historical order — webhook,
MQTT, Web Push, local notification — so the composition root's wiring is
untouched; the dashboard's "send test alert" deliberately bypasses the
list (it posts even inside a quiet window and never logs).

### Detection Model Store
**`capture/ml/DetectionModelStore.kt`** — the on-demand home of the ML
detection model: the EfficientDet-Lite0 int8 tflite is *not* bundled in the
APK; this store downloads it once on request into app-private `filesDir/models/`,
hands the resolved file to the Object Detection Engine, and publishes the
lifecycle (`NotDownloaded` / `Downloading(progress)` / `Ready(file)` /
`Failed(reason)`) as a StateFlow for the settings screen's status row. One
entry point, `requestDownload()`, is idempotent — Ready and in-flight models
are never re-fetched, Failed always retries — and the integrity gate is
fail-closed: the download lands in a `.part` file and must match both the
exact expected byte length and the build-time-pinned SHA-256 digest (the
update stack's `UpdateIntegrity` does the hashing and the constant-time
compare) before an atomic same-directory rename installs it; anything else
discards the partial file. The same gate re-runs at resolve time: an
installed file that no longer matches is quarantined and the state demoted
off Ready, so a model corrupted at rest re-downloads instead of wedging with
an engine that cannot load it. The model's provenance (TensorFlow Hub source URL,
Apache-2.0, 320x320 input) lives in the store's KDoc now that the bundled
asset README is gone. Consumers: the coordinator's gate auto-requests the
download on every gated motion event (idempotent in the store, so the first
request after launch or failure is the one that fetches), the App Settings
detection section renders the state and the manual Download/Retry button, and the engine reads
`resolveModelFile()` per init attempt (disk, not state — the file outlives
the process). The dashboard rides the same store: the settings DTO carries
the response-only `mlModelState`/`mlModelProgress`/`mlModelError` fields
(mapped through the store's `wireFields` into the named `ModelWireFields`,
mirrored through the contract fixtures into types.ts and API_DEFAULTS), and
`POST /api/settings/ml-model/download` — token-writable, in the siren/torch
maintenance-action class — fires the same idempotent request; the
SecurityCard renders the status row and the Download/Retry button. Because
the download rides the settings payload with no SSE push of its own, the
dashboard's settings poll runs at the slow cadence always (a download can
also be auto-requested by the device, so it must be discoverable at any
time) and a fast `modelDownload` lane takes the settings fetch over — the
slow lane backs off — while the wire state is `downloading`, so the progress
row moves even mid-stream.

### Sound Classification
**`capture/ml/AudioModelStore.kt` + the sound-classification engine** —
YAMNet, the audio twin of the object gate: the store runs the exact
DetectionModelStore lifecycle for the ~3.9 MB tflite (on-demand download
with a digest gate and quarantine-at-resolve, idempotent requestDownload —
one class per model so each engine, settings row, and download route reads
its own state), and the engine annotates each RMS sound event with class
labels (521 AudioSet classes, 16 kHz mono input, 0.96 s windows;
`YamnetLabels` is the single index→name mapping). Annotate-only by design:
classification never suppresses or delays the RMS event — a missing model,
an API-24- device, or a failed window ships the event unlabeled — with a
confidence threshold, a user-narrowable allow-list of security-relevant
classes, and label-stability/cooldown logic; the labels ride the
webhook/MQTT payloads, the event log, and the dashboard feed like the ML
object labels.

### Object Detection Engine
**`capture/ml/ObjectDetectionEngine.kt`**,
**`capture/model/DetectionClassPolicy.kt`** — the model and policy halves of
the ML motion gate. The engine is the LiteRT task-vision `ObjectDetector`
over the EfficientDet-Lite0 int8 model (resolved per init attempt from the
Detection Model Store — the model file is downloaded, not bundled), loaded
lazily on first gated motion event: the NV21 frame takes the
YuvImage→JPEG round-trip and a power-of-two downscale before the task
library's own 320 px resize, and every failure mode (missing model, broken
init, undecodable frame, inference error) maps to `Unavailable` — the
engine never decides suppression, the caller owns the fail-open semantics.
The init latch is the pure `DetectorInitGate`, and the missing-vs-broken
distinction its API forces on the call sites is load-bearing: only
`onInitFailure` latches (a present-but-broken model, a task-library rejection
— process lifetime), and a *missing* model file never reaches it, so the
download can land at any moment and the next classify retries. The policy is the pure
COCO allow-list (person, common pets/livestock, road
vehicles): `filter` returns the lowercase labels scoring at or above
`mlMinScorePercent`, deduped, in allow-list order (person first) so the wire
`labels` array is deterministic regardless of the detector's result
ordering; an empty result is the only suppression verdict. The persisted
per-group toggles (`mlIncludePerson`/`mlIncludePets`/`mlIncludeVehicles`)
narrow the same list through `DetectionClassPolicy.allowList` — the
person/pet/vehicle groups are the one source `ALLOWED_CLASSES` is built
from, so every label belongs to a group and no un-grouped label can slip
past a toggle.

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

### Retention Policy
**`data/RetentionPolicy.kt`** — the pure time-based retention verdicts for
the capture history and the detection-event log: `cutoffMs` / `shouldDelete`
/ `pruneEntries` over a window in days where 0 (and any negative) means
"keep forever" and every verdict answers conservatively. `pruneEntries`
returns null for "nothing changes" so a no-op sweep skips the persistence
rewrite, and callers supply `nowMs`, so the ladder is JVM-tested.
CaptureHistoryStore sweeps on open, after each append, and on every
MediaStore refresh (victims through `StorageManager.retentionVictims`, so
the files go with the entries); DetectionEventStore sweeps on open and after
each append. Both windows live in the Settings Store
(`captureRetentionDays` / `eventRetentionDays`); no caller re-derives a
cutoff.

### Media Encryption at Rest
**`core/MediaCrypto.kt` + `capture/EncryptedMediaSink.kt`** — opt-in
(default-off), migration-free per-file encryption: one AES-256-GCM blob per
capture under a single hardware-backed Keystore key, at-rest format
`"LCE1" | nonce(12) | ciphertext+tag`, with the real `.jpg`/`.mp4` names
kept. `EncryptedMediaSink` is the one encrypt-on-write seam (both
producers — photos and recordings — funnel through it, and a failed write
deletes the row so MediaStore never holds a half-written capture), and the
Capture Media Resolver's header sniff is the one decrypt-on-read path, so
plaintext and ciphertext coexist forever and the toggle never migrates
anything. Documented trade-offs while enabled: video thumbnails show a
placeholder (the thumbnail decoder needs real mp4 bytes at rest), photos
are served from a decrypted cache file for Coil, and backups upload the
*decrypted* capture so the remote copy is readable evidence.

### Encoded Stream Hub
**`streaming/EncodedStreamHub.kt`** — the shared video/AAC encode pipeline:
camera YUV in, encoded access units out to every registered sink — the RTSP
server (RTP), the HLS ring, the WS video path, and the RTMP and SRT pushes —
with its
start/stop decision the pure `EncodedStreamPolicy` verdict over sink
activity. The
codec seam is `RtspVideoCodec`: the hub lazily instantiates the H.264 or
H.265 encoder (one per codec, cached across flips), reconfigures
stop → new encoder → start on a codec change, and implements the
codec-aware `EncodedSource` seam the RTSP server reads for its SDP and PLAY
sync-frame requests (SPS/PPS of the active codec, VPS H.265-only, so an
H.264 SDP can never see a stale VPS). The fan-out feeds every sink the same
access units except under H.265, where the RTMP and SRT pushes are gated
off (they have no usable H.265 mapping and their start ladders refuse the
codec outright); HLS and WS are codec-aware — the TS muxer's PMT stream
type flips with the codec (manager-owned, alongside a ring reset), and the
WS sidecar opens with a self-describing config message (`LCCF` + avcC for
H.264, `LCHC` + hvcC for HEVC) so legacy WS clients fail cleanly instead of
misconfiguring a decoder (the shipped web player reads `LCCF` only; a
Chromium-side hvcC path is future work).

The frame path also stamps the configured stream overlay into every frame
just before encode — the `EncodedOverlayCompositor` blends the same lines
the MJPEG path renders into the NV21 array, so RTSP and every output riding
this shared encode (HLS, WS video, RTMP, SRT) burn the timestamp in at the
source (WHIP/WHEP encode separately and are not stamped). The blend never
mutates the camera's own analysis array — when rotation and dimensions make
the frame alias it, the compositor blends into a per-size reusable copy,
because the MJPEG pipeline and the WHIP/WHEP taps still read that array
after the fan-out. The sub-stream scales the ROTATED frame, not the
possibly re-scaled main frame: its dimensions move under `frameData`, and
feeding the sub scale the stale source dims corrupted `/sub` whenever the
encode resolution differed from the analysis resolution. The encoder
instantiation is a constructor seam (`encoderFactory`), so the frame path —
including the overlay stamping — is JVM-tested with fake encoders.

### H264 Stream Assembler
**`streaming/rtsp/H264StreamAssembler.kt`** — the wire-format core of the
H.264 encoder path: holds the latest SPS/PPS from both MediaCodec CSD
sources (codec-config buffers and the output format's csd-0/csd-1) and owns
the keyframe prepend decision in `assemble(nalUnits, isKeyFrame)`. Pure over
ByteBuffers, so both CSD paths and the assembly are JVM-tested. The
keyframe verdict fed in is the encoder's `BUFFER_FLAG_KEY_FRAME` flag ORed
with `H264NalParser.containsIdr` (NAL type 5, in the parser — not here),
because the flag alone is vendor-unreliable.

### Media Codec Encoder Harness
**`streaming/rtsp/MediaCodecEncoderHarness.kt`** — the one MediaCodec
lifecycle behind both RTSP encoders: the running guard, the
create→configure→start ladder, the output-drain thread (10 ms dequeue,
format-changed and output callbacks), and the teardown (join, stop/release)
with the exact exception ladder — all behind a thin `CodecLike` seam (the
production adapter wraps MediaCodec), so the invariants — start idempotence,
stop-after-failed-start safety, drain-exit classification, the original
timings — are JVM-tested with a fake codec. `H264Encoder`/`H265Encoder`
keep only their codec knowledge (CSD interpretation, keyframe NAL verdict)
behind the shared `MediaCodecVideoEncoder`, which rides this harness;
`AacEncoder` keeps its format construction and input feeding;
`RtspServer`'s call sites are unchanged.

### Video Packetizers
**`streaming/rtsp/RtpPacketizer.kt`** (RFC 6184),
**`streaming/rtsp/H265RtpPacketizer.kt`** (RFC 7798 single NAL + FU) — the
codec-neutral `VideoPacketizer` seam `RtspServer` fans access units through:
per-start instances over the shared `RtpStreamState` header writer, with
`packetizeAccessUnit` stamping every NAL unit of one frame with the same RTP
timestamp and setting the marker bit on exactly the last packet of the
access unit (per-NAL markers announce frame ends mid-AU, which strict
depacketizers surface as corrupted frame boundaries). The server picks the
implementation at start from the configured codec, so the fan-out and the
RTCP counters stay codec-blind.

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

### Transport Scheme Switch
**`streaming/SseStatusStream.kt`**, **`web/src/lib/transportSwitch.ts`** - the
HTTPS toggle's cross-client handoff. Before the listener is replaced under
the other scheme, the status stream broadcasts a one-shot `transport` event
naming the scheme the replacement will really speak (resolved after the TLS
identity build, so an identity fallback announces plain HTTP). An open
dashboard reloads the same host, port, path and hash under that scheme
instead of reconnecting to the dead listener. The broadcast is bounded and
best-effort: a tab beyond the SSE client cap or with a stalled queue falls
back to its own reconnect. The announcement is sent only when the scheme
actually changes; a port-only apply stays silent.

### IP Address Policy
**`core/IpAddressPolicy.kt`** - the literal codec shared by the TLS identity:
`toBytes` supplies the SAN address bytes, `canonical` supplies the comparison
spelling. Zone suffixes are link-scope metadata and are stripped from both
forms; malformed interface strings answer null instead of throwing. It is a
codec, not a decision policy, despite the repo's policy naming convention -
the two TLS call sites must agree on addressing, so the parsing lives once.

### ONVIF Device Service
**`streaming/onvif/OnvifServer.kt`**, **`streaming/onvif/WsDiscoveryResponder.kt`**,
**`streaming/onvif/OnvifResponses.kt`** — the ONVIF Profile S surface: SOAP
over HTTP at `/onvif/device_service` on the web port (a StreamingServer
dispatch branch ahead of the auth gate — unauthenticated by design, since
LAN ONVIF clients cannot hold the web session cookie; the endpoint answers
only static device metadata plus the stream/snapshot URIs, while RTSP keeps
its own Digest/Basic and the snapshot route stays behind the web gate), plus
the WS-Discovery UDP responder (239.255.255.250:3702, owning the app's one
multicast lock, answering Probes with a rate-limited ProbeMatch). The XML
builders are pure and JVM-tested; configuration arrives as live provider
lambdas, so a port or resolution change needs no restart. The
`onvifEnabled` toggle runs the responder lifecycle through the Settings
Applier; the HTTP side is stateless.

### SSE Detection Event Stream
**`streaming/SseDetectionEventStream.kt`** — the GET
`/api/detection/events/stream` channel: replays the latest 50 events as
`data:` frames on connect (the same per-event JSON the polling GET returns),
then pushes every newly recorded or clip-linked event live, emitting a
`: ping` comment after each silent 15 s. Served by the StreamingServer
outside the JSON router — the never-ending chunked shape of the `/api/events`
status stream (`SseStatusStream`); both channels ride the shared SSE Client
Pump (`streaming/SseClientPump.kt`): one writer thread per client, a
whole-chunk queue, close on disconnect, a bounded lifetime and client cap
(the transport answers 503 when full), and the retry/bye EventSource
framing. The dashboard's `useEventStream` hook consumes it with a
polling fallback.

### Detection Events Screen
**`capture/DetectionEventsScreen.kt`** — the on-device detection-event log,
the app-side twin of the dashboard's event feed: a type-filtered
(Motion/Sound/Tamper) newest-first list with the trigger snapshot, the
dispatched actions, the zone/ML labels, and the linked clip's file name. It
reads the shared Detection Event Store directly — live updates tail
`eventsFlow`, and Clear goes through `store.clear()` on Dispatchers.IO like
the web route's DELETE. It is the notification tap-through target
(`DETECTION_EVENTS_ROUTE` in the Navigation Graph) and owns no copy of the
event data.

### App Graph
**`AppGraph.kt`** — the app's dependency seam: the process-lifetime
collaborators the composition root owns, declared as an interface so screens,
ViewModel factories, services, workers, and streaming components resolve them
without reaching into the composition-root class. Before it, every consumer
did `context.applicationContext as MainApplication`, pulling its
collaborators off the Application at runtime — the dependency direction
inverted; now consumers cast to `AppGraph` and depend on the graph's types.
`MainApplication` implements the interface directly — its existing lazy
`val`s are the overrides, so singleton identity and lazy-init timing are
unchanged — and the Application class is only the graph's Android host (the
class the OS instantiates). The surface is exactly the members consumers
pull; composition-only wiring no consumer reads (the Settings Applier, the
push sender, the update pipeline) stays off it and private to the host. The
deliberate `as? MainApplication` cold-start guards in the boot/tile/widget
receivers keep their Application-class cast: those guards exist to fail
early in a process that has no composition root yet (or a Robolectric
minimal application), which an interface cast would silently weaken.

### Web API Stack Builder
**`WebApiStackBuilder.kt`** — the Web API stack's composition root: builds
the `WebApiStack` (one handler module per domain — see Web API Handlers) by
pulling the app-tier collaborators off the App Graph and the manager-owned
pieces (the siren player, the Web Auth Gate, the context, via the manager's
`appContext()`) off the Streaming Manager. It used to be a private
`buildWebApiStack()` inside StreamingManager, which made the streaming class
a second composition root carrying ~15 app-tier dependencies and a
self-reference through the status handler. The manager now receives the
builder as a required `webApiStackFactory: (StreamingManager) -> WebApiStack`
constructor parameter and keeps the lazy `webApiStack` property — built
once, at the stack's first request (today the transport's eager initial
server build, mid-manager-construction), with the manager handed in as the
status/stream handlers' self-reference, the `this` capture the builder
always relied on. Passing the manager as a value is not a nicety: the first
request lands while the manager's construction is still on the stack, so a
builder that resolved the manager through the graph's
`streamingManager` lazy would re-enter it mid-initialization and recurse
until the heap dies. No default factory — one living in StreamingManager
would re-create the app-tier coupling this move retires.

### Streaming Manager
**`streaming/StreamingManager.kt`** — owns live streaming runtime state (web
MJPEG stream, RTSP server, audio streaming, mDNS). Its thermal monitor is
constructor-owned and its Web API stack is a constructor-injected factory
(see Web API Stack Builder) held in the manager's lazy `webApiStack`;
`StreamingServer` instances *receive* the
`WebApiStack` and the `NetworkQualityMonitor` at construction — the transport
layer never grows its own handlers. The web transport itself — the HTTP/HTTPS
listener and its replace cycle — is delegated to the Web Transport module (see
Web Transport); the manager keeps the public setters and the status mirror. Its setters are its applied interface; the
Settings Applier is their intended caller. One user-facing frame rate fans out
internally via `setFrameRate()` (M-JPEG interval, adaptive default, RTSP), and
one camera frame fans out internally via `pushFrame()` (web pipeline, RTSP
encoder) — the frame listener is wired once at the composition root
(`MainApplication.wireFramePump()`), never in a screen ViewModel. Stream-audio
changes funnel through one internal policy: live-update when the running
server supports it, restart when it doesn't. Auth settings are read live
wherever they're consumed — the Web Auth Gate reference and the RTSP
auth-spec provider both read the applied value directly, so server
recreation re-applies nothing.

### Web Transport
**`streaming/WebTransport.kt`** — the web transport: the phone's HTTP/HTTPS
listener (MJPEG, Web API, WS sidecar, WHEP, HLS) and its replace cycle,
pulled out of Streaming Manager as the last transport without an output
module (the Rtsp Output/Push Transport Outputs pattern). It retains the
(port, TLS) config — settings land here even while stopped, so the next
start picks them all up — and owns the stop → swap → start-if-was-running
cycle, the scheme derivation, and the server state mirror; the manager keeps
its public surface (setters, ensureServerRunning, the URL builders) and its
fan-out. The replace cycle's bug-prevention choreography is the module's
contract: everything runs under one lock (an unsynchronized create-then-start
race once failed an HTTPS toggle with EADDRINUSE); the replacement is
resolved — including the TLS fallback-to-plain verdict — before the old
listener is stopped, so the transport-switch announcement names the scheme
the new listener will really speak; the announcement goes to the live
instance *before* teardown (the SSE frames must reach the socket first) and
only on an actual scheme change; every stop releases the port even after a
failed start (NanoHTTPD binds before it reports, so a failed candidate holds
its socket), and a stop that only cleans up after a failed start is WHEP-silent — WHEP
sessions arm only with a serving transport and die with every stop of one.
The server itself sits behind the **Web Server Handle**
(`streaming/WebServerHandle.kt`) — the narrow start/stop/port interface the
real `StreamingServer` adapter implements; the JVM suite drives the replace
cycle through a fake handle, two adapters justifying the seam.

### Web Push
**`core/push/`** — the phone-as-publisher half of Web Push, all
javax.crypto/java.security (no new dependencies). `VapidKeys` owns the
persistent P-256 identity (a load-once app-private key file — Keystore-backed
EC keys can't do ECDH below API 31); `PushEncryption` is the pure RFC 8291
`aes128gcm` payload ladder (ephemeral ECDH + HKDF-SHA256, 0x02 padding
delimiter, the ephemeral public key riding the header's keyid) plus the RFC
8292 VAPID signer (compact JWT, ES256 raw r‖s, `vapid t=…,k=…`);
`PushSubscriptionStore` persists one shared subscription file to
`filesDir/push_subscriptions.json` (cloud-backup/device-transfer excluded,
like the auth sessions) with endpoint keys, per-row `browser`/`unifiedpush`
kinds, endpoint dedupe, atomic `replacedEndpoint` rotation, and 404/410
pruning that only removes a row whose key material still matches;
`WebPushSender` is the fail-open sink beside the webhook/MQTT publishers —
one daemon worker, one encrypted POST per subscription (`TTL`/`Urgency`/`Topic`
headers, one ephemeral sender key per fan-out, a VAPID JWT cached per
origin+subject, a 5 s transport timeout, a three-consecutive-transport-failure
abort, and a 3993-byte cleartext cap that shortens only the body), the live
`pushEnabled`/`unifiedPushEnabled` + `pushVapidSubject` settings read per
dispatch, failures logged (origin only — an endpoint is a capability URL) and
never thrown. `streaming/web/UnifiedPushWebHandler` is the receiver-facing
registration surface (`GET /api/push/unifiedpush`, token-writable `POST` and
`/remove`), while `PushWebHandler` stays session-only and kind-scoped to
browser rows.

### Secret Wire Type
**`core/Secret.kt`** — the write-only-credential convention owned in ONE
place: `Secret` is a plain identity class riding the settings DTO's
secret fields (rtmpUrl, whipToken, srtUrl, mqttPassword, backupWebdavPassword,
telegramBotToken, apiToken — the TS mirror's unchanged `string`), and
`SecretWireAdapter` (registered once on App Json, the single Moshi
instance) serializes every one of them blank, so GET — and with it the
settings export envelope — can copy the stored values straight into the
response and still never emit a credential. The update half is
`SecretUpdate`: `resolve(incoming, stored)` is the keep-or-overwrite rule
(empty/absent keeps, a presented value overwrites, whitespace is content),
`overwrites` is the write-side guard, and the settings PUT loop, the import
path that reuses it, and the in-app SettingsViewModel all decide through it
instead of hand-rolled `isNotEmpty` guards. Serialization through a Moshi
instance that did not register the adapter fails closed (adapter-not-found),
never leaks. Two shapes were evaluated and rejected: a Moshi
`@JsonQualifier` annotation cannot work — KSP2 cannot run
moshi-kotlin-codegen's qualifier path at all (square/moshi#1874) — and a
`@JvmInline value class` breaks the codegen decode path, because the
generated adapter resolves the DTO constructor reflectively with the
declared parameter types while a value-class parameter erases to its
underlying `String` on the JVM (NoSuchMethodException on the first decode
of any DTO carrying a secret field). The identity class is the shape whose
JVM constructor signature equals its declared one. The one layered
exception is the **apiToken**: the store holds only the SHA-256 hash of the
plaintext, and the enable flag is hash-and-gated —
`SecretUpdate.willHaveSecret` arms it only when a usable credential would
exist after the update (fails closed on a blank hash) — documented at its
site in the Web API Settings Handler. The auth-config route's password pair
predates the type and keeps its own handler-side contract (Web Auth Gate).

### Web API Handlers
**`streaming/web/`** — the JSON-in/JSON-out surface behind the streaming
server's `/api/*` routes, split one handler per domain: `SettingsWebHandler`
(DTO mapping, plus the detection model's response-only status fields and its
download route, and the settings export/import pair — GET
`/api/settings/export` wraps the GET shape in a versioned
`SettingsExportDto` envelope, POST `/api/settings/import` validates the
envelope through the pure `SettingsImportParser` and applies it through the
same `put` path a dashboard save uses; the parser also accepts a bare
settings update document with full PUT semantics — a present section
persists wholesale, so the envelope remains the lossless path), `StatusWebHandler` (status
aggregation, plus the live camera-control truth — torch, zoom, selected
lens, and the device's real control ranges — so the dashboard mirrors the
device instead of hardcoding bounds), `StreamWebHandler`
(lifecycle), `CaptureWebHandler`, `AutomationWebHandler`
(GET/PUT `/api/automation` — the rule's ONE declaration: the state
document embeds the domain `AutomationRule`s directly, decorated only
with the fire-journal-derived `lastFiredAt`; PUT mints blank wire ids
and sanitizes before saving, and both routes answer from a fresh disk
read so the client sees the persisted truth, not what it sent; the
read-only catalogs publish their picker `group`/`icon` kinds and the
`limits` block publishes StreamDefaults' editor bounds — see
Automation Limits), `LensWebHandler`,
`IntervalCaptureWebHandler`, `RecordingWebHandler` (observes the Recording
Controller), `GalleryWebHandler` (media resolution/thumbnails),
`DeterrenceWebHandler` (the siren route), `DetectionEventsWebHandler` (the
event log's list/clear plus the per-event JSON the SSE stream reuses),
`DetectionTestWebHandler` (POST `/api/detection/test` — one synthetic
`EventKind.TEST` alert through the webhook/MQTT/Web-Push/UnifiedPush/notification sinks
only;
never logged to the event store, never armed against the schedule, the ML
gate, deterrence, or quiet hours — the test exists to prove the sinks fire,
so it posts even inside a quiet window; MQTT publishes the event JSON
without a binary-sensor pulse since the test has no entity), and
`AuthWebHandler` (the config/session-management JSON), and
`PushWebHandler` (the browser `/api/push` trio — the public VAPID key,
the session-cookie-only endpoint list with `p256dh`/`auth` redacted, and
subscribe/unsubscribe; deliberately absent from the token allow-list because
a browser subscription is browser-session state, not an automation API), and
`UnifiedPushWebHandler` (the receiver-facing `/api/push/unifiedpush` GET plus
token-writable register and remove — the one push exception, because a
headless receiver has no session cookie and the routes are kind-scoped to
receiver rows). The
`AuditLog` (a capped, atomically-persisted `filesDir/audit_log.json` ring)
is written from exactly three places — the ApiRouter (every POST/PUT/DELETE it
answers, dispatched or unknown-route, success or error, as `"$method $path"`),
the StreamingServer
(`login.success`/`login.failed` with the remote address, only while auth is
armed), and the HttpAuthFilter (`access.denied` with the method and URI when
the viewer-role Role Gate refuses a request — the denial the filter answers
403 is the one audited path outside the router seam) — and read through
`AuditWebHandler` (GET/DELETE `/api/audit`; the
router audits the clear itself, so a wiped trail reopens with
`DELETE /api/audit` as its first entry — a cleared log reads as cleared);
the audit never breaks the audited path. The seam
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
stay behind the router like every other JSON handler, as do the four deliberate
binary-style bypasses `/api/events` (the SSE status stream's never-ending
chunked response), `/api/detection/events/stream` (the detection-event SSE
twin), `/api/audio/uplink` (raw PCM16 body), and the HEAD method (mapped onto
the GET route, headers only, CSRF-safe like GET). The WS sidecar
(`streaming/ws/WsMediaServer`)
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
deletes); the dead `StreamingCard.tsx` is deleted. The detection feed rides
`web/src/hooks/eventStreamCore.ts` (the SSE-with-polling-fallback reducer
the EventFeed and the connection-lost banner consume, vitest-tested), and
the trend sparklines ride `web/src/hooks/useSignalHistory.ts` (a bounded
sample window pushed from status payloads) behind `Sparkline.tsx`. Two
small mirrors keep client verdicts from drifting server-side:
`web/src/armDays.ts` re-states Motion Arming's day-mask bits and
never-clear-last-day toggle (pinned by test against the Kotlin policy), and
`web/src/format.ts` is the one byte formatter the connection panel and the
diagnostics/system cards share. The newer dashboard cards — AuditCard (the
audit trail), SystemPanel (the /api/system triage view), and ConfigBackupCard
(the settings export/import pair) — are one-card clients of the same client
API surface.

### Gallery Page
**`streaming/web/GalleryPage.kt`** — the pure `/api/gallery` pagination:
`of(items, type, page, pageSize)` returns items, filtered total, and the
hasMore verdict with the handler's exact math. `GalleryWebHandler` parses
query params, delegates, and serializes; it serves the store's current
snapshot honestly (no token fire-and-forget refresh inside the request).

### Web Auth Gate
**`streaming/WebAuthGate.kt`** — the auth policy for the web client: session
tokens, rate-limited PBKDF2 login (`login`), request authorization
(`authenticate`), CSRF origin checks (`isCsrfSafe`), and the stateless
API-token verdict (`authorizeApiToken` — GET/HEAD on protected routes except
the auth routes and the browser push subscription list, POST
only on the exact routes Token Write Policy allows, never an `/api/auth/`
route; while the token setting is off the verdict fails closed and the
headers are inert). Owned by the Streaming
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

### Viewer Role Gate
**`streaming/web/RoleGatePolicy.kt`** — the pure viewer-verdict matrix
behind `HttpAuthFilter`'s role gate: ADMIN (and the API token, and
auth-off) allows everything; a VIEWER session reads everything except the
three admin-only GETs (`/api/auth/config`, `/api/auth/sessions`,
`/api/audit`) and writes nothing except its own logout and the talkback
uplink (`/api/audio/uplink` — the doorbell-intercom use; the WS twin
`/ws/talkback` rides the sidecar's cookie gate with no role by the same
decision). Every denial the filter answers 403 is audited as
`access.denied` — the one audited path outside the router seam. Credentials
are the optional second pair on the Web Auth Gate (`setViewerCredentials`);
sessions carry their role, and the token path never mints sessions so it
has no viewer form.

### Token Write Policy
**`streaming/web/TokenWritePolicy.kt`** — the exact POST routes a valid API
token may write (stream/web/RTSP start and stop, `/api/capture`, recording
start and stop, siren, torch, the model downloads, the detection-test alert,
and the UnifiedPush receiver pairing routes). Every path outside the list
stays read-only for tokens. The list deliberately contains no `/api/auth/`
route; under `/api/push/` it contains only the two receiver-facing
UnifiedPush routes, so a bearer token never mints sessions, rotates
credentials, logs out, or registers a browser push subscription. The
UnifiedPush routes are the one deliberate persisted-state exception: a token
holder can enroll a receiver and therefore route future detection alerts to
it, so treat the token as a push-enrollment credential; the browser
subscription list is session-only (GET/HEAD included) and invisible to
tokens. The Web Auth Gate's token verdict consults this list for the method
check, and the list is a deliberate subset of the ApiRouter's registered POST
routes — every listed route is one the router registers, and a route the
router does not register has no business being token-writable.

### Frame Pipeline
**`streaming/FramePipeline.kt`** — the web frame path: YUV in, overlaid JPEG
out. Owns the frame-interval throttle (thermal + adaptive), JPEG quality
resolution, YUV→JPEG conversion with reusable buffers, the conflated queue,
and the processed/dropped counters. StreamingManager gates activity and feeds
`server.updateFrame` through the pipeline's listener; the pixel work sits
behind `push()` without dragging in lifecycle. The accept/reject tick itself
is the shared `FrameThrottle`, and the fps→interval derivation is
`streaming/FrameTiming` (`effectiveFps` falls back to
`StreamDefaults.STREAM_FPS`) — one home each for the web and RTSP push paths.

### RTSP Server
**`streaming/rtsp/RtspServer.kt`** — the RTSP output. Its interface is
`start(initial: RtspConfig)` / `apply(config)` / `feedVideo` / `feedAudio` /
`stop`: `RtspConfig` is one immutable value (video, audio, auth), so the old
order-sensitive setter bag is gone, and both entry points clamp the config to
its `StreamDefaults` bounds (`normalize`). What `apply` does live vs what
restarts is a pure verdict — `RtspConfig.diff(old, new)` classifies each
changed field HotSwap or NeedsRestart (video bitrate genuinely hot-swaps in
the encoder; audio bitrate does not, so it restarts) — the code, not a
comment, owns the promise. Structural changes restart the server (StreamingManager's
call). RTP sequence/SSRC state lives
in per-start `RtpPacketizer`/`AacRtpPacketizer` instances — no global reset
ritual — over the shared `RtpStreamState` header writer. RTP timestamps are
wall-clock anchored (`RtpTimestampClock` — one shared `RtpEpoch` for video,
sub, and audio, so A/V stays aligned; the fixed per-delivered-frame increment
froze players during delivery gaps and fast-forwarded the bursts that
followed). The video fan-out
is keyframe-gated per client: every PLAY (re)arms a per-client wait and
requests a sync frame from the encoded source, and `acceptVideoAu` passes a
client's first bytes only on the next keyframe AU — a mid-GOP join never
receives P-frames it cannot decode. Sending is decoupled from the drain
thread: each ClientSession owns one bounded queue of whole `$`-framed AUs
(video RTP + RTCP, audio, sub — one typed queue preserves the
interleaved-channel ordering) and one daemon writer thread that does the
blocking socket writes; the drain thread only offers, and a congested client
drops its oldest AUs under `RtspSendPolicy` instead of stalling the encoder
and every other sink. YUV rotation and
conversion live in `core/YuvConverter`, encoder color-format selection in
`EncoderFormatPolicy`; the session/track state machine stays here, but the
protocol knowledge behind it is pure: `RtspRequest` (wire parsing),
`RtspSessionAuthorizer` (Digest/Basic ladder + the nonce store it owns),
`RtspUriPolicy` (track-id grammar, path normalization, method allow-list),
`RtspSessionProtocol` (the SETUP transport verdict, the CSeq monotonicity
ladder, the Session-header parse, PLAY's RTP-Info, the RTCP sender-report
bytes, and the `$`-interleaved framing — all byte-pinned by test),
and `SdpBuilder`. ClientSession only reads requests, asks the authorizer,
consults the policies, and enqueues frames (its writer thread delivers them)
— the security surface is JVM-tested without a socket.

### RTP Timestamp Clock
**`streaming/rtsp/RtpTimestampClock.kt`** — the RTSP media clock as pure,
injectable math: `RtpEpoch` anchors lazily on the first `elapsedMs()` over a
`nowMs` source (RtspServer passes `SystemClock.elapsedRealtime()`), and
`RtpTimestampClock(clockHz, epoch)` maps elapsed milliseconds to ticks
(`elapsed * clockHz / 1000`), clamped strictly increasing (`computed <= last`
⇒ `last + 1` — the HlsManager anchor shape, so a backwards or stalled clock
never rewinds or repeats a timestamp). One shared epoch with three clocks —
video and sub at 90 kHz, audio at the configured AAC sample rate — keeps A/V
aligned while each stream's tick rate stays its own; `reset()` re-anchors on
server start. `peekNext()` is the non-anchoring lower bound PLAY's RTP-Info
reports (a DESCRIBE/PLAY exchange must not start the media clock); `current`
is the last emitted tick. Timestamps grow unbounded as Longs — the wire
writers already mask to 32 bits (`RtspSessionProtocol.senderReport`,
`RtpStreamState`). The retired fixed `90000/fps` per-delivered-AU increment
(`FrameTiming.rtpClockIncrement`) was the freeze-then-catch-up bug: irregular
delivery (throttle, lag gate, thermal) made the media clock stop during gaps
and jump during bursts, so players froze and then fast-forwarded. JVM-pinned
including the 500 ms-stall regression (a ~45 000-tick jump, not 3 000).

### RTSP Send Policy
**`streaming/rtsp/RtspSendPolicy.kt`** — the per-client congestion verdict at
the queue offer, pure and JVM-tested: `onOffer(queueSize, capacity,
isKeyframeAu, nowMs, lastKeyframeRequestMs)` returns drop count (oldest whole
AUs, `maxOf(0, size + 1 - capacity)`, capacity clamped ≥1 — the incoming AU
is never dropped), keyframe-gate re-arm, and keyframe request. Two load-bearing
rules: a drop re-arms the keyframe gate **unless the incoming AU is itself a
keyframe** (it is the fresh sync point — rearming past it would stall a whole
GOP), and keyframe requests ride a 1000 ms cooldown (without it, sustained
congestion requests a sync frame per drop and the encoder goes all-intra —
bitrate explosion). The accepted tradeoff: a congested client gets
GOP-cadence refreshes, not growing latency. The queue bound is 8 AUs
(~330 ms at 24 fps). Beside it, **`RtspSendTap.kt`** windows the writers'
real send-time samples (≥100 ms before emitting): per-AU LAN writes finish
sub-millisecond, and `NetworkQualityMonitor.recordEncodedSend` drops
`durationMs <= 0` samples — an unwindowed tap at the writer would silently
disable adaptive encoded bitrate. The tap fires only from the writer threads
measuring blocking writes, never from the drain thread's offers.

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

### RTSP Resolution
**`streaming/rtsp/RtspResolution.kt`** — the RTSP output's resolution choices
as the one pure wire-name → (width, height, aspect) mapper: 480p (4:3),
720p (the default), 720p43, 1080p, 1080p43, with the tolerant
`fromWireName` fallback-to-default decode. Persisted as `rtsp_resolution`
and applied by the Settings Applier onto the encoded-stream hub's retained
dimensions; a width/height change is NEEDS_RESTART because MediaCodec
dimensions are set at `configure`. The entries deliberately do not use the
camera `Resolution` enum — the RTSP output encodes a fixed-size stream of
its own choosing, independent of the preview's capture resolution. The
*aspect* does cross into the camera, though: a dedicated Settings Applier
collector lands it on CameraService's `setStreamAspectRatio` seam, and the
streaming analysis window follows (960×720 for 4:3, the 1280×720 ceiling
for 16:9 — `analysisSizeFor`), so a 4:3 tier encodes the full sensor width
instead of cropping 16:9 analysis frames. Incoming frames are aspect-filled
onto the configured size (`YuvConverter.scaleNv21`), so the stream is
exactly that size whatever the camera analysis produces. Two accepted
crops: a sensor without a 4:3 analysis mode falls back (CameraX
closest-lower-then-higher) to a 16:9 frame that aspect-fills with a centered
horizontal crop, and a small capture (SD_480P's 720×480) passes through
unchanged and aspect-fills the same way — no distortion either way.

### RTSP Video Codec
**`streaming/rtsp/RtspVideoCodec.kt`** — the wire name → codec mapper
(`h264` default, `h265`) behind the encoded-stream hub's encoder selection,
the server's packetizer choice, and the SDP's media line. The H.265 side is
the HEVC stack: `streaming/rtsp/H265NalParser.kt` (the 2-byte-header NAL
decode; IDR is types 19/20, CRA deliberately not a keyframe verdict),
`streaming/rtsp/H265StreamAssembler.kt` (VPS/SPS/PPS from either CSD shape,
the keyframe prepend decision),
`streaming/rtsp/H265Encoder.kt` (the H264Encoder sibling over the shared
`MediaCodecVideoEncoder` — intra-refresh suppressed for the same
no-IDR-to-join reason), and
the RFC 7798 packetizer under Video Packetizers. Persisted as
`rtsp_video_codec`; the swap is NEEDS_RESTART. Under H.265 the fan-out is
codec-aware: RTSP always receives, the HLS TS muxer flips its PMT stream
type and the WS sidecar sends the `LCHC` hvcC config (see Encoded Stream
Hub), while the RTMP and SRT pushes are gated off at the hub's fan-out.

### Push Transport Outputs
**`streaming/PushOutput.kt`** — the deep module the three push transports
are configurations of (the `RtspOutput` pattern collapsed across the
twins). `PushOutput<C, P, H, S>` owns everything "a push output" means:
the retained connect config (`RtmpPushConfig`/`SrtPushConfig`/
`WhipPushConfig` — small value classes over `PushConnectConfig`; settings
land even while stopped, so the next start picks them all up), the enabled
gate, the start-time validation ladder (enabled → URL parse → optional
H.264 codec gate), the publisher lifecycle with restart-on-config-change
through one `update(trigger, transform)` entry, and the status mirror. The
per-transport configuration points: `parse` (the pure `RtmpUrl`/`SrtUrl`/
`WhipUrl` decode), `videoCodec` (the H.264 gate — RTMP and SRT gate, WHIP
passes null), `publisherFactory` + `startPublisher`/`stopPublisher` (the
per-transport handle seams, untouched), `describeTarget` (the log
redaction seam — SRT's `SrtUrl.redacted`), `idleStatus`/`errorStatus` (the
transport status constructors), and `beforeStart` (WHIP's frame-rate
handoff). The manager-driven `start`/`stop` run an injected `settle`
epilogue (the encoded-hub refresh + streaming-state mirror; WHIP settles
the streaming state only) — internal restarts and disable-stops settle
quietly, exactly the pre-collapse ordering. The WHIP mic-arbitration
restart rides `PushRestartTrigger.WHILE_ACTIVE`: the verdict is a
publisher-construction input, so `onMicVerdictChanged` restarts a live
output without a config field moving. The `RtmpStatus`/`SrtStatus`/
`WhipStatus` wire contracts are unchanged; they extend the shared
`PushStatus` base. `StreamingManager`'s push setters keep their names and
semantics; the JVM suites are `PushOutputTest` (the shared lifecycle run
against all three configurations) plus the per-transport suites
(transport-distinct wiring only).

### RTMP Push Output
**`streaming/rtmp/`** — the RTMP configuration of the shared
`PushOutput` module (see Push Transport Outputs): `RtmpOutput` supplies the
retained URL (settings land even while the output is stopped, so the next
start picks them up), the enabled gate, the start-time validation ladder
(a usable URL parsed by the pure `RtmpUrl`, plus the H.264 codec — H.265
has no standard RTMP mapping, so a push under it is refused with a
readable error), and the publisher lifecycle with restart-on-URL-change;
`RtmpPublisher` speaks the wire protocol (handshake, AMF0 connect with
tcUrl/userinfo credentials, FLV-tagged audio/video chunks, the
FCUnpublish + deleteStream clean close) with a capped-backoff
auto-reconnect while enabled. The URL is a write-only credential over the
Web API (the stream key is its last path segment), and a portless
authority defaults to 1935 for `rtmp` and 443 for `rtmps`. StreamingManager
keeps the public surface and the hub fan-out.

### SRT Push Output
**`streaming/srt/`** — the SRT configuration of the shared `PushOutput`
module, the RTMP twin over MPEG-TS/UDP: `SrtOutput` supplies the retained
`srt://` URL (parsed by the pure `SrtUrl`, port defaulting to 9710), the
same H.264 codec gate (the shared TS muxing is H.264-pinned even though
MPEG-TS itself carries HEVC), and the publisher lifecycle with
restart-on-URL-change; `SrtPublisher` handshakes and streams with a
capped-backoff auto-reconnect. The URL is a write-only credential over the
Web API — userinfo and streamid never echo; `SrtUrl.redacted` is the only
rendering that reaches a log, handed to the deep module as its
`describeTarget` redaction seam. `SrtOutput.stats()` is the wire-stats
read seam (RTT, loss counts) for the status surfaces.

### WHIP Push Output
**`streaming/whip/`** — the WHIP configuration of the shared `PushOutput`
module (see Push Transport Outputs), the WebRTC push (RFC 9725):
`WhipOutput` supplies the retained endpoint/token/STUN settings, the
enabled gate, and the start-time validation ladder (enabled + a usable URL
parsed by the pure `WhipUrl` — no codec gate, because libwebrtc encodes
its own H.264 from the NV21 analysis tap, independent of the RTSP codec),
plus the mic-arbitration verdict: evaluated at publisher-construction
time, with `onMicVerdictChanged` routing the verdict flip through the deep
module's WHILE_ACTIVE restart (a live push rebuilds with the fresh
verdict; a stopped one only remembers it). The publisher is one WHIP
session: ICE one-shot with a ~3 s gather cap (blank STUN = host candidates
only, LAN reach), SDP offer/answer against the endpoint with the optional
bearer token, a DELETE teardown of the session resource, and the status
mirrored onto the snapshot exactly like RTMP's. The endpoint carries no
embedded secret so it round-trips; the token is write-only like every
other credential.

### WHEP Viewer Endpoint
**`streaming/whep/`** — the WHIP push's egress twin: browsers POST an SDP
offer to `POST /whep` on the main HTTP server and watch the live camera
sub-second over WebRTC. `WhepServer` owns the sessions: one shared
libwebrtc environment (factory + `VideoSource` tapping the same NV21
analysis frames WHIP consumes, plus one shared 16 kHz mono
`JavaAudioDeviceModule` when WHIP's mic-arbitration verdict allows audio)
under per-viewer `PeerConnection`s, each with its own hardware H.264
encoder — H.264 only by construction, independent of the RTSP codec. The
handshake is one-shot ICE like WHIP (answer after the ~3 s capped gather,
candidates inline, no trickle), answered `201` with `Location:
/whep/{id}`; `DELETE /whep/{id}` (or endpoint stop) disposes the session,
and the pure `WhepSessionRegistry` GCs the rest — never-connected offers
past the 10 s window and sessions DISCONNECTED past a 30 s grace — so a
dead tab can never hold an encoder slot; `WHEP_MAX_VIEWERS` (the RTSP
max-4 convention) caps concurrency at 503. The pure `WhepSdp` gate is the
only code that touches a viewer's offer before libwebrtc (bounded bytes,
line count, printable ASCII, `v=0` first, an `m=` video section required),
so hostile bodies answer a readable 400 without reaching the native parser.
The routes are transport branches served by `StreamingServer` outside the
JSON router (SDP is not the JSON-handler contract, like ONVIF), gated by
the Web Auth Filter as media egress: session cookie, or the API token's
GET verdict for POST/DELETE (curl viewers need no session cookie), and the
Role Gate allows a viewer session's WHEP verbs as reads. The status
snapshot's `whepClients` mirrors the live count; the dashboard's player
ladder rides WHEP as the top rung (`web/src/lib/whepClient.ts`).

### AAC Format
**`streaming/rtsp/AacFormat.kt`** — one home for the AAC stream's format
literals and pure capture math: samples-per-AU (1024), PCM bytes per
sample, the mic probe ladder with default from `StreamDefaults`,
`resolveBuffers` (frame-aligned read chunk and record buffer sizing, the
former `AudioStreamingManager` inline math), the AudioSpecificConfig bytes,
and the SDP fallback hex derived per call from the actual rate/channel count
(`fallbackAscHex` — never a single historical literal, which advertised stereo
for the mono default). Encoder, RTP clock,
SDP, and mic capture reference the same symbols. The audio RTP clock is
wall-clock anchored at the configured sample rate (`RtpTimestampClock`) —
the old per-AU fixed-increment contract against RtspServer was retired with
the freeze-then-catch-up fix (at a steady 48 kHz the two agree: one AU per
~21.3 ms ≈ 1024 ticks).

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
registry: named, distinct IDs (recording 1001, runtime service 1002 for both
server and streaming modes, update 1003, interval capture 1004 — the interval
worker's old private 1002 collided with streaming's), the pure message builders
(`streamingMessage`, `serverMessage`,
`intervalCaptureMessage`), and the promotion variants — camera and
connected-device `Service.startForeground` paths (microphone only when the
permission is actually held) plus the camera-typed WorkManager
`ForegroundInfo` builder. StreamingService, RecordingService,
IntervalCaptureWorker, and UpdateNotifier reference the registry; no private
ID constant survives, so a new foreground owner cannot silently collide.

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

### Jpeg Downscaler
**`core/JpegDownscaler.kt`** — the one JPEG downscale ladder behind every
consumer that re-encodes a JPEG smaller: decode at the largest power-of-two
sample still reaching the target, encode, and — while the caller's size gate
fails — halve the decode and retry, so a busy frame ships a smaller image
instead of no image; null on missing or undecodable input. Consumers: the
detection-event snapshot encoder (the 40,000-character webhook/log cap) and
the gallery photo thumbnails (512 px).

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
`selectApkAsset` (the APK for the device's best supported ABI, else the
universal APK, else any APK), the release-asset
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
`OVERLAY_MARGIN_PX`), plus `REFERENCE_WIDTH` — the width both outputs scale
font sizes against, so identical settings render proportionally identical
text on the MJPEG canvas and the encoded frames.
`StreamOverlayRenderer` keeps the Bitmap/Canvas adapter work
(`renderTextOverlayBitmap` rasterizes the overlay box standalone — the one
styling source the MJPEG canvas draws and the encoded path converts) and
delegates every number — and every line decision — to the policy, so the
privacy-critical rect math and the overlay text are JVM-tested without a
device.

### Encoded Overlay Compositor
**`streaming/EncodedOverlayCompositor.kt`** — the encoded path's twin of the
MJPEG overlay pass: blends the configured overlay lines into the NV21 frames
`EncodedStreamHub` feeds its encoders, so RTSP (and every output riding the
shared encode) shows the same timestamp/branding/custom text the web stream
does. The cost split keeps the frame path cheap: rasterization and ARGB→
YUVA conversion (BT.601 studio swing, `YuvConverter.argbToYuvaStrip`) run
only when the rendered content can have changed — a new timestamp second, a
settings swap, a different encode size (the MJPEG web viewer count is
deliberately NOT rendered on this path); between renders the per-size cached
strip alpha-blends into the frame via the pure
`YuvConverter.blendNv21Overlay`, and a disabled overlay is one volatile
read. The rasterization seam (the shared text-box bitmap in production,
fakes in tests) and the blend math are both JVM-tested; the visual parity
between the two paths is the reason the text-box bitmap is shared.

### Status Snapshot Builder
**`streaming/web/StatusSnapshotBuilder.kt`** — the pure `/api/status`
aggregation: typed `StreamingInputs`/`ThermalInputs`/`BatteryInputs`/
`WatchdogInputs`/`AdaptiveInputs`/`NetworkInputs` in, one
`StatusResponseDto` out (adaptive null-when-disabled, connectionQuality
null-when-idle, first-client fps). `StatusWebHandler` only collects flows and
delegates — the dashboard/API mapping is tested without a manager.

### Quiet Hours Policy
**`capture/QuietHoursPolicy.kt`** — the pure quiet-hours verdict for local
detection alerts: when the persisted window is enabled, the Detection
Coordinator holds the heads-up notification for events inside it (the same
midnight-wrapping minute-of-day semantics the arm schedule uses). Webhook,
MQTT, recordings, and the event log are untouched — quiet hours silence the
phone, not the integrations. The `notify` action only lands in the event log
when a notification actually posted, so a quiet-held alert reads honestly.
The dashboard's test alert is deliberately exempt — it exists to verify the
notification sink, so it posts even inside the window.

### Adaptive Noise Floor
**`capture/SoundDetectionPolicy.kt` (`AdaptiveNoiseFloor`)** — the optional
sound-trigger ladder: a slow asymmetric EMA of the measured RMS (rises at
α=0.15, falls at α=0.02) and `effectiveThreshold` = max(user threshold,
floor + 10%). A constant ambient neither masks real events nor trips the
detector on its own; the slow fall keeps a sustained loud event from walking
the floor up to self-suppression within its own duration, and the first
sample anchors the floor outright (ramping from zero would trip the detector
on any ambient louder than the user threshold while the floor converges).
The toggle is persisted as `sound_adaptive_noise_floor` and
applied by the Settings Applier alongside the threshold and the sound
cooldown (motion and sound carry their own persisted cooldown settings).

### Recording Session Index
**`capture/model/RecordingSessionIndex.kt`** — the pure grouping/classification
behind GET `GET /api/recordings/sessions?day=YYYY-MM-DD`: the requested local
day's video captures as NVR timeline sessions. Ends are inferred (persisted
duration → next capture's start, capped → 60 s fallback) and triggers are
reconstructed from the persisted detection events — `motion`/`sound` when an
event overlaps the capture window (small lead tolerance), `manual` otherwise;
continuous/scheduled origins are deliberately not recoverable from history.
The handler (`streaming/web/RecordingSessionsWebHandler`) only joins
CaptureHistoryStore + DetectionEventStore and serializes; day parsing
degrades a missing/invalid `day` to today. JVM-tested; contract-pinned by
`web/contract/recording-sessions.json`.

### Eco Idle Policy
**`core/EcoIdlePolicy.kt`** — the pure decision core behind the eco idle-fps
mode (`ecoIdleFpsEnabled`, default off): when the toggle is on, the device is
off charger, no stream consumer is connected (MJPEG/RTSP/WS clients, HLS
fetches, RTMP push), and thermal is NORMAL, the effective frame rate drops to
`StreamDefaults.ECO_IDLE_FPS` and live-audio encoding pauses; any consumer,
charger, or thermal event restores the full rate immediately. Priority is
explicit — eco applies only while thermal is NORMAL, so the ladders never
stack. Anti-thrash: the idle conditions must hold `IDLE_STABLE_MS` before the
first drop and a `RESTORE_COOLDOWN_MS` window after each restore blocks the
next one. StreamingManager hosts the poll loop and applied side effects
(frame-rate fan-out re-applied over the remembered user rate, audio refresh);
verdicts are JVM-tested.

### Detection Stats Policy
**`capture/DetectionStatsPolicy.kt`** — the pure aggregation behind GET
`/api/detection/stats`: per-type counts over the 24 h / 7 d / all-time
windows, a seven-day per-UTC-day series (zero days included, oldest first),
and the top zone/ML labels. Caller-supplied clock, JVM-tested; the handler
(`DetectionEventsWebHandler.stats`) only maps to the DTO. The event log's
`limit`-style read gained a `type` filter at the same time
(`GET /api/detection/events?type=` and the export route, with the feed's
`total` counting the filtered set; the name decodes through
`EventKind.fromWireNameOrNull` — an unknown `type` answers with the
handler-error payload, never a silently empty feed), and `GET
/api/detection/events/export` downloads the log as RFC-4180 CSV (default) or
a bare JSON array — snapshots deliberately omitted. CSV cells that begin
with a formula trigger (`=`, `+`, `-`, `@`, TAB) carry an apostrophe guard,
so a user-authored zone or label like `=SUM(A1)` never executes as a
formula when the export opens in a spreadsheet app.

### System Info Endpoint
**`streaming/web/SystemWebHandler.kt`** — the read-only GET `/api/system`
triage snapshot: app version, device model/manufacturer, Android
version/SDK, OS and process uptimes, extended battery facts (temperature,
voltage, health off the sticky BATTERY_CHANGED intent; level/charging from
the same PowerManager the status endpoint uses, so the two never disagree),
and storage (capture bytes vs the configured quota plus StatFs volume
free/total). Every read degrades to a safe value — a headless triage page
must never 500.

### Storage Quota
**`data/CaptureHistoryStore.kt` (`quotaMb` provider)** — the once-hard-coded
2 GB quota is now the persisted `storage_quota_mb` setting (100 MB–32 GB,
clamped by its descriptor), read per enforcement pass the same live-read way
as retention days. Every pass (append, low-disk floor, `enforceQuota`, the
storage bar, `/api/system`) routes through `StorageManager.quotaBytes` over
the live value — the default stays 2 GB, so existing installs behave
identically until the knob is turned (in-app Storage section, web
StorageCard).

### Wear Companion
**`wear/`** — the Wear OS remote, deliberately zero-coupled from `:app`: it
talks to the phone over the same Web API (`WearApiClient` with the pure
`WearRequestUrls` path builder, org.json for its own decoding — no App Json
or any app-module dependency by design, keeping the wear APK minimal and
its manifest INTERNET-only), `WearRemoteController` drives the
stream/recording lifecycle actions, and `WearSettingsStore` holds the phone
base URL and credentials locally on the watch.

### Automation Rule
**`automation/schedule/AutomationRule.kt`** — one if/then rule as the user
configured it (WHEN a trigger, ONLY WHEN optional conditions, THEN
actions): a stable `id` (the Fire Journal's key; the writers mint it — the
web handler and the in-app ViewModel at save, the store's serializer
persisting one for any writer that sent none, so a null id never lands on
disk, and the store decode mints for legacy payloads without one — because
an id-less rule could never be journaled and would re-fire, and an id that
re-minted on every decode would orphan its journal entries just the same),
a free-form `name`, a per-rule `enabled`
kill switch independent of the master toggle, a `triggerType` discriminator
out of the Automation Trigger Catalog (`time_of_day` is the default, so old
time-only payloads decode unchanged; the sanitize step resets anything else
to it, so an unknown future trigger degrades to a time trigger instead of
firing on the wrong cue), the trigger's own `triggerParams` string map
(time_of_day ignores it — its "params" stay the top-level local
`minuteOfDay` 0..1439 and `daysMask` in the Motion Arming Policy bit
convention: bit 0 = Monday … bit 6 = Sunday, 127 = every day, one mask
semantics, not two), the ordered `conditions` list of
`AutomationConditionSpec`s (an EMPTY list is unconditional) with a
`conditionMode` of `"all"` (default) or `"any"`, a `cooldownSeconds` floor
for event triggers (0..86400, default
`StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS` = 60), a
`missedRunPolicy` for time triggers — what happens when the scheduled
moment passes without a firing (`"catch_up"` = the historical catch-up
behavior and the fold target for anything unknown; `"skip"` = at the
scheduled time or not at all; `"window"` = fire within
`missedWindowMinutes` 1..1440 of the scheduled minute with every action
riding, the rule window overriding the per-action ONESHOT grace; only
time rules can miss, events fire in the present) — and the ordered
action list — and `lastFiredAt`, a read-only TRANSPORT decoration: the GET
response builder (`AutomationWebHandler.buildState`'s
`copy(lastFiredAt = …)`) decodes it from the Fire Journal for the
dashboard's "Last ran" line; `sanitizeRules` resets it to null, so a
client echoing a response rule back in a PUT body cannot smuggle a claimed
fire instant into persistence, and the field never lands on disk.
Deliberately a plain wire/persistence shape: every decision (when a rule is
due, what its entries mean) lives in the Automation Schedule Policy and the
three catalogs, keeping the scheduling surface JVM-testable. ONE
declaration: the domain class (@JsonClass) IS both the persistence shape
and the wire shape — the Settings Store serializes it directly through the
shared Moshi codec and GET/PUT `/api/automation` embeds it in the state and
update documents; the former `AutomationRuleDto`/`AutomationRuleJson`
clones are gone. The store codec still guarantees a total decode (a
malformed payload degrades to an empty list; a pre-if/then payload's new
fields fold to their defaults) and hands every rule through `sanitizeRules`
before it is trusted — the data-class defaults are decode-fallbacks, not
trusted wire values.

### Automation Action Spec
**`automation/schedule/AutomationRule.kt` (`AutomationActionSpec`)** — one
step inside an Automation Rule: a catalog action id plus its parameters,
all serialized as strings so the persisted shape never needs schema
versioning when an action gains a parameter. Validity and typing are the
Automation Action Catalog's job at sanitize time: an id outside the catalog
drops the whole action, and so does an unknown param key (a schema
violation, not a value problem — a writer that believed in a parameter this
build has never heard of cannot be trusted on the others); present values
are parsed and clamped to the param's documented bounds, absent optional
params stay absent (the executor's "leave unchanged" semantics), and
absent or invalid required values fold to the documented default.

### Automation Action Catalog
**`automation/schedule/AutomationActionCatalog.kt`** — ids, param schemas
(names/kinds/bounds/defaults/autoAtZero) and catch-up classes are
single-sourced here; per-type EXECUTION and pretty labels dispatch at the
executor/UI seams by design (exhaustive `when`s over a closed set), so
adding an action touches the catalog plus those seams. Eleven action types
(`camera_exposure`, `camera_ev`, `camera_zoom`, `camera_fps`, `camera_lens`,
`torch`, `stream`, `capture_photo`, `recording`, `siren`, `detection`),
each with its parameter schema (wire name, BOOL/INT/FLOAT kind, bounds,
optionality, default, and the `autoAtZero` pin — true exactly on
`camera_exposure.iso`/`exposure_time_ms`, whose "0" means auto, so clients
may render an Auto toggle for them; the duration params' "0" means "no
limit", an ordinary value), its catch-up class (STATEFUL converges on a
setting so it always rides the catch-up; ONESHOT — only `capture_photo` —
is a moment effect and fires only inside the one-shot grace), and an
`interruptsStream` flag (only `camera_lens`) that is a UI warning flag
only: the executor does NOT force a teardown — the lens switch goes through
`CameraService.selectLens`, whose `CameraSessionArbiter` rebinds
immediately when the camera is free (a brief preview/stream interruption)
and defers to the next natural rebind while an exclusive session
(recording) is active.
Additive-only by contract: ids and param names are persisted forever and
pinned by a JVM test, so a typo fails a test instead of silently disabling
a feature. Documented sentinels: `iso`/`exposure_time_ms` "0" mean auto,
`recording.duration_seconds` "0" means unbounded, and
`siren.duration_seconds` absent means run-until-stopped while "0" means an
explicit no-limit. `sanitizeAction` is the one validate-and-canonicalize
ladder (the Settings Store's codec, the Web API PUT, and the schedule
policy's filter all ride it), and GET `/api/automation` publishes the
catalog read-only as device truth (alongside the trigger and condition
catalogs) — now carrying each entry's picker `group` (semantic ids:
`camera`/`light_sound`/`stream_capture`/`detection`) and `icon` kind, so
both editors group and badge the entries from this one source and never
send them back.

### Automation Trigger Catalog
**`automation/schedule/AutomationTriggerCatalog.kt`** — the WHEN side's
closed vocabulary, mirroring the Automation Action Catalog's shape (same
`AutomationParamSpec` schema type, additive-only ids pinned by a JVM test,
per-type label dispatch at the UI seams). Nine trigger ids: `time_of_day`
(the default; special-cased — its "params" are the rule's top-level
`minuteOfDay`/`daysMask`, so old payloads decode unchanged),
`motion_detected`, `sound_detected`, `tamper_detected` (the three
parameterless detection events), `charging_changed` (`charging` BOOL,
default true = on the connect edge), `battery_level` (`level` INT 1..99
default 20, `when_below` BOOL default true — the threshold-CROSSING
verdict, not a level poll: fires when the previous level was on one side
and the current is on the other; the first level after process start has
no previous and never fires), `streaming_changed` (`streaming` BOOL),
`viewers_changed` (`connected` BOOL — the viewer 0↔≥1 edge), and
`app_started` (no params). It also defines the sealed `AutomationEvent`
hierarchy the wiring hands the scheduler (`AppStarted`, `MotionDetected`,
`SoundDetected`, `TamperDetected`, `ChargingChanged(charging)`,
`BatteryLevelChanged(previous, current)`, `StreamingChanged(streaming)`,
`ViewersChanged(connected)`) and `triggerMatches`' raw material:
`sanitizeParams(triggerId, raw)` returns null for an unknown id or an
unknown param key — a writer that believed in a parameter this build has
never heard of cannot be trusted, so the whole params map degrades to the
schema defaults (required params fold in) and the rule survives. Like the
other two catalogs, every entry carries a picker `group`
(`schedule`/`device_events`/`power`/`stream_app`) and an
Automation Icon Vocabulary `icon` kind, published on the wire.

### Automation Condition Catalog
**`automation/schedule/AutomationConditionCatalog.kt`** — the ONLY WHEN
side's closed vocabulary, same catalog discipline as the trigger catalog:
eleven condition ids with param schemas, additive-only and test-pinned.
`time_window` (`start_minute` 0..1439 default 0, `end_minute` default 1439,
`days_mask` 1..127 default 127 — a start > end window wraps across
midnight via the same `MinuteWindow.covers` the arm schedule uses, while
start == end covers nothing), `charging`/`wifi_connected` (BOOL state
matches), `battery_level`/`viewers` (optional inclusive `min_`/`max_`
ranges — one-sided when only one bound is present; an unknown battery
level is never met rather than guessed), `power_save`, `streaming`,
`torch`, `recording`, `thermal` (normal vs throttling), and
`motion_detection` (the toggle state). `sanitizeCondition` drops an
unknown id or unknown param key entirely — an unknown condition is
*never met*, the safe direction (a rule with degraded conditions asks
for less firing, not more). Like the other two catalogs, every entry
carries a picker `group` (`time`/`power`/`connectivity`/`device_state`)
and an Automation Icon Vocabulary `icon` kind, published on the wire.

### Automation Limits
**`streaming/model/WebApiDtos.kt` (`AutomationLimitsDto`), sourced from
`core/StreamDefaults`** — the editor contract's bounds/defaults/presets as
ONE Kotlin source: GET `/api/automation`'s `limits` block projects the
StreamDefaults automation constants onto the wire (`maxRules` 16,
`maxActionsPerRule`/`maxConditionsPerRule` 8, the cooldown clamp
0..86400 with default 60 and the preset chips
`AUTOMATION_COOLDOWN_PRESET_SECONDS` = 30/60/300/900/1800/3600, the
missed-run window clamp 1..1440 with default 30 and the preset chips
`AUTOMATION_MISSED_WINDOW_PRESET_MINUTES` = 5/15/30/60/180). Only what the
sanitizer actually enforces is published — no invented caps. Read-only:
the catalogs and limits are never accepted back on the wire. The web
dashboard's pre-fetch fallbacks (`web/src/api/defaults.ts`) are pinned to
this block by the three-way contract fixtures
(`DtoContractFixtureTest` ↔ `web/contract/automation.json` ↔
`web/src/contract.test.ts`), which is the fence that retired the old
hand-mirror. The icon KINDS and picker GROUPS ride the same principle:
`automation/schedule/AutomationIconVocabulary.kt` is the closed 29-kind
set every catalog entry publishes (the app's `AutomationVisuals` maps
kind → drawable, the web's `automationIcons.tsx` maps kind → glyph), and
the `group` ids (`AutomationTriggerCatalog.GROUP_*` and friends) are the
one grouping both editors render from — `AutomationVisuals` derives its
member lists by filtering the catalogs, no hand lists.

### Automation Sensor Snapshot
**`automation/schedule/AutomationSensorSnapshot.kt`** — the pure data
class both rule families evaluate conditions against: the clock triple
(`minuteOfDay`/`epochDay`/`isoDayIndex`, the same wall-clock space the
schedule policy keys occurrences in) plus the live-state fields —
`charging`, `batteryLevel: Int?` (null = unknown), `powerSave`,
`wifiConnected`, `streamingActive`, `viewerCount`, `torchOn`,
`recordingActive`, `thermalNormal`, `motionDetectionEnabled`. Built by the
`AutomationSensorSnapshotProvider` fun interface, whose production
implementation lives in the Automation Event Wiring (it reads the app's
existing flows/pulls — PowerManager, ConnectivityMonitor, ThermalMonitor,
StreamingManager, CameraService, RecordingController, the Settings Store)
and whose tests substitute a fixed snapshot, keeping the condition policy
JVM-testable with no Android imports.

### Keep Awake
**`core/KeepAwakeController.kt`** — the keep-awake setting's runtime arm and
the app's awake posture: while `keepAwakeEnabled` (on by default — the
security-phone posture is the default and quitting is the explicit act) the
controller holds a `PARTIAL_WAKE_LOCK` (`"LensCast::KeepAwake"`, no timeout,
its own slot in `core/PowerManager.kt`) for the process's whole life, so the
automation engine's 30-second poll and every in-process event stream keep
running with the screen locked. Process death is the release guarantee; the
startup reconcile is a two-phase machine — the disk read seeds the hold (a
stale-default flow value never produces one), then every emission reconciles
against the seed INCLUDING the first, which is trusted only when a fresh
disk read taken at collect time agrees (the old blanket `drop(1)` discarded
a real toggle that landed in the startup gap — a StateFlow never re-emits
it — and stranded the hold until the next toggle). Deliberately a second
slot beside the streaming session's own wake lock — a session end releases
only its own hold, and the streaming lock is re-armed by the session's
30-second poll (`renewWakeLock`) before its 60-minute budget lapses,
independent of this hold.

### Automation Condition Policy
**`automation/schedule/AutomationConditionPolicy.kt`** — the pure verdict
over a snapshot: `isMet(condition, snapshot)` per condition (the catalog
schema resolved through the shared AutomationParamValues codec; unknown
condition id → false) and `conditionsMet(rule, snapshot)` (empty list →
true; `"any"` → at least one; else every one). Both rule families gate on
it — the schedule policy's `dueRules`/`eventDue` call it before any action
runs, so conditions are always evaluated against a FRESH snapshot at the
moment of firing, never a cached one.

### Automation Schedule Policy
**`automation/schedule/AutomationSchedulePolicy.kt`** — the pure decision
half of the engine, no Android imports (the scheduler owns the clock, the
journal, and the runtime seams; the snapshot arrives as a parameter). Its
verdicts: `occurrenceKey` = `epochDay * 1440 + minuteOfDay`, one monotonic
minute counter that makes "did today's occurrence already fire?" one
integer comparison instead of a date+time dance — each local day's minute
is its own occurrence, so midnight wrap is implicit; `dueRules` for time
rules (master toggle on, rule enabled, day mask covers the ISO day index
via Motion Arming Policy, occurrence key strictly newer than the journaled
one, occurrence time actually arrived, Automation Condition Policy met —
an unmet-condition rule stays DUE and unjournaled, firing on a later tick
the same day once conditions turn true; the rule's missed-run policy gates
both late boots and deferred fires the same way — SKIP fires only in the
scheduled minute itself, WINDOW only within its missedWindowMinutes, and
the default CATCH_UP keeps the per-action split: STATEFUL actions ride any
lateness while ONESHOT ones must be within the grace (inside a WINDOW the
rule window is the authority and every action rides), all measured in the
same wall-clock minute space as the keys because a real-epoch comparison
would smear the window by the zone offset); `eventDue` + `triggerMatches` for
event rules (the trigger matches the AutomationEvent — including the
battery-crossing verdict computed purely from
`BatteryLevelChanged(previous, current)` — the Automation Condition Policy
holds on the snapshot taken at event time, and the per-rule cooldown has
elapsed: the journal is consulted with the dual-value convention below, a
time-occurrence key never suppresses an event fire); `sanitizeRule`/
`sanitizeRules` (minute clamped, a zero day mask refused because the
enabled toggle already expresses disarm, name trimmed, unknown trigger
reset to `time_of_day`, trigger params sanitized against the trigger
catalog, conditions sanitized against the condition catalog and capped at
`AUTOMATION_MAX_CONDITIONS_PER_RULE`, `conditionMode` defaulted to "all",
`cooldownSeconds` clamped to 0..86400, `missedRunPolicy` folded to
"catch_up" unless it is exactly "skip" or "window" with
`missedWindowMinutes` clamped to 1..1440, unknown actions dropped, and the
caps applied to the *sanitized* list so a rule keeps its max executable
entries rather than losing valid ones to unknown noise ahead of them); and
`mergeCameraSettings` (one catalog action folded into CameraSettings: the
"0" auto sentinels fold to null, milliseconds convert to nanoseconds, and
an absent optional param leaves its field unchanged). A rule whose whole
action list is filtered away never fires partially and is not journaled,
so its one-shot can still catch up on a later tick within grace. Event
rules have no catch-up filter: an event is NOW, so all actions run —
including ONESHOT ones.

### EVENT_JOURNAL_THRESHOLD (journal dual-value convention)
**`AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD` (10⁹)** — one journal
file (`Map<String, Long>` per rule id), two value spaces: time fires store
occurrence keys (`epochDay * 1440 + minuteOfDay`, values under ~3×10⁷),
event fires store wall-clock epoch-ms timestamps (values ≥ ~10¹²). The
magnitude split is the discriminator, so the journal keeps its on-disk
shape and schema forever: `eventDue` reads only entries at-or-above the
threshold as cooldown timestamps (anything below is ancient history —
"long expired"), and `dueRules` reads only entries below it as occurrence
keys. The payoff: switching a rule's trigger kind between the two families
can never wedge it — an old time occurrence can't suppress an event fire
and an event timestamp can't block a scheduled minute.

### Automation Scheduler
**`automation/schedule/AutomationScheduler.kt`** — the shell around the
Automation Schedule Policy, owning the clock, the poll loop, the event
entry point, and the Fire Journal (the same pure-policy/thin-shell split
as StreamingManager's eco-idle monitor over Eco Idle Policy). It
re-evaluates every `StreamDefaults.AUTOMATION_POLL_SECONDS` (30 s) on its
own SupervisorJob scope for the process lifetime — started once from
MainApplication, never cancelled, exactly like Stream Watchdog — and
restarts the tick loop immediately whenever the persisted rules or the
master toggle change. The first evaluation is immediate: it IS the boot
catch-up (process start → DataStore rules survive → the first pass fires
everything the process missed), which is why no boot receiver is needed.
Live events arrive on `onEvent(event)` and evaluate `eventDue` against the
cached rules; both paths serialize through one evaluation Mutex so a time
tick and an event fire (or two events) can never interleave their
rule runs — actions from different rules execute in a defined order. The
AutomationSensorSnapshotProvider seam is a constructor parameter (tests
pin a fixed snapshot); the wall-clock snapshot is re-taken per evaluation
with runCatching→skip per rule so one failing provider read never kills
the pass. `app_started` fires exactly ONCE per process: a one-shot flag
inside the rules collector guards it, because any rules edit restarts the
inner `collectLatest` loop and would otherwise re-fire the trigger. Each
fired rule runs through the Automation Action Runner one at a time (one
failing rule never loses the others), and every attempt is journaled after
the run regardless of failure — a persistently failing action must not
tight-loop the poll cadence re-running it every tick. The local epochDay
derivation is DST-safe (zone + DST offsets, floorDiv).

### Automation Action Runner
**`automation/schedule/AutomationScheduler.kt`
(`fun interface AutomationActionRunner`)** — the scheduler's execution seam:
`run(fired)` hands one fired rule at a time from the evaluation loop to the
runtime, and the scheduler knows nothing about what the actions do. The
Automation Action Executor is the production implementation (by delegation,
so the receiver-facing methods stay its primary surface); tests substitute
a fake.

### Automation Fire Journal
**`automation/schedule/AutomationFireJournal.kt`** — the persisted half of
the scheduler's dedup: an atomic-write file in
`filesDir/automation/fire_journal.json` (the Stream State Journal shape)
recording, per rule id, the last thing that actually fired — the
occurrence key for time rules, the wall-clock epoch-ms timestamp for event
rules (the dual-value convention is EVENT_JOURNAL_THRESHOLD above; the
file shape stays `Map<String, Long>` either way). The rules themselves
live in the Settings Store and survive every process start, so without the
journal the boot-time first pass would replay every rule whose minute had
already passed (a reboot, a crash, or the user swiping the app away would
re-run the whole day) and event rules would re-fire on every restart
inside their cooldown. Keyed on the stable rule id, it covers rule edits
the same way — renaming or re-timing a rule never erases the fact that it
fired — which is what lets the scheduler need no boot receiver: the
catch-up IS the first poll evaluation, and the journal keeps it honest.
`load` is a deliberate per-tick disk read (the file is tiny — one small
long per rule id) and `record` merges over the freshly-read disk state
under a lock; a missing or malformed file means "nothing has fired", so a
corrupt journal costs at most one re-fire, never a lost one.

### Automation Event Wiring
**`automation/AutomationEventWiring.kt`** — the live-event half of the
engine's inputs, started from MainApplication next to the scheduler: five
collectors that translate the app's existing streams into
`AutomationEvent`s for `scheduler.onEvent`. Detection events ride the
Detection Event Store's flow, decoded by wire name into motion/sound/
tamper (unknown kinds dropped); power arrives from PowerManager as
charger connect/disconnect EDGES (seeded from the live state once at
start, so the current state never replays as a spurious edge) and
battery-percentage changes (the crossing verdict stays in the policy —
the wiring only forwards previous/current); streaming start/stop edges
and the viewer 0↔≥1 edge come from StreamingManager's flows, likewise
seed-suppressed. The wiring also owns the production
AutomationSensorSnapshotProvider (see Automation Sensor Snapshot), so
the engine's entire Android surface is this one file plus the scheduler
hook — everything else in `automation/schedule/` stays pure and
JVM-tested.

### Automation Action Executor
**`automation/AutomationActionExecutor.kt`** — the one execution seam for
automation commands, shared by two front-ends so a broadcast command and a
scheduled rule can never drift apart: the AutomationReceiver (the external
broadcast surface) and the Automation Scheduler (which implements the
Automation Action Runner by delegation). Every action id resolves through
the Automation Action Catalog and routes through the app's shared seams —
Stream Toggle (via `BootResumePolicy`, the QS-tile verdict), Recording
Controller, Photo Capture Manager, CameraService, SirenPlayer with the
app's one SirenAutoStop timer — never a second implementation. Camera-valued
actions (`camera_*`, `torch`) merge into the persisted CameraSettings via
`mergeCameraSettings` and the Settings Applier applies them in place with
no stream interruption; `camera_lens` switches on Main, clamped into the
live lens inventory (empty inventory and a missing index skip silently);
`detection` writes the motion/sound toggles to the Settings Store; unknown
ids skip silently as the last gate. Deliberately stateless and cheap to
construct — a front-end builds an instance per command against
MainApplication's lazy seam graph, so the composition root gains no new
member. The torch mode split is deliberate: see Torch (LIVE vs PERSISTED).

### Automation Rule Editor State
**`automation/RuleDraft.kt`, `automation/RuleDraftSaver.kt`,
`automation/RuleEditorState.kt`** — the automation rule editor's non-UI
half, extracted out of AutomationScreen so it is JVM-tested like the
Automation ViewModel's companion instead of stranded in composition. The
`RuleDraft` is the screen-local editing shape, a field-for-field mirror of
the Automation Rule (a third copy of the rule shape on purpose: the editor
mutates the draft freely and only the committed save projects to a rule,
which the sanitize pass then clamps); `new()` starts a fresh rule at 08:00
every day with the StreamDefaults cooldown/missed-window defaults (the web
editor's twin picks the same 08:00), `from`/`toRule` are the only
conversions. The Saver is the editor's rotation/process-death proof: the
draft serializes through the store's own Automation Rule Moshi codec —
same adapter, same persisted field names, so an in-flight snapshot written
by an older build still restores — and a corrupt snapshot degrades to
"editor closed", never a half-valid draft (`encodeRuleDraftJson` /
`decodeRuleDraftJson` are the testable pure halves; the Compose `Saver` is
the thin wrapper). `isAutoSentinelParam` decides whether a param row
renders the Auto toggle ("0" = the sentinel): the Automation Action
Catalog's `autoAtZero` flag is the sole authority — the screen's old
hardcoded fallback name-set is gone because the catalog fully covers it
(ISO and exposure time are exactly the two `autoAtZero` specs), unlike the
web client, which keeps a name-set fallback for its fetched-catalog
misses. `conditionWindowDaysMask` reads a time-window condition's
persisted day mask in the Motion Arming Policy bit convention, folding an
absent or unparseable value to the every-day default the catalog
documents. The cooldown and missed-window preset chips and the cooldown
display ceiling (the persisted bound allows a day; the slider stops at an
hour, a wider persisted value renders pinned at the ceiling) live here as
named constants instead of inline literals — the web client pins the same
preset lists, and each list's default rides the StreamDefaults constant
rather than a second literal. `moved` is the one spec-list reorder step
the editor's ↑/↓ buttons use.

### Torch Strength Policy
**`camera/model/TorchStrengthPolicy.kt`** — the pure decisions for the API
33+ per-level torch: `CameraManager.turnOnTorchWithStrengthLevel` plus the
`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` characteristic (both Android 13 — not
CameraX's API-35-only `setTorchStrengthLevel` wrapper, which rides the
different `CaptureRequest.FLASH_STRENGTH_LEVEL` key; sourcing in
`docs/research/torch-strength.md`) let a multi-level flash
run below full power, where `CameraControl.enableTorch` is binary.
`effectiveLevel` is the no-op ladder — every rung returns null, "don't call
the strength API; plain enableTorch already gives the user everything the
device can do": SDK < 33, no requested strength (the persisted default),
an unknown device max level, or a single-level flash (max ≤ 1); otherwise
the request clamped into 1..deviceMax. The device's real max always wins at
apply time — the catalog/persistence bound 1..8 (`MAX_REQUESTED_LEVEL`) is
what the settings layer may store, not an apply-time ceiling.
CameraService queries the bound camera's max level into a
`torchStrengthMaxLevel` StateFlow, drives the strength only while the torch
should be ON (the strength API switches the torch on as a side effect when
it was off), and re-asserts it on the same `applyCameraControls` seam that
keeps the binary torch alive across rebinds; `CameraSettings.torchStrength`
(null = device default) carries the persisted request, and the automation
editor caps its strength slider at the live device verdict.

### Torch (LIVE vs PERSISTED)
**`automation/AutomationActionExecutor.kt` (`setTorchLive` vs the
scheduler's camera-settings path)** — two torch modes on purpose. The
receiver's SET_TORCH is LIVE deterrence: drive
`CameraService.setTorchEnabled` on Main, persist nothing — the persisted
setting may disagree with reality, which is exactly why a toggle computes
its target from the live `isTorchOn()` read, never the setting. A scheduler
`torch` action is PERSISTED: it converges
`CameraSettings.torchEnabled`/`torchStrength` through the Settings Store so
the Settings Applier applies it and a device restart or a settings
re-apply restores what the rule asked for. Detection deterrence stays LIVE
like the receiver; neither mode touches the other's semantics, and manual
LED control is unchanged.

### Notification Permission Policy
**`camera/model/NotificationPermissionPolicy.kt`** — the pure POST_NOTIFICATIONS
ask-once decision behind the camera screen's first-launch permission pass
(detection alerts need the permission on API 33+). The verdict was stranded
in composition behind a process-global `var` guard in CameraScreen; it now
lives here with the once-flag in CameraViewModel's state (surviving
recompositions and rotation with the screen's ViewModel), mirroring the
audio path's `MicAccess.shouldAutoRequest`. The pinned ladder: the budget is
spent on the FIRST fresh composition even when no dialog can show (below API
33 or already granted), the dialog launches only when `shouldAsk(granted,
askedBefore, apiLevel)` holds, the launcher result is ignored (a denial buys
silence — a later revocation in system settings never re-prompts within the
flag's lifetime), and a spent budget is never re-marked.

### Connect Info Policy
**`camera/model/ConnectInfoPolicy.kt`** — the pure derivations the connect
sheet's composition used to inline: `currentPort(httpUrl)` (the port the
sheet's USB `adb forward` instruction quotes) and `isSecureHttp(httpUrl)`
(the case-sensitive `https://` gate for showing the TLS fingerprint). This
is a truth seam, not idealized URL parsing: the port slice is text after the
FIRST colon up to the FIRST slash, `toIntOrNull`-parsed (signs survive, no
range check) with `StreamDefaults.WEB_PORT` as the fallthrough — which means
every schemed URL (`scheme://host:port/path`, the only shape the Streaming
Manager builds, IPv4 or bracketed IPv6) resolves to the DEFAULT port, since
the slice starts at `//` and ends at its own leading slash. A custom server
port is quoted only from a scheme-less `host:port(/path)` string. The TLS
fingerprint itself flows through CameraViewModel (`tlsCertificateFingerprint`
— read once per sheet open); the screen no longer reaches around the
ViewModel into the app-scoped manager.

### Gallery Session
**`gallery/GallerySession.kt`** — the app-scoped owner of the gallery's
soft-delete staging and warm decryption: the DeleteStagingPolicy instance,
the pending `stagedIds` flow, the undo, the one commit poller, and the
`decryptedPhotos`/`encryptedVideoIds` warm-decryption maps — the state the
gallery and viewer routes used to keep in two separate
NavBackStackEntry-scoped GalleryViewModels, where a stage done in the
viewer stayed invisible to the grid until commit and the sniff/decrypt pass
ran once per gallery route. One session shared by both routes' ViewModels
is the single staging truth; the ViewModels delegate to it and keep only
their own per-route UI state (selection, filter, search).
Commit-on-exit contract: leaving the GALLERY AREA is not an undo — the
gallery ViewModel's `onCleared` calls `flush`, committing whatever is still
staged immediately — while leaving the VIEWER deliberately does not flush,
so its staged ids stay pending with a live undo affordance in the grid. The
expiry poller (a 250 ms tick, one at a time, restarted per stage) is the
safety net for every staged id either way, and the pending set is
republished only after the store delete lands, so a committed item stays
hidden until its media is really gone. Threading: the mutators run on the
UI thread (via the ViewModels) while the poller and warm loop run on the app
scope — every policy access is serialized under one lock, replacing the old
Main-thread confinement.

### Session Demand
**`camera/model/SessionDemand.kt`** — one immutable record of what the camera
session is being asked to do: `previewRequested`, `exclusiveSessionRefCount`,
`keepAliveRefCount`, `isActivityForeground`, `currentPreviewView`. Before it
the five facts lived in five loose CameraService fields written one
statement at a time — a multi-fact writer (startPreview, stopPreview,
release) could be observed half-applied by the auto-recovery thread's
cross-thread arbiter consults. Every writer goes through the service's
`updateDemand` seam on Main, swapping the whole record copy-on-write;
`demandState()` maps a snapshot onto the arbiter's `CameraDemandState`. The
five named demand states are overlaps of the facts, not an enum — Unbound,
Preview-bound foreground, Preview-bound surface-detached, Headless
keep-alive, Exclusive-held — and a session sits in several at once (a
recording is Exclusive-held AND Headless keep-alive AND backgrounded).
Ownership is strict: RecordingService alone writes
`exclusiveSessionRefCount`; StreamingSession, PhotoCaptureManager, and
RecordingService share `keepAliveRefCount`; MainActivity's lifecycle
observer writes `isActivityForeground` (default `true` before the first
lifecycle event); the UI writes `previewRequested` and
`currentPreviewView` together. The preserved, documented decision:
`CameraService.release()` does NOT reset `keepAliveRefCount` — the stale
keep-alive after release is today's behavior on purpose.

### Selected Lens
**`camera/model/SelectedLens.kt`** — the one selected-lens fact: the
`CameraSelector` the next bind uses, whether it faces front, and its index
into the published lens inventory, published as one immutable value. The
three must move together because they describe one lens from three angles —
the former three-store version (a plain selector var plus two flows) could
half-update: the enumeration-failure fallback reset only the index, leaving
the selector and the facing stale. CameraService is the only writer (lens
selection, the switch fallback, both enumeration outcomes — each a single
atomic update); the lens row, the Web API's lens status, and the facing
mirror read the published flow. `isFrontCamera` and `selectedLensIndex`
are derived projections off the one fact — they can never disagree with
the selector.

### Torch State
**`camera/model/TorchState.kt`** — the LIVE torch fact as a sealed machine:
`Off` (also the state while no camera is bound — the live fact dies with
the camera session), `On(null)` lit at the device default brightness, and
`On(level)` lit at a strength the service actually drove through the API
33+ ladder. Distinct from the persisted
`CameraSettings.torchEnabled`/`torchStrength` pair, which is INTENT: remote
writers (the Web API's torch toggle, the deterrence coordinator) flip the
live torch through `CameraService.setTorchEnabled` without ever persisting,
so a settings read must not be mistaken for the torch's state.
CameraService is the only writer, fed from the bound camera's cameraInfo
torchState observation plus the strength it last drove; the pure
`TorchState.from` mapping keeps the machine JVM-testable, and
`isTorchOn()` derives from the same flow. The automation side's
LIVE/PERSISTED split (see Torch (LIVE vs PERSISTED)) rides this line.

### Rule Vocabulary
**`automation/schedule/AutomationRuleEnums.kt`** — the typed forms of the
rule graph's stringly fields: `AutomationTriggerKind` (one constant per
Automation Trigger Catalog id; unknown wire ids fold to `TIME_OF_DAY`),
`ConditionMode` (`ALL`/`ANY` — anything not exactly `"any"` folds to `ALL`,
the stricter semantics), and `MissedRunPolicy` (`CATCH_UP`/`SKIP`/`WINDOW`;
unknown folds to `CATCH_UP`, the historical behavior). Each enum carries
its exact canonical wire id (`id`), a tolerant parse, and a `WIRE_ADAPTER`
that writes `enum.id` — the same string the String fields held, so the
persisted-rule JSON and the web API stay byte-compatible across the
stringly→typed conversion. `AutomationRuleJson` (same file) is the one
Moshi instance for the rule graph: AppJson plus the three wire adapters;
adapters built from plain `AppJson.moshi` would fall through to Moshi's
name-based enum encoding and emit `"TIME_OF_DAY"` — a wire break — so no
rule-graph surface (the store codec, the draft saver, the
`/api/automation` handler) may serialize through the bare instance. Name
note: the former `AutomationRuleJson` DTO clones are gone (see Automation
Rule); the object is the Moshi instance, not a data clone. RuleDraft keeps
wire strings and converts at the boundary; the catalogs stay the
parameter-schema authority — the enums are the decode/compare form.

## State Machines

Every state machine below has a named owner type — the type that alone
writes it; pure verdicts over raw facts live in `*Policy`/`*State` files
with JVM tests. Flows publish single truths — mirrors are derived, never a
second hand-written copy — and wire/persistence formats are byte-compatible
contracts.

### Camera

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `CameraState` | `CameraViewModel._cameraState` — sticky overlay over `CameraService`'s flow | `camera/model/CameraState.kt` | Idle / Initializing / RequestPermission / Ready / Error | sticky filter drops an incoming Idle (see Deliberately implicit) |
| `StreamStatus` | `CameraViewModel._streamStatus`, built by the Status Snapshot Builder | `camera/model/CameraState.kt` | 7 booleans (isActive, isWebActive, isServerRunning, isAudioActive, isRtspActive, isWebEnabled, isRtspEnabled) + urls, clientCount | the boolean cluster as one typed snapshot; folded by the dashboard verdicts |
| `ServerStatusTier` / `StreamShutterContainer` / `WifiBannerVariant` | `CameraDashboardPolicy` verdicts | `camera/model/CameraDashboardPolicy.kt` | LIVE/READY/OFFLINE; RECORDING/ENABLED/DISABLED; STREAM_ACTIVE/STREAM_IDLE | pure folds over the StreamStatus cluster; the screen maps tiers onto colors and strings |
| `RecordButtonTier` | `CameraDashboardPolicy.recordButtonTier(RecordingState)` | `camera/model/CameraDashboardPolicy.kt` | IDLE / SCHEDULED / RECORDING | visuals only — the tap verdict stays `RecordingToggle.decide`'s |
| `CountdownState` | the CameraViewModel countdown job (only writer) | `camera/model/CountdownState.kt` | Idle / Counting(secondsRemaining) / Firing | self-timer; the initial Counting is written synchronously before the job launches (immediate-cancel race fixed); the settings mode flipping OFF cancels |
| `SelectedLens` | `CameraService` (only writer) | `camera/model/SelectedLens.kt` | selector / isFront / index as one fact | the enumerate-failure fallback resets all three; `isFrontCamera`/`selectedLensIndex` are derived projections |
| `TorchState` | `CameraService` (only writer) | `camera/model/TorchState.kt` | Off / On(strengthLevel?) | LIVE truth — persisted settings are intent, remote writers drive live only; unbound ⇒ Off |
| `QuickSettingsUiState` | CameraScreen (`remember`, the three transitions) | `camera/model/QuickSettingsUiState.kt` | Collapsed / Expanded / Sheet(setting) | sheet-open ⇒ bar-expanded structurally; intentionally does not survive rotation |
| `ZoomIndicatorState` | CameraScreen `zoomIndicator` (the gesture writes and the retire timer both land here) | `camera/model/ZoomIndicatorState.kt` | Hidden / Visible(ratio) | the whole state is the retire effect's key, so every pinch write re-arms the hide deadline — the retired flag+float pair's exit transition was dead and the pill never left the viewfinder |
| `SessionDemand` | `CameraService` via `updateDemand` (Main only) | `camera/model/SessionDemand.kt` | previewRequested / exclusiveSessionRefCount / keepAliveRefCount / isActivityForeground / currentPreviewView | ONE immutable record swapped copy-on-write; `demandState()` maps it to the arbiter's `CameraDemandState`; `release()` intentionally does NOT reset keepAliveRefCount (documented decision) |
| `SpiritLevelMonitor.Registration` | `SpiritLevelMonitor` (private) | `camera/SpiritLevelMonitor.kt` | Attached(sensor) / NoSensor / Detached | "registered" is derived from the two gates, not stored; the no-accelerometer verdict is memoized |
| `StreamStartOutcome` | `StreamToggle` | `camera/model/StreamToggle.kt` | Started / Stopped / Disabled / StartFailed / BeginFailedRolledBack | the gate→start→begin→rollback ladder, exactly once; a failed session begin rolls back the just-started output |

### Capture

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `RecordingState` | `RecordingController.state`; RecordingService reports via `onService*` | `capture/RecordingController.kt` | Idle / Scheduled(startAtMs, config) / Recording(startedAtMs, config, finalizing) | the only public truth about recording (see Recording Controller); no optimistic copies anywhere |
| `TimelapseState` | `CaptureViewModel` (only writer) | `capture/model/TimelapseState.kt` | Idle / Running / Done(TimelapseComposer.Result) | Running is re-asserted per run, so a previous run's Done never leaks |
| `IntervalCaptureState` | `CaptureViewModel` (only writer) | `capture/model/IntervalCaptureState.kt` | Unknown / Running(snapshot) / Stopped | Unknown = the WorkManager snapshot not yet observed; the Start button renders disabled-with-progress until observed |
| `DetectionModelStore.State` | `DetectionModelStore` (`requestDownload`, idempotent) | `capture/ml/DetectionModelStore.kt` | NotDownloaded / Downloading(progress) / Ready(file) / Failed(reason) | a Ready that fails its size-then-digest re-check is demoted, never wedged |
| `AudioModelStore.State` | `AudioModelStore` (`requestDownload`, idempotent) | `capture/ml/AudioModelStore.kt` | NotDownloaded / Downloading(progress) / Ready(file) / Failed(reason) | same shape as `DetectionModelStore.State` |
| Continuous-loop rearm discipline | `ContinuousRecordingController.decideIdle`/`tryArm` | `capture/ContinuousRecordingController.kt` | a live suppression always keeps exactly one wake-up scheduled | a second chain break inside a suppression window retargets the pending job (`scheduleRearm` cancels+replaces) — the old `rearmJob == null ? ScheduleRearm : Noop` left the stale job aimed at the old deadline; its fire failed `canArm` and the loop sat Idle with no flow emission ever coming, silently dead |
| Photo capture completion invariant | `PhotoCaptureManager.takePictureCallback` wrappers | `capture/PhotoCaptureManager.kt` | — | history is best-effort; the use-case release and the caller's completion signal are unconditional — a history throw used to leak the photo use-case slot and hold AutomationReceiver's goAsync window until the OS timeout |
| RecordingService destroy rescue | `onDestroy` | `capture/RecordingService.kt` | — | a destroy during Recording runs the same rescue as the Finalizing path (session ladder + `onServiceStopped` + temp delete) — the controller and the camera session cannot outlive the service |
| `LoopState` | `ContinuousRecordingController` (lock-confined, the only writer) | `capture/ContinuousRecordingController.kt` | Dormant / Maintaining(ours, graceIdleSinceMs) / Suppressing(untilMs) | the armed/armedByUs/suppressedUntilMs/idleSinceMs cluster as one machine; the `ours` update formula keeps its deliberate `\|\|` (a foreign takeover over an ours-chain still reads ours — the disable-time teardown then stops the foreign recording — and a config-equal recording adopts the loop's own late repeat re-start); entering Maintaining drops a suppression stamp, provably decision-identical (post-wave-6 a pending rearm always targets the current stamp, so the only armed-side stamp read — tryArm's rider — sees ≤0 remaining); `rearmJob`/`graceJob` stay beside it (`rearmJob` deliberately outlives Suppressing: its fire over a live user recording is the quiet no-op that keeps exactly one wake-up) |
| RecordingService `Phase` | `RecordingService` (private, main-thread-written) | `capture/RecordingService.kt` | Idle / Recording / Finalizing | the boolean cluster's shadow of `RecordingController.state` (the service never holds Scheduled); (Recording ∧ Finalizing) is unreachable by construction, so the onDestroy rescue and the stopRecording guard are exhaustive `when`s with exactly the old predicates — stop during Finalizing still re-reports `onServiceStopped` (the idempotent no-op that keeps a stale stop from wedging the controller); `pendingTempFile` stays a plain field (its IO-coroutine writes are deliberate, not machine state) |

### Gallery

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `GallerySession` | app-scoped `GallerySession` | `gallery/GallerySession.kt` | DeleteStagingPolicy + stagedIds + commit poller; decryptedPhotos / encryptedVideoIds warm maps | one truth shared by the gallery and viewer routes; commit-on-exit lives in the gallery VM's onCleared, the viewer never flushes |
| `SelectionState` | `GalleryViewModel.selection` (private) | `gallery/GalleryViewModel.kt` | active / ids | `selectMode`/`selectedIds` are derived maps; zero-selected-with-mode-on is kept behavior |
| `UndoSnackbarState` | `GalleryViewModel.undoSnackbar` (derived from stagedIds) | `gallery/GalleryViewModel.kt` | stagedIds snapshot at snackbar show | Undo unstages exactly the snapshot, never the live set |
| `ResolvedMedia` | `resolveViewerMedia(filePath, encryptedAtRest)` | `gallery/GalleryMedia.kt` | PlainFile / SchemeUri / EncryptedVideo / Missing | encrypted wins by TYPE — ciphertext never surfaces as a plain model |
| `ViewerSyncEffect` | `ViewerSync.reduce` / `onDelete` | `gallery/ViewerSync.kt` | JumpTo(index) / AnimateTo(index) / Pop | the first resync of a fresh viewer jumps, later ones animate; NavigationGraph executes it |

### Streaming

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `RunState` | `StreamingServer` (private) | `streaming/StreamingServer.kt` | NeverTried / FailedStart / Running | dirty-stopped retryability: a throwing stop leaves FailedStart so the next stop runs and the release is retried |
| `MediaCodecEncoderHarness.Phase` | the harness (behind `AacEncoder`/`H264Encoder`) | `streaming/rtsp/MediaCodecEncoderHarness.kt` | Idle / Running / Draining | Draining = the AAC EOF path's stop: loops exited, codec still live until the next stop/start releases it — the phase the old boolean could not represent, which leaked one codec per mic restart |
| `WsMediaRoutes.CachedVideoConfig` | `feedVideo` (encode thread) swaps one volatile record | `streaming/ws/WsMediaRoutes.kt` | no-config / AvcConfig / HevcConfig as one immutable record | a WS handshake reads either the whole AVC or the whole HEVC config — the retired four-field cluster could be read torn mid codec flip and hand the client a config its decoder rejects |
| `ForegroundMode` | `StreamingService.foregroundMode` (@Volatile) | `streaming/StreamingService.kt` | None / ServerOnly / Streaming | bookkeeping of the last successful typed foreground start; the failed-reassert stopSelf sharp edge is documented and unchanged |
| `Credentials` | `WebAuthGate` (private) | `streaming/WebAuthGate.kt` | nullable admin / viewer pairs | a blank half clears the whole pair (the blanking convention, now structural) |
| `SessionPhase` | `WhepSessionRegistry` (per entry) | `streaming/whep/WhepSessionRegistry.kt` | Awaiting / Connected / Disconnected | the per-phase reap deadlines are byte-identical to the old thresholds |
| `PushPhase` | `PushOutput.phase()` (derived) | `streaming/PushOutput.kt` | Idle / Connecting / Reconnecting / Connected / Refused | derived from wireName × active — distinguishes refused vs reconnecting; the /api/status wire strings are unchanged (OpenApiSpec-pinned); the lifecycle transitions serialize on one `transitionLock` (concurrent starts used to leak a live publisher nobody could stop; a stop between a restart's legs resurrected the output) and a superseded handle's statuses are dropped at the sink |
| `TalkbackPhase` | `AudioStreamingManager.talkbackPhase` (one @Volatile write per entry point) | `streaming/TalkbackPolicy.kt` | Idle / Clip(untilMs) / Held | the half-duplex mic-drop verdict, read once per chunk — the retired isTalking/talkUntilMs/uplinkStreaming trio raced both ways: one chunk leaked past a PTT open, and a lost update un-muted the mic for a whole clip and echoed the talker's voice to every audio subscriber |
| `DiscoveryRegistrationPolicy` verdicts | `ServiceDiscoveryManager` (@Volatile listeners, `rtspAdvert` change detection) | `streaming/DiscoveryRegistrationPolicy.kt` | no-advert / advert(port, path, auth) | unregister keys on the listener, not a flag — a stop landing before the async registration confirmed used to ghost the advert until process death and mint a duplicate on the next start; a re-invocation with changed params re-registers, so the TXT auth attribute follows the truth |
| WhepServer reap loop | `reapJob: Job?` (start/stop own it) | `streaming/whep/WhepServer.kt` | armed / cancelled | a start after a stop relaunches — the old once-only flag over an immortal loop was correct only by accident |
| `EncodedStreamPolicy.needsEncoderBuild` | `EncodedStreamHub.startLocked` guard | `streaming/EncodedStreamPolicy.kt` | Stopped / Running / Running·encoderless (retryable) | a permanent reconfigure failure nulls `activeEncoder` (and so does `stopLocked`), so the ≤500ms frame-path refresh rebuilds — the old wedge left a black pipeline that `isRunning()` reported live, unrecoverable until every sink deactivated |
| `LatestFrame` | `MjpegStreamPump` (private) | `streaming/MjpegStreamPump.kt` | (jpeg, version) | swapped atomically on every `updateFrame`; readers compare versions |
| `isStreaming` | `StreamingManager` — derived combine of five active mirrors | `streaming/StreamingManager.kt` | Boolean | mirrors written by their owners at the transition itself (`onActiveChanged` seams); `updateStreamingState` deleted; `isLiveStreaming()` is the same predicate |
| `applyEcoIdleState` | `StreamingManager` (single writer) | `streaming/StreamingManager.kt` | `EcoIdlePolicy.State` + lock-free `ecoIdleActive` volatile | the only writer of the pairing; the volatile write is the publish step (see Eco Idle Policy) |
| ClientSession `isPlaying` | `RtspServer.ClientSession` (private `SessionState`) | `streaming/rtsp/RtspServer.kt` | INIT / READY / PLAYING | `isPlaying` is derived (`state == PLAYING`); teardown and write failure drop to INIT so the send gates stop; the sub SETUP write is demote-safe (INIT→READY only — a SETUP during PLAYING used to demote it and freeze the main stream until a re-PLAY) and TEARDOWN clears the sub SETUP gate, so describe() stops advertising a torn-down /sub |
| `RtspSendPolicy.OfferVerdict` | `RtspServer.ClientSession.enqueueAu` (drain thread, per offer) | `streaming/rtsp/RtspSendPolicy.kt` | (dropCount, rearmKeyframe, requestKeyframe) | drop-oldest whole AUs at the 8-AU bound, never the incoming AU; re-arm on drop except when the incoming AU is the keyframe itself; keyframe requests under the 1000 ms cooldown (sustained congestion must not drive the encoder all-intra) |
| ClientSession writer lifecycle | `RtspServer.ClientSession.writerThread` (@Volatile `Thread?`) | `streaming/rtsp/RtspServer.kt` | absent / alive / dead-until-re-PLAY | `ensureWriter` (both PLAY paths) replaces a dead writer via `isAlive`; `stopWriter` (TEARDOWN/close) nulls + clears + interrupts; a write failure drops the session to INIT and exits the loop — the socket stays for the read loop's timeout/EOF to close (see Deliberately implicit) |
| `BootResumePolicy.Verdict` | `BootResumePolicy.decide` | `streaming/StreamStateJournal.kt` | NONE / WEB / RTSP / BOTH | boot resume over the persisted journal's web/rtsp flags |
| `PublisherSession<S>` | `RtmpPublisher`/`SrtPublisher`/`WhipPublisher` delegate start/stop/status to one instance | `streaming/PublisherSession.kt` | started/wanted CAS pair + one supervised reconnect loop + 1 s→30 s capped backoff (reset to 1 s on a clean attempt end) | the skeleton was character-identical in all three publishers (only the status type, the null-message fallback, the log line, and RTMP's compute/increment/delay textual order differed — canonicalized on delay-then-increment, documented identical); the three stop choreographies are opaque per-transport `onStop` hooks (RTMP channel-close-drives-clean-close + 2 s force-close; SRT shutdown-packet + 2 s grace; WHIP `videoSource = null` watch-beat self-teardown) and must not be decomposed further; the scope stays publisher-owned (the hooks launch cleanup on it) and a loop's completion cancels it — a stopped instance stays inert, which is why `PushOutput.startQuietly`'s fresh-publisher-every-start invariant stays unenforced by the type |
| `RtspSubStreamState` | `RtspServer.ClientSession.sub` (one per connection) | `streaming/rtsp/RtspSubStreamState.kt` | SETUP gate (isSetup) / PLAY gate (isWanted) / keyframe wait / negotiated channel pair | the /sub twin of the aggregate SessionState machine, named and JVM-tested beside `RtspSessionProtocol` — the first JVM pin on the sub session decisions; plain mutable fields on purpose (the session thread and the encoder drain thread race them benignly, exactly as the fields this replaced — volatile/immutable would change which access unit is first delivered after a mid-GOP PLAY); `onPlayArmed` stays adjacent to the caller's `requestSubKeyFrame`, `onDropRearm` re-arms the wait when the send queue drops queued AUs, and the demote-safe INIT→READY write stays server-side (the holder has no aggregate knowledge) |

### Automation

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `JournalEntry` | `AutomationFireJournal` | `automation/schedule/AutomationFireJournal.kt` | Occurrence(epochDay, minute) / EventFired(atMs) | total decode split by EVENT_JOURNAL_THRESHOLD; refutable garbage is sanitized on load |
| Rule vocabulary | `AutomationTriggerKind` / `ConditionMode` / `MissedRunPolicy` | `automation/schedule/AutomationRuleEnums.kt` | TIME_OF_DAY + 8 triggers; ALL/ANY; CATCH_UP/SKIP/WINDOW | exact wire ids; `AutomationRuleJson` = AppJson + the three wire adapters is the one Moshi for the rule graph (plain AppJson.moshi would emit enum names); RuleDraft keeps wire strings, converts at the boundary |
| `Picker` | AutomationScreen (private, `activePicker`) | `automation/AutomationScreen.kt` | Action / Trigger / Condition | editor pickers mutually exclusive structurally |
| Rule-editor dirty pair | AutomationScreen `modified`/`showDiscardConfirm` (rememberSaveable — the draft's shadow) | `automation/AutomationScreen.kt` | Closed / Open·clean / Open·dirty / ConfirmingDiscard | saveable together with the saveable draft, so a configuration change can never split truth from dirtiness (the old plain-remember dirty flag let rotation dismiss edits silently) |
| Wiring start guard | `AutomationEventWiring` (`@Volatile started`) | `automation/AutomationEventWiring.kt` | set-once | matches `AutomationScheduler.start`'s idempotence contract — a second start would double-launch the collectors and re-run every event trigger |

### Core

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `UpdateState` | `UpdateViewModel` | `update/model/UpdateInfo.kt` | Idle / Checking / UpdateAvailable / UpToDate / Downloading / ReadyToInstall / Error | the update screen's full lifecycle as one explicit machine |
| `MicStartDecision` | `MicAccess.startDecision` (consulted through MicGate) | `core/MicAccess.kt` | Proceed / Degrade(warning) | warn-and-degrade: the caller starts either way (see Mic Access) |
| Keep-awake startup reconcile | `KeepAwakeController` collector (single writer) | `core/KeepAwakeController.kt` | DiskSeeded → FlowFollowing | the first emission is disk-confirmed at collect time — a stale default never holds and a gap write is never lost (see Keep Awake) |
| Wake-slot renewal gate | `PowerManager` (`wakeLockLock` monitor + `wakeRenewalArmed`) | `core/PowerManager.kt` | armed (session live) / disarmed (released) | the renew ladder can never outlive the session that armed it — a post-release tick is a no-op, not a resurrection (`resolve(!held)` reads a released slot as "lost lock, re-acquire"); the streaming and keep-awake slots synchronize on independent monitors |
| `TrackingSnapshot` | `StreamWatchdog` (swapped via copy) | `core/WatchdogPolicy.kt` | five last* tracking facts (frame baselines, last check, last recovery, last failure reason) | one immutable snapshot per update; the runtime's Atomic counters stay separate on purpose |
| `isQuitting` latch | `MainApplication` (@Volatile flag) | `MainApplication.kt` | set-once flag | guards the quitApplication teardown ladder — the first invocation runs it, later ones no-op |
| GeoTag ask-once + degraded caption | SettingsScreen + its permission launcher | `settings/SettingsScreen.kt` | off / on-with-permission / on-but-denied (degraded) | persist-on-grant — a denial leaves the setting off; enabled-but-denied shows the credit-only EXIF caption |
| Nav Routes | `Routes` (object) | `navigation/Routes.kt` | the one route vocabulary | MainActivity navigation events use extraBufferCapacity=8 + DROP_OLDEST (freshest intent wins) |
| MQTT announce `Connection` | `MqttAlertPublisher` (executor-confined; one immutable record swapped whole) | `core/mqtt/MqttAlertPublisher.kt` | (config, announced, announcedTelemetry, publishedStreamStates) | the lastConfig + three-flag cluster as one record; five swap points at the exact legacy write sites keep the load-bearing asymmetries byte-identical — `announced` flips only AFTER a full announce (a mid-announce throw retries the whole announce), the optional-entity flags flip BEFORE their publishes (set-before-publish: a throw records "these went out", so close/cleanup stays conservative and clears exactly what went out); `clearDiscoveryConfigs` takes the flags explicitly so the old-truth read provably precedes the changed-config swap |

### Wear

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| `RequestState<T>` | the wear module's async surfaces | `wear/WearModels.kt` | Idle / Loading / Success / Error | the wear module's request lifecycle; Loading replaces content only on user-initiated actions |
| `WearTileModels.StateLine` | `WearTileModels.stateLine(status)` | `wear/WearTileModels.kt` | UNKNOWN / IDLE / WEB / RTSP / WEB_AND_RTSP | derived from the cached status's live flags |
| `PermissionState` | `SettingsActivity` (activity-local) | `wear/SettingsActivity.kt` | Unknown / Granted / Denied | replaces the nullable `notificationGranted` tri-state; pre-33 devices write Granted unconditionally |
| `WearTileStatusCache` holder | `WearTileStatusCache` (@Volatile holder swap) | `wear/WearTileService.kt` | (latest, publishedLine) in one immutable holder | the publisher skips a re-render when the line did not change; process death → "Phone status unknown" |

### Web Client

| Machine | Owner (type) | File | States | Invariant / notes |
| --- | --- | --- | --- | --- |
| AuthCard config mirror | `AuthCard` (`adminDirty`/`viewerDirty`) | `web/src/components/AuthCard.tsx` | Synced-with-server / Locally-dirty, per group | the 10 s poll overwrites a group only while synced — Save clears the bit and re-bases from server truth; the password fields are write-only drafts the poll never touches |
| Gallery fetch generation | `Gallery.tsx` `fetchGallery` (generation counter) | `web/src/Gallery.tsx` | one fetch owner at a time | a superseded fetch drops its result — the filter/query/page trigger effects cannot race the grid into showing the abandoned request's items (RecordingTimeline's day-guard, generalized) |
| Rule-editor dirty | `AutomationRuleEditor` derived memo | `web/src/components/AutomationRuleEditor.tsx` | clean / dirty = structural compare against the rule the editor opened with | dirty is derived, never tracked — a new mutator needs no bookkeeping; the memo reads `specsVersion` because the param records mutate in place |
| Recording/interval status generations | `useAppState` per-lane counters (the Gallery fetch-generation shape) | `web/src/hooks/useAppState.ts` | one poll owner at a time, per lane | the four optimistic action handlers invalidate in-flight polls — a stale response used to resurrect a stopped recording's REC overlay (or kill a just-started one) for up to one poll interval |
| `sseLaneHealthy` | `web/src/hooks/sseHealth.ts` (pure); `sseHealthChecker` publishes it | `web/src/hooks/sseHealth.ts` | Fresh / alive-grace / Dead | open AND alive < 5 s AND events < SSE_STALE_MS — a closed socket is dead immediately (the old inline checker could overwrite onerror's false with its grace-window true for up to 5 s); its real cadence is two-sided (a closed poll-ladder gate leaves the counter saturated): ~1 s while healthy (consulted every tick), ~3 s while dead (the lane fires and resets) — the wave-6 note claimed ~3 s flat; the onopen/onerror writes make visible transitions instant and the checker is the half-open-socket backstop, so it was deliberately NOT made the only `sseConnected` writer (see Deliberately implicit) |
| MJPEG retry ladder | `StreamPreview.previewRetryTimer` + `previewErrorCount` | `web/src/components/StreamPreview.tsx` | at most one pending retry timer; error budget resets on load | unmount cancels the timer and a successful load clears the budget — five lifetime transient errors used to permanently demote MJPEG to the ~10 s-latency HLS rung (or pin the 8 s backoff forever without HLS) |
| PTT unmount teardown | `StreamPreview` onCleanup (hold generation + `stopPttCleanup`) | `web/src/components/StreamPreview.tsx` | — | an unmount mid-hold (session demoted or logged out) tears the mic/context/WS down — the hold used to leave a hot mic and an open socket behind the login screen |
| Multicam tile fetch generation | `CameraTile` `requestTick` guard + `captureResetTimer` | `web/src/components/MultiCamCard.tsx` | one fetch owner per tick; one pending volley reset | a superseded or disposed response revokes its own blob URL and walks away (it used to revoke the newer tick's URL, or store one nothing would ever revoke); a newer volley cancels the previous one's pending badge wipe |
| `PttPhase` | `StreamPreview` (`pttPhase` signal; `talking` is the derived `pttBusy` memo — every reader site untouched) | `web/src/lib/pttPhase.ts` | idle / arming / holding / draining (+ `teardown` → idle from anywhere) | the hold lifecycle as a closed transition table; Ws/Fallback were refuted as phases — `sendPcm` re-decides the transport per chunk from `readyState` and `onclose` flips it mid-hold, so transport stays the per-chunk verdict it always was; busy = {arming, holding, draining} is exactly the old boolean latch's true-set including the drain (the idle write stays after `await stopPttCleanup`); `pttHold` stays beside the phase as the async-liveness guard; the audioUp write is hold-guarded so a stale continuation cannot stomp a newer hold |
| `SessionPhase` | `useAppState` (derived memo over the untouched auth signals) | `web/src/lib/sessionPhase.ts` | booting / open / locked / signedIn | Demoted was refuted as a resting variant — its resting field state is field-identical to an auth-on-no-session load and no consumer distinguishes them; demotion is the transition (`signOut({ demoted: true })`); one `signOut` reset merges the two hand-maintained ladders (both were six resets; the flagged list's "handleLogout four" was stale) with `demoted` additionally arming authRequired; `checkAuth`'s fail-open catch is documented, not changed; App's render gates still read the raw signals |
| `RecordingPhaseKind` | `useAppState` (derived memo over `isRecording`/`isScheduled`) | `web/src/lib/recordingPhase.ts` | idle / scheduled / recording | recording wins (the same priority the stop-button label encodes); payloads stay in their own signals (`recordingElapsed`, `scheduledStartTimeMs`) and the three generation-guarded write sites are untouched — a signal-of-object refactor was evaluated and rejected: it would rewrite the wave-6 guards for zero render win and silently "fix" the stop branch's deliberate stale-elapsed quirk |
| `PreviewRetryPolicy` | `StreamPreview`'s MJPEG `onError` (pure module; the `<img>` and the timer stay at the call site) | `web/src/hooks/previewRetryPolicy.ts` | budget `MAX_PREVIEW_ERRORS = 5`; backoff `previewRetryDelayMs` = 2 s·count capped at 8 s; demote `shouldDemoteToHls(count, hls)` | the ladder's constants byte-identical (`NO_HLS_RETRY_MS = 15_000` fixed arm vs the ~10 s HLS latency); the verdict replaces the old `nextPlayerMode('mjpeg', …) !== 'mjpeg'` probe — verdict-identical given playerLadder's ladder; the reset-at-exhaustion and the `playerMode() === 'mjpeg'` guard stay at the call site |
| `TileRung` / `tileBadgeKind` | `CameraTile` (derived memos; the five primitives stay the written truth) | `web/src/lib/tileRung.ts` | auth-frame / live / snapshot; badge live / online / offline / loading | derived, never stored — the fetch/toggle/demotion writers keep writing the primitives under the wave-6 revoke/stale discipline, and the rung only reads them (revocation invalidates the browser resource, not the string); the persisted-vs-local `mode` divergence is deliberate (a silent MJPEG demotion lasts the session only and must not destroy the persisted preference; credential tiles are snapshot-locked); a loading tile still carries the offline badge styling — the stylesheet has no `-loading` rule, and the class map keeps that byte-identical |

### Deliberately implicit

Machines that stay implicit on purpose — do not re-flag them:

- The `CameraState` stickiness overlay: two holders (`CameraService._cameraState` → `CameraViewModel._cameraState`) whose collector applies `stickyCameraState` (`camera/model/RecordingToggle.kt`) to drop an incoming Idle — the screen never regresses to Idle from a state it already reached; pinned by RecordingToggleTest. Stays implicit because the verdict is one pure function over plain flows.
- Capability sentinels ship plausible defaults until the first bind: the zoom/ISO/exposure ranges start at the `CameraSettings` persistence bounds, RAW support and `torchStrengthMaxLevel` start null — "null/default until proven" is documented at each field in `camera/CameraService.kt`; the device's live ranges replace them at bind time.
- Night-vision per-branch precedence in `CameraControlPlan.from` (`camera/model/CameraControlPlan.kt`): the ON branch clamps fps to 10..≤15 and forces the NIGHT scene mode ahead of the frameRate/sceneMode settings — per-branch by design, not a global precedence table.
- The eco-idle monitor's `Job?` null-sentinel (`StreamingManager.ecoIdleMonitorJob`): null = the loop is not running; each toggle flip restarts or cancels it, so a poll loop exists only while its mode is on.
- SirenAutoStop's armed-timer overlap: an already-armed stop stays armed across a null-delay start ("run until stopped" must not touch the timer) — documented in `automation/AutomationActionExecutor.kt`'s siren path.
- `AutomationScheduler`'s `rulesCache`/`enabledCache` and the startup event-loss window: an event arriving before the first rules emission evaluates against empty caches — documented in its KDoc (`automation/schedule/AutomationScheduler.kt`).
- `StreamStateJournalWriter`'s `drop(1)` first-emission gap (`streaming/StreamStateJournalWriter.kt`): the combine's initial value is skipped, so a state equal to the journal's pre-existing contents is never re-saved — the journal already holds it.
- ForegroundNotifications' runtime mic re-checks (`core/ForegroundNotifications.kt`): the typed startForeground re-checks RECORD_AUDIO at promote time, alongside MicGate's consult-time check — two checks on purpose (notification type vs feature start).
- The wear PhotoButton's confirming timer (`wear/WearRemoteScreen.kt`): a `LaunchedEffect` retires the green "Captured" phase after an auto-clear delay — display-only, no persistence.
- `UpdateViewModel`'s `downloadJob: Job?` sentinel (`update/UpdateViewModel.kt`): null vs an in-flight job gates download re-entry; the machine states themselves live in UpdateState.
- `StreamingManager`'s `isRtspRunning` (mirrors `server != null`) vs `RtspOutput.isActive()` after a failed bind: both are published, intentionally different facts — a failed bind leaves the server "running" while the output is inactive; the derived `isStreaming` reads `isActive`, the exact bit `isLiveStreaming()` probes.
- `CameraViewModel`'s dead `isFrontCamera` mirror flow (`camera/CameraViewModel.kt`): no consumer since SelectedLens; kept as public API.
- `bindRecording` discards the recording session's `Camera` object (`camera/CameraService.kt`, `writeBack = false`): the torch/zoom/focus seams keep tracking the pre-recording session during recording — pre-existing behavior, preserved.
- `StreamWatchdog.updateState()`'s re-derive-IDLE-over-FAILED quirk (`core/StreamWatchdog.kt`): a null-status refresh can re-derive IDLE over FAILED — flagged in a comment, behavior preserved.
- PushOutput's benign cross-thread status reads (`streaming/PushOutput.kt`): `statusValue` is @Volatile, written from connect/disconnect threads and read from /api/status threads — a torn read is impossible, a stale one is benign.
- DetectionNotifier posting silently no-ops when POST_NOTIFICATIONS is denied (`capture/DetectionNotifier.kt`): the persisted toggle and the runtime permission are two truths; the degrade happens at post time (`notify` returns false and the coordinator records no notify action).
- The wear first-run door (`wear/MainActivity.kt` `firstRunDoorOpened` × `WearRemoteController` `UiState.configured`): a once-per-activity-instance bounce memory held in two classes on purpose — the door belongs to the instance (a resume without a save stays on the remote screen); the wave-6 hunt refuted the suspected bug.
- AutomationReceiver's goAsync completion ladder (`automation/AutomationReceiver.kt`): finish-once via `finished` + `completionOwnedByCallbacks`; every non-capture path finishes in finally and the capture path's feeder (PhotoCaptureManager) now guarantees the completion signal unconditionally — the ladder is correct as written.
- The SSE health backstop's redundant writers (`web/src/hooks/useAppState.ts`): `onopen`/`onerror` write `sseConnected` AND the checker republishes the same verdict — deliberate layering, not drift. The event writes make visible transitions instant; removing them to make the checker the only writer was evaluated and rejected (1–2 extra post-reconnect status fetches and up to ~3 s of connection-lost-banner linger — slower truth in exactly the windows the banner exists for). The checker earns its keep for the half-open socket no event ever reports.
- The PTT phantom label (`web/src/components/StreamPreview.tsx`): during `arming` (the mic-permission dialog window) the button reads "Talking…" with no audio captured — deliberately kept: the busy label doubles as press feedback (a reverting button invites re-presses the latch silently drops), and the honest fix is a third locale key across the seven i18n files — UX work for a future wave, now a one-line change now that `PttPhase` exists.
- RtspServer's write-failure INIT without a socket close (`streaming/rtsp/RtspServer.kt` `writeLoop`): a failed AU write drops the session to INIT (the old inline `isPlaying = false` verdict) and exits the writer, but leaves the socket to the session read loop's timeout/EOF — exactly the old failure shape; `ensureWriter`'s `isAlive` check replaces the dead writer on the next PLAY.
- The RTSP send queue's lock-free `size` read at the offer (`RtspServer.ClientSession.enqueueAu`): the 8-AU bound is soft by one AU under a drain/writer race — the policy's room-making (`size + 1 - capacity`) absorbs it; a lock to make the bound hard would re-couple the drain thread to the writer, the exact stall the decoupling removes. The cooldown stamps (`lastKeyframeRequestMs`/`lastSubKeyframeRequestMs`) are drain-thread-only writes, so they stay plain fields.

### Flagged, not named — closed (wave 7 landed the list)

The wave-5/6 flag list (the eleven naming-only remainders) landed in
wave 7, every one as a named type with its equivalence argument in the
index rows above: `PublisherSession` (the three push publishers' shared
CAS/loop/backoff skeleton, stop choreographies as opaque hooks),
`RtspSubStreamState` (the /sub twin, plain fields on purpose),
`LoopState` (the continuous loop's Dormant/Maintaining/Suppressing
machine), RecordingService's private `Phase` (the guards are exhaustive
`when`s now), MqttAlertPublisher's `Connection` record (five swap points
preserving the retry-vs-cleanup asymmetry), `PttPhase`,
`SessionPhase` + `signOut`, `RecordingPhaseKind`, `TileRung` +
`tileBadgeKind`, and `PreviewRetryPolicy`. Two candidates were evaluated
and rejected, both documented in Deliberately implicit: making the SSE
health checker the only `sseConnected` writer (slower truth in exactly
the windows the banner exists for) and fixing the PTT phantom "Talking…"
label (press feedback; the honest fix is a third locale key — a UX
wave, and a one-liner now that the phase machine exists). There is no
open flag list; new machines get named when next touched, per the
program's standing rule.
