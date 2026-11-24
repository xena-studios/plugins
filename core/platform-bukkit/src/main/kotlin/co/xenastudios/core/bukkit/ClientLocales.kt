package co.xenastudios.core.bukkit

import co.xenastudios.core.bukkit.modern.ModernLocales
import co.xenastudios.core.common.scheduler.Scheduler
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/**
 * The locale each player's client sent (`en_us`), the way this server has it, picked once at
 * startup. Reading one is a field read; [listen] says when one changes, where the server can.
 */
internal abstract class ClientLocales : AutoCloseable {
    /** What [player]'s client sent, or `null` where the server can't say. */
    abstract fun of(player: Player): String?

    /**
     * Calls [changed] on the player's thread whenever their client changes language, once [of]
     * says the new one. Returns `false` on servers that never say, where the platform reads it
     * again a little after they join instead.
     */
    open fun listen(changed: (Player) -> Unit): Boolean = false

    override fun close() {}

    companion object {
        fun pick(server: BukkitServer, plugin: Plugin, scheduler: Scheduler): ClientLocales = when {
            server.localeApi -> modern(plugin, scheduler)
            server.bungeeChat -> Spigot
            else -> None
        }

        // Its own function, so only a server with the 1.12 API loads the class that calls it.
        private fun modern(plugin: Plugin, scheduler: Scheduler): ClientLocales {
            val locales = ModernLocales(plugin, scheduler)
            return object : ClientLocales() {
                override fun of(player: Player): String? = locales.locale(player)

                override fun listen(changed: (Player) -> Unit): Boolean {
                    locales.listen(Consumer { changed(it) })
                    return true
                }

                override fun close() {
                    locales.close()
                }
            }
        }
    }

    /** Spigot before 1.12: `player.spigot().getLocale()`. These servers fire no event when it changes. */
    object Spigot : ClientLocales() {
        override fun of(player: Player): String? = player.spigot().locale
    }

    /** CraftBukkit before 1.12, which never says: its players read in `default-language`. */
    object None : ClientLocales() {
        override fun of(player: Player): String? = null
    }
}
