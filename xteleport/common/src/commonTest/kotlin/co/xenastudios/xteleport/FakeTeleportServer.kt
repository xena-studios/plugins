package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.VirtualScheduler

/**
 * A server for xTeleport's tests: who's online and where, what each was sent, their permissions,
 * and worlds whose blocks are a function ([ground]): by default a flat world of stone with its top
 * at y 63, which `/rtp` and the safety check read like any other.
 */
class FakeTeleportServer(private val scheduler: VirtualScheduler) : TeleportServer, OnlinePlayers, LanguageSource {
    val online = LinkedHashMap<PlayerRef, FakeCommandSource>()
    val where = HashMap<PlayerRef, Location>()
    val sent = HashMap<PlayerRef, MutableList<String>>()
    val bars = HashMap<PlayerRef, MutableList<String>>()
    val beds = HashMap<PlayerRef, Location>()
    val teleports = ArrayList<Pair<PlayerRef, Location>>()
    var refuseTeleports = false
    var economy: FakeEconomy? = null
    var players: PlayerEvents? = null
    var spawns: SpawnEvents? = null
    var damage: ((PlayerRef) -> Unit)? = null
    val hiddenFrom = HashMap<PlayerRef, MutableSet<PlayerRef>>()
    val loads = ArrayList<Triple<String, Int, Int>>()

    /** The block kind at a place, by world: stone below y 64, air above, unless a test changes it. */
    val ground = HashMap<String, (Int, Int, Int) -> Int>()
    val worlds = linkedMapOf(
        "world" to info("world", end = false),
        "world_nether" to info("world_nether", ceiling = true),
        "world_the_end" to info("world_the_end", end = true),
    )

    private fun info(name: String, ceiling: Boolean = false, end: Boolean = false) = WorldInfo(
        WorldRef.of(name), 0, 256, if (ceiling) 128 else 256, ceiling, end, Location(WorldRef.of(name), 0.5, 64.0, 0.5), 0.0, 0.0, 60_000_000.0,
    )

    fun join(source: FakeCommandSource, at: Location = Location(WorldRef.of("world"), 0.5, 64.0, 0.5)) {
        val player = source.player!!
        online[player] = source
        where[player] = at
        scheduler.spawn(player.uuid, at)
        players?.joined(player, source.name)
    }

    fun quit(source: FakeCommandSource) {
        val player = source.player!!
        online.remove(player)
        where.remove(player)
        scheduler.despawn(player.uuid)
        players?.quit(player)
    }

    /** Moves [player], as walking does. */
    fun walk(player: PlayerRef, to: Location) {
        where[player] = to
        scheduler.move(player.uuid, to)
    }

    override val features: Features = Features(actionBar = true, loading = "at once (tests)")

    override fun isOnline(player: PlayerRef): Boolean = player in online

    override fun name(player: PlayerRef): String? = online[player]?.name

    override fun online(): List<PlayerRef> = online.keys.toList()

    override fun send(player: PlayerRef, message: Component) {
        if (player in online) sent.getOrPut(player) { ArrayList() } += message.plain()
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        bars.getOrPut(player) { ArrayList() } += message.plain()
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = online[player]?.hasPermission(node) == true

    override fun homeNumbers(player: PlayerRef): Int =
        online[player]?.permissions?.mapNotNull { it.removePrefix("xteleport.homes.").toIntOrNull() }?.maxOrNull() ?: 0

    override fun location(player: PlayerRef): Location? = where[player]

    override fun bed(player: PlayerRef): Location? = beds[player]

    override fun standOn(block: Location): Location? = Location(block.world, block.blockX + 0.5, block.blockY + 0.5625, block.blockZ + 0.5)

    override fun worlds(): List<WorldRef> = worlds.values.map { it.ref }

    override fun world(name: String): WorldInfo? = worlds[name]

    override fun loadChunk(world: WorldRef, chunkX: Int, chunkZ: Int, generate: Boolean): Pending<Boolean> {
        loads += Triple(world.name, chunkX, chunkZ)
        return pendingOf(true)
    }

    override fun blocks(world: WorldRef): Blocks? {
        val info = worlds[world.name] ?: return null
        val kinds = ground[world.name] ?: { _, y, _ -> if (y < 64) BlockKind.SOLID or BlockKind.FULL else BlockKind.AIR }
        return object : Blocks {
            override fun kind(x: Int, y: Int, z: Int): Int = if (y < info.minY || y >= info.maxY) BlockKind.AIR else kinds(x, y, z)

            override fun highest(x: Int, z: Int): Int {
                for (y in info.maxY - 1 downTo info.minY) if (kinds(x, y, z) != BlockKind.AIR) return y
                return Int.MIN_VALUE
            }

            override fun biome(x: Int, y: Int, z: Int): String = "plains"
        }
    }

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        if (refuseTeleports || player !in online) return done(false)
        teleports += player to to
        walk(player, to)
        done(true)
    }

    override fun watchPlayers(listener: PlayerEvents): AutoCloseable {
        players = listener
        return AutoCloseable { players = null }
    }

    override fun watchSpawn(listener: SpawnEvents): AutoCloseable {
        spawns = listener
        return AutoCloseable { spawns = null }
    }

    override fun watchDamage(hurt: (PlayerRef) -> Unit): AutoCloseable {
        damage = hurt
        return AutoCloseable { damage = null }
    }

    override fun economy(): Economy? = economy

    // ---- OnlinePlayers and LanguageSource ----

    override fun find(name: String, viewer: CommandSource): PlayerRef? = online.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.key
        ?.takeIf { target -> viewer.player?.let { target in hiddenFrom[it].orEmpty() } != true }

    override fun names(viewer: CommandSource): List<String> = online.values.map { it.name }

    override fun clientLocale(player: PlayerRef): String? = null
}

/** An economy with balances in memory, which settles at once. */
class FakeEconomy : Economy {
    val balances = HashMap<PlayerRef, Double>()
    override val name: String get() = "Fake"

    override fun has(player: PlayerRef, amount: Double): Boolean = (balances[player] ?: 0.0) >= amount

    override fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> {
        if (!has(player, amount)) return pendingOf(false)
        balances[player] = (balances[player] ?: 0.0) - amount
        return pendingOf(true)
    }

    override fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> {
        balances[player] = (balances[player] ?: 0.0) + amount
        return pendingOf(true)
    }

    override fun format(amount: Double): String = "$" + amount.toLong()
}
