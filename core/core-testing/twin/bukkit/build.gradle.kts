plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    // Shrunk as xSample's is, so the family test boots two R8 jars (build/r8/XTwin.jar).
    id("xena.r8")
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
    // Its own bStats, relocated as its core is: the family test boots two copies side by side.
    shade(libs.bstats.bukkit)
    compileOnly(libs.bstats.bukkit)
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // Downloaded, as xSample's: on servers before 1.16.5 the two plugins share one copy.
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
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
    archiveFileName = "XTwin.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xtwin.core")
    relocate("org.bstats", "co.xenastudios.xtwin.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// What newer servers have beyond the 1.8.8 API, for R8 to see (as xSample's build explains).
val serverApi = configurations.dependencyScope("serverApi")
val serverApiClasspath = configurations.resolvable("serverApiClasspath") { extendsFrom(serverApi.get()) }
dependencies {
    "serverApi"(libs.paper.api)
}

xenaR8 {
    packageName = "co.xenastudios.xtwin.r8"
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}
