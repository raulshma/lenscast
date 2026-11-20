# Glossary

Domain vocabulary for LensCast. Most terms below crystallized during the
2026-11-19 architecture review and were then implemented in the 2026-11-20
reliability wave — each term names a real module or invariant in the code now.

- **Live fact** — a runtime fact measured at its source of truth (e.g. whether
  the torch LED is lit *right now*, `CameraService.torchState`), as opposed to
  the **stored intent** in settings. Commits e84b36d / 565a690 / 22fe040
  established "live LED fact" as the arbiter when the two disagree.

- **Disk-truth merge base** — reading the persisted settings document straight
  from disk (never a possibly-lagging in-memory flow `.value`) as the base a
  writer merges into or a decision reads. Introduced for automation actions in
  368515b; the settings store exposes it as `SettingsDataStore.disk(pref)` and
  the RTSP auth spec assembles from one untorn `readFresh` snapshot
  (`RtspAuthSpecAssembly`).

- **Section document** — one settings section (camera, streaming) treated as a
  single atomic document: one merge over a fresh disk read, one write. The
  streaming PUT persists via `SettingsDataStore.saveEntries` (one DataStore
  `edit {}`), so a crash mid-PUT leaves the previous whole document and a
  concurrent disk-truth reader sees old-or-new, never half.

- **Stream facts** — the small read-only set of live observations about the
  streaming server and camera that the reliability runtime consumes
  (`core/StreamFacts.kt`: server/RTSP running, frame counters, client count,
  camera state, RTSP health, live-streaming). Production adapter:
  `StreamingManager`; JVM fake: `StreamWatchdogTest`.

- **Recovery levers** — the actions the reliability runtime may pull when a
  verdict escalates: `recover(tier)`, soft restart, relaunch, plus the facts it
  needs (`isServerRunning`, `userStopped`). The *choice* of levers stays at the
  composition root (`MainApplication`); the *choreography* belongs to the
  reliability modules.

- **Reliability runtime** — the concept spanning "alarm fired" → "verdict
  executed": `HeartbeatController` + `StreamWatchdog` + the arming bridges.
  The Android side reaches it through the **reliability relay**
  (`core/ReliabilityRelay.kt`) — a 4-member interface `MainApplication`
  implements, so `StreamingService` casts once to a narrow seam instead of five
  times to the whole `AppGraph`.

- **Grace window** — the delay between "server stopped" and acting on the stop,
  because a restart bounces `isServerRunning` false→true inside one
  choreography. The **fire-time re-check** (re-read the fact when the delay
  elapses) is part of the definition; a grace window without it is the #14
  failure shape. One implementation: `core/GraceWindowedArming`, three
  consumers (heartbeat alarm, liveness watch, foreground service).

- **Miss counting** — the increment-then-decide convention (a miss count
  includes the current event: incremented before the policy is asked). Owned
  structurally by `core/MissCounter`; the policies receive the advanced count.

- **Bind choreography** — the camera bind decisions (observation identity
  guards, open-state transitions, post-bind controls rungs, the recording
  bind ladder) as pure data in `camera/model/BindChoreography.kt`;
  `CameraService` executes the returned plans. Commit 5ad333c is the recorded
  bug this extraction targets.

- **Camera bracket / lease** — the acquire → begin-exclusive → bind →
  teardown-in-order protocol a camera consumer runs around camera use. The
  bracket remains caller-owned prose (defended by `RecordingControllerTest`);
  a **lease** handle making it structural was reviewed and deliberately
  DEFERRED by the 2026-11-19 architecture review — revisit only when a new
  camera consumer lands.

- **Resume source** — boot vs relaunch, the only varying input to
  `streaming/ResumeController.resume()`, which owns the read → land-on-gates →
  execute ordering for both (the "land before execute" invariant carries the
  shipped soft-restart re-arm bug as history).

- **Catalog-carried action** — an automation action's merge/restore/dispatch
  behavior declared on its `AutomationActionCatalog` entry, so a new action is
  one catalog entry + locale strings, not a wave of `when` ladders. Web id
  lists derive from the contract fixture (device truth).
