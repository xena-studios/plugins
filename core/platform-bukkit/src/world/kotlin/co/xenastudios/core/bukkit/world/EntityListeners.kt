package co.xenastudios.core.bukkit.world

import co.xenastudios.core.common.world.ContainerChange
import co.xenastudios.core.common.world.EntityChange
import co.xenastudios.core.common.world.EntityChangeKind
import co.xenastudios.core.common.world.EntityDeath
import co.xenastudios.core.common.world.EntitySpawn
import co.xenastudios.core.common.world.SpawnReason
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.entity.Entity
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.event.EventPriority as BukkitPriority

/**
 * Items players put into or take out of block containers: what the container held is noted as
 * it's opened and compared as it's closed, so a whole visit costs two copies of its contents,
 * not an event per click. Heard at `MONITOR`, once the visit is over.
 */
internal class Containers(private val hooks: BukkitWorldHooks) : Listener {

    private class Open(val holder: BlockState, val contents: Array<ItemStack?>)

    private val open = ConcurrentHashMap<UUID, Open>()

    init {
        hooks.on(this, InventoryOpenEvent::class.java, BukkitPriority.MONITOR) { event ->
            val player = event.player as? Player ?: return@on
            if (event.isCancelled) return@on
            val inventory = event.inventory
            val block = blockOf(holderOf(inventory)) ?: return@on
            open[player.uniqueId] = Open(block, copy(inventory.contents))
        }
        hooks.on(this, InventoryCloseEvent::class.java, BukkitPriority.MONITOR) { event ->
            val player = event.player as? Player ?: return@on
            val was = open.remove(player.uniqueId) ?: return@on
            val change = diff(was.contents, event.inventory.contents)
            if (change.isEmpty()) return@on
            val world = was.holder.world
            val ref = hooks.ref(world)
            for ((item, amount) in change) {
                hooks.tell(ContainerChange(ref, was.holder.x, was.holder.y, was.holder.z, BukkitItem(item), amount, hooks.ref(player), player.name, null, world))
            }
        }
    }

    companion object {
        // Paper's getHolder(false) doesn't copy the block's state for the answer; Spigot has only getHolder().
        private val HOLDER: MethodHandle? = try {
            MethodHandles.publicLookup().findVirtual(Inventory::class.java, "getHolder", MethodType.methodType(InventoryHolder::class.java, Boolean::class.javaPrimitiveType))
        } catch (e: ReflectiveOperationException) {
            null
        }

        fun holderOf(inventory: Inventory): InventoryHolder? =
            if (HOLDER != null) HOLDER.invoke(inventory, false) as InventoryHolder? else inventory.holder

        /** The block a container inventory belongs to: a double chest counts as its left half. */
        fun blockOf(holder: InventoryHolder?): BlockState? = when (holder) {
            is DoubleChest -> holder.leftSide as? BlockState
            is BlockState -> holder
            else -> null
        }

        /** How many of each item (one of it, so stacks of the same thing match) there are now compared with before. */
        fun diff(before: Array<ItemStack?>, after: Array<ItemStack?>): Map<ItemStack, Int> {
            val change = HashMap<ItemStack, Int>()
            count(before, -1, change)
            count(after, 1, change)
            change.values.removeIf { it == 0 }
            return change
        }

        private fun count(contents: Array<ItemStack?>, sign: Int, into: HashMap<ItemStack, Int>) {
            for (item in contents) {
                if (item == null || item.type == Material.AIR || item.amount <= 0) continue
                val one = item.clone()
                one.amount = 1
                into[one] = (into[one] ?: 0) + sign * item.amount
            }
        }

        private fun copy(contents: Array<ItemStack?>): Array<ItemStack?> = Array(contents.size) { contents[it]?.clone() }
    }
}

/** What hoppers and droppers move between containers, as `minecraft:hopper`: out of one, into the other. */
internal class Hoppers(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, InventoryMoveItemEvent::class.java, BukkitPriority.MONITOR) { event ->
            if (event.isCancelled) return@on
            val moved = event.item
            val amount = moved.amount
            if (amount <= 0) return@on
            val one = moved.clone().also { it.amount = 1 }
            Containers.blockOf(Containers.holderOf(event.source))?.let { from ->
                hooks.tell(ContainerChange(hooks.ref(from.world), from.x, from.y, from.z, BukkitItem(one), -amount, null, null, HOPPER, from.world))
            }
            Containers.blockOf(Containers.holderOf(event.destination))?.let { to ->
                hooks.tell(ContainerChange(hooks.ref(to.world), to.x, to.y, to.z, BukkitItem(one), amount, null, null, HOPPER, to.world))
            }
        }
    }

    private companion object {
        const val HOPPER = "minecraft:hopper"
    }
}

/**
 * Entities about to spawn: Bukkit's `EntitySpawnEvent` and the creature, item and projectile
 * events under it, with the player behind a spawn where Bukkit says who: who bred the animals
 * (`EntityBreedEvent`, just before the child spawns), who used the spawn egg (their click,
 * just before), and who placed a boat, a minecart, an armour stand or an end crystal
 * (`EntityPlaceEvent`) or a painting or an item frame (`HangingPlaceEvent`), which are heard
 * as those events, [SpawnReason.PLACED]. A boat, minecart or armour stand a dispenser puts out
 * is [SpawnReason.DISPENSER].
 */
internal class Spawns(private val hooks: BukkitWorldHooks) : Listener {

    private class Credit(val player: Player?, val reason: SpawnReason?)

    // Who's behind an entity about to spawn, by its UUID: set just before its spawn event, read there.
    private val credited = ConcurrentHashMap<UUID, Credit>()

    // The player using a spawn egg or a dispenser putting something out on this thread, and when:
    // the spawn follows within the same call.
    private val egg = ThreadLocal<Pair<Player, Long>>()
    private val dispensing = ThreadLocal<Long>()

    init {
        hooks.on(this, EntitySpawnEvent::class.java) { event ->
            val entity = event.entity
            val credit = credited.remove(entity.uniqueId)
            if (credit === PLACED_ALREADY) return@on
            val at = event.location
            val world = at.world ?: return@on
            var reason = if (event is CreatureSpawnEvent) reasonOf(event.spawnReason.name) else SpawnReason.OTHER
            var player = credit?.player
            if (credit?.reason != null) reason = credit.reason
            if (reason == SpawnReason.SPAWN_EGG && player == null) player = recent(egg.get())?.first
            if (reason == SpawnReason.OTHER && recent(dispensing.get()?.let { Pair(Unit, it) }) != null && placeable(entity)) reason = SpawnReason.DISPENSER
            val spawn = EntitySpawn(hooks.ref(world), at.blockX, at.blockY, at.blockZ, Keys.of(entity.type), reason, player?.let(hooks::ref), entity)
            if (event.isCancelled) spawn.isCancelled = true
            event.isCancelled = hooks.tell(spawn).isCancelled
        }
        hooks.on(this, EntityBreedEvent::class.java, BukkitPriority.MONITOR) { event ->
            val breeder = event.breeder as? Player ?: return@on
            if (!event.isCancelled) credited[event.entity.uniqueId] = Credit(breeder, SpawnReason.BREEDING)
        }
        hooks.on(this, PlayerInteractEvent::class.java, BukkitPriority.MONITOR) { event ->
            val item = event.item ?: return@on
            if (item.type.name.endsWith("_SPAWN_EGG")) egg.set(Pair(event.player, System.nanoTime()))
        }
        hooks.on(this, BlockDispenseEvent::class.java, BukkitPriority.MONITOR) { event ->
            if (!event.isCancelled) dispensing.set(System.nanoTime())
        }
        // Marked a draft in 1.13's API, and still there on the newest Paper.
        @Suppress("DEPRECATION")
        hooks.on(this, EntityPlaceEvent::class.java) { event -> placed(event, event.entity, event.player) }
        hooks.on(this, HangingPlaceEvent::class.java) { event -> placed(event, event.entity, event.player) }
    }

    private fun placed(event: org.bukkit.event.Cancellable, entity: Entity, player: Player?) {
        val at = entity.location
        val world = at.world ?: return
        val spawn = EntitySpawn(hooks.ref(world), at.blockX, at.blockY, at.blockZ, Keys.of(entity.type), SpawnReason.PLACED, player?.let(hooks::ref), entity)
        if (event.isCancelled) spawn.isCancelled = true
        event.isCancelled = hooks.tell(spawn).isCancelled
        // Heard once: the spawn event the server fires for it next (an armour stand's) isn't a second spawn.
        if (!event.isCancelled) credited[entity.uniqueId] = PLACED_ALREADY
    }

    private fun <T> recent(was: Pair<T, Long>?): Pair<T, Long>? = was?.takeIf { System.nanoTime() - it.second < RECENT }

    private fun placeable(entity: Entity): Boolean = entity is org.bukkit.entity.Vehicle || entity is org.bukkit.entity.ArmorStand

    private companion object {
        val PLACED_ALREADY = Credit(null, null)

        // Within one tick: the spawn comes in the same call as the click or the dispense.
        val RECENT = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)

        // Bukkit's reasons by name, so a reason a newer server adds (TRIAL_SPAWNER) or an older one lacks needs no class.
        val REASONS: Map<String, SpawnReason> = HashMap<String, SpawnReason>().apply {
            for (name in listOf("NATURAL", "CHUNK_GEN", "JOCKEY", "MOUNT", "PATROL", "RAID", "VILLAGE_INVASION", "TRAP", "REINFORCEMENTS")) put(name, SpawnReason.NATURAL)
            put("SPAWNER", SpawnReason.SPAWNER)
            put("TRIAL_SPAWNER", SpawnReason.SPAWNER)
            put("BREEDING", SpawnReason.BREEDING)
            put("EGG", SpawnReason.EGG)
            put("SPAWNER_EGG", SpawnReason.SPAWN_EGG)
            put("DISPENSE_EGG", SpawnReason.DISPENSER)
            put("COMMAND", SpawnReason.COMMAND)
        }

        fun reasonOf(name: String): SpawnReason = REASONS[name] ?: SpawnReason.OTHER
    }
}

/** Living entities dying, and who's credited with the kill. */
internal class Deaths(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, EntityDeathEvent::class.java, BukkitPriority.MONITOR) { event ->
            val dead = event.entity
            val at = dead.location
            val world = at.world ?: return@on
            val killer = dead.killer
            hooks.tell(EntityDeath(hooks.ref(world), at.blockX, at.blockY, at.blockZ, Keys.of(dead.type), killer?.let(hooks::ref), killer?.name, dead))
        }
    }
}

/** Players taming animals. */
internal class Tames(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, EntityTameEvent::class.java) { event ->
            val player = event.owner as? Player ?: return@on
            val entity = event.entity
            val at = entity.location
            val world = at.world ?: return@on
            val change = EntityChange(hooks.ref(world), at.blockX, at.blockY, at.blockZ, Keys.of(entity.type), EntityChangeKind.TAMED, hooks.ref(player), player.name, entity)
            if (event.isCancelled) change.isCancelled = true
            event.isCancelled = hooks.tell(change).isCancelled
        }
    }
}
