import co.xenastudios.gradle.ApiJarCheck
import co.xenastudios.gradle.ApiSources
import co.xenastudios.gradle.MavenCentralCheck
import co.xenastudios.gradle.R8Shrink
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

// A rewritten plugin's developer API on Maven Central (decisions/2026-11-27-the-apis-on-maven-central.md),
// applied by xena.release to a plugin's release project when the plugin has an api/ folder:
//
//   co.xenastudios.<plugin>:<plugin>-api         the API, with the core's api types it exposes,
//                                                relocated as the jar has them (PlayerRef, EventBus...)
//   co.xenastudios.<plugin>:<plugin>-api-bukkit  its Bukkit events (co.xenastudios.<plugin>.api.bukkit)
//
// Each one's classes are the shipped Bukkit jar's own (shrinkJar), so a caller compiles against
// exactly what links at runtime; sources and Dokka's javadoc beside each, from the same relocated
// view of the code. Every task here is local:
//
//   ./gradlew <path>:releaseMavenBundle     the unsigned bundle in the release folder's maven/
//   ./gradlew <path>:releaseMavenCentral    checks it against Central's requirements and prints the upload
//   ./gradlew <path>:releaseApiDocs         Dokka's HTML, in build/dokka/api (what /api/<plugin>/ will serve)
//
// Nothing here signs or uploads: release.yml's maven job does, with gpg and the Central Portal's
// API, so the signing key and the Portal's token never reach a build script.
plugins {
    `maven-publish`
    id("org.jetbrains.dokka")
}

val release = the<XenaReleaseExtension>()
val pluginDir = checkNotNull(project.parent) { "xena.release-api is for a rewritten plugin's release project, <plugin>/<platform>" }
val id = pluginDir.name
val ownPackage = "co/xenastudios/$id"
val releaseOutput = extra["xenaReleaseOutput"] as Provider<Directory>
val root = rootProject.layout.projectDirectory

// The shipped jar, R8's output: the API's bytes, as servers run them.
val shipped = tasks.named<R8Shrink>("shrinkJar").flatMap { it.outputJar }
interface Archives {
    @get:javax.inject.Inject
    val operations: ArchiveOperations
}
val archives = objects.newInstance<Archives>().operations

// --- Sources: the JVM's view, relocated as the jar is ---

fun kotlinRoots(module: String) = listOf("commonMain", "jvmMain").map { root.dir("$module/src/$it/kotlin").asFile }
val apiSources = tasks.register<ApiSources>("apiSources") {
    description = "The API's sources as the jar has them: the JVM's view, the core's api relocated."
    pluginId = id
    val modules = listOf("$id/api", "core/core-api")
    modules.forEach { roots.from(kotlinRoots(it).filter { dir -> dir.isDirectory }) }
    pairs = modules.map { kotlinRoots(it).joinToString("|") }
    apiJar.from(tasks.named("apiJar"))
    outputDir = layout.buildDirectory.dir("api-sources/api")
}
val bukkitSources = tasks.register<ApiSources>("apiBukkitSources") {
    description = "The Bukkit events' sources (co.xenastudios.$id.api.bukkit), the core's api relocated."
    pluginId = id
    val dir = layout.projectDirectory.dir("src/main/kotlin").asFile
    roots.from(dir)
    pairs = listOf("${dir.resolve("none")}|$dir")
    only = listOf("$ownPackage/api/bukkit")
    outputDir = layout.buildDirectory.dir("api-sources/bukkit")
}

// --- Jars ---

val mavenDir = layout.buildDirectory.dir("maven-api")
fun Jar.reproducible() {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    destinationDirectory = mavenDir
}
val apiJar = tasks.register<Jar>("apiJar") {
    description = "$id-api: the API's classes from the shipped jar, with the core's api types it exposes."
    reproducible()
    archiveFileName = "$id-api.jar"
    val zips = archives
    from(shipped.map { zips.zipTree(it) }) {
        include("$ownPackage/api/**", "$ownPackage/core/api/**")
        exclude("$ownPackage/api/bukkit/**")
    }
    manifest.attributes("Automatic-Module-Name" to "co.xenastudios.$id.api")
}
val apiBukkitJar = tasks.register<Jar>("apiBukkitJar") {
    description = "$id-api-bukkit: the Bukkit events' classes from the shipped jar."
    reproducible()
    archiveFileName = "$id-api-bukkit.jar"
    val zips = archives
    from(shipped.map { zips.zipTree(it) }) { include("$ownPackage/api/bukkit/**") }
    manifest.attributes("Automatic-Module-Name" to "co.xenastudios.$id.api.bukkit")
}
val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") { reproducible(); archiveFileName = "$id-api-sources.jar"; from(apiSources) }
val apiBukkitSourcesJar = tasks.register<Jar>("apiBukkitSourcesJar") { reproducible(); archiveFileName = "$id-api-bukkit-sources.jar"; from(bukkitSources) }

// Every class an API jar names is in it (or, for the Bukkit events, in the API's): what a caller
// compiles against links. And PlayerRefs, the Java helpers, is there wherever PlayerRef is.
val unrelocated = listOf(":core:core-api", ":$id:api").map { path -> project(path).tasks.named<Jar>("jvmJar").flatMap { it.archiveFile } }
val checkApiJar = tasks.register<ApiJarCheck>("checkApiJar") {
    jar = apiJar.flatMap { it.archiveFile }
    ownPrefix = "$ownPackage/"
    sourceJars.from(unrelocated)
    marker = layout.buildDirectory.file("maven-api/checked-api")
}
val checkApiBukkitJar = tasks.register<ApiJarCheck>("checkApiBukkitJar") {
    jar = apiBukkitJar.flatMap { it.archiveFile }
    ownPrefix = "$ownPackage/"
    dependencies.from(apiJar)
    sourceJars.from(unrelocated)
    marker = layout.buildDirectory.file("maven-api/checked-api-bukkit")
}
tasks.named("check") { dependsOn(checkApiJar, checkApiBukkitJar) }

// --- Dokka: the API's reference, from the relocated sources, for Maven Central's javadoc jars
// and the site's /api/<plugin>/ ---

val dokkaClasspath = configurations.dependencyScope("apiDocsClasspath")
val dokkaClasspathFiles = configurations.resolvable("apiDocsClasspathFiles") { extendsFrom(dokkaClasspath.get()) }
dependencies {
    "apiDocsClasspath"(versionCatalogs.named("libs").findLibrary("kotlin-stdlib").get()) { isTransitive = false }
    "apiDocsClasspath"(versionCatalogs.named("libs").findLibrary("spigot-api-floor").get()) { isTransitive = false }
}

dokka {
    // Its folder in the HTML, and so in /api/<plugin>/'s addresses.
    moduleName = "$id-api"
    moduleVersion = provider { project.version.toString() }
    dokkaPublications.html { outputDirectory = layout.buildDirectory.dir("dokka/api") }
    // The project's own code isn't the API: only the relocated view below.
    dokkaSourceSets.configureEach { if (name != "api") suppress = true }
    dokkaSourceSets.register("api") {
        displayName = "JVM"
        analysisPlatform = KotlinPlatform.JVM
        jdkVersion = 21
        sourceRoots.from(apiSources, bukkitSources)
        classpath.from(dokkaClasspathFiles)
        documentedVisibilities(VisibilityModifier.Public, VisibilityModifier.Protected)
        reportUndocumented = false
    }
}
val apiDocs = tasks.register("releaseApiDocs") {
    group = "documentation"
    description = "Dokka's HTML for the API, in build/dokka/api: Maven Central's javadoc and the site's /api/$id/."
    dependsOn("dokkaGeneratePublicationHtml")
}
val javadocJar = tasks.register<Jar>("apiJavadocJar") {
    reproducible()
    archiveFileName = "$id-api-javadoc.jar"
    dependsOn(apiDocs)
    from(layout.buildDirectory.dir("dokka/api"))
}

// --- The Maven bundle ---

val apiProject = project(":$id:api")
fun MavenPom.common(name: Provider<String>, about: Provider<String>) {
    this.name = name
    description = about
    inceptionYear = "2026"
    url = "https://plugins.xenastudios.co/$id/developer-api/"
    licenses {
        license {
            this.name = "GPL-3.0-only"
            url = "https://www.gnu.org/licenses/gpl-3.0.txt"
            distribution = "repo"
        }
    }
    developers { developer { this.id = "xena-studios"; this.name = "Xena Studios"; url = "https://xenastudios.co" } }
    organization { this.name = "Xena Studios"; url = "https://xenastudios.co" }
    scm {
        url = "https://github.com/xena-studios/plugins"
        connection = "scm:git:https://github.com/xena-studios/plugins.git"
        developerConnection = "scm:git:ssh://git@github.com/xena-studios/plugins.git"
    }
    issueManagement { system = "GitHub"; url = "https://github.com/xena-studios/plugins/issues" }
}
fun MavenPom.dependsOn(vararg coordinates: Triple<String, String, String>) = withXml {
    val deps = asNode().appendNode("dependencies")
    coordinates.forEach { (group, artifact, version) ->
        deps.appendNode("dependency").apply {
            appendNode("groupId", group); appendNode("artifactId", artifact); appendNode("version", version); appendNode("scope", "compile")
        }
    }
}
val kotlinVersion = versionCatalogs.named("libs").findVersion("kotlin").get().requiredVersion

// The version and the release folder are the build script's, set after this convention is applied.
afterEvaluate {
val apiVersion = project.version.toString()
publishing {
    publications {
        register<MavenPublication>("api") {
            groupId = "co.xenastudios.$id"
            artifactId = "$id-api"
            version = apiVersion
            artifact(apiJar)
            artifact(apiSourcesJar) { classifier = "sources" }
            artifact(javadocJar) { classifier = "javadoc" }
            pom {
                common(release.displayName.map { "$it API" }, release.displayName.map { apiProject.description ?: "$it's developer API." })
                // Kotlin's stdlib, which every Xena plugin downloads at runtime, for its types (Uuid, functions).
                dependsOn(Triple("org.jetbrains.kotlin", "kotlin-stdlib", kotlinVersion))
            }
        }
        register<MavenPublication>("apiBukkit") {
            groupId = "co.xenastudios.$id"
            artifactId = "$id-api-bukkit"
            version = apiVersion
            artifact(apiBukkitJar)
            artifact(apiBukkitSourcesJar) { classifier = "sources" }
            artifact(javadocJar) { classifier = "javadoc" }
            pom {
                common(release.displayName.map { "$it API for Bukkit" }, release.displayName.map { "$it's events as Bukkit events, for plugins and Skript scripts listening the Bukkit way. The server's own API (Paper, Spigot) is the caller's." })
                dependsOn(Triple("co.xenastudios.$id", "$id-api", apiVersion))
            }
        }
    }
    repositories {
        // The release folder's maven/: what the maven job signs and uploads.
        maven {
            name = "ReleaseBundle"
            url = uri(releaseOutput.get().dir("maven").asFile)
        }
    }
}
}
// Artifacts without a component: no Gradle module metadata to publish.
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

val cleanBundle = tasks.register<Delete>("releaseMavenBundleClean") { delete(releaseOutput.map { it.dir("maven") }) }
tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToReleaseBundleRepository") }.configureEach {
    dependsOn(cleanBundle, checkApiJar, checkApiBukkitJar)
}
val bundle = tasks.register("releaseMavenBundle") {
    group = "publishing"
    description = "Writes the API's unsigned Maven Central bundle into the release folder's maven/ (after releaseAssemble)."
    dependsOn("publishAllPublicationsToReleaseBundleRepository")
    // A repository's maven-metadata.xml isn't part of a Central bundle: the Portal writes its own.
    val repo = releaseOutput.map { it.dir("maven").asFile }
    doLast { repo.get().walkTopDown().filter { it.isFile && it.name.startsWith("maven-metadata.xml") }.forEach { it.delete() } }
}
tasks.named("releaseAssemble") { finalizedBy(bundle) }
cleanBundle.configure { mustRunAfter("releaseAssemble") }

val central = tasks.register<MavenCentralCheck>("releaseMavenCentral") {
    group = "publishing"
    description = "Checks the Maven Central bundle against Central's requirements and prints what would be uploaded."
    releaseDir = releaseOutput
    mustRunAfter(bundle)
}
tasks.named("releasePublish") { dependsOn(central) }
