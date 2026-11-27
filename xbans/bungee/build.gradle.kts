plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    // Shrinks xBans-Bungee.jar to build/r8/, against its line in gradle/jar-budgets.txt: the
    // jar the proxy boot tests run on BungeeCord and Waterfall.
    id("xena.r8")
}

version = "2.0.0"
description = "xBans on BungeeCord and Waterfall: banned players turned away at the network's door, kicks and bans across every server."

// Shaded into the jar and relocated: xBans, the core and the platform.
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xbans:common"))
    implementation(project(":core:platform-bungee"))
    compileOnly(libs.bungeecord.api)
    for (module in listOf(":xbans:api", ":xbans:common", ":core:platform-bungee", ":core:core-jvm", ":core:core-common", ":core:core-api", ":core:core-loader")) {
        shade(project(module)) { isTransitive = false }
    }
    shade(project(":core:core-loader")) {
        isTransitive = false
        capabilities { requireFeature("bungee") }
    }
    // bStats, relocated below: bStats refuses to run in its own package.
    shade(libs.bstats.bungeecord)
    compileOnly(libs.bstats.bungeecord)
    // Downloaded by BungeeCord (bungee.yml's libraries:) or by the shim, never shaded.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The database drivers, downloaded when the database opens: a proxy ships none.
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

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "libraries" to fetchedCoordinates.get().joinToString("") { "\n  - $it" },
    )
    inputs.properties(props)
    filesMatching("bungee.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "xBans-Bungee.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xbans.core")
    relocate("org.bstats", "co.xenastudios.xbans.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// What the proxy has beside the jar, for R8: BungeeCord's API and what's downloaded.
val proxyApi = configurations.dependencyScope("proxyApi")
val proxyApiClasspath = configurations.resolvable("proxyApiClasspath") { extendsFrom(proxyApi.get()) }
dependencies {
    "proxyApi"(libs.bungeecord.api)
}

xenaR8 {
    packageName = "xbans"
    libraries.from(configurations.named("fetchedJars"), proxyApiClasspath)
}
