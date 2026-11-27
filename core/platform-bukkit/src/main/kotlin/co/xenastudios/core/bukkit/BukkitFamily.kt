package co.xenastudios.core.bukkit

import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.seconds

/**
 * The family's side of the Bukkit platform: the board the Xena plugins on this server share, and
 * the player cache's joins, quits and languages (DECISIONS.md, "The family").
 */
internal object BukkitFamily {

    /**
     * The server's family board, from Bukkit's `ServicesManager` under `ConcurrentMap`, a JDK
     * type every plugin's copy of the core sees as the same class; made by the first plugin to
     * start. Every plugin registers it too, not only the first: Bukkit drops a plugin's services
     * when it's disabled, and the board must outlive the first plugin.
     */
    fun board(plugin: Plugin): JvmFamilyBoard {
        val services = plugin.server.servicesManager
        val board = JvmFamilyBoard.find(services.getRegistrations(ConcurrentMap::class.java).map { it.provider })
        services.register(ConcurrentMap::class.java, board.root, plugin, ServicePriority.Lowest)
        // Where the plugin's database finds the board for the family's backups.
        JvmFamilyBoard.server = board
        return board
    }

    /**
     * Records in [cache] each player who joins (with their name and client locale), who quits,
     * and whose client changes language; and the players already online, for a plugin enabled
     * while they're on. Every Xena plugin does this for the shared cache: the first records a
     * change, the rest find it already there. Returns what to undo it with.
     */
    fun record(plugin: Plugin, cache: PlayerCache, locales: ClientLocales, scheduler: Scheduler): AutoCloseable {
        val handler = object : Listener {}
        val listens = locales.listen { player -> locales.of(player)?.let { cache.languageChanged(player.uniqueId.toPlayerRef(), it) } }
        fun joined(player: Player) {
            val ref = player.uniqueId.toPlayerRef()
            cache.joined(ref, player.name, locales.of(player))
            // Before 1.12 nothing says when the client's settings arrive, which is just after it
            // joins: until then the server says en_US. So look again once they're in.
            if (!listens) {
                scheduler.run(Where.on(ref), delay = LOCALE_SETTLES) {
                    locales.of(player)?.let { cache.languageChanged(ref, it) }
                }
            }
        }
        val plugins = plugin.server.pluginManager
        plugins.registerEvent(PlayerJoinEvent::class.java, handler, EventPriority.MONITOR, EventExecutor { _, event ->
            if (event is PlayerJoinEvent) joined(event.player)
        }, plugin)
        plugins.registerEvent(PlayerQuitEvent::class.java, handler, EventPriority.MONITOR, EventExecutor { _, event ->
            if (event is PlayerQuitEvent) cache.left(event.player.uniqueId.toPlayerRef())
        }, plugin)
        plugin.server.onlinePlayers.forEach(::joined)
        return AutoCloseable { HandlerList.unregisterAll(handler) }
    }

    private val LOCALE_SETTLES = 2.seconds
}
