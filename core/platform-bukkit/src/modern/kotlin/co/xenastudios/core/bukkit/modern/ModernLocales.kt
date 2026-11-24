package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Where
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLocaleChangeEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/**
 * Each player's client locale from 1.12 on: `Player.getLocale()`, a field read, and
 * [PlayerLocaleChangeEvent] when they change it.
 *
 * The event comes before the server stores the new locale, so a change is passed on at the
 * player's next tick, on their own thread, when [locale] already says the new one.
 */
internal class ModernLocales(private val plugin: Plugin, private val scheduler: Scheduler) : AutoCloseable {

    private val handler = object : Listener {}

    fun locale(player: Player): String? = player.clientLocale()

    /** Calls [changed] with each player whose client changes language, once the new locale is readable. */
    fun listen(changed: Consumer<Player>) {
        val executor = EventExecutor { _, event ->
            val player = (event as? PlayerLocaleChangeEvent)?.player ?: return@EventExecutor
            scheduler.run(Where.on(player.uniqueId.toPlayerRef())) { changed.accept(player) }
        }
        plugin.server.pluginManager.registerEvent(PlayerLocaleChangeEvent::class.java, handler, EventPriority.MONITOR, executor, plugin)
    }

    override fun close() {
        HandlerList.unregisterAll(handler)
    }
}
