# Privacy Policy

LensCast is a camera and streaming tool. Its privacy posture is simple:
**all processing happens on your device**. There is no LensCast account,
no telemetry, no analytics, and no crash reporting SDK.

## Data processing — local by default

- Camera and microphone data are processed on-device for preview,
  capture, streaming, and (if you enable it) motion/sound/ML detection.
- Captured photos and recordings stay in the app's storage on the device,
  subject to the retention window and storage quota you configure.
- The detection-event log, audit log, and settings live in the app's
  private storage. The auth session tokens and the detection-event log
  (it embeds event snapshots) are explicitly excluded from OS-level cloud
  backup and device transfer — they never leave the device that produced
  them.
- No analytics, no telemetry, no advertising identifiers, no crash
  reports. Nothing phones home to the developers.

## Optional network features — off unless you configure them

The built-in HTTP/RTSP servers listen on your local network only. Every
feature below that can send data to a third party is **opt-in and
user-configured**; LensCast never enables egress on its own:

- **RTMP / WHIP push streaming** — pushes your live stream to the media
  server URL you enter (e.g. YouTube, Twitch, nginx-rtmp, MediaMTX,
  Cloudflare). Off by default; only connects to the endpoint you set.
- **WebDAV / Telegram backup** — auto-uploads new captures to the WebDAV
  collection or Telegram chat you configure, with an optional Wi-Fi-only
  mode. Note: uploads are sent as readable files (decrypted), so the
  remote copy is not encrypted-media-at-rest.
- **Webhook / MQTT alerts** — pushes detection-event payloads (including
  a snapshot image) to the webhook URL or MQTT broker you configure
  (e.g. ntfy, Home Assistant).
- **Web Push notifications** — sends encrypted detection notifications
  to browsers you have explicitly subscribed from the dashboard. Each
  notification is end-to-end encrypted (RFC 8291) and published through
  the browser's push service (FCM/Mozilla autopush); the push service
  sees only ciphertext and routing metadata.

## Runtime downloads

Two optional ML models (object detection, EfficientDet-Lite0, and sound
classification, YAMNet) are **not bundled in the APK**. If you enable
those features, the model file is downloaded once on first use from
GitHub releases / TensorFlow's official hosting and is verified against a
**pinned SHA-256** before use. The download happens only when you enable
the feature; the source sees an anonymous file request.

## Location

- **EXIF GPS geotagging is opt-in.** Photos can embed GPS coordinates in
  their EXIF metadata, but only if you enable the setting — the location
  permission is requested at that moment, never before, and the data is
  written into your photo files (not sent anywhere by LensCast itself).
- The app otherwise uses coarse network state only to serve streams and
  show connectivity — it does not track or transmit your location.

## In-app update check

The store flavor checks GitHub releases for a newer version (version,
APK size, SHA-256 digest). The check goes to GitHub's API and carries no
identifiers beyond a standard HTTPS request. The F-Droid flavor contains
no updater at all.

## Summary

| Data | Where it goes |
|---|---|
| Camera / microphone / media | Stays on device |
| Detection events, audit log, auth tokens | Stays on device (excluded from cloud backup) |
| Settings | Stays on device |
| Stream, backups, alerts, push | Only to the endpoints **you** configure |
| Optional ML models | One-time download from official hosting, SHA-256-pinned |
| Telemetry / analytics / ads | **None, ever** |
