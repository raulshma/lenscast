# NVR & Smart-Home Integration

To a consumer, LensCast is a standard network camera: an HTTP server
(dashboard, M-JPEG, snapshot, HLS), an RTSP server (H.264 or H.265 video,
AAC audio), and an optional ONVIF Profile S device service. This page
collects tested recipes for pointing other systems at it.

## Endpoints (defaults)

Defaults from `core/StreamDefaults.kt`; the web and RTSP ports are
configurable (1024–65535). See [remote access](remote-access.md) for
tunneling these URLs outside your LAN.

| Endpoint | Default URL |
|---|---|
| Snapshot (JPEG frame) | `http://PHONE_IP:8080/snapshot` |
| M-JPEG stream | `http://PHONE_IP:8080/stream` |
| HLS playlist | `http://PHONE_IP:8080/hls/playlist.m3u8` |
| RTSP (H.264/H.265 + AAC) | `rtsp://PHONE_IP:8554/stream` |
| ONVIF device service | `http://PHONE_IP:8080/onvif/device_service` |
| WS-Discovery | UDP `239.255.255.250:3702` (Probe/ProbeMatch) |

The HLS playlist (and the dashboard's WebCodecs video) is produced by the
shared encoded H.264/AAC pipeline, not the M-JPEG pump: it is available
while the RTSP output is streaming or an HLS/WebSocket video client has
kept that pipeline recently active — no M-JPEG consumer needs to be
connected. HLS and WebCodecs are H.264-only: with the RTSP codec set to
H.265 those two sinks go dark until the codec returns to H.264 (the RTSP
stream itself keeps flowing).

## Home Assistant (generic camera)

```yaml
camera:
  - platform: generic
    name: LensCast
    still_image_url: http://PHONE_IP:8080/snapshot
    stream_source: rtsp://PHONE_IP:8554/stream
    rtsp_transport: tcp
    # required only when stream auth is enabled:
    username: youruser
    password: yourpassword
    # required only when the HTTPS toggle is on:
    verify_ssl: false
```

Notes:

- `rtsp_transport: tcp` matters: the RTSP server negotiates RTP/AVP/TCP
  (interleaved) only — a UDP SETUP is rejected with 461 Unsupported Transport.
- RTSP authentication is a Digest challenge (MD5, qop=auth) when stream auth
  is enabled; Basic is also accepted by the server.
- The HTTP side (including `/snapshot`) uses cookie-based session auth, not
  HTTP Basic, and the generic camera integration cannot send arbitrary
  headers — so when web auth is enabled, `still_image_url` cannot
  authenticate with URL credentials. Two ways out: leave web auth off for
  unattended pullers on a trusted LAN segment, or enable the API token in
  the dashboard and pull snapshots with a header-capable client:

  ```bash
  curl -H "Authorization: Bearer YOUR_TOKEN" http://PHONE_IP:8080/snapshot -o snap.jpg
  ```

## Home Assistant (ONVIF)

LensCast ships an ONVIF Profile S device service, so Home Assistant can
discover and set it up without any YAML. Enable the **ONVIF (Profile S)**
toggle in the dashboard's settings panel (off by default; `onvifEnabled`),
then in Home Assistant run **Settings → Devices & Services → Add
Integration → ONVIF** — the device is found automatically via WS-Discovery
(LensCast answers Probe datagrams on UDP 239.255.255.250:3702), or you can
enter the IP manually. The integration only needs the host; it picks up the
stream URI (`rtsp://PHONE_IP:8554/stream`) and the snapshot URI
(`http://PHONE_IP:8080/snapshot`) from `GetStreamUri` / `GetSnapshotUri`.

Notes:

- The SOAP endpoint lives on the web port at
  `http://PHONE_IP:8080/onvif/device_service` and answers the standard
  device/media queries: `GetSystemDateAndTime`, `GetCapabilities`,
  `GetServices`, `GetDeviceInformation`, `GetVideoSources`, `GetProfiles`,
  `GetStreamUri`, and `GetSnapshotUri` (unknown operations get an
  `ActionNotSupported` fault). While the toggle is off, the endpoint answers
  a fault instead — nothing is advertised.
- The endpoint is **unauthenticated by design**: ONVIF clients cannot hold
  the web session cookie, so gating it would disable ONVIF entirely. It
  answers only static device metadata plus the two URIs — the RTSP stream
  itself keeps its own Digest/Basic auth, and the snapshot route stays
  behind the web gate. `GetDeviceInformation` reports the manufacturer,
  model, firmware, and a serial number derived from the device's Android ID;
  none of this leaves the LAN except through the toggle above. Run it on a
  trusted LAN segment.
- Profiles advertise the RTSP video configuration — codec, resolution,
  bitrate, fps — so H.264 (the default) is what every ONVIF consumer
  expects, and the profile flips to an H.265 encoding when the setting does.
  Switching `rtspVideoCodec` to H.265
  changes the wire codec of the same URI — useful when the NVR or the
  network prefers HEVC (lower bitrate at the same resolution, and the only
  way to carry 1080p comfortably on constrained links), but only point an
  H.265-capable consumer at it: VLC/ffmpeg handle it, older clients may
  not. LensCast's own HLS/WebCodecs paths stay H.264-only either way.
- With the HTTPS toggle on, every web-port URL ONVIF advertises (the device
  service XAddrs and the snapshot URI) uses `https://` to match.

## API token (programmatic writes)

When stream auth is enabled, the API token (Bearer / `X-Api-Token` header)
authorizes read-only GET and HEAD requests — and POSTs on an explicit
allow-list, so scripts can drive the camera without holding a session
cookie:

```bash
TOKEN="Authorization: Bearer YOUR_TOKEN"

# Stream lifecycle
curl -X POST -H "$TOKEN" http://PHONE_IP:8080/api/stream/rtsp/start
curl -X POST -H "$TOKEN" http://PHONE_IP:8080/api/stream/stop

# Photo capture
curl -X POST -H "$TOKEN" http://PHONE_IP:8080/api/capture

# Recording lifecycle
curl -X POST -H "$TOKEN" -H "Content-Type: application/json" \
  -d '{"durationSeconds":30,"repeatIntervalSeconds":0,"quality":"HIGH","includeAudio":true}' \
  http://PHONE_IP:8080/api/recording/start
curl -X POST -H "$TOKEN" http://PHONE_IP:8080/api/recording/stop

# Deterrence
curl -X POST -H "$TOKEN" -H "Content-Type: application/json" \
  -d '{"siren":true}' http://PHONE_IP:8080/api/deterrence/siren
curl -X POST -H "$TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled":true}' http://PHONE_IP:8080/api/camera/torch
```

The writable set is exactly: `/api/stream/start`, `/api/stream/resume`,
`/api/stream/stop`, `/api/stream/web/start`, `/api/stream/web/stop`,
`/api/stream/rtsp/start`, `/api/stream/rtsp/stop`, `/api/capture`,
`/api/recording/start`, `/api/recording/stop`, `/api/deterrence/siren`,
`/api/camera/torch`.
Everything else stays read-only for tokens, and no `/api/auth/` route is
ever token-writable.

## VLC / ffmpeg

```bash
# RTSP (H.264 video, AAC audio by default; H.265 when the codec setting is switched) — force TCP to match the server
ffplay -rtsp_transport tcp rtsp://PHONE_IP:8554/stream
vlc rtsp://PHONE_IP:8554/stream

# Record without re-encoding
ffmpeg -rtsp_transport tcp -i rtsp://PHONE_IP:8554/stream -c copy out.mp4

# M-JPEG over HTTP (universal fallback)
ffplay http://PHONE_IP:8080/stream
vlc http://PHONE_IP:8080/stream
```

With stream auth enabled, VLC and ffmpeg answer the RTSP Digest challenge
from their normal credential prompts / `-rtsp_transport` + URL credentials
(`rtsp://user:pass@PHONE_IP:8554/stream`).

## Frigate

LensCast currently produces a single RTSP stream, so the detect-role pattern
(a low-resolution sub-stream dedicated to detection) is not yet available —
the main output serves both `detect` and `record` roles:

```yaml
cameras:
  lenscast:
    ffmpeg:
      inputs:
        - path: rtsp://PHONE_IP:8554/stream
          input_args: preset-rtsp-tcp
          roles:
            - detect
            - record
    detect:
      enabled: true
```

For 24/7 detect duty, keep the stream modest: the RTSP output defaults to
1280x720 at 2 Mbps H.264 (the resolution setting can drop it to 480p),
which is already a sensible detect resolution —
resist raising it, and prefer a lower frame rate over long-running high
output. On a phone, sustained encoding is thermal-bound; see
[Monitoring & Power Management](../README.md#monitoring--power-management)
in the README for how LensCast adapts quality and frame rate as the device
heats up.

## ntfy / webhooks

When a detection event fires, LensCast POSTs JSON to the configured webhook
URL (`webhookEnabled` / `webhookUrl` in streaming settings). Motion and
sound events are gated by the arm schedule; tamper (power disconnected
while a stream is live) dispatches immediately, outside the schedule. When
the ML object-detection gate is on (`mlDetectionEnabled`), a motion event
additionally needs an allowed class at or above the confidence threshold
before it dispatches anywhere — webhook, MQTT, log, and deterrence
alike. Delivery retries up
to three attempts with a linear backoff (2 s, then 4 s). The event feed's
`webhook` badge records that a dispatch went out for the event; a delivery
that exhausts the retry ladder surfaces as a device-log warning, not a feed
correction. Dispatch is
single-flight: while one event is on that retry ladder (worst case on the
order of a minute — each attempt can spend both its connect and its read
timeout, 10 s each, plus the two backoff waits), later events are
coalesced — the newest one waits in a single slot
and follows the live dispatch, anything older than that is dropped, so a
burst costs the intermediate detections but never the latest one. A
custom-header map
(`webhookHeaders`, a JSON `{"Name": "value"}` object — e.g. an
`Authorization` header) is applied after the built-ins. Headers:
`Content-Type: application/json` and `Title: LensCast detection event`.

Payload shape (current fields):

```json
{
  "type": "motion",
  "value": 12.34,
  "timestampMs": 1725800000000,
  "source": "lenscast",
  "zones": ["Doorway"],
  "labels": ["person"],
  "batteryPercent": 87,
  "snapshotJpeg": "<base64 JPEG, when the base64 text fits the 40,000-character cap (~30 KB of JPEG)>"
}
```

- `type` — `"motion"`, `"sound"`, or `"tamper"` (power disconnected while a
  stream is live).
- `value` — the detector metric: frame delta for motion, RMS percent for
  sound, battery percent for tamper (`0` when the level is unknown — the
  field is always present; `batteryPercent` below is the one that omits).
- `timestampMs` — epoch milliseconds at the event.
- `source` — always `"lenscast"`.
- `zones` — labels of the motion zones that fired; `[]` for whole-frame or
  non-motion events, so automations can attribute motion to a named region.
- `labels` — ML class labels attached by the object-detection gate
  (`person`, `dog`, `car`, …) when `mlDetectionEnabled` is on and the motion
  event passed the gate; `[]` when the gate is off, unavailable, or the
  event never went through it (sound and tamper are never classified).
- `batteryPercent` — device battery level at trigger time (omitted when
  unknown).
- `snapshotJpeg` — base64-encoded downscaled JPEG (long edge ~640 px, quality 60)
  grabbed from the live stream at trigger time. When the base64 text exceeds
  the 40,000-character cap (~30 KB of JPEG), it is re-encoded at
  progressively halved decode sizes until it
  fits (a busy scene ships a smaller thumbnail, never a truncated one);
  omitted when no frame is available or even the smallest decode exceeds the
  cap. Detection events
  are also kept in an on-device log served by `GET /api/detection/events`
  (clearable with `DELETE`), so the dashboard's event feed can show them
  even without a webhook endpoint.

Recipe — ntfy: point `webhookUrl` at a topic URL, e.g.
`https://ntfy.example.com/lenscast`. The JSON body arrives as the message
body and the `Title` header becomes the notification title.

Recipe — Home Assistant: create a webhook-triggered automation:

```yaml
automation:
  - alias: "LensCast detection"
    triggers:
      - trigger: webhook
        allowed_methods:
          - POST
        webhook_id: lenscast
    actions:
      - action: notify.persistent_notification
        data:
          title: "LensCast {{ trigger.json.type }}"
          message: "value={{ trigger.json.value }}"
```

## MQTT (Home Assistant discovery)

When MQTT is enabled (`mqttEnabled` plus `mqttBrokerHost`/`mqttBrokerPort`,
optionally `mqttUsername`, `mqttTls`, `mqttPassword` — write-only like the
backup credentials; the credentials travel as a pair, since MQTT 3.1.1
forbids a password without a username — and `mqttDiscoveryPrefix`, the HA
discovery root, default `homeassistant`), LensCast connects to the broker
(QoS 1 publishes, 60 s keepalive) and turns its detection events into Home
Assistant `binary_sensor` entities via MQTT discovery:

- Discovery configs (retained) are published under
  `<discoveryPrefix>/binary_sensor/lenscast_<deviceId>_{motion,sound,tamper}/config`
  — one sensor per kind, each with an `off_delay` that auto-resets the ON
  pulse (30 s motion, 5 s sound, 60 s tamper).
- Per event, the sensor's state topic receives an `ON` pulse and the event
  topic `<discoveryPrefix>/lenscast/<deviceId>/event` receives the same JSON
  payload as the webhook (zones, ML labels, and snapshot included).
- Availability `<discoveryPrefix>/lenscast/<deviceId>/status` is retained
  (`online`), with a retained `offline` last will — so a device that dies
  without a goodbye shows unavailable in HA, and a broker restart replays
  the true state. Turning MQTT off also publishes a retained `offline` and
  clears the retained discovery configs (empty retained payloads) before
  disconnecting, so the entities never outlive the setting; changing the
  discovery prefix or broker clears the old configs the same way before
  re-announcing under the new ones.

`<deviceId>` is the install-scoped Android ID (a persisted random id on
the rare install where ANDROID_ID is unreadable, so two such devices
never share one MQTT client id). Enabling MQTT requires no
extra apps: point it at any broker (Mosquitto, Home Assistant's built-in
broker, a cloud broker) and the entities appear automatically.
