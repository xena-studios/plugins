plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
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
