plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

version = "0.0.0"
description = "xSample as a Bukkit plugin, on platform-bukkit and the core's Java shim: boot-tested from Spigot 1.8.8 to Folia. Never released."

// Shaded into the jar and relocated, as each plugin will: the sample, the core and the platform,
// and the YAML parser. The stdlib is downloaded (libraries: or the loader).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":core:core-testing:sample"))
    implementation(project(":core:platform-bukkit"))
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    for (module in listOf(":core:core-testing:sample", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api", ":core:core-loader")) {
        shade(project(module)) { isTransitive = false }
    }
    // Downloaded by the server (libraries:) or by the loader, never shaded.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The JDBC drivers the core fetches when a server has none (core-jvm's DriverLibrary),
    // listed for the platform's driver fetcher.
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
    // The YAML parser the family's and lang files are read with, shaded and relocated until
    // packaging settles how it reaches servers (DECISIONS.md, "The rewrite's config").
    shade(libs.snakeyaml.engine.kmp) {
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
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "XSample.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xsample.core")
    relocate("it.krzeminski", "co.xenastudios.xsample.lib.snakeyaml")
    relocate("okio", "co.xenastudios.xsample.lib.okio")
    relocate("net.thauvin.erik", "co.xenastudios.xsample.lib.urlencoder")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    // Bukkit API only: Paper doesn't need to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// ./gradlew :core:core-testing:sample:bukkit:runServer (or runFolia): a server in run/ with the jar.
val minecraft = libs.versions.paper.get().substringBefore("-R0.1")
tasks.runServer {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shadowJar.flatMap { it.archiveFile })
}
runPaper.folia.registerTask {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shadowJar.flatMap { it.archiveFile })
}
