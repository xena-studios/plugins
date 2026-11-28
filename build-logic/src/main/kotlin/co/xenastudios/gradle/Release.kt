package co.xenastudios.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest

/**
 * A platform a release jar is built for, and the stores that take it (ROADMAP.md, "Build, CI
 * and releases"). GitHub Releases take every jar.
 */
enum class ReleasePlatform(
    /** Modrinth's loaders for the jar, or empty when Modrinth doesn't list the platform. */
    val modrinthLoaders: List<String>,
    /** Hangar's platform, or null. */
    val hangar: String? = null,
    /** The CurseForge project the jar goes to, or null. */
    val curseForge: CurseForgeClass? = null,
    /** The loader's name among CurseForge's game versions, for mods. */
    val curseForgeLoader: String? = null,
    /** voxel.shop, SpigotMC and BuiltByBit: the Bukkit family. */
    val bukkitFamily: Boolean = false,
) {
    /** One jar for Paper, Folia, Spigot and Bukkit (platform-bukkit). */
    BUKKIT(listOf("bukkit", "spigot", "paper", "purpur"), "PAPER", CurseForgeClass.BUKKIT, bukkitFamily = true),
    /** A Paper-only jar (xLimbo). */
    PAPER(listOf("paper", "purpur"), "PAPER", CurseForgeClass.BUKKIT, bukkitFamily = true),
    VELOCITY(listOf("velocity"), "VELOCITY"),
    /** BungeeCord and Waterfall: Hangar takes it as Waterfall's, whose versions are its own lines (1.21). */
    BUNGEECORD(listOf("bungeecord", "waterfall"), "WATERFALL"),
    FABRIC(listOf("fabric"), curseForge = CurseForgeClass.MODS, curseForgeLoader = "Fabric"),
    NEOFORGE(listOf("neoforge"), curseForge = CurseForgeClass.MODS, curseForgeLoader = "NeoForge"),
    FORGE(listOf("forge"), curseForge = CurseForgeClass.MODS, curseForgeLoader = "Forge"),
    SPONGE(listOf("sponge")),
    HYTALE(emptyList(), curseForge = CurseForgeClass.HYTALE),
    /** Pumpkin and Vine's Wasm builds: GitHub only, until Pumpkin's own store is ready. */
    PUMPKIN(emptyList()),
    ;

    /** Modrinth's loaders, with Folia when the plugin runs on it. */
    fun modrinthLoaders(folia: Boolean): List<String> =
        if (folia && (this == BUKKIT || this == PAPER)) modrinthLoaders + "folia" else modrinthLoaders

    /**
     * The site's platform ids for the jar (a plugin's manifest's `platforms`, which the download
     * picker checks each jar against): the Bukkit jar is Paper's, Folia's and Spigot's.
     */
    fun sitePlatforms(folia: Boolean): List<String> = when (this) {
        BUKKIT -> listOfNotNull("paper", "folia".takeIf { folia }, "spigot")
        PAPER -> listOfNotNull("paper", "folia".takeIf { folia })
        else -> listOf(name.lowercase())
    }
}

/** CurseForge's project classes: each plugin has up to three projects, one per class (M0). */
enum class CurseForgeClass(val host: String) {
    /** Bukkit Plugins, on dev.bukkit.org. */
    BUKKIT("https://dev.bukkit.org/api/"),
    /** Minecraft mods: Fabric, NeoForge and Forge. */
    MODS("https://minecraft.curseforge.com/api/"),
    /** Hytale, which the legacy host finds from the project id. */
    HYTALE("https://legacy.curseforge.com/api/"),
}

/** Modrinth's version types; CurseForge's release types are the same three words. */
enum class VersionType {
    ALPHA, BETA, RELEASE;

    val id: String get() = name.lowercase()

    companion object {
        /** `2.0.0` is a release, `2.0.0-alpha.1` an alpha, and any other suffix (`-beta.2`, `-rc.1`) a beta. */
        fun of(version: String): VersionType {
            val suffix = version.substringBefore('+').substringAfter('-', "")
            return when {
                suffix.isEmpty() -> RELEASE
                suffix.startsWith("alpha", ignoreCase = true) -> ALPHA
                else -> BETA
            }
        }
    }
}

/** One jar of a release, as `release.json` lists it. */
data class ReleaseFile(
    /** The jar's name in the plugin's build, such as `bukkit` or `fabric-1.21.11`. */
    val name: String,
    /** Its file name in the release, `<plugin>-<version>-<name>.jar`. */
    val file: String,
    val platform: ReleasePlatform,
    /** For titles: "Bukkit", "Fabric 1.21.11". */
    val label: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val java: Int?,
    val sha256: String,
    val size: Long,
    /** Hangar's versions for the platform, where they aren't [gameVersions] (Velocity's own). */
    val hangarVersions: List<String> = emptyList(),
    /** Or every version Hangar lists for the platform from this one on. */
    val hangarFrom: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name, "file" to file, "platform" to platform.name, "label" to label,
        "gameVersions" to gameVersions, "loaders" to loaders, "java" to java, "sha256" to sha256, "size" to size,
        "hangarVersions" to hangarVersions, "hangarFrom" to hangarFrom,
    )

    companion object {
        fun of(map: Map<*, *>) = ReleaseFile(
            name = map["name"] as String,
            file = map["file"] as String,
            platform = ReleasePlatform.valueOf(map["platform"] as String),
            label = map["label"] as String,
            gameVersions = (map["gameVersions"] as List<*>).map { it.toString() },
            loaders = (map["loaders"] as List<*>).map { it.toString() },
            java = (map["java"] as Number?)?.toInt(),
            sha256 = map["sha256"] as String,
            size = (map["size"] as Number).toLong(),
            hangarVersions = (map["hangarVersions"] as List<*>?)?.map { it.toString() }.orEmpty(),
            hangarFrom = map["hangarFrom"] as String?,
        )
    }
}

/** The plugin's store page details, from the brand catalog. */
data class Listing(val name: String, val summary: String?, val icon: String?, val banner: String?) {
    fun toMap(): Map<String, Any?> = linkedMapOf("name" to name, "summary" to summary, "icon" to icon, "banner" to banner)

    companion object {
        fun of(map: Map<*, *>) = Listing(map["name"] as String, map["summary"] as String?, map["icon"] as String?, map["banner"] as String?)
    }
}

/**
 * Everything a release publishes, written by `releaseAssemble` to `release.json` beside the
 * jars, so each store's job publishes exactly the jars that were built, checksummed and
 * attested once.
 */
data class ReleaseManifest(
    val pluginId: String,
    val version: String,
    val tag: String,
    val type: VersionType,
    val listing: Listing,
    val changelog: String,
    val files: List<ReleaseFile>,
) {
    /** "xBans 2.0.0". */
    val title: String get() = "${listing.name} $version"

    fun toJson(): String = JsonOutput.prettyPrint(JsonOutput.toJson(linkedMapOf(
        "pluginId" to pluginId, "version" to version, "tag" to tag, "type" to type.id,
        "listing" to listing.toMap(), "changelog" to changelog, "files" to files.map { it.toMap() },
    )))

    companion object {
        const val FILE = "release.json"

        fun read(dir: File): ReleaseManifest {
            val file = dir.resolve(FILE)
            if (!file.isFile) throw GradleException("$file is missing: run releaseAssemble first")
            val map = JsonSlurper().parse(file) as Map<*, *>
            return ReleaseManifest(
                pluginId = map["pluginId"] as String,
                version = map["version"] as String,
                tag = map["tag"] as String,
                type = VersionType.valueOf((map["type"] as String).uppercase()),
                listing = Listing.of(map["listing"] as Map<*, *>),
                changelog = map["changelog"] as String,
                files = (map["files"] as List<*>).map { ReleaseFile.of(it as Map<*, *>) },
            )
        }
    }
}

/** The pure parts of a release, kept apart from the tasks so they can be tested. */
object Release {
    const val REPO = "xena-studios/plugins"
    const val BRAND_RAW = "https://raw.githubusercontent.com/xena-studios/brand/main/"
    const val CATALOG_URL = BRAND_RAW + "tokens/plugins-catalog.json"

    /** The heading of one version's section, such as "2.0.0 (unreleased)", or null. */
    fun changelogHeading(changelog: String, version: String): String? =
        changelog.lines().firstOrNull { it == "## $version" || it.startsWith("## $version ") }?.removePrefix("## ")

    /** One version's section of a CHANGELOG.md, as scripts/changelog.sh prints it, or null. */
    fun changelogSection(changelog: String, version: String): String? {
        val heading = "## $version"
        val lines = changelog.lines()
        val start = lines.indexOfFirst { it == heading || it.startsWith("$heading ") }
        if (start < 0) return null
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
            .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return section.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /**
     * Minecraft releases from [from] to [to], both included, oldest first, from Modrinth's
     * `/v2/tag/game_version` (its names are the ones Modrinth, Hangar and CurseForge share).
     */
    fun minecraftReleases(gameVersionTags: String, from: String, to: String): List<String> {
        val releases = (JsonSlurper().parseText(gameVersionTags) as List<*>)
            .map { it as Map<*, *> }
            .filter { it["version_type"] == "release" }
            .sortedBy { it["date"] as String }
            .map { it["version"] as String }
        val start = releases.indexOf(from)
        val end = releases.indexOf(to)
        if (start < 0) throw GradleException("Modrinth doesn't know Minecraft $from")
        if (end < 0) throw GradleException("Modrinth doesn't know Minecraft $to")
        if (end < start) throw GradleException("Minecraft $from is newer than $to")
        return releases.subList(start, end + 1)
    }

    /** The plugin's brand catalog entry (brand repo, tokens/plugins-catalog.json), or null. */
    fun listing(catalog: String, pluginId: String): Listing? {
        val plugins = (JsonSlurper().parseText(catalog) as Map<*, *>)["plugins"] as List<*>
        val entry = plugins.map { it as Map<*, *> }.firstOrNull { it["id"] == pluginId } ?: return null
        fun asset(key: String) = (entry[key] as String?)?.let { BRAND_RAW + it }
        return Listing(entry["name"] as String, entry["summary"] as String?, asset("icon"), asset("banner"))
    }

    fun downloadUrl(tag: String, file: String) = "https://github.com/$REPO/releases/download/$tag/$file"

    fun releaseUrl(tag: String) = "https://github.com/$REPO/releases/tag/$tag"

    /**
     * The update post for a store without an upload API (SpigotMC, BuiltByBit): its title, the
     * changelog in BBCode, and where the Bukkit-family jar downloads from, to paste by hand.
     */
    fun updatePost(store: String, manifest: ReleaseManifest): String {
        val jars = manifest.files.filter { it.platform.bukkitFamily }
        return buildString {
            appendLine("$store update for ${manifest.title}")
            appendLine()
            appendLine("Title (version): ${manifest.version}")
            appendLine()
            appendLine("Download URL (the listing's external download):")
            jars.forEach { appendLine("  ${downloadUrl(manifest.tag, it.file)}") }
            appendLine()
            appendLine("Message (BBCode):")
            appendLine()
            appendLine(BbCode.fromMarkdown(manifest.changelog))
            appendLine()
            appendLine("[url=${releaseUrl(manifest.tag)}]All downloads and checksums[/url]")
        }
    }

    /** `sha256sum`'s format, so `sha256sum -c SHA256SUMS` checks the downloads. */
    fun checksums(files: List<ReleaseFile>): String = files.joinToString("") { "${it.sha256}  ${it.file}\n" }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The job summary: what goes where, and the listing the hand-kept store pages copy. */
    fun summary(manifest: ReleaseManifest, stores: Map<String, List<String>>): String = buildString {
        appendLine("## ${manifest.title} (${manifest.type.id})")
        appendLine()
        appendLine("| Jar | Platform | Minecraft | SHA-256 |")
        appendLine("|---|---|---|---|")
        manifest.files.forEach {
            val versions = if (it.gameVersions.size > 4) {
                "${it.gameVersions.first()} to ${it.gameVersions.last()} (${it.gameVersions.size})"
            } else {
                it.gameVersions.joinToString(", ")
            }
            appendLine("| `${it.file}` | ${it.label} | $versions | `${it.sha256.take(16)}…` |")
        }
        appendLine()
        appendLine("| Store | Jars |")
        appendLine("|---|---|")
        stores.forEach { (store, jars) -> appendLine("| $store | ${jars.joinToString(", ").ifEmpty { "none" }} |") }
        appendLine()
        appendLine("Store pages (CurseForge, SpigotMC, BuiltByBit) use the brand catalog's:")
        appendLine()
        appendLine("- Name: ${manifest.listing.name}")
        appendLine("- Summary: ${manifest.listing.summary ?: "(not in the brand catalog)"}")
        appendLine("- Icon: ${manifest.listing.icon ?: "(not in the brand catalog)"}")
        appendLine("- Banner: ${manifest.listing.banner ?: "(not in the brand catalog)"}")
    }
}
