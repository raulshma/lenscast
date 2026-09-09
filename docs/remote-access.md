# Remote Access

LensCast serves everything from the phone itself: the dashboard, the streams,
and the API all live on the device. There is no cloud component, so viewing the
camera from outside your LAN means building an encrypted path to it. The
options below are ordered by recommendation.

## Endpoints and defaults

Defaults from `core/StreamDefaults.kt`. The web and RTSP ports are configurable
in the app (valid range 1024–65535); the WebSocket sidecar always rides the web
port + 1, so 65534 is the practical web-port ceiling — at 65535 the sidecar has
nowhere to bind and starts without WebSocket video or talkback.

| Surface | Default port | HTTP mode | HTTPS mode |
|---|---|---|---|
| Dashboard (web root) | 8080 | `http://PHONE_IP:8080/` | `https://PHONE_IP:8080/` |
| M-JPEG stream | 8080 | `http://PHONE_IP:8080/stream` | `https://PHONE_IP:8080/stream` |
| Snapshot (JPEG frame) | 8080 | `http://PHONE_IP:8080/snapshot` | `https://PHONE_IP:8080/snapshot` |
| HLS playlist | 8080 | `http://PHONE_IP:8080/hls/playlist.m3u8` | `https://PHONE_IP:8080/hls/playlist.m3u8` |
| WebSocket sidecar (WebCodecs video / talkback) | 8081 (web port + 1) | `ws://PHONE_IP:8081` | `wss://PHONE_IP:8081` |
| RTSP (H.264 + AAC) | 8554 | `rtsp://PHONE_IP:8554/stream` | `rtsp://PHONE_IP:8554/stream` (unchanged) |

Two toggles change how these URLs behave:

- **HTTPS** (`httpsEnabled`, off by default): the web server and WebSocket
  sidecar switch to TLS with a persistent self-signed certificate. The
  certificate's SHA-256 fingerprint is shown on the in-app Connect sheet for
  one-tap verification.
- **Stream auth** (off by default): when enabled, `/api/*`, `/stream`,
  `/audio`, `/snapshot`, and `/hls/*` require a session obtained by logging in
  on the dashboard. Passwords are stored PBKDF2-hashed and failed logins are
  rate-limited. The RTSP server challenges with Digest (Basic is also
  accepted) using the same credentials. For scripted clients that cannot hold
  a cookie (curl one-liners, Home Assistant's `still_image_url`), the settings
  can enable a read-only API token: send it as `Authorization: Bearer <token>`
  or `X-Api-Token: <token>` and it authorizes read-only GET and HEAD requests
  to the protected routes (never the auth routes, never writes, and never the
  WebSocket paths — those stay cookie-session-only). Token requests skip the
  cookie path's CSRF origin check: they are header-authenticated, so there is
  nothing for a cross-site page to forge. While the token setting is off,
  token headers are ignored: protected routes behave exactly as if the header
  were absent.

On the LAN, the stream is also discoverable via mDNS/NSD.

## 1. Tailscale (recommended)

A tailnet gives you WireGuard connectivity with zero router changes, and the
phone serving the stream needs no Tailscale-specific setup.

1. Install Tailscale on the phone running LensCast and on every viewing device;
   sign both into the same tailnet.
2. Find the phone's tailnet IP (100.x.y.z) in the Tailscale admin console or
   with `tailscale ip` on the phone.
3. Open the dashboard at `https://100.x.y.z:8080/` (or plain `http://` if you
   run without the HTTPS toggle).

Certificate verification still works as designed: the self-signed certificate
shows its SHA-256 fingerprint on the Connect sheet — compare it once, then
accept the browser's one-tap exception.

MagicDNS caveat: the certificate carries SAN entries for the phone's LAN IPs
plus the fixed DNS name `lenscast.local` only. Your tailnet hostname (e.g.
`phone.your-tailnet.ts.net`) is not in the certificate today, so browsers show
a name-mismatch warning on top of the self-signed warning. Use the tailnet
IP-based URLs, or accept the exception after verifying the fingerprint.
`lenscast.local` does not route across the tailnet, and LensCast does not
advertise the name itself — the certificate merely keeps carrying its SAN, so
a network that maps the name (a router DNS override, Avahi, a hosts entry)
gets a bookmark that also validates. On a plain LAN without such a mapping,
bookmark the phone's IP URL from the Connect sheet instead.

## 2. WireGuard (self-hosted)

Run a WireGuard server inside your LAN (a router, a NAS, or any always-on
box), and route remote viewers through it to the phone's LAN IP. Only the
WireGuard UDP port is exposed to the internet — the phone's HTTP/RTSP ports
never leave the LAN.

Server (`/etc/wireguard/wg0.conf` on a box inside the LAN):

```ini
[Interface]
Address = 10.0.0.1/24
ListenPort = 51820
PrivateKey = <server-private-key>

[Peer]                      # remote viewer
PublicKey = <viewer-public-key>
AllowedIPs = 10.0.0.2/32
```

Viewer device:

```ini
[Interface]
Address = 10.0.0.2/24
PrivateKey = <viewer-private-key>

[Peer]
PublicKey = <server-public-key>
Endpoint = your-home-address.example.org:51820
# include the LAN subnet the phone sits on so PHONE_LAN_IP routes in-tunnel
AllowedIPs = 10.0.0.0/24, 192.168.1.0/24
PersistentKeepalive = 25
```

Then open `http://PHONE_LAN_IP:8080/` as usual. Forward only UDP 51820 on the
router; do not forward 8080 or 8554.

## 3. Port forwarding — NOT recommended

Forwarding the web or RTSP port publishes the phone's server stack — a
NanoHTTPD-based HTTP server plus a custom RTSP implementation — directly to
the open internet. Authentication is optional and off by default, so a default
setup would serve the camera to anyone who finds the port. When auth is
enabled, logins are PBKDF2-hashed and rate-limited, but the HTTP stack itself
was not built to face internet-scale hostile traffic: any future parsing bug
becomes a remotely reachable one.

If you insist despite this:

- **Require auth** — enable stream auth before anything else.
- **Keep HTTPS on** so credentials and streams are not plaintext on the wire.
- Use a strong, unique password.
- Restrict the forwarded port's source IPs at the router if your router
  supports it.

The Tailscale and WireGuard options above achieve the same reachability
without this exposure.

## What does not work today

- There is no built-in cloud relay — LensCast has no hosted service; traffic
  never leaves your network unless you add a tunnel yourself.
- There is no WebRTC support — browsers use the M-JPEG, WebCodecs-over-
  WebSocket, or HLS paths described above.

---

See also [NVR integration](nvr-integration.md) for Home Assistant, VLC/ffmpeg,
Frigate, and webhook recipes.
