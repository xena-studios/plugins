package co.xenastudios.xteleport.api.event

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xteleport.api.TeleportType
import kotlin.jvm.JvmField

/**
 * A player is about to be teleported: after the warmup and the safety check, before the cost is
 * taken. Cancel it to stop the teleport (a combat tag, an arena); the caller gets
 * [co.xenastudios.xteleport.api.TeleportResult.BLOCKED] and nothing is charged.
 *
 * Fired on the player's thread, only when something listens. On Bukkit it's also
 * `co.xenastudios.xteleport.api.bukkit.BukkitPreTeleportEvent`.
 *
 * @since 1.0.0
 */
public class PreTeleportEvent(
    public val player: PlayerRef,
    /** Where they're going. */
    public val destination: Location,
    public val type: TeleportType,
) : CancellableEvent() {
    public companion object {
        /** Subscribe to it on [co.xenastudios.xteleport.api.XTeleportApi.events]. */
        @JvmField
        public val TYPE: EventType<PreTeleportEvent> = EventType.of("xteleport:pre_teleport")
    }
}

/**
 * A player arrived, by one of xTeleport's teleports or one asked for through the API. Fired on the
 * player's thread, only when something listens. On Bukkit it's also
 * `co.xenastudios.xteleport.api.bukkit.BukkitTeleportEvent`.
 *
 * @since 1.0.0
 */
public class TeleportEvent(
    public val player: PlayerRef,
    /** Where they were. */
    public val from: Location,
    /** Where they are now. */
    public val to: Location,
    public val type: TeleportType,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<TeleportEvent> = EventType.of("xteleport:teleport")
    }
}

/**
 * A player is setting a home with `/sethome`, within their limit. Cancel it to refuse (a claim
 * plugin's own land, say); the player is told nothing more, so tell them why. Not fired for homes
 * set through the API. On the player's thread. On Bukkit it's also
 * `co.xenastudios.xteleport.api.bukkit.BukkitHomeSetEvent`.
 *
 * @since 1.0.0
 */
public class HomeSetEvent(
    public val player: PlayerRef,
    /** As typed. */
    public val name: String,
    public val location: Location,
    /** Whether it moves a home they already have by that name. */
    public val replacing: Boolean,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<HomeSetEvent> = EventType.of("xteleport:home_set")
    }
}

/**
 * A player is sending a teleport request with `/tpa` or `/tpahere`. Cancel it to refuse; the
 * sender is told nothing more. On the sender's thread. On Bukkit it's also
 * `co.xenastudios.xteleport.api.bukkit.BukkitTpaRequestEvent`.
 *
 * @since 1.0.0
 */
public class TpaRequestEvent(
    /** Who's asking. */
    public val sender: PlayerRef,
    /** Who's asked. */
    public val target: PlayerRef,
    /** `true` for `/tpahere`: the target would come to the sender. */
    public val here: Boolean,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<TpaRequestEvent> = EventType.of("xteleport:tpa_request")
    }
}
