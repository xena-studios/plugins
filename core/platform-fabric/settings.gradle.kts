// platform-fabric: the core on Fabric, one jar per Minecraft line through Stonecutter, and
// xSample built as a Fabric mod on it (sandbox/), for boot tests. Run it from the repo's root
// with `./gradlew -p core/platform-fabric <task>`.
//
// A build of its own rather than a project of the root build (DECISIONS.md, "platform-fabric"):
// Loom sets Minecraft up for every version while the build is configured, which would slow every
// build of today's plugins and, on a cold CI cache, download Minecraft for them; and Loom adds
// repositories to its projects, which the root build forbids. The core's modules come from the
// root build, included below, so they're built from source here too.
pluginManagement {
    // The root build's conventions, for xena.fabric-mod: how every Xena mod's jar is packaged
    // (its core relocated, R8 and the jar budget, the YAML parser's pins).
    includeBuild("../../build-logic")
    // The rewrite's Kotlin version, from the root build's catalog, so the two never differ.
    val catalog = file("../../gradle/libs.versions.toml").readLines()
    fun version(name: String) = catalog.first { it.startsWith("$name = ") }.substringAfter('"').substringBefore('"')
    repositories {
        // Google's mirror of Maven Central in Central's place, as in the root build (its
        // settings.gradle.kts); the plugin portal sends on to Central what the mirror lacks.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
    plugins {
        kotlin("jvm") version version("kotlin")
    }
}

plugins {
    // One source for every Minecraft line: comments in the code switch per version.
    id("dev.kikugie.stonecutter") version "0.10"
    // Applies fabric-loom-remap below 26.1 (obfuscated, so Mojang's names are mapped) and
    // fabric-loom on 26.1 and later (unobfuscated). Proven in M0 (sandbox/m0/mods).
    id("dev.kikugie.loom-back-compat") version "0.4.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // The Minecraft lines, oldest first: one jar each (DECISIONS.md, M0). 1.20.1 is the
        // floor (Forge's last line, and the oldest Fabric the roadmap supports), 26.3 the newest.
        // A new line is a version here and its block in stonecutter.properties.toml.
        versions("1.20.1", "1.21.11", "26.3")
        vcsVersion = "26.3"
        // xSample as a Fabric mod on this adapter, never released: a node per line, like these.
        branch("sandbox")
        // xTwin, a second mod on its own relocated core, booted beside xSample in the family test.
        branch("twin")
        // xUtilities' Fabric mod. Its folder is xutilities/fabric, in the plugin's own folder
        // (ROADMAP.md, Architecture); it's a branch here, set to that folder below.
        branch("xutilities")
        // zChat's Fabric mod, likewise, from zchat/fabric.
        branch("zchat")
        // xMoney's Fabric mod, from xmoney/fabric.
        branch("xmoney")
        branch("xteleport")
        branch("xbans")
        branch("xvanish")
        branch("xmarket")
    }
}

// A plugin's Fabric adapter lives in its own folder; its branch here is pointed at it, and each
// Minecraft line's node at its versions/ folder there, as Stonecutter puts a branch's nodes.
for (plugin in listOf("xutilities", "zchat", "xmoney", "xteleport", "xbans", "xvanish", "xmarket")) {
    val folder = file("../../$plugin/fabric")
    project(":$plugin").projectDir = folder
    for (node in project(":$plugin").children) node.projectDir = folder.resolve("versions/${node.name}").also { it.mkdirs() }
}

rootProject.name = "platform-fabric"

// Google's mirror of Maven Central in place of Central for every project here, as in the root
// build. Not ahead of every repository: Gradle takes a module's files from the repository that
// had its metadata, and Mojang's libraries repository (which Loom adds before Central) has
// classifiers Central lacks for modules both list, such as LWJGL's natives-macos-patch. So each
// Central that Loom or a build script adds reads the mirror instead, and Central itself goes
// last, for anything the mirror hasn't caught up with yet.
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
        substitute(module("co.xenastudios:xmoney-common")).using(project(":xmoney:common"))
        substitute(module("co.xenastudios:xteleport-common")).using(project(":xteleport:common"))
        substitute(module("co.xenastudios:xbans-common")).using(project(":xbans:common"))
        substitute(module("co.xenastudios:xvanish-common")).using(project(":xvanish:common"))
        substitute(module("co.xenastudios:xmarket-common")).using(project(":xmarket:common"))
    }
}
