# LensCast Wear

A Wear OS remote for LensCast: stream toggle, photo capture, live snapshot,
detection alerts, and a watch-face tile.

- **Start/Stop Stream** — a single toggle that mirrors the phone's live
  streaming state (green "Start Stream" / red "Stop Stream", driven by the
  periodic `/api/status` poll, never an optimistic guess).
- **Photo capture** — fires `POST /api/capture` through the phone's capture
  pipeline, with a short "Captured" confirmation animation.
- **Live snapshot pane** — a periodically refreshed `GET /snapshot` JPEG with
  its last-frame timestamp and a one-second age ticker; the pane's Refresh
  button forces an immediate fetch and doubles as the error retry.
- **Detection alerts** — an opt-in (default ON) watch-side mirror of the
  phone's detection events: a dismissible banner card in the remote, a
  heads-up notification, and a haptic pulse per new event (see below).
- **Watch-face tile** — the phone's stream-state line (web / RTSP) plus a
  "Tap to open" chip into the remote, refreshed by push on state change and
  a 30-minute fallback (see below).

Plus a **settings screen** (host, port, auth mode, credentials, the alerts
toggle) with a **Test** button that verifies the connection and surfaces the
phone's identity (`<device model> · LensCast <app version>`).

## Architecture

```
wear/src/main/java/com/raulshma/lenscast/wear/
├── MainActivity.kt         Host activity: controller graph + lifecycle-poll binding
├── WearRemoteScreen.kt     The remote UI (Compose for Wear OS, ScalingLazyColumn)
│                           incl. the dismissible alert banner card
├── SettingsActivity.kt     Connection settings + live connection test + alerts
│                           opt-in (lazily requests POST_NOTIFICATIONS on 33+)
├── WearRemoteController.kt Single state holder: status/snapshot/events poll loops,
│                           stream/photo actions, Idle/Loading/Success/Error per surface
├── WearApiClient.kt        OkHttp: /api/status, /api/system, /api/detection/events,
│                           /api/stream/*, /api/capture, /snapshot — all suspend + cancellable
├── WearSettingsStore.kt    Watch-side DataStore persistence (host, port, auth, alerts)
├── WearAlertPolicy.kt      Pure alert core: new-event verdict (set-based dedup),
│                           banner detail line, relative-age buckets (the JVM-tested seam)
├── WearAlertStateStore.kt  SharedPreferences persistence of the seen-state
│                           (newest timestamp + bounded seen-id ring), survives screen-off
├── WearDetectionAlerter.kt Side effects per new event: haptic double-pulse +
│                           heads-up notification (permission-gated on API 33+)
├── WearTileModels.kt       Pure tile mapper: cached status → tile view state
│                           (the JVM-tested half of the tile)
├── WearTileService.kt      Thin TileService: view state → tiles-material layout,
│                           in-process status cache, "Tap to open" LaunchAction
├── WearRequestUrls.kt      Pure URL builder (the JVM-tested seam)
└── WearModels.kt           Settings/status/event value types + RequestState ladder
```

**Deliberately NOT used: the Wear Data Layer / phone-app pairing handshake.**
The watch app talks HTTP directly to the LensCast server over the phone's
LAN. Justification:

- **Zero coupling.** No `WearableListenerService`, no capability matching,
  no phone-side counterpart code — `:app` needs no changes at all.
- **Works across APK signatures.** The Data Layer requires the watch and
  phone apps to share signing keys; plain HTTP does not. Sideloaded, Play,
  and F-Droid builds of the phone app are all equally reachable.
- **F-Droid-friendly.** No Google Play Services dependency, no proprietary
  transport. The module is pure AOSP + AndroidX.

The same envelope holds for alerts: they ride the **same resumed-only poll
loop** as the remote — no background service, no Wear Data Layer listener.
Detection alerts exist while the remote is on screen (banner + haptic +
notification), not around the clock.

## HTTP endpoints consumed

All against `http://<phone-lan-ip>:<port>` (default port 8080, LensCast's
`StreamingServer`):

| Purpose             | Method + path                   | Auth-presented as                |
|---------------------|---------------------------------|----------------------------------|
| Status poll (5s)    | `GET /api/status`               | token header / Basic credentials |
| Diagnostics (test)  | `GET /api/system`               | same                             |
| Detection feed (5s) | `GET /api/detection/events?limit=5` | same                         |
| Snapshot pane       | `GET /snapshot`                 | same                             |
| Stream on           | `POST /api/stream/start`        | same                             |
| Stream off          | `POST /api/stream/stop`         | same                             |
| Photo capture       | `POST /api/capture`             | same                             |

Response handling follows the server's JSON contract: handlers answer HTTP
200 with the outcome in the payload (`success` / `error`), so both the code
and the body are surfaced; snapshot bytes are decoded with `BitmapFactory`.
The detection feed decodes only the fields the watch surfaces (`id`,
`timestampMs`, `type`, `zones`, `labels`) — the base64 snapshots stay on the
wire, and the banner shows the live `/snapshot` frame as its thumbnail.

**Auth modes** (chosen in settings):

- **API Token** (default) — sends the `X-Api-Token` header. This is the
  fully-supported programmatic path against the current server: the Web
  Auth Gate accepts `X-Api-Token` / `Authorization: Bearer` on every
  protected route, and the write routes above are on the server's
  `TokenWritePolicy` POST allow-list.
- **Basic** — sends RFC 7617 `Authorization: Basic base64(user:pass)`. The
  current server build does **not** validate Basic credentials on its
  protected routes (its ladder is API token or session cookie); this mode
  exists for deployments that front LensCast with a Basic-auth-capable
  proxy, and for forward compatibility. It is offered as-is — no
  client-side pretending otherwise.

## Detection alerts

Opt-in in the settings screen (default ON, one tap to flip; the flip to ON
lazily asks for the API 33+ notification permission, mirroring the phone
app's ask-when-used posture — without the grant the notification post is a
silent no-op, while the banner and haptic still work).

While the remote is resumed, the controller tails
`GET /api/detection/events?limit=5` on the same 5-second cadence as the
status poll (staggered half a lap so the two requests never collide on the
radio). New-event detection is the pure `WearAlertPolicy`:

- **Set-based dedup, not an id counter.** The phone mints event ids as
  random UUID strings, so "greater than last seen" is meaningless on the
  wire. The watch keeps a bounded ring (10 entries, 2x the feed window) of
  recently seen ids plus the newest event timestamp as a floor.
- **First contact baselines silently** — a fresh install must not buzz for
  the phone's event history.
- **Freshness gate** — an unseen event older than 10 minutes (the watch was
  away) updates the ring silently; the banner is for events that still
  matter now.
- The seen-state persists in `SharedPreferences`, so screen-off / process
  death never re-alerts the same event.

Per new event: a haptic double-pulse, a heads-up notification (one slot —
the newest replaces instead of stacking a burst; tapping opens the remote),
and the in-app banner card: event title (motion / sound / tamper), the
ML-label/zone detail line, the snapshot pane's last frame as thumbnail, a
one-second age ticker, and Dismiss. Dismissing clears only the card.

## Watch-face tile

`WearTileService` renders one line of truth: the phone's stream state read
from the cached last status — "Web + RTSP live" / "Web stream live" /
"RTSP stream live" / "Idle" / "Phone status unknown" — plus a "Tap to open"
chip whose `LaunchAction` opens the remote. Content building is the pure
`WearTileModels` mapper (cached status → view state), JVM-tested without
Robolectric; the service is only the tiles-material translation.

Refresh is two-lane: the resumed status poll pushes through an in-process
cache and requests an immediate re-render via
`TileService.getUpdater(context).requestUpdate(...)` whenever the
stream-state line actually changes; a 30-minute `freshnessIntervalMillis`
is the periodic fallback that also covers "app never resumed". After
process death the cache is empty and the tile honestly reads "Phone status
unknown" rather than a stale guess.

## WiFi limitation (read this)

The watch reaches the phone **only when both are on the same network** —
in practice the watch on WiFi and the phone on the same WiFi/LAN subnet.
Bluetooth-tethered routing is unreliable and is explicitly NOT a target:
BLE wear transport bandwidth is far below a JPEG snapshot stream, and
tethered watch traffic often bypasses the phone's LAN stack entirely.

If the watch is on LTE or a different SSID/VLAN, requests fail with the
"Unreachable" status header state. This is the documented operating
envelope: LensCast Wear is a same-LAN companion.

## How the integrator wires it in

The module rides the **shared version catalog** (`gradle/libs.versions.toml`)
exclusively — AGP, the Kotlin compose plugin, the Compose BOM, OkHttp, the
wear-compose line, and the tiles stack all resolve through `libs.*`
references; no version literal appears in `wear/build.gradle.kts`, so a
root pin bump lands here with it (the wear-only entries are `wear`,
`wearCompose`, `wearTiles` in the catalog).

The complete integration is one line in `settings.gradle.kts`:

```kotlin
rootProject.name = "LensCast"
include(":app")
include(":wear")          // ← add this
```

Nothing else changes:

- **No :app changes.** The watch app is an independent APK
  (`applicationId com.raulshma.lenscast.wear`) installed on the watch, not
  a phone-APK embed. Build it with `:wear:assembleDebug` /
  `:wear:assembleRelease` and side-load via `adb install` onto the watch.
- **Signing:** the release build type intentionally declares no signing
  config (debug-signed by default); add a CI signing config if the watch
  APK ships through a store.
- **Unit tests:** `./gradlew :wear:testDebugUnitTest` runs the pure pins —
  the URL builder, the alert policy (dedup / floor / freshness / age
  ladder), and the tile view-state mapper (JUnit 4.13.2 via the catalog).

### Dependency pins (all via the version catalog)

| Artifact | Catalog ref | Version |
|---|---|---|
| `com.android.application` (plugin) | `libs.plugins.android.application` | = root AGP |
| `org.jetbrains.kotlin.plugin.compose` (plugin) | `libs.plugins.kotlin.compose` | = root Kotlin |
| `androidx.compose:compose-bom` (platform) | `libs.compose.bom` | = root BOM |
| `androidx.wear:wear` | `libs.wear` | 1.3.0 |
| `androidx.wear.compose:compose-foundation` | `libs.wear.compose.foundation` | 1.4.0 (stable) |
| `androidx.wear.compose:compose-material` | `libs.wear.compose.material` | 1.4.0 (stable) |
| `androidx.compose.ui:ui`, `androidx.compose.foundation:foundation`, `ui-tooling(-preview)` | `libs.compose.*` | via BOM |
| `androidx.activity:activity-compose` | `libs.androidx.activity.compose` | = root |
| `androidx.datastore:datastore-preferences` | `libs.datastore.preferences` | = root |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `libs.coroutines.android` | = root |
| `com.squareup.okhttp3:okhttp` | `libs.okhttp` | 4.12.0 |
| `androidx.wear.tiles:tiles` + `tiles-material` | `libs.wear.tiles(.material)` | 1.5.0 |
| `junit:junit` (testImplementation) | `libs.junit` | = root |

**Wear Compose artifact choice:** the stable `androidx.wear.compose`
Material-line artifacts (`compose-foundation` + `compose-material`) at
**1.4.0** — verified present on Google Maven (the line continues through
1.4.1 / 1.5.x / 1.6.x stable), so no alpha and no fallback to 1.3.1 was
needed. `compose-material3` for Wear (the M3-flavored line) was avoided
because stable-quality pins sit on the alpha channel; `compose-material`
1.4.0 is the conservative stable Wear Compose UI. Two consequences of
staying on the stable line, both handled: text fields use foundation's
`BasicTextField` (the 1.4.0 material artifact ships no `TextField`), and
`ScalingLazyColumn` comes from `androidx.wear.compose.foundation.lazy`
(the non-deprecated home in 1.4.0).

**Tiles stack notes:** tiles 1.5.0's `TileService` binds through the
`androidx.wear.tiles.action.BIND_TILE_PROVIDER` action (the manifest entry
carries the system-held `com.google.android.wearable.permission.BIND_TILE_PROVIDER`
permission), and the stack pulls only the `ListenableFuture` interface —
not Guava — so the service returns a ten-line `ImmediateFuture` instead of
adding a dependency to the watch APK. Tile clicks use the tiles-idiomatic
`LaunchAction` (tiles are rendered remotely; a literal `PendingIntent` is
not the mechanism) — it opens the main activity exactly like one would.

## F-Droid

Excluded from F-Droid metadata initially: the existing fdroid recipe is
unchanged and builds `:app` only. The module is already shaped for a later
recipe addition — `dependenciesInfo` disabled (mirrors `:app`), no
proprietary dependencies, no Play-services artifacts — with `INTERNET` +
`POST_NOTIFICATIONS` as the only permissions.

## Permissions

- `android.permission.INTERNET` — every control in the remote is an HTTP
  round trip to the paired phone's LAN server.
- `android.permission.POST_NOTIFICATIONS` — the detection-alert
  notification surface; runtime-requested lazily, only when the user
  enables alerts on an API 33+ device. The banner and haptic work without
  it.

No Wear Data Layer, no background services, no sensors; polls run only
while the app is resumed (bound to the activity lifecycle), so the watch
keeps its battery when the remote is not on screen. The tile is rendered
by the system on demand and never keeps the app alive.
