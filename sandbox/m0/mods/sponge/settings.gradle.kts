// M0 spike: a Kotlin Sponge API 8+ plugin with Cloud 2 and Sponge's own Adventure.
// Its own build (run with ../gradlew -p sponge), not part of the Stonecutter build above it.
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "m0-sponge"
