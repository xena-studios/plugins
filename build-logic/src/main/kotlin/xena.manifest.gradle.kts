import co.xenastudios.gradle.CheckXenaManifest
import co.xenastudios.gradle.Translations
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
//   ./gradlew checkTranslations     fails on a missing key or a broken tag in lang/*.yml; part of check
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
    langDirs.convention(Translations.FOLDERS.map { layout.projectDirectory.dir(it) })
    allowMissingTranslations.convention(false)
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

// core-jvm, for the translation check: it reads YAML with core-jvm's reader, which core-common
// can't have (no YAML library for wasmWasi yet). Its JVM runtime, as a Java library.
val translationTools = configurations.create("xenaTranslationTools") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies.add(translationTools.name, dependencies.project(mapOf("path" to ":core:core-jvm")))

val translationsChecked = layout.buildDirectory.file("xena/translations-checked")

val checkTranslations = tasks.register<JavaExec>("checkTranslations") {
    group = "verification"
    description = "Fails on a missing key, an unknown key or a broken tag in the plugin's lang/*.yml files."
    mainClass = "co.xenastudios.core.jvm.i18n.TranslationCheckMain"
    val source = reference.source
    val folders = reference.langDirs
    val allowMissing = reference.allowMissingTranslations
    val stamp = translationsChecked
    inputs.property("source", source)
    inputs.property("allowMissing", allowMissing)
    inputs.files(folders).withPropertyName("translations").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(stamp)
    outputs.cacheIf { true }
    classpath(translationTools)
    argumentProviders += CommandLineArgumentProvider { Translations.arguments(source.get(), folders.files, allowMissing.get()) }
    // Passing is what's cached: a run that fails writes nothing, so it runs again next time.
    doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n") }
}

// The JVM test runtime classpath: the plugin's classes, its tests' (where the source may live),
// and core-common's writer and checker.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    val test = the<KotlinMultiplatformExtension>().jvm().compilations.getByName("test")
    xenaManifest.configure { classpath(test.output.allOutputs, test.runtimeDependencyFiles) }
    checkTranslations.configure { classpath(test.output.allOutputs, test.runtimeDependencyFiles) }
}
plugins.withId("org.jetbrains.kotlin.jvm") {
    xenaManifest.configure { classpath(the<SourceSetContainer>()["test"].runtimeClasspath) }
    checkTranslations.configure { classpath(the<SourceSetContainer>()["test"].runtimeClasspath) }
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
    dependsOn(checkTranslations)
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
