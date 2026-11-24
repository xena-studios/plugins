package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.i18n.LanguageChangeListener
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Where
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLocaleChangeEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Each player's language from 1.12 on: the locale their client sent (`Player.getLocale()`, a
 * field read), and [PlayerLocaleChangeEvent] when they change it. The console has none, so it
 * reads in `default-language`.
 *
 * The event comes before the server stores the new locale, so listeners hear of it on the
 * player's next tick, on their own thread, when [clientLocale] already says the new one.
 */
internal class ModernLanguages(
    private val plugin: Plugin,
    private val scheduler: Scheduler,
) : LanguageSource<CommandSender>, AutoCloseable {

    private val listeners = CopyOnWriteArrayList<LanguageChangeListener<CommandSender>>()
    private val handler = object : Listener {}

    // Only touched holding this object's lock.
    private var registered = false

    override fun clientLocale(viewer: CommandSender): String? = (viewer as? Player)?.clientLocale()

    override fun onLanguageChange(listener: LanguageChangeListener<CommandSender>) {
        listeners += listener
        // Only a plugin that keeps text per viewer listens, so only then is the event heard.
        synchronized(this) {
            if (registered) return
            registered = true
        }
        val executor = EventExecutor { _, event ->
            val player = (event as? PlayerLocaleChangeEvent)?.player ?: return@EventExecutor
            scheduler.run(Where.on(player.uniqueId.toPlayerRef())) {
                for (each in listeners) each.languageChanged(player)
            }
        }
        plugin.server.pluginManager.registerEvent(PlayerLocaleChangeEvent::class.java, handler, EventPriority.MONITOR, executor, plugin)
    }

    override fun close() {
        listeners.clear()
        HandlerList.unregisterAll(handler)
    }
}
