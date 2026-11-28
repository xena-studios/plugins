package co.xenastudios.xplots

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.model.Field
import co.xenastudios.xplots.model.Layout
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.SignPlacement
import co.xenastudios.xplots.model.SignSpot
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Why something can't be made: a message and its placeholders. */
public class Problem(public val message: MessageKey, public vararg val placeholders: Pair<String, Any>)

/** Makes and deletes fields and plots: in memory, in the database, in the world, and in the region systems. */
public class Builder internal constructor(private val plugin: XPlotsPlugin) {
    private fun placement(): SignPlacement = XPlotsConfig.signs.placement(plugin.config)

    /** Why a field can't go here, or `null` if it can. */
    public fun problem(name: String, world: String, layout: Layout?): Problem? {
        if (!NAME.matches(name)) return Problem(XPlotsMessages.whyName)
        if (plugin.plots.field(name) != null) return Problem(XPlotsMessages.whyTaken, "field" to name)
        if (layout == null) return Problem(XPlotsMessages.whyTooSmall)
        if (layout.count > MAX_PLOTS) return Problem(XPlotsMessages.whyTooMany, "count" to layout.count.toString(), "max" to MAX_PLOTS.toString())
        return overlap(world, layout.area, null)
    }

    private fun overlap(world: String, box: Box, own: Field?): Problem? {
        for (field in plugin.plots.fields()) {
            if (field.world != world) continue
            if (field !== own && field.layout.area.overlapsColumns(box)) return Problem(XPlotsMessages.whyOverlapsField, "field" to field.name)
            for (plot in field.plots()) {
                if (plot.box.overlapsColumns(box)) return Problem(XPlotsMessages.whyOverlapsPlot, "plot" to plot.id)
            }
        }
        return null
    }

    /** Makes the field: saves it, saves each plot's blocks as what it's put back to, then places the signs; [done] once they're up. */
    public fun createField(name: String, world: String, layout: Layout, done: () -> Unit): Field {
        val field = locked(plugin.lock) {
            val field = Field(plugin.plots.maxFieldId() + 1, name, world, layout)
            for (spot in layout.spots(field.signPlacement(placement()))) {
                field.add(Plot(field, spot.number, spot.box, SignSpot(spot.signX, spot.signY, spot.signZ, spot.facing)))
            }
            plugin.plots.index(field)
            field
        }
        plugin.fieldsChanged()
        plugin.store?.saveField(field)
        plugin.addons.define(field.regionId, world, layout.area)
        val plots = field.plots()
        plugin.resets.capture(plots, PlaceAll(plugin, plots, done))
        return field
    }

    private class PlaceAll(private val plugin: XPlotsPlugin, private val plots: List<Plot>, private val done: () -> Unit) : () -> Unit {
        override fun invoke() {
            plots.forEach(plugin.signs::place)
            done()
        }
    }

    /** Deletes the field: rents end without refunds, signs come down, plots stay as they are. */
    public fun deleteField(field: Field) {
        for (plot in field.plots()) {
            plugin.signs.remove(plot)
            if (!field.isGrid(plot) && !field.layout.area.overlapsColumns(plot.box)) plugin.addons.removeRegion("${field.regionId}-${plot.number}", field.world)
        }
        plugin.addons.removeRegion(field.regionId, field.world)
        locked(plugin.lock) { plugin.plots.remove(field) }
        field.plots().forEach(plugin.renewals::ended)
        plugin.fieldsChanged()
        plugin.store?.deleteField(field)
        plugin.expiry.reschedule()
    }

    /** Why a plot by itself can't go here, or `null` if it can. */
    public fun plotProblem(field: Field, world: String, box: Box): Problem? {
        if (field.world != world) return Problem(XPlotsMessages.whyOtherWorld, "field" to field.name)
        if (box.width > 256 || box.depth > 256) return Problem(XPlotsMessages.whyTooWide)
        // Inside its own field's paths is fine; on top of a plot, or in another field, isn't.
        return overlap(world, box, field)
    }

    /** Makes one plot from a box, with its sign outside its south edge, placed as the field's signs are. */
    public fun createPlot(field: Field, box: Box): Plot {
        val sign = field.signPlacement(placement()).place(box, south = true, onPath = true)
        val plot = locked(plugin.lock) {
            Plot(field, field.nextNumber(), box, SignSpot(sign.x, minOf(box.minY + 1, box.maxY), sign.z, sign.facing)).also { plugin.plots.add(it) }
        }
        plugin.store?.savePlot(plot)
        if (!field.layout.area.overlapsColumns(box)) plugin.addons.define("${field.regionId}-${plot.number}", field.world, box)
        plugin.resets.capture(listOf(plot), PlaceAll(plugin, listOf(plot)) {})
        return plot
    }

    /**
     * Puts every sign in the field where its sign settings say, undoing any moved by hand. Grid
     * plots face their walkway; plots made by themselves face south. Returns how many moved.
     */
    public fun placeSigns(field: Field): Int {
        val placement = field.signPlacement(placement())
        val grid = field.layout.spots(placement).associateBy { it.number }
        var moved = 0
        for (plot in field.plots()) {
            val spot = grid[plot.number]
            val target = if (spot != null && spot.box == plot.box) {
                SignSpot(spot.signX, minOf(plot.box.minY + 1, plot.box.maxY), spot.signZ, spot.facing)
            } else {
                val s = placement.place(plot.box, south = true, onPath = true)
                SignSpot(s.x, minOf(plot.box.minY + 1, plot.box.maxY), s.z, s.facing)
            }
            if (target == plot.sign) continue
            moveSign(plot, target)
            moved++
        }
        return moved
    }

    /** Moves (or turns) the plot's sign to [to]. */
    public fun moveSign(plot: Plot, to: SignSpot) {
        val old = plot.sign
        if (old.x != to.x || old.y != to.y || old.z != to.z) plugin.signs.remove(plot, old)
        locked(plugin.lock) { plugin.plots.moveSign(plot, to) }
        plugin.store?.savePlot(plot)
        plugin.signs.place(plot)
    }

    /** Deletes one plot: its rent ends, its sign comes down, its blocks stay. */
    public fun deletePlot(plot: Plot) {
        plugin.signs.remove(plot)
        val field = plot.field
        if (!field.isGrid(plot) && !field.layout.area.overlapsColumns(plot.box)) plugin.addons.removeRegion("${field.regionId}-${plot.number}", field.world)
        locked(plugin.lock) { plugin.plots.remove(plot) }
        plugin.renewals.ended(plot)
        plugin.store?.deletePlot(plot)
        plugin.expiry.reschedule()
    }

    public companion object {
        /** Field names: lowercase letters, digits and _, so plot ids like `box-12` read back. */
        public val NAME: Regex = Regex("[a-z0-9_]{1,24}")

        /** More than this is almost certainly a typo in the plot size. */
        public const val MAX_PLOTS: Int = 4096
    }
}

/** Each sender's selection: two corners in a world, keyed by sender name (a player's, or `CONSOLE`). */
public class Selections {
    public class Selection(public val world: String, public val first: IntArray?, public val second: IntArray?) {
        /** The box between the corners, or `null` until both are set. */
        public val box: Box? get() = if (first == null || second == null) null else Box.of(first[0], first[1], first[2], second[0], second[1], second[2])
    }

    private val selections = concurrentMap<String, Selection>()

    /** Sets corner [which] (1 or 2). A corner in another world starts the selection over. */
    public fun corner(sender: String, which: Int, world: String, x: Int, y: Int, z: Int): Selection {
        val old = selections[sender]?.takeIf { it.world == world }
        val corner = intArrayOf(x, y, z)
        val made = if (which == 1) Selection(world, corner, old?.second) else Selection(world, old?.first, corner)
        selections[sender] = made
        return made
    }

    public operator fun get(sender: String): Selection? = selections[sender]

    public fun forget(sender: String) {
        selections.remove(sender)
    }
}

/**
 * Changes waiting on `/xplots confirm`: a previewed field, or deleting one. One per sender; a new
 * one replaces the old, and each lapses after two minutes.
 */
public class Confirms internal constructor(private val plugin: XPlotsPlugin) {
    /** A change waiting to be confirmed. */
    public interface Action {
        public fun confirm()

        /** It's cancelled, replaced or lapses: stop any preview. */
        public fun dismiss() {}
    }

    private class Waiting(val action: Action, val lapse: Task)

    private val waiting = concurrentMap<String, Waiting>()

    public fun put(sender: String, action: Action) {
        cancel(sender)
        waiting[sender] = Waiting(action, plugin.platform.scheduler.run(Where.Global, delay = 2.minutes, action = Lapse(this, sender)))
    }

    private class Lapse(private val confirms: Confirms, private val sender: String) : (Task) -> Unit {
        override fun invoke(task: Task) {
            confirms.cancel(sender)
        }
    }

    /** Runs the sender's waiting change; `false` if there was none. */
    public fun confirm(sender: String): Boolean {
        val w = waiting.remove(sender) ?: return false
        w.lapse.cancel()
        w.action.dismiss()
        w.action.confirm()
        return true
    }

    /** Drops the sender's waiting change; `false` if there was none. */
    public fun cancel(sender: String): Boolean {
        val w = waiting.remove(sender) ?: return false
        w.lapse.cancel()
        w.action.dismiss()
        return true
    }

    public fun clear() {
        for (sender in waiting.keys.toList()) cancel(sender)
    }
}

/**
 * Shows a field before it's made, to the player making it alone: each plot's outline in white
 * dust, and a lime mark where each sign will go. Only plots within 48 blocks are drawn, twice a
 * second, on the player's thread, until the field is confirmed or cancelled.
 */
internal class Preview(private val plugin: XPlotsPlugin, private val player: PlayerRef, private val world: String, layout: Layout, signs: SignPlacement) : (Task) -> Unit {
    private val spots = layout.spots(signs)
    private val y = layout.area.minY + 1.1
    private val task: Task = plugin.platform.scheduler.run(Where.On(player.uuid), period = 500.milliseconds, action = this)

    fun stop() = task.cancel()

    override fun invoke(task: Task) {
        val eye = plugin.server.position(player) ?: return
        if (eye.world.name != world) return
        for (spot in spots) {
            val b = spot.box
            val cx = (b.minX + b.maxX + 1) / 2.0
            val cz = (b.minZ + b.maxZ + 1) / 2.0
            if (kotlin.math.abs(cx - eye.x) > RANGE || kotlin.math.abs(cz - eye.z) > RANGE) continue
            var x = b.minX.toDouble()
            while (x <= b.maxX + 1) {
                plugin.server.dust(player, x, y, b.minZ.toDouble(), EDGE, 1.5f, 1, 0.0)
                plugin.server.dust(player, x, y, b.maxZ + 1.0, EDGE, 1.5f, 1, 0.0)
                x += 0.5
            }
            var z = b.minZ + 0.5
            while (z < b.maxZ + 1) {
                plugin.server.dust(player, b.minX.toDouble(), y, z, EDGE, 1.5f, 1, 0.0)
                plugin.server.dust(player, b.maxX + 1.0, y, z, EDGE, 1.5f, 1, 0.0)
                z += 0.5
            }
            plugin.server.dust(player, spot.signX + 0.5, spot.signY + 0.5, spot.signZ + 0.5, SIGN, 2.0f, 3, 0.2)
        }
    }

    private companion object {
        const val RANGE = 48.0

        // White, and two to a block, so the outline shows on grass, sand or stone alike.
        const val EDGE = 0xFFFFFF
        const val SIGN = 0xA9D750
    }
}

/** `/plots home` and `/plots warp`: a teleport after standing still for the warmup. */
internal class Teleports(private val plugin: XPlotsPlugin) {
    private class Warmup(val from: Location, val to: Location, val ticks: Int) {
        var waited = 0
        var task: Task? = null
    }

    private val warmups = concurrentMap<PlayerRef, Warmup>()

    /** On the player's thread. */
    fun teleport(player: PlayerRef, to: Location) {
        cancel(player)
        val seconds = plugin.config[XPlotsConfig.teleportWarmup]
        if (seconds <= 0 || plugin.server.hasPermission(player, XPlotsPlugin.WARMUP_BYPASS)) {
            plugin.server.teleport(player, to)
            return
        }
        val from = plugin.server.position(player) ?: return
        plugin.texts.send(player, XPlotsMessages.teleporting, "seconds" to seconds.toString())
        val warmup = Warmup(from, to, seconds * 4)
        warmups[player] = warmup
        warmup.task = plugin.platform.scheduler.run(Where.On(player.uuid), 250.milliseconds, 250.milliseconds, retired = Retired(this, player), action = Step(this, player, warmup))
    }

    private class Step(private val teleports: Teleports, private val player: PlayerRef, private val warmup: Warmup) : (Task) -> Unit {
        override fun invoke(task: Task) = teleports.step(task, player, warmup)
    }

    private class Retired(private val teleports: Teleports, private val player: PlayerRef) : () -> Unit {
        override fun invoke() {
            teleports.warmups.remove(player)
        }
    }

    private fun step(task: Task, player: PlayerRef, warmup: Warmup) {
        if (warmups[player] !== warmup) return task.cancel()
        val now = plugin.server.position(player)
        if (now == null) {
            warmups.remove(player)
            return task.cancel()
        }
        if (now.world.name != warmup.from.world.name || now.blockX != warmup.from.blockX || now.blockY != warmup.from.blockY || now.blockZ != warmup.from.blockZ) {
            warmups.remove(player)
            task.cancel()
            plugin.texts.send(player, XPlotsMessages.teleportCancelled)
            return
        }
        if (++warmup.waited < warmup.ticks) return
        warmups.remove(player)
        task.cancel()
        plugin.server.teleport(player, warmup.to)
    }

    fun cancel(player: PlayerRef) {
        warmups.remove(player)?.task?.cancel()
    }

    fun clear() {
        for (player in warmups.keys.toList()) cancel(player)
    }
}

/**
 * xPlots' placeholders, answered from memory, as 1.x had them: `owned`, `time_left` (the
 * player's soonest-ending plot), `plot` and `plot_owner` (the plot they stand in).
 * PlaceholderAPI reads them as `%xplots_owned%` on Bukkit.
 */
public class Placeholders internal constructor(private val plugin: XPlotsPlugin) {
    @Volatile
    private var made: co.xenastudios.core.common.placeholder.PlaceholderSet? = null

    /** The ones the docs list. */
    public fun set(): co.xenastudios.core.common.placeholder.PlaceholderSet = made ?: build().also { made = it }

    private fun build() = co.xenastudios.core.common.placeholder.placeholders(XPlotsPlugin.PLUGIN_ID) {
        player("owned", "How many plots the player rents.", "1") { resolve("owned", it) }
        player("time_left", "Time left on the player's plot that runs out soonest.", "2d 4h") { resolve("time_left", it) }
        player("plot", "The plot the player is standing in.", "box-12") { resolve("plot", it) }
        player("plot_owner", "Who rents the plot the player is standing in.", "Steve") { resolve("plot_owner", it) }
    }

    /** The value of [name] (without `xplots_`) for [player], or `null` if there's no such placeholder. */
    public fun resolve(name: String, player: PlayerRef?): String? {
        if (name !in NAMES) return null
        if (player == null) return ""
        return try {
            when (name) {
                "owned" -> plugin.plots.owned(player).size.toString()
                "time_left" -> plugin.plots.owned(player).mapNotNull { it.lease?.expires }.minOrNull()?.let { plugin.rentals.time(maxOf(0L, it - plugin.now())) } ?: ""
                "plot" -> here(player)?.id ?: ""
                else -> here(player)?.lease?.ownerName ?: ""
            }
        } catch (e: RuntimeException) {
            ""
        }
    }

    private fun here(player: PlayerRef): Plot? {
        val at = plugin.server.position(player) ?: return null
        val plot = plugin.plots.at(at.world.name, at.blockX, at.blockY, at.blockZ)
        return if (plot === Field.PATH) null else plot
    }

    private companion object {
        val NAMES = setOf("owned", "time_left", "plot", "plot_owner")
    }
}
