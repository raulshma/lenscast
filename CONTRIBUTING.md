# Contributing to LensCast

Thanks for your interest in improving LensCast. This guide covers the
practical parts: getting a build running, running the tests, finding your
way around the code, and shipping a release.

## Read CONTEXT.md first

Before touching code, read [CONTEXT.md](CONTEXT.md). It is the project's
domain glossary — the vocabulary (stream outputs, detection events, the
auth gate, contract fixtures, …) that code comments, commit messages, and
issue discussions all assume. It is far cheaper than reverse-engineering
the domain from source.

## Prerequisites

- **JDK 21** — the Gradle daemon toolchain is pinned to 21
  (`gradle/gradle-daemon-jvm.properties`); older JDKs will fail daemon
  criteria checks.
- **Node.js 20+ with npm** — the web UI is built from source during the
  Gradle build (Vite output lands in `app/src/main/assets/webui/`).
- Android SDK (Android Studio or command-line tools); `compileSdk = 36`.

## Build

```bash
# Web UI + Android app together (Gradle builds the web UI automatically)
./gradlew assembleDebug

# F-Droid flavor (no self-updater)
./gradlew assembleFdroidDebug

# Wear OS companion
./gradlew :wear:assembleDebug
```

## Test

CI runs all of these on every PR and every push to `main`; a change is not
done until they pass locally:

```bash
# Android JVM unit tests — both flavors
./gradlew :app:testStoreDebugUnitTest
./gradlew :app:testFdroidDebugUnitTest

# Wear companion
./gradlew :wear:testDebugUnitTest

# Web UI
cd web
npm ci
npm run typecheck   # tsc --noEmit
npm run test        # vitest run
npm run build       # production build (also runs as part of Gradle)
```

## Lint

CI also runs `./gradlew :app:lintStoreDebug` on every PR. Lint is
baseline-gated: the findings the tree already carries live in the checked-in
`app/lint-baseline.xml`, and any **new** finding fails the build
(`abortOnError`). When you add or change code, regenerate the baseline with
`./gradlew :app:updateLintBaseline` and commit the refreshed file alongside
your change — never hand-edit it, and never delete it to make the build pass.

## Code conventions

**Pure policies.** Decision logic that doesn't need Android framework
objects belongs in a pure `object …Policy` (see
`app/src/main/java/com/raulshma/lenscast/core/BatteryQualityPolicy.kt` for
the reference shape): plain constants for the thresholds, a pure `resolve`
function, no `Context`, no `Log`. The Android-facing class
(`PowerManager`, `ThermalMonitor`, …) keeps only the receivers and sensor
reads and delegates every decision. This is what makes the logic
JVM-testable.

**Test conventions.** Every pure policy gets a mirroring JVM unit test
(`app/src/test/...`, JUnit 4, backtick method names — see
`BatteryQualityPolicyTest.kt`): cover each tier, then the boundary pairs
between tiers. Parser/responder code gets a `…FuzzTest` with randomized
inputs. Web logic is tested by vitest under `web/src/`.

**Defaults and bounds live in `StreamDefaults`.** Never re-copy a default
literal or a `(min, max)` bound — reference
`app/src/main/java/com/raulshma/lenscast/core/StreamDefaults.kt` so a
change lands everywhere at once. Policy-internal tier constants are the
one exception: they live inside their policy object.

**Contract fixtures must stay in sync.** The REST API contract is pinned
by fixtures consumed from three sides:

- Kotlin DTOs (`app/src/main/java/com/raulshma/lenscast/streaming/model/`),
  exercised by `DtoContractFixtureTest.kt`
- JSON fixtures (`web/contract/*.json`)
- TypeScript types (`web/src/types.ts`), checked by `web/src/contract.test.ts`

If you change a DTO field, update all three in the same change — a
drift fails CI, but only after review time is wasted.

## Commit / PR hygiene

- Explain *why*, not just what — the codebase leans on comments that
  record reasoning and trade-offs; match that in commit messages.
- Keep PRs scoped; feature work and mechanical churn don't mix well here.
- Don't commit `local.properties`, keystores, or `lenscast-release.jks`
  changes.

## Release checklist

Releases are cut from `v<version>` branches; `release.yml` builds one
signed store-flavor APK per ABI and publishes a draft GitHub Release.
Before pushing the branch:

1. Bump the `versionCode` and `versionName` **literals** in
   `app/build.gradle.kts` (`defaultConfig`). The literal is the x86_64
   code (`major*10_000 + minor*1_000 + patch*10 + 3`); F-Droid's
   update checker parses it, so it must be the real value for the tag.
2. Add the changelog at
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, where
   `<versionCode>` is the x86_64 code (`base + 3`). The workflow **fails**
   if this file is missing — an empty release body is never published
   silently.
3. Commit, then push the `v<major>.<minor>.<patch>` branch. CI runs the
   full test suite on it; `release.yml` then tags and publishes the draft.
4. Verify the draft release's assets, then publish it.
5. For F-Droid: update `fdroid/com.raulshma.lenscast.yml` (see
   [docs/fdroid.md](docs/fdroid.md) for the full procedure).

## Cutting a beta

Between stable releases, the beta channel (`.github/workflows/beta.yml`)
publishes signed store-flavor APKs from `main` as GitHub **pre-releases**
(tag `v<version>-beta.<n>`). To cut one:

1. Make sure `main` is green and contains what you want tested.
2. Push a `beta/<version>` branch (e.g. `beta/0.2.2`) or dispatch the
   workflow with an explicit `version_name` (e.g. `0.3.0-beta.1`). With no
   input the target is the Gradle literal's patch + 1, and the counter is
   one past the highest existing `v<target>-beta.*` tag.
3. Skim the published pre-release — its body carries the tester-facing
   install notes.

Betas need no literal bump and no fastlane changelog: they ride the
workflow's `-PversionName`/`-PversionCode` overrides. Their versionCodes
occupy the free slots between the last stable (x86_64 literal `L`) and the
next patch release's codes (`L+1`..`L+3`): beta cut *n* takes
`L+3n-2`..`L+3n`, one code per ABI — exactly **three** betas fit per
patch cycle. A fourth would collide with the next stable's codes (Android
blocks downgrades, so that ABI's testers would be stranded until the
release after), and the workflow refuses to cut it — ship the stable or
bump the literals first.

Beta tags stay invisible outside GitHub pre-releases with no fdroid-side
change: F-Droid's Tags-mode checker parses the Gradle literals at the
tagged commit, and beta commits carry the last shipped stable's literals —
so every beta tag reads back as "up to date" and generates no F-Droid
build. That relies on each release's literal bump landing on `main`
(see `release.yml`'s literal gate); cut betas from an up-to-date `main`.
One guard is still needed in-repo: `release.yml` filters its "next patch"
tag glob to stable-shaped tags, so `sort -V` cannot rank a beta tag above
the last stable and skew the next release's version.

## Licensing

By contributing, you agree your contributions are licensed under the
project's [GPL-3.0-or-later license](LICENSE).
