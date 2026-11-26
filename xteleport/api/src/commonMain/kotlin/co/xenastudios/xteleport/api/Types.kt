package co.xenastudios.xteleport.api

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * One of a player's homes. Its [location]'s world may not be loaded now. Names are kept as typed
 * and compared in any case: `Base` and `base` are one home.
 *
 * @since 2.0.0
 */
public class Home(
    /** Whose home it is. */
    public val owner: PlayerRef,
    /** As the player typed it. */
    public val name: String,
    public val location: Location,
) {
    override fun equals(other: Any?): Boolean = other is Home && other.owner == owner && other.name == name && other.location == location

    override fun hashCode(): Int = (owner.hashCode() * 31 + name.hashCode()) * 31 + location.hashCode()

    override fun toString(): String = "Home($owner, $name, $location)"
}

/**
 * A warp, which everyone on the server shares (and, with per-warp permissions on, needs
 * `xteleport.warp.<name>` to use).
 *
 * @since 2.0.0
 */
public class Warp(
    /** As it was typed. */
    public val name: String,
    public val location: Location,
) {
    override fun equals(other: Any?): Boolean = other is Warp && other.name == name && other.location == location

    override fun hashCode(): Int = name.hashCode() * 31 + location.hashCode()

    override fun toString(): String = "Warp($name, $location)"
}

/** What kind of teleport it is, in events. @since 2.0.0 */
public enum class TeleportType {
    HOME,
    TPA,
    TPAHERE,
    WARP,
    SPAWN,
    RTP,

    /** Another plugin's, through [XTeleportApi.teleport]. */
    PLUGIN,
}

/** How a teleport ended. @since 2.0.0 */
public enum class TeleportResult {
    /** The player arrived. */
    TELEPORTED,

    /** The warmup was cancelled (they moved, or were hurt), or xTeleport stopped first. */
    CANCELLED,

    /** The cooldown is still running; the player was told how long is left. */
    ON_COOLDOWN,

    /** The player can't pay, or costs are set and there's no economy. */
    CANT_AFFORD,

    /** The destination is unsafe now (lava, inside blocks, the void, outside the border), or its world isn't loaded. */
    UNSAFE,

    /** A listener cancelled [co.xenastudios.xteleport.api.event.PreTeleportEvent]. */
    BLOCKED,

    /** The server refused it, the player left, or there was nowhere to go. */
    FAILED,
}

/**
 * How a teleport through the API behaves. [defaults] is xTeleport's warmup, no cooldown, no cost
 * and the safety check; each `with` method returns a copy with one thing changed:
 *
 * ```java
 * TeleportOptions.defaults().withWarmup(0).withCost(25.0).withCooldown("arena", 60)
 * ```
 *
 * @since 2.0.0
 */
public class TeleportOptions @JvmOverloads constructor(
    /** Seconds to stand still first, or -1 for the config's `teleports.warmup`. */
    public val warmupSeconds: Int = -1,
    /** The cooldown's key: blank for none. A key a command uses (`home`) shares that command's. */
    public val cooldownKey: String = "",
    /** How long the cooldown is, once the player arrives. */
    public val cooldownSeconds: Int = 0,
    /** What it costs, through the economy (xMoney, or Vault on Bukkit); 0 is free. */
    public val cost: Double = 0.0,
    /** Whether an unsafe destination is refused ([TeleportResult.UNSAFE]). */
    public val checkSafe: Boolean = true,
) {
    init {
        require(cooldownSeconds >= 0) { "cooldownSeconds can't be negative" }
        require(cost >= 0 && !cost.isNaN()) { "cost can't be negative" }
    }

    public fun withWarmup(seconds: Int): TeleportOptions = TeleportOptions(seconds, cooldownKey, cooldownSeconds, cost, checkSafe)

    public fun withCooldown(key: String, seconds: Int): TeleportOptions = TeleportOptions(warmupSeconds, key, seconds, cost, checkSafe)

    public fun withCost(cost: Double): TeleportOptions = TeleportOptions(warmupSeconds, cooldownKey, cooldownSeconds, cost, checkSafe)

    public fun withCheckSafe(check: Boolean): TeleportOptions = TeleportOptions(warmupSeconds, cooldownKey, cooldownSeconds, cost, check)

    override fun toString(): String = "TeleportOptions(warmup=$warmupSeconds, cooldown=$cooldownKey/$cooldownSeconds, cost=$cost, checkSafe=$checkSafe)"

    public companion object {
        /** The config's warmup, no cooldown, free, checked for safety. */
        @JvmStatic
        public fun defaults(): TeleportOptions = DEFAULTS

        /** No warmup, no cooldown, free, and checked for safety: for a plugin sending a player back. */
        @JvmStatic
        public fun immediate(): TeleportOptions = IMMEDIATE

        private val DEFAULTS = TeleportOptions()
        private val IMMEDIATE = TeleportOptions(warmupSeconds = 0)
    }
}

/**
 * Where [XTeleportApi.findSafeLocation] looks: a ring from [minRadius] to [maxRadius] blocks around
 * [centerX], [centerZ], inside the world border, trying up to [attempts] spots.
 *
 * @since 2.0.0
 */
public class RtpSettings @JvmOverloads constructor(
    public val centerX: Int,
    public val centerZ: Int,
    public val minRadius: Int,
    public val maxRadius: Int,
    public val attempts: Int = 10,
    /** Only chunks that were already generated, so the search never makes the server generate terrain. */
    public val onlyGenerated: Boolean = false,
) {
    init {
        require(minRadius in 0..maxRadius) { "minRadius must be from 0 to maxRadius" }
        require(attempts in 1..50) { "attempts must be from 1 to 50" }
    }

    override fun toString(): String = "RtpSettings($centerX, $centerZ, $minRadius..$maxRadius, $attempts, onlyGenerated=$onlyGenerated)"
}

/**
 * A destination another plugin offers by name ([XTeleportApi.registerDestination]): asked where to
 * once the player's warmup is over.
 *
 * @since 2.0.0
 */
public fun interface DestinationProvider {
    /** Where [player] goes, or `null` if there's nowhere for them. Called on the player's thread; may complete on any. */
    public fun destination(player: PlayerRef): Pending<Location?>
}

/**
 * Homes another plugin keeps ([XTeleportApi.registerHomes]), such as plot homes.
 *
 * @since 2.0.0
 */
public fun interface HomeProvider {
    /** [player]'s homes from this plugin, from memory: called on any thread, often. */
    public fun homes(player: PlayerRef): List<Home>
}

/**
 * Who can't see whom, for `/tpa` ([XTeleportApi.registerVisibility]): a vanish plugin's.
 *
 * @since 2.0.0
 */
public fun interface VisibilityHook {
    /** Whether [target] is hidden from [viewer]. From memory, on any thread. */
    public fun hidden(viewer: PlayerRef, target: PlayerRef): Boolean
}

/** The names xTeleport's API takes for homes and warps. @since 2.0.0 */
public object Names {
    /** The longest a home's or warp's name may be. */
    @JvmField
    public val MAX_LENGTH: Int = 32

    /** Whether [name] can be a home's or a warp's: 1 to 32 letters, digits, `-` or `_`. */
    @JvmStatic
    public fun valid(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_LENGTH) return false
        for (c in name) {
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_')) return false
        }
        return true
    }
}
