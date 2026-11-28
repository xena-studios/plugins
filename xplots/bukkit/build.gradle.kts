import co.xenastudios.gradle.ReleasePlatform

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks xPlots.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar
    // that ships, and the one the boot tests run.
    id("xena.r8")
    // xplots-v<version> releases this jar: Bukkit only for 2.0 (Fabric and NeoForge come later, with xPlots' own regions).
    id("xena.release")
}

group = "co.xenastudios"
// The plugin's version, the same as XPlotsPlugin.VERSION (checked below).
version = "2.0.0"
description = "xPlots for Paper, Folia, Spigot and CraftBukkit, 1.13 to the newest, with WorldGuard and WorldEdit when they're installed."

// What only some servers have (Paper's chunks off the server thread, its async teleports, signs'
// sides and wax, items' persistent data and custom model data, action bars), compiled against Paper's API, while main is compiled against Spigot
// 1.13.2's (block data's first version, xPlots's floor) so it can't call what the oldest lacks.
// Main calls these only once a check at startup found what they use; the JVM loads a class on
// first use, so an old server never loads them. Java 8 class files, like the rest.
val modern = sourceSets.create("modern")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name in setOf("compileKotlin", "compileTestKotlin") }.configureEach {
    friendPaths.from(modern.output.classesDirs)
}
configurations.named(modern.compileClasspathConfigurationName) {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

// Shaded into the jar and relocated to xPlots's own package, so each Xena plugin has its own
// core: the plugin, its API, the core and the platform. The stdlib and the YAML parser are
// downloaded (libraries:, or the loader below 1.16.5). The API's package keeps its name, so
// other plugins find it; its core types are relocated with the core (DECISIONS.md).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xplots:common"))
    implementation(project(":core:platform-bukkit"))
    compileOnly(libs.spigot.api.world) { isTransitive = false }
    // Spigot's chat components, for action bars where there's no Adventure; every Spigot ships them.
    compileOnly(libs.bungeecord.chat)
    // Optional: %xplots_...% placeholders (Papi, the one class that names it).
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    "modernImplementation"(project(":xplots:common"))
    "modernImplementation"(project(":core:platform-bukkit"))
    "modernCompileOnly"(libs.paper.api)
    implementation(modern.output)
    for (module in listOf(
        ":xplots:api", ":xplots:common", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api",
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
    archiveFileName = "xPlots.jar"
    configurations = listOf(shadeClasspath.get())
    from(modern.output)
    relocate("co.xenastudios.core", "co.xenastudios.xplots.core")
    relocate("org.bstats", "co.xenastudios.xplots.bstats")
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
    "serverApi"(libs.placeholderapi) { isTransitive = false }
}

xenaR8 {
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}

// The plugin's version is the one its code reports (/xplots, the debug report).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    description = "Checks XPlotsPlugin.VERSION is this build's version."
    val source = project(":xplots:common").layout.projectDirectory.file("src/commonMain/kotlin/co/xenastudios/xplots/XPlotsPlugin.kt")
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val code = source.asFile.readText()
        check("VERSION: String = \"$expected\"" in code) { "XPlotsPlugin.VERSION isn't $expected, the build's version" }
    }
}
tasks.check { dependsOn(checkVersion) }

// ./gradlew :xplots:bukkit:runServer (or runFolia): a server in run/ with the shrunk jar.
val minecraft = libs.versions.paper.get().substringBefore("-R0.1")
tasks.runServer {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}
runPaper.folia.registerTask {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}

xenaRelease {
    pluginId = "xplots"
    displayName = "xPlots"
    folia = true
    modrinthId = "xplots"
    // hangar.papermc.io/xena-studios/xPlots
    hangarId = "xPlots"
    // voxel.shop/product/10711
    voxelId = 10711
    // CurseForge: no projects yet (ROADMAP.md, the store table). Set curseForgeBukkitId and
    // curseForgeModsId once they exist; until then the release skips CurseForge.
    jar("bukkit") {
        platform = ReleasePlatform.BUKKIT
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.13")
        java = 8
    }
}

// The release notes are the plugin's, one folder up.
tasks.named<co.xenastudios.gradle.ReleaseAssemble>("releaseAssemble") {
    changelogFile = rootProject.layout.projectDirectory.file("xplots/CHANGELOG.md")
}
