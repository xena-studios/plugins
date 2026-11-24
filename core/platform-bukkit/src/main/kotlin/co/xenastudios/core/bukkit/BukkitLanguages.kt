package co.xenastudios.core.bukkit

import co.xenastudios.core.bukkit.modern.ModernLanguages
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.scheduler.Scheduler
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Where each reader's language comes from on this server, picked once at startup. */
internal object BukkitLanguages {

    fun pick(server: BukkitServer, plugin: Plugin, scheduler: Scheduler): LanguageSource<CommandSender> = when {
        server.localeApi -> modern(plugin, scheduler)
        server.bungeeChat -> SpigotLanguages
        else -> NoLanguages
    }

    // Its own function, so only a server with the 1.12 API loads the class that calls it.
    private fun modern(plugin: Plugin, scheduler: Scheduler): LanguageSource<CommandSender> = ModernLanguages(plugin, scheduler)

    /**
     * Spigot before 1.12: `player.spigot().getLocale()`, a field read. These servers fire no
     * event when it changes.
     */
    object SpigotLanguages : LanguageSource<CommandSender> {
        override fun clientLocale(viewer: CommandSender): String? = (viewer as? Player)?.spigot()?.locale
    }

    /** CraftBukkit before 1.12, which never says: everyone reads in `default-language`. */
    object NoLanguages : LanguageSource<CommandSender> {
        override fun clientLocale(viewer: CommandSender): String? = null
    }
}
