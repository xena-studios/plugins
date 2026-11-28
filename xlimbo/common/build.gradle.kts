import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xLimbo's logic, for every platform: its config, messages, the return queue and its status ping, the commands and the API. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xlimbo:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml and lang/ from disk, and the status ping's socket, for the JVM adapter (Paper).
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

// docs/src/generated/xlimbo/manifest.json: commands, permissions, config, messages, events and the
// platform table, from XLimboReference.
xenaReference {
    pluginId = "xlimbo"
    source = "co.xenastudios.xlimbo.XLimboReference"
    platform("paper", "Paper", "1.21.11", java = 21, since = "1.0.0")
    platform("velocity", "Velocity", "latest", status = "planned")
}
