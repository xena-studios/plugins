import co.xenastudios.gradle.CheckXenaManifest
import co.xenastudios.gradle.UpdateXenaManifest
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// A plugin's reference manifest for the docs (ROADMAP.md, Site): its commands, permissions,
// config, placeholders, messages and events, from the objects it runs on, plus the platforms
// its build names. Applied beside xena.kmp (or xena.kotlin-jvm):
//
//   xenaReference {
//       pluginId = "xhomes"
//       source = "co.xenastudios.xhomes.HomesReference"   // a ManifestSource object
//       platform("paper", "Paper", "1.8.8", java = 8, since = "2.0.0")
//   }
//
//   ./gradlew xenaManifest          build/xena/<id>/manifest.json and config.yml, and manifest.schema.json
//   ./gradlew checkXenaManifest     fails when docs/src/generated has a stale copy; part of check
//   ./gradlew updateXenaManifest    copies the fresh files into docs/src/generated, to commit
//
// The writer is core-common's (co.xenastudios.core.common.manifest.ManifestMain), run on the
// plugin's JVM test runtime classpath, so the source can build the plugin on core-testing's fake
// platform without a server. -PupdateGolden updates the copy instead of failing, as it does for
// the config goldens.
plugins {
    base
}

val reference = extensions.create<XenaReferenceExtension>("xenaReference").apply {
    docsDir.convention(rootProject.layout.projectDirectory.dir("docs/src/generated"))
}

val out = layout.buildDirectory.dir("xena/reference")

val xenaManifest = tasks.register<JavaExec>("xenaManifest") {
    group = "documentation"
    description = "Writes the plugin's reference manifest and its JSON Schema."
    mainClass = "co.xenastudios.core.common.manifest.ManifestMain"
    val version = project.version.toString()
    val source = reference.source
    val platforms = reference.platforms
    val outDir = out
    inputs.property("version", version)
    inputs.property("source", source)
    inputs.property("platforms", platforms)
    outputs.dir(outDir)
    outputs.cacheIf { true }
    argumentProviders += CommandLineArgumentProvider {
        listOf("--source", source.get(), "--version", version, "--out", outDir.get().asFile.path) +
            platforms.get().flatMap { listOf("--platform", it) }
    }
    // Only what this run writes: a file it no longer writes mustn't linger and be compared.
    doFirst { outDir.get().asFile.deleteRecursively() }
}

// The JVM test runtime classpath: the plugin's classes, its tests' (where the source may live),
// and core-common's writer.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    val test = the<KotlinMultiplatformExtension>().jvm().compilations.getByName("test")
    xenaManifest.configure { classpath(test.output.allOutputs, test.runtimeDependencyFiles) }
}
plugins.withId("org.jetbrains.kotlin.jvm") {
    xenaManifest.configure { classpath(the<SourceSetContainer>()["test"].runtimeClasspath) }
}

val updateGolden = providers.gradleProperty("updateGolden").map { true }.orElse(false)

val checkXenaManifest = tasks.register<CheckXenaManifest>("checkXenaManifest") {
    group = "verification"
    description = "Fails when docs/src/generated holds a stale copy of the plugin's reference."
    fresh = xenaManifest.map { out.get() }
    committed = reference.docsDir
    plugin = reference.pluginId
    update = updateGolden
}

tasks.check {
    dependsOn(checkXenaManifest)
    // The check's comparison is build-logic's code, which has tests of its own.
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

tasks.register<UpdateXenaManifest>("updateXenaManifest") {
    group = "documentation"
    description = "Copies the plugin's fresh reference into docs/src/generated, to commit."
    fresh = xenaManifest.map { out.get() }
    committed = reference.docsDir
    plugin = reference.pluginId
}
