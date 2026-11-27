import co.xenastudios.gradle.ReleasePlatform

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks xBans.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar
    // that ships, and the one the boot tests run.
    id("xena.r8")
    // xbans-v<version> releases every jar: this one, and the Fabric build's (below).
    id("xena.release")
}

group = "co.xenastudios"
// The plugin's version, the same as XBansPlugin.VERSION (checked below) and the Fabric jars'.
version = "2.0.0"
description = "xBans for Paper, Folia, Spigot and CraftBukkit, 1.8.8 to the newest."

// What only some servers have (Paper's AsyncChatEvent and PlayerConnectionValidateLoginEvent, Adventure kick screens), compiled against Paper's API, while main is compiled
// against Spigot 1.8.8's so it can't call what the oldest server lacks: platform-bukkit's way.
// Main calls these only once a check at startup found what they use; the JVM loads a class on
// first use, so an old server never loads them. Java 8 class files, like the rest.
val modern = sourceSets.create("modern")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name in setOf("compileKotlin", "compileTestKotlin") }.configureEach {
    friendPaths.from(modern.output.classesDirs)
}
configurations.named(modern.compileClasspathConfigurationName) {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

// Shaded into the jar and relocated to xBans's own package, so each Xena plugin has its own
// core: the plugin, its API, the core and the platform. The stdlib and the YAML parser are
// downloaded (libraries:, or the loader below 1.16.5). The API's package keeps its name, so
// other plugins find it; its core types are relocated with the core (DECISIONS.md).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xbans:common"))
    implementation(project(":core:platform-bukkit"))
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    "modernImplementation"(project(":xbans:common"))
    "modernImplementation"(project(":core:platform-bukkit"))
    "modernCompileOnly"(libs.paper.api)
    implementation(modern.output)
    for (module in listOf(
        ":xbans:api", ":xbans:common", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api",
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
    // What /xbans import reads LiteBans' H2 and AdvancedBan's HSQLDB files with: fetched only when
    // such an import runs, one H2 per file format (LocalFiles).
    onDemand(libs.h2.format1) { isTransitive = false }
    onDemand(libs.h2.format2) { isTransitive = false }
    onDemand(libs.h2.format3) { isTransitive = false }
    onDemand(libs.hsqldb) { isTransitive = false }
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
}

tasks.jar {
    from(modern.output)
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
    archiveFileName = "xBans.jar"
    configurations = listOf(shadeClasspath.get())
    from(modern.output)
    relocate("co.xenastudios.core", "co.xenastudios.xbans.core")
    relocate("org.bstats", "co.xenastudios.xbans.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    // Bukkit API only: Paper doesn't need to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// Paper's API, for R8 to see what the modern paths call.
val serverApi = configurations.dependencyScope("serverApi")
val serverApiClasspath = configurations.resolvable("serverApiClasspath") { extendsFrom(serverApi.get()) }
dependencies {
    "serverApi"(libs.paper.api)
}

xenaR8 {
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}

// The plugin's version is the one its code reports (/xbans, the debug report).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    description = "Checks XBansPlugin.VERSION is this build's version."
    val source = project(":xbans:common").layout.projectDirectory.file("src/commonMain/kotlin/co/xenastudios/xbans/XBansPlugin.kt")
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val code = source.asFile.readText()
        check("VERSION: String = \"$expected\"" in code) { "XBansPlugin.VERSION isn't $expected, the build's version" }
    }
}
tasks.check { dependsOn(checkVersion) }

// ./gradlew :xbans:bukkit:runServer (or runFolia): a server in run/ with the shrunk jar.
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
    pluginId = "xbans"
    displayName = "xBans"
    folia = true
    modrinthId = "xbans"
    // hangar.papermc.io/xena-studios/xBans
    hangarId = "xBans"
    // voxel.shop/product/10714
    voxelId = 10714
    // CurseForge: no projects yet (ROADMAP.md, the store table). Set curseForgeBukkitId and
    // curseForgeModsId once they exist; until then the release skips CurseForge.
    jar("bukkit") {
        platform = ReleasePlatform.BUKKIT
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.8.8")
        java = 8
    }
    // The proxies' side, which turns banned players away at the network's door: one jar each.
    jar("velocity") {
        platform = ReleasePlatform.VELOCITY
        file = project(":xbans:velocity").tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        // A proxy fronts servers of every version, as the backends' jar runs on them.
        minecraft("1.8.8")
        // Hangar lists Velocity's own versions: 3.4 (the first on Java 21) on.
        hangarVersions = listOf("3.4", "3.5")
        java = 21
    }
    jar("bungeecord") {
        platform = ReleasePlatform.BUNGEECORD
        file = project(":xbans:bungee").tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.8.8")
        java = 17
    }
    fabricLines.forEach { (line, java) ->
        jar("fabric-$line") {
            platform = ReleasePlatform.FABRIC
            file = fabricLibs.file("xbans-fabric-${project.version}+$line.jar")
            gameVersions = listOf(line)
            this.java = java
        }
    }
}

// The release notes are the plugin's, one folder up.
tasks.named<co.xenastudios.gradle.ReleaseAssemble>("releaseAssemble") {
    changelogFile = rootProject.layout.projectDirectory.file("xbans/CHANGELOG.md")
}
