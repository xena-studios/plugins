// platform-neoforge: the core on NeoForge, one jar per Minecraft line through Stonecutter, and
// xSample built as a NeoForge mod on it (sandbox/), for boot tests. Run it from the repo's root
// with `./gradlew -p core/platform-neoforge <task>`.
//
// A build of its own, as platform-fabric is (DECISIONS.md, "platform-fabric"): ModDevGradle sets
// Minecraft up for every version while the build is configured, which would slow every build of
// the root, and it adds repositories to its projects, which the root build forbids. The core's
// modules come from the root build, included below, so they're built from source here too.
pluginManagement {
    // The root build's conventions, for xena.neoforge-mod: how every Xena mod's jar is packaged
    // (its core relocated, R8 and the jar budget, the YAML parser's pins).
    includeBuild("../../build-logic")
    // The rewrite's Kotlin version, from the root build's catalog, so the two never differ.
    val catalog = file("../../gradle/libs.versions.toml").readLines()
    fun version(name: String) = catalog.first { it.startsWith("$name = ") }.substringAfter('"').substringBefore('"')
    repositories {
        // Google's mirror of Maven Central in Central's place, as in the root build.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
    plugins {
        kotlin("jvm") version version("kotlin")
    }
}

plugins {
    // One source for every Minecraft line: comments in the code switch per version.
    id("dev.kikugie.stonecutter") version "0.10"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // The Minecraft lines, oldest first: one jar each (decisions/2026-11-28-neoforge-and-forge.md).
        // 1.21.1 is the floor, NeoForge's long-lived line and the first ModDevGradle builds (1.20.2
        // to 1.20.4 run with SRG names on a server, which a jar would need remapping for); 1.21.11
        // the last 1.21 line; 26.3 the newest. A new line is a version here and its block in
        // stonecutter.properties.toml.
        // Forge 1.20.1, where Forge's modpack servers stay, is a node of this tree too (decisions/
        // 2026-11-28-neoforge-and-forge.md): NeoForge forked from it, so the same source builds it,
        // with Forge's package names swapped in (stonecutter.gradle.kts) and Minecraft 1.20.1's
        // differences switched as platform-fabric switches them.
        version("1.20.1-forge", "1.20.1")
        versions("1.21.1", "1.21.11", "26.3")
        vcsVersion = "26.3"
        // xSample as a NeoForge mod on this adapter, never released: a node per line, like these.
        branch("sandbox")
        // xTwin, a second mod on its own relocated core, booted beside xSample in the family test.
        branch("twin")
        // xUtilities' NeoForge mod. Its folder is xutilities/neoforge, in the plugin's own folder
        // (ROADMAP.md, Architecture); it's a branch here, set to that folder below.
        branch("xutilities")
        branch("zchat")
    }
}

// A plugin's NeoForge adapter lives in its own folder; its branch here is pointed at it, and each
// Minecraft line's node at its versions/ folder there, as Stonecutter puts a branch's nodes.
for (plugin in listOf("xutilities", "zchat")) {
    val folder = file("../../$plugin/neoforge")
    project(":$plugin").projectDir = folder
    for (node in project(":$plugin").children) node.projectDir = folder.resolve("versions/${node.name}").also { it.mkdirs() }
}

rootProject.name = "platform-neoforge"

// Google's mirror of Maven Central in place of Central for every project here, as in the root
// build and platform-fabric's; Central itself goes last, for anything the mirror lacks yet.
gradle.lifecycle.beforeProject {
    repositories.withType<MavenArtifactRepository>().configureEach {
        if (url.toString().trimEnd('/') == "https://repo.maven.apache.org/maven2") {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}
gradle.lifecycle.afterProject {
    repositories.maven("https://repo1.maven.org/maven2/") { name = "MavenCentralFallback" }
}

// The root build's catalog, which build-logic's conventions read (R8's version among them).
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

// The repo's own build, for the core's modules: Gradle builds them from source when a version
// node needs them, so a change to the core is seen here at once.
includeBuild("../..") {
    name = "xena-plugins"
    dependencySubstitution {
        for (module in listOf("core-api", "core-common", "core-jvm", "core-loader", "core-testing")) {
            substitute(module("co.xenastudios.core:$module")).using(project(":core:$module"))
        }
        substitute(module("co.xenastudios.core:sample")).using(project(":core:core-testing:sample"))
        substitute(module("co.xenastudios.core:twin")).using(project(":core:core-testing:twin"))
        substitute(module("co.xenastudios:xutilities-common")).using(project(":xutilities:common"))
        substitute(module("co.xenastudios:zchat-common")).using(project(":zchat:common"))
    }
}
