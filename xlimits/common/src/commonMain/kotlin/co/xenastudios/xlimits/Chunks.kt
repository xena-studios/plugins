package co.xenastudios.xlimits

import co.xenastudios.core.common.concurrentMap
import co.xenastudios.xlimits.api.ChunkRef
import kotlin.concurrent.Volatile

/**
 * What xLimits knows about one chunk, kept while it's loaded and something limited happened in
 * it. Only the thread that owns the chunk reads or changes it (Folia's region), so nothing here
 * is locked; the few fields other threads read for reports are volatile.
 */
internal class ChunkEntry(val chunk: ChunkRef) {
    /** The [Rules.generation] the arrays below are sized for. */
    var generation: Int = -1

    /** When its entities were last counted, in the platform's elapsed milliseconds. */
    var countedAt: Long = NEVER

    /** Per entity rule: how many it counts here, from the last count plus what was allowed since. */
    var entities: IntArray = EMPTY

    /** Per farm rule: how many of its mobs are here. */
    var farm: IntArray = EMPTY

    /** Per farm rule: whether its mobs here may be slowed now (and so may need restoring). */
    var slowed: BooleanArray = BooleanArray(0)

    var tnt: Int = 0
    var falling: Int = 0

    /** Per block rule: how many are here, once counted; `null` until then. */
    var blocks: IntArray? = null

    /** Per block rule: what changed while a count was being made off the server's threads. */
    var blockDelta: IntArray? = null

    /** Per redstone block (its place in the chunk, [redstoneKey]): when its window started, and its changes in it. */
    var redstone: HashMap<Int, LongArray>? = null

    /** The redstone changes counted here lately, for `/xlimits top redstone`. Any thread reads it. */
    @Volatile
    var redstoneScore: Int = 0

    var portalStart: Long = NEVER
    var portalCount: Int = 0

    /** When staff were last alerted about this chunk. */
    var alertedAt: Long = NEVER

    /** The chunk's region's load profile, on Folia: each chunk's checks keep their region's. */
    val profile: ProfileState = ProfileState()

    /** Empties the arrays for rules of [rules.generation]: counted again at the next check. */
    fun resize(rules: Rules) {
        if (generation == rules.generation) return
        generation = rules.generation
        entities = IntArray(rules.entities.size)
        val wasSlowed = slowed.any { it }
        farm = IntArray(rules.farms.size)
        // A reload's rules may differ: any farm here may have slowed mobs to restore.
        slowed = BooleanArray(rules.farms.size) { wasSlowed }
        blocks = null
        blockDelta = null
        countedAt = NEVER
        profile.reset()
    }

    companion object {
        const val NEVER: Long = Long.MIN_VALUE / 2
        val EMPTY = IntArray(0)

        /** A block's place within its chunk as one number: y, then x and z in the chunk. */
        fun redstoneKey(x: Int, y: Int, z: Int): Int = ((y + 4096) shl 8) or ((x and 15) shl 4) or (z and 15)
    }
}

/** Every chunk xLimits knows about, by chunk. Each entry belongs to the thread that owns its chunk. */
internal class Chunks {
    private val entries = concurrentMap<ChunkRef, ChunkEntry>()

    /** [chunk]'s entry, made if there's none. On [chunk]'s thread. */
    fun entry(chunk: ChunkRef): ChunkEntry = entries[chunk] ?: ChunkEntry(chunk).also { entries[chunk] = it }

    /** [chunk]'s entry if there is one. */
    fun find(chunk: ChunkRef): ChunkEntry? = entries[chunk]

    fun forget(chunk: ChunkRef) {
        entries.remove(chunk)
    }

    /** A copy of every entry, for reports that only read their volatile fields. */
    fun all(): List<ChunkEntry> = entries.values.toList()

    fun clear() {
        entries.clear()
    }

    val size: Int get() = entries.size
}

/**
 * Who owns which tamed mobs, in memory: each pet's owner and type, by the pet's id, learned as
 * they're tamed and as chunks with them are counted, forgotten as they die. Any thread.
 */
internal class Pets {
    private val owners = concurrentMap<String, Pair<String, String>>()
    private val counts = concurrentMap<String, IntArray>()
    private val lock = Any()

    /** [pet] of [type] is [owner]'s. */
    fun seen(owner: String, pet: String, type: String) {
        co.xenastudios.core.common.locked(lock) {
            val before = owners[pet]
            if (before != null && before.first == owner && before.second == type) return
            if (before != null) drop(before)
            owners[pet] = owner to type
            bump(owner, 1)
            bump("$owner/$type", 1)
        }
    }

    fun gone(pet: String) {
        co.xenastudios.core.common.locked(lock) { owners.remove(pet)?.let(::drop) }
    }

    /** How many pets [owner] has, of [type] or of every type. */
    fun count(owner: String, type: String? = null): Int = counts[if (type == null) owner else "$owner/$type"]?.get(0) ?: 0

    fun clear() {
        co.xenastudios.core.common.locked(lock) {
            owners.clear()
            counts.clear()
        }
    }

    val size: Int get() = owners.size

    private fun drop(was: Pair<String, String>) {
        bump(was.first, -1)
        bump("${was.first}/${was.second}", -1)
    }

    private fun bump(key: String, by: Int) {
        val box = counts[key]
        if (box == null) {
            if (by > 0) counts[key] = intArrayOf(by)
            return
        }
        box[0] += by
        if (box[0] <= 0) counts.remove(key)
    }
}
