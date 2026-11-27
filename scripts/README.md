# Verification tools

Runnable checks and rigs used during device and web testing. They are
working tools, not CI gates — CI runs the suites from CONTRIBUTING.md.

## device/ — stream verification on a device round

**`device/sps_dimensions.py`** decodes coded width/height out of an H.264
SPS NAL unit (hex, base64 from an RTSP `sprop-parameter-sets`, or Annex-B
file). Wire-level proof of what the encoder is emitting, next to the
hub's own dimension logcat lines. `--selftest` runs the built-in vectors.
See [docs/device-testing.md](../docs/device-testing.md) for the full
drive-and-observe recipe.

## web/ — beta console without a device

Debugging web-console CSS/layout doesn't need a phone: **`web/mock-api.cjs`**
is a zero-dependency mock of the API surface the UI boots against, and
**`web/web-e2e.cjs`** drives the real app from `vite dev` in headless
Chromium — measuring every inspector switch across a column-width sweep,
screenshotting, and exiting nonzero on any broken geometry.

```bash
cd scripts/web && npm install        # once (playwright-core)

# terminal 1
node scripts/web/mock-api.cjs        # :8099 (never :8080 — that's the device's port)

# terminal 2 — from web/
DEV_API_ORIGIN=http://localhost:8099 npm run dev

# terminal 3
node scripts/web/web-e2e.cjs                      # default width sweep
node scripts/web/web-e2e.cjs --widths=340,380     # or your own
```

For a different regression, keep the boot flow and width sweep in
`web-e2e.cjs` and replace the `measure` check. The exit code tracks
geometry only — console 404s from unmocked routes are expected noise.
The boot surface the mock serves (auth, settings, status, ui/layout,
lenses, SSE events) is what the app needs to reach the Live view with the
Streaming & Links preset; unknown routes 404 loudly, so extend it when the
app grows new boot reads.
