
// LensCast Wear — the watch-side remote (:wear). A standalone `application`
// module: the integrator only adds `include(":wear")` to settings.gradle.kts.
//
// Every plugin and dependency rides the shared version catalog
// (gradle/libs.versions.toml) with the same pins the root declares for :app
// (AGP, Kotlin compose plugin, Compose BOM) plus the wear-only entries
// (wear, wear-compose, wear-tiles, okhttp) — no second copy of a version
// anywhere in this file, so a root pin bump lands here with it.
plugins {
    alias(libs.plugins.android.application)
    // The Compose compiler plugin rides AGP 9's built-in Kotlin (the module
    // deliberately does NOT apply org.jetbrains.kotlin.android — mirroring
    // :app's plugin set).
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.raulshma.lenscast.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raulshma.lenscast.wear"
        // 26 is the oldest Wear OS 3 baseline; the watch fleet this remote
        // targets (Wear OS 3+) is all API 26+, so no multi-APK story exists.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Debug-signed by default (no signingConfig declared): the watch
            // APK is a side-load companion, not a Play artifact; CI can add a
            // signing config later without touching the source set.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // AGP embeds a Play-oriented dependency manifest in the APK signing
    // block ("Dependency metadata"); F-Droid's scanner rejects it. Mirrors
    // the :app module so a future fdroid recipe for :wear starts clean.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Compose BOM pinned to the same 2026.03.00 the root catalog pins; the
    // unversioned androidx.compose artifacts below inherit from it.
    implementation(platform(libs.compose.bom))

    // Wear core + Wear Compose (stable 1.4.0 — NOT the Material 3 alpha
    // line). compose-material is the M2-flavored Wear MaterialTheme
    // (Scaffold/TimeText/ScalingLazyColumn); compose-foundation adds the
    // rotary/lazy building blocks beneath it.
    implementation(libs.wear)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)

    // Watch-side settings persistence (host, port, credentials, alerts).
    implementation(libs.datastore.preferences)

    implementation(libs.coroutines.android)

    // Plain OkHttp for the phone's HTTP API — small, no image loader (the
    // snapshot decodes through BitmapFactory), no MOSHI (org.json parses the
    // small status/event payloads).
    implementation(libs.okhttp)

    // The Wear OS tile (stream-state mirror on the watch face carousel):
    // tiles = the TileService + protocol builders, tiles-material = the
    // ready-made layout components. Nothing beyond these two artifacts.
    implementation(libs.wear.tiles)
    implementation(libs.wear.tiles.material)

    testImplementation(libs.junit)
}
