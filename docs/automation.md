# Scheduled Automation

LensCast can run the camera on a schedule: time-of-day rules — "on these
days at this minute, run these actions" — that switch camera settings, the
torch, the stream, recording, the siren, and the detection toggles without
anyone touching the device. The classic use case is a mounted security
phone whose camera needs one configuration at night and another by day.

Configure it in the app under **App settings → Automation**: a master
switch plus one card per rule (up to **16 rules**, up to **8 actions**
each), edited in a full-screen editor with a time picker, day chips, and
action editors rendered from the action catalog. Rules stay visible and
editable while the engine is off; a banner explains they will not fire
until it is on. Rules persist in app settings (DataStore) and survive
restarts.

The engine lives in `automation/schedule/` (a pure scheduling policy plus a
thin poll-loop shell), behind the same execution seam the broadcast
receiver drives — see the [Automation section of the
README](../README.md#automation) for the external `action.*` broadcasts,
which are unchanged by this feature.

## The Day / Night recipe

Two rules cover the headline case (times are local wall clock):

| Rule  | Time  | Days      | Actions                                                             |
|-------|-------|-----------|---------------------------------------------------------------------|
| Night | 18:00 | every day | `camera_exposure` ISO 4000 + exposure 175 ms · `torch` on, strength 1 |
| Day   | 07:00 | every day | `camera_exposure` ISO auto (0) + exposure auto (0) · `torch` off    |

Two calibration notes the catalog applies honestly:

- ISO is bounded by the catalog at **0–10000** as a sanity ceiling wide
  enough for the 4000-class night sensors — the device's live ISO range
  (`SENSOR_INFO_SENSITIVITY_RANGE`, read at bind) wins again at apply time.
- `torch` strength 1 requires the torch-strength hardware (see below:
  Android 15+ with a multi-level flash); elsewhere the strength is ignored
  and the torch is plain on/off — which is everything that device can do.

## How scheduling works

- **Time model.** A rule fires at one local-wall-clock minute
  (`minuteOfDay`, 0–1439) on the days set in its day mask — bit 0 = Monday
  … bit 6 = Sunday, `127` = every day, the same convention as the motion
  arm schedule. There is no window that crosses midnight: each local day's
  minute is its own occurrence, so midnight wrap needs no special case.
- **A poll loop, not alarms.** An app-scoped coroutine re-evaluates the
  rules every **30 seconds** (and immediately whenever the rules or the
  master toggle change). No exact-alarm permissions, no boot receiver.
- **Catch-up.** The first evaluation after process start runs immediately —
  that first pass *is* the boot catch-up: rules the device missed while the
  process was dead or the tick was deferred still fire. What fires late
  depends on the action's catch-up class: setting-convergence actions
  (everything except photo capture) are **STATEFUL** and fire no matter how
  late the tick is; the one moment-effect action (`capture_photo`) is
  **ONESHOT** and only fires within a **10-minute grace** of its scheduled
  instant. A rule whose whole action list is filtered out by the grace is
  not journaled, so its photo can still catch up on a later tick within
  grace.
- **The fire journal prevents double-fires.** Each rule's last fired
  occurrence (a per-day minute key: `epochDay * 1440 + minuteOfDay`) is
  recorded in `filesDir/automation/fire_journal.json`, keyed by the rule's
  stable id. A restart — or re-timing or renaming a rule — never replays an
  occurrence that already ran; a rule fires only when its occurrence key is
  strictly newer than the journaled one. A corrupt journal costs at most
  one re-fire, never a lost one.
- **Everything is sanitized.** Before a rule is trusted (on decode, on the
  web PUT, on an in-app save) it is clamped into shape: minute into 0–1439,
  day mask into 1–127, name trimmed, unknown trigger kinds reset to
  `time_of_day` (the only trigger defined today — the field is reserved for
  future kinds), unknown actions dropped, and the caps applied to the
  sanitized list, so a rule always keeps its maximum *executable* actions.
  Invalid parameter values fold to their documented defaults instead of
  firing something unexpected.

## Action catalog

Eleven action types. Absent optional parameters leave the current setting
unchanged; `0` on ISO / exposure time is the auto sentinel. All bounds
below are the persistence/catalog clamps — the device's live ranges win at
apply time.

| Action id        | What it does | Notable params |
|------------------|--------------|----------------|
| `camera_exposure` | Manual ISO and/or exposure time (auto sentinels at 0) | `iso` INT 0–10000 (0 = auto), `exposure_time_ms` FLOAT 0–1000 ms (0 = auto); both optional |
| `camera_ev`      | Exposure compensation index | `index` INT −12…12 |
| `camera_zoom`    | Zoom ratio | `ratio` FLOAT 1–10 |
| `camera_fps`     | Camera frame rate | `fps` INT 1–120 |
| `camera_lens`    | Switches the lens — rebinds the camera, briefly interrupting the preview/stream (flagged in the editor) | `index` INT 0–8, clamped to the device's lens inventory |
| `torch`          | Torch on/off, optionally at a brightness level | `enabled` BOOL (default true); `strength` INT 1–8, optional, API 35+ multi-level flash only |
| `stream`         | Starts/stops streaming | `enabled` BOOL (default true) |
| `capture_photo`  | Captures one photo to the gallery — the only ONESHOT action | — |
| `recording`      | Starts/stops a recording | `enabled` BOOL; `duration_seconds` INT 0–86400, optional (0 = unbounded) |
| `siren`          | Starts/stops the siren | `enabled` BOOL; `duration_seconds` INT 0–3600, optional (absent = run until stopped, `0` = explicit no-limit) |
| `detection`      | Enables/disables motion and/or sound detection | `motion_enabled`, `sound_enabled` BOOL, both optional (absent = unchanged) |

Execution semantics worth knowing:

- Camera-setting actions (`camera_exposure`, `camera_ev`, `camera_zoom`,
  `camera_fps`, `torch`) merge into the **persisted** camera settings and
  the Settings Applier applies them in place — no stream interruption.
- `stream` on is the Quick-Settings-tile start (always starts, restoring
  the web/RTSP outputs the on-device journal last recorded); `stream` off
  is the whole-server stop — the same Stream Toggle every client uses.
- `recording` rides the one Recording Controller (bounded duration when
  given), and `siren` shares the app's single siren auto-stop timer with
  detection deterrence, so a scheduled siren and an auto-siren cannot cut
  each other short.
- One failing rule never blocks the others: each fired rule executes on its
  own, failures are logged, and the occurrence is still journaled so a
  persistently failing action cannot tight-loop every poll tick.

## Torch strength (device requirements)

Multi-level torch brightness rides Android 15's (API 35+)
`CameraManager.turnOnTorchWithStrengthLevel` and the
`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` characteristic
(`camera/model/TorchStrengthPolicy.kt` is the pure no-op ladder):

- **API 35+ required.** Below that — or on a single-level flash (max level
  ≤ 1), or when the device's max level is unknown — the strength parameter
  is ignored and the torch is plain on/off: that already is everything the
  hardware can do.
- Levels are **1–8** at the persistence/catalog bound; the device's real
  maximum always wins at apply time, so a level above what the hardware
  supports clamps down.
- The level is driven only while the torch should be on and re-asserts on
  every settings apply and after every camera rebind, exactly like the
  binary torch. The in-app editor caps its strength slider at the live
  device maximum.

## Web API

```
GET /api/automation
PUT /api/automation        # POST accepted as an alias
```

- `GET` returns the engine state: `{"enabled": …, "rules": […],
  "catalog": […]}`. The `catalog` is the device's action catalog published
  read-only (ids, param names, kinds, bounds, defaults, catch-up class,
  stream-interruption flag), so a client — e.g. a dashboard — can render
  rule editors dynamically instead of hardcoding the eleven actions.
- `PUT` takes `{"enabled": …, "rules": […]}` wholesale (a rule absent from
  the document is deleted, the settings-section convention), sanitizes
  server-side, and answers with a fresh read of the sanitized truth — minted
  rule ids, clamped minutes/masks, canonical params — rather than what was
  sent.
- Auth matches `PUT /api/settings`: any authenticated session may read;
  writes are config writes, so a read-only viewer session is denied and the
  optional API token stays read-only here.

The built-in dashboard ships an **Automation card** on its settings page
(`AutomationCard` / `AutomationRuleEditor` in `web/src/components/`): the
same master switch, rule list, and rule editor as the in-app screen, with
the action editors rendered from the `catalog` the GET response publishes —
pretty labels from a small id map, everything else generic. Viewer sessions
see the card read-only.

## What does not change

- **Manual LED control and deterrence are untouched.** The camera screen's
  torch control and the detection coordinator's auto-siren/auto-torch
  response behave exactly as before; a scheduler `torch` action is separate,
  persisted state (see the LIVE vs persisted distinction in CONTEXT.md).
- **The external broadcast surface is unchanged**: the same
  `com.raulshma.lenscast.action.*` actions, the same extras, and the same
  `com.raulshma.lenscast.permission.AUTOMATION` permission guard. The
  receiver and the scheduler are two front-ends over one shared executor
  (`automation/AutomationActionExecutor.kt`), so a broadcast command and a
  scheduled rule can never drift apart.
