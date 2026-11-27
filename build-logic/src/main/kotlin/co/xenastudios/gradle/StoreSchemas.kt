package co.xenastudios.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.net.URI
import java.net.http.HttpRequest

/**
 * The JSON Schema subset the stores' request schemas use (OpenAPI 3's): `type` (with
 * `nullable`), `properties`, `required`, `additionalProperties`, `items`, `enum`, `pattern`,
 * `minLength`/`maxLength`, `minItems`/`maxItems`, `uniqueItems`, `minimum`, `allOf` and local
 * `$ref`s. Enough to check a payload against a store's published schema without a library on
 * build-logic's class path.
 */
class JsonSchema(private val root: Map<*, *>) {
    /** Every way [value] breaks the schema, as "path: problem", or none. */
    fun problems(value: Any?): List<String> = mutableListOf<String>().also { check(root, value, "$", it) }

    private fun resolve(schema: Map<*, *>): Map<*, *> {
        val ref = schema["\$ref"] as String? ?: return schema
        if (!ref.startsWith("#/")) throw GradleException("Only local \$refs are supported: $ref")
        var node: Any? = root
        for (part in ref.removePrefix("#/").split('/')) node = (node as Map<*, *>)[part]
        return resolve(node as Map<*, *>? ?: throw GradleException("No $ref in the schema"))
    }

    private fun check(raw: Map<*, *>, value: Any?, path: String, out: MutableList<String>) {
        val schema = resolve(raw)
        (schema["allOf"] as List<*>?)?.forEach { check(it as Map<*, *>, value, path, out) }
        if (value == null) {
            if (schema["type"] != null && schema["nullable"] != true) out += "$path: is null, must be ${schema["type"]}"
            return
        }
        when (val type = schema["type"] as String?) {
            null -> {}
            "object" -> if (value !is Map<*, *>) out += "$path: must be an object" else checkObject(schema, value, path, out)
            "array" -> if (value !is List<*>) out += "$path: must be an array" else checkArray(schema, value, path, out)
            "string" -> if (value !is String) out += "$path: must be a string" else checkString(schema, value, path, out)
            "integer" -> if (value !is Number || value.toDouble() % 1.0 != 0.0) out += "$path: must be an integer" else checkNumber(schema, value, path, out)
            "number" -> if (value !is Number) out += "$path: must be a number" else checkNumber(schema, value, path, out)
            "boolean" -> if (value !is Boolean) out += "$path: must be true or false"
            else -> throw GradleException("Unsupported schema type $type")
        }
        (schema["enum"] as List<*>?)?.let { if (value !in it) out += "$path: is ${render(value)}, must be one of ${it.joinToString { e -> render(e) }}" }
    }

    private fun checkObject(schema: Map<*, *>, value: Map<*, *>, path: String, out: MutableList<String>) {
        val properties = schema["properties"] as Map<*, *>? ?: emptyMap<Any, Any>()
        (schema["required"] as List<*>?)?.forEach { if (it !in value) out += "$path: is missing \"$it\"" }
        for ((key, item) in value) {
            val property = properties[key] as Map<*, *>?
            when {
                property != null -> check(property, item, "$path.$key", out)
                schema["additionalProperties"] == false -> out += "$path: has \"$key\", which the store doesn't take"
                schema["additionalProperties"] is Map<*, *> -> check(schema["additionalProperties"] as Map<*, *>, item, "$path.$key", out)
            }
        }
    }

    private fun checkArray(schema: Map<*, *>, value: List<*>, path: String, out: MutableList<String>) {
        (schema["minItems"] as Number?)?.let { if (value.size < it.toInt()) out += "$path: has ${value.size} items, at least $it needed" }
        (schema["maxItems"] as Number?)?.let { if (value.size > it.toInt()) out += "$path: has ${value.size} items, at most $it allowed" }
        if (schema["uniqueItems"] == true && value.toSet().size != value.size) out += "$path: has repeated items"
        (schema["items"] as Map<*, *>?)?.let { items -> value.forEachIndexed { i, item -> check(items, item, "$path[$i]", out) } }
    }

    private fun checkString(schema: Map<*, *>, value: String, path: String, out: MutableList<String>) {
        (schema["minLength"] as Number?)?.let { if (value.length < it.toInt()) out += "$path: is ${value.length} characters, at least $it needed" }
        (schema["maxLength"] as Number?)?.let { if (value.length > it.toInt()) out += "$path: is ${value.length} characters, at most $it allowed" }
        (schema["pattern"] as String?)?.let { if (!Regex(it).containsMatchIn(value)) out += "$path: ${render(value)} doesn't match $it" }
    }

    private fun checkNumber(schema: Map<*, *>, value: Number, path: String, out: MutableList<String>) {
        (schema["minimum"] as Number?)?.let { if (value.toDouble() < it.toDouble()) out += "$path: is $value, at least $it needed" }
    }

    private fun render(value: Any?): String = if (value is String) "\"${value.take(60)}\"" else value.toString()

    companion object {
        /** A schema from build-logic's resources, `xena/release/schemas/<name>.json`. */
        fun load(name: String): JsonSchema {
            val text = JsonSchema::class.java.getResource("/xena/release/schemas/$name.json")?.readText()
                ?: throw GradleException("No schema $name in build-logic")
            return JsonSchema(JsonSlurper().parseText(text) as Map<*, *>)
        }
    }
}

/**
 * What the stores themselves list, which a schema can't say: Modrinth's loaders and Minecraft
 * versions (Hangar's versions per platform are [Hangar.fetchPlatformVersions]). A dry run reads
 * them as a release does (public GETs, no token), so an upload with a version or loader the store
 * would refuse fails before anything is sent.
 */
object StoreReference {
    /** Modrinth's loader names that take plugins or mods, from `/v2/tag/loader`. */
    fun modrinthLoaders(json: String): Set<String> = (JsonSlurper().parseText(json) as List<*>).map { it as Map<*, *> }
        .filter { (it["supported_project_types"] as List<*>).any { t -> t == "plugin" || t == "mod" } }
        .map { it["name"] as String }.toSet()

    /** Every Minecraft version Modrinth lists, snapshots included, from `/v2/tag/game_version`. */
    fun modrinthGameVersions(json: String): Set<String> =
        (JsonSlurper().parseText(json) as List<*>).map { (it as Map<*, *>)["version"] as String }.toSet()

    private fun get(url: String): String {
        val response = Stores.send(HttpRequest.newBuilder(URI(url)).GET())
        if (response.statusCode() != 200) throw GradleException("$url answered HTTP ${response.statusCode()}")
        return response.body()
    }

    /** Modrinth's loaders and game versions, read once per build. */
    val modrinth: Pair<Set<String>, Set<String>> by lazy {
        modrinthLoaders(get(Modrinth.API + "tag/loader")) to modrinthGameVersions(get(Modrinth.API + "tag/game_version"))
    }
}

/**
 * Each store's upload checked against its published schema (vendored in build-logic's
 * resources, `xena/release/schemas/`, each naming where it comes from) and against what the store
 * lists ([StoreReference]). Every store task runs it before it sends, or, in a dry run, instead of
 * sending: a payload a store would refuse fails the run before any store gets anything.
 */
object StoreChecks {
    private val slurper = JsonSlurper()

    private fun json(text: String): Any? = slurper.parseText(text)

    /** Modrinth's `data` part (Labrinth's `InitialVersionData`), and its loaders and versions. */
    fun modrinth(data: String, jar: java.io.File, loaders: Set<String>?, gameVersions: Set<String>?): List<String> {
        val parsed = json(data) as Map<*, *>
        val problems = JsonSchema.load("modrinth-version").problems(parsed).toMutableList()
        loaders?.let { known -> (parsed["loaders"] as List<*>?)?.filter { it !in known }?.forEach { problems += "Modrinth has no loader \"$it\" for plugins or mods" } }
        gameVersions?.let { known -> (parsed["game_versions"] as List<*>?)?.filter { it !in known }?.forEach { problems += "Modrinth doesn't list Minecraft $it" } }
        val parts = parsed["file_parts"] as List<*>? ?: emptyList<Any>()
        if (parsed["primary_file"] != null && parsed["primary_file"] !in parts) problems += "primary_file ${parsed["primary_file"]} isn't one of file_parts"
        problems += fileProblems("Modrinth", listOf(jar), maxFile = null, maxTotal = null)
        return problems
    }

    /** Hangar's `versionUpload` part (its `VersionUpload`), its versions, and its file limits. */
    fun hangar(versionUpload: String, jars: List<java.io.File>, known: Map<String, Set<String>>?): List<String> {
        val parsed = json(versionUpload) as Map<*, *>
        val problems = JsonSchema.load("hangar-version-upload").problems(parsed).toMutableList()
        val platforms = (parsed["platformDependencies"] as Map<*, *>?).orEmpty()
        known?.let {
            platforms.forEach { (platform, versions) ->
                val listed = it[platform] ?: emptySet()
                (versions as List<*>).filter { v -> v !in listed }.forEach { v -> problems += "Hangar doesn't list $platform $v" }
            }
        }
        val filePlatforms = (parsed["files"] as List<*>?).orEmpty().flatMap { ((it as Map<*, *>)["platforms"] as List<*>?).orEmpty() }
        if (filePlatforms.toSet() != platforms.keys) problems += "Hangar's files are for ${filePlatforms.sortedBy { it.toString() }}, its platformDependencies for ${platforms.keys.sortedBy { it.toString() }}"
        if (jars.size != (parsed["files"] as List<*>?)?.size) problems += "Hangar gets ${jars.size} jars for ${(parsed["files"] as List<*>?)?.size} files"
        problems += fileProblems("Hangar", jars, maxFile = HANGAR_MAX_FILE, maxTotal = HANGAR_MAX_TOTAL)
        return problems
    }

    /** CurseForge's `metadata` part, as its upload API documents it. */
    fun curseForge(metadata: String, jar: java.io.File, resolved: Boolean): List<String> {
        val parsed = json(metadata) as Map<*, *>
        val problems = JsonSchema.load("curseforge-upload-metadata").problems(parsed).toMutableList()
        // gameVersions are ids, looked up with a token; without one they can't be in the payload.
        if (resolved && parsed["gameVersions"] == null) problems += "CurseForge needs gameVersions"
        problems += fileProblems("CurseForge", listOf(jar), maxFile = null, maxTotal = null)
        return problems
    }

    /** voxel.shop's `postUpdate` form fields. */
    fun voxel(fields: Map<String, String>, jar: java.io.File): List<String> =
        JsonSchema.load("voxel-post-update").problems(fields) + fileProblems("voxel.shop", listOf(jar), maxFile = null, maxTotal = null)

    private fun fileProblems(store: String, jars: List<java.io.File>, maxFile: Long?, maxTotal: Long?): List<String> = buildList {
        jars.forEach {
            if (!it.isFile) add("$store: ${it.name} is missing")
            else if (!it.name.endsWith(".jar")) add("$store: ${it.name} isn't a jar")
            else if (maxFile != null && it.length() > maxFile) add("$store takes files up to $maxFile bytes; ${it.name} is ${it.length()}")
        }
        val total = jars.sumOf { it.length() }
        if (maxTotal != null && total > maxTotal) add("$store takes up to $maxTotal bytes a version; these jars are $total")
    }

    /** Hangar's `projects.maxFileSize` and `maxTotalFilesSize` (ProjectsConfig.java's defaults). */
    const val HANGAR_MAX_FILE = 7_000_000L
    const val HANGAR_MAX_TOTAL = 15_000_000L

    /** Fails with every problem, labelled by what was being checked. */
    fun require(what: String, problems: List<String>) {
        if (problems.isNotEmpty()) {
            throw GradleException("$what would be refused, so nothing was sent:\n  " + problems.joinToString("\n  "))
        }
    }
}
