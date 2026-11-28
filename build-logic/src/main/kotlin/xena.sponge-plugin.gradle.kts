import co.xenastudios.gradle.ApiFloor
import co.xenastudios.gradle.R8Shrink

// A Xena plugin's Sponge jar, API 8 (SpongeVanilla 1.16.5) on, one jar for every API line,
// packaged the same way for every plugin (decisions/2026-11-28-platform-sponge.md):
//
//     plugins { id("xena.sponge-plugin") }
//     xenaSpongePlugin { id = "xutilities" }
//     dependencies { implementation(project(":xutilities:common")) }
//
// with the plugin's `src/main/resources/META-INF/sponge_plugins.json` (`${version}` is filled in)
// naming its main class, which extends platform-sponge's SpongeBootstrap.
//
// 1. `shadowJar`: the plugin, the core and everything they run on, the Kotlin stdlib and the YAML
//    parser too: Sponge has no library loader and no Kotlin language provider (M0). The core
//    moves to `co.xenastudios.<id>.core` and the libraries to `co.xenastudios.<id>.libs`: every
//    Sponge plugin shares one class loader, so two Xena plugins must never both have `kotlin.Unit`.
// 2. `shrinkJar` (xena.r8): R8 against SpongeAPI's floor, into the package `<id>`, with the jar's
//    line in gradle/jar-budgets.txt.
// 3. `checkApiFloor` and `checkApiNewest` (part of `check`): the shrunk jar's calls into Sponge's
//    and Adventure's packages against API 8 and against the newest API (ApiFloor), so a member
//    R8 reached for, or one a later API removed, fails the build instead of a server. What's only
//    called after a check is listed in api-floor.txt or api-newest.txt beside the build file.
plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xena.r8")
}

val plugin = extensions.create<XenaSpongePluginExtension>("xenaSpongePlugin")
val catalog = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":core:platform-sponge"))
    "compileOnly"(catalog.findLibrary("spongeapi").get())
}

// What the server has beside the jar: SpongeAPI and what it brings (Adventure, Guice, Log4j,
// geantyref), at the floor and at the newest API.
val spongeApiFloor = configurations.dependencyScope("spongeApiFloor")
val spongeApiLatest = configurations.dependencyScope("spongeApiLatest")
val spongeApi = configurations.resolvable("spongeApiClasspath") { extendsFrom(spongeApiFloor.get()) }
val spongeApiNewest = configurations.resolvable("spongeApiNewestClasspath") { extendsFrom(spongeApiLatest.get()) }
dependencies {
    "spongeApiFloor"(catalog.findLibrary("spongeapi").get())
    "spongeApiLatest"(catalog.findLibrary("spongeapi-newest").get())
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("META-INF/sponge_plugins.json") { expand(props) }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    configurations = listOf(project.configurations.getByName("runtimeClasspath"))
    val id = plugin.id
    val libs = id.map { "co.xenastudios.$it.libs" }
    relocate("co.xenastudios.core", "co.xenastudios.${id.get()}.core")
    relocate("kotlin", "${libs.get()}.kotlin")
    relocate("kotlinx", "${libs.get()}.kotlinx")
    relocate("it.krzeminski", "${libs.get()}.krzeminski")
    relocate("okio", "${libs.get()}.okio")
    relocate("net.thauvin", "${libs.get()}.thauvin")
    // Compile-time annotations, read by no one at runtime.
    exclude("org/jetbrains/annotations/**", "org/intellij/**")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**", "META-INF/proguard/**", "META-INF/com.android.tools/**", "**/module-info.class", "kotlin/**/*.kotlin_builtins")
}

tasks.named("assemble") { dependsOn("shadowJar") }

the<XenaR8Extension>().apply {
    packageName.convention(plugin.id)
    // Only the server's: everything else is in the jar.
    libraries.setFrom(spongeApi)
}

val shrinkJar = tasks.named<R8Shrink>("shrinkJar")

fun ApiFloor.configure(api: NamedDomainObjectProvider<out Configuration>, file: String, write: Boolean) {
    jar.set(shrinkJar.flatMap { it.outputJar })
    mapping.set(shrinkJar.flatMap { it.mapping })
    floor.from(api)
    allowed.set(layout.projectDirectory.file(file))
    update.set(write)
}
for ((name, api, file) in listOf(Triple("ApiFloor", spongeApi, "api-floor.txt"), Triple("ApiNewest", spongeApiNewest, "api-newest.txt"))) {
    val check = tasks.register<ApiFloor>("check$name") {
        group = "verification"
        description = "Fails if the shrunk jar calls Sponge API that $file doesn't list and ${if (name == "ApiFloor") "API 8" else "the newest API"} lacks."
        configure(api, file, write = false)
        inputs.file(allowed).optional().withPropertyName("allowed").withPathSensitivity(PathSensitivity.NONE)
    }
    tasks.register<ApiFloor>("update$name") {
        group = "verification"
        description = "Writes $file: the Sponge API members the shrunk jar uses that ${if (name == "ApiFloor") "API 8" else "the newest API"} lacks."
        configure(api, file, write = true)
        outputs.file(allowed)
    }
    tasks.named("check") { dependsOn(check) }
}
