package co.xenastudios.xvanish.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xvanish.api.VanishCause
import kotlin.jvm.JvmField

/**
 * A player is about to vanish ([vanishing] `true`) or show again. Cancel it to refuse (a
 * minigame that mustn't lose a player); the one who asked is told it was refused. Not fired for a
 * change another server made ([VanishCause.NETWORK]): that server fired it.
 *
 * Fired on the player's thread, only when something listens. On Bukkit it's also
 * `co.xenastudios.xvanish.api.bukkit.BukkitVanishEvent`.
 *
 * @since 1.0.0
 */
public class VanishEvent(
    public val player: PlayerRef,
    /** `true` to vanish, `false` to show. */
    public val vanishing: Boolean,
    /** Whether the fake join or quit message is left out. */
    public val silent: Boolean,
    public val cause: VanishCause,
) : CancellableEvent() {
    public companion object {
        /** Subscribe to it on [co.xenastudios.xvanish.api.XVanishApi.events]. */
        @JvmField
        public val TYPE: EventType<VanishEvent> = EventType.of("xvanish:vanish")
    }
}

/**
 * A player vanished: hidden from everyone who can't see them. On the player's thread, only when
 * something listens. On Bukkit it's also `co.xenastudios.xvanish.api.bukkit.BukkitVanishedEvent`.
 *
 * @since 1.0.0
 */
public class VanishedEvent(
    public val player: PlayerRef,
    public val silent: Boolean,
    public val cause: VanishCause,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<VanishedEvent> = EventType.of("xvanish:vanished")
    }
}

/**
 * A player showed again: everyone sees them. On the player's thread, only when something
 * listens. On Bukkit it's also `co.xenastudios.xvanish.api.bukkit.BukkitUnvanishedEvent`.
 *
 * @since 1.0.0
 */
public class UnvanishedEvent(
    public val player: PlayerRef,
    public val silent: Boolean,
    public val cause: VanishCause,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<UnvanishedEvent> = EventType.of("xvanish:unvanished")
    }
}
