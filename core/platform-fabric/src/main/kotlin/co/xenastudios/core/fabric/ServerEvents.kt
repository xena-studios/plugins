package co.xenastudios.core.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType

// What the server tells a plugin, as events on the core's bus ([FabricPlatform.events]), fired
// from Fabric API's callbacks. Nothing in them is Fabric's: they move to core-common, beside
// the bus, once platform-bukkit fires the same ones, so shared code can listen to them.

/** A player finished joining: they're in a world and can be sent messages. Fired on the server thread. */
public class PlayerJoinEvent(
    /** Who joined. */
    public val player: PlayerRef,
    /** Their name. */
    public val name: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<PlayerJoinEvent> = EventType.of("core:player_join")
    }
}

/** A player left, or was disconnected. Fired on the server thread, while they're still in the player list. */
public class PlayerQuitEvent(
    /** Who left. */
    public val player: PlayerRef,
    /** Their name. */
    public val name: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<PlayerQuitEvent> = EventType.of("core:player_quit")
    }
}

/** The server finished starting: worlds are loaded and the first tick is next. */
public class ServerStartedEvent : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ServerStartedEvent> = EventType.of("core:server_started")
    }
}

/** The server is stopping: the last moment worlds and players can still be touched. */
public class ServerStoppingEvent : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ServerStoppingEvent> = EventType.of("core:server_stopping")
    }
}
