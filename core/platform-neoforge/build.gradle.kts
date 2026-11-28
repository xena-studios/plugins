import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The core on NeoForge, built once per Minecraft line (settings.gradle.kts): this script runs for
// each version's node, with `sc.current` the version. Not a mod of its own: a plugin bundles it
// with the core, as on Fabric. NeoForge runs Mojang's names from 1.20.5 on, so nothing is remapped.
plugins {
    id("net.neoforged.moddev")
    kotlin("jvm")
}

group = "co.xenastudios.core"
version = "0.0.0+${sc.current.version}"
description = "The rewrite's core on NeoForge, server-side: one jar per Minecraft line."
base.archivesName = "platform-neoforge"

// Minecraft's Java decides the bytecode: 21 for 1.21.x, 25 for 26.1 and later.
val requiredJava: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

neoForge {
    version = sc.properties["deps.neoforge"]
}

dependencies {
    // The Kotlin stdlib, which the server gets from Kotlin for Forge: the rewrite's version, as
    // the line's Kotlin for Forge carries it (stonecutter.properties.toml).
    compileOnly(kotlin("stdlib"))

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

// ModDevGradle puts Minecraft and NeoForge on the main code's class path only (its unit tests run
// a whole game). The tests here need Minecraft's plain classes (text, Brigadier), not a game.
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

tasks.test {
    // Minecraft's classes start its logging, which writes logs/ where the tests run.
    val runIn = layout.buildDirectory.dir("test-run")
    workingDir(runIn)
    doFirst { runIn.get().asFile.mkdirs() }
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
