pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// No foojay toolchains resolver: F-Droid's scanner rejects it, and the
// daemon JVM (see gradle/gradle-daemon-jvm.properties, JDK 21) is present
// on both CI and dev machines, so provisioning is never needed.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LensCast"
include(":app")
