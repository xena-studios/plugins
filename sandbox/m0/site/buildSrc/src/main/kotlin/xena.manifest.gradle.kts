// Writes a plugin's reference manifest (commands, permissions, config, messages, placeholders,
// events and platforms) from its code, and checks the docs' committed copy against it.
//
//   ./gradlew xenaManifest          build/xena/<id>/manifest.json, config.yml, manifest.schema.json
//   ./gradlew checkXenaManifest     fails if docs/src/generated differs (part of ./gradlew check)
//   ./gradlew updateXenaManifest    copies the fresh files into docs/src/generated
plugins {
    java
}

val xena = extensions.create<XenaExtension>("xena")

val out = layout.buildDirectory.dir("xena")

val xenaManifest = tasks.register<JavaExec>("xenaManifest") {
    group = "documentation"
    description = "Writes the plugin's reference manifest and its JSON Schema."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "co.xenastudios.m0.site.core.DumpKt"
    val version = project.version.toString()
    val definition = xena.definition
    val platforms = xena.platforms
    val langDir = layout.projectDirectory.dir("src/main/resources/lang")
    val outDir = out
    inputs.property("version", version)
    inputs.property("definition", definition)
    inputs.property("platforms", platforms)
    inputs.dir(langDir).withPropertyName("lang").optional()
    outputs.dir(out)
    outputs.cacheIf { true }
    argumentProviders += CommandLineArgumentProvider {
        listOf("--definition", definition.get(), "--version", version, "--lang", langDir.asFile.path, "--out", outDir.get().asFile.path) +
            platforms.get().flatMap { listOf("--platform", it) }
    }
    doFirst { outDir.get().asFile.deleteRecursively() }
}

val checkXenaManifest = tasks.register<CheckGenerated>("checkXenaManifest") {
    group = "verification"
    description = "Fails when docs/src/generated holds a stale copy of this plugin's manifest."
    fresh = out
    committed = xena.docsDir
    fixCommand = "./gradlew updateXenaManifest"
    dependsOn(xenaManifest)
}

tasks.named("check") { dependsOn(checkXenaManifest) }

tasks.register<Copy>("updateXenaManifest") {
    group = "documentation"
    description = "Copies the fresh manifest into docs/src/generated, to commit."
    from(xenaManifest)
    into(xena.docsDir)
}
