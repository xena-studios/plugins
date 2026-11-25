package co.xenastudios.core.velocity

import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent
import java.util.Locale

/**
 * The family's side of the Velocity platform (DECISIONS.md, "The family"): the board the Xena
 * plugins on this proxy share, and the player cache's joins, quits and languages.
 */
internal object VelocityFamily {

    /** The shim's class name, whatever package each plugin relocated it to. */
    private const val SHIM = "core.loader.velocity.VelocityBootstrap"

    /**
     * The proxy's family board: another Xena plugin's, found through Velocity's plugin manager on
     * its main object (the loader's shim hands it out as a JDK `Supplier`), or a new one for the
     * first. This plugin's main object hands out the same board from now on, so it stays found
     * whichever plugin the others ask.
     */
    fun board(platform: VelocityPlatform): JvmFamilyBoard {
        val others = platform.server.pluginManager.plugins.filter { it.instance.orElse(null) !== platform.bootstrap }
        val board = JvmFamilyBoard.fromShims(others.map { it.instance.orElse(null) }, SHIM)
        platform.bootstrap.share(board.root)
        return board
    }

    /**
     * Records in [cache] each player who logs in, who leaves, and whose client's language changes
     * (Velocity says when its settings arrive, just after the login), and the players on the
     * proxy now. Returns what to undo it with.
     */
    fun record(platform: VelocityPlatform, cache: PlayerCache): AutoCloseable {
        val events = platform.server.eventManager
        val plugin = platform.bootstrap
        val joins = EventHandler<PostLoginEvent> { event ->
            cache.joined(event.player.uniqueId.toPlayerRef(), event.player.username, event.player.effectiveLocale?.let(::localeTag))
        }
        val quits = EventHandler<DisconnectEvent> { event -> cache.left(event.player.uniqueId.toPlayerRef()) }
        val settings = EventHandler<PlayerSettingsChangedEvent> { event ->
            val ref = event.player.uniqueId.toPlayerRef()
            val locale = localeTag(event.playerSettings.locale)
            if (cache.clientLocale(ref) != locale) cache.languageChanged(ref, locale)
        }
        events.register(plugin, PostLoginEvent::class.java, Priority.LAST, joins)
        events.register(plugin, DisconnectEvent::class.java, Priority.LAST, quits)
        events.register(plugin, PlayerSettingsChangedEvent::class.java, Priority.LAST, settings)
        for (player in platform.server.allPlayers) cache.joined(player.uniqueId.toPlayerRef(), player.username, player.effectiveLocale?.let(::localeTag))
        return AutoCloseable {
            events.unregister(plugin, joins)
            events.unregister(plugin, quits)
            events.unregister(plugin, settings)
        }
    }

    /** A client's locale as Minecraft writes it and the other platforms record it: `de_de`. */
    fun localeTag(locale: Locale): String = locale.toString().lowercase(Locale.ROOT)
}
