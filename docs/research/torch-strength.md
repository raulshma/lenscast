# Torch strength API: real levels, contract, HAL, devices, fallbacks

Research for issue #16 ("brightness adjustment not supported on my device, which runs
Android 13 (API 33)... brightness can be adjusted across five levels (1/5 to 5/5)"
— reporter comment, 2026-11-07) against our `SDK >= 35` gate in
`camera/model/TorchStrengthPolicy.kt`.

Location note: this file lives at `docs/research/torch-strength.md` because the repo
has no existing research-notes convention (there is no `docs/research/` or notes dir
in the tree today); `docs/` holds per-topic engineering docs, so a research subfolder
of it seemed the least surprising home.

**Resolution (2026-11-07):** Option 1 shipped — the gate is now `SDK >= 33` at
`TorchStrengthPolicy.isSupported` and both `CameraService` sites
(`TIRAMISU`), with the §6 sweep applied and `TorchStrengthPolicyTest` rebased
with 32/33 boundary cases. The §"Residual unknowns" pair: (b) is now
observable on device — `applyTorchStrength` performs the CTS-style
`getTorchStrengthLevel` read-back and logs a mismatch warning; (a) (CameraX
repeating-request re-assert clobbering) still needs a multi-level API 33/34
device — none was available when this shipped (the dev POCO F1, API 35,
reports `strengthMaximumLevel = 1`, a live example of the single-level rung).

---

## TL;DR verdict

**The hypothesis is CONFIRMED.** The torch strength API is **Android 13 / API 33
(Tiramisu), not API 35**:

| Member | Added in API level | Primary evidence |
|---|---|---|
| `CameraManager.turnOnTorchWithStrengthLevel(String, int)` | **33** | developer.android.com reference "Added in API level 33"; API diff 32→33 lists it as Added |
| `CameraManager.getTorchStrengthLevel(String)` | **33** | same |
| `CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` | **33** | present verbatim in the `android-13.0.0_r1` framework source; docs generated from that API level |
| `CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL` | **33** | same |

`source.android.com`'s dedicated page states it outright: *"For devices running
Android 13 or higher, the Android framework provides a multilevel control for torch
strength. In Android 12 and lower, the framework allows only for turning the torch
mode on or off."*

**Where 35 actually comes from (the likely source of our wrong gate):** API 35 added
a *different* piece — the per-request key `CaptureRequest.FLASH_STRENGTH_LEVEL`
(confirmed by the official API 34→35 diff). CameraX 1.5.0's
`CameraControl.setTorchStrengthLevel` (added in 1.5.0-alpha06) is built **only** on
that API-35 request key; I decompiled the shipped 1.5.0 artifact and its gate is
literally `SDK_INT >= 35` with **no** API 33 fallback. Copying CameraX's gate — or
reading the API 35 docs — would produce exactly our bug. The `CameraManager` path we
use needs only 33.

**On the reporter's Samsung Android 13 device:** the SDK gate is *our* bug, but
whether the fix restores brightness on that specific phone depends on its camera HAL:
Samsung ships both kinds (Galaxy S21 and A53 report 5 levels through the public API
on Android 13/14; Galaxy S20 and A71 report 1 level even on Android 16). The
reporter's 5-level system UI is a good sign (One UI's official torch UI is
"Level 1 to Level 5", and community data shows the public API surfaces the same
levels on models that support it), but the only honest answer is runtime capability
detection — which our code already has and must keep.

---

## 1. Exact API levels

### 1.1 Official reference pages (developer.android.com)

- [`CameraManager.turnOnTorchWithStrengthLevel`](https://developer.android.com/reference/android/hardware/camera2/CameraManager#turnOnTorchWithStrengthLevel(java.lang.String,%20int)):
  page shows **"Added in API level 33"**. Signature: `public void
  turnOnTorchWithStrengthLevel (String cameraId, int torchStrength)`.
- [`CameraManager.getTorchStrengthLevel`](https://developer.android.com/reference/android/hardware/camera2/CameraManager#getTorchStrengthLevel(java.lang.String)):
  **"Added in API level 33"**.
- `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` / `FLASH_INFO_STRENGTH_DEFAULT_LEVEL`:
  documented on the
  [`CameraCharacteristics`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
  page ("Maximum flashlight brightness level." / "Default flashlight brightness level
  to be set via `CameraManager#turnOnTorchWithStrengthLevel(java.lang.String, int)`").
  The per-field API-level chips are only rendered on the individual-key anchors; the
  authoritative level proof for the keys is §1.3 below (present in the Android 13
  framework source) plus the HAL docs marking them "3.8" (§3), which is the Android
  13 metadata version.

### 1.2 Official API diff (strongest doc-tooling evidence)

https://developer.android.com/sdk/api_diff/33/changes/android.hardware.camera2.CameraManager
— header: *"API Diff Specification — To Level: 33, From Level: 32, Generated
2022.06.03"*. Under "Added Methods": `getTorchStrengthLevel(String)` and
`turnOnTorchWithStrengthLevel(String, int)`. Nothing torch-strength-related was added
to `CameraManager` in any later diff; the API 34→35 diff adds
`CaptureRequest.FLASH_STRENGTH_LEVEL` (the per-request key), not the manager methods
(https://developer.android.com/sdk/api_diff/35/changes/android.hardware.camera2.CaptureRequest).

### 1.3 AOSP source (android-13.0.0_r1 tag and main)

- `frameworks/base/.../CameraManager.java` at tag `android-13.0.0_r1` contains both
  methods (`turnOnTorchWithStrengthLevel` at line 1096, `getTorchStrengthLevel` at
  line 1122, `TorchCallback.onTorchStrengthLevelChanged` in the same file):
  https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/hardware/camera2/CameraManager.java
- `CameraCharacteristics.java` at the same tag declares
  `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` (line 1333) and
  `FLASH_INFO_STRENGTH_DEFAULT_LEVEL` (line 1356):
  https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/hardware/camera2/CameraCharacteristics.java
- On `main` (checked 2026-11-07), neither the methods nor the key fields carry a
  `@RequiresApi` annotation — framework-internal code annotates only
  `@PublicKey`/`@NonNull` here. Android's "Added in API level" chips and lint's
  NewApi check come from generated API-history metadata (`api-versions.xml`), not
  from in-source annotations, so the diff pages and the release-tag presence above
  are the authoritative checks. (Contrast: androidx *does* annotate — CameraX's
  `Api35Compat` is literally `@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)`,
  see §4.)

**Verdict: API 33 (Tiramisu). The claim "added by API 35" in our KDoc is factually
wrong and must be fixed wherever it appears (§6).**

---

## 2. Contract semantics (official reference + AOSP contract docs)

### 2.1 What the call does

Reference Javadoc (verbatim, from the
[CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager#turnOnTorchWithStrengthLevel(java.lang.String,%20int))
page):

> "Set the brightness level of the flashlight associated with the given cameraId in
> torch mode. **If the torch is OFF and torchStrength is >= 1, torch will turn ON
> with the strength level specified in torchStrength.**"

> "Use `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` to check whether the camera device
> supports flash unit strength control or not. **If this value is greater than 1,
> applications can call this API to control the flashlight brightness level.**"

The AIDL HAL contract (below, §3) adds the brightness scaling semantics the Java doc
omits:

> "This level does not represent the real brightness units. **It is linear in nature
> i.e. flashlight at level 10 is twice as bright as at level 5.**"
> — `ICameraDevice.aidl`, tag `android-13.0.0_r1`
> (https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-13.0.0_r1/camera/device/aidl/android/hardware/camera/device/ICameraDevice.aidl)

### 2.2 Exceptions

From the same reference page, `turnOnTorchWithStrengthLevel` throws:

- **`CameraAccessException`** — "if it failed to access the flash unit";
  `CAMERA_IN_USE` if the camera device is in use, `MAX_CAMERAS_IN_USE` if other
  camera resources needed to keep the torch on are in use, `CAMERA_DISCONNECTED` if
  camera service is not available.
- **`IllegalArgumentException`** — "if cameraId was null, cameraId doesn't match any
  currently or previously available camera device, the camera device doesn't have a
  flash unit **or if torchStrength is not within the range i.e. is greater than the
  maximum level `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` or <= 0**."

The AIDL interface documents the unsupported-HAL case as a service-specific error
that surfaces as `CameraAccessException`-family failures:

> "`OPERATION_NOT_SUPPORTED`: This status code is returned when: — This camera device
> does not support direct operation of flashlight torch mode (the framework must open
> the camera device and turn the torch on through the device interface). — This
> camera device does not have a flash unit. — **This camera device has flash unit but
> does not support torch strength control.**"

The CTS test (`FlashlightTest.java`, tag `android-13.0.0_r1`) pins the range behavior
empirically: `turnOnTorchWithStrengthLevel(id, 0)` and
`turnOnTorchWithStrengthLevel(id, maxLevel + 1)` must both throw
`IllegalArgumentException`. Note CTS **skips** the whole test when
`maxLevel == 1` ("Torch strength level adjustment is not supported."), so what
exactly happens when you call the API on a max==1 device is *not* exercised by CTS —
another reason LensCast's defensive `runCatching` is correct.

### 2.3 Reset-to-default behavior (exact quotes)

Reference page, verbatim:

> "**When the torch is turned OFF via `setTorchMode`, the flashlight brightness level
> will reset to default value `FLASH_INFO_STRENGTH_DEFAULT_LEVEL`**. In this case the
> `TorchCallback.onTorchStrengthLevelChanged` will not be invoked."

> "If torch is enabled via `setTorchMode` after calling
> `turnOnTorchWithStrengthLevel` with level N **then the flash unit will have the
> brightness level N**. Since multiple applications are free to call
> `setTorchMode`, when the latest application that turned ON the torch mode exits,
> the torch mode will be turned OFF and in this case the brightness level will reset
> to default level."

`getTorchStrengthLevel` (same page):

> "When the torch is turned OFF, the strength level will reset to a default level
> `FLASH_INFO_STRENGTH_DEFAULT_LEVEL`. In this case the return value will be
> `FLASH_INFO_STRENGTH_DEFAULT_LEVEL` rather than 0."

The HAL AIDL repeats the same rule ("Whenever the torch is turned OFF, the brightness
level will reset to `FLASH_INFO_STRENGTH_DEFAULT_LEVEL`") — so reset-on-off is a
hard HAL contract, not an implementation accident.

### 2.4 Interaction with `enableTorch` / `setTorchMode`

- The docs only cover the `CameraManager.setTorchMode` case: setting level N then
  `setTorchMode(true)` keeps brightness N (quote above). The strength survives torch
  re-enable; only a real torch-OFF resets it.
- What the docs do **not** spell out: `CameraX.enableTorch` does not go through
  `setTorchMode` — it sets `FLASH_MODE_TORCH` in the session's repeating capture
  request (verified in the shipped CameraX 1.5.0 bytecode: the torch-on branch sets
  `CaptureRequest.FLASH_MODE == 2` with `OptionPriority.REQUIRED`). Whether a
  repeating-request torch re-assert clobbers device-level strength on every HAL is
  undocumented. The current main-branch metadata docs for the (new, v3.10) dynamic
  `android.flash.strengthLevel` tag say that if the app doesn't set the per-request
  key and torch strength is supported, "the flash will be fired at the default level
  set by HAL" — which suggests request-driven torch *can* land at default brightness
  on some HALs.
- **LensCast is already ordered safely**: `applyCameraControls` calls
  `enableTorch(settings.torchEnabled)` first and `applyTorchStrength` second
  (`CameraService.kt:1265-1278`), and re-asserts both on every settings apply and
  after every rebind — the same pattern the FlashDim project adopted as an explicit
  "Fix brightness control" workaround (their PR #140: on some devices the strength
  call while the torch is off applies default brightness; fix = turn torch on first,
  then set level). Keep this ordering.

### 2.5 `getTorchStrengthLevel` with no camera open

Works without any camera session: `source.android.com` states *"No camera permissions
are required for these APIs because the camera isn't accessed"*, and the CTS
`FlashlightTest` calls `getTorchStrengthLevel` immediately after
`turnOnTorchWithStrengthLevel` without ever opening a camera device. LensCast's
bind-time query pattern is fine.

---

## 3. HAL requirement

- The camera metadata spec marks both static keys **"3.8"** — the camera2
  metadata/API version introduced with Android 13. From the generated metadata docs
  (`system/media/camera/docs/docs.html`, tag `android-13.0.0_r1`):

  > "android.flash.info.strengthMaximumLevel — int32 [public] — Maximum flashlight
  > brightness level. **3.8** — Details: If this value is greater than 1, then the
  > device supports controlling the flashlight brightness level via
  > CameraManager#turnOnTorchWithStrengthLevel. **If this value is equal to 1,
  > flashlight brightness control is not supported.** The value for this key will be
  > null for devices with no flash unit. **The maximum value is guaranteed to be safe
  > to use for an indefinite duration** in terms of device flashlight lifespan, but
  > may be too bright for comfort for many use cases."

  > "android.flash.info.strengthDefaultLevel — int32 [public] — Default flashlight
  > brightness level to be set via CameraManager#turnOnTorchWithStrengthLevel. **3.8**
  > — Details: If flash unit is available this will be greater than or equal to 1 and
  > less or equal to android.flash.info.strengthMaximumLevel. **Setting flashlight
  > brightness above the default level may make the device more likely to reach
  > thermal throttling conditions** and slow down, or drain the battery quicker than
  > normal. To minimize such issues, it is recommended to start the flashlight at
  > this default brightness until a user explicitly requests a brighter level. … The
  > default level should always be > 0."
  > (https://android.googlesource.com/platform/system/media/+/refs/tags/android-13.0.0_r1/camera/docs/docs.html)

- **"HAL 3.8" is the camera2 *metadata* version, not a HIDL interface version.** The
  Android 13 tag of `hardware/interfaces` contains HIDL `camera/device` only up to
  **3.7** (verified directory listing); the feature is delivered through the **AIDL**
  `android.hardware.camera.device.ICameraDevice` — `turnOnTorchWithStrengthLevel(int)`
  and `getTorchStrengthLevel()` — whose docs are quoted in §2. Android 13 is also the
  first release with the AIDL camera HAL, which is why the metadata "3.8" feature and
  API 33 coincide.
- `source.android.com`'s implementation guide names the AIDL interface, the two
  characteristics keys, the reference implementation (`EmulatedCameraDeviceHWLImpl.cpp`),
  and the validation tests (VTS `VtsAidlHalCameraProvider_TargetTest.cpp`, CTS
  `FlashlightTest.java`): https://source.android.com/docs/core/camera/torch-strength-control
- Meaning of the levels: **max == 1 ⇒ brightness control not supported (binary
  torch)**; **max > 1 ⇒ levels 1..max, linear in brightness** (§2.1); **default
  level** ∈ [1, max] is the thermal-safe starting brightness, which the device
  returns to every time the torch goes off.
- Current main-branch metadata (HAL 3.9/3.10 era, Android 14/15/16) later split the
  keys into `android.flash.torchStrengthMaxLevel` / `singleStrengthMaxLevel` and made
  `android.flash.strengthLevel` a dynamic request/result tag; the public
  `CaptureRequest.FLASH_STRENGTH_LEVEL` request key is **API 35**
  (https://developer.android.com/sdk/api_diff/35/changes/android.hardware.camera2.CaptureRequest).
  None of that is needed for the 33/34 path.

---

## 4. Device support landscape

Labels: **[official]** = first-party docs; **[community]** = reproducible community
evidence (public issue trackers with concrete device data); **[anecdote]** = forum
posts without reproducible detail.

### 4.1 Samsung

- **[official]** Samsung's support page documents One UI torch brightness as a
  5-level system feature: *"Hold & Press Torch icon … You can adjust the Torch
  brightness from **Level 1 to Level 5**"*
  (https://www.samsung.com/in/support/mobile-devices/samsung-galaxy-how-to-adjust-torch-brightness/).
  Samsung publishes **no developer API** for it; the page describes only the
  quick-settings flow. Whether One UI's slider internally uses
  `turnOnTorchWithStrengthLevel` is undocumented — but see the community evidence
  below that the public API exposes the same levels on supporting models.
- **[community]** FlashDim (cyb3rko/flashdim, the largest OSS torch-dim app, F-Droid)
  device reports via its public API path:
  - **Samsung Galaxy S21 (SM-G991B), Android 14: Light Levels = 5** — issue #82
    comment: "This device works with the app."
  - **Samsung Galaxy A53, stock One UI: 5 dim levels** — issue #61 ("on my samsung
    galaxy a53 on stock rom this app was working without problem with 5 dim levels";
    flashing a GSI breaks it — the levels come from the vendor HAL, not the app).
  - **Samsung Galaxy S20 (SM-G980F), Android 13 (API 33): Light Levels = 1** —
    issue #40 (auto-report template).
  - **Samsung Galaxy A71 (SM-A715F), Android 16 (API 36): Light Levels = 1** —
    issue #157.
  So Samsung HALs are **bimodal**: many post-2021 models (S21+, A53, S23 per
  polodarb's README) report max 5 through the public API on Android 13+; older or
  lower-tier models report max 1 forever, regardless of OS version. The reporter's
  5-level One UI slider therefore suggests a supporting HAL — consistent with, but
  not proof of, the public API working on their phone.
- **[community]** The FlashDim "unsupported" list — devices manually excluded from
  its Play listing for reporting max == 1 — currently counts **1,178 models**
  (README section "Excluded devices (Play Store) / Unsupported devices"), all
  Android-13-capable phones. Capability detection is not optional.

### 4.2 Google Pixel

- **[community]** Pixels report large level counts via the public API: FlashDim
  issue #159 — **Pixel 8a: "used to offer 45 levels of brightness"** (reduced to 21
  after the Android 17 update); issue #146 — Pixel 9a 45 → 21 after the same update.
- **[anecdote/press]** Android Authority's "Pixel 8's flashlight is half intensity by
  default" (linked from the FlashDim README) is consistent with the docs' default
  level being a mid-level thermal choice on Pixels.

### 4.3 Dedicated OSS torch-dim projects (the de-facto device matrices)

- **[community]** **FlashDim** (cyb3rko/flashdim): README badge "API 33+"; "Starting
  with Android 13, it's possible to control multiple brightness levels of the
  flashlight." Full functionality "ONLY AVAILABLE for devices which support the dim
  functionality of the flashlight (hardware limited)." It reads
  `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` and degrades to binary torch otherwise
  (issue #2).
- **[community]** **Flashlight-Tiramisu** (polodarb/Flashlight-Tiramisu, the
  original Android 13 dimmer FlashDim credits): README states "The minimum version of
  Android is **13 (SDK33)**. Flashlight brightness control only supports **HAL
  version 3.8 and higher**, so among the supported devices, the latest ones (For
  example, **Pixel 6/7, Samsung S23**, etc.)".
- **[community]** FlashDim PR #140 ("Add 'Fix brightness control' setting"): on some
  devices "calling `turnOnTorchWithStrengthLevel()` while the torch is off causes the
  flashlight to turn on at default brightness instead of the requested level"; the
  fix calls `setTorchMode(true)` first, then the strength call. Directly validates
  LensCast's enableTorch-first ordering.
- The names in the research brief: the active project is **cyb3rko/flashdim**
  (F-Droid `com.cyb3rko.flashdim`); "luca020400" and "iasatan" forks did not turn up
  as the maintained matrix sources; **polodarb/Flashlight-Tiramisu** is the third
  pillar. I found no published per-device max-level matrix beyond FlashDim's issue
  tracker + exclusion list.

---

## 5. Fallback landscape for unsupported devices

### 5a. Is there ANY public brightness API below API 33? — No.

- **[official]** The metadata proof: the Android 12 (`android-12.0.0_r1`) camera
  metadata spec contains **zero flash-strength tags** — its only "strength" keys are
  `edge.strength` / `noiseReduction.strength` / `shading.strength` (image-processing
  controls). The flash package there is binary: `flash.mode`, `flash.state`,
  `flash.firingPower/firingTime` (strobe *energy* for still capture, not torch
  brightness).
- **[official]** `CameraManager.setTorchMode` (API 23+) is boolean. Camera2
  `CaptureRequest.FLASH_MODE` values `OFF/ON/SINGLE/TORCH`... control *capture*
  flashing; torch is on/off. Camera1 (`android.hardware.Camera.Parameters`) flash
  modes are likewise enumerated on/off modes with no intensity
  (https://developer.android.com/reference/android/hardware/Camera.Parameters).
- Conclusion: below 33 there is genuinely nothing public. Any pre-33 "dimming" is
  either PWM tricks (5b) or privileged hacks (5c/5d).

### 5b. PWM / duty-cycle dimming (rapid toggling) — not viable for LensCast

- **Toggle latency is real and unbounded by contract.** The documented path has no
  latency guarantee. `setTorchMode` is a binder round-trip cameraservice → HAL;
  CameraX `enableTorch` is worse: it updates the session's repeating request, so the
  change reaches the HAL on the next request submission (frame-scale, and CameraX
  applies updates asynchronously with cancellation semantics). CTS's own pacing
  (TORCH_DURATION_MS = 1000 ms settle, TORCH_TIMEOUT_MS = 3000 ms callback timeout,
  `FlashlightTest.java:49-50`) signals that the platform team treats the torch path
  as slow-ish; no spec promises a minimum toggle interval, so rapid toggling is
  undefined behavior on HALs.
- **Community evidence [anecdote-grade]:** the classic StackOverflow thread
  "Android: how to blink LED/flashlight rapidly"
  (https://stackoverflow.com/questions/16348418/) shows 50 ms-period toggling
  already missing beats a decade ago ("it is not blinking rapidly... it takes time
  to blink"), with behavior varying per device ("did not work on my Nexus 5 with
  Lollipop. It did however work on Sony Z3"). Modern evidence is scarce precisely
  because the strength API made it unnecessary; FlashDim's strobe-ish features
  (morse/BPM modes) operate at human-visible rates, not dimming rates.
- **Flicker in our own stream (LensCast-specific, engineering analysis):** the
  illumination LED sits in the camera's field of view. Duty-cycle dimming at 10–50 Hz
  interacts with 30 fps capture and rolling shutter → visible pulsing and per-frame
  banding in the CCTV stream, plus 50/100 Hz mains-band artifacts if the LED driver
  has ripple. Even if a given HAL toggles cleanly, the stream shows it.
- **Thermal/HAL concerns:** the strength spec's whole point is that *max* level is
  the only level "guaranteed to be safe to use for an indefinite duration" (§3);
  PWM still drives full-current pulses, and the metadata docs explicitly warn that
  sustained above-default brightness invites thermal throttling. A HAL is also free
  to ignore/serialize rapid mode switches (`CAMERA_IN_USE` /
  `MAX_CAMERAS_IN_USE` failures on resource contention).
- Verdict: **do not ship PWM dimming** as a fallback. The platform's own "strobe"
  use case (source.android.com, §3) is a signaling feature of the strength API, not
  an endorsement of toggle-dimming.

### 5c. Root / sysfs paths — out of scope (documented, not viable)

- **[community]** The canonical non-API approach is writing
  `/sys/class/leds/...brightness` (per-device LED driver nodes). Concrete example:
  `MematiBas42/Ginkgo-Flashlight-Brightness-Control` — a **root shell script** for
  Redmi Note 8 that requires "**Root access** on your Android device" **and** "a
  kernel that supports adjustable flashlight brightness — such as the Flop Ginkgo
  Kernel", with node names/level counts defined by the specific kernel driver.
- Why out of scope for a public F-Droid app: requires root (excludes the target
  audience and most CCTV repurposed phones), nodes/semantics are per-kernel and
  undocumented, SELinux may deny untrusted-app or even shell writes on stock ROMs,
  and behavior differs per device in ways LensCast cannot sanely abstract. (FlashDim
  issue #61 even shows ROM swaps silently changing level counts — sysfs hacks are at
  least as fragile.)

### 5d. Vendor SDKs — none callable by a third-party app

- **Samsung:** no public/official developer API for torch brightness (their docs
  describe only the system-UI flow, §4.1). One UI's slider is a system component
  over the same camera HAL capability; there is no SDK surface a F-Droid app can
  call without privileged/system permissions.
- **Xiaomi/Motorola/others:** the only mechanisms surfaced by search are root-based
  (§5c) or vendor-HIDL binder hacks that require privileges untrusted apps don't
  have. **[community]** evidence found no rootless, redistributable vendor API.
- Conclusion: there is no vendor escape hatch; the public API + capability detection
  is the entire honest surface.

---

## 6. LensCast fit — every place that must change if the gate moves 35 → 33

No edits made; findings only. All paths relative to repo root. (`grep -rn "Android 15|API 35|SDK 35|Android 13|API 33"` across app/src, web/src (excl. node_modules), docs/, README.md, wear/, CONTEXT.md, fdroid/, fastlane/ — the only "Android 13/API 33" hits are the unrelated POST_NOTIFICATIONS comments, which are correct as-is.)

### 6.1 The functional gate (must change)

| Location | What's there | Change |
|---|---|---|
| `app/src/main/java/com/raulshma/lenscast/camera/model/TorchStrengthPolicy.kt:44` | `fun isSupported(sdkInt: Int): Boolean = sdkInt >= 35` | `>= 33` (`Build.VERSION_CODES.TIRAMISU`) |
| `app/src/main/java/com/raulshma/lenscast/camera/CameraService.kt:908` | `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && cam.cameraInfo.hasFlashUnit())` gating the `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` bind-time query | `>= TIRAMISU` — otherwise `_torchStrengthMaxLevel` stays null on API 33/34 and the policy never engages even after the policy gate is fixed |
| `app/src/main/java/com/raulshma/lenscast/camera/CameraService.kt:1402` | `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return` at the top of `applyTorchStrength` | `>= TIRAMISU` check inverted accordingly |

Note the SDK check in `CameraService` is needed at both sites because
`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` and `turnOnTorchWithStrengthLevel` are API 33
symbols; with compileSdk 36 the references are safe, the checks just need the right
constant.

### 6.2 Comments/KDoc that repeat the wrong claim (must change to stay truthful)

- `TorchStrengthPolicy.kt:4` ("API 35+ torch strength API"), `:6-8` ("API 35 added
  `CameraManager.turnOnTorchWithStrengthLevel`…"), `:18` ("Unsupported SDK (< 35)…"),
  `:32` ("per the API 35 contract"), `:38` (javadoc reference), and `:44` docstring.
- `CameraService.kt:158-163` ("torch strength ceiling (API 35+…)"), `:897-905`
  ("API 35+ per-level torch API…"), `:1265-1274` ("Per-level torch strength (API 35+)…"),
  `:1391-1400` ("the API 35+ turnOnTorchWithStrengthLevel path…").
- `CameraSettings.kt:46` ("Torch brightness override for API 35+ torch-strength
  control, 1..8").
- `SettingsDataStore.kt:922` ("a present key is the API 35+ override").
- `AutomationActionCatalog.kt:200` ("API 35+ devices only; absent = device default
  strength.").
- `CONTEXT.md:1925-1941` (architecture notes: "35+ per-level torch: … SDK < 35 …").

### 6.3 Tests

- `app/src/test/java/com/raulshma/lenscast/camera/model/TorchStrengthPolicyTest.kt`
  hard-codes `35` throughout (lines 18, 26, 36, 42, 47, 52, 57-58, 65-66, 71, 76-78,
  86, 93 — e.g. `sdk below 35 is unsupported`, `sdk 35 and above is supported`).
  Needs a 33 rebase plus boundary cases at 32/33; consider adding a
  `effectiveLevel(3, 5, 33)` style test to pin the new floor.

### 6.4 Docs and user-facing copy

- `docs/automation.md:49-51` — "torch strength 1 requires the torch-strength
  hardware (see below: **Android 15+** with a multi-level flash)".
- `docs/automation.md:256` — action table row: "`strength` INT 1–8, optional,
  **API 35+ multi-level flash only**".
- `docs/automation.md:281-293` — the "Torch strength (device requirements)" section:
  "Multi-level torch brightness rides **Android 15's (API 35+)**
  `CameraManager.turnOnTorchWithStrengthLevel`…" and "**API 35+ required.** Below
  that — or on a single-level flash (max level ≤ 1), or when the device's max level
  is unknown — the strength parameter is ignored…".
- `README.md:134` — "the torch action takes an optional strength level on **Android
  15+ (API 35)** devices with a multi-level flash (1–8; the device's real maximum
  wins at apply time; below that the torch is plain on/off)".
- `README.md:135` — "strength needs the **API 35+ multi-level-flash** hardware
  above."
- `app/src/main/assets/webui/assets/index-DsoPPMee.js` — built web-UI bundle
  (regenerated from `web/`, not hand-editable).

### 6.5 Strings that are already version-agnostic (no change needed, listed to close the search)

- App strings (`app/src/main/res/values*/strings.xml`):
  `automation_param_strength` ("Strength", values line 581),
  `automation_summary_torch_strength` ("Torch strength %1$s", line 588) and the
  translated variants (values-de/es/fr/pt-rBR/ru/zh) — no version mention anywhere;
  the "Android 15|API 35" grep is clean over `res/`.
- Web i18n hint `automation.editor.torchStrengthHint` = "Strength levels are
  supported on devices with a multi-level flash only" — present in all six locales
  (`web/src/i18n/en.ts:825`, de/es/fr/pt/zh equivalents), versionless and still
  accurate post-fix. If the fix ships, consider extending this hint with "requires
  Android 13+" for expectation-setting — that would touch all six locale files plus
  `app` parity if mirrored.
- In-app editor hint lives in `AutomationScreen.kt` (param hints flow through
  `AutomationViewModel.torchStrengthBounds`, which is device-max-aware and
  version-agnostic — no change).

---

## Implementation options for LensCast (ranked)

### Option 1 — Lower the gate to 33 with runtime capability detection (recommended)

Change `TorchStrengthPolicy.isSupported` to `sdkInt >= 33` and swap
`VANILLA_ICE_CREAM` → `TIRAMISU` at `CameraService.kt:908` and `:1402`; update the
comments/KDoc listed in §6.2, docs in §6.4, and rebase the tests (§6.3).

- **Effort:** small. Two functional lines + comment/test/doc sweep (≈8 files, all
  mechanical except the test rebase).
- **Risk:** low. The existing no-op ladder already provides the runtime capability
  detection this needs: `deviceMaxLevel == null` (characteristic missing/failed
  query) and `max <= 1` both no-op to plain `enableTorch`, and the apply is wrapped
  in `runCatching` — which covers the HALs that throw `OPERATION_NOT_SUPPORTED` /
  `IllegalArgumentException` on unsupported devices (CTS never exercises the max==1
  call, §2.2, so the try/catch is load-bearing).
- **Behavioral upside:** the exact enableTorch-then-strength ordering §2.4 validates
  via FlashDim PR #140 already ships here, so API 33/34 devices with max > 1 (S21,
  A53, Pixels, S23…) get real brightness control.
- **Residual unknowns to test on-device (33/34):** (a) whether a CameraX repeating
  request torch re-assert between our strength calls clobbers the level on some HALs
  (undocumented, §2.4 — mitigation already in place: we re-assert strength on every
  apply/rebind); (b) that `getTorchStrengthLevel`-based verification matches the
  requested level after apply (CTS does exactly this; a debug log or assertion would
  be cheap).
- Do **not** switch to CameraX 1.5's `CameraControl.setTorchStrengthLevel` for this
  fix — its shipped implementation is API-35-only (§TL;DR); keep the CameraManager
  path, which is the only 33/34-capable public path.

### Option 2 — Graceful behavior when strength is unsupported (mostly built; polish only)

Current state is already the right shape: missing characteristic → null → skip;
`max <= 1` → null → skip; failures logged-and-swallowed; docs say strength is
"ignored" on those devices (`docs/automation.md:286-288`); web editor hint is
versionless ("multi-level flash only", §6.5).

- **Effort:** near-zero for correctness; small for UX honesty. Optional: when
  `torchStrengthMaxLevel == null` while `sdkInt >= 33`, surface "this device's
  flashlight has no brightness levels" in the rule editor instead of silently
  accepting a strength param (the ViewModel already has
  `torchStrengthBounds(deviceMaxLevel)` to hang this on). Also consider capping the
  stored value against the live max at save time when known (already clamped at
  apply).
- **Risk:** low.
- Do **not** add PWM/toggle fallbacks (§5b): flicker lands in the CCTV stream's own
  field of view, toggle latency is contractually unbounded, and above-default
  sustained brightness is explicitly thermal-cursed by the platform docs. Do not
  chase root/sysfs or vendor hacks (§5c/5d) — out of scope for F-Droid.

### Option 3 — Anything for pre-33 devices

Nothing honest exists (§5a: no public API, no metadata, no vendor SDK below 33).
With `minSdk = 23` (`app/build.gradle.kts:146`) pre-33 devices install the app, so
the only pre-33 work worth doing is messaging: the automation editor/web hint
already degrade to "plain on/off"; optionally detect `sdkInt < 33` in the editor and
hide the strength field instead of accepting a value that will always no-op
(effort: small; risk: low). Anything beyond that (PWM, root) is rejected above.

---

## Sources

Primary/official:

- CameraManager reference (turnOnTorchWithStrengthLevel, getTorchStrengthLevel, setTorchMode, TorchCallback): https://developer.android.com/reference/android/hardware/camera2/CameraManager
- API diff 32→33, CameraManager: https://developer.android.com/sdk/api_diff/33/changes/android.hardware.camera2.CameraManager
- API diff 34→35, CaptureRequest (FLASH_STRENGTH_LEVEL added): https://developer.android.com/sdk/api_diff/35/changes/android.hardware.camera2.CaptureRequest
- CameraCharacteristics reference: https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- source.android.com — Torch strength control (Android 13+, AIDL HAL, validation): https://source.android.com/docs/core/camera/torch-strength-control
- source.android.com — Camera version support: https://source.android.com/docs/core/camera/versioning
- AOSP frameworks/base CameraManager.java @ android-13.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/hardware/camera2/CameraManager.java
- AOSP frameworks/base CameraManager.java @ main: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/CameraManager.java
- AOSP frameworks/base CameraCharacteristics.java @ android-13.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/hardware/camera2/CameraCharacteristics.java
- AOSP hardware/interfaces ICameraDevice.aidl @ android-13.0.0_r1: https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-13.0.0_r1/camera/device/aidl/android/hardware/camera/device/ICameraDevice.aidl
- AOSP hardware/interfaces camera/device listing @ android-13.0.0_r1 (HIDL stops at 3.7): https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-13.0.0_r1/camera/device/
- AOSP system/media camera metadata docs @ android-13.0.0_r1 (strength tags, v3.8): https://android.googlesource.com/platform/system/media/+/refs/tags/android-13.0.0_r1/camera/docs/docs.html
- AOSP system/media camera metadata docs @ android-12.0.0_r1 (no flash strength tags): https://android.googlesource.com/platform/system/media/+/refs/tags/android-12.0.0_r1/camera/docs/docs.html
- AOSP system/media camera metadata docs @ main (v3.10 dynamic strengthLevel, split max keys): https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/docs.html
- AOSP CTS FlashlightTest.java @ android-13.0.0_r1: https://android.googlesource.com/platform/cts/+/refs/tags/android-13.0.0_r1/tests/camera/src/android/hardware/camera2/cts/FlashlightTest.java
- AndroidX CameraControl reference (setTorchStrengthLevel contract, added 1.5.0): https://developer.android.com/reference/androidx/camera/core/CameraControl
- AndroidX Camera release notes (1.5.0-alpha06 torch strength APIs): https://developer.android.com/jetpack/androidx/releases/camera
- AndroidX source, ApiCompat.kt (`@RequiresApi(VANILLA_ICE_CREAM)` Api35Compat): https://github.com/androidx/androidx/blob/androidx-main/camera/camera-camera2/src/main/java/androidx/camera/camera2/compat/ApiCompat.kt
- AndroidX source, TorchControl.kt @ androidx-camera-release (SDK 35 gate, request-key path): https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-camera-release/camera/camera-camera2/src/main/java/androidx/camera/camera2/impl/TorchControl.kt
- CameraX camera-camera2 1.5.0 AAR (decompiled bytecode evidence): https://dl.google.com/android/maven2/androidx/camera/camera-camera2/1.5.0/camera-camera2-1.5.0.aar
- Samsung support — Galaxy torch brightness "Level 1 to Level 5": https://www.samsung.com/in/support/mobile-devices/samsung-galaxy-how-to-adjust-torch-brightness/
- Camera.Parameters (Camera1 flash modes, binary): https://developer.android.com/reference/android/hardware/Camera.Parameters
- LensCast issue #16 (reporter comment: Android 13, 5-level system UI): https://github.com/raulshma/lenscast/issues/16

Community (reproducible trackers/projects; labeled as such above):

- FlashDim (cyb3rko/flashdim) README — "Android 13+", 1,178-model exclusion list: https://github.com/cyb3rko/flashdim
- FlashDim issue #2 ("only supports 1 light level" template): https://github.com/cyb3rko/flashdim/issues/2
- FlashDim issue #40 (Galaxy S20 / Android 13 / 1 level): https://github.com/cyb3rko/flashdim/issues/40
- FlashDim issue #82 (Galaxy S21 / Android 14 / 5 levels): https://github.com/cyb3rko/flashdim/issues/82
- FlashDim issue #61 (Galaxy A53 stock 5 levels; GSI breaks): https://github.com/cyb3rko/flashdim/issues/61
- FlashDim issue #157 (Galaxy A71 / Android 16 / 1 level): https://github.com/cyb3rko/flashdim/issues/157
- FlashDim issue #159 (Pixel 8a, 45 → 21 levels): https://github.com/cyb3rko/flashdim/issues/159
- FlashDim issue #146 (Pixel 9a, 45 → 21 levels): https://github.com/cyb3rko/flashdim/issues/146
- FlashDim PR #140 (strength-while-off quirk; setTorchMode-first workaround): https://github.com/cyb3rko/flashdim/issues/140
- Flashlight-Tiramisu (polodarb) README — "minimum Android 13 (SDK33)", HAL 3.8+, Pixel 6/7, S23: https://github.com/polodarb/Flashlight-Tiramisu
- StackOverflow 16348418 — rapid flashlight blink attempts/limits: https://stackoverflow.com/questions/16348418/android-how-to-blink-led-flashlight-rapidly
- StackOverflow 76980137 — turnOnTorchWithStrengthLevel throwing on-device (referenced; fetch blocked by SO, not directly quoted): https://stackoverflow.com/questions/76980137/
- Ginkgo-Flashlight-Brightness-Control (root + custom kernel sysfs approach): https://github.com/MematiBas42/Ginkgo-Flashlight-Brightness-Control
