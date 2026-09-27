pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "subscription-watchdog"

// Pure-Kotlin SMS parsing + renewal engine. Builds and tests without an Android SDK.
include(":core")

// The Android app needs an SDK; skip it when none is configured so `./gradlew :core:test` still works.
val localProperties = java.util.Properties().apply {
    val f = File(settingsDir, "local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    localProperties.getProperty("sdk.dir") != null
if (hasAndroidSdk) {
    include(":app")
} else {
    println("No Android SDK found (set ANDROID_HOME or sdk.dir in local.properties) - configuring :core only.")
}
