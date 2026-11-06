# Automation: If/Then Rules

LensCast runs the camera on if/then rules: **WHEN** something happens (a
scheduled minute, a detection event, a power or streaming edge), **ONLY
WHEN** optional preconditions hold (charging, Wi-Fi, a time window, …,
combinable ALL/ANY), **THEN** run actions — camera settings, the torch, the
stream, recording, the siren, the detection toggles — without anyone
touching the device. The classic use case is a mounted security phone whose
camera needs one configuration at night and another by day, and whose siren
should fire on motion — but only at night, and only while streaming.

Configure it in the app under **App settings → Automation**: a master
switch plus one card per rule (up to **16 rules**, up to **8 actions** and
**8 conditions** each), edited in a full-screen editor with a trigger
picker, the time picker and day chips for time-of-day rules, per-trigger
param editors, the condition list with an ALL/ANY toggle, the cooldown
slider, and action editors rendered from the catalogs. Rules stay visible
and editable while the engine is off; a banner explains they will not fire
until it is on. Rules persist in app settings (DataStore) and survive
restarts.

The engine lives in `automation/schedule/` (pure policies plus a thin
scheduler shell), behind the same execution seam the broadcast receiver
drives — see the [Automation section of the
README](../README.md#automation) for the external `action.*` broadcasts,
which are unchanged by this feature.

## Recipes

The headline Day / Night pair (times are local wall clock):

| Rule  | Trigger | Only when | Actions                                                                |
|-------|---------|-----------|------------------------------------------------------------------------|
| Night | 18:00 daily | — | `camera_exposure` ISO 4000 + exposure 175 ms · `torch` on, strength 1 |
| Day   | 07:00 daily | — | `camera_exposure` ISO auto (0) + exposure auto (0) · `torch` off      |

And an if/then rule the old time-only engine could not express:

| Rule | Trigger | Only when | Actions |
|------|---------|-----------|---------|
| Night deterrent | `motion_detected` | time window 22:00–06:00 AND streaming active (ALL) | `siren` 10 s · `capture_photo` (re-fire cooldown 120 s) |

Two calibration notes the catalog applies honestly:

- ISO is bounded by the catalog at **0–10000** as a sanity ceiling wide
  enough for the 4000-class night sensors — the device's live ISO range
  (`SENSOR_INFO_SENSITIVITY_RANGE`, read at bind) wins again at apply time.
- `torch` strength 1 requires the torch-strength hardware (see below:
  Android 15+ with a multi-level flash); elsewhere the strength is ignored
  and the torch is plain on/off — which is everything that device can do.

## Rule anatomy

A rule is one JSON document (same shape on DataStore, the web API, and the
fixtures): `id`, `name`, `enabled`, then the WHEN/ONLY WHEN/THEN fields —

- **Trigger** — `triggerType` plus `triggerParams`. `time_of_day` (the
  default, so old payloads decode unchanged) keeps its top-level
  `minuteOfDay` (0–1439) and `daysMask` (bit 0 = Monday … bit 6 = Sunday,
  127 = every day — the motion arm schedule's convention); every other
  trigger carries its params in `triggerParams` (a charger-connect rule is
  `triggerType: "charging_changed", triggerParams: {"charging": "true"}`).
- **Conditions** — `conditions: [{type, params}]` plus `conditionMode`
  (`"all"` = every condition must hold, the default; `"any"` = one
  suffices). An empty list means unconditional. Unknown/unmet conditions
  never fire the rule.
- **Cooldown** — `cooldownSeconds` (0–86400, default 60) for event
  triggers: the persisted minimum time between two firings of this rule.
- **Missed-run policy** — `missedRunPolicy` for `time_of_day` triggers:
  what happens when the scheduled moment passes without a firing (the
  device was off, or the ONLY WHEN conditions were only met later).
  `"catch_up"` (the default — old payloads decode unchanged) keeps the
  historical behavior; `"skip"` means *at the scheduled time or not at
  all*; `"window"` bounds the catch-up to `missedWindowMinutes` (1–1440,
  default 30) past the scheduled minute. Unknown policies fold to
  `"catch_up"`. Meaningless for event triggers — they fire in the present.
- **Actions** — up to 8 catalog actions, executed in order.

Everything is sanitized before a rule is trusted (on DataStore decode, on
the web PUT, on an in-app save): minute into 0–1439, mask into 1–127,
unknown trigger kinds reset to `time_of_day`, trigger/condition params
folded to their documented defaults when unknown, unknown conditions and
actions dropped, the caps applied to the sanitized lists (a rule always
keeps its maximum *executable* entries), the cooldown clamped, the
missed-run policy folded and its window clamped. Invalid values degrade
to the documented behavior instead of firing something unexpected.

## How the engine works

One scheduler (`AutomationScheduler`) drives both rule families. A
**30-second poll loop** re-evaluates time-of-day rules (and immediately
whenever rules or the master toggle change); **live events** — detections,
power edges, streaming lifecycle — arrive through `AutomationEventWiring`
and are pushed in via `onEvent`. Time ticks and event fires share a mutex,
so two rules' actions never interleave. Actions always run through the one
Automation Action Executor the broadcast receiver uses. What keeps the CPU
awake underneath all of that — locked screens, overnight streams — is the
[keep-awake hold](#when-the-device-is-locked).

### Time-of-day rules (WHEN = scheduled minute)

- The classic model, unchanged: one local-wall-clock minute per armed day,
  no window crossing midnight — each local day's minute is its own
  occurrence. An app-scoped coroutine re-evaluates every 30 s; no
  exact-alarm permissions, no boot receiver.
- **Catch-up.** The first evaluation after process start runs immediately —
  that first pass *is* the boot catch-up. What fires late depends on the
  rule's **missed-run policy** (`missedRunPolicy`). In the default
  `"catch_up"` mode it depends on the action's catch-up class:
  setting-convergence actions (everything except photo capture) are
  **STATEFUL** and fire no matter how late the tick is; the one
  moment-effect action (`capture_photo`) is **ONESHOT** and only fires
  within a **10-minute grace** of its scheduled instant. In `"window"`
  mode the rule's own window is the authority — it fires only within
  `missedWindowMinutes` of the scheduled minute and every action rides
  inside it (a rule may *widen* a one-shot's grace, never exceed the
  window). `"skip"` fires only in the scheduled minute itself. Lateness is
  measured in wall-clock minute space (the same units as the occurrence
  keys), never against a real epoch clock, so a zone offset can't smear a
  window. A rule whose whole action list is filtered out is not journaled,
  so its one-shot can still catch up on a later tick within grace.
- **Conditions gate the tick, not the schedule.** A time rule whose
  conditions are unmet stays *due* (unjournaled) and fires on a later tick
  the same day once they become met — the occurrence key only stamps when
  it actually fires. ONESHOT grace still ages from the scheduled minute —
  and the deferred fire flows through the same minutes-late computation as
  a late boot, so the rule's missed-run policy governs both: with
  `"skip"` a rule whose conditions are met past the scheduled minute never
  fires that day ("at the scheduled time or not at all"); with `"window"`
  the deferred fire must still land inside the window.
- **The fire journal prevents double-fires.** Each rule's last fired
  occurrence is recorded in `filesDir/automation/fire_journal.json`, keyed
  by the rule's stable id. A restart — or re-timing or renaming a rule —
  never replays an occurrence that already ran. A corrupt journal costs at
  most one re-fire, never a lost one.

### Event rules (WHEN = something happened)

- **The wiring** (`automation/AutomationEventWiring.kt`, started from
  `MainApplication` with the scheduler) subscribes the app's existing
  streams and maps them onto a small sealed event set: detection events
  from the event store (motion / sound / tamper), charger
  connect/disconnect edges and battery-percentage changes (from
  PowerManager), streaming start/stop edges, and the viewer 0↔≥1 edge
  (from StreamingManager). StateFlow seeds are read once at start so the
  current state never replays as a spurious edge.
- **Evaluation.** When an event arrives, the scheduler takes a fresh
  **sensor snapshot** (`AutomationSensorSnapshot`: clock, charging,
  battery level, power save, Wi-Fi, streaming, viewer count, torch,
  recording, thermal, motion-detection toggle) and a rule fires when its
  trigger matches the event, its conditions hold on *that* snapshot, and
  its cooldown has elapsed. All actions run in the present — there is no
  catch-up filter and no grace: the event *is* now.
- **Cooldown.** The journal stores the wall-clock fire time (epoch ms) for
  event rules; a rule re-fires only after `cooldownSeconds` have passed.
  This survives restarts like every journal value.
- **One journal, two value spaces.** Time fires store per-day occurrence
  keys (`epochDay * 1440 + minuteOfDay`, values under ~3×10⁷); event fires
  store epoch-ms timestamps (values ≥ 10⁹). The magnitude split
  (`EVENT_JOURNAL_THRESHOLD`) means switching a rule's trigger kind never
  wedges it — an old occurrence key can never suppress an event fire and
  vice versa.
- **`app_started`** fires once per process, after the first rules load —
  guarded by a one-shot flag, so editing rules (which restarts the
  scheduler's inner collector) never re-fires it. Conditions still gate it.

## When the device is locked

The engine is a 30-second poll loop plus in-process event streams — no
exact-alarm permissions, no boot receiver — so "does it work while locked?"
is really a power question: it runs exactly as long as the CPU is awake.
Two holds answer that, both in `core/PowerManager.kt`:

- **The keep-awake hold (on by default).** While the app process runs, a
  partial wakelock (`LensCast::KeepAwake`, held by the Keep Awake
  controller for the process's whole life) keeps the CPU awake with the
  screen locked and dark: time rules fire on their minute, event rules fire
  in the present, and a headless stream pumps frames. The toggle lives in
  **App settings → Background** (and on the web dashboard; `keepAwakeEnabled`
  on the wire). On is the default — the mounted security phone is the app's
  purpose, quitting is the explicit act, and the battery cost is what you
  opt out of. Quitting (the camera screen's Quit, force stop) releases the
  hold with the process; from then until the process starts again, the
  missed-run policy above is the catch-up contract. One OS caveat: deep
  doze — unplugged, stationary, idle — can suspend the CPU even under a
  held wakelock, so the battery-optimization exemption (the row just above
  the toggle in the same settings section) completes the setup for a phone
  that might sit unplugged.
- **The streaming hold, renewed.** A live session takes its own partial
  wakelock and its 30-second battery poll re-arms it well before the
  60-minute acquire budget lapses (`renewWakeLock`), so a locked device
  streaming overnight never loses the CPU mid-stream — and never silently
  stops automating, since the engine shares that awake CPU. A lock that was
  lost (expired timeout racing the poll, an external release) is
  re-acquired on the next tick.

## Trigger catalog

Nine trigger ids (AutomationTriggerCatalog; published read-only by GET
`/api/automation` as `triggers`). `time_of_day` is special-cased: its
"params" are the rule's top-level `minuteOfDay`/`daysMask`.

| Trigger id | Fires when | Params |
|------------|------------|--------|
| `time_of_day` | The armed local minute begins (the 30 s poll may fire it up to 30 s late) | `minuteOfDay`, `daysMask` (top-level fields) |
| `motion_detected` | A motion detection event | — |
| `sound_detected` | A sound detection event | — |
| `tamper_detected` | A tamper (power-cut) event | — |
| `charging_changed` | The charger connect/disconnect edge | `charging` BOOL (default true = on connect; false = on disconnect) |
| `battery_level` | The battery percentage **crosses** a threshold | `level` INT 1–99 (default 20), `when_below` BOOL (default true = crossing downward; false = crossing upward) |
| `streaming_changed` | Streaming starts/stops | `streaming` BOOL (default true = on start) |
| `viewers_changed` | The viewer count crosses 0↔≥1 | `connected` BOOL (default true = first viewer joins; false = last viewer leaves) |
| `app_started` | The process starts (once per process, after rules load) | — |

Battery crossing is edge-detected, not polled: it fires when the previous
level was above `level` and the current is at or below it (`when_below`
true), or the mirror image — not on every one-percent wiggle at the
boundary. The very first level seen after process start (unknown previous)
never fires.

## Condition catalog

Eleven condition ids (AutomationConditionCatalog; published as
`conditions`). Conditions are evaluated against a fresh snapshot at fire
time — for time rules, on each tick; for event rules, at the event. An
unknown condition id is simply never met.

| Condition id | Holds when | Params |
|--------------|------------|--------|
| `time_window` | The snapshot minute is inside the window, on an armed day | `start_minute` INT 0–1439 (default 0), `end_minute` INT 0–1439 (default 1439), `days_mask` INT 1–127 (default 127). A window with start > end wraps across midnight (22:00→06:00); start == end covers nothing |
| `charging` | Charger state matches | `charging` BOOL (default true) |
| `battery_level` | The battery percentage is inside an optional inclusive range | `min_level`/`max_level` INT 0–100, optional; unknown battery level → unmet |
| `power_save` | Power-save mode matches | `enabled` BOOL (default false) |
| `wifi_connected` | Wi-Fi connectivity matches | `connected` BOOL (default true) |
| `streaming` | Streaming state matches | `active` BOOL (default true) |
| `viewers` | The viewer count is inside an optional inclusive range | `min_count`/`max_count` INT 0–99, optional |
| `torch` | Torch state matches | `on` BOOL (default true) |
| `recording` | Recording state matches | `active` BOOL (default true) |
| `thermal` | Thermal state matches (normal vs throttling) | `normal` BOOL (default true) |
| `motion_detection` | The motion-detection toggle matches | `enabled` BOOL (default true) |

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
  "catalog": […], "triggers": […], "conditions": […], "limits": {…}}`.
  The rule shape in `rules` IS the app's one `AutomationRule` declaration
  (the same class the app persists — there is no separate wire clone);
  each rule additionally carries the read-only `lastFiredAt` fire stamp
  decoded from the device's fire journal (null/absent = never fired). The
  three catalogs are the device's truth published read-only — actions (ids,
  param kinds, bounds, defaults, catch-up class, stream-interruption flag),
  triggers, and conditions (ids + param schemas) — and each catalog entry
  carries its picker `group` (a semantic id, e.g. `camera` or `schedule`)
  and its `icon` kind (a small closed vocabulary: the app maps kind →
  drawable, the web maps kind → glyph), so every client groups and badges
  the pickers identically from the one source. The `limits` block publishes
  the editor contract's bounds, defaults and preset chips from
  StreamDefaults (`maxRules`, `maxActionsPerRule`, `maxConditionsPerRule`,
  the cooldown and missed-window clamps and defaults, and the two preset
  lists) — read-only, like the catalogs.
- `PUT` takes `{"enabled": …, "rules": […]}` wholesale (a rule absent from
  the document is deleted, the settings-section convention), sanitizes
  server-side, and answers with a fresh read of the sanitized truth — minted
  rule ids (a blank `id` is the new-rule shape; every persisted rule carries
  a minted, non-blank id, so GET responses always emit one), clamped
  minutes/masks, folded missed-run policies with clamped windows, canonical
  params — rather than what was sent. A client echoing a rule's
  `lastFiredAt` back in a PUT body has it discarded: the fire stamp is a
  GET decoration, never persisted.
- Auth matches `PUT /api/settings`: any authenticated session may read;
  writes are config writes, so a read-only viewer session is denied and the
  optional API token stays read-only here.

The built-in dashboard ships an **Automation card** on its settings page
(`AutomationCard` / `AutomationRuleEditor` in `web/src/components/`): the
same master switch, rule list, and rule editor as the in-app screen, with
trigger/condition/action editors rendered from the catalogs the GET
response publishes — the picker groups and icons come from the entries'
published `group`/`icon`, the cap counters, cooldown/window bounds and
preset chips from the `limits` block (pre-fetch fallbacks are pinned to the
checked-in contract fixture by `web/src/contract.test.ts`), everything else
generic. Viewer sessions see the card read-only.

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
  rule action can never drift apart.
