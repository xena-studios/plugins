package co.xenastudios.xlimits.bukkit

import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * The server's own settings next to xLimits' limits, for `/xlimits here`: Spigot's
 * `spigot.yml` and, on Paper 1.19 on, `config/paper-world-defaults.yml` with the world's own
 * `paper-world.yml` over it. Read as the command runs: it's rare, and they're small.
 */
internal object ServerSettings {
    fun read(server: Server, world: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val spigot = server.spigot().config
            fun spigot(key: String): String? = spigot.getString("world-settings.$world.$key") ?: spigot.getString("world-settings.default.$key")
            spigot("max-entity-collisions")?.let { out += "max-entity-collisions" to it }
            spigot("hopper-transfer")?.let { out += "hopper-transfer" to it }
            spigot("hopper-check")?.let { out += "hopper-check" to it }
            spigot("max-tnt-per-tick")?.let { out += "max-tnt-per-tick" to it }
        } catch (e: Exception) {
            // Not Spigot's config (CraftBukkit): nothing to show.
        } catch (e: LinkageError) {
        }
        val defaults = File("config", "paper-world-defaults.yml")
        if (defaults.isFile) {
            val paper = YamlConfiguration.loadConfiguration(defaults)
            val own = server.getWorld(world)?.worldFolder?.resolve("paper-world.yml")?.takeIf { it.isFile }?.let(YamlConfiguration::loadConfiguration)
            fun paper(key: String): String? = own?.getString(key)?.takeIf { it != "default" } ?: paper.getString(key)
            paper("collisions.max-entity-collisions")?.let { value -> out.removeAll { it.first == "max-entity-collisions" }; out += "max-entity-collisions" to value }
            paper("hopper.cooldown-when-full")?.let { out += "hopper.cooldown-when-full" to it }
            paper("entities.spawning.per-player-mob-spawns")?.let { out += "per-player-mob-spawns" to it }
            paper("environment.max-tnt-per-tick")?.let { value -> out.removeAll { it.first == "max-tnt-per-tick" }; out += "max-tnt-per-tick" to value }
        }
        return out
    }
}
