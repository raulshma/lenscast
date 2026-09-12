# SRT Push Publishing

LensCast can push its encoded stream out to an **SRT (Secure Reliable
Transport)** listener — the protocol MediaMTX, OBS Studio, Haivision gateways,
and most NVR/ingest stacks accept. Like the RTMP push, the phone connects
*out* to the listener (caller mode), so it works through NATs without port
forwarding; unlike RTMP the transport is UDP datagrams with the low-latency
ARQ semantics SRT is known for.

The publisher is hand-rolled inside the app (`streaming/srt/`): the SRT
control/data packet headers, the caller handshake (induction + conclusion
with the HSREQ/HSRESP extension blocks), and a per-session MPEG-TS muxer
(PAT/PMT + PES with continuity counters) — no third-party streaming
dependency.

## Requirements and caveats

- **H.264 video only.** Like the RTMP push, the SRT output refuses to start
  under the H.265 codec, a live push is stopped when the codec is switched,
  and the encoded pipeline's H.265 access units are never routed to the SRT
  sink.
- **Audio is AAC**, muxed into the TS the same way the HLS ring does (shared
  TS primitives; the AAC rides a PES stream on PID 0x0101 declared as
  stream type 0x0F in the PMT, with the same caveats as the HLS audio path).
- The push joins the stream at the **next keyframe**, and a fresh connection
  always starts with PAT/PMT and re-announces them before every keyframe, so
  a late listener decodes from the next GOP.
- **No retransmission in v1.** Full ACKs are parsed (the RTT shows up in the
  stats and the status snapshot) and NAKs are parsed, counted, and logged —
  but NAKed packets are not resent. MPEG-TS tolerates loss (a continuity
  glitch, not corruption) and the listener's latency buffer absorbs most
  bursts, but on a lossy path you will see picture artifacts where a full SRT
  stack would repair them. The ACK/NAK bookkeeping here is exactly the
  scaffolding a retransmit window would ride.
- **No encryption in v1** (`streamid` and optional URL userinfo are the
  credential surface; the KK field is always 0). Passphrase/KM exchange is
  not implemented.
- Keepalives are sent when the media path is quiet; a listener that goes
  fully silent for 15 s drops the attempt and the capped-backoff
  auto-reconnect ladder (1 s doubling to 30 s) takes over, exactly like the
  RTMP publisher's.

## URL format

Configure the push target in the app's settings under **SRT Push** (or
through the settings store / Web API):

```
srt://host[:port]                       # default port 9710
srt://host:port?streamid=<publish-id>   # the streamid is passed through verbatim
srt://user:pass@host:port?streamid=…    # optional URL userinfo
```

Extra query parameters are tolerated and ignored, so a target pasted from an
NVR (`?mode=caller&latency=120`) still parses. The URL is a **write-only**
credential over the Web API, exactly like the RTMP push URL: PUT carries it,
GET responses are always blank, and the only rendering that ever reaches a
log is `srt://host:port?streamid=<redacted>`.

## Wire facts (for interop debugging)

- Handshake: Version 4 induction (Extension Field 2), Version 5 conclusion
  with the HSREQ block (SRT version 1.4.3, flags TSBPDSND|CRYPT, 120 ms
  TSBPD delay). The induction response's magic `0x4A17` is verified per
  spec.
- Data: MPEG-TS payloads, 7 × 188 = 1316 bytes per datagram, single ordered
  unencrypted messages (`FF=10, O=1, kk=00`), u31 sequence numbers, µs
  timestamps from the session clock, PTS at 90 kHz from the same clock.

## API

- `POST /api/stream/srt/start` · `POST /api/stream/srt/stop` (token-writable
  device actions, like the RTMP pair).
- Status: `srtEnabled` / `srtActive` / `srtStatus` (`idle`, `connecting`,
  `connected`, `error`) / `srtError` / `srtRttMs` on the `/api/status`
  snapshot's `streaming` object.
- Settings: `srtEnabled` plus the write-only `srtUrl` on the settings
  document.
- MQTT: a retained `ON`/`OFF` on `lenscast/<device-id>/stream/srt/state`
  follows the push lifecycle (see the MQTT telemetry section of the
  integrations docs).
