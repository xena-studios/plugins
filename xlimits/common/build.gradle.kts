import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xLimits' logic, for every platform: its config, messages, the limits and counts per chunk, farm slowing, load profiles and the commands. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xlimits:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml, lang/ and FarmControl's and Insights' files from disk, for the JVM
        // platforms' adapters (Bukkit, Fabric).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
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

// docs/src/generated/xlimits/manifest.json: commands, permissions, config, messages, events and
// the platform table, from XLimitsReference.
xenaReference {
    pluginId = "xlimits"
    source = "co.xenastudios.xlimits.XLimitsReference"
    platform("paper", "Paper", "1.13.2", java = 8, since = "1.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "1.0.0")
    platform("spigot", "Spigot", "1.13.2", java = 8, since = "1.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "1.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
}
