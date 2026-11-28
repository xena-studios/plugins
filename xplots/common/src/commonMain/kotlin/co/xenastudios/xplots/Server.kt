package co.xenastudios.xplots

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menus
import co.xenastudios.core.common.text.Component
import co.xenastudios.xplots.api.RegionBackend
import co.xenastudios.xplots.model.Box

/** A player who has played here: their [player] and their name as the server knows it. */
public class Known(public val player: PlayerRef, public val name: String)

/** What rent is paid with when `currency.type` is `item`: matched by material, and by name and custom model data when set. */
public class ItemCurrency(public val material: String, public val name: String, public val customModelData: Int)

/**
 * What xPlots needs from the server it runs on, which each adapter gives: players, blocks, signs,
 * particles, items, and the region system it has. Everything that touches the world is called on
 * the thread that owns the place (its region's on Folia), everything that touches a player on
 * theirs; the rest say otherwise. The decisions (who may build, what a plot is) are xPlots', in
 * this module: an adapter hears its platform's events and asks ([Protection]).
 */
public interface PlotServer {
    // ---- players: any thread unless said ----
    public fun online(): List<PlayerRef>
    public fun isOnline(player: PlayerRef): Boolean
    public fun name(player: PlayerRef): String?
    public fun send(player: PlayerRef, message: Component)
    public fun actionBar(player: PlayerRef, message: Component)
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** The highest `<prefix><n>` permission an online [player] has (`xplots.limit.3` is 3), or -1. On their thread. */
    public fun highestNumbered(player: PlayerRef, prefix: String): Int

    /** Where an online player is, with where they look; `null` if they're not online. On their thread. */
    public fun position(player: PlayerRef): Location?

    /** A player who has joined before, by [name] in any case, or `null`. From the server's memory, without a lookup. */
    public fun known(name: String): Known?

    /** Teleports an online player, without blocking where the server can. On their thread. */
    public fun teleport(player: PlayerRef, to: Location)

    /** Whether the server shows [target] to [viewer] (Bukkit's `canSee`). */
    public fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean

    // ---- the world ----
    public fun hasWorld(world: String): Boolean
    public fun worlds(): List<String>

    /** Whether the chunk is loaded. Any thread. */
    public fun isLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean

    /**
     * Loads the chunk in the background where the server can, and keeps it loaded (a ticket) until
     * [release]; settles `false` if it couldn't be. Any thread.
     */
    public fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean>

    /** Lets a chunk [load] kept go again. Any thread. */
    public fun release(world: String, chunkX: Int, chunkZ: Int)

    /** The block's state, as Minecraft writes it: `minecraft:oak_stairs[facing=north,half=bottom]`. On the place's thread. */
    public fun stateAt(world: String, x: Int, y: Int, z: Int): String

    /** [state] as this server's own block data, or `null` if this server can't read it (a newer version's block). Any thread. */
    public fun compile(state: String): Any?

    /** Whether the block is [compiled] (from [compile]) already. On the place's thread. */
    public fun matches(world: String, x: Int, y: Int, z: Int, compiled: Any): Boolean

    /** Whether [compiled] is a block that holds something: a chest, a sign, a furnace. Any thread. */
    public fun holds(compiled: Any): Boolean

    /** Sets the block to [compiled] without physics; [fresh] first clears it, so a chest's items or a sign's text go. On the place's thread. */
    public fun set(world: String, x: Int, y: Int, z: Int, compiled: Any, fresh: Boolean)

    /** Moves players in [box] to [front] and takes away items, frames and the like lying there (not mobs). On the place's thread. */
    public fun clearOut(world: String, box: Box, front: Location)

    /** Whether the block is empty or can be replaced (grass, snow), so a sign can go there. On the place's thread. */
    public fun isFree(world: String, x: Int, y: Int, z: Int): Boolean

    // ---- signs, on the place's thread ----
    public fun isSign(world: String, x: Int, y: Int, z: Int): Boolean

    /** Places a standing sign of [material] (any standing sign; a fallback if this server has none of that name), facing [facing]. */
    public fun placeSign(world: String, x: Int, y: Int, z: Int, material: String, facing: Int)

    /** Writes [lines] (at most four) on the sign's front, which players then can't edit; `false` if there's no sign there. */
    public fun writeSign(world: String, x: Int, y: Int, z: Int, lines: List<Component>): Boolean

    /** Takes the sign away, if it's still a sign. */
    public fun removeSign(world: String, x: Int, y: Int, z: Int)

    // ---- what players see and hold ----

    /** Shows [player] alone [count] dust particles of [rgb] at [x] [y] [z], spread by [spread]. On their thread. */
    public fun dust(player: PlayerRef, x: Double, y: Double, z: Double, rgb: Int, size: Float, count: Int, spread: Double)

    /** Gives the wand: a blaze rod that shines, named [name] with [lore], marked as xPlots'. On their thread. */
    public fun giveWand(player: PlayerRef, name: Component, lore: List<Component>)

    /** The menus' item by its id (`oak_sign`, `minecraft:clock`), or a fallback this server has; `null` for air. Any thread. */
    public fun icon(id: String): Icon?

    /** A head of [player] (their skin where the server knows it), as an [Icon]'s data; `null` if it can't be made. */
    public fun head(player: PlayerRef, name: String): String?

    /** The platform's chest menus. */
    public val menus: Menus

    // ---- item currency, on the player's thread ----
    public fun countItems(player: PlayerRef, currency: ItemCurrency): Int
    public fun takeItems(player: PlayerRef, currency: ItemCurrency, amount: Int): Boolean

    /** Gives [amount] of the currency's item, named as it's matched; what doesn't fit is dropped at their feet. */
    public fun giveItems(player: PlayerRef, currency: ItemCurrency, amount: Int)

    // ---- what the server has beside ----

    /** The region system's side of fields (WorldGuard's on Bukkit), or `null` without one. */
    public fun regions(): RegionBackend?

    /** The world and box of [player]'s WorldEdit selection, or `null` without WorldEdit or a selection. */
    public fun worldEditSelection(player: PlayerRef): Pair<String, Box>?
}

/**
 * Money, from whichever economy the server has: xMoney 2.0's API (`XMoneyBridge`, each payment in
 * its ledger as `xPlots` with the plot) or Vault's. By UUID, so an owner who's offline can be
 * charged for a renewal.
 */
public interface Economy {
    /** For the console: `xMoney`, `Vault (EssentialsX Economy)`. */
    public val name: String

    public fun has(player: PlayerRef, amount: Double): Boolean

    /** Takes [amount], settling with whether it was taken. [reason] is a few words: `rent for box-12`. */
    public fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean>

    public fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean>

    /** [amount] as the economy writes money, or `null` to write the number. */
    public fun format(amount: Double): String?

    /**
     * Whether [player]'s ledger has a payment from xPlots whose reason has [marker] in it: a
     * renewal's charge that a crash may have left unsettled. `null` when the economy keeps no
     * ledger it can ask (Vault), which xPlots then takes as paid, so it never charges twice.
     */
    public fun charged(player: PlayerRef, marker: String): Pending<Boolean?>
}

/** xRewind 2.0's rollbacks, through its API: the changes a player made inside one plot. */
public interface Rewind {
    /**
     * Rolls back what [playerName] did in [world] inside [box] in the last [seconds], with
     * xRewind's per-tick budget; settles with how many changes were undone, or why it couldn't.
     */
    public fun rollback(world: String, box: Box, playerName: String, seconds: Long): Pending<co.xenastudios.core.common.Outcome<Int, String>>
}

/** A plot home as xTeleport lists it: its name (`box-12`) and where it puts the player. */
public class PlotHome(public val name: String, public val location: Location)

/**
 * The family's plugins xPlots works with, through their APIs, each found when it's first needed
 * (an economy plugin often registers after xPlots starts). Every one is optional.
 */
public interface Hooks {
    /** The economy: xMoney's, else Vault's; `null` without either. */
    public fun economy(): Economy?

    /** xRewind's rollbacks, or `null` without xRewind 2.0. */
    public fun rewind(): Rewind?

    /** Whether [target] is vanished from [viewer] by xVanish (its levels, and `xvanish.see`); `false` without xVanish. */
    public fun vanished(viewer: PlayerRef, target: PlayerRef): Boolean

    /**
     * Lists [homes] in xTeleport's `/homes` (its extra-homes seam), until the returned handle is
     * closed; `null` without xTeleport 2.0. [homes] is asked from memory, on any thread.
     */
    public fun registerHomes(homes: (PlayerRef) -> List<PlotHome>): AutoCloseable?
}
