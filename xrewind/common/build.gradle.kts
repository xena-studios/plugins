import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xRewind's logic, for every platform: its config, messages, the log and its writer, lookups, rollbacks, purges and the CoreProtect import. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xrewind:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml and lang/ from disk, the database over JDBC, and CoreProtect's
        // database, for the JVM platforms' adapters (Bukkit, Fabric).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            implementation(project(":core:core-testing"))
            // The plugin's tests run on a real SQLite database; MySqlTest on MariaDB with XR_MYSQL.
            runtimeOnly(libs.sqlite.jdbc)
            runtimeOnly(libs.mysql.connector)
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

// docs/src/generated/xrewind/manifest.json: commands, permissions, config, messages, events and
// the platform table, from XRewindReference (its tests build the plugin on the fake platform).
xenaReference {
    pluginId = "xrewind"
    source = "co.xenastudios.xrewind.XRewindReference"
    platform("paper", "Paper", "1.13", java = 8, since = "2.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "2.0.0")
    platform("spigot", "Spigot", "1.13", java = 8, since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "2.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
}
