package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.Warp
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * EssentialsX's homes, warps and spawn, read from its files without changing them:
 * `Essentials/userdata/<uuid>.yml` (`homes:`), `Essentials/warps/<name>.yml` and
 * `Essentials/spawn.yml` (`spawns.default`). 1.x's importer, on the core's YAML reader. Called off
 * the server's threads: it reads the disk.
 */
public class EssentialsFiles(private val essentials: Path, override val folder: String = "plugins/Essentials") : Essentials {

    override fun read(worldName: (String) -> String?): Essentials.Found {
        val homes = ArrayList<Home>()
        val warps = ArrayList<Warp>()
        val problems = ArrayList<String>()
        var players = 0
        for (file in ymlFiles(essentials.resolve("userdata"), problems)) {
            val base = file.fileName.toString()
            val owner = PlayerRef.parseOrNull(base.removeSuffix(".yml")) ?: continue
            val section = (load(file, problems) as? YamlNode.Mapping)?.get("homes") as? YamlNode.Mapping ?: continue
            val before = homes.size
            val named = HashSet<String>()
            for (entry in section.entries) {
                val place = place(entry.value as? YamlNode.Mapping, worldName)
                val clean = Essentials.cleanName(entry.key)
                if (place == null || clean == null) {
                    problem(problems, "Skipped home \"${entry.key}\" in $base: ${if (clean == null) "its name" else "its world"}.")
                    continue
                }
                if (!named.add(clean.lowercase())) {
                    problem(problems, "Skipped home \"${entry.key}\" in $base: as $clean, its name is another of the player's homes'.")
                    continue
                }
                homes += Home(owner, clean, place)
            }
            if (homes.size > before) players++
        }
        val warpNames = HashSet<String>()
        for (file in ymlFiles(essentials.resolve("warps"), problems)) {
            val yaml = load(file, problems) as? YamlNode.Mapping ?: continue
            val base = file.fileName.toString()
            val name = Essentials.cleanName(scalar(yaml, "name") ?: base.removeSuffix(".yml"))
            val place = place(yaml, worldName)
            if (place == null || name == null) {
                problem(problems, "Skipped warp $base: ${if (name == null) "its name" else "its world"}.")
                continue
            }
            if (!warpNames.add(name.lowercase())) {
                problem(problems, "Skipped warp $base: as $name, its name is another warp's.")
                continue
            }
            warps += Warp(name, place)
        }
        var spawn: Location? = null
        val spawnFile = essentials.resolve("spawn.yml")
        if (Files.isRegularFile(spawnFile)) {
            val spawns = (load(spawnFile, problems) as? YamlNode.Mapping)?.get("spawns") as? YamlNode.Mapping
            spawn = place(spawns?.get("default") as? YamlNode.Mapping, worldName)
        }
        return Essentials.Found(homes, warps, spawn, players, problems.toList())
    }

    private fun problem(problems: MutableList<String>, problem: String) {
        if (problems.size < MAX_PROBLEMS) problems += problem
    }

    private fun ymlFiles(folder: Path, problems: MutableList<String>): List<Path> {
        if (!Files.isDirectory(folder)) return emptyList()
        return try {
            Files.newDirectoryStream(folder, "*.yml").use { it.toList() }.sortedBy { it.fileName.toString() }
        } catch (e: java.io.IOException) {
            problem(problems, "Couldn't list $folder: ${e.message}")
            emptyList()
        }
    }

    private fun load(file: Path, problems: MutableList<String>): YamlNode? {
        val text = try {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (e: java.io.IOException) {
            problem(problems, "Couldn't read ${file.fileName}: ${e.message}")
            return null
        }
        return when (val read = JvmYaml.read(text)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> {
                problem(problems, "Couldn't read ${file.fileName}: ${read.error.message}")
                null
            }
        }
    }

    private fun scalar(section: YamlNode.Mapping, key: String): String? = (section[key] as? YamlNode.Scalar)?.content

    private fun number(section: YamlNode.Mapping, key: String): Double? = scalar(section, key)?.trim()?.toDoubleOrNull()

    /** A place from EssentialsX's keys, or `null` if its world can't be named. */
    internal fun place(section: YamlNode.Mapping?, worldName: (String) -> String?): Location? {
        if (section == null) return null
        val x = number(section, "x") ?: return null
        val y = number(section, "y") ?: return null
        val z = number(section, "z") ?: return null
        var world = scalar(section, "world-name")?.takeIf { it.isNotBlank() }
        if (world == null) {
            val raw = scalar(section, "world").orEmpty()
            // A uuid, as newer versions save it, or the name itself, as old ones did.
            world = worldName(raw) ?: raw.takeIf { PlayerRef.parseOrNull(it) == null }
        }
        if (world.isNullOrBlank()) return null
        return Location(WorldRef.of(world), x, y, z, (number(section, "yaw") ?: 0.0).toFloat(), (number(section, "pitch") ?: 0.0).toFloat())
    }

    private companion object {
        const val MAX_PROBLEMS = 20
    }
}
