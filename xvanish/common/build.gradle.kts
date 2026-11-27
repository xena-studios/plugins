import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xVanish's logic, for every platform: its config, messages, who's hidden from whom, the commands, network mode and the placeholders. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xvanish:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml, lang/ and SuperVanish's files from disk, and the network's database
        // over JDBC, for the JVM platforms' adapters (Bukkit, Fabric).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            // Network mode's tests run on a real SQLite database.
            runtimeOnly(libs.sqlite.jdbc)
        }
    }
}

// Tests can't hang: a stuck one fails the build in minutes.
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(5)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    timeout = Duration.ofMinutes(5)
}

// docs/src/generated/xvanish/manifest.json: commands, permissions, config, messages,
// placeholders, events and the platform table, from XVanishReference.
xenaReference {
    pluginId = "xvanish"
    source = "co.xenastudios.xvanish.XVanishReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "1.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "1.0.0")
    platform("spigot", "Spigot", "1.8.8", java = 8, since = "1.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "1.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
    platform("hytale", "Hytale", "latest", status = "planned")
}
