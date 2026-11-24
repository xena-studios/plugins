// platform-fabric: the core on Fabric, one jar per Minecraft line through Stonecutter, and
// xSample built as a Fabric mod on it (sandbox/), for boot tests. Run it from the repo's root
// with `./gradlew -p core/platform-fabric <task>`.
//
// A build of its own rather than a project of the root build (DECISIONS.md, "platform-fabric"):
// Loom sets Minecraft up for every version while the build is configured, which would slow every
// build of today's plugins and, on a cold CI cache, download Minecraft for them; and Loom adds
// repositories to its projects, which the root build forbids. The core's modules come from the
// root build, included below, so they're built from source here too.
pluginManagement {
    // The rewrite's one Kotlin version, from the root build's catalog, so the two never differ.
    val kotlinVersion = file("../../gradle/libs.versions.toml").readLines()
        .first { it.startsWith("kotlin = ") }.substringAfter('"').substringBefore('"')
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
    plugins {
        kotlin("jvm") version kotlinVersion
    }
}

plugins {
    // One source for every Minecraft line: comments in the code switch per version.
    id("dev.kikugie.stonecutter") version "0.10"
    // Applies fabric-loom-remap below 26.1 (obfuscated, so Mojang's names are mapped) and
    // fabric-loom on 26.1 and later (unobfuscated). Proven in M0 (sandbox/m0/mods).
    id("dev.kikugie.loom-back-compat") version "0.4.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // The Minecraft lines, oldest first: one jar each (DECISIONS.md, M0). 1.20.1 is the
        // floor (Forge's last line, and the oldest Fabric the roadmap supports), 26.3 the newest.
        // A new line is a version here and its block in stonecutter.properties.toml.
        versions("1.20.1", "1.21.11", "26.3")
        vcsVersion = "26.3"
        // xSample as a Fabric mod on this adapter, never released: a node per line, like these.
        branch("sandbox")
    }
}

rootProject.name = "platform-fabric"

// The repo's own build, for the core's modules: Gradle builds them from source when a version
// node needs them, so a change to the core is seen here at once.
includeBuild("../..") {
    name = "xena-plugins"
    dependencySubstitution {
        for (module in listOf("core-api", "core-common", "core-jvm", "core-loader", "core-testing")) {
            substitute(module("co.xenastudios.core:$module")).using(project(":core:$module"))
        }
        substitute(module("co.xenastudios.core:sample")).using(project(":core:core-testing:sample"))
    }
}
