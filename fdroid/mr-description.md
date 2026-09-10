Author-submitted new app: LensCast — turns an Android phone into a networked camera (web/MJPEG/RTSP streaming, remote web dashboard, detection & automation). GPLv3.

## Required

- [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy) — all dependencies FOSS (Apache-2.0/BSD/MIT), no proprietary binaries, builds fully from source
- [x] The original app author has been notified — I am the author
- [x] No existing fdroiddata/RFP issues found for this app
- [x] Builds with `fdroid build` and all pipelines pass — verified in this MR's pipeline (build + scanner green; the web UI is built during the Gradle build via `npm ci`, Node.js installed via the recipe's pinned `sudo:` step)
- [x] Issue tracker and contact: https://github.com/raulshma/lenscast/issues

## Notes for reviewers

- The optional ML object-detection model (EfficientDet-Lite0, **Apache-2.0**) is **not bundled**: it is downloaded only on explicit user request at runtime from TensorFlow's official storage, integrity-checked against a pinned SHA-256 (`DetectionModelStore.kt`). The detection gate is an optional feature; the app is fully functional without it.
- The `fdroid` Gradle flavor ships **without the in-app self-updater** (`SELF_UPDATE=false`); `REQUEST_INSTALL_PACKAGES` exists only in the store flavor's manifest and is not merged into this build.
- One **universal APK** deliberately: ABI splits are auto-disabled for fdroid-flavor Gradle tasks (`app/build.gradle.kts`), so `assembleFdroidRelease` yields a single APK with the right versionCode.
- Summary/description/changelogs/icon live in the app repo under `fastlane/metadata/android/en-US/` and are maintained there.

## Strongly Recommended

- [x] Fastlane metadata in the upstream repo
- [x] Releases tagged (`v%v`) with `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`

## Suggested

Reproducible Builds: No, I don't want this — F-Droid signing is fine for now.
Multiple APKs for native code: not applicable — one universal APK deliberately (see notes).
