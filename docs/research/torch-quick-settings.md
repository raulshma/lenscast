# QS flashlight vs the flash-owning camera: three layers, one lock

Research for issue #16c (reporter comment, 2026-11): *"when using the Main lens,
the flashlight toggle in the Quick Settings panel becomes locked and disabled,
and Samsung Modes and Routines can no longer control it either. This currently
works when using the Ultra-Wide lens."* The reporter explicitly asks that the
external control keep working, and worries changes could break the Ultra-Wide
case. Companion research: `torch-strength.md` (the brightness half of #16b).

**Resolution (2026-11-16):** the lock is not a LensCast bug and not fixable from
LensCast — it is the framework's flash-unit exclusivity, layered with a
ROM-dependent SystemUI availability policy. Verified by experiment on the dev
POCO F1 (see §4) and against the public API docs + CTS (§2–3). **No behavior
change:** the experiment closed off the one alternative we could have tried —
`CameraManager.setTorchMode` is not usable while streaming at all. The one
LensCast-side action item is *do not* "fix" this by switching torch strategy,
which would trade a framework rule for a worse one (§5).

---

## TL;DR verdict

The QS flashlight tile IS `CameraManager.setTorchMode` — the tile and the app
compete for the same flash unit, and the framework rules make that competition
one-sided:

1. **Framework (both ROMs):** opening the camera that owns the flash unit turns
   the torch off and marks it unavailable; `setTorchMode` throws
   `CAMERA_IN_USE` while the camera is in use. The flash unit is exclusively
   claimed by the open session.
2. **LensCast's Main lens:** the LED is driven inside the camera session
   (`FLASH_MODE_TORCH` through CameraX `enableTorch`) — the only public path
   while streaming, because torch mode requires the camera closed. Therefore a
   locked QS tile while the Main lens streams is structural: *no* app
   implementation can light that LED and keep the tile free.
3. **SystemUI (ROM-dependent):** when the tile disables, and for which camera.
   The reporter's One UI keys it off flash-unit ownership (Ultra-Wide has no
   flash unit → tile stays live). AOSP-family SystemUI is stricter: on the dev
   POCO F1 the tile locks whenever ANY camera is open — even the flash-less
   front camera — with the OS's own "Camera in use" subtitle.

The Ultra-Wide behavior the reporter wants to preserve is the SystemUI layer
doing its per-flash-unit thing on their ROM. LensCast neither causes nor can
remove it. The best we can do — and what this research pins as a constraint —
is keep LensCast's camera usage exactly as it is and never adopt
`setTorchMode` as the streaming path (§5).

---

## 1. Who owns what: the flash unit belongs to the camera ID

The torch and the camera session address the same hardware through the same
service, with a strict exclusivity contract in `CameraManager.setTorchMode`
(API reference, quoted 2026-11-16):

> "Note that even if a camera device has a flash unit, turning on the torch
> mode may fail if the camera device or other camera resources needed to turn
> on the torch mode are in use."
>
> "The torch mode will be turned off and becomes unavailable when the camera
> device that the flash unit belongs to becomes unavailable or when other
> camera resources to keep the torch on become unavailable
> (`TorchCallback.onTorchModeUnavailable` will be invoked)."

and from the `@throws` table: "`CAMERA_IN_USE` will be thrown if the camera
device is in use." `turnOnTorchWithStrengthLevel` carries the same throws.

So the framework-level rule is: **flash unit of camera N ↔ session on camera
N**. Open camera N, and (a) any external torch on N is force-turned-off, (b)
further `setTorchMode`/strength calls on N fail with `CAMERA_IN_USE` until the
session closes. Opening camera M ≠ N does not touch N's flash at the service
level.

## 2. CTS confirms the torch APIs are the camera-closed path

AOSP CTS `FlashlightTest` (`tests/camera/src/android/hardware/camera2/cts/`,
read 2026-11-16) exercises `testTurnOnTorchWithStrengthLevel` and
`testSetTorchModeOnOff` **without ever opening a camera device** — the tests
only `resetTorchModeStatus`, register a `TorchCallback`, and toggle. Two more
facts fall out of the same file:

- For a flash camera whose `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` is `1`, CTS
  skips the strength block entirely and logs "Torch strength level adjustment
  is not supported." — our `TorchStrengthPolicy` (null verdict = skip) is the
  same verdict.
- `testSetTorchModeOnOff` verifies `onTorchModeUnavailable` is **never**
  invoked during plain torch toggling. Unavailable is specifically the
  camera-opened (or resource-lost) signal, not part of normal torch use.

## 3. The QS tile is a setTorchMode client

SystemUI's flashlight tile has no privileged path; it toggles
`CameraManager.setTorchMode` and derives its enabled state from the
`TorchCallback` availability stream (same public API). Samsung "Modes and
Routines" is likewise an external torch client — the reporter observed it
losing control exactly when the tile locks, which is consistent with both
being served by the same exclusive rule. "Locked and disabled" in the QS panel
is therefore not a separate system setting: it is the tile reacting to
`onTorchModeUnavailable` (One UI) or to camera-use state (AOSP-family, §4),
both caused by LensCast holding the flash-owning camera open.

## 4. Experiment (dev POCO F1, 2026-11-16)

Device: POCO F1 (beryllium), LineageOS 22 / Android 15, SDK 35, LensCast
installed. Method: `dumpsys media.camera` for the camera map and active
clients; `cmd statusbar expand-settings` + `screencap` for the tile state;
`input tap` on the app's lens chips to switch cameras.

**Camera map** (from `dumpsys media.camera` static info; `strengthMaximumLevel`
where present):

| ID | Facing | Flash unit | strengthMaximumLevel |
|----|--------|------------|----------------------|
| 0  | Back (Wide/Main) | TRUE | 1 |
| 1  | Front | FALSE | — |
| 2  | Back (Ultra-Wide) | FALSE | — |
| 3  | Back | TRUE | 1 |
| 4  | Back | TRUE | 1 |
| 5  | Front | FALSE | — |

Same bimodal-Samsung story as `torch-strength.md` §"On the reporter's Samsung",
seen here on Xiaomi hardware: flash units exist, but every one reports a
single strength level, so the POCO F1 is a binary-torch device throughout.

**Observations:**

1. *No camera open* (`Active Camera Clients: []`): Flashlight tile enabled,
   subtitle "Off".
2. *LensCast bound to camera 0 (Wide — flash-owning)*: tile dimmed, subtitle
   **"Camera in use"**, green camera-use chip in the shade, "1 app is active"
   privacy chip. The OS itself states the reason on the tile.
3. *LensCast bound to camera 1 (Front — NO flash unit)*: tile **still** dimmed
   with "Camera in use". AOSP-family SystemUI here keys the lock off ANY open
   camera, not off flash ownership.
4. *Camera released (HOME)*: tile returns to enabled/"Off".

This refines the model: the framework layer (§1) is per-flash-unit, but the
SystemUI layer that greys the tile is ROM policy, and ROMs differ:

- **One UI (reporter's Samsung, Android 13):** locks only while the
  flash-owning camera is open → Ultra-Wide streams keep the tile usable. This
  is what the reporter observed and wants preserved.
- **AOSP-family (POCO F1, LineageOS 22):** locks whenever any camera is open →
  even the Ultra-Wide case would lock on this ROM.

Side note from the same session: relaunching the app via adb right after HOME
hit the while-in-use policy ("Camera disabled by policy (while-in-use
denial)") — the same REJECT line visible in this device's camera events log
from earlier testing. A cold start clears it. Unrelated to the torch, recorded
here so the failure signature is findable.

## 5. setTorchMode as the streaming torch path: closed off

The spike's driving question — *would driving the torch via
`CameraManager.setTorchMode` instead of `FLASH_MODE_TORCH` keep the QS tile
available?* — has a documented answer that makes the experiment moot:

- `setTorchMode` exists precisely to control the flash **without opening the
  camera device** (its one-line summary), and throws `CAMERA_IN_USE` when the
  camera is in use (§1). Streaming requires the camera open. Therefore
  `setTorchMode` is not an alternative in-session torch path at all — there is
  no public API that lights the Main-lens LED from outside its own session.
- In-session, `FLASH_MODE_TORCH` (what CameraX `enableTorch` drives, what
  LensCast uses) is the only mechanism.
- Conclusion: the Main-lens QS lock is a framework rule about exclusive flash
  ownership, not an implementation choice. **No behavior change.** And a
  standing constraint: do not adopt `setTorchMode` for streaming torch — it
  would fail with `CAMERA_IN_USE` on every device and could only regress the
  working Ultra-Wide case.

## 6. What remains open, and what it feeds

Nothing on #16c remains open — the behavior is fully explained and the
preservation ask is satisfied by not touching the current design.

One adjacent unknown feeds **#16b** instead (it surfaced during this spike):
`turnOnTorchWithStrengthLevel` is documented to throw `CAMERA_IN_USE` "if the
camera device is in use", and CTS only exercises it with no camera open — but
LensCast calls it **while its own CameraX session is bound**
(`CameraService.applyTorchStrength`, resolved through `Camera2CameraInfo`).
Whether the framework serves that call to the client that owns the camera is
unverified on a multi-level device — the dev POCO F1 reports max=1 on every
flash unit, so `TorchStrengthPolicy` never reaches the call here. If the #16b
reporter's device reports a level > 1 *and* their logcat shows 0.2.1's
"Torch strength failed" line, that is this bug, and the fix is an in-session
strength strategy (the API-35 `CaptureRequest.FLASH_STRENGTH_LEVEL` request
key, or setting strength only while the session is closed). The new
`torchStrengthMaxLevel` field in `GET /api/system` plus the reporter's logcat
decides between that case and the benign max=1 case.
