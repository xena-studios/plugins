import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    // Adds xenaManifest, checkXenaManifest (run by check) and updateXenaManifest (buildSrc).
    id("xena.manifest")
}

version = "2.0.0"

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

tasks.test {
    useJUnitPlatform()
}

// Build metadata, not code: where the plugin runs. In the rewrite the floors and platforms
// come from one table in build-logic (ROADMAP.md, "Platforms per plugin"), which also feeds
// the release's store uploads, so the docs and the stores can't disagree.
xena {
    pluginId = "xdemo"
    definition = "co.xenastudios.m0.site.xdemo.XDemo"
    // The repo's docs: three folders up from sandbox/m0/site.
    docsDir = rootProject.layout.projectDirectory.dir("../../../docs/src/generated")
    platform("paper", "Paper", "1.8.8", "latest", java = 8, status = "release", since = "2.0.0")
    platform("folia", "Folia", "1.20.6", "latest", java = 21, status = "release", since = "2.0.0")
    platform("spigot", "Spigot", "1.8.8", "latest", java = 8, status = "release", since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", "latest", java = 21, status = "release", since = "2.0.0")
    platform("velocity", "Velocity", "3.3", "latest", java = 21, status = "release", since = "2.0.0", kind = "proxy")
    platform("neoforge", "NeoForge", "1.20.6", "latest", java = 21, status = "planned")
    platform("sponge", "Sponge", "API 8", "latest", java = 17, status = "planned")
    platform("pumpkin", "Pumpkin", "latest", "latest", java = null, status = "planned")
}
