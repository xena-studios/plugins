package co.xenastudios.xutilities.api

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus

/**
 * xUtilities' developer API, the same on Paper, Folia, Spigot and Fabric: the spot `/back`
 * returns a player to, which of xUtilities' commands are on, and `/back`'s events.
 *
 * Get it with [XUtilities.api]. On Bukkit it's also in the services manager while xUtilities is
 * enabled. Every method may be called from any thread, answers from memory and returns at once.
 *
 * The `/back` methods work only while `/back` is turned on in xUtilities' config
 * ([isBackEnabled]). While it's off nothing is remembered: getting a spot returns `null` and
 * setting one does nothing.
 *
 * Players are by [PlayerRef] (a UUID): from Java, `PlayerRefs.of(player.getUniqueId())`.
 *
 * @since 3.0.0
 */
public interface XUtilitiesApi {
    /** The running xUtilities' version, such as `3.0.0`. */
    public val version: String

    /**
     * `/back`'s events, [co.xenastudios.xutilities.api.event.BackRecordEvent] and
     * [co.xenastudios.xutilities.api.event.BackTeleportEvent]. On Bukkit each is also fired as a
     * Bukkit event, `co.xenastudios.xutilities.api.bukkit.BukkitBackRecordEvent` and
     * `BukkitBackTeleportEvent`, so Skript and plugins listening the Bukkit way see them.
     */
    public val events: EventBus

    /** Whether `/back` is turned on, so xUtilities remembers where players were. */
    public fun isBackEnabled(): Boolean

    /**
     * Where `/back` would take [player]: where they were before their last teleport by a
     * command or plugin, or where they died. `null` if there's none, or `/back` is off.
     */
    public fun getBackLocation(player: PlayerRef): Location?

    /**
     * Sets where `/back` takes [player], for a teleport xUtilities doesn't see as one to go back
     * from (on Fabric, which has no teleport event, every teleport another mod makes). Doesn't
     * fire [co.xenastudios.xutilities.api.event.BackRecordEvent]: the caller decided. The spot
     * is forgotten when the player leaves.
     *
     * @return `true` if it was set; `false` if `/back` is off or the player isn't online
     */
    public fun setBackLocation(player: PlayerRef, location: Location): Boolean

    /** Forgets where `/back` would take [player], such as when they join a minigame they mustn't return to. */
    public fun clearBackLocation(player: PlayerRef)

    /**
     * Whether xUtilities has a command turned on under this name or alias: one of its commands, a
     * link command an owner defines, or `/xutilities` itself (alias `/xu`). [label] may have the
     * leading `/` and the `xutilities:` prefix; case doesn't matter.
     *
     * `true` means xUtilities registered the command, not that the bare label runs it: if
     * another plugin already had the label, xUtilities' is only reachable as `/xutilities:label`.
     */
    public fun isCommandEnabled(label: String): Boolean
}
