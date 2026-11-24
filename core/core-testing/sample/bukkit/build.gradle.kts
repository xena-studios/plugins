plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks XSample.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar
    // the boot tests and the budget check run.
    id("xena.r8")
}

version = "0.0.0"
description = "xSample as a Bukkit plugin, on platform-bukkit and the core's Java shim: boot-tested from Spigot 1.8.8 to Folia. Never released."

// Shaded into the jar and relocated, as each plugin will: the sample, the core and the platform.
// The stdlib and the YAML parser are downloaded (libraries: or the loader).
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
    // bStats (BukkitPlatform.metrics), with its base library, relocated below: bStats refuses
    // to run in its own package, so every plugin carries its own copy.
    shade(libs.bstats.bukkit)
    compileOnly(libs.bstats.bukkit)
    // Downloaded by the server (libraries:) or by the loader, never shaded.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The YAML parser the family's and lang files are read with (core-jvm's JvmYaml), with the
    // okio and urlencoder jars it needs: 870 KiB that every plugin would otherwise carry.
    // plugin.yml names the parser alone, and the server resolves the other two from its POM;
    // the loader's list has all three, pinned.
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
    // The JDBC drivers the core fetches when a server has none (core-jvm's DriverLibrary),
    // listed for the platform's driver fetcher.
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
    archiveFileName = "XSample.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xsample.core")
    relocate("org.bstats", "co.xenastudios.xsample.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    // Bukkit API only: Paper doesn't need to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// What newer servers have that the 1.8.8 API this compiles against doesn't: Paper's API with
// Adventure, which platform-bukkit's modern paths call. For R8 only, which must see every class
// the jar names.
val serverApi = configurations.dependencyScope("serverApi")
val serverApiClasspath = configurations.resolvable("serverApiClasspath") { extendsFrom(serverApi.get()) }
dependencies {
    "serverApi"(libs.paper.api)
}

xenaR8 {
    // The plugin's own package, which the budget check's profile counts as xSample's.
    packageName = "co.xenastudios.xsample.r8"
    // What the server has at runtime beside the jar: its API, and what's downloaded.
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}

// ./gradlew :core:core-testing:sample:bukkit:runServer (or runFolia): a server in run/ with the jar.
val minecraft = libs.versions.paper.get().substringBefore("-R0.1")
tasks.runServer {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}
runPaper.folia.registerTask {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}
