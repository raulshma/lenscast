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

/**
 * Registers one `npm run <script>` Exec task for the web UI, sharing the
 * Windows cmd shim, the caching inputs, and the exit-code failure ladder.
 */
fun Project.npmScriptTask(
    name: String,
    script: String,
    description: String,
    dependsOnTask: TaskProvider<*>,
    failureHeading: String,
    failureCommand: String,
    failureFooter: String,
    configureCache: Exec.() -> Unit,
): TaskProvider<Exec> = tasks.register(name, Exec::class) {
    this.description = description
    group = "build"

    dependsOn(dependsOnTask)

    workingDir = file("${rootProject.projectDir}/web")
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        commandLine("cmd", "/c", "npm", "run", script)
    } else {
        commandLine("npm", "run", script)
    }

    configureCache()

    isIgnoreExitValue = true

    doLast {
        val result = executionResult.get()
        if (result.exitValue != 0) {
            throw GradleException(
                """
                |$failureHeading (exit code ${result.exitValue}).
                |Make sure Node.js and npm are installed, then run:
                |  cd web && $failureCommand
                |$failureFooter
                """.trimMargin()
            )
        }
    }
}

val checkWebUiTypes = npmScriptTask(
    name = "checkWebUiTypes",
    script = "typecheck",
    description = "Type-checks the web UI TypeScript (tsc --noEmit); fails the build on error",
    dependsOnTask = installWebDeps,
    failureHeading = "Web UI typecheck failed",
    failureCommand = "npm run typecheck",
    failureFooter = "and fix the reported type errors before rebuilding.",
) {
    val webSrc = file("${rootProject.projectDir}/web/src")
    val webContract = file("${rootProject.projectDir}/web/contract")
    val webTsConfig = file("${rootProject.projectDir}/web/tsconfig.json")
    inputs.dir(webSrc)
    inputs.dir(webContract)
    inputs.file(webTsConfig)
    inputs.file(file("${rootProject.projectDir}/web/package.json"))
}

val testWebUi = npmScriptTask(
    name = "testWebUi",
    script = "test",
    description = "Runs the web UI vitest suite (DTO contract fixtures + logic); fails the build on error",
    dependsOnTask = checkWebUiTypes,
    failureHeading = "Web UI tests failed",
    failureCommand = "npm run test",
    failureFooter = "and fix the reported failures before rebuilding.",
) {
    val webSrc = file("${rootProject.projectDir}/web/src")
    val webContract = file("${rootProject.projectDir}/web/contract")
    val webVitest = file("${rootProject.projectDir}/web/vitest.config.ts")
    inputs.dir(webSrc)
    inputs.dir(webContract)
    inputs.file(webVitest)
    inputs.file(file("${rootProject.projectDir}/web/package.json"))
}

val buildWebUi = npmScriptTask(
    name = "buildWebUi",
    script = "build",
    description = "Builds the SolidJS web UI into Android assets",
    dependsOnTask = testWebUi,
    failureHeading = "Web UI build failed",
    failureCommand = "npm install && npm run build",
    failureFooter = "Then rebuild the Android project.",
) {
    val webSrc = file("${rootProject.projectDir}/web/src")
    val webPkg = file("${rootProject.projectDir}/web/package.json")
    val webVite = file("${rootProject.projectDir}/web/vite.config.ts")
    inputs.dir(webSrc)
    inputs.file(webPkg)
    inputs.file(webVite)
    outputs.dir(file("src/main/assets/webui"))
}

android {
    namespace = "com.raulshma.lenscast"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystorePath = rootProject.file("lenscast-release.jks")
            val keystoreExists = keystorePath.exists()
            storeFile = if (keystoreExists) keystorePath else null
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.raulshma.lenscast"
        minSdk = 23
        targetSdk = 36
        // The in-app updater compares this value against the release channel;
        // a property override (or the CI's tag-driven bump) keeps releases
        // strictly increasing — F-Droid metadata requires it too.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 7
        versionName = project.findProperty("versionName") as String? ?: "0.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The `fdroid` flavor ships without the self-updater: F-Droid policy
    // forbids both side-loading channels (REQUEST_INSTALL_PACKAGES) and
    // app-managed updates. The `play`/`ci` default flavor keeps it.
    flavorDimensions += "store"
    productFlavors {
        create("store") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("fdroid") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null &&
                !releaseSigning.storePassword.isNullOrEmpty() &&
                !releaseSigning.keyAlias.isNullOrEmpty() &&
                !releaseSigning.keyPassword.isNullOrEmpty()
            ) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Plain-JVM unit tests hit android.util.Log in the streaming
            // monitors; return defaults instead of throwing "not mocked".
            isReturnDefaultValues = true
        }
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

    implementation(libs.work.manager)
    // LiteRT (TensorFlow Lite) Task Library for the ML object-detection gate
    // (capture/ml/). Ships to BOTH store flavors — it is a plain library, not
    // a Play-services dependency, so the fdroid flavor gains nothing proprietary.
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.ws)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.guava)
    implementation(libs.moshi)

    implementation(libs.coil.base)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
