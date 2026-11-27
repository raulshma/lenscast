# Device testing: drive and observe over HTTP

The connected device is a test rig. Install a build (see the
[install walkthrough](../CONTRIBUTING.md#install-on-a-connected-device)),
launch it, then drive the camera and streams through its web server and
watch the results from the host — no manual tapping. This page is the
drive-and-observe recipe: the facts every on-device validation session
needs before its first `curl`.

## Reach the server

```bash
adb shell monkey -p com.raulshma.lenscast 1     # launch the app
adb shell ip -f inet addr show wlan0            # the device's LAN IP
curl -s http://<device-ip>:8080/api/auth/status # {"required":false} on a dev device
```

The server listens on **:8080** by default (port changes, auth modes, and
remote tunnels are [remote-access](remote-access.md)'s territory). With
auth off, reads are open. With auth on, log in once and reuse the session
cookie, or arm the API token and send `Authorization: Bearer <token>`
(coverage in [remote-access](remote-access.md)).

## Writes: the CSRF gate

State-changing POSTs/PUTs must prove same-origin: a matching `Origin`
header, the `X-Requested-With: <any>` marker (accepted from any value), or
an API token (token requests skip the check entirely). A bare curl fails
the gate even when auth is off — always send the header from scripts:

```bash
curl -s -X POST -H "X-Requested-With: curl" \
    http://<device-ip>:8080/api/stream/start
```

The mechanism's full story lives in
[usb-streaming](usb-streaming.md) ("Scripting the API through a forward").

## The surface you'll actually use

| Route | Notes |
|---|---|
| `GET /api/status` | the read-everything snapshot (`torchOn`, streaming lanes, fps, …). Field names are pinned by `web/contract/status.json` |
| `GET` / `PUT /api/settings` | **section-level merge**: send only the section you're changing (`{"camera": {…}}`); fields the section omits keep their stored values. The settings DTO carries **no torch fields** by contract — torch writes ride `/api/camera/torch` so the convergence policy sees explicit intent |
| `POST /api/stream/start` / `…/stop` | all lanes at once; `/api/stream/web/start`, `/api/stream/rtsp/start`, … drive one lane |
| `POST /api/camera/torch` | body `{"enabled": <bool>}` — the live torch lever |
| `POST /api/system/reset-camera` | unbind + rebind; the lever for "what does a fresh session bake" questions |
| `POST /api/system/restart?mode=soft` | in-process soft-restart ladder, keeps the foreground camera standing; no/other `mode` is the deep restart (Android 11+ blocks background camera until the app is foregrounded) |

Streams to watch on the wire:

| URL | What it is |
|---|---|
| `http://<ip>:8080/stream` | MJPEG live stream — endless multipart, so always curl with `--max-time` |
| `http://<ip>:8080/snapshot` | one JPEG frame |
| `http://<ip>:8080/hls/playlist.m3u8` | HLS playlist + `/hls/seg*.ts` segments |
| `rtsp://<ip>:8554/stream` | RTSP — `DESCRIBE` returns SDP carrying the SPS in `sprop-parameter-sets` |
| `ws://<ip>:8080/ws/video` | WebCodecs video route (cookie-auth gated; open when auth is off) |

## Observing results

**logcat is the primary observable** — the components narrate their own
decisions, and the hub's dimension lines are the dimension truth:

```bash
adb logcat -c && adb logcat | grep -E 'EncodedStreamHub|CameraService|StreamingManager'
#   EncodedStreamHub: "Encoded stream started (h264 1080x1920; …)"
#                     "Encoder reconfigured to 1920x1080 (h264)"
adb logcat -d | grep -E 'AndroidRuntime|FATAL'   # crash sweep at the end
```

- **Dimensions / orientation flip**: the `Encoded stream started` /
  `Encoder reconfigured to W×H` logcat lines; for wire-level proof, decode
  the SPS from an RTSP `DESCRIBE` with `scripts/device/sps_dimensions.py`.
- **HLS reconfigures**: poll the playlist for `#EXT-X-DISCONTINUITY` — it
  lands at the first keyframe after an encoder reconfigure.
- **MJPEG fps**: count frames over a timed window:
  `curl -s --max-time 10 http://<ip>:8080/stream | grep -c 'Content-Length'`
- **Torch**: `torchOn` in `/api/status`; hardware flips surface in logcat
  (framework `onTorchStatusChanged` lines).
- **Restore state when done**: snapshot `/api/settings` before mutating,
  PUT it back, and stop lanes you started — device-testing sessions share
  the phone with its owner.
