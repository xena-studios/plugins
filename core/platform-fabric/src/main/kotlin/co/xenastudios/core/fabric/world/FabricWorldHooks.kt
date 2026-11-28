package co.xenastudios.core.fabric.world

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.BlockData
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.ContainerChange
import co.xenastudios.core.common.world.EntityChange
import co.xenastudios.core.common.world.EntityChangeKind
import co.xenastudios.core.common.world.EntityDeath
import co.xenastudios.core.common.world.EntitySpawn
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.core.common.world.SpawnReason
import co.xenastudios.core.common.world.WorldHook
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.core.fabric.ref
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.CompoundContainer
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.material.Fluid
import java.util.IdentityHashMap

/**
 * The world hooks on Fabric (DECISIONS.md, "The world hooks"): what the mod's mixins hand
 * [WorldPoints], told to this plugin's listeners, on the server thread. Deaths come from
 * Fabric API's `AFTER_DEATH`. Made by `FabricPlatform.world`; the mod's `fabric.mod.json` must
 * list its world mixins (`<id>-world.mixins.json`, which `xena.fabric-mod` writes with
 * `worldHooks = true`), or nothing but deaths is ever heard.
 */
public class FabricWorldHooks internal constructor(
    priority: EventPriority,
    log: Log,
    server: () -> MinecraftServer?,
) : WorldHooks(priority, log) {

    override val supported: Set<WorldHook> = WorldHook.entries.toSet()

    @Volatile
    internal var bits: Int = 0

    init {
        Registered.once()
        WorldPoints.add(this, server()?.runningThread)
    }

    override fun start(hook: WorldHook) {
        bits = bits or (1 shl hook.ordinal)
        WorldPoints.refresh()
    }

    override fun stop(hook: WorldHook) {
        bits = bits and (1 shl hook.ordinal).inv()
        WorldPoints.refresh()
        if (hook == WorldHook.CONTAINER) open.clear()
    }

    override fun close() {
        super.close()
        WorldPoints.remove(this)
    }

    internal fun hearsHook(hook: WorldHook): Boolean = hears(hook)

    // Server thread only, as every caller is.
    private val worlds = IdentityHashMap<Level, WorldRef>()

    private fun ref(level: Level): WorldRef = worlds.getOrPut(level) { level.ref }

    /** Tells the listeners of [cause] about a change; returns whether one stopped it. */
    internal fun blockChange(
        level: ServerLevel,
        pos: BlockPos,
        before: BlockState,
        after: BlockState,
        cause: ChangeCause,
        player: ServerPlayer?,
        source: String?,
        cancellable: Boolean,
    ): Boolean {
        if (!hears(cause)) return false
        val event = BlockChange(
            ref(level), pos.x, pos.y, pos.z, Names.data(before), Names.data(after), cause,
            player?.ref, player?.scoreboardName, if (player == null) source else null, cancellable, level,
        )
        return fire(event).isCancelled
    }

    internal fun spawn(level: ServerLevel, entity: Entity, reason: SpawnReason, player: ServerPlayer?): Boolean {
        if (!hears(WorldHook.ENTITY_SPAWN)) return false
        val pos = entity.blockPosition()
        return fire(EntitySpawn(ref(level), pos.x, pos.y, pos.z, Names.entity(entity), reason, player?.ref, entity)).isCancelled
    }

    internal fun tame(entity: Entity, player: ServerPlayer): Boolean {
        if (!hears(WorldHook.ENTITY_CHANGE)) return false
        val pos = entity.blockPosition()
        val event = EntityChange(ref(entity.level()), pos.x, pos.y, pos.z, Names.entity(entity), EntityChangeKind.TAMED, player.ref, player.scoreboardName, entity)
        return fire(event).isCancelled
    }

    internal fun died(entity: LivingEntity, killer: ServerPlayer?) {
        if (!hears(WorldHook.ENTITY_DEATH)) return
        val pos = entity.blockPosition()
        fire(EntityDeath(ref(entity.level()), pos.x, pos.y, pos.z, Names.entity(entity), killer?.ref, killer?.scoreboardName, entity))
    }

    private class Open(val level: ServerLevel, val pos: BlockPos, val contents: List<ItemStack>)

    private val open = IdentityHashMap<ServerPlayer, Open>()

    /** [player] opened the menu of the block at [at]: what its container holds, if it's one. */
    internal fun opened(player: ServerPlayer, at: BlockPos) {
        if (!hears(WorldHook.CONTAINER)) return
        //? if modern_teleport {
        val level = player.level()
        //?} else
        //val level = player.level() as? ServerLevel ?: return
        if (level.getBlockEntity(at) !is Container) return
        open[player] = Open(level, Names.chestHalf(level, at), Containers.contents(player))
    }

    /** [player] is closing their menu: what changed in the container they had open. */
    internal fun closing(player: ServerPlayer) {
        val was = open.remove(player) ?: return
        val change = Containers.diff(was.contents, Containers.contents(player))
        if (change.isEmpty()) return
        val world = ref(was.level)
        for ((item, amount) in change) {
            fire(ContainerChange(world, was.pos.x, was.pos.y, was.pos.z, FabricItem(item), amount, player.ref, player.scoreboardName, null, was.level))
        }
    }

    /** A hopper moved [count] of [item] from [from] (if a container) to [to]. */
    internal fun hopper(from: Container?, to: Container, item: ItemStack, count: Int) {
        if (!hears(WorldHook.CONTAINER_HOPPER)) return
        Containers.blockOf(from)?.let { be ->
            val level = be.level as? ServerLevel ?: return@let
            val pos = Names.chestHalf(level, be.blockPos)
            fire(ContainerChange(ref(level), pos.x, pos.y, pos.z, FabricItem(item), -count, null, null, HOPPER, level))
        }
        Containers.blockOf(to)?.let { be ->
            val level = be.level as? ServerLevel ?: return@let
            val pos = Names.chestHalf(level, be.blockPos)
            fire(ContainerChange(ref(level), pos.x, pos.y, pos.z, FabricItem(item), count, null, null, HOPPER, level))
        }
    }

    private companion object {
        const val HOPPER = "minecraft:hopper"
    }

    // Fabric API's events can't be unregistered, so this mod registers once, for every hooks
    // object it makes; each callback reads WorldPoints' bits first.
    private object Registered {
        @Volatile
        private var done = false

        @Synchronized
        fun once() {
            if (done) return
            done = true
            ServerLifecycleEvents.SERVER_STARTED.register { server -> WorldPoints.serverStarted(server.runningThread) }
            ServerTickEvents.START_SERVER_TICK.register { _ -> WorldPoints.tick() }
            ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
                if (WorldPoints.hears(WorldHook.ENTITY_DEATH)) {
                    val killer = source.entity as? ServerPlayer ?: entity.killCredit as? ServerPlayer
                    WorldPoints.died(entity, killer)
                }
            }
        }
    }
}

/** A block state on Fabric: one per Minecraft state, made once, its text worked out when first read. */
internal class FabricBlock(override val native: BlockState) : BlockData() {
    override val type: String = Names.block(native)
    override val data: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(native)
    }
    override val isAir: Boolean = native.isAir
}

/** An item on Fabric: a copy, of one. */
internal class FabricItem(override val native: ItemStack) : ItemData() {
    override val type: String = BuiltInRegistries.ITEM.getKey(native.item).toString()
}

/** Ids of Minecraft's types, worked out once each, on the server thread. */
internal object Names {
    private val blocks = IdentityHashMap<Block, String>()
    private val entities = IdentityHashMap<EntityType<*>, String>()
    private val fluids = IdentityHashMap<Fluid, String>()
    private val states = IdentityHashMap<BlockState, FabricBlock>()

    fun block(state: BlockState): String = blocks.getOrPut(state.block) { BuiltInRegistries.BLOCK.getKey(state.block).toString() }

    fun entity(entity: Entity): String = entities.getOrPut(entity.type) { EntityType.getKey(entity.type).toString() }

    fun fluid(fluid: Fluid): String = fluids.getOrPut(fluid) { BuiltInRegistries.FLUID.getKey(fluid).toString() }

    fun data(state: BlockState): FabricBlock = states.getOrPut(state) { FabricBlock(state) }

    /** What fire and fluids spread into without destroying anything: air, fire, fluids. */
    fun spreadable(state: BlockState): Boolean = state.isAir || state.block is BaseFireBlock || state.block is LiquidBlock

    /** What a player throws or drops rather than places: projectiles, items, experience. */
    fun thrown(entity: Entity): Boolean = entity is Projectile || entity is ItemEntity || entity is ExperienceOrb

    /** A double chest is the half Minecraft makes its container's first (Bukkit's left side): the one whose type is RIGHT. */
    fun chestHalf(level: Level, pos: BlockPos): BlockPos {
        val state = level.getBlockState(pos)
        if (state.block !is ChestBlock || state.getValue(ChestBlock.TYPE) != ChestType.LEFT) return pos
        return pos.relative(ChestBlock.getConnectedDirection(state))
    }
}

/** A container menu's contents, and what changed between two looks at it. */
internal object Containers {
    /** Copies of what the player's open menu holds outside their own inventory. */
    fun contents(player: ServerPlayer): List<ItemStack> {
        val menu = player.containerMenu
        val out = ArrayList<ItemStack>()
        for (slot in menu.slots) {
            if (slot.container is net.minecraft.world.entity.player.Inventory) continue
            val item = slot.item
            if (!item.isEmpty) out += item.copy()
        }
        return out
    }

    /** Each kind of item, one of it, and how many more (positive) or fewer there are in [after]. */
    fun diff(before: List<ItemStack>, after: List<ItemStack>): List<Pair<ItemStack, Int>> {
        val kinds = ArrayList<ItemStack>()
        val counts = ArrayList<Int>()
        fun add(item: ItemStack, amount: Int) {
            for (i in kinds.indices) {
                if (same(kinds[i], item)) {
                    counts[i] = counts[i] + amount
                    return
                }
            }
            kinds += item.copyWithCount(1)
            counts += amount
        }
        for (item in before) add(item, -item.count)
        for (item in after) add(item, item.count)
        return kinds.indices.filter { counts[it] != 0 }.map { kinds[it] to counts[it] }
    }

    private fun same(a: ItemStack, b: ItemStack): Boolean =
        //? if item_components {
        ItemStack.isSameItemSameComponents(a, b)
        //?} else
        //ItemStack.isSameItemSameTags(a, b)

    /** The block a container belongs to: a double chest is its first half. */
    fun blockOf(container: Container?): BlockEntity? = when (container) {
        is BlockEntity -> container
        is CompoundContainer -> (container as co.xenastudios.core.fabric.mixin.CompoundContainerAccess).`xena$first`() as? BlockEntity
        else -> null
    }
}
