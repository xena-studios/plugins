import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xPlots' logic, for every platform: its config, messages, fields and plots, the database, renting and renewals, signs, snapshots and resets, protection's decisions, menus and commands. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xplots:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml and lang/ from disk, the database over JDBC, snapshots' deflate, and
        // xMoney's, xTeleport's, xRewind's and xVanish's APIs, for the JVM platforms' adapters.
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            implementation(project(":core:core-testing"))
            // The plugin's tests run on a real SQLite database, the database 1.x left included.
            runtimeOnly(libs.sqlite.jdbc)
        }
    }
}

// Tests can't hang: a stuck one fails the build in minutes, never holds CI.
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(5)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    timeout = Duration.ofMinutes(5)
}

// docs/src/generated/xplots/manifest.json: commands, permissions, config, messages, events and
// the platform table, from XPlotsReference (its tests build the plugin on the fake platform).
xenaReference {
    pluginId = "xplots"
    source = "co.xenastudios.xplots.XPlotsReference"
    platform("paper", "Paper", "1.13", java = 8, since = "2.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "2.0.0")
    platform("spigot", "Spigot", "1.13", java = 8, since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, status = "planned")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
}
