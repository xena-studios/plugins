package co.xenastudios.neoskript.model.fabric

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.ref
import co.xenastudios.neoskript.model.BlockType
import co.xenastudios.neoskript.model.Blocks
import co.xenastudios.neoskript.model.Entities
import co.xenastudios.neoskript.model.EntityType
import co.xenastudios.neoskript.model.Game
import co.xenastudios.neoskript.model.ItemType
import co.xenastudios.neoskript.model.Items
import co.xenastudios.neoskript.model.Players
import co.xenastudios.neoskript.model.Threads
import co.xenastudios.neoskript.model.Types
import co.xenastudios.neoskript.model.Worlds
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.util.IdentityHashMap

/**
 * A block on Fabric, which has no block object: a level and a position. Equal when its place is,
 * as Bukkit's blocks are.
 *
 * [pending] is for a block an event is about to change: the core's world hooks hear a place
 * before the block is in the world (Bukkit's place event comes after), so the event's block reads
 * as the state the change will leave, as it does on Bukkit.
 */
public class FabricBlock(public val level: ServerLevel, public val pos: BlockPos, internal val pending: BlockState? = null) {
    internal val state: BlockState get() = pending ?: level.getBlockState(pos)

    override fun equals(other: Any?): Boolean = other is FabricBlock && other.level === level && other.pos == pos

    override fun hashCode(): Int = 31 * level.hashCode() + pos.hashCode()

    override fun toString(): String = "FabricBlock(${level.dimension()} $pos)"
}

/**
 * The model on a Fabric server (Mojang's names, 1.21.11 and 26.3). Values are Minecraft's own
 * objects, but for blocks ([FabricBlock]). Types are found from the registries by raw id, which
 * is each type's index here: the tables are built in registry order.
 */
public class FabricGame(internal val core: FabricPlatform, internal val server: MinecraftServer) : Game {

    override val platform: String = core.name

    override val blockTypes: Types<BlockType> =
        Types((0 until BuiltInRegistries.BLOCK.size()).map { BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(it)).toString() }, ::BlockType)
    override val itemTypes: Types<ItemType> =
        Types((0 until BuiltInRegistries.ITEM.size()).map { BuiltInRegistries.ITEM.getKey(BuiltInRegistries.ITEM.byId(it)).toString() }, ::ItemType)
    override val entityTypes: Types<EntityType> =
        Types((0 until BuiltInRegistries.ENTITY_TYPE.size()).map { BuiltInRegistries.ENTITY_TYPE.getKey(BuiltInRegistries.ENTITY_TYPE.byId(it)).toString() }, ::EntityType)

    // The levels are made as the server starts and kept: one WorldRef each.
    private val refs = IdentityHashMap<ServerLevel, WorldRef>().also { map -> server.allLevels.forEach { map[it] = it.ref } }

    internal fun ref(level: ServerLevel): WorldRef = refs[level] ?: level.ref

    internal fun level(ref: WorldRef): ServerLevel? = server.allLevels.firstOrNull { ref(it) == ref }

    override val worlds: Worlds = object : Worlds {
        override fun isWorld(value: Any?): Boolean = value is ServerLevel
        override fun ref(world: Any): WorldRef = this@FabricGame.ref(world as ServerLevel)
        override fun byRef(ref: WorldRef): Any? = level(ref)
        override fun name(world: Any): String = this@FabricGame.ref(world as ServerLevel).name
        override fun all(): List<Any> = server.allLevels.toList()
    }

    override val blocks: Blocks = object : Blocks {
        override fun isBlock(value: Any?): Boolean = value is FabricBlock
        override fun at(world: Any, x: Int, y: Int, z: Int): Any = FabricBlock(world as ServerLevel, BlockPos(x, y, z))
        override fun at(location: Location): Any? =
            level(location.world)?.let { FabricBlock(it, BlockPos(location.blockX, location.blockY, location.blockZ)) }
        override fun type(block: Any): BlockType {
            val b = block as FabricBlock
            return blockTypes[BuiltInRegistries.BLOCK.getId(b.state.block)]
        }
        override fun setType(block: Any, type: BlockType) {
            val b = block as FabricBlock
            // 3: tell neighbours and clients, as a player's change does.
            b.level.setBlock(b.pos, BuiltInRegistries.BLOCK.byId(type.index).defaultBlockState(), 3)
        }
        override fun location(block: Any): Location {
            val b = block as FabricBlock
            return Location(ref(b.level), b.pos.x.toDouble(), b.pos.y.toDouble(), b.pos.z.toDouble())
        }
        override fun world(block: Any): Any = (block as FabricBlock).level
    }

    override val items: Items = object : Items {
        override fun isItem(value: Any?): Boolean = value is ItemStack
        override fun create(type: ItemType, amount: Int): Any = ItemStack(BuiltInRegistries.ITEM.byId(type.index), amount)
        override fun type(item: Any): ItemType = itemTypes[BuiltInRegistries.ITEM.getId((item as ItemStack).item)]
        override fun amount(item: Any): Int = (item as ItemStack).count
        override fun setAmount(item: Any, amount: Int) {
            (item as ItemStack).count = amount
        }
    }

    override val entities: Entities = object : Entities {
        override fun isEntity(value: Any?): Boolean = value is Entity
        override fun isLiving(value: Any?): Boolean = value is LivingEntity
        override fun uuid(entity: Any): String = (entity as Entity).uuid.toString()
        override fun type(entity: Any): EntityType = entityTypes[BuiltInRegistries.ENTITY_TYPE.getId((entity as Entity).type)]
        override fun location(entity: Any): Location {
            val e = entity as Entity
            return Location(ref(e.level() as ServerLevel), e.x, e.y, e.z, e.yRot, e.xRot)
        }
        override fun world(entity: Any): Any = (entity as Entity).level()
        override fun name(entity: Any): String = (entity as Entity).name.string
        override fun health(entity: Any): Double = (entity as LivingEntity).health.toDouble()
        override fun setHealth(entity: Any, health: Double) {
            (entity as LivingEntity).health = health.toFloat()
        }
        override fun damage(entity: Any, amount: Double) {
            val e = entity as LivingEntity
            val level = e.level() as ServerLevel
            e.hurtServer(level, level.damageSources().generic(), amount.toFloat())
        }
        override fun kill(entity: Any) {
            val e = entity as Entity
            if (e is LivingEntity) e.kill(e.level() as ServerLevel) else e.discard()
        }
        override fun teleport(entity: Any, to: Location, done: ((Boolean) -> Unit)?) {
            val level = level(to.world)
            val moved = level != null && (entity as Entity).teleportTo(level, to.x, to.y, to.z, emptySet(), to.yaw, to.pitch, false)
            done?.invoke(moved)
        }
        override fun isValid(entity: Any): Boolean {
            val e = entity as Entity
            return !e.isRemoved && (e !is LivingEntity || e.isAlive)
        }
        override fun spawn(type: EntityType, location: Location): Any? {
            val level = level(location.world) ?: return null
            val entity = BuiltInRegistries.ENTITY_TYPE.byId(type.index).create(level, EntitySpawnReason.COMMAND) ?: return null
            entity.setPos(location.x, location.y, location.z)
            return if (level.addFreshEntity(entity)) entity else null
        }
    }

    override val players: Players = object : Players {
        override fun isPlayer(value: Any?): Boolean = value is ServerPlayer
        override fun name(player: Any): String = (player as ServerPlayer).scoreboardName
        override fun send(player: Any, text: String) {
            (player as ServerPlayer).sendSystemMessage(Component.literal(text))
        }
        override fun give(player: Any, item: Any) {
            val p = player as ServerPlayer
            val stack = item as ItemStack
            if (p.inventory.add(stack) && stack.isEmpty) return
            // One of the two calls that differ between 1.21.11 and 26.3 here (core's stonecutter constants).
            //? if item_prediction {
            p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY)
            //?} else
            //p.drop(stack, false, false)
        }
        override fun heldItem(player: Any): Any? = (player as ServerPlayer).mainHandItem.takeIf { !it.isEmpty }
        override fun online(): List<Any> = server.playerList.players.toList()
    }

    // One server thread on Fabric: it owns everything.
    override val threads: Threads = object : Threads {
        override val regions: Boolean = false
        override fun owns(value: Any): Boolean = server.isSameThread
        override fun runOn(value: Any, action: () -> Unit) {
            server.execute(action)
        }
    }

    override val events: FabricEvents = FabricEvents(this)
}
