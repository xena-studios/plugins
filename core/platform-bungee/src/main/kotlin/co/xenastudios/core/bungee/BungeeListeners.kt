package co.xenastudios.core.bungee

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.proxy.ProxyJoinEvent
import co.xenastudios.core.common.proxy.ProxyQuitEvent
import co.xenastudios.core.common.proxy.ServerSwitchEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import net.md_5.bungee.api.event.ServerSwitchEvent as BungeeSwitch

/**
 * BungeeCord's joins, quits and server switches fired on the platform's bus as the core's proxy
 * events, at [EventPriority.HIGH], after most plugins. An event no one listens to isn't made.
 */
internal class BungeeListeners(private val platform: BungeePlatform, private val bus: SimpleEventBus) : Listener, AutoCloseable {

    fun start() {
        platform.server.pluginManager.registerListener(platform.plugin, this)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PostLoginEvent) {
        fire(ProxyJoinEvent.TYPE) { ProxyJoinEvent(event.player.uniqueId.toPlayerRef(), event.player.name) }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onQuit(event: PlayerDisconnectEvent) {
        fire(ProxyQuitEvent.TYPE) { ProxyQuitEvent(event.player.uniqueId.toPlayerRef(), event.player.name) }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSwitch(event: BungeeSwitch) {
        val to = event.player.server?.info?.name ?: return
        fire(ServerSwitchEvent.TYPE) { ServerSwitchEvent(event.player.uniqueId.toPlayerRef(), event.from?.name, to) }
    }

    private inline fun <E : Event> fire(type: EventType<E>, make: () -> E) {
        if (bus.hasListeners(type)) bus.fire(type, make())
    }

    override fun close() {
        platform.server.pluginManager.unregisterListener(this)
    }
}
