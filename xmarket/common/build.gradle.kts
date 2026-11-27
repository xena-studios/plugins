import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xMarket's logic, for every platform: its config, messages, the price list and its recipes, the loop audit, limits, trades, menus, history, imports. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xmarket:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml, shops/, lang/ and other shops' files from disk, the database over
        // JDBC, and xMoney's API, for the JVM platforms' adapters (Bukkit, Fabric).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            runtimeOnly(libs.sqlite.jdbc)
        }
    }
}

// The price list's sources (xmarket/prices/), which the generator's test reads; with
// -PupdateGolden it writes the shipped shops/*.yml from them instead of failing.
val prices = layout.projectDirectory.dir("../prices")
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(5)
    inputs.dir(prices).withPropertyName("prices")
    systemProperty("xmarket.prices", prices.asFile.absolutePath)
    systemProperty("xmarket.shops", layout.projectDirectory.dir("src/commonMain/resources/xmarket/shops").asFile.absolutePath)
    systemProperty("xena.updateGolden", providers.gradleProperty("updateGolden").isPresent)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    timeout = Duration.ofMinutes(5)
}

// docs/src/generated/xmarket/manifest.json: commands, permissions, config, messages,
// placeholders, events and the platform table, from XMarketReference.
xenaReference {
    pluginId = "xmarket"
    source = "co.xenastudios.xmarket.XMarketReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "1.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "1.0.0")
    platform("spigot", "Spigot", "1.8.8", java = 8, since = "1.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "1.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
}
