package co.xenastudios.xlimits.fabric

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.EntityChange
import co.xenastudios.core.common.world.EntityChangeKind
import co.xenastudios.core.common.world.EntityDeath
import co.xenastudios.core.common.world.EntitySpawn
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.level
import co.xenastudios.core.fabric.location
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.xlimits.BlockChanges
import co.xenastudios.xlimits.Category
import co.xenastudios.xlimits.Census
import co.xenastudios.xlimits.ChunkStat
import co.xenastudios.xlimits.EntityKind
import co.xenastudios.xlimits.LimitsServer
import co.xenastudios.xlimits.LimitsView
import co.xenastudios.xlimits.SpawnRoutes
import co.xenastudios.xlimits.Switch
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.api.XLimits
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.scores.Team
import java.util.IdentityHashMap
import kotlin.concurrent.Volatile

/**
 * xLimits' [LimitsServer] on a Fabric server, with Minecraft's own classes: spawns, blocks, tames
 * and deaths from the core's world hooks (their mixins are the mod's), chunks with their mobs from
 * Fabric API's entity events. Everything runs on the server thread, Fabric's only one.
 *
 * What a Fabric server has no event for isn't limited here, and the docs say so: redstone clocks,
 * TNT and falling blocks, and portals. Farm slowing turns collisions off through a scoreboard
 * team of its own (Minecraft has no per-mob switch); no-ai and no-breeding are Bukkit's.
 */
internal class FabricLimitsServer(private val platform: FabricPlatform, private val hooks: WorldHooks) : LimitsServer {

    override val features: LimitsServer.Features = LimitsServer.Features(
        setOf(Switch.ENTITIES, Switch.CHUNKS, Switch.BLOCKS, Switch.PETS),
        setOf(SlowAction.NO_COLLISIONS),
        placedBeforeCheck = false,
        perRegion = false,
        gaps = listOf(
            "redstone, tnt and portals limits (Fabric has no events for them)",
            "farm slowing's no-ai and no-breeding",
            "tps-below and mspt-above profiles",
            "pets are counted as they're tamed while the server runs, not from the mobs already there",
        ),
    )

    // Server thread only.
    private val kinds = IdentityHashMap<net.minecraft.world.entity.EntityType<*>, EntityKind>()

    fun kind(entity: Entity): EntityKind = kinds.getOrPut(entity.type) { make(entity) }

    private fun make(entity: Entity): EntityKind {
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path
        var c = when (entity.type.category) {
            MobCategory.CREATURE -> Category.ANIMAL
            MobCategory.MONSTER -> Category.MONSTER
            MobCategory.AMBIENT -> Category.AMBIENT
            MobCategory.MISC -> 0
            else -> Category.WATER
        }
        when {
            entity is ServerPlayer -> c = Category.PLAYER
            id == "villager" || id == "wandering_trader" -> c = Category.VILLAGER
            id == "iron_golem" || id == "snow_golem" -> c = Category.GOLEM
            id.endsWith("boat") || id.endsWith("raft") || id.contains("minecart") -> c = Category.VEHICLE
            id == "armor_stand" -> c = Category.ARMOR_STAND
            id == "item_frame" || id == "glow_item_frame" || id == "painting" -> c = Category.ITEM_FRAME
            id == "tnt" -> c = Category.TNT
            id == "falling_block" -> c = Category.FALLING
        }
        return EntityKind(id, c)
    }

    private val server get() = platform.server

    private fun level(chunk: ChunkRef): ServerLevel? = server?.level(chunk.world)

    private fun entities(level: ServerLevel, chunk: ChunkRef): List<Entity> {
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return emptyList()
        val x = (chunk.x shl 4).toDouble()
        val z = (chunk.z shl 4).toDouble()
        return level.getEntities(null as Entity?, AABB(x, -4096.0, z, x + 16, 4096.0, z + 16)) { true }
    }

    override fun online(): Collection<PlayerRef> = server?.playerList?.players?.map { it.ref }.orEmpty()

    override fun onlineCount(): Int = server?.playerList?.playerCount ?: 0

    override fun name(player: PlayerRef): String? = server?.player(player)?.scoreboardName

    override fun hasPermission(player: PlayerRef, node: String): Boolean = server?.player(player)?.let { platform.permissions.has(it, node) } == true

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        val s = server ?: return
        val send = Runnable { s.player(player)?.sendSystemMessage(text) }
        if (s.isSameThread) send.run() else s.execute(send)
    }

    override fun console(message: Component) {
        platform.log.info(message.plain())
    }

    override fun location(player: PlayerRef): Location? = server?.player(player)?.location

    override fun census(chunk: ChunkRef, census: Census): Boolean {
        val level = level(chunk) ?: return false
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return false
        for (entity in entities(level, chunk)) census.add(kind(entity), slowed = slowed(entity))
        return true
    }

    override fun countBlocks(chunk: ChunkRef, ids: Set<String>, done: (Map<String, Int>?) -> Unit) {
        val level = level(chunk) ?: return done(null)
        val loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z) ?: return done(null)
        // On the server thread: its sections, skipping the empty ones, one state read per block.
        val counts = HashMap<String, Int>()
        val names = IdentityHashMap<net.minecraft.world.level.block.Block, String?>()
        for (section in loaded.sections) {
            if (section.hasOnlyAir()) continue
            for (y in 0 until 16) for (x in 0 until 16) for (z in 0 until 16) {
                val block = section.getBlockState(x, y, z).block
                val id = names.getOrPut(block) { BuiltInRegistries.BLOCK.getKey(block).path.takeIf { it in ids } } ?: continue
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        done(counts)
    }

    override fun slow(chunk: ChunkRef, select: (EntityKind) -> Boolean, actions: Set<SlowAction>, villagers: Boolean, slow: Boolean): Int {
        val level = level(chunk) ?: return 0
        var changed = 0
        for (entity in entities(level, chunk)) {
            if (entity !is Mob) continue
            val kind = kind(entity)
            if (!select(kind)) continue
            if (slow) {
                if (slowed(entity)) continue
                if (entity.hasCustomName() || (entity is TamableAnimal && entity.isTame) || entity.isLeashed) continue
                if (!villagers && kind.isIn(Category.VILLAGER)) continue
                if (SlowAction.NO_COLLISIONS in actions && entity.team == null) {
                    level.scoreboard.addPlayerToTeam(entity.scoreboardName, team(level))
                    entity.addTag(NO_COLLISIONS)
                    changed++
                }
                entity.addTag(XLimits.SLOWED_TAG)
            } else if (restore(level, entity)) {
                changed++
            }
        }
        return changed
    }

    // Whether [entity] has farm slowing's tag. The tags' getter was renamed between Minecraft lines;
    // adding the tag says whether it was there on every one (and it's taken off again if not).
    private fun slowed(entity: Entity): Boolean {
        if (!entity.addTag(XLimits.SLOWED_TAG)) return true
        entity.removeTag(XLimits.SLOWED_TAG)
        return false
    }

    private fun restore(level: ServerLevel, entity: Entity): Boolean {
        if (!entity.removeTag(XLimits.SLOWED_TAG)) return false
        if (entity.removeTag(NO_COLLISIONS)) {
            val team = level.scoreboard.getPlayerTeam(TEAM)
            if (team != null && entity.team == team) level.scoreboard.removePlayerFromTeam(entity.scoreboardName, team)
        }
        return true
    }

    // The team farm slowing puts mobs in, made the first time, that nothing pushes.
    private fun team(level: ServerLevel): net.minecraft.world.scores.PlayerTeam {
        val board = level.scoreboard
        val team = board.getPlayerTeam(TEAM) ?: board.addPlayerTeam(TEAM)
        if (team.collisionRule != Team.CollisionRule.NEVER) team.collisionRule = Team.CollisionRule.NEVER
        return team
    }

    // The chunks around every online player, Fabric having no list of every loaded chunk here.
    private fun around(players: List<ServerPlayer>, radius: Int): List<ChunkRef> {
        val seen = LinkedHashSet<ChunkRef>()
        for (p in players) {
            val world = p.level().ref
            val cx = p.blockPosition().x shr 4
            val cz = p.blockPosition().z shr 4
            for (x in cx - radius..cx + radius) for (z in cz - radius..cz + radius) seen += ChunkRef(world, x, z)
        }
        return seen.toList()
    }

    override fun restoreAll(done: (Int) -> Unit) {
        platform.scheduler.run(Where.Global) {
            var restored = 0
            val players = server?.playerList?.players.orEmpty()
            for (chunk in around(players, RADIUS)) {
                val level = level(chunk) ?: continue
                for (entity in entities(level, chunk)) if (restore(level, entity)) restored++
            }
            done(restored)
        }
    }

    override fun busiest(what: Int, around: PlayerRef?, done: (List<ChunkStat>) -> Unit) {
        platform.scheduler.run(Where.Global) {
            val all = server?.playerList?.players.orEmpty()
            val players = if (around != null) all.filter { it.ref == around } else all
            val out = ArrayList<ChunkStat>()
            for (chunk in around(players, if (around != null) NEAR else RADIUS)) {
                val level = level(chunk) ?: continue
                val loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z) ?: continue
                out += ChunkStat(chunk, entities(level, chunk).size, loaded.blockEntities.size)
            }
            done(out)
        }
    }

    override fun breakBlock(location: Location) {
        platform.scheduler.run(Where.Global) {
            val level = server?.level(location.world) ?: return@run
            level.destroyBlock(BlockPos(location.blockX, location.blockY, location.blockZ), true)
        }
    }

    override fun teleport(player: PlayerRef, location: Location): Boolean {
        val s = server ?: return false
        val level = s.level(location.world) ?: return false
        platform.scheduler.run(Where.Global) {
            val p = s.player(player) ?: return@run
            val x = location.blockX
            val z = location.blockZ
            var y = location.blockY
            val here = BlockPos(x, y, z)
            if (!level.getBlockState(here).isAir || !level.getBlockState(here.above()).isAir) y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            // Vanilla's own command, the same on every Minecraft line and across dimensions.
            s.commands.performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput(), "execute in ${location.world.name} run tp @s ${x + 0.5} $y ${z + 0.5}")
        }
        return true
    }

    override fun tps(chunk: ChunkRef): Double? = null

    override fun mspt(): Double? = null

    override fun serverSettings(chunk: ChunkRef): List<Pair<String, String>> = emptyList()

    override fun hasPlugin(name: String): Boolean = FabricLoader.getInstance().isModLoaded(name.lowercase())

    // Fabric API's callbacks can't be unregistered: registered once, each doing nothing while its switch is off.
    @Volatile private var chunks: LimitsView? = null
    private val pending = LinkedHashSet<ChunkRef>()
    private var registered = false

    private fun registerOnce() {
        if (registered) return
        registered = true
        ServerEntityEvents.ENTITY_LOAD.register { entity, level ->
            val view = chunks ?: return@register
            if (entity is ServerPlayer) return@register
            val chunk = ChunkRef(level.ref, entity.blockPosition().x shr 4, entity.blockPosition().z shr 4)
            // A chunk's mobs load together: the chunk is checked once, at the next tick.
            if (pending.add(chunk) && pending.size == 1) {
                platform.scheduler.run(Where.Global) {
                    val each = pending.toList()
                    pending.clear()
                    for (c in each) view.chunkLoaded(c)
                }
            }
        }
    }

    override fun listen(switch: Int, view: LimitsView): AutoCloseable = when (switch) {
        Switch.ENTITIES -> Subscribed(hooks.onEntitySpawn(EventPriority.HIGH, listener = Spawns(view, this)))
        Switch.CHUNKS -> {
            registerOnce()
            chunks = view
            AutoCloseable { chunks = null }
        }
        Switch.BLOCKS -> Subscribed(
            hooks.onBlockChange(setOf(ChangeCause.PLAYER), EventPriority.HIGH, listener = Blocks(view, placing = true)),
            hooks.onBlockChange(setOf(ChangeCause.EXPLOSION, ChangeCause.FIRE, ChangeCause.PISTON, ChangeCause.ENTITY), EventPriority.MONITOR, listener = Blocks(view, placing = false)),
        )
        else -> Subscribed(hooks.onEntityChange(EventPriority.HIGH, listener = Tames(view, this)), hooks.onEntityDeath(Deaths(view)))
    }

    private class Subscribed(private vararg val subscriptions: Subscription) : AutoCloseable {
        override fun close() = subscriptions.forEach { it.cancel() }
    }

    private class Spawns(private val view: LimitsView, private val server: FabricLimitsServer) : EventListener<EntitySpawn> {
        override fun on(event: EntitySpawn) {
            val entity = event.nativeEntity as? Entity ?: return
            val kind = server.kind(entity)
            if (kind.categories == 0 || kind.isIn(Category.PLAYER or Category.TNT or Category.FALLING)) return
            val at = Location(event.world, event.x + 0.5, event.y.toDouble(), event.z + 0.5)
            if (!view.spawning(kind, at, SpawnRoutes.of(event.reason, kind), event.player)) event.isCancelled = true
        }
    }

    private class Blocks(private val view: LimitsView, private val placing: Boolean) : EventListener<BlockChange> {
        override fun on(event: BlockChange) {
            BlockChanges.on(view, event, Location(event.world, event.x + 0.5, event.y.toDouble(), event.z + 0.5), placing)
        }
    }

    private class Tames(private val view: LimitsView, private val server: FabricLimitsServer) : EventListener<EntityChange> {
        override fun on(event: EntityChange) {
            if (event.kind != EntityChangeKind.TAMED) return
            val owner = event.player ?: return
            val entity = event.nativeEntity as? Entity ?: return
            if (!view.taming(owner, server.kind(entity), entity.location, entity.uuid.toString())) event.isCancelled = true
        }
    }

    private class Deaths(private val view: LimitsView) : EventListener<EntityDeath> {
        override fun on(event: EntityDeath) {
            val entity = event.nativeEntity as? TamableAnimal ?: return
            if (entity.isTame) view.petGone(entity.uuid.toString())
        }
    }

    private companion object {
        const val TEAM = "xlimits_slowed"
        const val NO_COLLISIONS = "xlimits.no-collisions"
        const val NEAR = 4
        const val RADIUS = 8
    }
}
