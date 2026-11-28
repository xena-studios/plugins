package co.xenastudios.xplots

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.xplots.api.Access
import co.xenastudios.xplots.api.AccessRule
import co.xenastudios.xplots.api.PriceModifier
import co.xenastudios.xplots.api.RegionBackend
import co.xenastudios.xplots.api.RentKind
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.model.Field
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.info
import kotlin.concurrent.Volatile

/**
 * Who may change what in a field: the decisions, the same on every platform, which each adapter
 * asks from its own events (Bukkit's listeners now; Fabric's and NeoForge's later, with no
 * WorldGuard). In a rented plot, its owner and members may build, open containers and use doors
 * and buttons; nobody else may. The rest of a field (its paths, and above and below its plots)
 * and plot signs are staff's alone, as are free plots. `xplots.bypass` may do anything.
 *
 * Finding the plot for a block is a box check and some arithmetic, the same for ten plots or ten
 * thousand; a block outside every field stops at the first check. Changes that cross from one
 * plot to another (pistons, flowing liquids, fire, trees growing) are stopped at the edge. Every
 * answer is from memory, on any thread (a Folia region's). Until the plots are read, every
 * change a player makes is refused, and every change that could cross into a field stopped.
 */
public class Protection internal constructor(private val plugin: XPlotsPlugin) {
    // When each player was last told, so holding a click doesn't flood the action bar.
    private val told = concurrentMap<PlayerRef, Long>()

    /** What a block is to xPlots: `null` outside every field, [Field.PATH] for a field's protected spots, or the plot. */
    public fun at(world: String, x: Int, y: Int, z: Int): Plot? = plugin.plots.at(world, x, y, z)

    /** Whether the block is any plot's sign. */
    public fun isSign(world: String, x: Int, y: Int, z: Int): Boolean = plugin.plots.signAt(world, x, y, z) != null

    /** Whether the block is xPlots' to protect at all: in a field, or a sign (or anything, until the plots are read). */
    public fun covers(world: String, x: Int, y: Int, z: Int): Boolean = !plugin.isReady || at(world, x, y, z) != null || isSign(world, x, y, z)

    /** Whether the world has any field, for events that stop at the world. */
    public fun hasFields(world: String): Boolean = !plugin.isReady || plugin.plots.hasFields(world)

    /** Whether [player] may change or use the block; [bypass] is whether they have `xplots.bypass`. */
    public fun mayBuild(player: PlayerRef, bypass: Boolean, world: String, x: Int, y: Int, z: Int): Boolean {
        if (bypass) return true
        if (!plugin.isReady) return false
        if (isSign(world, x, y, z)) return false
        val plot = at(world, x, y, z) ?: return true
        return mayBuild(player, plot, world, x, y, z)
    }

    /** Whether [player] may build in [plot] ([Field.PATH] for a path) at that block, without `xplots.bypass`. */
    public fun mayBuild(player: PlayerRef, plot: Plot, world: String, x: Int, y: Int, z: Int): Boolean {
        when (plugin.addonsIfMade?.access(player, plot, world, x, y, z) ?: Access.DEFAULT) {
            Access.ALLOW -> return true
            Access.DENY -> return false
            Access.DEFAULT -> {}
        }
        if (plot === Field.PATH) return false
        val lease = plot.lease ?: return false
        return lease.trusts(player) && !plugin.isResetting(plot)
    }

    /**
     * Whether a change spreading from one block to another crosses a plot's edge, or touches a
     * sign: water or lava, fire, a piston's push, a tree's growth. Path to path is fine: water
     * staff put on a path may flow along it.
     */
    public fun crosses(world: String, fromX: Int, fromY: Int, fromZ: Int, toWorld: String, toX: Int, toY: Int, toZ: Int): Boolean {
        if (!plugin.isReady) return true
        if (isSign(toWorld, toX, toY, toZ)) return true
        val target = at(toWorld, toX, toY, toZ) ?: return false
        val source = if (world == toWorld) at(world, fromX, fromY, fromZ) else null
        return source !== target
    }

    /** Tells [player] on the action bar that they can't, at most once every 1.5 seconds. */
    public fun tell(player: PlayerRef) {
        val now = plugin.now()
        val last = told[player]
        if (last != null && now - last <= 1500) return
        told[player] = now
        plugin.texts.render(player, XPlotsMessages.protectedHere)?.let { plugin.server.actionBar(player, it) }
    }

    /** Forgets a player who left, so the map doesn't grow. */
    public fun forget(player: PlayerRef) {
        told.remove(player)
    }
}

/**
 * What other plugins registered through the API: price modifiers, access rules and region
 * backends, by owner. Read on every price and every protected block, so each kind is a list
 * replaced whole as it changes, and costs one check while there are none.
 */
public class Addons internal constructor(private val plugin: XPlotsPlugin) {
    private class Entry<T>(val owner: String, val value: T)

    @Volatile
    private var modifiers: List<Entry<PriceModifier>> = emptyList()

    @Volatile
    private var rules: List<Entry<AccessRule>> = emptyList()

    @Volatile
    private var backends: List<Entry<RegionBackend>> = emptyList()

    internal fun add(owner: String, modifier: PriceModifier) = locked(this) { modifiers = modifiers + Entry(owner, modifier) }

    internal fun add(owner: String, rule: AccessRule) = locked(this) { rules = rules + Entry(owner, rule) }

    internal fun add(owner: String, backend: RegionBackend) {
        locked(this) { backends = backends + Entry(owner, backend) }
        if (plugin.isReady) defineAll(backend, owner)
    }

    internal fun remove(owner: String) = locked(this) {
        modifiers = modifiers.filter { it.owner != owner }
        rules = rules.filter { it.owner != owner }
        backends = backends.filter { it.owner != owner }
    }

    internal fun clear() = locked(this) {
        modifiers = emptyList()
        rules = emptyList()
        backends = emptyList()
    }

    /** The price after every modifier, never below 0. */
    internal fun price(player: PlayerRef, plot: Plot, kind: RentKind, base: Double): Double {
        val list = modifiers
        if (list.isEmpty()) return base
        var price = base
        val shown = info(plot, plugin.isResetting(plot))
        for (entry in list) {
            price = try {
                entry.value.price(player, shown, kind, price).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: price
            } catch (e: Exception) {
                plugin.log.warn("${entry.owner}'s price modifier failed: $e")
                price
            }
        }
        return price
    }

    /** The first rule's say that isn't [Access.DEFAULT]. */
    internal fun access(player: PlayerRef, plot: Plot, world: String, x: Int, y: Int, z: Int): Access {
        val list = rules
        if (list.isEmpty()) return Access.DEFAULT
        val shown = if (plot === Field.PATH) null else info(plot, plugin.isResetting(plot))
        val ref = WorldRef.of(world)
        for (entry in list) {
            val said = try {
                entry.value.access(player, shown, ref, x, y, z)
            } catch (e: Exception) {
                plugin.log.warn("${entry.owner}'s access rule failed: $e")
                Access.DEFAULT
            }
            if (said != Access.DEFAULT) return said
        }
        return Access.DEFAULT
    }

    // ---- regions: the server's own (WorldGuard) and the API's backends ----

    private fun all(): List<Pair<String, RegionBackend>> = buildList {
        plugin.server.regions()?.let { add("WorldGuard" to it) }
        backends.forEach { add(it.owner to it.value) }
    }

    /** Every region a field needs: one over its grid, and one for each plot made by itself outside it. */
    private fun regionsOf(field: Field): List<Pair<String, Box>> = buildList {
        add(field.regionId to field.layout.area)
        for (plot in field.plots()) {
            if (!field.isGrid(plot) && !field.layout.area.overlapsColumns(plot.box)) add("${field.regionId}-${plot.number}" to plot.box)
        }
    }

    /** As the plots go live: each backend registered before then hears of every field. */
    internal fun regionsLoaded() {
        for (entry in backends) defineAll(entry.value, entry.owner)
    }

    private fun defineAll(backend: RegionBackend, owner: String) {
        for (field in plugin.plots.fields()) {
            for ((id, box) in regionsOf(field)) call(owner) { backend.define(id, field.worldRef, box.info()) }
        }
    }

    /** A region made: [box]'s columns, called [id]. */
    internal fun define(id: String, world: String, box: Box) {
        val ref = WorldRef.of(world)
        for ((owner, backend) in all()) call(owner) { backend.define(id, ref, box.info()) }
    }

    internal fun removeRegion(id: String, world: String) {
        val ref = WorldRef.of(world)
        for ((owner, backend) in all()) call(owner) { backend.remove(id, ref) }
    }

    private inline fun call(owner: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            plugin.log.warn("$owner couldn't change a region for xPlots: $e")
        }
    }
}
