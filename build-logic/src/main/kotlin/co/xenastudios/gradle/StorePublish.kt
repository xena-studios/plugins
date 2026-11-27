package co.xenastudios.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest

/** A dry run's report: printed, and kept beside the release as `dry-run/<store>.txt`. */
object DryRun {
    fun report(releaseDir: File, store: String, text: String, logger: Logger) {
        val file = releaseDir.resolve("dry-run/$store.txt")
        file.parentFile.mkdirs()
        file.writeText(text)
        logger.lifecycle("Dry run, nothing sent. {} would get:\n{}", store, text.trimEnd())
    }
}

/** Modrinth's version API, without Minotaur, whose one upload per project can't carry several jars. */
object Modrinth {
    const val API = "https://api.modrinth.com/v2/"

    /**
     * Each jar is its own Modrinth version, since loaders and game versions belong to a version:
     * `2.0.0` when the release has one Modrinth jar, `2.0.0+fabric-1.21.11` when it has several.
     */
    fun versionNumber(manifest: ReleaseManifest, jar: ReleaseFile): String =
        if (manifest.files.count { it.loaders.isNotEmpty() } > 1) "${manifest.version}+${jar.name}" else manifest.version

    fun data(manifest: ReleaseManifest, jar: ReleaseFile, projectId: String): String {
        val several = manifest.files.count { it.loaders.isNotEmpty() } > 1
        return JsonOutput.toJson(linkedMapOf(
            "project_id" to projectId,
            "version_number" to versionNumber(manifest, jar),
            "name" to if (several) "${manifest.title} (${jar.label})" else manifest.title,
            "changelog" to manifest.changelog,
            "version_type" to manifest.type.id,
            "loaders" to jar.loaders,
            "game_versions" to jar.gameVersions,
            "dependencies" to emptyList<Any>(),
            "featured" to (manifest.type == VersionType.RELEASE),
            "file_parts" to listOf("file"),
            "primary_file" to "file",
        ))
    }

    fun projectId(slug: String, token: String): String {
        val response = Stores.send(HttpRequest.newBuilder(URI("${API}project/${enc(slug)}")).header("Authorization", token).GET())
        if (response.statusCode() != 200) throw GradleException("Modrinth has no project $slug (HTTP ${response.statusCode()})")
        return (JsonSlurper().parseText(response.body()) as Map<*, *>)["id"] as String
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

/** Each JVM jar to the plugin's Modrinth project, skipping a version Modrinth already has. */
@DisableCachingByDefault(because = "Publishes to Modrinth")
abstract class ModrinthPublish : DefaultTask() {
    @get:Input
    abstract val projectSlug: Property<String>

    @get:Internal
    abstract val releaseDir: DirectoryProperty

    @get:Input
    abstract val dryRun: Property<Boolean>

    @get:Internal
    abstract val token: Property<String>

    @TaskAction
    fun publish() {
        val dir = releaseDir.get().asFile
        val manifest = ReleaseManifest.read(dir)
        val jars = manifest.files.filter { it.loaders.isNotEmpty() }
        val slug = projectSlug.get()
        // Every jar's payload is checked before the first is sent.
        val (loaders, gameVersions) = if (jars.isEmpty()) emptySet<String>() to emptySet() else StoreReference.modrinth
        StoreChecks.require("${manifest.title} on Modrinth", jars.flatMap { jar ->
            StoreChecks.modrinth(Modrinth.data(manifest, jar, "<the id of $slug>"), dir.resolve(jar.file), loaders, gameVersions)
                .map { "${jar.file}: $it" }
        })
        if (dryRun.get()) {
            val report = jars.joinToString("\n") { jar ->
                upload(Modrinth.data(manifest, jar, "<the id of $slug>"), "<token>", dir.resolve(jar.file)).describe()
            }
            DryRun.report(dir, "modrinth", report.ifEmpty { "no jar of this release goes to Modrinth\n" } +
                (if (jars.isEmpty()) "" else "  checked: Modrinth's version schema, its loaders and its Minecraft versions\n"), logger)
            return
        }
        val token = token.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException("MODRINTH_TOKEN isn't set")
        val projectId by lazy { Modrinth.projectId(slug, token) }
        for (jar in jars) {
            val number = Modrinth.versionNumber(manifest, jar)
            if (Stores.modrinthHas(slug, number, token)) {
                logger.lifecycle("Modrinth project {} already has {}", slug, number)
                continue
            }
            val response = upload(Modrinth.data(manifest, jar, projectId), token, dir.resolve(jar.file)).send()
            if (response.statusCode() != 200) {
                throw GradleException("Modrinth refused $number (HTTP ${response.statusCode()}): ${response.body()}")
            }
            logger.lifecycle("Published {} to Modrinth project {}", number, slug)
        }
    }

    private fun upload(data: String, token: String, jar: File) = Upload(
        store = "Modrinth",
        url = "${Modrinth.API}version",
        headers = mapOf("Authorization" to token),
        fields = mapOf("data" to data),
        files = listOf("file" to jar),
        secretHeaders = setOf("Authorization"),
    )
}

/** Hangar's upload API: one version with every Hangar jar (Paper, Velocity), each tagged with its platform. */
object Hangar {
    const val API = "https://hangar.papermc.io/api/v1/"

    /** Every Hangar project starts with these two channels. */
    fun channel(type: VersionType) = if (type == VersionType.RELEASE) "Release" else "Snapshot"

    /** Every version Hangar lists for a platform, from `/api/v1/platforms/{platform}/versions`. */
    fun platformVersions(json: String): Set<String> = (JsonSlurper().parseText(json) as List<*>).flatMap {
        val map = it as Map<*, *>
        listOf(map["version"] as String) + (map["subVersions"] as List<*>).map { v -> v as String }
    }.toSet()

    fun fetchPlatformVersions(platform: String): Set<String> {
        val response = Stores.send(HttpRequest.newBuilder(URI("${API}platforms/$platform/versions")).GET())
        if (response.statusCode() != 200) throw GradleException("Hangar answered HTTP ${response.statusCode()} for $platform's versions")
        return platformVersions(response.body())
    }

    /**
     * A jar's versions for its Hangar platform: [ReleaseFile.hangarFrom] on, or its
     * [ReleaseFile.hangarVersions], or its Minecraft versions (Paper's are Minecraft's), each
     * narrowed to the ones Hangar lists. Waterfall's are its own lines, never guessed.
     */
    fun platformVersions(jar: ReleaseFile, known: Set<String>?): List<String> {
        val platform = jar.platform.hangar!!
        val from = jar.hangarFrom
        val wanted = when {
            from != null -> {
                val listed = known ?: throw GradleException("${jar.file}: hangarFrom needs Hangar's $platform versions")
                listed.filter { compareVersions(it, from) >= 0 }.sortedWith(::compareVersions)
            }
            jar.hangarVersions.isNotEmpty() -> jar.hangarVersions
            jar.platform == ReleasePlatform.BUNGEECORD ->
                throw GradleException("${jar.file}: set hangarVersions (or hangarFrom) for Waterfall, whose versions are its own lines (1.21), not Minecraft's")
            else -> jar.gameVersions
        }
        val listed = known?.let { k -> wanted.filter { it in k } } ?: wanted
        if (listed.isEmpty()) throw GradleException("Hangar lists none of ${jar.file}'s versions for $platform ($wanted)")
        return listed
    }

    /** `3.4` < `3.4.0` < `3.5` < `4.0.0`, number by number. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: -1).compareTo(y.getOrNull(i) ?: -1)
            if (c != 0) return c
        }
        return 0
    }

    /**
     * The `versionUpload` part. Hangar refuses versions it doesn't list (it has 1.8.8 but not
     * 1.8.9), so each jar's are narrowed to [known]'s for its platform.
     */
    fun versionUpload(manifest: ReleaseManifest, jars: List<ReleaseFile>, known: Map<String, Set<String>>): String {
        val platforms = jars.map { it.platform.hangar!! }
        if (platforms.size != platforms.toSet().size) {
            throw GradleException("Hangar takes one jar per platform, not ${jars.joinToString { it.file }}")
        }
        val versions = jars.associate { jar ->
            val platform = jar.platform.hangar!!
            platform to platformVersions(jar, known[platform])
        }
        return JsonOutput.toJson(linkedMapOf(
            "version" to manifest.version,
            "channel" to channel(manifest.type),
            "description" to manifest.changelog,
            "pluginDependencies" to emptyMap<String, Any>(),
            "platformDependencies" to versions,
            "files" to jars.map { mapOf("platforms" to listOf(it.platform.hangar!!)) },
        ))
    }
}

@DisableCachingByDefault(because = "Publishes to Hangar")
abstract class HangarPublish : DefaultTask() {
    /** The Hangar project slug, such as `xPlots`. Unset: the release skips Hangar. */
    @get:Input
    @get:Optional
    abstract val projectSlug: Property<String>

    @get:Internal
    abstract val releaseDir: DirectoryProperty

    @get:Input
    abstract val dryRun: Property<Boolean>

    @get:Internal
    abstract val apiKey: Property<String>

    @TaskAction
    fun publish() {
        val dir = releaseDir.get().asFile
        val manifest = ReleaseManifest.read(dir)
        val jars = manifest.files.filter { it.platform.hangar != null }
        val slug = projectSlug.orNull
        val dryRun = dryRun.get()
        if (jars.isEmpty() || (slug == null && !dryRun)) {
            logger.lifecycle(if (jars.isEmpty()) "Hangar: no jar of this release goes there" else "Hangar: the plugin has no project yet")
            return
        }
        val known = jars.map { it.platform.hangar!! }.associateWith { Hangar.fetchPlatformVersions(it) }
        val versionUpload = Hangar.versionUpload(manifest, jars, known)
        StoreChecks.require("${manifest.title} on Hangar", StoreChecks.hangar(versionUpload, jars.map { dir.resolve(it.file) }, known))
        val upload = { jwt: String ->
            Upload(
                store = "Hangar",
                url = "${Hangar.API}projects/${slug ?: "<no project yet>"}/upload",
                headers = mapOf("Authorization" to jwt),
                fields = mapOf("versionUpload" to versionUpload),
                files = jars.map { "files" to dir.resolve(it.file) },
                secretHeaders = setOf("Authorization"),
            )
        }
        if (dryRun) {
            DryRun.report(dir, "hangar", upload("<token>").describe() +
                "  checked: Hangar's VersionUpload schema, its limits and its versions per platform\n" +
                (if (slug == null) "  (no Hangar project yet: a real release skips this)\n" else ""), logger)
            return
        }
        val key = apiKey.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException("HANGAR_API_TOKEN isn't set")
        if (Stores.hangarHas(slug!!, manifest.version, key)) {
            logger.lifecycle("Hangar project {} already has {}", slug, manifest.version)
            return
        }
        val response = upload(Stores.hangarJwt(key)).send()
        if (response.statusCode() !in 200..299) {
            throw GradleException("Hangar refused ${manifest.version} (HTTP ${response.statusCode()}): ${response.body()}")
        }
        logger.lifecycle("Published {} to Hangar project {}", manifest.version, slug)
    }
}
