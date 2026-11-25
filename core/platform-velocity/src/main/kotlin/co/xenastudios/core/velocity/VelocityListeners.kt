package co.xenastudios.core.velocity

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.proxy.ProxyJoinEvent
import co.xenastudios.core.common.proxy.ProxyQuitEvent
import co.xenastudios.core.common.proxy.ServerSwitchEvent
import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent

/**
 * Velocity's joins, quits and server switches fired on the platform's bus as the core's proxy
 * events, at [Priority.LATE], after other plugins have had their say. An event no one listens to
 * isn't made.
 */
internal class VelocityListeners(private val platform: VelocityPlatform, private val bus: SimpleEventBus) : AutoCloseable {
    private val handlers = ArrayList<EventHandler<*>>()

    fun start() {
        listen(PostLoginEvent::class.java) { event ->
            fire(ProxyJoinEvent.TYPE) { ProxyJoinEvent(event.player.uniqueId.toPlayerRef(), event.player.username) }
        }
        listen(DisconnectEvent::class.java) { event ->
            fire(ProxyQuitEvent.TYPE) { ProxyQuitEvent(event.player.uniqueId.toPlayerRef(), event.player.username) }
        }
        listen(ServerConnectedEvent::class.java) { event ->
            fire(ServerSwitchEvent.TYPE) {
                ServerSwitchEvent(event.player.uniqueId.toPlayerRef(), event.previousServer.orElse(null)?.serverInfo?.name, event.server.serverInfo.name)
            }
        }
    }

    private fun <E : Any> listen(type: Class<E>, handler: EventHandler<E>) {
        platform.server.eventManager.register(platform.bootstrap, type, Priority.LATE, handler)
        handlers += handler
    }

    private inline fun <E : Event> fire(type: EventType<E>, make: () -> E) {
        if (bus.hasListeners(type)) bus.fire(type, make())
    }

    override fun close() {
        for (handler in handlers) platform.server.eventManager.unregister(platform.bootstrap, handler)
        handlers.clear()
    }
}
