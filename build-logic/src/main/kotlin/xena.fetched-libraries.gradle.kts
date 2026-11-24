import co.xenastudios.gradle.PinnedLibraries
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.reflect.TypeOf

// A plugin jar that downloads libraries at runtime instead of shading them: the Kotlin
// stdlib on servers without `libraries:`, and later the optional ones. Put them in
// `fetched`:
//
//     dependencies { fetched(libs.kotlin.stdlib) { isTransitive = false } }
//
// Every jar `fetched` resolves to must have its SHA-256 in gradle/library-pins.txt, or the
// build fails (-PupdatePins writes them after a deliberate bump). The list, with the hashes,
// goes into the jar as META-INF/xena/libraries.txt, which the core's loader
// (core/core-loader) reads. `fetchedCoordinates` gives what was declared, for plugin.yml's
// `libraries:`.
plugins {
    java
}

val fetched = configurations.dependencyScope("fetched")
val fetchedJars = configurations.resolvable("fetchedJars") {
    extendsFrom(fetched.get())
}

val pinnedLibraries = tasks.register<PinnedLibraries>("pinnedLibraries") {
    description = "Checks the libraries downloaded at runtime against their pinned SHA-256 and lists them for the loader."
    val artifacts = fetchedJars.map { it.incoming.artifacts }
    coordinates.set(artifacts.flatMap { collection ->
        collection.resolvedArtifacts.map { results ->
            results.map { result ->
                val id = result.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: throw GradleException("fetched libraries come from Maven Central, not ${result.id.componentIdentifier}")
                "${id.group}:${id.module}:${id.version}"
            }
        }
    })
    jars.from(artifacts.map { it.artifactFiles })
    pins.set(isolated.rootProject.projectDirectory.file("gradle/library-pins.txt"))
    update.set(providers.gradleProperty("updatePins").map { true }.orElse(false))
    output.set(layout.buildDirectory.dir("generated/xena-libraries"))
}

the<SourceSetContainer>().named("main") {
    resources.srcDir(pinnedLibraries.flatMap { it.output })
}

/** The declared `fetched` dependencies, `group:artifact:version`, for plugin.yml's `libraries:`. */
val fetchedCoordinates: Provider<List<String>> = fetched.map { scope ->
    scope.dependencies.map { "${it.group}:${it.name}:${it.version}" }
}
extensions.add(object : TypeOf<Provider<List<String>>>() {}, "fetchedCoordinates", fetchedCoordinates)
