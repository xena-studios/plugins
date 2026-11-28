import co.xenastudios.gradle.ReleasePlatform

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks xLimits.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar
    // that ships, and the one the boot tests run.
    id("xena.r8")
    // xlimits-v<version> releases every jar: this one, and the Fabric build's (below).
    id("xena.release")
}

group = "co.xenastudios"
// The plugin's version, the same as XLimitsPlugin.VERSION (checked below) and the Fabric jars'.
version = "1.0.0"
description = "xLimits for Paper, Folia, Spigot and CraftBukkit, 1.13 to the newest."

// What only some servers have (Paper's TPS and tick times, Folia's region TPS, the TNT prime
// events, mobs' awareness), compiled against Paper's API, while main is compiled against Spigot
// 1.13.2's, xLimits' floor, so it can't call what the oldest server lacks: platform-bukkit's way.
// Main calls these only once a check at startup found what they use; the JVM loads a class on
// first use, so an old server never loads them. Java 8 class files, like the rest.
val modern = sourceSets.create("modern")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name in setOf("compileKotlin", "compileTestKotlin") }.configureEach {
    friendPaths.from(modern.output.classesDirs)
}
configurations.named(modern.compileClasspathConfigurationName) {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

// Shaded into the jar and relocated to xLimits's own package, so each Xena plugin has its own
// core: the plugin, its API, the core and the platform. The stdlib and the YAML parser are
// downloaded (libraries:, or the loader below 1.16.5). The API's package keeps its name, so
// other plugins find it; its core types are relocated with the core (DECISIONS.md).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xlimits:common"))
    implementation(project(":core:platform-bukkit"))
    // 1.13.2, the floor: block data, entity tags and the block-entity lists are there from 1.13.
    compileOnly(libs.spigot.api.world) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    "modernImplementation"(project(":xlimits:common"))
    "modernImplementation"(project(":core:platform-bukkit"))
    "modernCompileOnly"(libs.paper.api)
    implementation(modern.output)
    for (module in listOf(
        ":xlimits:api", ":xlimits:common", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api",
        ":core:core-loader",
    )) {
        shade(project(module)) { isTransitive = false }
    }
    // No bStats: xLimits has no bStats service id yet (bstats.org/plugin/bukkit/xLimits once it's
    // made), so its library isn't in the jar and nothing is sent.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
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
    archiveFileName = "xLimits.jar"
    configurations = listOf(shadeClasspath.get())
    from(modern.output)
    relocate("co.xenastudios.core", "co.xenastudios.xlimits.core")
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

// The plugin's version is the one its code reports (/xlimits, the debug report).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    description = "Checks XLimitsPlugin.VERSION is this build's version."
    val source = project(":xlimits:common").layout.projectDirectory.file("src/commonMain/kotlin/co/xenastudios/xlimits/XLimitsPlugin.kt")
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val code = source.asFile.readText()
        check("VERSION: String = \"$expected\"" in code) { "XLimitsPlugin.VERSION isn't $expected, the build's version" }
    }
}
tasks.check { dependsOn(checkVersion) }

// ./gradlew :xlimits:bukkit:runServer (or runFolia): a server in run/ with the shrunk jar.
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
    pluginId = "xlimits"
    displayName = "xLimits"
    folia = true
    // A new plugin: its Modrinth slug, reserved as a draft (docs/src/data/modrinth.json once
    // it's published). Hangar, voxel.shop and CurseForge have no project yet, so the release
    // skips them until their ids are set here.
    modrinthId = "xlimits"
    jar("bukkit") {
        platform = ReleasePlatform.BUKKIT
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.13")
        java = 8
    }
    fabricLines.forEach { (line, java) ->
        jar("fabric-$line") {
            platform = ReleasePlatform.FABRIC
            file = fabricLibs.file("xlimits-fabric-${project.version}+$line.jar")
            gameVersions = listOf(line)
            this.java = java
        }
    }
}

// The release notes are the plugin's, one folder up.
tasks.named<co.xenastudios.gradle.ReleaseAssemble>("releaseAssemble") {
    changelogFile = rootProject.layout.projectDirectory.file("xlimits/CHANGELOG.md")
}
