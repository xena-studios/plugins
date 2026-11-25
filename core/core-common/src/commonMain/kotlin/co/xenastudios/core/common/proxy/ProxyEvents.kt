package co.xenastudios.core.common.proxy

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import kotlin.jvm.JvmField

// What a proxy tells a plugin, on the platform's bus (VelocityPlatform.events,
// BungeePlatform.events): the same events from either proxy, so a plugin's proxy side is shared
// code. Each is fired off the plugin's own thread, on the proxy's; nothing is made when no one
// listens.

/** A player logged in to the proxy, before they reach a server. */
public class ProxyJoinEvent(
    /** Who joined. */
    public val player: PlayerRef,
    /** Their name. */
    public val name: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ProxyJoinEvent> = EventType.of("core:proxy_join")
    }
}

/** A player left the proxy. */
public class ProxyQuitEvent(
    /** Who left. */
    public val player: PlayerRef,
    /** Their name. */
    public val name: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ProxyQuitEvent> = EventType.of("core:proxy_quit")
    }
}

/** A player is now on [to], having come from [from], or from nowhere as they join. */
public class ServerSwitchEvent(
    /** Who moved. */
    public val player: PlayerRef,
    /** The server they were on, by the proxy's name for it, or `null` as they join. */
    public val from: String?,
    /** The server they're on now. */
    public val to: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ServerSwitchEvent> = EventType.of("core:server_switch")
    }
}
