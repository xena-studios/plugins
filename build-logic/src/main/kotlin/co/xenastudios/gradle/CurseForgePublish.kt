package co.xenastudios.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.net.http.HttpRequest

/**
 * CurseForge's upload API (M0, sandbox/m0/mods/RESULTS.md): one token, three hosts, one per
 * project class. Game versions, loaders, environments and Java versions are all "game
 * versions" there, each with a type, and an upload names them by id, so the ids are looked up
 * from `/api/game/versions` and `/api/game/version-types` on each release.
 */
object CurseForge {
    data class GameVersion(val id: Int, val typeId: Int, val name: String)

    data class GameVersionType(val id: Int, val name: String, val slug: String)

    data class Resolved(val ids: List<Int>, val missing: List<String>)

    fun parseVersions(json: String): List<GameVersion> = (JsonSlurper().parseText(json) as List<*>).map {
        val map = it as Map<*, *>
        GameVersion((map["id"] as Number).toInt(), (map["gameVersionTypeID"] as Number).toInt(), map["name"] as String)
    }

    fun parseTypes(json: String): List<GameVersionType> = (JsonSlurper().parseText(json) as List<*>).map {
        val map = it as Map<*, *>
        GameVersionType((map["id"] as Number).toInt(), map["name"] as String, map["slug"] as String)
    }

    /**
     * The ids to upload a jar with. Minecraft versions the host doesn't know are left out (a
     * Bukkit jar names every release from its floor, and dev.bukkit doesn't list them all),
     * but at least one must be known, and a mod's loader and the Server environment must be.
     */
    fun resolve(
        projectClass: CurseForgeClass,
        types: List<GameVersionType>,
        versions: List<GameVersion>,
        minecraft: List<String>,
        loader: String?,
        java: Int?,
    ): Resolved {
        val typeSlugs = types.associate { it.id to it.slug }
        fun ofType(predicate: (String) -> Boolean) = versions.filter { v -> typeSlugs[v.typeId]?.let(predicate) == true }
        fun find(pool: List<GameVersion>, name: String) = pool.firstOrNull { it.name.equals(name, ignoreCase = true) }

        val minecraftPool = when (projectClass) {
            CurseForgeClass.MODS -> ofType { it.startsWith("minecraft-") }
            CurseForgeClass.BUKKIT -> ofType { it == "bukkit" }
            CurseForgeClass.HYTALE -> versions
        }
        val ids = mutableListOf<Int>()
        val missing = mutableListOf<String>()
        minecraft.forEach { name -> find(minecraftPool, name)?.let { ids += it.id } ?: run { missing += name } }
        if (ids.isEmpty()) {
            throw GradleException("CurseForge (${projectClass.name}) knows none of the game versions $minecraft")
        }
        if (projectClass == CurseForgeClass.MODS) {
            val required = listOfNotNull(loader?.let { "modloader" to it }, "environment" to "Server")
            required.forEach { (slug, name) ->
                ids += find(ofType { it == slug }, name)?.id
                    ?: throw GradleException("CurseForge has no \"$name\" game version of type $slug")
            }
            // Optional: not every host lists every Java version.
            java?.let { find(ofType { it == "java" }, "Java $it") }?.let { ids += it.id }
        }
        return Resolved(ids, missing)
    }

    /** The `metadata` part of an upload. */
    fun metadata(displayName: String, changelog: String, type: VersionType, gameVersions: List<Int>?): String =
        JsonOutput.toJson(linkedMapOf<String, Any>(
            "changelog" to changelog,
            "changelogType" to "markdown",
            "displayName" to displayName,
            "releaseType" to type.id,
        ).apply { if (gameVersions != null) put("gameVersions", gameVersions) })

    fun upload(host: String, projectId: Int, token: String, metadata: String, jar: File) = Upload(
        store = "CurseForge",
        url = "${host}projects/$projectId/upload-file",
        headers = mapOf("X-Api-Token" to token),
        fields = mapOf("metadata" to metadata),
        files = listOf("file" to jar),
        secretHeaders = setOf("X-Api-Token"),
    )

    fun get(host: String, path: String, token: String): String {
        val response = Stores.send(HttpRequest.newBuilder(URI(host + path)).header("X-Api-Token", token).GET())
        if (response.statusCode() != 200) {
            throw GradleException("CurseForge answered HTTP ${response.statusCode()} for $path: ${response.body()}")
        }
        return response.body()
    }

    /** The new file's id, from the upload's answer. */
    fun uploadedId(status: Int, body: String): Int {
        if (status != 200) throw GradleException("CurseForge refused the upload (HTTP $status): $body")
        return ((JsonSlurper().parseText(body) as Map<*, *>)["id"] as Number).toInt()
    }
}

/**
 * Uploads the release's jars for one CurseForge project class (Bukkit, mods or Hytale) to the
 * plugin's project of that class. CurseForge's upload API can't list a project's files, so,
 * unlike the other stores, a rerun can't tell what's already there: rerun only the CurseForge
 * job that failed, or remove the file on CurseForge first.
 */
@DisableCachingByDefault(because = "Publishes to CurseForge")
abstract class CurseForgePublish : DefaultTask() {
    @get:Input
    abstract val projectClass: Property<CurseForgeClass>

    /** The CurseForge project id of that class. Unset: the release skips it. */
    @get:Input
    @get:Optional
    abstract val projectId: Property<Int>

    /** Where `releaseAssemble` put the jars and `release.json`. */
    @get:Internal
    abstract val releaseDir: DirectoryProperty

    @get:Input
    abstract val dryRun: Property<Boolean>

    /** The API host; tests point it at a local server. */
    @get:Input
    abstract val host: Property<String>

    @get:Internal
    abstract val token: Property<String>

    init {
        host.convention(projectClass.map { it.host })
    }

    @TaskAction
    fun publish() {
        val dir = releaseDir.get().asFile
        val manifest = ReleaseManifest.read(dir)
        val cls = projectClass.get()
        val jars = manifest.files.filter { it.platform.curseForge == cls }
        if (jars.isEmpty()) {
            logger.lifecycle("CurseForge ({}): no jar of this release goes there", cls.name)
            return
        }
        val dryRun = dryRun.get()
        val id = projectId.orNull
        if (id == null && !dryRun) {
            logger.lifecycle("CurseForge ({}): the plugin has no project of this class yet (xenaRelease)", cls.name)
            return
        }
        val token = token.orNull?.takeIf { it.isNotBlank() }
        if (token == null && !dryRun) throw GradleException("CURSEFORGE_TOKEN isn't set")
        val host = host.get()
        // A dry run with a token looks the ids up (read-only), so the token and the Hytale id
        // can be checked before a real release.
        val lookup = token?.let {
            CurseForge.parseTypes(CurseForge.get(host, "game/version-types", it)) to
                CurseForge.parseVersions(CurseForge.get(host, "game/versions", it))
        }
        val report = StringBuilder()
        for (jar in jars) {
            val resolved = lookup?.let { (types, versions) ->
                CurseForge.resolve(cls, types, versions, jar.gameVersions, jar.platform.curseForgeLoader, jar.java)
            }
            resolved?.missing?.takeIf { it.isNotEmpty() }?.let {
                logger.warn("CurseForge ({}) doesn't list {}; {} goes up without them", cls.name, it, jar.file)
            }
            val displayName = if (jars.size > 1) "${manifest.title} (${jar.label})" else manifest.title
            val metadata = CurseForge.metadata(displayName, manifest.changelog, manifest.type, resolved?.ids)
            StoreChecks.require("${jar.file} on CurseForge (${cls.name})", StoreChecks.curseForge(metadata, dir.resolve(jar.file), resolved != null))
            val upload = CurseForge.upload(host, id ?: 0, token ?: "", metadata, dir.resolve(jar.file))
            if (dryRun) {
                report.append(upload.describe())
                if (resolved == null) {
                    report.appendLine("  gameVersions: looked up with a token, from: " +
                        (jar.gameVersions + listOfNotNull(jar.platform.curseForgeLoader, "Server".takeIf { cls == CurseForgeClass.MODS }))
                            .joinToString(", "))
                }
                if (id == null) report.appendLine("  (no ${cls.name} project id yet: a real release skips this)")
                report.appendLine()
                continue
            }
            val response = upload.send()
            val file = CurseForge.uploadedId(response.statusCode(), response.body())
            logger.lifecycle("Published {} to CurseForge project {} (file {})", jar.file, id, file)
        }
        if (dryRun) DryRun.report(dir, "curseforge-${cls.name.lowercase()}", report.toString(), logger)
    }
}
