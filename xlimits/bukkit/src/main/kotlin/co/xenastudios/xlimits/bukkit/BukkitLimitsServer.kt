package co.xenastudios.xlimits.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.BukkitServer
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.xlimits.Busiest
import co.xenastudios.xlimits.Census
import co.xenastudios.xlimits.ChunkStat
import co.xenastudios.xlimits.EntityKind
import co.xenastudios.xlimits.LimitsServer
import co.xenastudios.xlimits.LimitsView
import co.xenastudios.xlimits.Switch
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.api.XLimits
import org.bukkit.Chunk
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.toKotlinUuid

/**
 * xLimits' [LimitsServer] on Bukkit, from 1.13 to the newest Paper and Folia. Spawns, blocks,
 * tames and deaths come from the core's world hooks ([hooks]); chunks loading, redstone, TNT,
 * falling blocks and portals from listeners of xLimits' own (`Listeners.kt`), each only while its
 * limit is on. What each server has is found once ([Paths]); the newer calls are in `modern/`.
 */
internal class BukkitLimitsServer(
    private val plugin: JavaPlugin,
    private val platform: BukkitPlatform,
    /** The core's world hooks, made with xLimits' place among other plugins. */
    val hooks: WorldHooks,
) : LimitsServer {
    private val server = plugin.server
    val kinds = Kinds()
    val paths = Paths(platform.server, platform.threading == Threading.REGIONS)

    /** What's found once: which calls this server has. */
    class Paths(version: BukkitServer, val folia: Boolean) {
        val paper = version.flavor == BukkitServer.Flavor.PAPER || version.flavor == BukkitServer.Flavor.FOLIA
        val aware = version.atLeast(1, 15)
        val minHeight = version.atLeast(1, 17)
        val entitiesLoad = version.atLeast(1, 17)
        val tps = paper && has("org.bukkit.Server", "getTPS")
        /** Folia's `Server.getRegionTPS(World, int, int)`, in Folia's API only, so called through its method, found once. */
        val regionTps: java.lang.reflect.Method? = if (!folia) null else try {
            Class.forName("org.bukkit.Server").getMethod("getRegionTPS", World::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } catch (e: ReflectiveOperationException) {
            null
        }
        val tickTime = paper && !folia && has("org.bukkit.Server", "getAverageTickTime")
        val teleportAsync = has("org.bukkit.entity.Entity", "teleportAsync", org.bukkit.Location::class.java)
        val bukkitTntPrime = has("org.bukkit.event.block.TNTPrimeEvent")
        val paperTntPrime = has("com.destroystokyo.paper.event.block.TNTPrimeEvent")

        private companion object {
            fun has(cls: String, method: String? = null, vararg args: Class<*>): Boolean = try {
                val c = Class.forName(cls)
                if (method != null) c.getMethod(method, *args)
                true
            } catch (e: ReflectiveOperationException) {
                false
            } catch (e: LinkageError) {
                false
            }
        }
    }

    override val features: LimitsServer.Features = LimitsServer.Features(
        Switch.NAMES.indices.toSet(),
        buildSet {
            add(SlowAction.NO_COLLISIONS)
            add(SlowAction.NO_BREEDING)
            if (paths.aware) add(SlowAction.NO_AI)
        },
        placedBeforeCheck = true,
        perRegion = paths.folia,
        gaps = buildList {
            if (!paths.aware) add("farm slowing's no-ai needs 1.15 (mobs' awareness)")
            if (!paths.tps && paths.regionTps == null) add("tps-below profiles need Paper or Folia")
            if (!paths.tickTime) add("mspt-above profiles need Paper (not Folia)")
            if (paths.folia) add("/xlimits top and restore cover the chunks around online players (Folia has no list of every loaded chunk)")
        },
    )

    private fun player(ref: PlayerRef): Player? = ref.online(server)

    fun world(chunk: ChunkRef): World? = server.getWorld(chunk.world.name)

    /** [chunk]'s Bukkit chunk, if it's loaded. */
    fun loaded(chunk: ChunkRef): Chunk? {
        val world = world(chunk) ?: return null
        return if (world.isChunkLoaded(chunk.x, chunk.z)) world.getChunkAt(chunk.x, chunk.z) else null
    }

    override fun online(): Collection<PlayerRef> = server.onlinePlayers.map { it.ref }

    override fun onlineCount(): Int = server.onlinePlayers.size

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(server.consoleSender, message)
    }

    override fun location(player: PlayerRef): Location? = player(player)?.location?.toCore()

    override fun census(chunk: ChunkRef, census: Census): Boolean {
        val loaded = loaded(chunk) ?: return false
        for (entity in loaded.entities) {
            val kind = kinds.of(entity)
            val owner = (entity as? Tameable)?.takeIf { it.isTamed }?.owner
            census.add(kind, slowed = entity.scoreboardTags.contains(XLimits.SLOWED_TAG), owner = owner?.let { PlayerRef.of(it.uniqueId.toKotlinUuid()) }, id = owner?.let { entity.uniqueId.toString() })
        }
        return true
    }

    override fun countBlocks(chunk: ChunkRef, ids: Set<String>, done: (Map<String, Int>?) -> Unit) {
        val loaded = loaded(chunk) ?: return done(null)
        if (ids.all { it in BLOCK_ENTITIES }) {
            // Block entities only: the chunk's own list of them, at once.
            val counts = HashMap<String, Int>()
            for (state in loaded.tileEntities) {
                val id = Kinds.id(state.type)
                if (id in ids) counts[id] = (counts[id] ?: 0) + 1
            }
            return done(counts)
        }
        // Plain blocks: a snapshot, counted off the server's threads, the answer back on the chunk's.
        val world = loaded.world
        val snapshot = loaded.getChunkSnapshot(false, false, false)
        val bottom = if (paths.minHeight) co.xenastudios.xlimits.bukkit.modern.minHeight(world) else 0
        val top = world.maxHeight
        val back = chunk.center()
        platform.scheduler.run(Where.Async) {
            val counts = count(snapshot, ids, bottom, top)
            platform.scheduler.run(Where.At(back)) { done(counts) }
        }
    }

    private fun count(snapshot: ChunkSnapshot, ids: Set<String>, bottom: Int, top: Int): Map<String, Int> {
        val wanted = BooleanArray(MATERIALS.size) { Kinds.id(MATERIALS[it]) in ids }
        val tally = IntArray(MATERIALS.size)
        for (y in bottom until top) for (x in 0 until 16) for (z in 0 until 16) {
            val i = snapshot.getBlockType(x, y, z).ordinal
            if (wanted[i]) tally[i]++
        }
        val out = HashMap<String, Int>()
        for (i in tally.indices) if (tally[i] > 0) out[Kinds.id(MATERIALS[i])] = (out[Kinds.id(MATERIALS[i])] ?: 0) + tally[i]
        return out
    }

    override fun slow(chunk: ChunkRef, select: (EntityKind) -> Boolean, actions: Set<SlowAction>, villagers: Boolean, slow: Boolean): Int {
        val loaded = loaded(chunk) ?: return 0
        var changed = 0
        for (entity in loaded.entities) {
            if (entity !is LivingEntity || entity is Player) continue
            val kind = kinds.of(entity)
            if (!select(kind)) continue
            if (slow) {
                if (exempt(entity, kind, villagers)) continue
                if (slow(entity, actions)) changed++
            } else if (restore(entity)) {
                changed++
            }
        }
        return changed
    }

    // Named, tamed and leashed mobs are never slowed; villagers only when asked.
    private fun exempt(entity: LivingEntity, kind: EntityKind, villagers: Boolean): Boolean =
        entity.customName != null || (entity is Tameable && entity.isTamed) || entity.isLeashed ||
            (!villagers && kind.isIn(co.xenastudios.xlimits.Category.VILLAGER))

    // Slows one mob, marking what was changed so restoring undoes only that.
    private fun slow(entity: LivingEntity, actions: Set<SlowAction>): Boolean {
        val tags = entity.scoreboardTags
        if (XLimits.SLOWED_TAG in tags) return false
        var any = false
        if (SlowAction.NO_COLLISIONS in actions && entity.isCollidable) {
            entity.isCollidable = false
            entity.addScoreboardTag(NO_COLLISIONS)
            any = true
        }
        if (SlowAction.NO_AI in actions && paths.aware && co.xenastudios.xlimits.bukkit.modern.aware(entity, false)) {
            entity.addScoreboardTag(NO_AI)
            any = true
        }
        if (SlowAction.NO_BREEDING in actions && entity is Ageable && entity.isAdult && entity.canBreed()) {
            entity.setBreed(false)
            entity.addScoreboardTag(NO_BREEDING)
            any = true
        }
        // Marked even when nothing needed changing, so it isn't looked at again while the farm stays full.
        entity.addScoreboardTag(XLimits.SLOWED_TAG)
        return any
    }

    private fun restore(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        if (XLimits.SLOWED_TAG !in tags) return false
        if (entity.removeScoreboardTag(NO_COLLISIONS)) entity.isCollidable = true
        if (entity.removeScoreboardTag(NO_AI) && paths.aware) co.xenastudios.xlimits.bukkit.modern.aware(entity, true)
        if (entity.removeScoreboardTag(NO_BREEDING) && entity is Ageable && entity.isAdult) entity.setBreed(true)
        entity.removeScoreboardTag(XLimits.SLOWED_TAG)
        return true
    }

    override fun restoreAll(done: (Int) -> Unit) {
        val restored = AtomicInteger()
        eachChunk(everyLoaded(), { chunk ->
            for (entity in chunk.entities) if (entity is LivingEntity && restore(entity)) restored.incrementAndGet()
            null
        }) { done(restored.get()) }
    }

    override fun busiest(what: Int, around: PlayerRef?, done: (List<ChunkStat>) -> Unit) {
        val near = around?.let(::player)
        val list = if (near != null) {
            around(near.world, near.location.blockX shr 4, near.location.blockZ shr 4, NEAR)
        } else {
            everyLoaded()
        }
        eachChunk(list, { chunk ->
            val ref = ChunkRef(chunk.world.ref, chunk.x, chunk.z)
            ChunkStat(ref, chunk.entities.size, chunk.tileEntities.size)
        }, done)
    }

    // Every loaded chunk; on Folia, which has no such list, those around each online player.
    private fun everyLoaded(): List<Triple<World, Int, Int>> {
        if (!paths.folia) return server.worlds.flatMap { w -> w.loadedChunks.map { Triple(w, it.x, it.z) } }
        val seen = LinkedHashSet<Triple<World, Int, Int>>()
        val radius = minOf(server.viewDistance, MAX_RADIUS)
        for (p in server.onlinePlayers) {
            val at = p.location
            seen += around(at.world ?: continue, at.blockX shr 4, at.blockZ shr 4, radius)
        }
        return seen.toList()
    }

    private fun around(world: World, cx: Int, cz: Int, radius: Int): List<Triple<World, Int, Int>> {
        val out = ArrayList<Triple<World, Int, Int>>()
        for (x in cx - radius..cx + radius) for (z in cz - radius..cz + radius) out += Triple(world, x, z)
        return out
    }

    /**
     * Runs [each] on every loaded chunk of [chunks], each on its own thread (Folia's regions;
     * elsewhere all on the main thread), and calls [done] with what they returned once all have.
     */
    private fun <T : Any> eachChunk(chunks: List<Triple<World, Int, Int>>, each: (Chunk) -> T?, done: (List<T>) -> Unit) {
        val results = java.util.Collections.synchronizedList(ArrayList<T>())
        if (!paths.folia) {
            platform.scheduler.run(Where.Global) {
                for ((world, x, z) in chunks) if (world.isChunkLoaded(x, z)) each(world.getChunkAt(x, z))?.let(results::add)
                done(results.toList())
            }
            return
        }
        if (chunks.isEmpty()) return done(emptyList())
        val left = AtomicInteger(chunks.size)
        for ((world, x, z) in chunks) {
            val at = Location(world.ref, (x shl 4) + 8.0, 64.0, (z shl 4) + 8.0)
            val finish = { if (left.decrementAndGet() == 0) done(results.toList()) }
            platform.scheduler.run(Where.At(at), retired = finish) {
                try {
                    if (world.isChunkLoaded(x, z)) each(world.getChunkAt(x, z))?.let(results::add)
                } finally {
                    finish()
                }
            }
        }
    }

    override fun breakBlock(location: Location) {
        val world = server.getWorld(location.world.name) ?: return
        // After the event that noticed it, so the change it's in finishes first.
        platform.scheduler.run(Where.At(location)) {
            val block = world.getBlockAt(location.blockX, location.blockY, location.blockZ)
            if (block.type != Material.AIR) block.breakNaturally()
        }
    }

    override fun teleport(player: PlayerRef, location: Location): Boolean {
        val p = player(player) ?: return false
        val world = server.getWorld(location.world.name) ?: return false
        // A safe place first, on the thread that owns it: the given height if there's room, else the top.
        platform.scheduler.run(Where.At(location)) {
            val x = location.blockX
            val z = location.blockZ
            var y = location.blockY
            fun room(at: Int) = !world.getBlockAt(x, at, z).type.isSolid && !world.getBlockAt(x, at + 1, z).type.isSolid
            if (!room(y)) y = world.getHighestBlockYAt(x, z) + 1
            val to = org.bukkit.Location(world, x + 0.5, y.toDouble(), z + 0.5, p.location.yaw, p.location.pitch)
            if (paths.teleportAsync) {
                co.xenastudios.xlimits.bukkit.modern.teleportAsync(p, to)
            } else {
                platform.scheduler.run(Where.on(player)) { p.teleport(to) }
            }
        }
        return true
    }

    override fun tps(chunk: ChunkRef): Double? = when {
        paths.regionTps != null -> world(chunk)?.let { (paths.regionTps.invoke(server, it, chunk.x, chunk.z) as? DoubleArray)?.firstOrNull() }
        paths.tps -> co.xenastudios.xlimits.bukkit.modern.tps(server)
        else -> null
    }

    override fun mspt(): Double? = if (paths.tickTime) co.xenastudios.xlimits.bukkit.modern.mspt(server) else null

    override fun serverSettings(chunk: ChunkRef): List<Pair<String, String>> = ServerSettings.read(server, chunk.world.name)

    override fun hasPlugin(name: String): Boolean = server.pluginManager.isPluginEnabled(name)

    override fun listen(switch: Int, view: LimitsView): AutoCloseable = Listeners.start(switch, view, plugin, this, platform)

    /** [entity]'s place, as the core has it. */
    fun at(entity: Entity): Location = entity.location.toCore()

    companion object {
        const val NO_COLLISIONS = "xlimits.no-collisions"
        const val NO_AI = "xlimits.no-ai"
        const val NO_BREEDING = "xlimits.no-breeding"

        /** How far `/xlimits near` looks, in chunks. */
        const val NEAR = 4

        /** The most chunks around each player `/xlimits top` and `restore` cover on Folia. */
        const val MAX_RADIUS = 10

        private val MATERIALS: Array<Material> = Material.values()

        /** The blocks with a block entity, counted from the chunk's list of them rather than a snapshot. */
        val BLOCK_ENTITIES: Set<String> = setOf(
            "hopper", "spawner", "chest", "trapped_chest", "barrel", "furnace", "blast_furnace", "smoker", "dispenser", "dropper",
            "brewing_stand", "beacon", "shulker_box", "ender_chest", "enchanting_table", "jukebox", "lectern", "campfire", "soul_campfire",
            "beehive", "bee_nest", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "sculk_catalyst", "crafter", "trial_spawner",
            "vault", "decorated_pot", "chiseled_bookshelf", "comparator", "daylight_detector", "sign", "bell", "conduit", "end_gateway",
        )
    }
}
