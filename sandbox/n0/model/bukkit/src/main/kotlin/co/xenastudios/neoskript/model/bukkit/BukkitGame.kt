package co.xenastudios.neoskript.model.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
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
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Location as BukkitLocation
import org.bukkit.entity.EntityType as BukkitEntityType

/**
 * The model on Paper and Folia (1.21.11's API). Values are Bukkit's own objects; types are
 * found from Bukkit's enums by ordinal, through arrays made once here.
 */
public class BukkitGame(internal val plugin: Plugin) : Game {

    override val platform: String = "${Bukkit.getName()} ${Bukkit.getMinecraftVersion()}"

    // Today's materials only: Bukkit's LEGACY_ ones are 1.12's, which this server maps itself.
    private val materials: List<Material> = Material.entries.filter { !it.name.startsWith("LEGACY_") }
    private val blockMaterials = materials.filter { it.isBlock }
    private val itemMaterials = materials.filter { it.isItem }

    override val blockTypes: Types<BlockType> = Types(blockMaterials.map { id(it) }, ::BlockType)
    override val itemTypes: Types<ItemType> = Types(itemMaterials.map { id(it) }, ::ItemType)

    private val livingTypes = BukkitEntityType.entries.filter { it != BukkitEntityType.UNKNOWN }
    override val entityTypes: Types<EntityType> = Types(livingTypes.map { "minecraft:" + it.name.lowercase() }, ::EntityType)

    // Bukkit's ordinal -> our type, and our index -> Bukkit's: the only lookups at run time.
    internal val blockByOrdinal = arrayOfNulls<BlockType>(Material.entries.size).also { a -> blockMaterials.forEachIndexed { i, m -> a[m.ordinal] = blockTypes[i] } }
    internal val itemByOrdinal = arrayOfNulls<ItemType>(Material.entries.size).also { a -> itemMaterials.forEachIndexed { i, m -> a[m.ordinal] = itemTypes[i] } }
    internal val entityByOrdinal = arrayOfNulls<EntityType>(BukkitEntityType.entries.size).also { a -> livingTypes.forEachIndexed { i, t -> a[t.ordinal] = entityTypes[i] } }

    @Suppress("DEPRECATION")
    private fun id(material: Material): String = material.key.toString()

    // One WorldRef per world, so `location of` doesn't make a new one each time.
    private val refs = ConcurrentHashMap<String, WorldRef>()

    internal fun ref(world: World): WorldRef = refs.computeIfAbsent(world.name, WorldRef::of)

    internal fun bukkit(location: Location): BukkitLocation? =
        Bukkit.getWorld(location.world.name)?.let { BukkitLocation(it, location.x, location.y, location.z, location.yaw, location.pitch) }

    internal fun core(location: BukkitLocation): Location = Location(ref(location.world), location.x, location.y, location.z, location.yaw, location.pitch)

    internal val folia: Boolean = try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    override val worlds: Worlds = object : Worlds {
        override fun isWorld(value: Any?): Boolean = value is World
        override fun ref(world: Any): WorldRef = this@BukkitGame.ref(world as World)
        override fun byRef(ref: WorldRef): Any? = Bukkit.getWorld(ref.name)
        override fun name(world: Any): String = (world as World).name
        override fun all(): List<Any> = Bukkit.getWorlds()
    }

    override val blocks: Blocks = object : Blocks {
        override fun isBlock(value: Any?): Boolean = value is Block
        override fun at(world: Any, x: Int, y: Int, z: Int): Any = (world as World).getBlockAt(x, y, z)
        override fun at(location: Location): Any? = Bukkit.getWorld(location.world.name)?.getBlockAt(location.blockX, location.blockY, location.blockZ)
        override fun type(block: Any): BlockType = blockByOrdinal[(block as Block).type.ordinal]!!
        override fun setType(block: Any, type: BlockType) {
            (block as Block).type = blockMaterials[type.index]
        }
        override fun location(block: Any): Location {
            val b = block as Block
            return Location(ref(b.world), b.x.toDouble(), b.y.toDouble(), b.z.toDouble())
        }
        override fun world(block: Any): Any = (block as Block).world
    }

    override val items: Items = object : Items {
        override fun isItem(value: Any?): Boolean = value is ItemStack
        override fun create(type: ItemType, amount: Int): Any = ItemStack(itemMaterials[type.index], amount)
        override fun type(item: Any): ItemType = itemByOrdinal[(item as ItemStack).type.ordinal]!!
        override fun amount(item: Any): Int = (item as ItemStack).amount
        override fun setAmount(item: Any, amount: Int) {
            (item as ItemStack).amount = amount
        }
    }

    override val entities: Entities = object : Entities {
        override fun isEntity(value: Any?): Boolean = value is Entity
        override fun isLiving(value: Any?): Boolean = value is LivingEntity
        override fun uuid(entity: Any): String = (entity as Entity).uniqueId.toString()
        override fun type(entity: Any): EntityType = entityByOrdinal[(entity as Entity).type.ordinal]!!
        override fun location(entity: Any): Location = core((entity as Entity).location)
        override fun world(entity: Any): Any = (entity as Entity).world
        override fun name(entity: Any): String = (entity as Entity).name
        override fun health(entity: Any): Double = (entity as LivingEntity).health
        override fun setHealth(entity: Any, health: Double) {
            (entity as LivingEntity).health = health
        }
        override fun damage(entity: Any, amount: Double) {
            (entity as LivingEntity).damage(amount)
        }
        override fun kill(entity: Any) {
            if (entity is LivingEntity) entity.health = 0.0 else (entity as Entity).remove()
        }
        override fun teleport(entity: Any, to: Location, done: ((Boolean) -> Unit)?) {
            val target = bukkit(to)
            if (target == null) {
                done?.invoke(false)
                return
            }
            val e = entity as Entity
            // Folia moves an entity between regions only asynchronously; Paper does it at once.
            if (folia) {
                e.teleportAsync(target).thenAccept { done?.invoke(it) }
            } else {
                val moved = e.teleport(target)
                done?.invoke(moved)
            }
        }
        override fun isValid(entity: Any): Boolean = (entity as Entity).isValid
        override fun spawn(type: EntityType, location: Location): Any? {
            val at = bukkit(location) ?: return null
            return at.world.spawnEntity(at, livingTypes[type.index])
        }
    }

    override val players: Players = object : Players {
        override fun isPlayer(value: Any?): Boolean = value is Player
        override fun name(player: Any): String = (player as Player).name
        override fun send(player: Any, text: String) {
            (player as Player).sendMessage(text)
        }
        override fun give(player: Any, item: Any) {
            val p = player as Player
            for (left in p.inventory.addItem(item as ItemStack).values) p.world.dropItem(p.location, left)
        }
        override fun heldItem(player: Any): Any? = (player as Player).inventory.itemInMainHand.takeIf { !it.type.isAir }
        override fun online(): List<Any> = ArrayList<Any>(Bukkit.getOnlinePlayers())
    }

    override val threads: Threads = object : Threads {
        override val regions: Boolean = folia

        override fun owns(value: Any): Boolean {
            if (!folia) return Bukkit.isPrimaryThread()
            return when (value) {
                is Entity -> Bukkit.isOwnedByCurrentRegion(value)
                is Block -> Bukkit.isOwnedByCurrentRegion(value)
                is Location -> bukkit(value)?.let(Bukkit::isOwnedByCurrentRegion) ?: false
                is World -> Bukkit.isGlobalTickThread()
                else -> Bukkit.isGlobalTickThread()
            }
        }

        override fun runOn(value: Any, action: () -> Unit) {
            if (!folia) {
                Bukkit.getScheduler().runTask(plugin, Runnable(action))
                return
            }
            when (value) {
                is Entity -> value.scheduler.run(plugin, { action() }, null)
                is Block -> Bukkit.getRegionScheduler().execute(plugin, value.location, action)
                is Location -> bukkit(value)?.let { Bukkit.getRegionScheduler().execute(plugin, it, action) }
                else -> Bukkit.getGlobalRegionScheduler().execute(plugin, action)
            }
        }
    }

    override val events: BukkitEvents = BukkitEvents(this)
}
