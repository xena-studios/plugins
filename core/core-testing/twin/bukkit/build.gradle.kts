plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
}

version = "0.0.0"
description = "xTwin as a Bukkit plugin, the family test's second plugin beside xSample. Never released."

// As xSample's Bukkit jar, with the core relocated to xTwin's own package: two plugins, two
// copies of the core that share nothing but the family's board and folder.
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":core:core-testing:twin"))
    implementation(project(":core:platform-bukkit"))
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(project(":core:core-loader"))
    for (module in listOf(":core:core-testing:twin", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api", ":core:core-loader")) {
        shade(project(module)) { isTransitive = false }
    }
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
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
    archiveFileName = "XTwin.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xtwin.core")
    relocate("it.krzeminski", "co.xenastudios.xtwin.lib.snakeyaml")
    relocate("okio", "co.xenastudios.xtwin.lib.okio")
    relocate("net.thauvin.erik", "co.xenastudios.xtwin.lib.urlencoder")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
