package co.xenastudios.core.api

import kotlin.jvm.JvmOverloads
import kotlin.math.floor

/**
 * A place in a [world], and which way something there faces. Immutable, so it's safe to keep
 * and to pass between threads; equal when every part is.
 *
 * Its [chunkX] and [chunkZ] are what Folia's regions are made of, so the core's scheduler can
 * run work on the thread that owns this place.
 */
public class Location @JvmOverloads constructor(
    /** The world it's in. */
    public val world: WorldRef,
    /** East (+) and west (−). */
    public val x: Double,
    /** Up (+) and down (−). */
    public val y: Double,
    /** South (+) and north (−). */
    public val z: Double,
    /** Which way it faces around, in degrees: 0 is south, 90 west. */
    public val yaw: Float = 0f,
    /** Which way it faces up or down, in degrees: −90 is straight up, 90 straight down. */
    public val pitch: Float = 0f,
) {
    /** The block's x: [x] rounded down. */
    public val blockX: Int get() = floor(x).toInt()

    /** The block's y: [y] rounded down. */
    public val blockY: Int get() = floor(y).toInt()

    /** The block's z: [z] rounded down. */
    public val blockZ: Int get() = floor(z).toInt()

    /** The chunk's x: 16 blocks to a chunk. */
    public val chunkX: Int get() = blockX shr 4

    /** The chunk's z. */
    public val chunkZ: Int get() = blockZ shr 4

    /** The same place facing [yaw] and [pitch]. */
    public fun facing(yaw: Float, pitch: Float): Location = Location(world, x, y, z, yaw, pitch)

    /**
     * The square of the distance to [other], or [Double.POSITIVE_INFINITY] in another world.
     * Squared, so a range check needs no square root.
     */
    public fun distanceSquared(other: Location): Double {
        if (other.world != world) return Double.POSITIVE_INFINITY
        val dx = other.x - x
        val dy = other.y - y
        val dz = other.z - z
        return dx * dx + dy * dy + dz * dz
    }

    override fun equals(other: Any?): Boolean = other is Location && other.world == world &&
        other.x == x && other.y == y && other.z == z && other.yaw == yaw && other.pitch == pitch

    override fun hashCode(): Int {
        var result = world.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        result = 31 * result + yaw.hashCode()
        result = 31 * result + pitch.hashCode()
        return result
    }

    override fun toString(): String = "$world $x $y $z $yaw $pitch"
}
