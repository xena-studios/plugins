plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(17)
    // 17 by default; -Pm0.jvm=16 or 1.8 for Spigot 1.13-1.16.5, which refuse to start on Java 17.
    // -Xjdk-release also keeps the JDK API to that version, not just the class file format.
    val jvm = providers.gradleProperty("m0.jvm").orElse("17")
    compilerOptions.jvmTarget = jvm.map { org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(it) }
    compilerOptions.freeCompilerArgs.add(jvm.map { "-Xjdk-release=$it" })
}

// The main class is Java 8 bytecode like the loader, so a Java 8 server reads it and gets
// the bootstrap's "needs Java 17" message. The Kotlin classes are Java 17.
tasks.compileJava { options.release = 8 }
// The Java and Kotlin targets differ on purpose.
tasks.compileKotlin {
    jvmTargetValidationMode = org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode.IGNORE
}

// Fetched everywhere: by `libraries:` on Spigot 1.16.5+ and Paper, by the bootstrap below that.
val fetchedCore by configurations.creating
// Fetched only where the server has no Adventure (Spigot, CraftBukkit, Paper before 1.16.5).
val fetchedAdventure by configurations.creating {
    // The server's own Gson is used (1.8.8: 2.2.4); adventure-platform-bukkit copes with it.
    exclude(group = "com.google.code.gson")
    exclude(module = "adventure-text-minimessage")
}
// Paper bundles MiniMessage only from 1.18.2 (1.16.5 has Adventure without it; PandaSpigot has both).
val fetchedMiniMessage by configurations.creating { isTransitive = false }

dependencies {
    compileOnly(libs.spigot.api) { isTransitive = false }
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.cloud.paper)
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.platform.bukkit)
    compileOnly(libs.bungeecord.chat) { isTransitive = false }
    implementation(project(":loader"))

    fetchedCore(libs.kotlin.stdlib) { isTransitive = false }
    fetchedCore(libs.cloud.paper)
    fetchedAdventure(libs.adventure.api)
    fetchedMiniMessage(libs.adventure.minimessage)
    fetchedAdventure(libs.adventure.platform.bukkit)
}

configurations.named("api") {
    withDependencies { removeIf { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" } }
}

val librariesTxt = tasks.register<FetchedLibraries>("librariesTxt") {
    group("kotlin.Unit", fetchedCore)
    group("net.kyori.adventure.audience.Audience", fetchedAdventure)
    group("net.kyori.adventure.text.minimessage.MiniMessage", fetchedMiniMessage)
    output = layout.buildDirectory.file("generated/m0/META-INF/m0/libraries.txt")
}

sourceSets.main { resources.srcDir(librariesTxt.map { layout.buildDirectory.dir("generated/m0").get() }) }

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "kotlin" to libs.versions.kotlin.get(),
        "cloud" to libs.versions.cloud.minecraft.get(),
    )
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "HelloCloud.jar"
    relocate("co.xenastudios.m0.loader", "co.xenastudios.m0.hellocloud.loader")
    exclude("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/maven/**")
    // Bukkit API only, no NMS: tells Paper not to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
