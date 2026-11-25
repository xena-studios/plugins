import co.xenastudios.gradle.ReleasePlatform

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    // Shrinks LoaderSandbox.jar to build/r8/, against its budget in gradle/jar-budgets.txt;
    // its tests boot the shrunk jar (src/test).
    id("xena.r8")
    // Never released, but the release pipeline's dry run builds it: M1's exit (ROADMAP.md).
    id("xena.release")
}

version = "0.0.0"
description = "A Kotlin plugin on the core's Java shim, for boot tests on Bukkit 1.8.8 to latest. Never released."

// Shaded into the jar, relocated so each plugin has its own copy: only the loader.
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    shade(project(":core:core-loader"))
    // Downloaded by the server (libraries:) or by the loader, never shaded.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The JDBC drivers the core fetches when a server has none (core-jvm's DriverLibrary): pinned
    // here so gradle/library-pins.txt holds their hashes, and listed for the loader to find.
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
    // JarBootTest boots the jars on Bukkit 1.8.8's own plugin loader. Its chat API's
    // 1.8-SNAPSHOT is in no public repository, so the oldest release stands in.
    testRuntimeOnly(libs.spigot.api.floor) { exclude(group = "net.md-5") }
    testRuntimeOnly(libs.bungeecord.chat)
    testCompileOnly(libs.spigot.api.floor) { isTransitive = false }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "libraries" to fetchedCoordinates.get().joinToString("") { "\n  - $it" },
    )
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "LoaderSandbox.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core.loader", "co.xenastudios.loadersandbox.loader")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    // Bukkit API only: Paper doesn't need to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

xenaR8 {
    // The plugin's own package, as a real plugin's (its id).
    packageName = "loadersandbox"
}

tasks.test {
    // Every class the plugin runs comes from the jar under test, never from this module's
    // own build output, which would be found first.
    classpath -= sourceSets.main.get().output
    val checks = layout.projectDirectory.file("boot-checks.txt")
    inputs.file(checks).withPropertyName("bootChecks").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("xena.bootChecks", checks.asFile.path)
}

// What a release of it would publish, for the release workflow's dry run. No store has a
// project for it, so a real release would skip all but GitHub and Modrinth.
xenaRelease {
    pluginId = "loadersandbox"
    displayName = "LoaderSandbox"
    folia = false
    jar("bukkit") {
        platform = ReleasePlatform.BUKKIT
        // The R8-shrunk jar, the one its budget and boot tests check.
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.8.8")
        java = 8
    }
}
