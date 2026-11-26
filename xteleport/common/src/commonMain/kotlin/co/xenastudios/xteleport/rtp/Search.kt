package co.xenastudios.xteleport.rtp

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xteleport.Blocks
import co.xenastudios.xteleport.TeleportServer
import co.xenastudios.xteleport.WorldInfo
import co.xenastudios.xteleport.teleport.Safety
import co.xenastudios.core.common.scheduler.Scheduler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Finds a safe random spot in a world, for `/rtp` and the API, without lagging the server:
 *
 * - **One candidate at a time**: a point spread evenly over the ring's area, inside the world
 *   border (and off the End's main island unless allowed). Points the border or the island rule
 *   out cost nothing and aren't tries.
 * - **Its chunk loads off the server thread** where the server can (Paper and Folia), or else on
 *   the server thread a few a tick ([TeleportServer.loadChunk]); with `only-generated`, a chunk
 *   never generated is skipped rather than made.
 * - **The column is read on the thread that owns it** (its region's on Folia), as one short read,
 *   and each search reads at most one column a tick: the next candidate waits for the last.
 * - **Bounded**: at most `attempts` candidates a search, one search per player, and `max-searches`
 *   at once server-wide; anyone over it is told to try again rather than queued.
 */
public class Search(private val server: TeleportServer, private val scheduler: Scheduler, private val enabled: () -> Boolean) {

    /** Where to look. [blockedBiomes] are names without a namespace, in lower case. */
    public class Area(
        public val centerX: Int,
        public val centerZ: Int,
        public val minRadius: Int,
        public val maxRadius: Int,
        public val attempts: Int,
        public val onlyGenerated: Boolean,
        public val allowMainIsland: Boolean,
        public val blockedBiomes: Set<String>,
    )

    /** A spot found, and how many candidates it took. */
    public class Found(public val location: Location, public val tries: Int)

    /** Whether a search may start. */
    public enum class Start { STARTED, BUSY, ALREADY }

    private val lock = Any()
    private var running = 0
    private val searching = HashSet<PlayerRef>()

    /**
     * Takes a place among the searches running, if there's room and [player] (`null` for none, as for
     * the API) isn't already searching. Each [Start.STARTED] needs one [end].
     */
    public fun begin(player: PlayerRef?, max: Int): Start = locked(lock) {
        when {
            player != null && player in searching -> Start.ALREADY
            running >= max -> Start.BUSY
            else -> {
                running++
                if (player != null) searching += player
                Start.STARTED
            }
        }
    }

    public fun end(player: PlayerRef?) {
        locked(lock) {
            running = max(0, running - 1)
            if (player != null) searching -= player
        }
    }

    /** Whether [player] has a search running. Any thread. */
    public fun searching(player: PlayerRef): Boolean = locked(lock) { player in searching }

    internal val runningNow: Int get() = locked(lock) { running }

    /**
     * Searches. Call [begin] first and [end] when it settles. Any thread. Settles with a spot, or
     * `null` if none was found in the attempts; fails only if reading the world threw.
     */
    public fun find(world: WorldInfo, area: Area, random: Random = Random.Default): Pending<Found?> {
        val found = newPending<Found?>()
        val half = world.borderSize / 2 - BORDER_MARGIN
        val bounds = Bounds(world.borderCenterX - half, world.borderCenterX + half, world.borderCenterZ - half, world.borderCenterZ + half)
        Attempt(world, area, bounds, world.end && !area.allowMainIsland, random, found).next(1)
        return found
    }

    internal class Bounds(val minX: Double, val maxX: Double, val minZ: Double, val maxZ: Double) {
        fun contains(x: Int, z: Int): Boolean = x >= minX && x <= maxX && z >= minZ && z <= maxZ

        /** How far the furthest corner is from a point. */
        fun furthest(x: Double, z: Double): Double {
            val dx = max(abs(minX - x), abs(maxX - x))
            val dz = max(abs(minZ - z), abs(maxZ - z))
            return sqrt(dx * dx + dz * dz)
        }
    }

    // One search's state, and its steps: a class rather than a lambda per step.
    private inner class Attempt(
        val world: WorldInfo,
        val area: Area,
        val bounds: Bounds,
        val skipMainIsland: Boolean,
        val random: Random,
        val found: Pending<Found?>,
    ) {
        fun next(tries: Int) {
            if (tries > area.attempts || !enabled()) {
                found.resolve(null)
                return
            }
            // Candidates the border or the main island rule out cost no chunk and aren't tries:
            // only the ring as far as the border's furthest corner is drawn from, and a bounded
            // number of misses ends the search when the ring and the border barely meet.
            val reach = min(area.maxRadius.toDouble(), bounds.furthest(area.centerX.toDouble(), area.centerZ.toDouble()))
            var x = 0
            var z = 0
            var picked = false
            if (reach >= area.minRadius) {
                for (miss in 0 until MAX_MISSES) {
                    val point = pick(area.minRadius.toDouble(), reach, area.centerX.toDouble(), area.centerZ.toDouble(), random)
                    x = point.first
                    z = point.second
                    if (bounds.contains(x, z) && !(skipMainIsland && x.toLong() * x + z.toLong() * z < MAIN_ISLAND.toLong() * MAIN_ISLAND)) {
                        picked = true
                        break
                    }
                }
            }
            if (!picked) {
                found.resolve(null)
                return
            }
            val bx = x
            val bz = z
            server.loadChunk(world.ref, bx shr 4, bz shr 4, !area.onlyGenerated).onSettled { loaded, _ ->
                if (loaded != true) {
                    next(tries + 1)
                } else {
                    scheduler.run(Where.At(Location(world.ref, bx.toDouble(), 64.0, bz.toDouble()))) { check(bx, bz, tries) }
                }
            }
        }

        // On the thread that owns the column.
        private fun check(x: Int, z: Int, tries: Int) {
            val spot = try {
                server.blocks(world.ref)?.let { spot(it, x, z) }
            } catch (e: Exception) {
                found.reject(e)
                return
            }
            if (spot != null) found.resolve(Found(spot, tries)) else next(tries + 1)
        }

        private fun spot(blocks: Blocks, x: Int, z: Int): Location? {
            val ground = Safety.rtpGround(blocks, world, x, z) ?: return null
            val feet = ground + 1
            if (area.blockedBiomes.isNotEmpty() && blocks.biome(x, feet, z) in area.blockedBiomes) return null
            val yaw = random.nextInt(4) * 90f
            return Location(world.ref, x + 0.5, feet.toDouble(), z + 0.5, yaw, 0f)
        }
    }

    public companion object {
        /** The End's main island reaches about this far from 0,0; its outer islands start beyond. */
        public const val MAIN_ISLAND: Int = 1000

        /** Spots stay this far inside the world border. */
        public const val BORDER_MARGIN: Int = 16

        /** Points outside the border or on the End's main island drawn before giving up. */
        internal const val MAX_MISSES: Int = 1000

        /** A point in the ring, spread evenly over its area, not bunched at the centre. */
        internal fun pick(min: Double, max: Double, centerX: Double, centerZ: Double, random: Random): Pair<Int, Int> {
            val radius = sqrt(min * min + random.nextDouble() * (max * max - min * min))
            val angle = random.nextDouble() * PI * 2
            return floor(centerX + radius * cos(angle)).toInt() to floor(centerZ + radius * sin(angle)).toInt()
        }
    }
}
