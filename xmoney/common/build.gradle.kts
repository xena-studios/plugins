plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xMoney's logic, for every platform: its config, messages, ledger, commands, imports and placeholders. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xmoney:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml and lang/ from disk, and the database over JDBC, for the JVM
        // platforms' adapters (Bukkit, Fabric).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            // The ledger's tests run on real databases: SQLite always, MariaDB with XR_MYSQL.
            runtimeOnly(libs.sqlite.jdbc)
            runtimeOnly(libs.mysql.connector)
        }
    }
}

// KillTest runs a ledger in a JVM of its own, so it can kill it mid-write.
tasks.named<Test>("jvmTest") {
    val test = kotlin.jvm().compilations.getByName("test")
    val classpath = test.output.allOutputs + test.runtimeDependencyFiles!!
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dxmoney.testClasspath=" + classpath.asPath) })
}

// docs/src/generated/xmoney/manifest.json: commands, permissions, config, messages,
// placeholders, events and the platform table, from XMoneyReference (its tests build the plugin
// on the fake platform).
xenaReference {
    pluginId = "xmoney"
    source = "co.xenastudios.xmoney.XMoneyReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "2.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "2.0.0")
    platform("spigot", "Spigot", "1.8.8", java = 8, since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "2.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
    platform("hytale", "Hytale", "latest", status = "planned")
}
