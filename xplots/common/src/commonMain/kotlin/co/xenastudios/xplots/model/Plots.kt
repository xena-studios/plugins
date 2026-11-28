package co.xenastudios.xplots.model

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.xplots.api.FieldInfo
import co.xenastudios.xplots.api.PlotInfo
import kotlin.concurrent.Volatile

/**
 * A plot's rental: who, until when, and who else may build. Never changed: a change is a new
 * lease, so a protection check on another thread (a Folia region's) reads one whole lease.
 */
public class Lease(
    public val owner: PlayerRef,
    /** Their name as last seen. */
    public val ownerName: String,
    /** When it runs out, in milliseconds since 1970. */
    public val expires: Long,
    /** How many members it may have, besides the owner. */
    public val slots: Int,
    /** Warnings sent for it: [WARNED_NONE], [WARNED_DAY] or [WARNED_HOUR]. */
    public val warned: Int,
    /** Members and their names as last seen, in the order they were added. */
    public val members: Map<PlayerRef, String>,
) {
    public fun remaining(now: Long): Long = maxOf(0L, expires - now)

    /** Whether [player] may build here: the owner or a member. */
    public fun trusts(player: PlayerRef): Boolean = owner == player || player in members

    public fun with(
        ownerName: String = this.ownerName,
        expires: Long = this.expires,
        slots: Int = this.slots,
        warned: Int = this.warned,
        members: Map<PlayerRef, String> = this.members,
    ): Lease = Lease(owner, ownerName, expires, slots, warned, members)

    public companion object {
        public const val WARNED_NONE: Int = 0
        public const val WARNED_DAY: Int = 1
        public const val WARNED_HOUR: Int = 2

        public const val HOUR: Long = 3_600_000L
        public const val DAY: Long = 86_400_000L

        /** Which warning is due with this much left: the hour's, the day's, or none. */
        public fun warningFor(remaining: Long): Int = when {
            remaining <= HOUR -> WARNED_HOUR
            remaining <= DAY -> WARNED_DAY
            else -> WARNED_NONE
        }
    }
}

/** Where a plot's sign stands, and which way its text faces ([Facing]). */
public class SignSpot(public val x: Int, public val y: Int, public val z: Int, public val facing: Int) {
    override fun equals(other: Any?): Boolean = other is SignSpot && other.x == x && other.y == y && other.z == z && other.facing == facing

    override fun hashCode(): Int = ((x * 31 + y) * 31 + z) * 31 + facing
}

/** One plot: where it is, where its sign is, and who rents it. */
public class Plot(public val field: Field, public val number: Int, public val box: Box, sign: SignSpot) {
    @Volatile
    public var sign: SignSpot = sign
        internal set

    /** The rental, or `null` if the plot is free. Set through [Plots.lease]. */
    @Volatile
    public var lease: Lease? = null
        internal set

    /** What its sign last showed, so the sign is only rewritten when that changes. */
    @Volatile
    public var shown: String? = null

    /** `box-12`: the field's name and the plot's number. */
    public val id: String get() = "${this.field.name}-$number"

    public val isFree: Boolean get() = lease == null

    override fun toString(): String = id
}

/**
 * A group of plots that share settings: a grid split from one selection, plus any plots made one
 * at a time. Its whole footprint is protected, at every height; plots can be built in only
 * between the selection's lowest and highest blocks. Changed under the plugin's lock, read from
 * any thread: each change replaces what it changes.
 */
public class Field(public val id: Int, public val name: String, public val world: String, public val layout: Layout) {
    @Volatile
    private var grid: Array<Plot?> = arrayOfNulls(layout.count)

    @Volatile
    private var custom: List<Plot> = emptyList()

    /** The settings this field changes from the config's, by key, such as `price`. */
    @Volatile
    public var overrides: Map<String, String> = emptyMap()
        internal set

    /** Where `/plots warp` takes players, as `x,y,z,yaw,pitch`, or `null` for none. */
    @Volatile
    public var warp: String? = null

    /** Every column the field covers: its grid's selection and any plots made one at a time. */
    @Volatile
    public var bounds: Box = layout.area
        private set

    public val worldRef: WorldRef get() = WorldRef.of(world)

    /** The plot at a block, [PATH] for the rest of the field's footprint, or `null` outside it. */
    public fun locate(x: Int, y: Int, z: Int): Plot? {
        if (!bounds.containsColumn(x, z)) return null
        val others = custom
        for (i in others.indices) {
            val plot = others[i]
            if (plot.box.containsColumn(x, z)) return if (y in plot.box.minY..plot.box.maxY) plot else PATH
        }
        if (!layout.area.containsColumn(x, z)) return null
        val number = layout.numberAt(x, z)
        val cells = grid
        val plot = if (number == 0) null else cells[number - 1]
        if (plot == null || y < plot.box.minY || y > plot.box.maxY) return PATH
        return plot
    }

    /** Adds a plot: a grid plot by its number, or one made by itself. Under the plugin's lock. */
    internal fun add(plot: Plot) {
        if (plot.number <= grid.size) {
            grid = grid.copyOf().also { it[plot.number - 1] = plot }
        } else {
            custom = custom + plot
            bounds = bounds.union(plot.box)
        }
    }

    internal fun remove(plot: Plot) {
        if (plot.number <= grid.size && grid[plot.number - 1] === plot) {
            grid = grid.copyOf().also { it[plot.number - 1] = null }
        } else {
            custom = custom - plot
        }
    }

    /** Every plot, grid first, in number order. */
    public fun plots(): List<Plot> {
        val cells = grid
        val out = ArrayList<Plot>(cells.size + custom.size)
        for (plot in cells) if (plot != null) out += plot
        out += custom
        return out
    }

    /** The plot numbered [number], or `null`. */
    public fun plot(number: Int): Plot? {
        val cells = grid
        if (number in 1..cells.size) return cells[number - 1]
        return custom.firstOrNull { it.number == number }
    }

    /** The number the next plot made by itself gets. */
    public fun nextNumber(): Int = maxOf(grid.size, custom.maxOfOrNull { it.number } ?: 0) + 1

    /** Whether [plot] is one of the grid's, rather than one made by itself. */
    public fun isGrid(plot: Plot): Boolean = plot.number <= grid.size

    /** The settings, the config's ([defaults]) with this field's own on top. */
    public fun settings(defaults: PlotSettings): PlotSettings {
        val cached = settingsCache
        if (cached != null && cached.first === defaults && cached.second === overrides) return cached.third
        val made = Settings.apply(defaults, overrides)
        settingsCache = Triple(defaults, overrides, made)
        return made
    }

    @Volatile
    private var settingsCache: Triple<PlotSettings, Map<String, String>, PlotSettings>? = null

    /** Where this field's signs go: the config's, with the field's own sign settings on top. */
    public fun signPlacement(defaults: SignPlacement): SignPlacement = SignPlacement.of(defaults, overrides)

    /** The WorldGuard region xPlots made for this field: 1.x's name. */
    public val regionId: String get() = "xplots-$name"

    override fun toString(): String = name

    public companion object {
        private val NOWHERE = Field(0, "", "", Layout.of(Box(0, 0, 0, 0, 0, 0), 1, 1, 0)!!)

        /** What [locate] returns for a protected spot that's no plot: a path, or above or below one. */
        public val PATH: Plot = Plot(NOWHERE, 0, Box(0, 0, 0, 0, 0, 0), SignSpot(0, 0, 0, Facing.SOUTH))
    }
}

/**
 * Every field and plot, held in memory and indexed so the questions events ask are answered
 * without a search: which plot is this block in, is this block a plot's sign, which plots does
 * this player rent. Changed under the plugin's lock; read from any thread, each world's index
 * replaced whole as it changes.
 */
public class Plots {
    /** One world's fields, with the box around all of them, so most events stop at one check. */
    private class WorldIndex(val fields: List<Field>, val signs: Map<Long, Plot>, val signChunks: Map<Long, List<Plot>>) {
        val minX: Int = fields.minOfOrNull { it.bounds.minX } ?: Int.MAX_VALUE
        val minZ: Int = fields.minOfOrNull { it.bounds.minZ } ?: Int.MAX_VALUE
        val maxX: Int = fields.maxOfOrNull { it.bounds.maxX } ?: Int.MIN_VALUE
        val maxZ: Int = fields.maxOfOrNull { it.bounds.maxZ } ?: Int.MIN_VALUE
    }

    @Volatile
    private var byName: Map<String, Field> = emptyMap()

    @Volatile
    private var worlds: Map<String, WorldIndex> = emptyMap()

    private val owned = concurrentMap<PlayerRef, List<Plot>>()

    public val isEmpty: Boolean get() = byName.isEmpty()

    public fun fields(): Collection<Field> = byName.values

    public fun field(name: String): Field? = byName[name.lowercase()]

    /** `box-12`, or `null`. */
    public fun plot(id: String): Plot? {
        val dash = id.lastIndexOf('-')
        if (dash <= 0) return null
        val field = field(id.substring(0, dash)) ?: return null
        val number = id.substring(dash + 1).toIntOrNull() ?: return null
        return field.plot(number)
    }

    /** Every plot's id, for completion. */
    public fun plotIds(): List<String> = byName.values.flatMap { f -> f.plots().map { it.id } }

    /** The highest field id, so new fields get the next one. */
    public fun maxFieldId(): Int = byName.values.maxOfOrNull { it.id } ?: 0

    /** Adds a new field with the plots it has. Under the plugin's lock. */
    public fun index(field: Field) {
        byName = byName + (field.name to field)
        field.plots().forEach(::indexOwner)
        rebuild(field.world)
    }

    /** Adds a plot to a field already indexed. */
    public fun add(plot: Plot) {
        plot.field.add(plot)
        indexOwner(plot)
        rebuild(plot.field.world)
    }

    public fun remove(field: Field) {
        byName = byName - field.name
        field.plots().forEach(::unindexOwner)
        rebuild(field.world)
    }

    /** Takes one plot out of its field and the index. */
    public fun remove(plot: Plot) {
        unindexOwner(plot)
        plot.field.remove(plot)
        rebuild(plot.field.world)
    }

    public fun moveSign(plot: Plot, sign: SignSpot) {
        plot.sign = sign
        plot.shown = null
        rebuild(plot.field.world)
    }

    /** Gives the plot a lease, or takes it away with `null`, keeping the owner index right. */
    public fun lease(plot: Plot, lease: Lease?) {
        unindexOwner(plot)
        plot.lease = lease
        indexOwner(plot)
    }

    /** The plot at a block, [Field.PATH] for the rest of a field, or `null` outside every field. */
    public fun at(world: String, x: Int, y: Int, z: Int): Plot? {
        val index = worlds[world] ?: return null
        if (x < index.minX || x > index.maxX || z < index.minZ || z > index.maxZ) return null
        val list = index.fields
        for (i in list.indices) {
            val plot = list[i].locate(x, y, z)
            if (plot != null) return plot
        }
        return null
    }

    /** The plot whose sign is at this block, or `null`. */
    public fun signAt(world: String, x: Int, y: Int, z: Int): Plot? = worlds[world]?.signs?.get(blockKey(x, y, z))

    /** Plots whose signs are in this chunk. */
    public fun signsInChunk(world: String, chunkX: Int, chunkZ: Int): List<Plot> = worlds[world]?.signChunks?.get(chunkKey(chunkX, chunkZ)) ?: emptyList()

    /** Whether the world has any fields at all. */
    public fun hasFields(world: String): Boolean = world in worlds

    /** The plots [player] rents. */
    public fun owned(player: PlayerRef): List<Plot> = owned[player] ?: emptyList()

    /** Every rented plot. */
    public fun rented(): List<Plot> = owned.values.flatten()

    private fun rebuild(world: String) {
        val fields = byName.values.filter { it.world == world }.sortedBy { it.id }
        if (fields.isEmpty()) {
            worlds = worlds - world
            return
        }
        val signs = HashMap<Long, Plot>()
        val chunks = HashMap<Long, MutableList<Plot>>()
        for (field in fields) {
            for (plot in field.plots()) {
                val s = plot.sign
                signs[blockKey(s.x, s.y, s.z)] = plot
                chunks.getOrPut(chunkKey(s.x shr 4, s.z shr 4)) { ArrayList() } += plot
            }
        }
        worlds = worlds + (world to WorldIndex(fields, signs, chunks))
    }

    private fun indexOwner(plot: Plot) {
        val owner = plot.lease?.owner ?: return
        owned[owner] = (owned[owner] ?: emptyList()) + plot
    }

    private fun unindexOwner(plot: Plot) {
        val owner = plot.lease?.owner ?: return
        val left = (owned[owner] ?: return) - plot
        if (left.isEmpty()) owned.remove(owner) else owned[owner] = left
    }

    public companion object {
        /** Packs a block position into one number: 26 bits each for x and z, 12 for y. */
        public fun blockKey(x: Int, y: Int, z: Int): Long = (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)

        public fun chunkKey(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
    }
}

/** A copy of [plot] for the API and events, as it is now. */
public fun info(plot: Plot, resetting: Boolean): PlotInfo {
    val lease = plot.lease
    return PlotInfo(
        plot.id, plot.field.name, plot.number, plot.field.worldRef, plot.box.info(),
        lease?.owner, lease?.ownerName, lease?.expires ?: 0L, lease?.members ?: emptyMap(), lease?.slots ?: 0, resetting,
    )
}

/** A copy of [field] for the API. */
public fun info(field: Field): FieldInfo {
    val plots = field.plots()
    return FieldInfo(field.name, field.worldRef, field.layout.area.info(), plots.map { it.id }, plots.count { !it.isFree }, field.warp != null)
}
