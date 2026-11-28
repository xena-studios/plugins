import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xBackup's logic, for every platform: its config, messages, commands, the repository's formats, retention and the restore's rules. Nothing from java.* or a platform."

kotlin {
    // IOException is java.io's on the JVM (an expect class with an actual typealias).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":xbackup:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // The engine on the JVM, for the Bukkit and Fabric adapters and the command line: the
        // repository on disk, the scanner, restores, the remotes (S3, WebDAV and the drives over
        // HTTP; SFTP and FTP through libraries fetched when a remote of that type is used).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
            compileOnly(libs.jsch)
            compileOnly(libs.commons.net)
        }
        jvmTest.dependencies {
            // The family test: xMoney's and xBans' own tables, on their own databases. Their
            // jars, not the projects: all three are co.xenastudios:common, which Gradle would
            // take for this one.
            implementation(files("../../xmoney/common/build/libs/common-jvm.jar").builtBy(":xmoney:common:jvmJar"))
            implementation(files("../../xbans/common/build/libs/common-jvm.jar").builtBy(":xbans:common:jvmJar"))
            implementation(libs.sqlite.jdbc)
            implementation(libs.jsch)
            implementation(libs.commons.net)
        }
    }
}

// Tests can't hang: a stuck one fails the build in minutes, never holds CI.
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(10)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    timeout = Duration.ofMinutes(5)
}

// docs/src/generated/xbackup/manifest.json: commands, permissions, config, messages, events and
// the platform table, from XBackupReference (its tests build the plugin on the fake platform).
xenaReference {
    pluginId = "xbackup"
    source = "co.xenastudios.xbackup.XBackupReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "1.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "1.0.0")
    platform("spigot", "Spigot", "1.8.8", java = 8, since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "2.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
    platform("hytale", "Hytale", "latest", status = "planned")
}
