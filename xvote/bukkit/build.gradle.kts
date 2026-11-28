import co.xenastudios.gradle.ReleasePlatform

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks xVote.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar that
    // ships, and the one the boot tests run.
    id("xena.r8")
    // xvote-v<version> releases every jar: this one, the proxies' and the Fabric build's (below).
    id("xena.release")
}

group = "co.xenastudios"
// The plugin's version, the same as XVotePlugin.VERSION (checked below) and the other jars'.
version = "2.0.0"
description = "xVote for Paper, Folia, Spigot and CraftBukkit, 1.8.8 to the newest."

// Shaded into the jar and relocated to xVote's own package, so each Xena plugin has its own
// core: the plugin, its API, the core and the platform. The stdlib and the YAML parser are
// downloaded (libraries:, or the loader below 1.16.5). The API's package keeps its name, so
// other plugins find it; its core types are relocated with the core (DECISIONS.md).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xvote:common"))
    implementation(project(":core:platform-bukkit"))
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    // The placeholders, an expansion of PlaceholderAPI's, loaded only while it's on.
    compileOnly(libs.placeholderapi) { isTransitive = false }
    for (module in listOf(
        ":xvote:api", ":xvote:common", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api",
        ":core:core-loader",
    )) {
        shade(project(module)) { isTransitive = false }
    }
    // bStats (BukkitPlatform.metrics), with its base library, relocated below: bStats refuses
    // to run in its own package, so every plugin carries its own copy.
    shade(libs.bstats.bukkit)
    compileOnly(libs.bstats.bukkit)
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The database drivers a server without them downloads when it opens the database (the
    // loader's on-demand libraries): Paper and Spigot ship both, so usually never.
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
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
    archiveFileName = "xVote.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xvote.core")
    relocate("org.bstats", "co.xenastudios.xvote.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    // Bukkit API only: Paper doesn't need to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// Paper's API and PlaceholderAPI's, for R8 to see what the jar calls.
val serverApi = configurations.dependencyScope("serverApi")
val serverApiClasspath = configurations.resolvable("serverApiClasspath") { extendsFrom(serverApi.get()) }
dependencies {
    "serverApi"(libs.paper.api)
    "serverApi"(libs.placeholderapi) { isTransitive = false }
}

xenaR8 {
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}

// The plugin's version is the one its code reports (/xvote, the debug report).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    description = "Checks XVotePlugin.VERSION is this build's version."
    val source = project(":xvote:common").layout.projectDirectory.file("src/commonMain/kotlin/co/xenastudios/xvote/XVotePlugin.kt")
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val code = source.asFile.readText()
        check("VERSION: String = \"$expected\"" in code) { "XVotePlugin.VERSION isn't $expected, the build's version" }
    }
}
tasks.check { dependsOn(checkVersion) }

// ./gradlew :xvote:bukkit:runServer (or runFolia): a server in run/ with the shrunk jar.
val minecraft = libs.versions.paper.get().substringBefore("-R0.1")
tasks.runServer {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}
runPaper.folia.registerTask {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}

// The Fabric jars come from platform-fabric's own build (DECISIONS.md, "platform-fabric"),
// which the release workflow runs first (`./gradlew -p core/platform-fabric collect`): each
// line's jar, its core relocated, in that build's build/libs. Locally, run it before a dry run.
val fabricLibs = rootProject.layout.projectDirectory.dir("core/platform-fabric/build/libs")
val fabricLines = listOf("1.20.1" to 17, "1.21.11" to 21, "26.3" to 25)

xenaRelease {
    pluginId = "xvote"
    displayName = "xVote"
    folia = true
    modrinthId = "xvote"
    // hangar.papermc.io/xena-studios/xVote
    hangarId = "xVote"
    // voxel.shop/product/10713
    voxelId = 10713
    // CurseForge: no projects yet (ROADMAP.md, the store table). Set curseForgeBukkitId and
    // curseForgeModsId once they exist; until then the release skips CurseForge.
    jar("bukkit") {
        platform = ReleasePlatform.BUKKIT
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.8.8")
        java = 8
    }
    // The proxies' side, which receives the network's votes: one jar each.
    jar("velocity") {
        platform = ReleasePlatform.VELOCITY
        file = project(":xvote:velocity").tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        // A proxy fronts servers of every version, as the backends' jar runs on them.
        minecraft("1.8.8")
        // Hangar lists Velocity's own versions: 3.4 (the first on Java 21) on.
        hangarVersions = listOf("3.4", "3.5")
        java = 21
    }
    jar("bungeecord") {
        platform = ReleasePlatform.BUNGEECORD
        // Hangar takes it as Waterfall's, whose versions are its own lines: 1.21, the one CI boots.
        hangarVersions = listOf("1.21")
        file = project(":xvote:bungee").tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.8.8")
        java = 17
    }
    fabricLines.forEach { (line, java) ->
        jar("fabric-$line") {
            platform = ReleasePlatform.FABRIC
            file = fabricLibs.file("xvote-fabric-${project.version}+$line.jar")
            gameVersions = listOf(line)
            this.java = java
        }
    }
}

// The release notes are the plugin's, one folder up.
tasks.named<co.xenastudios.gradle.ReleaseAssemble>("releaseAssemble") {
    changelogFile = rootProject.layout.projectDirectory.file("xvote/CHANGELOG.md")
}
