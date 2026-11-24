import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The core on Fabric, built once per Minecraft line (settings.gradle.kts): this script runs for
// each version's node, with `sc.current` the version. Not a mod of its own: a plugin bundles it
// with the core, so its jar is in Mojang's names and the plugin's own jar is remapped.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
}

group = "co.xenastudios.core"
version = "0.0.0+${sc.current.version}"
description = "The rewrite's core on Fabric, server-side: one jar per Minecraft line."
base.archivesName = "platform-fabric"

// Minecraft's Java decides the bytecode, not the core's Java 8 (DECISIONS.md, M0): 17 for
// 1.20.1, 21 for 1.20.5 to 1.21.x, 25 for 26.1 and later.
val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    fun fabricApi(vararg modules: String) {
        for (module in modules) modImplementation(fabricApi.module(module, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Mojang's names on the obfuscated versions too, so one source reads the same everywhere.
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    // Lifecycle and tick events (the scheduler), commands, and players joining and leaving.
    fabricApi("fabric-api-base", "fabric-lifecycle-events-v1", "fabric-command-api-v2", "fabric-networking-api-v1")
    // The Kotlin stdlib, which the server gets from fabric-language-kotlin. Its POM names a
    // Fabric API too, for a newer Minecraft, which would win over this line's.
    modImplementation("net.fabricmc:fabric-language-kotlin:${sc.properties["deps.flk"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    // Permissions as LuckPerms and the other permission mods answer them. A plugin's jar
    // carries it inside (sandbox/), as every mod using it does. Its metadata brings a Fabric API
    // BOM for 1.21.3, which would lift this line's Fabric API, so that's left out.
    modImplementation("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }

    // The core, from the root build (settings.gradle.kts): its JVM side brings the family's
    // board, YAML, JDBC and the library loader the drivers are fetched with.
    api("co.xenastudios.core:core-jvm")

    testImplementation(kotlin("test"))
    testImplementation("co.xenastudios.core:core-testing")
}

java {
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

kotlin {
    // As the core's modules: nothing public by accident, and warnings fail the build.
    explicitApi()
    jvmToolchain(requiredJava.majorVersion.toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(requiredJava.majorVersion)
        allWarningsAsErrors = true
    }
}

// Not a mod, so nothing to run: a server runs xSample on it, `:sandbox:<version>:runServer`.
tasks.withType<net.fabricmc.loom.task.AbstractRunTask>().configureEach { enabled = false }

tasks.test {
    useJUnitPlatform()
    // Minecraft's classes start its logging, which writes logs/ where the tests run.
    val runIn = layout.buildDirectory.dir("test-run")
    workingDir(runIn)
    doFirst { runIn.get().asFile.mkdirs() }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
