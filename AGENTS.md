# AGENTS.md

Navigation pointers — the content lives in the files below, not here.

- **CONTEXT.md** — the domain glossary; read the `###` section for any module
  before touching its code, and add one `###` section for each new module.
- **Store hydration** — the settings store's shared flows replay a placeholder
  until DataStore's first disk read lands (the default-first race); any
  readiness/hydration gate anchors on the store's `*FromDisk()` twins, never a
  content heuristic. See CONTEXT.md "Store Hydration (the default-first race)".
- **CONTRIBUTING.md** — build/test commands, flavors (store/fdroid), lint gate.
- **docs/** — per-feature references (srt, rtmp, unifiedpush, automation, …).
  Testing on a connected device: **docs/device-testing.md** is the
  drive-and-observe-over-HTTP recipe (launch, auth/CSRF, routes, logcat).
- **scripts/** — runnable verification tools: the web mock + Playwright E2E
  rig (`scripts/web/`) and the H.264 SPS dimension decoder
  (`scripts/device/`); see `scripts/README.md`.
