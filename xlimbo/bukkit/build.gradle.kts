import co.xenastudios.gradle.ReleasePlatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    // Shrinks xLimbo.jar to build/r8/, against its line in gradle/jar-budgets.txt: the jar that
    // ships, and the one the boot tests run.
    id("xena.r8")
    // xlimbo-v<version> releases the jar.
    id("xena.release")
}

group = "co.xenastudios"
// The plugin's version, the same as XLimboPlugin.VERSION (checked below).
version = "3.0.0"
description = "xLimbo for Paper 1.21.11 and newer (and Purpur): Paper only, by design."

// Paper only, from 1.21.11, which needs Java 21: the whole jar is compiled against Paper's API and
// is Java 21 class files (ROADMAP.md, "Paper-only code is Java 21"). The core shaded in stays
// Java 8, as every plugin's does.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(freeCompilerArgs.get().filter { !it.startsWith("-Xjdk-release") } + "-Xjdk-release=21")
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
configurations.named("compileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
configurations.named("testCompileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
configurations.named("testRuntimeClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

// Shaded into the jar and relocated to xLimbo's own package, so each Xena plugin has its own
// core: the plugin, its API, the core and the platform. The stdlib and the YAML parser are
// downloaded (handed to Paper by the core's loader). The API's package keeps its name, so other
// plugins find it; its core types are relocated with the core (DECISIONS.md).
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

dependencies {
    implementation(project(":xlimbo:common"))
    implementation(project(":core:platform-bukkit"))
    // Paper 1.21.11, xLimbo's floor: what main compiles against is what every server it runs on has.
    compileOnly(libs.paper.api)
    compileOnly(project(":core:core-loader"))
    testImplementation(libs.paper.api)
    for (module in listOf(":xlimbo:api", ":xlimbo:common", ":core:platform-bukkit", ":core:core-jvm", ":core:core-common", ":core:core-api", ":core:core-loader")) {
        shade(project(module)) { isTransitive = false }
    }
    // bStats (BukkitPlatform.metrics), with its base library, relocated below: bStats refuses
    // to run in its own package, so every plugin carries its own copy.
    shade(libs.bstats.bukkit)
    compileOnly(libs.bstats.bukkit)
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "xLimbo.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xlimbo.core")
    relocate("org.bstats", "co.xenastudios.xlimbo.bstats")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    // Paper's API only: Paper doesn't need to remap the jar at startup.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// Paper's API, for R8 to see what the plugin calls.
val serverApi = configurations.dependencyScope("serverApi")
val serverApiClasspath = configurations.resolvable("serverApiClasspath") {
    extendsFrom(serverApi.get())
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
dependencies {
    "serverApi"(libs.paper.api)
}

xenaR8 {
    libraries.from(configurations.named("fetchedJars"), serverApiClasspath)
}

// The plugin's version is the one its code reports (/xlimbo, the debug report).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    description = "Checks XLimboPlugin.VERSION is this build's version."
    val source = project(":xlimbo:common").layout.projectDirectory.file("src/commonMain/kotlin/co/xenastudios/xlimbo/XLimboPlugin.kt")
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val code = source.asFile.readText()
        check("VERSION: String = \"$expected\"" in code) { "XLimboPlugin.VERSION isn't $expected, the build's version" }
    }
}
tasks.check { dependsOn(checkVersion) }

// ./gradlew :xlimbo:bukkit:runServer: a Paper server in run/ with the shrunk jar.
val minecraft = libs.versions.paper.get().substringBefore("-R0.1")
tasks.runServer {
    minecraftVersion(minecraft)
    pluginJars.setFrom(tasks.shrinkJar.flatMap { it.outputJar })
}

xenaRelease {
    pluginId = "xlimbo"
    displayName = "xLimbo"
    // Folia can't create worlds, and a limbo has nothing to spread across regions
    // (decisions/2026-10-22-xlimbo-is-paper-only.md).
    folia = false
    modrinthId = "xlimbo"
    // hangar.papermc.io/xena-studios/xLimbo
    hangarId = "xLimbo"
    // voxel.shop/product/10709
    voxelId = 10709
    jar("bukkit") {
        platform = ReleasePlatform.PAPER
        file = tasks.named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }
        minecraft("1.21.11")
        java = 21
    }
}

// The release notes are the plugin's, one folder up.
tasks.named<co.xenastudios.gradle.ReleaseAssemble>("releaseAssemble") {
    changelogFile = rootProject.layout.projectDirectory.file("xlimbo/CHANGELOG.md")
}
