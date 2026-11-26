package co.xenastudios.xteleport.api

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xTeleport's developer API, the same on every platform: teleports that behave like xTeleport's
 * own (warmup, cooldown, cost and a safe-arrival check), players' homes, the warps and the
 * spawn, the random teleport search, and the seams add-ons hook into (named destinations, homes
 * of their own, who's hidden from `/tpa`). Players are [PlayerRef]s and places core
 * [Location]s, so one API works everywhere.
 *
 * Get it with [XTeleport.api]; on Bukkit it's also in the services manager. Every method says
 * how it runs: either it answers from memory, on any thread, or it returns a [Pending] (a
 * `CompletableFuture` on the JVM) that completes later, often off the server's threads. Code
 * that then touches a player or the world moves to the right thread itself (the player's own on
 * Folia). Nothing here reads the database on the calling thread.
 *
 * @since 2.0.0
 */
public interface XTeleportApi {
    /** xTeleport's version, such as `2.0.0`. */
    public val version: String

    /** Where xTeleport's events are heard ([co.xenastudios.xteleport.api.event]). On Bukkit they're Bukkit events too. */
    public val events: EventBus

    /**
     * Teleports [player] the way xTeleport's commands do: checks the cooldown and the cost, waits
     * out the warmup (moving cancels it, and taking damage if the config says so), loads the
     * destination in the background and refuses it if it's unsafe, fires
     * [co.xenastudios.xteleport.api.event.PreTeleportEvent], charges, then teleports, without
     * blocking where the server can (Paper and Folia). A teleport that fails after charging is
     * refunded. The player is told each step in xTeleport's messages. Any thread.
     *
     * @param player an online player
     * @param destination where to
     * @return how it ended, once it has: [TeleportResult.FAILED] at once if [player] isn't online
     */
    public fun teleport(player: PlayerRef, destination: Location, options: TeleportOptions): Pending<TeleportResult>

    /**
     * Teleports [player] to a destination an add-on registered ([registerDestination]) by its
     * [name], worked out once the warmup is over. [TeleportResult.FAILED] if there's no such
     * destination, or it has nowhere for this player.
     */
    public fun teleport(player: PlayerRef, destination: String, options: TeleportOptions): Pending<TeleportResult>

    /**
     * The milliseconds until [player] may use the cooldown [key] again: `home`, `rtp`, any
     * command's name, or a key a plugin gave in [TeleportOptions]. 0 when it's ready. Any thread.
     */
    public fun cooldownMillis(player: PlayerRef, key: String): Long

    /**
     * An online player's homes, from memory, sorted by name; `null` if they aren't online or their
     * homes haven't been read yet (a moment after they join). Homes from [registerHomes] aren't in
     * it. Any thread.
     */
    public fun cachedHomes(player: PlayerRef): List<Home>?

    /** Any player's homes, online or not, sorted by name: from memory when it can, otherwise from the database. */
    public fun homes(player: PlayerRef): Pending<List<Home>>

    /**
     * Sets a home for [player], online or not, replacing one of the same name. It doesn't check the
     * home limit or fire [co.xenastudios.xteleport.api.event.HomeSetEvent]: the caller decided.
     * Completes with `true` once it's saved, with `false` at once if [name] isn't 1 to 32 letters,
     * digits, `-` or `_`, and exceptionally if the database couldn't save it.
     */
    public fun setHome(player: PlayerRef, name: String, location: Location): Pending<Boolean>

    /** Deletes one of [player]'s homes; `true` if there was one, exceptionally if the database couldn't delete it. */
    public fun deleteHome(player: PlayerRef, name: String): Pending<Boolean>

    /**
     * How many homes an online [player] may have: the config's default and limits and their
     * `xteleport.homes.<n>` permissions, the highest; [Int.MAX_VALUE] for no limit. The default
     * limit for a player who isn't online. Any thread, from memory.
     */
    public fun homeLimit(player: PlayerRef): Int

    /** Every warp, sorted by name. Any thread, from memory. */
    public fun warps(): List<Warp>

    /** The warp called [name] in any case, or `null`. Any thread, from memory. */
    public fun warp(name: String): Warp?

    /** Sets a warp, replacing one of the same name. As [setHome]. */
    public fun setWarp(name: String, location: Location): Pending<Boolean>

    /** Deletes a warp; `true` if there was one. As [deleteHome]. */
    public fun deleteWarp(name: String): Pending<Boolean>

    /**
     * Where `/spawn` goes: the spawn set with `/setspawn`, or the main world's spawn point if none
     * is set; `null` only before the server has a world. Any thread, from memory.
     */
    public fun spawn(): Location?

    /**
     * Looks for a safe random spot in [world], with the search `/rtp` uses: inside the world
     * border, no lava, water, leaves, powder snow or void, below the roof in the Nether and off
     * the main island in the End. Each candidate's chunk loads in the background (on Spigot, one a
     * tick on the server thread), and the search counts towards xTeleport's limit on searches at
     * once. Completes with a spot, or `null` if none was found in the attempts, or too many
     * searches are running.
     */
    public fun findSafeLocation(world: WorldRef, settings: RtpSettings): Pending<Location?>

    /**
     * Adds a destination other plugins can send players to by [name] ([teleport]), such as xLimbo's
     * way back or xPlots' plot entrances. [owner] is the plugin's name; a name already taken is
     * replaced. [unregisterAll] takes an owner's away.
     */
    public fun registerDestination(owner: String, name: String, provider: DestinationProvider)

    /**
     * Adds homes that come from another plugin, such as xPlots' plot homes: listed in `/homes` and
     * gone to with `/home <name>`, after the player's own of the same name. They don't count
     * towards the limit, and `/delhome` doesn't delete them.
     */
    public fun registerHomes(owner: String, provider: HomeProvider)

    /**
     * Adds a check of who can't see whom (a vanish plugin): a player hidden from the sender can't be
     * sent a request, named in `/tpaccept`, or completed in `/tpa`. The server's own visibility
     * (Bukkit's `canSee`) always counts as well.
     */
    public fun registerVisibility(owner: String, hook: VisibilityHook)

    /** Takes away every destination, home provider and visibility check [owner] registered, as it disables. */
    public fun unregisterAll(owner: String)
}

/**
 * Where xTeleport's API is found, on every platform:
 *
 * ```java
 * XTeleportApi api = XTeleport.api();
 * ```
 *
 * @since 2.0.0
 */
public object XTeleport {
    @Volatile
    private var running: XTeleportApi? = null

    /**
     * xTeleport's API.
     *
     * @throws IllegalStateException if xTeleport isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XTeleportApi =
        running ?: throw IllegalStateException("xTeleport isn't enabled. Add it to depend (or softdepend and check XTeleport.isEnabled() first).")

    /** Whether xTeleport is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xTeleport as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XTeleportApi?) {
        running = api
    }
}
