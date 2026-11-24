import co.xenastudios.gradle.PinnedLibraries
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.reflect.TypeOf

// A plugin jar that downloads libraries at runtime instead of shading them: the Kotlin
// stdlib on servers without `libraries:` in `fetched`, loaded at startup, and the JDBC drivers
// in `onDemand`, downloaded only when a server without one opens a database (core-loader's
// OnDemandLibraries, listed as META-INF/xena/on-demand-libraries.txt):
//
//     dependencies {
//         fetched(libs.kotlin.stdlib) { isTransitive = false }
//         onDemand(libs.sqlite.jdbc) { isTransitive = false }
//     }
//
// Every jar either resolves to must have its SHA-256 in gradle/library-pins.txt, or the
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
val onDemand = configurations.dependencyScope("onDemand")
val onDemandJars = configurations.resolvable("onDemandJars") {
    extendsFrom(onDemand.get())
}

/** A task checking the jars [configuration] resolves to against the pins, and listing them at [listed] in the jar. */
fun pinned(name: String, what: String, configuration: Provider<out Configuration>, listed: String) =
    tasks.register<PinnedLibraries>(name) {
        description = "Checks the libraries $what against their pinned SHA-256 and lists them for the loader."
        val artifacts = configuration.map { it.incoming.artifacts }
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
        // The repo's, also from a build of its own inside it (xena.repoRoot, as xena.r8 reads it).
        pins.set(isolated.rootProject.projectDirectory.dir(providers.gradleProperty("xena.repoRoot").getOrElse(".")).file("gradle/library-pins.txt"))
        update.set(providers.gradleProperty("updatePins").map { true }.orElse(false))
        resource.set(listed)
        output.set(layout.buildDirectory.dir("generated/$name"))
    }

val pinnedLibraries = pinned("pinnedLibraries", "downloaded at startup", fetchedJars, "META-INF/xena/libraries.txt")
val pinnedOnDemand = pinned("pinnedOnDemandLibraries", "downloaded when a feature needs them", onDemandJars, "META-INF/xena/on-demand-libraries.txt")

the<SourceSetContainer>().named("main") {
    resources.srcDir(pinnedLibraries.flatMap { it.output })
    resources.srcDir(pinnedOnDemand.flatMap { it.output })
}

/** The declared `fetched` dependencies, `group:artifact:version`, for plugin.yml's `libraries:`. */
val fetchedCoordinates: Provider<List<String>> = fetched.map { scope ->
    scope.dependencies.map { "${it.group}:${it.name}:${it.version}" }
}
extensions.add(object : TypeOf<Provider<List<String>>>() {}, "fetchedCoordinates", fetchedCoordinates)
