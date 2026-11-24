package co.xenastudios.xutilities.api.event

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.EventType
import kotlin.jvm.JvmField

/**
 * A spot is about to be saved as where `/back` takes a player. Cancel it to keep the spot they
 * had before, so `/back` can't return into a minigame or a jail.
 *
 * Fired on the player's thread (the main thread, or the player's region thread on Folia), only
 * while `/back` is on, and only when something listens. Not fired for
 * [co.xenastudios.xutilities.api.XUtilitiesApi.setBackLocation].
 *
 * @since 3.0.0
 */
public class BackRecordEvent(
    /** The player. */
    public val player: PlayerRef,
    /** The spot about to be saved. */
    public val location: Location,
    /** Why it's being saved. */
    public val reason: Reason,
) : CancellableEvent() {

    /** Why a spot is being saved. */
    public enum class Reason {
        /** The player was teleported by a command or a plugin; the spot is where they were. */
        TELEPORT,

        /** The player died; the spot is where. */
        DEATH,
    }

    public companion object {
        /** Subscribe to it on [co.xenastudios.xutilities.api.XUtilitiesApi.events]. */
        @JvmField
        public val TYPE: EventType<BackRecordEvent> = EventType.of("xutilities:back_record")
    }
}

/**
 * A player used `/back` and is about to be teleported. Cancel it to keep them where they are;
 * they're told nothing, so tell them why. Their `/back` spot is kept.
 *
 * Fired on the player's thread, only when something listens.
 *
 * @since 3.0.0
 */
public class BackTeleportEvent(
    /** The player using `/back`. */
    public val player: PlayerRef,
    /** Where `/back` would take them. */
    public val destination: Location,
) : CancellableEvent() {
    public companion object {
        /** Subscribe to it on [co.xenastudios.xutilities.api.XUtilitiesApi.events]. */
        @JvmField
        public val TYPE: EventType<BackTeleportEvent> = EventType.of("xutilities:back_teleport")
    }
}
