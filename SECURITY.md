# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately — do not open a public issue.

- **Preferred:** open a GitHub Security Advisory at
  <https://github.com/raulshma/lenscast/security/advisories/new>
- **Alternatively:** contact the maintainer ([@raulshma](https://github.com/raulshma))
  through GitHub (a private security advisory, a mention in a private
  context, or an issue asking for a contact channel works).

Include reproduction steps, affected versions, and impact assessment. You
will get an acknowledgement; fixes follow the advisory process (fix,
release, then public disclosure with credit unless you prefer otherwise).

## Threat model

LensCast turns a phone into an **authenticated HTTP/RTSP server on your
LAN**. The assets at stake are the camera feed, the microphone (talkback),
captured media, and the ability to change settings. The expected
deployment is a dedicated or semi-dedicated device on a home network.

What protects those assets:

- **Web dashboard authentication** (optional but recommended): HTTP Basic
  Auth plus session tokens, with a read-only viewer role for family/guest
  access. Failed logins trigger a **brute-force lockout** (repeated
  failures block the client for a window).
- **CSRF protection**: state-changing requests must carry a same-origin
  `Origin`/`Referer` header — cross-origin pages in a victim's browser
  cannot drive the dashboard.
- **API token**: the optional Bearer/`X-Api-Token` credential is
  read-only plus a narrow, explicit **write allow-list** (stream start/
  stop, capture, recording, siren/torch, model downloads); auth and
  session-management routes are never token-writable.
- **RTSP authentication**: RTSP digest auth (credentials are not sent in
  plaintext over the RTSP connection).
- **TLS mode**: an optional HTTPS mode with a **self-signed on-device
  certificate**; the fingerprint is displayed in the app for one-tap
  out-of-band verification. It encrypts the web dashboard and its streams.

## Known risk envelope

Honest inventory of the things that are not as strong as the rest — this
is a small-maintainer project and you should know where the seams are:

- **The HTTP transport library (NanoHTTPD 2.3.1) is unmaintained.** Its
  original upstream is dead; the ecosystem has moved to a community fork,
  which this project has not migrated to yet. Mitigations in place: all
  request-handling logic is decomposed into pure responder functions that
  are **fuzz-tested** (`*FuzzTest.kt`), responses carry a **strict
  Content-Security-Policy**, request **bodies are size-capped**, and
  sockets have **request/read timeouts**. Residual risk: an unpatched
  parser bug in the transport layer itself. Exposure is LAN-scoped unless
  you have port-forwarded (which [docs/remote-access.md](docs/remote-access.md)
  actively discourages — prefer Tailscale/WireGuard).
- **ONVIF device service is unauthenticated by design.** It exposes LAN
  device metadata and stream URIs only (no camera control), is off by
  default, and RTSP keeps its own auth. Treat it as information exposure
  on the LAN, not as a control surface.
- **RTSP itself is plaintext unless you use HTTPS mode.** The RTSP
  *authentication* is digest (not cleartext), but the stream media and
  SDP are not encrypted on the RTSP port. The web dashboard's HTTPS mode
  encrypts its own paths; if you need RTSP confidentiality, tunnel it
  (WireGuard/Tailscale) until an RTSPS story lands.
- **Self-signed TLS**: the HTTPS mode's certificate is generated on
  device. You must verify the fingerprint shown in the app against the
  browser warning; there is no Web-of-trust or CA backing.

## Scope

- The Android app (`app/`), the Wear companion (`wear/`), the web UI
  (`web/`), the CI/release pipeline, and the F-Droid recipe.
- Out of scope: vulnerabilities in the Android OS itself, in your VPN
  vendor, or in the networks you choose to expose the device on.
