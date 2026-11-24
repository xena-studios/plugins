// M0 spike: one Kotlin source set, a server-side Fabric mod built for several Minecraft
// versions with Stonecutter. Self-contained: not part of the root build.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
    plugins {
        kotlin("jvm") version "2.4.21"
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.10"
    // Applies fabric-loom-remap below 26.1 (obfuscated) and fabric-loom on 26.1+ (not).
    id("dev.kikugie.loom-back-compat") version "0.4.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        versions("1.20.1", "1.21.11", "26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "m0-mods"
