package co.xenastudios.xplots.model

import co.xenastudios.xplots.api.PlotBox

/** A box of blocks, corners included. */
public class Box(
    public val minX: Int,
    public val minY: Int,
    public val minZ: Int,
    public val maxX: Int,
    public val maxY: Int,
    public val maxZ: Int,
) {
    public fun contains(x: Int, y: Int, z: Int): Boolean = containsColumn(x, z) && y in minY..maxY

    /** Whether the column at [x], [z] is in the box, at any height. */
    public fun containsColumn(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ

    /** Whether the two boxes share a column. */
    public fun overlapsColumns(other: Box): Boolean = minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ

    public val width: Int get() = maxX - minX + 1
    public val height: Int get() = maxY - minY + 1
    public val depth: Int get() = maxZ - minZ + 1
    public val volume: Long get() = width.toLong() * height * depth

    /** `5×5`, or `5×7`: the size players see. */
    public val size: String get() = "$width×$depth"

    /** The smallest box holding this and [other]. */
    public fun union(other: Box): Box = Box(
        minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ),
        maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ),
    )

    public fun info(): PlotBox = PlotBox(minX, minY, minZ, maxX, maxY, maxZ)

    override fun equals(other: Any?): Boolean = other is Box && other.minX == minX && other.minY == minY && other.minZ == minZ &&
        other.maxX == maxX && other.maxY == maxY && other.maxZ == maxZ

    override fun hashCode(): Int = ((((minX * 31 + minY) * 31 + minZ) * 31 + maxX) * 31 + maxY) * 31 + maxZ

    override fun toString(): String = "$minX $minY $minZ to $maxX $maxY $maxZ"

    public companion object {
        /** The box with these two corners, in any order. */
        public fun of(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Box =
            Box(minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))
    }
}

/**
 * The 16 ways a standing sign can face, clockwise from north, as Bukkit's `BlockFace` names
 * them (1.x stored them by those names): index 0 is `NORTH`, 4 `EAST`, 8 `SOUTH`, 12 `WEST`.
 */
public object Facing {
    public val NAMES: List<String> = listOf(
        "NORTH", "NORTH_NORTH_EAST", "NORTH_EAST", "EAST_NORTH_EAST",
        "EAST", "EAST_SOUTH_EAST", "SOUTH_EAST", "SOUTH_SOUTH_EAST",
        "SOUTH", "SOUTH_SOUTH_WEST", "SOUTH_WEST", "WEST_SOUTH_WEST",
        "WEST", "WEST_NORTH_WEST", "NORTH_WEST", "NORTH_NORTH_WEST",
    )

    public const val NORTH: Int = 0
    public const val NORTH_EAST: Int = 2
    public const val EAST: Int = 4
    public const val SOUTH_EAST: Int = 6
    public const val SOUTH: Int = 8
    public const val SOUTH_WEST: Int = 10
    public const val WEST: Int = 12
    public const val NORTH_WEST: Int = 14

    /** The facing stored as [name]; south, as 1.x read one it didn't know. */
    public fun of(name: String?): Int = NAMES.indexOf(name).let { if (it < 0) SOUTH else it }

    public fun name(facing: Int): String = NAMES[facing.mod(16)]

    /** `north-north-east`, as players read it. */
    public fun shown(facing: Int): String = name(facing).lowercase().replace('_', '-')

    /** 45° clockwise. */
    public fun turn(facing: Int): Int = (facing + 2).mod(16)

    /** The facing that looks back at a player whose view has this yaw, to the nearest 22.5°. */
    public fun toward(yaw: Float): Int {
        // Yaw 0 looks south; a sign facing the player then faces north, index 0.
        val steps = kotlin.math.floor(yaw / 22.5f + 0.5f).toInt()
        return steps.mod(16)
    }

    /** One step in x for this facing: -1, 0 or 1. */
    public fun stepX(facing: Int): Int = when (facing.mod(16)) {
        in 1..7 -> 1
        in 9..15 -> -1
        else -> 0
    }

    /** One step in z for this facing: -1, 0 or 1. North is -z. */
    public fun stepZ(facing: Int): Int = when (facing.mod(16)) {
        in 5..11 -> 1
        0, 1, 2, 3, 13, 14, 15 -> -1
        else -> 0
    }

    /** Whether it's one of the eight faces a 45° turn reaches (not one of the in-between 22.5° ones). */
    public fun isMain(facing: Int): Boolean = facing % 2 == 0
}

/**
 * Where on its plot's front edge a sign stands, and which way it faces. "Left" and "right" are
 * as seen from the walkway, looking at the plot.
 */
public class SignPlacement(public val position: Position, public val rotation: Rotation) {
    public enum class Position { MIDDLE, LEFT_CORNER, RIGHT_CORNER }

    /** [STRAIGHT] faces the walkway; [DIAGONAL] is turned 45° out from its corner. */
    public enum class Rotation { STRAIGHT, DIAGONAL }

    /** A sign's column and facing. */
    public class Spot(public val x: Int, public val z: Int, public val facing: Int)

    /**
     * Where the sign goes for a plot whose front faces south ([south]) or north: just outside
     * the plot on the walkway when [onPath], or just inside its edge when the walkway is too narrow.
     */
    public fun place(box: Box, south: Boolean, onPath: Boolean): Spot {
        val z = if (south) (if (onPath) box.maxZ + 1 else box.maxZ) else (if (onPath) box.minZ - 1 else box.minZ)
        // Facing the plot from its walkway, left is west for a south-facing front, east for a north-facing one.
        val left = if (south) box.minX else box.maxX
        val right = if (south) box.maxX else box.minX
        val x = when (position) {
            Position.MIDDLE -> box.minX + (box.width - 1) / 2
            Position.LEFT_CORNER -> left
            Position.RIGHT_CORNER -> right
        }
        var facing = if (south) Facing.SOUTH else Facing.NORTH
        if (rotation == Rotation.DIAGONAL && position != Position.MIDDLE) {
            val towardWest = if (position == Position.LEFT_CORNER) south else !south
            facing = if (south) (if (towardWest) Facing.SOUTH_WEST else Facing.SOUTH_EAST) else (if (towardWest) Facing.NORTH_WEST else Facing.NORTH_EAST)
        }
        return Spot(x, z, facing)
    }

    override fun equals(other: Any?): Boolean = other is SignPlacement && other.position == position && other.rotation == rotation

    override fun hashCode(): Int = position.hashCode() * 31 + rotation.hashCode()

    public companion object {
        /** The defaults with a field's own `sign-position` and `sign-rotation` on top. */
        public fun of(defaults: SignPlacement, overrides: Map<String, String>): SignPlacement = SignPlacement(
            parse(Position.entries, overrides["sign-position"]) ?: defaults.position,
            parse(Rotation.entries, overrides["sign-rotation"]) ?: defaults.rotation,
        )

        /** `left-corner`, as written in the config, or `null` if it isn't one. */
        public fun <E : Enum<E>> parse(values: List<E>, value: String?): E? {
            if (value == null) return null
            val wanted = value.trim().replace('-', '_').uppercase()
            return values.firstOrNull { it.name == wanted }
        }

        public fun name(value: Enum<*>): String = value.name.lowercase().replace('_', '-')
    }
}

/**
 * How a selection splits into a grid of plots with paths between them, and where each plot's
 * sign goes. Columns run along x and rows along z; the grid is centred in the selection, and
 * whatever's left over at the edges is path. Rows face each other in pairs across a path, and
 * each plot's sign stands on that path facing away from the plot. A path narrower than two
 * blocks has no room for two rows' signs, so then each stands just inside its plot's edge.
 */
public class Layout private constructor(
    public val area: Box,
    public val sizeX: Int,
    public val sizeZ: Int,
    public val path: Int,
    public val originX: Int,
    public val originZ: Int,
    public val cols: Int,
    public val rows: Int,
) {
    /** A plot as the layout places it, before it's made. */
    public class Spot(public val number: Int, public val box: Box, public val signX: Int, public val signY: Int, public val signZ: Int, public val facing: Int)

    public val count: Int get() = cols * rows

    /** The plot number at a column, numbered from 1 across each row, or 0 for a path. Pure arithmetic. */
    public fun numberAt(x: Int, z: Int): Int {
        val dx = x - originX
        val dz = z - originZ
        if (dx < 0 || dz < 0) return 0
        val col = dx / (sizeX + path)
        val row = dz / (sizeZ + path)
        if (col >= cols || row >= rows || dx % (sizeX + path) >= sizeX || dz % (sizeZ + path) >= sizeZ) return 0
        return row * cols + col + 1
    }

    /** Every plot, in number order, with signs placed as [signs] says. */
    public fun spots(signs: SignPlacement): List<Spot> {
        val out = ArrayList<Spot>(count)
        val onPath = path >= 2
        for (row in 0 until rows) {
            // Even rows face the next row, odd rows the one before; a last row on its own faces back toward the rest.
            val south = row % 2 == 0 && (row + 1 < rows || row == 0)
            for (col in 0 until cols) {
                val minX = originX + col * (sizeX + path)
                val minZ = originZ + row * (sizeZ + path)
                val box = Box(minX, area.minY, minZ, minX + sizeX - 1, area.maxY, minZ + sizeZ - 1)
                val sign = signs.place(box, south, onPath)
                val signY = minOf(area.minY + 1, area.maxY)
                out += Spot(row * cols + col + 1, box, sign.x, signY, sign.z, sign.facing)
            }
        }
        return out
    }

    public companion object {
        /** Splits the selection, or `null` if not even one plot fits. */
        public fun of(area: Box, sizeX: Int, sizeZ: Int, path: Int): Layout? {
            if (sizeX < 1 || sizeZ < 1 || path < 0) return null
            val cols = (area.width + path) / (sizeX + path)
            val rows = (area.depth + path) / (sizeZ + path)
            if (cols < 1 || rows < 1) return null
            val usedX = cols * (sizeX + path) - path
            val usedZ = rows * (sizeZ + path) - path
            return Layout(area, sizeX, sizeZ, path, area.minX + (area.width - usedX) / 2, area.minZ + (area.depth - usedZ) / 2, cols, rows)
        }
    }
}
