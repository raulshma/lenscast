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
- `versionCode` follows `major*1_000_000 + minor*1_000 + patch` and must be
  bumped in `app/build.gradle.kts` defaults on **every** release, because
  F-Droid builds the tag with plain `assembleFdroidRelease` (no `-P`
  overrides). CI (`release.yml`) derives and passes the same value for
  GitHub releases.

## Submitting / updating

1. Cut the release the usual way: bump the `versionCode`/`versionName`
   defaults in `app/build.gradle.kts`, add the changelog at
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
   commit, and push the `v<version>` branch (CI tags and publishes).
2. Update `fdroid/com.raulshma.lenscast.yml`: add a `Builds` entry for the
   new tag and bump `CurrentVersion`/`CurrentVersionCode`.
3. Open (or update) a merge request against
   [fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding the
   recipe as `metadata/com.raulshma.lenscast.yml`, plus
   `metadata/com.raulshma.lenscast/en-US/icon.png` (and changelog files).
   You need a GitLab account; fork fdroiddata, commit, MR.
4. In the MR description, mention:
   - The runtime model download (Apache-2.0, SHA-pinned, optional feature).
   - That the fdroid flavor excludes the self-updater.
5. Maintainers run `fdroid checkbuild` / verify on their build server; the
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
