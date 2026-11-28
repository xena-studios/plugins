import co.xenastudios.gradle.ReleasePlatform

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks xBackup.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar that
    // ships, and the one the boot tests run.
    id("xena.r8")
    // xbackup-v<version> releases every jar: this one, and the Fabric build's (below).
    id("xena.release")
}

group = "co.xenastudios"
// The plugin's version, the same as XBackupPlugin.VERSION (checked below) and the Fabric jars'.
version = "2.0.0"
description = "xBackup for Paper, Folia, Spigot and CraftBukkit, 1.8.8 to the newest."

// Shaded into the jar and relocated to xBackup's own package, so each Xena plugin has its own
// core: the plugin, its API, the core and the platform. The stdlib and the YAML parser are
// downloaded (libraries:, or the loader below 1.16.5). The API's package keeps its name, so
// other plugins find it; its core types are relocated with the core (DECISIONS.md).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xbackup:common"))
    implementation(project(":core:platform-bukkit"))
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    for (module in listOf(
        ":xbackup:api", ":xbackup:common", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api",
        ":core:core-loader",
    )) {
        shade(project(module)) { isTransitive = false }
    }
    // bStats (BukkitPlatform.metrics), with its base library, relocated below: bStats refuses to
    // run in its own package, so every plugin carries its own copy.
    shade(libs.bstats.bukkit)
    compileOnly(libs.bstats.bukkit)
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The SQLite driver a server without one downloads for the databases no Xena plugin has open
    // (the loader's on-demand libraries): Paper and Spigot ship one, so usually never.
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "libraries" to fetchedCoordinates.get().joinToString("") { "\n  - $it" },
    )
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "xBackup.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xbackup.core")
    relocate("org.bstats", "co.xenastudios.xbackup.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    manifest.attributes(
        // Bukkit API only: Paper doesn't need to remap the jar at startup.
        "paperweight-mappings-namespace" to "mojang",
        // `java -jar xBackup.jar restore ...`: the restore that works with the server stopped.
        "Main-Class" to "co.xenastudios.xbackup.bukkit.XBackupCli",
    )
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// Paper's API, for R8 to see what the plugin calls on newer servers, and the SFTP and FTP
// libraries xBackup's remote.lib classes use (downloaded when such a remote is used, never shipped).
val serverApi = configurations.dependencyScope("serverApi")
val serverApiClasspath = configurations.resolvable("serverApiClasspath") { extendsFrom(serverApi.get()) }
dependencies {
    "serverApi"(libs.paper.api)
    "serverApi"(libs.jsch)
    "serverApi"(libs.commons.net)
}

xenaR8 {
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}

// The plugin's version is the one its code reports (/xbackup, the debug report, snapshots' source).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    description = "Checks XBackupPlugin.VERSION is this build's version."
    val source = project(":xbackup:common").layout.projectDirectory.file("src/commonMain/kotlin/co/xenastudios/xbackup/XBackupPlugin.kt")
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val code = source.asFile.readText()
        check("VERSION: String = \"$expected\"" in code) { "XBackupPlugin.VERSION isn't $expected, the build's version" }
    }
}
tasks.check { dependsOn(checkVersion) }

// ./gradlew :xbackup:bukkit:runServer (or runFolia): a server in run/ with the shrunk jar.
val minecraft = libs.versions.paper.get().substringBefore("-R0.1")
tasks.runServer {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}
runPaper.folia.registerTask {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}

// The Fabric jars come from platform-fabric's own build (DECISIONS.md, "platform-fabric"), which
// the release workflow runs first (`./gradlew -p core/platform-fabric collect`).
val fabricLibs = rootProject.layout.projectDirectory.dir("core/platform-fabric/build/libs")
val fabricLines = listOf("1.20.1" to 17, "1.21.11" to 21, "26.3" to 25)

xenaRelease {
    pluginId = "xbackup"
    displayName = "xBackup"
    folia = true
    modrinthId = "xbackup"
    // hangar.papermc.io/xena-studios/xBackup, voxel.shop/product/10717: 1.x's.
    hangarId = "xBackup"
    voxelId = 10717
    jar("bukkit") {
        platform = ReleasePlatform.BUKKIT
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.8.8")
        java = 8
    }
    fabricLines.forEach { (line, java) ->
        jar("fabric-$line") {
            platform = ReleasePlatform.FABRIC
            file = fabricLibs.file("xbackup-fabric-${project.version}+$line.jar")
            gameVersions = listOf(line)
            this.java = java
        }
    }
}

// The release notes are the plugin's, one folder up.
tasks.named<co.xenastudios.gradle.ReleaseAssemble>("releaseAssemble") {
    changelogFile = rootProject.layout.projectDirectory.file("xbackup/CHANGELOG.md")
}
