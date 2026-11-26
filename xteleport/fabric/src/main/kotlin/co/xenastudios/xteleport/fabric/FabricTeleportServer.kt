package co.xenastudios.xteleport.fabric

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.PlayerJoinEvent
import co.xenastudios.core.fabric.PlayerQuitEvent
import co.xenastudios.core.fabric.level
import co.xenastudios.core.fabric.location
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.xteleport.BlockKind
import co.xenastudios.xteleport.Blocks
import co.xenastudios.xteleport.Economy
import co.xenastudios.xteleport.Features
import co.xenastudios.xteleport.PlayerEvents
import co.xenastudios.xteleport.SpawnEvents
import co.xenastudios.xteleport.TeleportServer
import co.xenastudios.xteleport.WorldInfo
import co.xenastudios.xteleport.XMoneyEconomy
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks as McBlocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import java.util.IdentityHashMap
import kotlin.concurrent.Volatile
//? if chunk_result {
import net.minecraft.world.level.chunk.status.ChunkStatus
//?} else
//import net.minecraft.world.level.chunk.ChunkStatus
//? if modern_teleport
import net.minecraft.world.entity.Relative

/**
 * What xTeleport does on a Fabric server, with Minecraft's own classes and Fabric API's
 * callbacks, no mixins. One Minecraft line per jar (Stonecutter switches the few names that
 * differ). Everything runs on the server thread, Fabric's only one, except chunk loading and
 * generation, which Minecraft's chunk system does off it: a chunk's future is asked for and settles
 * once it's ready, never waited on. Worlds are dimensions: `minecraft:overworld`, and the Bukkit
 * names `world`, `world_nether` and `world_the_end` for the vanilla three.
 */
internal class FabricTeleportServer(private val platform: FabricPlatform) : TeleportServer {

    override val features: Features = Features(actionBar = true, loading = "in the background (Minecraft's chunk system)", spawnOnJoin = false)

    @Volatile
    private var damage: ((PlayerRef) -> Unit)? = null

    @Volatile
    private var spawns: SpawnEvents? = null

    init {
        // Fabric's callbacks can't be unregistered: each is registered once, and does nothing while
        // no one listens ([watchDamage], [watchSpawn]).
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            val hurt = damage
            if (hurt != null && entity is ServerPlayer) hurt(entity.ref)
            true
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { old, now, alive ->
            val listener = spawns
            // Deaths only, without a bed or anchor: leaving the End goes where the game sends it.
            if (listener != null && !alive && respawnPoint(old) == null) {
                listener.respawn(now.ref)?.let { teleport(now.ref, it) {} }
            }
        }
    }

    private fun server() = platform.server

    private fun player(ref: PlayerRef): ServerPlayer? = server()?.player(ref)

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun online(): List<PlayerRef> = server()?.playerList?.players?.map { it.ref }.orEmpty()

    // Sent on the server thread, from wherever it's asked.
    private fun onServer(work: () -> Unit) {
        val server = server() ?: return
        if (server.isSameThread) work() else server.execute(work)
    }

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        onServer { player(player)?.sendSystemMessage(text) }
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        onServer {
            val p = player(player) ?: return@onServer
            //? if overlay_message {
            p.sendOverlayMessage(text)
            //?} else
            //p.displayClientMessage(text, true)
        }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean {
        val p = player(player) ?: return false
        return platform.permissions.has(p, node)
    }

    // Fabric's permissions API can only be asked about one node at a time: these are asked.
    override fun homeNumbers(player: PlayerRef): Int {
        val p = player(player) ?: return 0
        for (n in NUMBERS) if (platform.permissions.has(p, "xteleport.homes.$n")) return n
        return 0
    }

    override fun location(player: PlayerRef): Location? = player(player)?.location

    override fun bed(player: PlayerRef): Location? {
        val p = player(player) ?: return null
        val (dimension, pos) = respawnPoint(p) ?: return null
        val level = server()?.allLevels?.firstOrNull { it.dimension() == dimension } ?: return null
        return Location(level.ref, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
    }

    // Where the player set their bed or anchor, without loading its chunk.
    private fun respawnPoint(p: ServerPlayer): Pair<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, BlockPos>? {
        //? if respawn_data {
        val data = p.respawnConfig?.respawnData() ?: return null
        return data.dimension() to data.pos()
        //?} else {
        /*val pos = p.respawnPosition ?: return null
        return p.respawnDimension to pos
        *///?}
    }

    override fun standOn(block: Location): Location? {
        val level = server()?.level(block.world) ?: return null
        val state = level.getBlockState(BlockPos(block.blockX, block.blockY, block.blockZ))
        val bed = state.`is`(BlockTags.BEDS)
        if (!bed && !state.`is`(McBlocks.RESPAWN_ANCHOR)) return null
        // A bed is just over half a block high; an anchor is a full block.
        return Location(block.world, block.blockX + 0.5, block.blockY + if (bed) 0.5625 else 1.0, block.blockZ + 0.5)
    }

    override fun worlds(): List<WorldRef> = server()?.allLevels?.map { it.ref }.orEmpty()

    private fun level(name: String): ServerLevel? {
        val server = server() ?: return null
        val id = ALIASES[name] ?: if (':' in name) name else "minecraft:$name"
        return server.allLevels.firstOrNull { it.ref.name == id }
    }

    override fun world(name: String): WorldInfo? {
        val level = level(name) ?: return null
        val type = level.dimensionType()
        val border = level.worldBorder
        //? if modern_teleport {
        val min = level.minY
        val max = level.maxY + 1
        //?} else {
        /*val min = level.minBuildHeight
        val max = level.maxBuildHeight
        *///?}
        //? if respawn_data {
        val spawn = level.respawnData.pos()
        //?} else
        //val spawn = level.sharedSpawnPos
        return WorldInfo(
            level.ref, min, max, minOf(max, min + type.logicalHeight()), type.hasCeiling(), level.dimension() == net.minecraft.world.level.Level.END,
            Location(level.ref, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5), border.centerX, border.centerZ, border.size,
        )
    }

    override fun loadChunk(world: WorldRef, chunkX: Int, chunkZ: Int, generate: Boolean): Pending<Boolean> {
        val done = newPending<Boolean>()
        onServer {
            val level = server()?.level(world)
            if (level == null) {
                done.resolve(false)
                return@onServer
            }
            if (generate) full(level, chunkX, chunkZ, done) else generated(level, chunkX, chunkZ, done)
        }
        return done
    }

    // The chunk, fully loaded (or generated), from Minecraft's chunk system: settles on the server thread.
    private fun full(level: ServerLevel, x: Int, z: Int, done: Pending<Boolean>) {
        level.chunkSource.getChunkFuture(x, z, ChunkStatus.FULL, true).whenComplete { result, error ->
            //? if chunk_result {
            done.resolve(error == null && result != null && result.isSuccess)
            //?} else
            //done.resolve(error == null && result != null && result.left().isPresent)
        }
    }

    // A chunk never generated isn't made: read as far as what's saved (EMPTY makes nothing), then loaded whole if it's done.
    private fun generated(level: ServerLevel, x: Int, z: Int, done: Pending<Boolean>) {
        level.chunkSource.getChunkFuture(x, z, ChunkStatus.EMPTY, true).whenComplete { result, error ->
            //? if chunk_result {
            val chunk = if (error == null && result != null && result.isSuccess) result.orElse(null) else null
            //?} else
            //val chunk = if (error == null && result != null) result.left().orElse(null) else null
            if (chunk == null || chunk.highestGeneratedStatus != ChunkStatus.FULL) done.resolve(false) else onServer { full(level, x, z, done) }
        }
    }

    override fun blocks(world: WorldRef): Blocks? {
        val level = server()?.level(world) ?: return null
        return LevelBlocks(level)
    }

    private inner class LevelBlocks(private val level: ServerLevel) : Blocks {
        private val pos = BlockPos.MutableBlockPos()

        override fun kind(x: Int, y: Int, z: Int): Int {
            pos.set(x, y, z)
            val state = level.getBlockState(pos)
            if (state.isAir) return BlockKind.AIR
            var kind = kinds.of(state)
            //? if modern_teleport {
            if (state.isSolidRender) kind = kind or BlockKind.FULL
            //?} else
            //if (state.isSolidRender(level, pos)) kind = kind or BlockKind.FULL
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) kind = kind or BlockKind.WATER
            return kind
        }

        // The heightmap gives the first free block above the highest that stops movement, so one below it.
        override fun highest(x: Int, z: Int): Int {
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1
            //? if modern_teleport {
            val min = level.minY
            //?} else {
            /*val min = level.minBuildHeight
            *///?}
            return if (y < min || level.getBlockState(BlockPos(x, y, z)).isAir) Int.MIN_VALUE else y
        }

        override fun biome(x: Int, y: Int, z: Int): String =
            level.getBiome(BlockPos(x, y, z)).unwrapKey().map { key ->
                //? if identifier {
                key.identifier().path
                //?} else
                //key.location().path
            }.orElse("")
    }

    private val kinds = Kinds()

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        val p = player(player) ?: return done(false)
        val level = server()?.level(to.world) ?: return done(false)
        //? if modern_teleport {
        val moved = p.teleportTo(level, to.x, to.y, to.z, emptySet<Relative>(), to.yaw, to.pitch, true)
        //?} else {
        /*p.teleportTo(level, to.x, to.y, to.z, to.yaw, to.pitch)
        val moved = true
        *///?}
        done(moved)
    }

    override fun watchPlayers(listener: PlayerEvents): AutoCloseable {
        val joins = platform.events.subscribe(PlayerJoinEvent.TYPE) { listener.joined(it.player, it.name) }
        val quits = platform.events.subscribe(PlayerQuitEvent.TYPE) { listener.quit(it.player) }
        return AutoCloseable {
            joins.cancel()
            quits.cancel()
        }
    }

    // Fabric has no event to place a joining player, so the spawn is a teleport just after they join.
    override fun watchSpawn(listener: SpawnEvents): AutoCloseable {
        spawns = listener
        val joins = platform.events.subscribe(PlayerJoinEvent.TYPE) { join ->
            val p = player(join.player) ?: return@subscribe
            val first = p.stats.getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0
            listener.spawnOnJoin(join.player, first)?.let { teleport(join.player, it) {} }
        }
        return AutoCloseable {
            spawns = null
            joins.cancel()
        }
    }

    override fun watchDamage(hurt: (PlayerRef) -> Unit): AutoCloseable {
        damage = hurt
        return AutoCloseable { damage = null }
    }

    // xMoney's API, found as a cost is first needed: Fabric mods share one class loader.
    @Volatile
    private var economy: XMoneyEconomy? = null

    override fun economy(): Economy? = economy ?: XMoneyEconomy.find(FabricTeleportServer::class.java.classLoader)?.also { economy = it }

    private companion object {
        val ALIASES = mapOf("world" to "minecraft:overworld", "world_nether" to "minecraft:the_nether", "world_the_end" to "minecraft:the_end")

        // The xteleport.homes.<n> asked about, highest first.
        val NUMBERS: IntArray = (listOf(1000, 500, 250, 200, 150, 100, 75, 50) + (40 downTo 1)).toIntArray()
    }
}

/** Each block's [BlockKind], worked out once per block from Minecraft's tags and flags. */
internal class Kinds {
    private val known = IdentityHashMap<Block, Int>()

    fun of(state: BlockState): Int {
        val block = state.block
        known[block]?.let { return it }
        return work(state).also { known[block] = it }
    }

    // isSolid is Minecraft's own "solid" flag, deprecated since 1.20 but what Bukkit's isSolid reads.
    @Suppress("DEPRECATION")
    private fun work(state: BlockState): Int {
        val block = state.block
        var kind = 0
        if (state.isSolid) kind = kind or BlockKind.SOLID
        val fluid = state.fluidState
        if (fluid.`is`(FluidTags.LAVA)) kind = kind or BlockKind.HURTS or BlockKind.LIQUID
        if (fluid.`is`(FluidTags.WATER) && !state.hasProperty(BlockStateProperties.WATERLOGGED)) kind = kind or BlockKind.WATER or BlockKind.LIQUID
        if (block == McBlocks.BUBBLE_COLUMN || block == McBlocks.KELP || block == McBlocks.KELP_PLANT || block == McBlocks.SEAGRASS || block == McBlocks.TALL_SEAGRASS) kind = kind or BlockKind.WATER
        if (block in HURTS) kind = kind or BlockKind.HURTS
        if (state.`is`(BlockTags.CLIMBABLE)) kind = kind or BlockKind.CLIMBABLE
        if (state.`is`(BlockTags.LEAVES)) kind = kind or BlockKind.LEAVES
        if (block == McBlocks.BEDROCK) kind = kind or BlockKind.BEDROCK
        if (block == McBlocks.SOUL_SAND || block == McBlocks.MUD) kind = kind or BlockKind.SHORT
        if (block == McBlocks.FARMLAND || block == McBlocks.DIRT_PATH) kind = kind or BlockKind.SHORTER
        return kind
    }

    private companion object {
        val HURTS: Set<Block> = setOf(
            McBlocks.FIRE, McBlocks.SOUL_FIRE, McBlocks.MAGMA_BLOCK, McBlocks.CAMPFIRE, McBlocks.SOUL_CAMPFIRE, McBlocks.CACTUS,
            McBlocks.SWEET_BERRY_BUSH, McBlocks.WITHER_ROSE, McBlocks.POWDER_SNOW, McBlocks.POINTED_DRIPSTONE, McBlocks.LAVA,
        )
    }
}
