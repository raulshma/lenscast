# Releasing LensCast on F-Droid

This project is set up for the official F-Droid repository (f-droid.org).

## How the F-Droid build works

- The `fdroid` Gradle flavor (`app/build.gradle.kts`) ships without the
  self-updater: `SELF_UPDATE=false` and no `REQUEST_INSTALL_PACKAGES`
  (that permission lives only in the store flavor's manifest).
- ABI splits are disabled automatically whenever a `fdroid`-flavor task is
  requested, so `assembleFdroidRelease` produces exactly one universal APK
  (multiple APKs sharing a versionCode break fdroid packaging).
- The web UI is built from source during the Gradle build
  (`npm ci` → typecheck → test → vite build). The F-Droid build server
  provides Node.js/npm; dependencies come from the committed lockfile.
- The optional ML model (EfficientDet-Lite0, Apache-2.0) is **not** bundled;
  users can download it at runtime from TensorFlow's official storage with a
  pinned SHA-256 (`DetectionModelStore.kt`). This is declared in the
  submission so reviewers don't flag it as a binary artifact.
- `versionCode` follows `major*10_000 + minor*1_000 + patch*10 + abiIndex`
  (abiIndex: armeabi-v7a=1, arm64-v8a=2, x86_64=3; 0.1.2 → 1021/1022/1023)
  and must be bumped in `app/build.gradle.kts` defaults on **every** release,
  because F-Droid builds the tag with plain `assembleFdroidRelease` + an
  `abiFilter` gradleprop (no `-PversionCode` override). CI (`release.yml`)
  derives and passes the same value for GitHub releases.

## Submitting / updating

After the initial submission, releases need **no manual F-Droid step**: the
recipe sets `AutoUpdateMode: Version` with `UpdateCheckMode: Tags`, so
fdroidserver's auto update checker picks up each new `v<version>` tag,
parses the `versionCode`/`versionName` literals from `app/build.gradle.kts`
at the tag, and generates one build per `VercodeOperation` entry
(`%c - 2` / `%c - 1` / `%c` → the three ABI codes). No fdroiddata MR is
needed per release; the build lands in the repo a few days after the tag
is pushed.

Per release:

1. Cut the release the usual way: bump the `versionCode`/`versionName`
   defaults in `app/build.gradle.kts`, add the changelog at
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
   commit, and push the `v<version>` branch (CI tags and publishes).
2. Keep the repo-side mirror `fdroid/com.raulshma.lenscast.yml` in sync:
   add `Builds` entries for the new tag and bump
   `CurrentVersion`/`CurrentVersionCode`. These mirror what the checker
   generates (same flavor, ABI splits and gradleprops); the pinned commit
   is repo-side documentation only.

Initial submission (once): open a merge request against
[fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding the
recipe as `metadata/com.raulshma.lenscast.yml`, plus
`metadata/com.raulshma.lenscast/en-US/icon.png` (and changelog files).
You need a GitLab account; fork fdroiddata, commit, MR. In the MR
description, mention:

- The runtime model download (Apache-2.0, SHA-pinned, optional feature).
- That the fdroid flavor excludes the self-updater.

Maintainers run `fdroid checkbuild` / verify on their build server; the
app appears in the repo after the next index signing (typically a few
days after merge).

## Local verification

```bash
./gradlew assembleFdroidRelease
# expect exactly one APK: app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

Optionally with [fdroidserver](https://gitlab.com/fdroid/fdroidserver)
installed, `fdroid readmeta` and `fdroid build com.raulshma.lenscast` can be
run against a local metadata copy for a full dress rehearsal.
