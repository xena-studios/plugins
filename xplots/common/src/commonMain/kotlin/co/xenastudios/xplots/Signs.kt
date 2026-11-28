package co.xenastudios.xplots

import co.xenastudios.core.api.Location
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.Plots
import co.xenastudios.xplots.model.SignSpot
import kotlin.concurrent.Volatile

/**
 * Plot signs. A sign is only written when what it shows has changed, and only while its chunk is
 * loaded, on the thread that owns it (its region's on Folia); a chunk that loads gets its signs
 * brought up to date. Once a minute the signs in loaded chunks are checked (rented plots' time
 * left changes, and a sign that's gone missing is put back), one task per chunk.
 */
public class Signs internal constructor(private val plugin: XPlotsPlugin) {
    // Plots whose sign is waiting to be written, so a burst of changes writes it once.
    private val queued = concurrentMap<Plot, Boolean>()

    // Bumped when the lines change (a reload), so every sign is rewritten once.
    @Volatile
    private var generation = 0

    @Volatile
    private var linesFrom: Translations? = null

    private fun location(plot: Plot, spot: SignSpot = plot.sign): Location = Location(plot.field.worldRef, spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble())

    /** Brings the plot's sign up to date, putting it back if it's gone, if its chunk is loaded. Any thread. */
    public fun update(plot: Plot) {
        val spot = plot.sign
        if (!plugin.server.isLoaded(plot.field.world, spot.x shr 4, spot.z shr 4)) return
        if (queued.put(plot, true) != null) return
        plugin.platform.scheduler.run(Where.At(location(plot)), action = Write(this, plot, false))
    }

    /** Places the plot's sign where it belongs, facing its path, and fills it in; in a chunk that isn't loaded, as the chunk loads. */
    public fun place(plot: Plot) {
        plot.shown = null
        val spot = plot.sign
        if (!plugin.server.isLoaded(plot.field.world, spot.x shr 4, spot.z shr 4)) return
        plugin.platform.scheduler.run(Where.At(location(plot)), action = Write(this, plot, true))
    }

    /** Takes the sign at [spot] down, as when a plot is deleted or its sign moves. */
    public fun remove(plot: Plot, spot: SignSpot = plot.sign) {
        val world = plot.field.world
        if (!plugin.server.hasWorld(world)) return
        plugin.platform.scheduler.run(Where.At(location(plot, spot)), action = Remove(plugin, world, spot))
    }

    private class Write(private val signs: Signs, private val plot: Plot, private val fresh: Boolean) : (Task) -> Unit {
        override fun invoke(task: Task) {
            signs.queued.remove(plot)
            signs.write(plot, fresh)
        }
    }

    private class Remove(private val plugin: XPlotsPlugin, private val world: String, private val spot: SignSpot) : (Task) -> Unit {
        override fun invoke(task: Task) {
            if (plugin.server.isSign(world, spot.x, spot.y, spot.z)) plugin.server.removeSign(world, spot.x, spot.y, spot.z)
        }
    }

    /** On the sign's thread: places it if it's missing (or [fresh]), and writes it if what it shows changed. */
    internal fun write(plot: Plot, fresh: Boolean) {
        if (plugin.plots.plot(plot.id) !== plot) return
        val world = plot.field.world
        val spot = plot.sign
        if (!plugin.server.hasWorld(world) || !plugin.server.isLoaded(world, spot.x shr 4, spot.z shr 4)) return
        val config = plugin.config
        val missing = !plugin.server.isSign(world, spot.x, spot.y, spot.z)
        if (missing || fresh) plugin.server.placeSign(world, spot.x, spot.y, spot.z, config[XPlotsConfig.signs.material], spot.facing)
        val translations = plugin.texts.translations
        if (translations !== linesFrom) {
            linesFrom = translations
            generation++
        }
        val now = plugin.now()
        val lease = plot.lease
        val message = when {
            lease == null -> XPlotsMessages.signFree
            lease.remaining(now) < config[XPlotsConfig.signs.expiringBelow].inWholeMilliseconds -> XPlotsMessages.signExpiring
            else -> XPlotsMessages.signRented
        }
        val placeholders = plugin.rentals.placeholders(plot, null)
        val key = "$generation|${message.key}|" + placeholders.joinToString("|") { it.second.toString() }
        if (!missing && !fresh && key == plot.shown) return
        val rendered = plugin.texts.render(null, message, *placeholders) ?: Component.EMPTY
        val lines = splitLines(rendered).take(4)
        if (plugin.server.writeSign(world, spot.x, spot.y, spot.z, lines)) plot.shown = key
    }

    /** The once-a-minute check: every sign in a loaded chunk, rewritten only where it changed, one task per chunk. Any thread. */
    public fun refreshAll() {
        val chunks = HashMap<Pair<String, Long>, MutableList<Plot>>()
        for (field in plugin.plots.fields()) {
            if (!plugin.server.hasWorld(field.world)) continue
            for (plot in field.plots()) {
                val spot = plot.sign
                if (!plugin.server.isLoaded(field.world, spot.x shr 4, spot.z shr 4)) continue
                chunks.getOrPut(field.world to Plots.chunkKey(spot.x shr 4, spot.z shr 4)) { ArrayList() } += plot
            }
        }
        for (plots in chunks.values) plugin.platform.scheduler.run(Where.At(location(plots[0])), action = Chunk(this, plots))
    }

    /** A chunk loaded: its signs may be out of date. Written on the next tick, once it's done loading. */
    public fun chunkLoaded(world: String, chunkX: Int, chunkZ: Int) {
        val plots = plugin.plots.signsInChunk(world, chunkX, chunkZ)
        if (plots.isEmpty()) return
        plugin.platform.scheduler.run(Where.At(location(plots[0])), action = Chunk(this, plots.toList()))
    }

    private class Chunk(private val signs: Signs, private val plots: List<Plot>) : (Task) -> Unit {
        override fun invoke(task: Task) {
            for (plot in plots) signs.write(plot, false)
        }
    }
}

/** [component]'s lines: split where its text has a newline, each line keeping its styles. */
public fun splitLines(component: Component): List<Component> {
    val lines = ArrayList<MutableList<Component>>()
    lines.add(ArrayList())
    fun walk(node: Component, style: co.xenastudios.core.common.text.Style) {
        val own = style.merge(node.style)
        val parts = node.text.split('\n')
        for ((i, part) in parts.withIndex()) {
            if (i > 0) lines.add(ArrayList())
            if (part.isNotEmpty()) lines.last() += Component(part, own)
        }
        for (child in node.children) walk(child, own)
    }
    walk(component, co.xenastudios.core.common.text.Style.EMPTY)
    return lines.map { parts -> if (parts.size == 1) parts[0] else Component("", children = parts) }
}
