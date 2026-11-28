package co.xenastudios.xlimits

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.VirtualScheduler
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.SlowAction

/**
 * A server for xLimits' tests: worlds of chunks holding entities and blocks the test puts there,
 * players who are [FakeCommandSource]s, and what xLimits did to them. Every call checks it's on
 * the thread that owns what it touches, as Folia would.
 */
class FakeLimitsServer(
    private val scheduler: VirtualScheduler,
    perRegion: Boolean = true,
    actions: Set<SlowAction> = SlowAction.entries.toSet(),
    placedBeforeCheck: Boolean = true,
) : LimitsServer, OnlinePlayers, LanguageSource {
    val world: WorldRef = WorldRef.of("world")

    /** One entity: its kind, its id, whom it's tamed by, its name, and farm slowing's marks. */
    class Mob(val kind: EntityKind, val id: String, var owner: PlayerRef? = null, var named: Boolean = false) {
        val tags = LinkedHashSet<String>()
    }

    val loaded = HashMap<ChunkRef, MutableList<Mob>>()
    val blocks = HashMap<ChunkRef, MutableMap<String, Int>>()
    val players = LinkedHashMap<PlayerRef, FakeCommandSource>()
    val where = HashMap<PlayerRef, Location>()
    val broken = ArrayList<Location>()
    val consoleLines = ArrayList<String>()
    var censuses = 0
    var blockCounts = 0
    var tps: Double? = 20.0
    var mspt: Double? = 10.0
    var plugins = setOf<String>()

    /** Block counts held back until [finishCounts], as an async snapshot would be. */
    var asyncCounts = false
    private val pendingCounts = ArrayList<() -> Unit>()

    val listening = LinkedHashSet<Int>()
    private var nextId = 0

    override val features: LimitsServer.Features = LimitsServer.Features(
        Switch.NAMES.indices.toSet(), actions, placedBeforeCheck, perRegion,
    )

    fun join(source: FakeCommandSource, at: Location = Location(world, 8.0, 64.0, 8.0)) {
        val player = source.player!!
        players[player] = source
        where[player] = at
        scheduler.spawn(player.uuid, at)
    }

    fun chunk(x: Int, z: Int): ChunkRef = ChunkRef(world, x, z)

    fun at(x: Int, z: Int): Location = Location(world, x * 16 + 4.0, 64.0, z * 16 + 4.0)

    /** Puts [count] mobs of [kind] in [chunk], as if they were there all along. */
    fun put(chunk: ChunkRef, kind: EntityKind, count: Int = 1, owner: PlayerRef? = null): List<Mob> {
        val list = loaded.getOrPut(chunk) { ArrayList() }
        return List(count) { Mob(kind, "mob-${nextId++}", owner).also { list += it } }
    }

    fun mobs(chunk: ChunkRef): List<Mob> = loaded[chunk].orEmpty()

    fun finishCounts() {
        val each = pendingCounts.toList()
        pendingCounts.clear()
        each.forEach { it() }
    }

    private fun owns(chunk: ChunkRef) {
        check(scheduler.owns(Where.At(chunk.center()))) { "touched $chunk off its region's thread" }
    }

    override fun online(): Collection<PlayerRef> = players.keys.toList()
    override fun onlineCount(): Int = players.size
    override fun name(player: PlayerRef): String? = players[player]?.name
    override fun hasPermission(player: PlayerRef, node: String): Boolean = players[player]?.hasPermission(node) == true
    override fun send(player: PlayerRef, message: Component) {
        players[player]?.reply(message)
    }
    override fun console(message: Component) {
        consoleLines += message.plain()
    }
    override fun location(player: PlayerRef): Location? = where[player]

    override fun census(chunk: ChunkRef, census: Census): Boolean {
        owns(chunk)
        val mobs = loaded[chunk] ?: return false
        censuses++
        for (mob in mobs) census.add(mob.kind, SLOWED in mob.tags, mob.owner, mob.owner?.let { mob.id })
        return true
    }

    override fun countBlocks(chunk: ChunkRef, ids: Set<String>, done: (Map<String, Int>?) -> Unit) {
        owns(chunk)
        blockCounts++
        val counts = blocks[chunk]?.filterKeys { it in ids }.orEmpty().toMap()
        if (!asyncCounts) return done(counts)
        pendingCounts += { scheduler.runAs(Where.At(chunk.center())) { done(counts) } }
    }

    override fun slow(chunk: ChunkRef, select: (EntityKind) -> Boolean, actions: Set<SlowAction>, villagers: Boolean, slow: Boolean): Int {
        owns(chunk)
        var changed = 0
        for (mob in mobs(chunk)) {
            if (!select(mob.kind) || mob.named || mob.owner != null) continue
            if (!villagers && mob.kind.isIn(Category.VILLAGER)) continue
            if (slow) {
                if (SLOWED in mob.tags) continue
                mob.tags += SLOWED
                actions.forEach { mob.tags += it.name }
                changed++
            } else if (mob.tags.remove(SLOWED)) {
                mob.tags.clear()
                changed++
            }
        }
        return changed
    }

    override fun restoreAll(done: (Int) -> Unit) {
        var changed = 0
        for ((chunk, mobs) in loaded) {
            scheduler.runAs(Where.At(chunk.center())) {
                for (mob in mobs) if (mob.tags.remove(SLOWED)) {
                    mob.tags.clear()
                    changed++
                }
            }
        }
        done(changed)
    }

    override fun busiest(what: Int, around: PlayerRef?, done: (List<ChunkStat>) -> Unit) {
        val near = around?.let { where[it] }?.let { ChunkRef.of(it) }
        done(
            loaded.keys.filter { near == null || (kotlin.math.abs(it.x - near.x) <= 4 && kotlin.math.abs(it.z - near.z) <= 4) }
                .map { ChunkStat(it, mobs(it).size, blocks[it]?.values?.sum() ?: 0) },
        )
    }

    override fun breakBlock(location: Location) {
        owns(ChunkRef.of(location))
        broken += location
    }

    override fun teleport(player: PlayerRef, location: Location): Boolean {
        if (location.world != world) return false
        where[player] = location
        return true
    }

    override fun tps(chunk: ChunkRef): Double? = tps
    override fun mspt(): Double? = mspt
    override fun serverSettings(chunk: ChunkRef): List<Pair<String, String>> = listOf("max-entity-collisions" to "8")
    override fun hasPlugin(name: String): Boolean = name in plugins

    override fun listen(switch: Int, view: LimitsView): AutoCloseable {
        listening += switch
        return AutoCloseable { listening -= switch }
    }

    // OnlinePlayers and LanguageSource
    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.key
    override fun names(viewer: CommandSource): List<String> = players.values.map { it.name }
    override fun clientLocale(player: PlayerRef): String? = null

    companion object {
        const val SLOWED = "xlimits.slowed"
        val COW = EntityKind("cow", Category.ANIMAL)
        val PIG = EntityKind("pig", Category.ANIMAL)
        val WOLF = EntityKind("wolf", Category.ANIMAL)
        val ZOMBIE = EntityKind("zombie", Category.MONSTER)
        val VILLAGER = EntityKind("villager", Category.VILLAGER)
        val BOAT = EntityKind("boat", Category.VEHICLE)
        val TNT = EntityKind("tnt", Category.TNT)
        val SAND = EntityKind("falling_block", Category.FALLING)
        val ITEM = EntityKind("item", 0)
    }
}
