
// LensCast Wear — the watch-side remote (:wear). A standalone `application`
// module: the integrator only adds `include(":wear")` to settings.gradle.kts.
//
// The version catalog (gradle/libs.versions.toml) is shared by :app and is
// intentionally NOT touched by this module, so every plugin and dependency
// version below is declared INLINE, matching the root versions exactly
// (AGP 9.4.0, Kotlin 2.2.10 — see gradle/libs.versions.toml). If the root
// build ever bumps a pin, this file must follow.
plugins {
    id("com.android.application") version "9.4.0" apply true
    // The Compose compiler plugin rides AGP 9's built-in Kotlin (the module
    // deliberately does NOT apply org.jetbrains.kotlin.android — mirroring
    // :app's plugin set).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply true
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
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))

    // Wear core + Wear Compose (stable 1.4.0 — verified present on Google
    // Maven; NOT the Material 3 alpha line). compose-material is the
    // M2-flavored Wear MaterialTheme (Scaffold/TimeText/ScalingLazyColumn);
    // compose-foundation adds the rotary/lazy building blocks beneath it.
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-material:1.4.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")

    // Watch-side settings persistence (host, port, credentials).
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Plain OkHttp for the phone's HTTP API — small, no image loader (the
    // snapshot decodes through BitmapFactory), no MOSHI (org.json parses the
    // two tiny status payloads).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
