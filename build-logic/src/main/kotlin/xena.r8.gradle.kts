import co.xenastudios.gradle.JarBudget
import co.xenastudios.gradle.R8Shrink

// A rewrite jar shrunk with R8 (ROADMAP.md, "Light": every jar is shrunk), with a size budget
// `check` enforces. Apply it to a project that builds a plugin jar (with Shadow or `jar`):
//
//     plugins { id("xena.r8") }
//
// `shrinkJar` (part of `assemble`) writes build/r8/<jar> and its mapping file; `checkJarBudget`
// (part of `check`) fails when that jar is over its line in gradle/jar-budgets.txt, or has
// none. Tests get both jars' paths (xena.r8.jar, xena.r8.inputJar) to boot them. The keep rules: build-logic's xena/r8/common.pro, the project's r8/*.pro, and what the
// jar's plugin.yml, fabric.mod.json and services name (R8Shrink). `xenaR8 { }` changes the
// input jar, the library jars or the package renamed classes go to.
plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

val r8 = configurations.dependencyScope("r8")
val r8Classpath = configurations.resolvable("r8Classpath") { extendsFrom(r8.get()) }

dependencies {
    "r8"(libs.findLibrary("r8").get())
}

val xenaR8 = extensions.create<XenaR8Extension>("xenaR8").apply {
    inputJar.convention(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    libraries.convention(configurations.named("compileClasspath"))
    // :xbans:bukkit -> co.xenastudios.xbans.bukkit.r8: unique in the family, by construction.
    packageName.convention(
        "co.xenastudios." + path.split(':').filter { it.isNotEmpty() }.joinToString(".") { it.replace("-", "").lowercase() } + ".r8",
    )
    rules.convention(fileTree("r8") { include("*.pro") })
    startupClasses.convention(files("startup-classes.txt"))
}

plugins.withId("com.gradleup.shadow") {
    xenaR8.inputJar.convention(tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
}

val javaToolchains = the<JavaToolchainService>()

val shrinkJar = tasks.register<R8Shrink>("shrinkJar") {
    group = "build"
    description = "Shrinks the plugin jar with R8."
    r8.from(r8Classpath)
    inputJar.from(xenaR8.inputJar)
    libraries.from(xenaR8.libraries)
    rules.from(xenaR8.rules)
    packageName.set(xenaR8.packageName)
    startupClasses.from(xenaR8.startupClasses)
    // R8 runs on, and reads java.* from, the JDK running the build.
    launcher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion))
    })
    val name = xenaR8.inputJar.elements.map { it.single().asFile.name }
    outputJar.set(layout.buildDirectory.file(name.map { "r8/$it" }))
    mapping.set(layout.buildDirectory.file(name.map { "r8/${it.removeSuffix(".jar")}.map" }))
    generatedRules.set(layout.buildDirectory.file("r8/generated.pro"))
}

// A build of its own inside the repo (core/platform-fabric) sets xena.repoRoot to the repo's
// root, for gradle/jar-budgets.txt, and xena.projectPrefix to its folder's project path, so its
// jars' lines read like the root build's (:core:platform-fabric:sandbox:1.21.11).
val repoRoot: Directory = isolated.rootProject.projectDirectory.dir(providers.gradleProperty("xena.repoRoot").getOrElse("."))
val ownPath: String = providers.gradleProperty("xena.projectPrefix").getOrElse("") + path

val checkJarBudget = tasks.register<JarBudget>("checkJarBudget") {
    group = "verification"
    description = "Fails if the shrunk jar is over its budget in gradle/jar-budgets.txt."
    jar.set(shrinkJar.flatMap { it.outputJar })
    budgets.set(repoRoot.file("gradle/jar-budgets.txt"))
    projectPath.set(ownPath)
}

// Tests can run the jars themselves: the shrunk one as xena.r8.jar, the one R8 read as
// xena.r8.inputJar (the loader sandbox boots both). Inputs, so a new jar reruns them.
tasks.withType<Test>().configureEach {
    val shrunk = shrinkJar.flatMap { it.outputJar }
    val unshrunk = xenaR8.inputJar
    inputs.file(shrunk).withPropertyName("xenaR8Jar").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(unshrunk).withPropertyName("xenaR8InputJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dxena.r8.jar=${shrunk.get().asFile.path}", "-Dxena.r8.inputJar=${unshrunk.singleFile.path}")
    })
}

tasks.named("assemble") { dependsOn(shrinkJar) }
tasks.named("check") { dependsOn(checkJarBudget) }
