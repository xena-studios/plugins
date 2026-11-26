package co.xenastudios.xteleport.teleport

import co.xenastudios.core.api.Location
import co.xenastudios.xteleport.BlockKind
import co.xenastudios.xteleport.BlockKind.has
import co.xenastudios.xteleport.Blocks
import co.xenastudios.xteleport.WorldInfo
import kotlin.math.floor

/**
 * Whether a spot is safe to arrive at, and where `/rtp` may land: the same rules on every
 * platform, read through [Blocks] on the thread that owns the spot, once its chunk has loaded.
 */
public object Safety {

    /**
     * Whether a loaded spot is safe to stand at: inside the world border, nothing harmful at the
     * feet or head, neither inside a full block, and ground that isn't harmful at most [maxFall]
     * blocks below, or water at any depth below. A ladder, vine or scaffolding on the way down
     * counts as ground. 1.x's rule, unchanged.
     */
    public fun arrival(blocks: Blocks, world: WorldInfo, at: Location, maxFall: Int): Boolean {
        val x = at.blockX
        val y = at.blockY
        val z = at.blockZ
        if (y <= world.minY || y >= world.maxY || !world.insideBorder(at.x, at.z)) return false
        val feet = blocks.kind(x, y, z)
        val head = blocks.kind(x, y + 1, z)
        if (has(feet, BlockKind.HURTS) || has(head, BlockKind.HURTS) || inside(feet, at.y - y) || has(head, BlockKind.FULL)) return false
        // From just under the feet, so a spot on a slab (y = 64.5) finds the slab itself.
        val top = floor(at.y - 0.01).toInt()
        var below = top
        while (below >= world.minY) {
            val kind = blocks.kind(x, below, z)
            if (has(kind, BlockKind.HURTS)) return false
            // Water, kelp or anything waterlogged: lava is HURTS.
            if (has(kind, BlockKind.WATER)) return true
            if (has(kind, BlockKind.SOLID) || has(kind, BlockKind.CLIMBABLE)) return top - below <= maxFall
            below--
        }
        return false
    }

    // Whether the feet are inside the block they're in: a full block, below its top.
    private fun inside(feet: Int, above: Double): Boolean {
        if (!has(feet, BlockKind.FULL)) return false
        val height = when {
            has(feet, BlockKind.SHORT) -> 0.875
            has(feet, BlockKind.SHORTER) -> 0.9375
            else -> 1.0
        }
        return above < height - 0.001
    }

    /** Ground nobody should land on with `/rtp`: what hurts, water, leaves and bedrock. */
    private const val BAD_GROUND = BlockKind.HURTS or BlockKind.WATER or BlockKind.LEAVES or BlockKind.BEDROCK or BlockKind.LIQUID

    /**
     * The y of the block to stand on in this column for `/rtp`, or `null` if there's none that's
     * safe: the top block (below the roof in a world with one), solid and not bad ground, with two
     * blocks of room above it. The biome isn't checked here.
     */
    public fun rtpGround(blocks: Blocks, world: WorldInfo, x: Int, z: Int): Int? {
        val ground = if (world.hasCeiling) floorUnderRoof(blocks, world, x, z) else blocks.highest(x, z).takeIf { it >= world.minY }
        if (ground == null || !goodGround(blocks.kind(x, ground, z))) return null
        if (!room(blocks.kind(x, ground + 1, z)) || !room(blocks.kind(x, ground + 2, z))) return null
        return ground
    }

    // The Nether: the highest good floor with two blocks of room above it, below the roof.
    private fun floorUnderRoof(blocks: Blocks, world: WorldInfo, x: Int, z: Int): Int? {
        val top = minOf(world.logicalHeight, world.maxY) - 2
        var room = 0
        var y = top
        while (y > world.minY) {
            val kind = blocks.kind(x, y, z)
            if (room(kind)) {
                room++
            } else {
                if (room >= 2 && goodGround(kind)) return y
                room = 0
            }
            y--
        }
        return null
    }

    private fun goodGround(kind: Int): Boolean = has(kind, BlockKind.SOLID) && kind and BAD_GROUND == 0

    private fun room(kind: Int): Boolean = !has(kind, BlockKind.SOLID) && kind and BAD_GROUND == 0
}
