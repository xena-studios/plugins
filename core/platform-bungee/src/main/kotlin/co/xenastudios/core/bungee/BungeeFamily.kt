package co.xenastudios.core.bungee

import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.SettingsChangedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.util.Locale

/** The family's side of the BungeeCord platform: the board the Xena plugins on this proxy share, and the player cache's joins, quits and languages. */
internal object BungeeFamily {

    /** The shim's class name, whatever package each plugin relocated it to. */
    private const val SHIM = "core.loader.bungee.BungeeBootstrap"

    /**
     * The proxy's family board: another Xena plugin's, found on its main object through
     * BungeeCord's plugin manager (the loader's shim hands it out as a JDK `Supplier`), or a new
     * one for the first. This plugin's main object hands out the same board from now on.
     */
    fun board(platform: BungeePlatform): JvmFamilyBoard {
        val others = platform.server.pluginManager.plugins.filter { it !== platform.plugin }
        val board = JvmFamilyBoard.fromShims(others, SHIM)
        platform.plugin.share(board.root)
        return board
    }

    /**
     * Records in [cache] each player who logs in, who leaves, and whose client's language changes
     * (BungeeCord says when a client's settings arrive), and the players on the proxy now.
     */
    fun record(platform: BungeePlatform, cache: PlayerCache): AutoCloseable {
        val listener = Recorder(cache)
        platform.server.pluginManager.registerListener(platform.plugin, listener)
        for (player in platform.server.players) cache.joined(player.uniqueId.toPlayerRef(), player.name, player.locale?.let(::localeTag))
        return AutoCloseable { platform.server.pluginManager.unregisterListener(listener) }
    }

    /** A client's locale as Minecraft writes it and the other platforms record it: `de_de`. */
    fun localeTag(locale: Locale): String = locale.toString().lowercase(Locale.ROOT)

    class Recorder(private val cache: PlayerCache) : Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        fun onJoin(event: PostLoginEvent) {
            cache.joined(event.player.uniqueId.toPlayerRef(), event.player.name, event.player.locale?.let(::localeTag))
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        fun onQuit(event: PlayerDisconnectEvent) {
            cache.left(event.player.uniqueId.toPlayerRef())
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        fun onSettings(event: SettingsChangedEvent) {
            val locale = event.player.locale?.let(::localeTag) ?: return
            val ref = event.player.uniqueId.toPlayerRef()
            if (cache.clientLocale(ref) != locale) cache.languageChanged(ref, locale)
        }
    }
}
