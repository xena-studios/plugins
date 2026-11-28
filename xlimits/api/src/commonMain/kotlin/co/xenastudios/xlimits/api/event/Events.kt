package co.xenastudios.xlimits.api.event

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.LimitKind
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.api.SpawnRoute
import kotlin.jvm.JvmField

/**
 * Something is about to be refused because a limit is reached: a mob or vehicle past its
 * chunk's cap, a block past its cap, TNT or a falling block, an item through a busy portal, a
 * tamed mob past its owner's cap, or a redstone block changing too often. **Cancel it to allow
 * it after all**; the player (if any) isn't told then.
 *
 * Fired on the thread that owns [location], only when something listens. On Bukkit it's also
 * `co.xenastudios.xlimits.api.bukkit.BukkitLimitReachedEvent`.
 *
 * @since 1.0.0
 */
public class LimitReachedEvent(
    public val kind: LimitKind,
    /** The limit's name: `animals`, `cow`, `hopper`, `tnt`, `falling_blocks`, `portal_items`, `pets` or `redstone`. */
    public val group: String,
    public val location: Location,
    /** The player who'd have done it: who placed it, used the spawn egg, bred or tamed. `null` for none. */
    public val player: PlayerRef?,
    /** How an entity came to be, for [LimitKind.ENTITY]; `null` for the other kinds. */
    public val route: SpawnRoute?,
    /** How many there are now. */
    public val count: Int,
    /** The limit they're at. */
    public val limit: Int,
) : CancellableEvent() {
    /** The chunk it's in. */
    public val chunk: ChunkRef get() = ChunkRef.of(location)

    public companion object {
        /** Subscribe to it on [co.xenastudios.xlimits.api.XLimitsApi.events]. */
        @JvmField
        public val TYPE: EventType<LimitReachedEvent> = EventType.of("xlimits:limit_reached")
    }
}

/**
 * The mobs of [group] in [chunk] passed their farm threshold and are about to be slowed by
 * [actions]. Cancel it to leave them as they are (they're asked again at the next spawn or breed
 * there). Named, tamed and leashed mobs are never slowed.
 *
 * Fired on the thread that owns [chunk], only when something listens. On Bukkit it's also
 * `co.xenastudios.xlimits.api.bukkit.BukkitFarmSlowedEvent`.
 *
 * @since 1.0.0
 */
public class FarmSlowedEvent(
    public val chunk: ChunkRef,
    public val group: String,
    public val count: Int,
    public val threshold: Int,
    public val actions: Set<SlowAction>,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<FarmSlowedEvent> = EventType.of("xlimits:farm_slowed")
    }
}

/**
 * The mobs of [group] in [chunk] went back to normal: their count fell under the threshold.
 * [restored] is how many mobs were changed back. (`/xlimits restore` fires none: it restores
 * every chunk at once, for owners removing the plugin.)
 *
 * Fired on the thread that owns [chunk], only when something listens. On Bukkit it's also
 * `co.xenastudios.xlimits.api.bukkit.BukkitFarmRestoredEvent`.
 *
 * @since 1.0.0
 */
public class FarmRestoredEvent(
    public val chunk: ChunkRef,
    public val group: String,
    public val restored: Int,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<FarmRestoredEvent> = EventType.of("xlimits:farm_restored")
    }
}

/**
 * A load profile turned on or off: [to] is on now (`normal` when none is), [from] was. [chunk] is
 * the chunk whose check noticed, and on Folia the region it's in is the only one that changed;
 * elsewhere the profile is the whole server's. Any thread: the one the check ran on.
 *
 * @since 1.0.0
 */
public class ProfileChangedEvent(
    public val from: String,
    public val to: String,
    public val chunk: ChunkRef,
    /** Whether the profile is per region (Folia) rather than the server's. */
    public val perRegion: Boolean,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ProfileChangedEvent> = EventType.of("xlimits:profile_changed")
    }
}
