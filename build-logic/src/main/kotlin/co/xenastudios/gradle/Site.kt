package co.xenastudios.gradle

import groovy.json.JsonSlurper

/**
 * The plugin site's side of a release (docs/README.md, Unreleased rewrites): the download picker's
 * entry in `docs/src/data/releases/<plugin>.json`, in the shape `src/lib/data.ts`'s
 * `releasesSchema` checks. `releaseAssemble` writes it beside the jars as `site-release.json`;
 * `scripts/release.ts` puts it in the release pull request.
 */
object Site {
    const val FILE = "site-release.json"

    /** Whether versions.json's line for [id] still has "unreleased" (docs/README.md, Unreleased rewrites). */
    fun isUnreleased(versions: String, id: String): Boolean =
        ((JsonSlurper().parseText(versions) as Map<*, *>)["plugins"] as List<*>).map { it as Map<*, *> }
            .any { it["id"] == id && "unreleased" in it }

    /** The platform ids the plugin's site manifest releases on (`status: release`). */
    fun releasePlatforms(manifest: String): Set<String> =
        ((JsonSlurper().parseText(manifest) as Map<*, *>)["platforms"] as List<*>).map { it as Map<*, *> }
            .filter { it["status"] == "release" }.map { it["id"] as String }.toSet()

    /**
     * Each store's page, for the stores the release reaches: Modrinth, Hangar, CurseForge,
     * voxel.shop by their ids, SpigotMC and BuiltByBit when their resource ids are known, and the
     * GitHub release, always.
     */
    fun storeLinks(
        tag: String,
        modrinth: String?,
        hangar: String?,
        curseForge: Int?,
        voxel: Int?,
        spigotMc: Int?,
        builtByBit: Int?,
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        modrinth?.let { put("modrinth", "https://modrinth.com/plugin/$it") }
        hangar?.let { put("hangar", "https://hangar.papermc.io/xena-studios/$it") }
        curseForge?.let { put("curseforge", "https://www.curseforge.com/projects/$it") }
        voxel?.let { put("voxel", "https://voxel.shop/product/$it") }
        spigotMc?.let { put("spigotmc", "https://www.spigotmc.org/resources/$it/") }
        builtByBit?.let { put("builtbybit", "https://builtbybit.com/resources/$it/") }
        put("github", Release.releaseUrl(tag))
    }

    /** The release's entry: its jars with their platforms, versions and checksums, and its stores. */
    fun entry(manifest: ReleaseManifest, folia: Boolean, platforms: Set<String>?, links: Map<String, String>, date: String): Map<String, Any> {
        fun sitePlatforms(jar: ReleaseFile) = jar.platform.sitePlatforms(folia).filter { platforms == null || it in platforms }
        val files = manifest.files.mapNotNull { jar ->
            val ids = sitePlatforms(jar)
            if (ids.isEmpty()) return@mapNotNull null
            linkedMapOf(
                "name" to jar.file, "url" to Release.downloadUrl(manifest.tag, jar.file), "size" to jar.size, "sha256" to jar.sha256,
                "platforms" to ids, "minecraft" to jar.gameVersions,
            )
        }
        fun platformsOf(filter: (ReleaseFile) -> Boolean) = manifest.files.filter(filter).flatMap(::sitePlatforms).distinct()
        val stores = links.mapNotNull { (store, url) ->
            val carried = when (store) {
                "modrinth", "github" -> null
                "hangar" -> platformsOf { it.platform.hangar != null }
                "curseforge" -> platformsOf { it.platform.curseForge != null }
                else -> platformsOf { it.platform.bukkitFamily }
            }
            if (carried != null && carried.isEmpty()) return@mapNotNull null
            linkedMapOf<String, Any>("store" to store, "url" to url).apply { if (carried != null) put("platforms", carried) }
        }
        return linkedMapOf("version" to manifest.version, "date" to date, "channel" to manifest.type.id, "files" to files, "stores" to stores)
    }

    /** How the recorded releases file's entry for this version differs from this build's jars, if it has one. */
    fun mismatches(releasesFile: String, entry: Map<String, Any>): List<String> {
        val releases = (JsonSlurper().parseText(releasesFile) as Map<*, *>)["releases"] as List<*>? ?: return emptyList()
        val recorded = releases.map { it as Map<*, *> }.firstOrNull { it["version"] == entry["version"] } ?: return emptyList()
        val built = (entry["files"] as List<*>).associateBy { (it as Map<*, *>)["name"] }
        val listed = (recorded["files"] as List<*>).associateBy { (it as Map<*, *>)["name"] }
        return buildList {
            (listed.keys - built.keys).forEach { add("$it is in the releases file, not in this build") }
            (built.keys - listed.keys).forEach { add("$it is in this build, not in the releases file") }
            for (name in built.keys.intersect(listed.keys)) {
                val b = built[name] as Map<*, *>
                val l = listed[name] as Map<*, *>
                if (l["sha256"] != null && l["sha256"] != b["sha256"]) add("$name: built ${b["sha256"]}, recorded ${l["sha256"]}")
                else if ((l["size"] as Number).toLong() != (b["size"] as Number).toLong()) add("$name: built ${b["size"]} bytes, recorded ${l["size"]}")
            }
        }
    }
}
