# RTMP Push Publishing

LensCast can push its encoded stream out to an RTMP server — the protocol
YouTube Live, Twitch, Facebook Live, nginx-rtmp, SRS, and MediaMTX accept
from broadcasting software like OBS. Instead of (or alongside) the pull
outputs (RTSP, HLS, web), the phone connects out to the ingest server and
publishes H.264 video and AAC audio over a single outbound TCP connection,
so it works through NATs without any port forwarding.

The publisher is hand-rolled inside the app (`streaming/rtmp/`): the plain
RTMP handshake, the chunk protocol, AMF0 command encoding, and the
FLV-style AVC/AAC message bodies — no third-party streaming dependency.

## Requirements and caveats

- **H.264 video only.** RTMP has no standard mapping for H.265/HEVC. With
  the RTSP video codec set to H.265 the push refuses to start with a
  readable error, a live push is stopped when the codec is switched, and
  the encoded pipeline's H.265 access units are never routed to the RTMP
  sink. Switch the codec back to H.264 and restart the push.
- **Audio is AAC**, the same live-audio path the web/HLS/RTSP outputs use
  (subject to the stream-audio toggle and microphone arbitration).
- The push joins the stream at the **next keyframe** (a mid-GOP start
  would be undecodable), and sequence headers (AVC `avcC`,
  `AudioSpecificConfig`) are always (re)sent ahead of the frames that
  need them, including after an encoder restart.
- The shared encoded pipeline runs whenever the push is live, exactly
  like an RTSP/HLS/WS consumer — no other output needs to be on.

## URL formats

Configure the push target in the app's settings under **RTMP Push** (or
through the settings store):

```
rtmp://host[:port]/app/streamKey
rtmps://host[:port]/app/streamKey          # TLS (default port 443; plain rtmp is 1935)
rtmp://user:pass@host[:port]/app/streamKey # optional credentials on the connect command
```

- The **stream key is the last path segment**; everything before it is the
  app name (multi-level apps like `live/extra` stay whole).
- A **query string stays with the app** (`rtmp://host/live/key?user=u&pass=p`
  is how nginx-rtmp's `on_publish` auth expects credentials).
- `rtmps://` wraps the same protocol in TLS. Many public ingests accept
  either; YouTube uses `rtmps://a.rtmp.youtube.com/live2` (port 443 for
  `rtmps`), Twitch uses `rtmp://live.twitch.tv/app` (1935) or
  `rtmps://live.twitch.tv:443/app`.

The URL is stored on the device only. It is **never echoed back by the Web
API** (the stream key is a credential; the start/stop responses carry no
URL), so anyone with dashboard access cannot read your ingest key off the
network.

## Status and lifecycle

The push has a four-state lifecycle mirrored on the device's status
snapshot (`GET /api/status`, fields `rtmpEnabled`, `rtmpActive`,
`rtmpStatus`, `rtmpError`) and on the settings screen:

| State | Meaning |
|---|---|
| `idle` | Not started (or stopped) |
| `connecting` | TCP/TLS connect, handshake, or the command ladder in flight |
| `connected` | Publish confirmed (`NetStream.Publish.Start`), media flowing |
| `error` | The last attempt failed; `rtmpError` carries the readable reason |

Failures auto-reconnect with a capped exponential backoff (1 s doubling to
30 s) while the output is enabled and started. Stopping sends the clean
close pair (`FCUnpublish` + `deleteStream`) before dropping the socket, so
the server frees the stream key immediately instead of waiting for a
timeout.

## Web API

```
POST /api/stream/rtmp/start
POST /api/stream/rtmp/stop
```

They mirror `/api/stream/rtsp/start|stop`: both are token-writable (see
the API-token allow-list), and both appear in the audit trail. `start`
answers `success=false` when the push is disabled or the validation ladder
refused it (disabled output, unusable URL, H.265 codec) — the specific
reason rides `rtmpStatus`/`rtmpError` on the status snapshot and the
settings screen.

## Recipe notes

- **YouTube Live**: enable the encoder in YouTube Studio, copy the stream
  key, and use
  `rtmp://a.rtmp.youtube.com/live2/<your-key>` (or the `rtmps://...:443`
  variant). YouTube wants H.264 + AAC — exactly what this publisher sends.
- **Twitch**: use `rtmp://live.twitch.tv/app/<stream_key>`; Twitch requires
  the key (the app name is fixed to `app`). Twitch Ingest Testing lists
  nearest-server hostnames if you want a specific ingest.
- **nginx-rtmp**: a minimal config is enough —
  `rtmp { server { listen 1935; application live { live on; } } }`. Point
  the push at `rtmp://SERVER_IP/live/lenscast` and play it back with any
  RTMP/HLS client the config exposes. nginx-rtmp's `on_publish` HTTP auth
  works with the query-string form above.
- **MediaMTX / SRS**: both accept a plain RTMP publish on the default
  port; MediaMTX re-serves it as RTSP/HLS/WebRTC, which pairs well with
  LensCast's own pull outputs.
- **OBS comparison**: LensCast pushes the same way OBS does (x264 → AVC
  NALUs, AAC frames behind FLV-style tags), so anything that documents an
  "OBS custom RTMP" URL works verbatim in the push URL field.

## Security notes

- **The stream key is a credential** — anyone holding it can stream to
  your channel (or, for a self-hosted server, occupy your stream name).
  Keep the push URL off shared screens and logs; LensCast stores it in the
  device settings store only and never returns it through the Web API.
- Prefer `rtmps://` (TLS) where the server supports it — it protects both
  the stream key and the media from passive LAN observers. Plain
  `rtmp://` is cleartext, including the URL-userinfo credentials.
- Prefer URL-userinfo credentials over app query strings when both are
  offered; nginx-rtmp's `on_publish` style query auth puts the password in
  the URL too — the same handling applies.
- Enabling RTMP (like enabling RTSP) arms an outbound stream: the phone
  publishes whenever the output is started. Use the Web API's token auth
  if the dashboard is exposed beyond your LAN, since the `/api/stream/rtmp/*`
  routes can start and stop the push.
