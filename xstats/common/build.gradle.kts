import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xStats' logic, for every platform and the proxy: its config, messages, stats, the save, leaderboards, placeholders, the web API's pages, commands and the family's stats. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xstats:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // The website's server, the world's stats files, reading config.yml and lang/ from disk,
        // the database over JDBC and the family's stats by reflection, for the JVM platforms'
        // adapters (Bukkit, Fabric, Velocity).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            runtimeOnly(libs.sqlite.jdbc)
            runtimeOnly(libs.mysql.connector)
        }
    }
}

// The website is plain files in xstats/web/, with no build step of its own: the JVM jar serves
// them from web/ in its resources, and /xstats web export writes the same files.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(rootProject.layout.projectDirectory.dir("xstats/web")) { into("web") }
}

// Tests can't hang: a stuck one fails the build in minutes, never holds CI.
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(5)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    timeout = Duration.ofMinutes(5)
}

// docs/src/generated/xstats/manifest.json: commands, permissions, config, messages, events and
// the platform table, from XStatsReference (its tests build the plugin on the fake platform).
xenaReference {
    pluginId = "xstats"
    source = "co.xenastudios.xstats.XStatsReference"
    platform("paper", "Paper", "1.13", java = 8, since = "2.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "2.0.0")
    platform("spigot", "Spigot", "1.13", java = 8, since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "2.0.0")
    platform("velocity", "Velocity", "3.4", java = 21, since = "2.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
    platform("hytale", "Hytale", "latest", status = "planned")
}
