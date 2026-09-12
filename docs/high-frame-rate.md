# High frame rate / slow-motion recording — feasibility note

Status: **investigated, deliberately not implemented** (bounded wave). This
note records why the frame-rate ceiling stays at 60 fps for now and what a
real implementation would take.

## Current behavior

- The persisted camera frame rate is clamped to a 15–60 fps slider span
  (`CameraSettings.FRAME_RATE_SLIDER_MIN/MAX`; the persistence ceiling
  `FRAME_RATE_MAX` is 120 but no UI or store path offers above 60).
- `CameraControlPlan.from` maps the requested fps onto
  `CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE` as a fixed `[fps, fps]` range,
  applied by `CameraService` through Camera2 interop. Recordings ride the
  same request: `RecordingService` records from the CameraX `Recorder` bound
  through `CameraService.bindRecording()` on the *normal* (non-high-speed)
  session the service builds.

## Why > 60 fps is not a clean CameraX change

Android sensors expose frame rates above 60 (90/120/240) almost exclusively
inside the **constrained high-speed** mode:

- The mode requires a `CameraConstrainedHighSpeedCaptureSession` — a distinct
  session type with its own request batching — and only accepts the sizes and
  ranges listed in `CONTROL_AE_AVAILABLE_HIGH_SPEED_VIDEO_SIZES` /
  `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` marked for high speed.
- CameraX `Recorder` (all versions through 1.5.0, the one this project pins)
  builds a standard capture session and exposes **no API** to select
  high-speed mode, high-speed sizes, or a high-speed fps range.
  `VideoCapture.Builder` has no high-speed variant; the longstanding upstream
  answer to the feature request is "use Camera2".
- Forcing `CONTROL_AE_TARGET_FPS_RANGE = [90, 90]` (or `[120, 120]`) onto the
  normal session via `Camera2Interop` is **HAL-dependent**: most devices
  silently clamp back to 60 or reject the request, and the ones that accept
  it do not guarantee the per-frame cadence slow motion needs (dropped and
  duplicated frames corrupt the MP4 timeline). Shipping that as a "90 fps"
  toggle would be dishonest on the majority of devices.

## Recommended path (a later wave)

1. Keep the CameraX pipeline for preview/streaming/normal recordings.
2. Add a **Camera2-based slow-mo recorder** behind the existing Camera
   Binding Seam (`bindRecording()` gains a high-speed sibling), gated by a
   capability probe: a high-speed session exists only when
   `CONTROL_AE_AVAILABLE_HIGH_SPEED_VIDEO_SIZES` is non-empty and contains a
   size the encoder handles (commonly 1280×720 or 1920×1080 at [120, 120] or
   [240, 240]).
3. Drive `CameraConstrainedHighSpeedCaptureSession` into MediaCodec
   (`KEY_VENDOR`-free H.264 path) + MediaMuxer — the EncryptedMediaSink /
   capture-history choreography can be reused as-is; only the frame source
   changes.
4. Surface the result as a capture-speed setting (SLOW-MO ½ / ¼) that maps
   capture fps → playback fps, rather than exposing raw sensor rates.

## What was NOT done in this wave

- No code changes: the 60 fps ceiling stays, because "extend the ceiling to
  the device max" without the high-speed session would either do nothing
  (clamped by the HAL) or produce variable-cadence captures — worse than the
  honest cap.
- The web/MJPEG and RTSP streaming paths are additionally bounded by the
  encoder and the network ladder; > 60 fps there is a separate product
  question (and only matters for local MP4 recordings anyway).
