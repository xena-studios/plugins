package co.xenastudios.gradle

import ReleaseJar
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.net.http.HttpRequest

/**
 * Gathers a release in one folder: every jar, renamed `<plugin>-<version>-<jar>.jar`, their
 * `SHA256SUMS`, `release.json` (what each store's job publishes), the changelog section, the
 * SpigotMC and BuiltByBit update posts to paste, and a summary for the workflow's job summary.
 */
@DisableCachingByDefault(because = "Reads the brand catalog and Modrinth's version list over the network")
abstract class ReleaseAssemble : DefaultTask() {
    @get:Input
    abstract val pluginId: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val displayName: Property<String>

    @get:Input
    abstract val folia: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelogFile: RegularFileProperty

    /** A dry run takes a missing changelog section, and says so. */
    @get:Input
    abstract val dryRun: Property<Boolean>

    /** The brand catalog: a URL, or a local copy's path. */
    @get:Input
    abstract val catalog: Property<String>

    /** The newest Minecraft a range reaches by default (xena.ciNewestMinecraft). */
    @get:Input
    abstract val newestMinecraft: Property<String>

    /** Store to project id, for the summary: a store without one is skipped by a real release. */
    @get:Input
    abstract val projects: MapProperty<String, String>

    @get:Nested
    val jars: MutableList<ReleaseJar> = mutableListOf()

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val id = pluginId.get()
        val version = version.get()
        val tag = "$id-v$version"
        if (jars.isEmpty()) throw GradleException("$id declares no release jars (xenaRelease { jar(...) })")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val changelog = changelogFile.orNull?.asFile?.takeIf { it.isFile }
            ?.let { Release.changelogSection(it.readText(), version) }
            ?: if (dryRun.get()) {
                logger.warn("No \"## {}\" section in {}'s CHANGELOG.md; a real release stops here", version, id)
                "(No changelog section for $version: a real release needs one.)"
            } else {
                throw GradleException("No \"## $version\" section in ${changelogFile.orNull?.asFile ?: "$id's CHANGELOG.md"}")
            }

        val listing = Release.listing(read(catalog.get()), id) ?: run {
            logger.warn("The brand catalog has no {}; titles use {}", id, displayName.get())
            Listing(displayName.get(), null, null, null)
        }

        val tags by lazy { read(Modrinth.API + "tag/game_version") }
        val files = jars.map { jar ->
            val platform = jar.platform.get()
            val gameVersions = jar.gameVersions.get().ifEmpty {
                val from = jar.minecraftFrom.orNull
                    ?: throw GradleException("Jar ${jar.name} names no Minecraft versions: set gameVersions or minecraft(from)")
                Release.minecraftReleases(tags, from, jar.minecraftTo.orNull ?: newestMinecraft.get())
            }
            val name = "$id-$version-${jar.name}.jar"
            val target = out.resolve(name)
            jar.file.get().asFile.copyTo(target)
            ReleaseFile(
                name = jar.name,
                file = name,
                platform = platform,
                label = jar.label.orNull ?: defaultLabel(jar.name),
                gameVersions = gameVersions,
                loaders = jar.loaders.get().ifEmpty { platform.modrinthLoaders(folia.get()) },
                java = jar.java.orNull,
                hangarVersions = jar.hangarVersions.getOrElse(emptyList()),
                sha256 = Release.sha256(target),
                size = target.length(),
            )
        }

        val manifest = ReleaseManifest(id, version, tag, VersionType.of(version), listing, changelog, files)
        out.resolve(ReleaseManifest.FILE).writeText(manifest.toJson())
        out.resolve("SHA256SUMS").writeText(Release.checksums(files))
        out.resolve("changelog.md").writeText(changelog + "\n")
        out.resolve("title.txt").writeText(manifest.title + "\n")
        if (files.any { it.platform.bukkitFamily }) {
            out.resolve("spigotmc-update.txt").writeText(Release.updatePost("SpigotMC", manifest))
            out.resolve("builtbybit-update.txt").writeText(Release.updatePost("BuiltByBit", manifest))
        }
        out.resolve("summary.md").writeText(Release.summary(manifest, stores(files)))
        logger.lifecycle("{}: {} jar(s) in {}", manifest.title, files.size, out)
        files.forEach { logger.lifecycle("  {}  {}  {}", it.sha256, it.file, it.gameVersions.joinToString(",")) }
    }

    private fun stores(files: List<ReleaseFile>): Map<String, List<String>> {
        val ids = projects.get()
        fun store(name: String, key: String, jars: List<ReleaseFile>) =
            (if (key in ids) name else "$name (no project yet: skipped)") to jars.map { it.file }
        val bukkit = files.filter { it.platform.bukkitFamily }
        return linkedMapOf(
            "GitHub Releases" to files.map { it.file },
            store("Modrinth", "modrinth", files.filter { it.loaders.isNotEmpty() }),
            store("Hangar", "hangar", files.filter { it.platform.hangar != null }),
            store("voxel.shop", "voxel", listOfNotNull(bukkit.minByOrNull { it.platform.ordinal })),
            store("CurseForge (Bukkit)", "curseforge-bukkit", files.filter { it.platform.curseForge == CurseForgeClass.BUKKIT }),
            store("CurseForge (mods)", "curseforge-mods", files.filter { it.platform.curseForge == CurseForgeClass.MODS }),
            store("CurseForge (Hytale)", "curseforge-hytale", files.filter { it.platform.curseForge == CurseForgeClass.HYTALE }),
            "SpigotMC (update post to paste)" to bukkit.map { it.file },
            "BuiltByBit (update post to paste)" to bukkit.map { it.file },
        ).filterValues { it.isNotEmpty() }
    }

    private fun read(location: String): String =
        if (location.startsWith("https://")) {
            val response = Stores.send(HttpRequest.newBuilder(URI(location)).GET())
            if (response.statusCode() != 200) throw GradleException("$location answered HTTP ${response.statusCode()}")
            response.body()
        } else {
            File(location).readText()
        }

    companion object {
        /** `fabric-1.21.11` -> "Fabric 1.21.11", `bukkit` -> "Bukkit". */
        fun defaultLabel(name: String): String =
            name.split('-').joinToString(" ").replaceFirstChar { it.uppercase() }
    }
}
