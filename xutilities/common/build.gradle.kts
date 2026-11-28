plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xUtilities' logic, for every platform: its config, messages, commands and /back. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xutilities:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml and lang/ from disk, for the JVM platforms' adapters (Bukkit, Fabric, NeoForge).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
    }
}

// docs/src/generated/xutilities/manifest.json: commands, permissions, config, messages, events
// and the platform table, from XUtilitiesReference (its tests build the plugin on the fake platform).
xenaReference {
    pluginId = "xutilities"
    source = "co.xenastudios.xutilities.XUtilitiesReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "3.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "3.0.0")
    platform("spigot", "Spigot", "1.8.8", java = 8, since = "3.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "3.0.0")
    platform("neoforge", "NeoForge", "1.21.1", java = 21, since = "3.0.0")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
}
