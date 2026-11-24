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

// The stdlib is fetched, never shaded: on the compile class path only.
val fetched by configurations.creating

dependencies {
    compileOnly(libs.spigot.api) { isTransitive = false }
    compileOnly(libs.kotlin.stdlib)
    implementation(project(":loader"))
    fetched(libs.kotlin.stdlib) { isTransitive = false }
}

// Kotlin's Gradle plugin adds the stdlib to `api` by default; this build adds it itself.
configurations.named("api") {
    withDependencies { removeIf { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" } }
}

val librariesTxt = tasks.register<FetchedLibraries>("librariesTxt") {
    group("kotlin.Unit", fetched)
    output = layout.buildDirectory.file("generated/m0/META-INF/m0/libraries.txt")
}

sourceSets.main { resources.srcDir(librariesTxt.map { layout.buildDirectory.dir("generated/m0").get() }) }

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "kotlin" to libs.versions.kotlin.get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "Hello.jar"
    relocate("co.xenastudios.m0.loader", "co.xenastudios.m0.hello.loader")
    // Nothing from Kotlin's own metadata the server could need.
    exclude("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/maven/**")
    // Bukkit API only, no NMS: tells Paper not to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
