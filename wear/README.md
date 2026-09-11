# LensCast Wear

A minimal Wear OS remote for LensCast: one watch app, three controls.

- **Start/Stop Stream** — a single toggle that mirrors the phone's live
  streaming state (green "Start Stream" / red "Stop Stream", driven by the
  periodic `/api/status` poll, never an optimistic guess).
- **Photo capture** — fires `POST /api/capture` through the phone's capture
  pipeline, with a short "Captured" confirmation animation.
- **Live snapshot pane** — a periodically refreshed `GET /snapshot` JPEG with
  its last-frame timestamp and a one-second age ticker; the pane's Refresh
  button forces an immediate fetch and doubles as the error retry.

Plus a **settings screen** (host, port, auth mode, credentials) with a
**Test** button that verifies the connection and surfaces the phone's
identity (`<device model> · LensCast <app version>`).

## Architecture

```
wear/src/main/java/com/raulshma/lenscast/wear/
├── MainActivity.kt        Host activity: controller graph + lifecycle-poll binding
├── WearRemoteScreen.kt    The remote UI (Compose for Wear OS, ScalingLazyColumn)
├── SettingsActivity.kt    Connection settings + live connection test
├── WearRemoteController.kt Single state holder: status/snapshot poll loops,
│                          stream/photo actions, Idle/Loading/Success/Error per surface
├── WearApiClient.kt       OkHttp: /api/status, /api/system, /api/stream/*,
│                          /api/capture, /snapshot — all suspend + cancellable
├── WearSettingsStore.kt   Watch-side DataStore persistence (host, port, auth)
├── WearRequestUrls.kt     Pure URL builder (the JVM-tested seam)
└── WearModels.kt          Settings value type + RequestState sealed ladder
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

## HTTP endpoints consumed

All against `http://<phone-lan-ip>:<port>` (default port 8080, LensCast's
`StreamingServer`):

| Purpose           | Method + path         | Auth-presented as                  |
|-------------------|-----------------------|------------------------------------|
| Status poll (5s)  | `GET /api/status`     | token header / Basic credentials   |
| Diagnostics (test)| `GET /api/system`     | same                               |
| Snapshot pane     | `GET /snapshot`       | same                               |
| Stream on         | `POST /api/stream/start` | same                            |
| Stream off        | `POST /api/stream/stop`  | same                            |
| Photo capture     | `POST /api/capture`   | same                               |

Response handling follows the server's JSON contract: handlers answer HTTP
200 with the outcome in the payload (`success` / `error`), so both the code
and the body are surfaced; snapshot bytes are decoded with `BitmapFactory`.

**Auth modes** (chosen in settings):

- **API Token** (default) — sends the `X-Api-Token` header. This is the
  fully-supported programmatic path against the current server: the Web
  Auth Gate accepts `X-Api-Token` / `Authorization: Bearer` on every
  protected route, and all three write routes above are on the server's
  `TokenWritePolicy` POST allow-list.
- **Basic** — sends RFC 7617 `Authorization: Basic base64(user:pass)`. The
  current server build does **not** validate Basic credentials on its
  protected routes (its ladder is API token or session cookie); this mode
  exists for deployments that front LensCast with a Basic-auth-capable
  proxy, and for forward compatibility. It is offered as-is — no
  client-side pretending otherwise.

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

`wear/build.gradle.kts` declares its plugins **inline with literal
versions** (AGP `9.4.0`, Kotlin compose plugin `2.2.10`) — matching the
root's pins exactly but touching neither `gradle/libs.versions.toml` nor
the root build scripts. Verified: Gradle 9.7.1 accepts the versioned
module requests even with the root's `apply false` declarations of the
same plugins on the classpath.

The complete integration is one line in `settings.gradle.kts`:

```kotlin
rootProject.name = "LensCast"
include(":app")
include(":wear")          // ← add this
```

Nothing else changes:

- **No catalog edits.** All dependency versions are inline in
  `wear/build.gradle.kts` (see the pins list below).
- **No :app changes.** The watch app is an independent APK
  (`applicationId com.raulshma.lenscast.wear`) installed on the watch, not
  a phone-APK embed. Build it with `:wear:assembleDebug` /
  `:wear:assembleRelease` and side-load via `adb install` onto the watch.
- **Signing:** the release build type intentionally declares no signing
  config (debug-signed by default); add a CI signing config if the watch
  APK ships through a store.
- **Unit tests:** `./gradlew :wear:testDebugUnitTest` runs the URL-builder
  pins (JUnit 4.13.2, declared inline).

### Dependency pins (inline, mirroring the root catalog)

| Artifact | Version |
|---|---|
| `com.android.application` (plugin) | 9.4.0 |
| `org.jetbrains.kotlin.plugin.compose` (plugin) | 2.2.10 |
| `androidx.compose:compose-bom` (platform) | 2026.03.00 |
| `androidx.wear:wear` | 1.3.0 |
| `androidx.wear.compose:compose-foundation` | 1.4.0 (stable) |
| `androidx.wear.compose:compose-material` | 1.4.0 (stable) |
| `androidx.compose.ui:ui`, `androidx.compose.foundation:foundation`, `ui-tooling(-preview)` | via BOM |
| `androidx.activity:activity-compose` | 1.10.1 |
| `androidx.datastore:datastore-preferences` | 1.1.4 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 |
| `junit:junit` (testImplementation) | 4.13.2 |

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

## F-Droid

Excluded from F-Droid metadata initially: the existing fdroid recipe is
unchanged and builds `:app` only. The module is already shaped for a later
recipe addition — `dependenciesInfo` disabled (mirrors `:app`), no
proprietary dependencies, INTERNET as the single permission — but no
`:wear` build block ships until the companion is ready for review.

## Permissions

`android.permission.INTERNET` — the only permission. No Wear Data Layer,
no background services, no sensors; polls run only while the app is
resumed (bound to the activity lifecycle), so the watch keeps its battery
when the remote is not on screen.
