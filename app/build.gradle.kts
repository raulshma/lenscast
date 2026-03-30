plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val installWebDeps by tasks.registering(Exec::class) {
    description = "Installs web UI npm dependencies if needed"
    group = "build"

    workingDir = file("${rootProject.projectDir}/web")
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        commandLine("cmd", "/c", "npm", "install")
    } else {
        commandLine("npm", "install")
    }

    inputs.file(file("${rootProject.projectDir}/web/package.json"))
    inputs.file(file("${rootProject.projectDir}/web/package-lock.json"))
    outputs.dir(file("${rootProject.projectDir}/web/node_modules"))
}

val buildWebUi by tasks.registering(Exec::class) {
    description = "Builds the SolidJS web UI into Android assets"
    group = "build"

    dependsOn(installWebDeps)

    workingDir = file("${rootProject.projectDir}/web")
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        commandLine("cmd", "/c", "npm", "run", "build")
    } else {
        commandLine("npm", "run", "build")
    }

    val webSrc = file("${rootProject.projectDir}/web/src")
    val webPkg = file("${rootProject.projectDir}/web/package.json")
    val webVite = file("${rootProject.projectDir}/web/vite.config.ts")
    inputs.dir(webSrc)
    inputs.file(webPkg)
    inputs.file(webVite)
    outputs.dir(file("src/main/assets/webui"))

    isIgnoreExitValue = true

    doLast {
        val result = executionResult.get()
        if (result.exitValue != 0) {
            throw GradleException(
                """
                |Web UI build failed (exit code ${result.exitValue}).
                |Make sure Node.js and npm are installed, then run:
                |  cd web && npm install && npm run build
                |Then rebuild the Android project.
                """.trimMargin()
            )
        }
    }
}

android {
    namespace = "com.raulshma.lenscast"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystoreExists = file("lenscast-release.jks").exists()
            storeFile = if (keystoreExists) file("lenscast-release.jks") else null
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.raulshma.lenscast"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = project.findProperty("versionName") as String? ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("Assets")
}.configureEach {
    dependsOn(buildWebUi)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildWebUi)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.video)
    implementation(libs.camerax.extensions)

    implementation(libs.work.manager)
    implementation(libs.nanohttpd)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.guava)
    implementation(libs.moshi)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
