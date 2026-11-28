package co.xenastudios.xplots.api

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * A box of blocks, corners included: a plot, or a field's selection.
 *
 * @since 2.0.0
 */
public class PlotBox(
    public val minX: Int,
    public val minY: Int,
    public val minZ: Int,
    public val maxX: Int,
    public val maxY: Int,
    public val maxZ: Int,
) {
    /** Whether the block at [x] [y] [z] is in the box. */
    public fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    /** Blocks along x. */
    public val width: Int get() = maxX - minX + 1

    /** Blocks along y. */
    public val height: Int get() = maxY - minY + 1

    /** Blocks along z. */
    public val depth: Int get() = maxZ - minZ + 1

    override fun equals(other: Any?): Boolean = other is PlotBox && other.minX == minX && other.minY == minY && other.minZ == minZ &&
        other.maxX == maxX && other.maxY == maxY && other.maxZ == maxZ

    override fun hashCode(): Int = ((((minX * 31 + minY) * 31 + minZ) * 31 + maxX) * 31 + maxY) * 31 + maxZ

    override fun toString(): String = "$minX $minY $minZ to $maxX $maxY $maxZ"
}

/**
 * A plot as it was when it was asked for: a copy, which doesn't change after. Plots are named
 * after their field, `box-12`.
 *
 * @since 2.0.0
 */
public class PlotInfo(
    /** `box-12`: the field's name and the plot's number. */
    public val id: String,
    /** The field's name, `box`. */
    public val field: String,
    /** Its number in the field, from 1. */
    public val number: Int,
    public val world: WorldRef,
    /** The blocks that are the plot's: only these may be built in. */
    public val box: PlotBox,
    /** Who rents it, or `null` if it's free. */
    public val owner: PlayerRef?,
    /** Their name as last seen, or `null` if it's free. */
    public val ownerName: String?,
    /** When the rent runs out, in milliseconds since 1970; 0 if it's free. */
    public val expiresAt: Long,
    /** Its members (who may build there besides the owner) and their names as last seen, in the order they were added. */
    public val members: Map<PlayerRef, String>,
    /** How many members it may have, besides the owner. */
    public val slots: Int,
    /** Whether it's being put back as it was made, so nobody may build in or rent it yet. */
    public val resetting: Boolean,
) {
    /** Whether nobody rents it. */
    public val isFree: Boolean get() = owner == null

    /** Whether [player] may build there: its owner or a member. */
    public fun trusts(player: PlayerRef): Boolean = player == owner || player in members

    override fun toString(): String = "PlotInfo($id, ${ownerName ?: "free"})"
}

/**
 * A field: a group of plots that share settings, made from one selection.
 *
 * @since 2.0.0
 */
public class FieldInfo(
    /** Its name, lower-case: `box`. */
    public val name: String,
    public val world: WorldRef,
    /** The selection its grid was split from. */
    public val area: PlotBox,
    /** Its plots' ids, in number order. */
    public val plots: List<String>,
    /** How many of them are rented. */
    public val rented: Int,
    /** Whether it has a warp, for `/plots warp`. */
    public val hasWarp: Boolean,
) {
    override fun toString(): String = "FieldInfo($name, ${plots.size} plots)"
}

/** Why a plot's rent ended. @since 2.0.0 */
public enum class EndReason {
    /** Its owner gave it up: the menu's Abandon. */
    ABANDONED,

    /** Its rent ran out. */
    EXPIRED,

    /** Staff ended it: `/xplots plot evict`, the staff menu, or a plugin through the API. */
    EVICTED,
}

/** What money is being paid for. @since 2.0.0 */
public enum class RentKind {
    /** Renting a free plot, for one period. */
    RENT,

    /** Paying rent ahead, from the plot menu: one to three periods. */
    PAY,

    /** Rent renewed for one period on its own as it was running out, owner online or not (`auto-renew`). */
    RENEW,

    /** One more member slot. */
    SLOT,
}

/**
 * An add-on's say over prices ([XPlotsApi.registerPriceModifier]): a rank's discount, a busy
 * field's surcharge. Asked whenever a price is shown or charged, from memory on any thread, in
 * the order modifiers were registered, each given the last one's price.
 *
 * @since 2.0.0
 */
public fun interface PriceModifier {
    /** What [player] pays for [kind] on [plot], given [price] so far (the field's setting, times the periods). At least 0. */
    public fun price(player: PlayerRef, plot: PlotInfo, kind: RentKind, price: Double): Double
}

/** What an [AccessRule] decides. @since 2.0.0 */
public enum class Access {
    /** The player may build and use things here, whatever xPlots would say. */
    ALLOW,

    /** The player may not, whatever xPlots would say. */
    DENY,

    /** No say: the next rule's, or xPlots' own (owners and members). */
    DEFAULT,
}

/**
 * An add-on's say over who may build ([XPlotsApi.registerAccessRule]): a builder's pass for a
 * contest, a frozen plot. Asked for every block a player changes or uses inside a field, on that
 * place's thread: keep it quick and from memory. `xplots.bypass` still builds anywhere.
 *
 * @since 2.0.0
 */
public fun interface AccessRule {
    /** Whether [player] may change or use the block at [x] [y] [z] of [plot] (`null`: a field's path, not a plot). */
    public fun access(player: PlayerRef, plot: PlotInfo?, world: WorldRef, x: Int, y: Int, z: Int): Access
}

/**
 * Another region system's side of a field ([XPlotsApi.registerRegionBackend]): told as fields
 * and plots are made and deleted, so its own protection lets xPlots decide inside them. xPlots
 * keeps one for WorldGuard on Bukkit (one region per field that allows building); a claims
 * plugin can add its own. Called on the global thread.
 *
 * @since 2.0.0
 */
public interface RegionBackend {
    /** Makes or replaces a region called [id] over these columns of [world], from the bottom of the world to the top. */
    public fun define(id: String, world: WorldRef, columns: PlotBox)

    /** Removes the region called [id] in [world], if there is one. */
    public fun remove(id: String, world: WorldRef)
}

/** What an API change did. @since 2.0.0 */
public enum class ChangeResult {
    /** It was made. */
    DONE,

    /** There's no such plot. */
    NO_PLOT,

    /** The plot isn't rented, or is (for [XPlotsApi.setOwner], never). */
    NOT_RENTED,

    /** The player is already a member, or the owner. */
    ALREADY,

    /** The player isn't a member. */
    NOT_MEMBER,

    /** A listener cancelled it. */
    CANCELLED,

    /** xPlots isn't ready: its database hasn't been read yet. */
    NOT_READY,
    ;

    /** Whether it was made. */
    public val isDone: Boolean get() = this == DONE

    public companion object {
        /** The result named [name], for a bridge: [DONE]'s is `DONE`. */
        @JvmStatic
        public fun of(name: String): ChangeResult = entries.first { it.name == name }
    }
}

/** Limits the API holds to. @since 2.0.0 */
public object PlotLimits {
    /** The most members a plot can have: what a menu shows. */
    @JvmField
    public val MAX_SLOTS: Int = 45
}
